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
 * Reads and changes single settings in {@code config.yaml}, for the settings screens. A change rewrites the
 * whole file in the grouped layout, so comments in it do not survive. A legacy properties file is migrated
 * first, as the app does on start.
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
        try {
            int mode = Os.lstat(path).st_mode;
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
            if (type != FileType.NO_EXIST && type != FileType.REGULAR)
                throw new IOException("Settings file is not a regular file");
        }
    }

    public synchronized Properties read() throws IOException {
        List<String> legacy = new ArrayList<>(legacyPaths);
        legacy.remove(target.getPath());
        TermuxConfigMigration.migrate(target, legacy);
        if (!target.exists() && !new File(target + ".bak").exists()) return new Properties();
        if (target.exists() && fileType(target.getPath()) != FileType.REGULAR)
            throw new IOException("Settings file is not a regular file");
        String text = new String(new AtomicFile(target).readFully(), StandardCharsets.UTF_8);
        try {
            return TermuxYamlConfig.read(text, null);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Set a setting by its key, or remove it with a null value. */
    public synchronized void set(@NonNull String key, @Nullable String value) throws IOException {
        validateTargets();
        Properties properties = read();
        if (value == null) properties.remove(key);
        else properties.setProperty(key, value);
        byte[] document = TermuxYamlConfig.write(properties, null).getBytes(StandardCharsets.UTF_8);

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
