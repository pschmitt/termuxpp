package com.termux.app.nativecli;

import android.content.Context;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the native {@code tpp} CLI that the APK carries (see {@code app/tpp-native.gradle}) into the
 * prefix, once per process, off the main thread. The APK is the single source of truth, so updating the
 * app updates the CLI; the installer's rules (never overwrite foreign or edited files, never touch the
 * dpkg database) are in {@link NativeCliInstaller}.
 */
public final class NativeCliUpdater {

    private static final String LOG_TAG = "NativeCliUpdater";
    private static final String MANIFEST = "tpp-native/manifest.json";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean done;

    private NativeCliUpdater() {
    }

    /** Does nothing while the prefix is not set up yet (the bootstrap installs later); a later call retries. */
    public static void installAsync(Context context) {
        if (done) return;
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!new File(prefix, "bin").isDirectory()) return;
        if (!RUNNING.compareAndSet(false, true)) return;

        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                run(app, prefix);
                done = true;
            } finally {
                RUNNING.set(false);
            }
        }, "tpp-native-install");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(Context context, File prefix) {
        try {
            String json;
            try (InputStream in = context.getAssets().open(MANIFEST)) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) != -1) buffer.write(chunk, 0, count);
                json = buffer.toString("UTF-8");
            }
            NativeManifest manifest = NativeManifest.parse(json);
            File libraries = new File(context.getApplicationInfo().nativeLibraryDir);
            NativeCliInstaller.Report report = NativeCliInstaller.install(prefix, manifest,
                new NativeCliInstaller.Source() {
                    @Override
                    public InputStream openAsset(String path) throws IOException {
                        return context.getAssets().open(path);
                    }

                    @Override
                    public File libraryDirectory() {
                        return libraries;
                    }
                });
            Logger.logInfo(LOG_TAG, "Native tpp CLI: " + report);
            for (String issue : report.issues) Logger.logWarn(LOG_TAG, issue);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Could not install the native tpp CLI: " + e);
        }
    }
}
