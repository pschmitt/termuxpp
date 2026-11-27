package com.termux.app.settings;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.file.filesystem.FileType;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigMigration;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxYamlConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads and changes single settings in {@code config.yaml}, for the settings screens. A change edits only
 * that setting and keeps the rest of the file, comments included; a former path of the setting is replaced
 * by the current one. A legacy properties file is migrated first, as the app does on start.
 *
 * <p>The file may be a symlink (dotfile managers link it) for reading. A change refuses to replace a symlink,
 * so that it does not silently cut the file loose from the dotfiles it is managed in.
 */
public final class TermuxPropertiesEditor {
    private final File target;
    private final List<String> legacyPaths;

    public TermuxPropertiesEditor() {
        this(new File(TermuxNix.configPaths(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST).get(0)),
            TermuxNix.configPaths(TermuxConstants.TERMUX_LEGACY_PROPERTIES_FILE_PATHS_LIST));
    }

    /**
     * @param target The YAML settings file.
     * @param legacyPaths Legacy properties files to migrate from when the YAML file does not exist yet.
     */
    public TermuxPropertiesEditor(@NonNull File target, @NonNull List<String> legacyPaths) {
        this.target = target;
        this.legacyPaths = legacyPaths;
    }

    private static FileType fileType(String path) throws IOException {
        return fileType(path, false);
    }

    private static FileType fileType(String path, boolean follow) throws IOException {
        try {
            int mode = (follow ? Os.stat(path) : Os.lstat(path)).st_mode;
            if (!follow && OsConstants.S_ISLNK(mode)) return FileType.SYMLINK;
            if (OsConstants.S_ISREG(mode)) return FileType.REGULAR;
            return FileType.UNKNOWN;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return FileType.NO_EXIST;
            throw new IOException("Cannot inspect settings file", e);
        }
    }

    /** The settings file and AtomicFile's companions must be regular files; links are never followed. */
    private void validateTargets() throws IOException {
        for (String suffix : new String[] {"", ".bak", ".new"}) {
            FileType type = fileType(target + suffix);
            if (type == FileType.SYMLINK && suffix.isEmpty())
                throw new IOException("config.yaml is a symlink: change the file it links to instead");
            if (type != FileType.NO_EXIST && type != FileType.REGULAR)
                throw new IOException("Settings file is not a regular file");
        }
    }

    public synchronized Properties read() throws IOException {
        List<String> legacy = new ArrayList<>(legacyPaths);
        legacy.remove(target.getPath());
        TermuxConfigMigration.migrate(target, legacy);
        if (!target.exists() && !new File(target + ".bak").exists()) return new Properties();
        if (target.exists() && fileType(target.getPath(), true) != FileType.REGULAR)
            throw new IOException("Settings file is not a regular file");
        String text = readText();
        try {
            return TermuxYamlConfig.read(text, null);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The text of the settings file, or an empty text when there is none. */
    private String readText() throws IOException {
        if (!target.exists() && !new File(target + ".bak").exists()) return "";
        if (fileType(target.getPath(), false) == FileType.SYMLINK) {
            // AtomicFile would read a leftover .bak instead; a link is read as it is.
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(target)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = in.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        return new String(new AtomicFile(target).readFully(), StandardCharsets.UTF_8);
    }

    /** Set a setting by its internal key, or remove it with a null value. */
    public synchronized void set(@NonNull String key, @Nullable String value) throws IOException {
        validateTargets();
        read();
        byte[] document;
        try {
            document = TermuxYamlConfig.update(readText(), key, value).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create settings directory");
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(document);
            atomic.finishWrite(output);
        } catch (IOException e) {
            if (output != null) atomic.failWrite(output);
            throw e;
        }
    }
}
