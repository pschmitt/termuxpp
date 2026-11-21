package dev.pschmitt.termux.api;

import android.content.Context;
import android.util.Log;

import dev.pschmitt.termux.api.util.ResultReturner;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;


public final class TermuxAPIApplication {

    public static final String LOG_TAG = "TermuxAPIApplication";

    private TermuxAPIApplication() {
    }

    public static void initialize(Context context) {
        Log.i(LOG_TAG, "AppInit");

        ResultReturner.setContext(context);
        setLogConfig(context, true);
        SocketListener.createSocketListener(context);
    }

    public static void setLogConfig(Context context, boolean commitToFile) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_API_APP_NAME.replaceAll("[: ]", ""));

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile);
    }

}
