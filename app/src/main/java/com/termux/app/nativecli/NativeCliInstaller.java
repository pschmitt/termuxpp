package com.termux.app.nativecli;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Installs and updates the files of a {@link NativeManifest} in the native prefix.
 *
 * <p>The installer is conservative. It writes only paths it created before (recorded with a digest in
 * {@code var/lib/tpp/native-install.json}) or that do not exist yet, replaces them atomically
 * (temporary file in the same directory, then rename), and never touches the dpkg database or runs
 * {@code dpkg}/{@code apt}. A path that holds something else (a file of a Debian package, a file the
 * user edited, a directory) is left alone and reported in {@link Report#issues}. Stale dpkg entries of
 * the retired {@code termux-api}/{@code termux-am} helper packages are only reported.
 */
public final class NativeCliInstaller {

    public static final String STATE_FILE = "var/lib/tpp/native-install.json";

    /** Where the manifest gets its files from: the APK in the app, a temporary tree in tests. */
    public interface Source {
        InputStream openAsset(String path) throws IOException;

        /** The directory that holds the extracted native libraries of the APK. */
        File libraryDirectory();
    }

    public static final class Report {
        public int installed;
        public int updated;
        public int unchanged;
        public int removed;
        public final List<String> issues = new ArrayList<>();

        @Override
        public String toString() {
            return "installed=" + installed + " updated=" + updated + " unchanged=" + unchanged
                + " removed=" + removed + " issues=" + issues.size();
        }
    }

    /** What a path should hold, or what the state file recorded for it. */
    private static final class Content {
        /** A path that holds something else (a directory, a device): never ours. */
        static final Content NONE = new Content(false, "\0other", null, false);

        final boolean symlink;
        /** SHA-256 for a file, link target for a symlink. */
        final String id;
        final byte[] bytes;
        final boolean executable;

        Content(boolean symlink, String id, byte[] bytes, boolean executable) {
            this.symlink = symlink;
            this.id = id;
            this.bytes = bytes;
            this.executable = executable;
        }

        boolean sameAs(Content other) {
            return other != null && symlink == other.symlink && id.equals(other.id);
        }
    }

    private static final Object LOCK = new Object();

    private NativeCliInstaller() {
    }

    public static Report install(File prefix, NativeManifest manifest, Source source) throws IOException {
        synchronized (LOCK) {
            return installLocked(prefix, manifest, source);
        }
    }

    private static Report installLocked(File prefix, NativeManifest manifest, Source source)
        throws IOException {
        Report report = new Report();
        Path root = prefix.toPath().toRealPath();
        String prefixPath = prefix.getPath();

        File stateFile = new File(prefix, STATE_FILE);
        Map<String, Content> recorded = readState(stateFile, report);

        Set<String> absolutePaths = new HashSet<>();
        for (NativeManifest.Entry entry : manifest.entries) absolutePaths.add(prefixPath + "/" + entry.path);
        Map<String, String> owners = Collections.emptyMap();
        DpkgDatabase dpkg = new DpkgDatabase(prefix);
        try {
            owners = dpkg.owners(absolutePaths);
        } catch (IOException e) {
            report.issues.add("Could not read the dpkg file lists: " + e.getMessage());
        }

        Map<String, Content> state = new TreeMap<>();
        Set<String> inManifest = new HashSet<>();
        for (NativeManifest.Entry entry : manifest.entries) {
            inManifest.add(entry.path);
            Content desired;
            try {
                desired = desired(entry, prefixPath, source, report);
            } catch (IOException e) {
                report.issues.add(entry.path + ": " + e.getMessage());
                keepRecord(state, recorded, entry.path);
                continue;
            }
            Path destination = root.resolve(entry.path);
            Content current = current(destination);
            Content record = recorded.get(entry.path);
            String owner = owners.get(prefixPath + "/" + entry.path);

            if (desired == null) {
                // An optional library the build does not have: drop a link of ours that now dangles.
                if (record != null && record.sameAs(current)) {
                    delete(destination);
                    report.removed++;
                } else if (record != null) {
                    keepRecord(state, recorded, entry.path);
                }
                continue;
            }
            if (current == null) {
                if (!parentIsInside(root, destination, report, entry.path)) continue;
                write(destination, desired);
                state.put(entry.path, desired);
                if (record == null) report.installed++;
                else report.updated++;
                if (owner != null) {
                    report.issues.add(entry.path + ": a file of package " + owner
                        + " in the dpkg database that no longer exists was installed again; dpkg still lists it");
                }
                continue;
            }
            if (current.sameAs(desired)) {
                report.unchanged++;
                if (owner == null) state.put(entry.path, desired);
                continue;
            }
            if (owner == null && current != Content.NONE && current.sameAs(record)) {
                if (!parentIsInside(root, destination, report, entry.path)) {
                    keepRecord(state, recorded, entry.path);
                    continue;
                }
                write(destination, desired);
                state.put(entry.path, desired);
                report.updated++;
                continue;
            }
            // Something that is not ours, or that we installed and somebody changed.
            if (owner != null) {
                report.issues.add(entry.path + ": owned by package " + owner + ", left unchanged");
            } else if (record != null) {
                report.issues.add(entry.path + ": modified after it was installed, left unchanged");
                state.put(entry.path, record);
            } else {
                report.issues.add(entry.path + ": exists and was not installed by TermuxPP, left unchanged");
            }
        }

        // Files that earlier versions installed and the manifest no longer lists.
        for (Map.Entry<String, Content> old : recorded.entrySet()) {
            if (inManifest.contains(old.getKey()) || !NativeManifest.isSafeRelativePath(old.getKey())) continue;
            Path destination = root.resolve(old.getKey());
            if (old.getValue().sameAs(current(destination))) {
                delete(destination);
                report.removed++;
            }
        }

        try {
            Set<String> names = new LinkedHashSet<>();
            names.add("termux-api");
            names.add("termux-am");
            for (DpkgDatabase.Package pkg : dpkg.stale(names)) {
                String advice = pkg.name.equals("termux-am")
                    ? "it provides bin/am, which the native API client runs: keep it until the app ships a replacement"
                    : "it is no longer built; once nothing needs it, remove it with apt (this is never done automatically)";
                report.issues.add("Stale dpkg entry " + pkg.name + " " + pkg.version + ": " + pkg.missing
                    + " of " + pkg.listed + " listed files are missing; " + advice);
            }
        } catch (IOException e) {
            report.issues.add("Could not read the dpkg status: " + e.getMessage());
        }

        writeState(stateFile, state, report);
        return report;
    }

    private static void keepRecord(Map<String, Content> state, Map<String, Content> recorded, String path) {
        Content record = recorded.get(path);
        if (record != null) state.put(path, record);
    }

    private static Content desired(NativeManifest.Entry entry, String prefixPath, Source source, Report report)
        throws IOException {
        switch (entry.type) {
            case SYMLINK:
                return new Content(true, entry.source, null, false);
            case LIB: {
                File library = new File(source.libraryDirectory(), entry.source);
                if (!library.isFile()) {
                    if (!entry.optional) report.issues.add(entry.path + ": the APK has no " + entry.source);
                    return null;
                }
                return new Content(true, library.getAbsolutePath(), null, false);
            }
            default: {
                byte[] bytes = read(source, entry.source);
                if (entry.substitute) {
                    String text = new String(bytes, StandardCharsets.UTF_8)
                        .replace(NativeManifest.PREFIX_TOKEN, prefixPath);
                    if (text.contains(NativeManifest.PREFIX_TOKEN))
                        throw new IOException("unresolved prefix token");
                    bytes = text.getBytes(StandardCharsets.UTF_8);
                }
                return new Content(false, sha256(bytes), bytes, entry.executable);
            }
        }
    }

    /** The current content of a path, or {@code null} when nothing is there. */
    private static Content current(Path path) throws IOException {
        if (Files.isSymbolicLink(path))
            return new Content(true, Files.readSymbolicLink(path).toString(), null, false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return Content.NONE;
        return new Content(false, sha256(Files.readAllBytes(path)), null, false);
    }

    private static boolean parentIsInside(Path root, Path destination, Report report, String path)
        throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        if (!parent.toRealPath().startsWith(root)) {
            report.issues.add(path + ": the parent directory leads out of the prefix, skipped");
            return false;
        }
        return true;
    }

    private static void write(Path destination, Content content) throws IOException {
        Path directory = destination.getParent();
        if (content.symlink) {
            Path staging = directory.resolve(".tpp-native-" + System.nanoTime() + ".tmp");
            try {
                Files.createSymbolicLink(staging, java.nio.file.Paths.get(content.id));
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(staging);
            }
            return;
        }
        File staging = File.createTempFile(".tpp-native-", ".tmp", directory.toFile());
        try {
            try (OutputStream out = Files.newOutputStream(staging.toPath())) {
                out.write(content.bytes);
            }
            staging.setReadable(true, false);
            staging.setWritable(true, true);
            staging.setExecutable(content.executable, false);
            Files.move(staging.toPath(), destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            staging.delete();
        }
    }

    private static void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private static byte[] read(Source source, String asset) throws IOException {
        try (InputStream in = source.openAsset(asset)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) != -1) buffer.write(chunk, 0, count);
            return buffer.toByteArray();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Content> readState(File file, Report report) {
        Map<String, Content> result = new HashMap<>();
        if (!file.isFile()) return result;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONObject files = root.getJSONObject("files");
            Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                JSONObject item = files.getJSONObject(path);
                if (!NativeManifest.isSafeRelativePath(path)) continue;
                if (item.has("target")) result.put(path, new Content(true, item.getString("target"), null, false));
                else result.put(path, new Content(false, item.getString("sha256"), null, false));
            }
        } catch (IOException | JSONException e) {
            // Without a usable record nothing counts as ours: existing files are left alone.
            report.issues.add("Ignored the unreadable install record: " + e.getMessage());
            result.clear();
        }
        return result;
    }

    private static void writeState(File file, Map<String, Content> state, Report report) throws IOException {
        try {
            JSONObject files = new JSONObject();
            for (Map.Entry<String, Content> entry : state.entrySet()) {
                JSONObject item = new JSONObject();
                if (entry.getValue().symlink) item.put("target", entry.getValue().id);
                else item.put("sha256", entry.getValue().id);
                files.put(entry.getKey(), item);
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("files", files);
            root.put("issues", new org.json.JSONArray(report.issues));
            Files.createDirectories(file.getParentFile().toPath());
            File staging = File.createTempFile(".tpp-native-state-", ".tmp", file.getParentFile());
            try {
                Files.write(staging.toPath(), (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                staging.delete();
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}
