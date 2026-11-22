package com.termux.app;

import android.app.Application;
import android.content.Context;

import com.termux.BuildConfig;
import com.termux.widget.TermuxWidgetApplication;
import dev.pschmitt.termux.api.TermuxAPIApplication;
import dev.pschmitt.termux.tasker.TermuxTaskerApplication;
import dev.pschmitt.termux.shared.errors.Error;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxBootstrap;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.crash.TermuxCrashUtils;
import dev.pschmitt.termux.shared.termux.file.TermuxFileUtils;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import dev.pschmitt.termux.shared.termux.shell.am.TermuxAmSocketServer;
import dev.pschmitt.termux.shared.termux.shell.TermuxShellManager;
import dev.pschmitt.termux.shared.termux.theme.TermuxThemeUtils;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        TermuxCompanionPreferencesMigration.migrate(context);

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        TermuxAPIApplication.initialize(context);
        TermuxTaskerApplication.initialize(context);
        TermuxWidgetApplication.initialize(context);

        Logger.logDebug("Starting Application");

        // Everything below owns app-wide state: the termux-am socket (one path; a second
        // server takes it over), the ADB shell listener, the nixpp watchdog and the shell
        // environment. Only the main process may run it. The ":background" process hosts the
        // Tasker plugin receiver, which only forwards intents to TermuxService; Android freezes
        // it while cached, so a socket server there stalls every termux-am and Termux:API call.
        if (!isMainProcess()) {
            Logger.logInfo(LOG_TAG, "Not the main process (" + processName() + "): skipping app-wide setup");
            return;
        }

        // Bring back an ADB shell listener that was already enabled (process restarted, app updated).
        // Never enables access or creates a token.
        AdbShellRecovery.recover(context, "application start");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Before any shell or native process starts: they preload the stable copy.
        if (isTermuxFilesDirectoryAccessible)
            dev.pschmitt.termux.shared.termux.nix.TermuxNix.installRedirectLibrary(getApplicationInfo().nativeLibraryDir);

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);
        NixppRuntimeRecovery.schedule(this);
        com.termux.app.update.UpdateCommandReceiver.publishInstalled(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);
        }
    }

    private boolean isMainProcess() {
        String name = processName();
        return name == null || name.equals(getPackageName());
    }

    /** This process's name, or null when unknown (then treated as the main process). */
    @androidx.annotation.Nullable
    private static String processName() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            return Application.getProcessName();
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/cmdline"));
            int end = 0;
            while (end < raw.length && raw[end] != 0) end++;
            return end == 0 ? null : new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
