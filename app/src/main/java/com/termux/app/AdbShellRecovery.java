package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;

import dev.pschmitt.termux.shared.logger.Logger;

import java.io.File;

/**
 * Restarts the ADB shell listener when the user had already enabled it, e.g. after an APK update killed the
 * process or after a reboot.
 *
 * <p>This is recovery only: it never enables access, never creates or rotates the enrollment token and never
 * touches stored authorizations. Without the explicit opt-in preference (set from the settings UI) it does nothing.
 */
public final class AdbShellRecovery {
    private static final String LOG_TAG = "AdbShellRecovery";

    /** Starts the service; replaceable so the gating can be tested without a real service. */
    interface Starter {
        void start(@NonNull Context context);
    }

    /** Returns whether a previously completed opt-in is present and worth restarting for. */
    static boolean isRecoverable(@NonNull Context context) {
        return !AdbShellService.isManuallyStopped(context) && isConfigured(context);
    }

    public static boolean isConfigured(@NonNull Context context) {
        android.content.SharedPreferences preferences =
            context.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(AdbShellService.PREF_ENABLED, false)) return false;
        String token = preferences.getString(AdbShellService.PREF_TOKEN, null);
        return (token != null && !token.isEmpty()) || hasProvisionedTlsMaterial(context);
    }

    public static boolean recover(@NonNull Context context, @NonNull String reason) {
        return recover(context, reason, AdbShellRecovery::startService);
    }

    static boolean recover(@NonNull Context context, @NonNull String reason, @NonNull Starter starter) {
        if (!isRecoverable(context)) return false;
        try {
            starter.start(context);
            Logger.logInfo(LOG_TAG, "Restarting the ADB shell service (" + reason + ")");
            return true;
        } catch (RuntimeException error) {
            // E.g. a background foreground-service start restriction. The next app start retries.
            Logger.logError(LOG_TAG, "Unable to restart the ADB shell service (" + reason + "): " +
                error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean hasProvisionedTlsMaterial(Context context) {
        File directory = new File(context.getFilesDir(), "adb-shell");
        return new File(directory, "adb-shell-server.pk8").isFile() &&
            new File(directory, "adb-shell-server.crt").isFile() &&
            new File(directory, "adb-shell-client-ca.crt").isFile();
    }

    private static void startService(Context context) {
        AdbShellService.requestStart(context, AdbShellService.ACTION_START);
    }

    private AdbShellRecovery() {}
}
