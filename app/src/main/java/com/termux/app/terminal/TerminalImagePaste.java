package com.termux.app.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Delivers an image pasted into a session (from the keyboard or the clipboard) to the program
 * running in it. A terminal only carries text, so the image is handed over as a file and the guest
 * helper {@value #HELPER} puts it where that program reads images: the guest's own wl-paste for a
 * local session, or, when the session's foreground process is an SSH connection, that host's
 * clipboard over a connection with the same destination and options. TermuxPP then
 * sends Ctrl+V, the image paste key of Claude Code and similar tools, or pastes the text the helper
 * returns (a path when the host has no wl-copy).
 */
final class TerminalImagePaste {
    private static final String LOG_TAG = "TerminalImagePaste";
    static final String HELPER = "termuxpp-image-paste";
    /** Read by the guest's wl-paste, see pkgs/nixpp/clipboard.nix. */
    static final File HANDOVER_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".cache/termuxpp-clipboard");
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long HELPER_TIMEOUT_SECONDS = 60;
    private static final int OUTPUT_LIMIT = 16 * 1024;
    private static final String CTRL_V = "\u0016";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private TerminalImagePaste() { }

    /** What to send to the session once the image was delivered. */
    static final class Result {
        @Nullable final String error;
        @Nullable final String text;

        private Result(@Nullable String error, @Nullable String text) {
            this.error = error;
            this.text = text;
        }

        static Result key() { return new Result(null, null); }
        static Result text(@NonNull String text) { return new Result(null, text); }
        static Result failed(@NonNull String error) { return new Result(error, null); }
    }

    /** An image on the clipboard, or {@code null} when it holds text or nothing. */
    @Nullable
    static Uri clipboardImage(@NonNull Context context, @NonNull String[] mimeType) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        Uri uri = clip.getItemAt(0).getUri();
        if (uri == null) return null;
        String type = context.getContentResolver().getType(uri);
        ClipDescription description = clip.getDescription();
        for (int i = 0; type == null && description != null && i < description.getMimeTypeCount(); i++) {
            if (description.getMimeType(i).startsWith("image/")) type = description.getMimeType(i);
        }
        if (type == null || !type.startsWith("image/")) return null;
        mimeType[0] = type;
        return uri;
    }

    /**
     * Pastes the image into the session in the background.
     *
     * @param release Runs once the image was read, for example to release a keyboard's grant.
     */
    static void paste(@NonNull Activity activity, @NonNull TerminalSession session, @NonNull Uri uri,
                      @NonNull String mimeType, @Nullable Runnable release) {
        Context context = activity.getApplicationContext();
        int pid = session.isRunning() ? session.getPid() : -1;
        EXECUTOR.execute(() -> {
            Result result;
            File image = null;
            try {
                image = store(context, uri, mimeType);
            } catch (IOException | SecurityException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not read the pasted image", e);
            } finally {
                if (release != null) release.run();
            }
            result = image == null ? Result.failed("Could not read the pasted image")
                : deliver(context, pid, mimeType, image);
            activity.runOnUiThread(() -> apply(activity, session, result));
        });
    }

    private static void apply(@NonNull Activity activity, @NonNull TerminalSession session, @NonNull Result result) {
        if (result.error != null) {
            Toast.makeText(activity, result.error, Toast.LENGTH_LONG).show();
            return;
        }
        if (!session.isRunning()) return;
        if (result.text == null) session.write(CTRL_V);
        else if (session.getEmulator() != null) session.getEmulator().paste(result.text);
    }

    /** Copies the image into the handover directory and returns its file. */
    @NonNull
    static File store(@NonNull Context context, @NonNull Uri uri, @NonNull String mimeType) throws IOException {
        return store(context, uri, mimeType, HANDOVER_DIR);
    }

    @NonNull
    static File store(@NonNull Context context, @NonNull Uri uri, @NonNull String mimeType, @NonNull File dir)
        throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        // Only the app's user may read pasted images.
        dir.setReadable(false, false);
        dir.setExecutable(false, false);
        dir.setReadable(true, true);
        dir.setWritable(true, true);
        dir.setExecutable(true, true);
        File partial = new File(dir, ".image.partial");
        File image = new File(dir, "image");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(partial)) {
            if (in == null) throw new IOException("No content at " + uri);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) throw new IOException("The image is larger than " + (MAX_BYTES >> 20) + " MiB");
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw e;
        }
        if (!partial.renameTo(image)) throw new IOException("Cannot write " + image);
        try (OutputStream out = new FileOutputStream(new File(dir, "type"))) {
            out.write((mimeType + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return image;
    }

    /** Runs the guest helper and maps its last line ("key" or "text PAYLOAD") to a result. */
    @NonNull
    static Result deliver(@NonNull Context context, int pid, @NonNull String mimeType, @NonNull File image) {
        if (!TermuxNix.isProvisioned() || !TermuxNix.isReady()) return Result.text(image.getPath());
        List<String> command = new ArrayList<>();
        command.add(TermuxNix.NIXPP_PATH);
        command.add("enter");
        command.add("--");
        command.add(HELPER);
        command.add("--type");
        command.add(mimeType);
        if (pid > 0) {
            // The helper finds the session's SSH connection below its shell process.
            command.add("--pid");
            command.add(Integer.toString(pid));
        }
        command.add(image.getPath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            process.getOutputStream().close();
            String output = readBounded(process.getInputStream());
            if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Result.failed("Pasting the image timed out");
            }
            return parse(process.exitValue(), output);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not run " + HELPER, e);
            return Result.failed("Could not run " + HELPER + ": " + e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Result.failed("Pasting the image was interrupted");
        }
    }

    @NonNull
    static Result parse(int exitCode, @NonNull String output) {
        String[] lines = output.trim().split("\n");
        String last = lines.length == 0 ? "" : lines[lines.length - 1].trim();
        if (exitCode == 0 && last.equals("key")) return Result.key();
        if (exitCode == 0 && last.startsWith("text ")) return Result.text(last.substring(5));
        if (exitCode == 127) return Result.failed("Image paste needs " + HELPER + " in the Nix guest");
        String reason = last.isEmpty() ? "exit status " + exitCode : last;
        return Result.failed("Could not paste the image: " + reason);
    }

    private static String readBounded(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() < OUTPUT_LIMIT) out.write(buffer, 0, Math.min(read, OUTPUT_LIMIT - out.size()));
        }
        return out.toString("UTF-8");
    }
}
