package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import dev.pschmitt.termux.shared.termux.TermuxConstants;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Copies settings out of legacy companion APK data directories into the merged app. */
public final class TermuxCompanionPreferencesMigration {

    private static final String MIGRATION_PREFERENCES = "termuxpp_companion_migrations";

    private static final CompanionPreferences[] COMPANIONS = {
        new CompanionPreferences(TermuxConstants.TERMUX_API_PACKAGE_NAME,
            TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        new CompanionPreferences(TermuxConstants.TERMUX_BOOT_PACKAGE_NAME,
            TermuxConstants.TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        new CompanionPreferences(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME,
            TermuxConstants.TERMUX_STYLING_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        new CompanionPreferences(TermuxConstants.TERMUX_TASKER_PACKAGE_NAME,
            TermuxConstants.TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
        new CompanionPreferences(TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME,
            TermuxConstants.TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
    };

    private TermuxCompanionPreferencesMigration() {
    }

    /** Copies each installed companion's named preference file once, without replacing new values. */
    public static void migrate(@NonNull Context context) {
        SharedPreferences migrations = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE);
        for (CompanionPreferences companion : COMPANIONS) {
            if (migrations.getBoolean(companion.packageName, false)) continue;

            Context legacyContext;
            try {
                legacyContext = context.createPackageContext(companion.packageName, Context.CONTEXT_IGNORE_SECURITY);
            } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
                continue;
            }

            SharedPreferences destination = context.getSharedPreferences(companion.preferencesName, Context.MODE_PRIVATE);
            SharedPreferences source = legacyContext.getSharedPreferences(companion.preferencesName, Context.MODE_PRIVATE);
            if (destination.getAll().isEmpty()) {
                SharedPreferences.Editor editor = destination.edit();
                for (Map.Entry<String, ?> entry : source.getAll().entrySet()) {
                    put(editor, entry.getKey(), entry.getValue());
                }
                editor.apply();
            }
            migrations.edit().putBoolean(companion.packageName, true).apply();
        }
    }

    private static void put(@NonNull SharedPreferences.Editor editor, @NonNull String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set<?>) {
            Set<String> strings = new HashSet<>();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) return;
                strings.add((String) item);
            }
            editor.putStringSet(key, strings);
        }
    }

    private static final class CompanionPreferences {
        final String packageName;
        final String preferencesName;

        CompanionPreferences(String packageName, String preferencesName) {
            this.packageName = packageName;
            this.preferencesName = preferencesName;
        }
    }
}
