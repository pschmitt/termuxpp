package com.termux.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.IOException;

/**
 * Settings of the Display screen that live in {@code config.yaml} (the accent, the color scheme and the
 * font), so that they travel with the file. Earlier versions kept them in the Android preferences; they are
 * moved to the file once.
 */
public final class ConfigSettings {

    private static final String LOG_TAG = "ConfigSettings";

    /** Former Android preference key to the setting it moved to. */
    private static final String[][] MOVED_PREFERENCES = {
        {"app_accent", TermuxPropertyConstants.KEY_APP_ACCENT},
        {"terminal_color_scheme", TermuxPropertyConstants.KEY_TERMINAL_COLOR_SCHEME},
        {"terminal_font", TermuxPropertyConstants.KEY_TERMINAL_FONT},
    };

    private ConfigSettings() {}

    /**
     * The value of a setting as last loaded. Without one, a preference that could not be moved yet (for
     * example while config.yaml is a symlink) is used, and then the default.
     */
    @NonNull
    public static String get(@NonNull Context context, @NonNull String key, @NonNull String def) {
        String value = get(key, null);
        if (value != null) return value;
        for (String[] moved : MOVED_PREFERENCES) {
            if (moved[1].equals(key))
                return PreferenceManager.getDefaultSharedPreferences(context).getString(moved[0], def);
        }
        return def;
    }

    @Nullable
    private static String get(@NonNull String key, @Nullable String def) {
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties == null) return def;
        String value = properties.getPropertyValue(key, null, true);
        return value == null || value.trim().isEmpty() ? def : value.trim();
    }

    /** Write a setting to the file and reload the settings. */
    public static void set(@NonNull Context context, @NonNull String key, @Nullable String value) {
        try {
            new TermuxPropertiesEditor().set(key, value);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not save \"" + key + "\"", e);
            Logger.showToast(context, "Could not save the setting: " + e.getMessage(), true);
            return;
        }
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties != null) properties.loadTermuxPropertiesFromDisk();
    }

    /**
     * Move the Display settings from the Android preferences to the file, once. A value that the file
     * already has wins; the preference is removed either way.
     */
    public static void migrateFromPreferences(@NonNull Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean changed = false;
        for (String[] moved : MOVED_PREFERENCES) {
            String value = preferences.getString(moved[0], null);
            if (value == null) continue;
            if (get(moved[1], null) == null) {
                try {
                    new TermuxPropertiesEditor().set(moved[1], value);
                    changed = true;
                } catch (IOException e) {
                    // Keep the preference and try again on the next start.
                    Logger.logStackTraceWithMessage(LOG_TAG, "Could not move \"" + moved[0] + "\" to config.yaml", e);
                    continue;
                }
            }
            preferences.edit().remove(moved[0]).apply();
        }
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (changed && properties != null) properties.loadTermuxPropertiesFromDisk();
    }
}
