package dev.pschmitt.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.settings.properties.SharedProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Moves the settings from the legacy Java properties file to {@code config.yaml}, once: when there is no
 * YAML file yet, the first legacy file found is converted, and renamed with a {@code .migrated} suffix so that
 * nothing reads it again. Its keys that the YAML layout does not know are logged and left out.
 */
public final class TermuxConfigMigration {

    private static final String LOG_TAG = "TermuxConfigMigration";
    public static final String MIGRATED_SUFFIX = ".migrated";

    private TermuxConfigMigration() {}

    /**
     * @return The legacy file that was migrated, or null when there was nothing to migrate.
     * @throws IOException When the YAML file cannot be written; the legacy file is then left in place.
     */
    @Nullable
    public static File migrate(@NonNull File yaml, @NonNull List<String> legacyPaths) throws IOException {
        if (yaml.exists()) return null;
        File legacy = SharedProperties.getPropertiesFileFromList(legacyPaths, LOG_TAG);
        if (legacy == null) return null;

        Properties properties = new Properties();
        try (InputStreamReader in = new InputStreamReader(new FileInputStream(legacy), StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        // The YAML layout has no deprecated keys: use-black-ui becomes night-mode, as on load.
        TermuxSharedProperties.replaceUseBlackUIProperty(properties);
        List<String> dropped = new ArrayList<>();
        String document = TermuxYamlConfig.write(properties, dropped);

        File parent = yaml.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create " + parent);
        File partial = new File(yaml.getPath() + ".new");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(partial), StandardCharsets.UTF_8)) {
            out.write(document);
        }
        if (!partial.renameTo(yaml)) {
            partial.delete();
            throw new IOException("Cannot create " + yaml);
        }

        File migrated = new File(legacy.getPath() + MIGRATED_SUFFIX);
        if (!legacy.renameTo(migrated))
            Logger.logWarn(LOG_TAG, "Migrated \"" + legacy + "\" but could not rename it to \"" + migrated + "\"");
        Logger.logInfo(LOG_TAG, "Migrated the settings from \"" + legacy + "\" to \"" + yaml + "\"");
        if (!dropped.isEmpty())
            Logger.logWarn(LOG_TAG, "Left out settings the YAML layout does not know: " + dropped);
        return legacy;
    }
}
