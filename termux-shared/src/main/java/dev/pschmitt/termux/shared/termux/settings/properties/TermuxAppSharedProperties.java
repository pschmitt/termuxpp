package dev.pschmitt.termux.shared.termux.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

import java.io.File;
import java.util.List;

public class TermuxAppSharedProperties extends TermuxSharedProperties {

    private static TermuxAppSharedProperties properties;


    private TermuxAppSharedProperties(@NonNull Context context) {
        super(context, TermuxConstants.TERMUX_APP_NAME,
            TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST,
            new TermuxSharedProperties.SharedPropertiesParserClient());
    }

    @Override
    protected List<String> getPropertiesFilePaths() {
        return TermuxNix.configPaths(super.getPropertiesFilePaths());
    }

    /** Migrates a legacy properties file to YAML first, once, so every load sees the current file. */
    @Override
    public synchronized void loadTermuxPropertiesFromDisk() {
        migrateLegacyProperties();
        super.loadTermuxPropertiesFromDisk();
    }

    /** Convert a legacy {@code termuxpp.properties} (or Termux's files) to {@code config.yaml} if there is none. */
    public static void migrateLegacyProperties() {
        try {
            TermuxConfigMigration.migrate(
                new File(TermuxNix.configPaths(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST).get(0)),
                TermuxNix.configPaths(TermuxConstants.TERMUX_LEGACY_PROPERTIES_FILE_PATHS_LIST));
        } catch (java.io.IOException e) {
            dev.pschmitt.termux.shared.logger.Logger.logStackTraceWithMessage("TermuxAppSharedProperties",
                "Could not migrate the legacy properties file", e);
        }
    }

    /**
     * Initialize the {@link #properties} and load properties from disk.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the {@link TermuxAppSharedProperties}.
     */
    public static TermuxAppSharedProperties init(@NonNull Context context) {
        if (properties == null)
            properties = new TermuxAppSharedProperties(context);

        return properties;
    }

    /**
     * Get the {@link #properties}.
     *
     * @return Returns the {@link TermuxAppSharedProperties}.
     */
    public static TermuxAppSharedProperties getProperties() {
        return properties;
    }

}
