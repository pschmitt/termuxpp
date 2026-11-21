package com.termux.window;

import android.content.Context;
import android.util.Log;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;

public final class TermuxFloatApplication {

    public static final String LOG_TAG = "TermuxFloatApplication";

    private TermuxFloatApplication() {
    }

    public static void initialize(Context context) {
        Log.i(LOG_TAG, "AppInit");

        setLogConfig(context, true);
    }

    public static void setLogConfig(Context context, boolean commitToFile) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_FLOAT_APP_NAME.replaceAll("[: ]", ""));

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile);
    }

}
