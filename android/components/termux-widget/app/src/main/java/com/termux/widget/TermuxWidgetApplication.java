package com.termux.widget;

import android.content.Context;
import android.util.Log;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.theme.TermuxThemeUtils;

public final class TermuxWidgetApplication {

    public static final String LOG_TAG = "TermuxWidgetApplication";

    private TermuxWidgetApplication() {
    }

    public static void initialize(Context context) {
        Log.i(LOG_TAG, "AppInit");

        setLogConfig(context, true);
        TermuxThemeUtils.setAppNightMode(context);
    }

    public static void setLogConfig(Context context, boolean commitToFile) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_WIDGET_APP_NAME.replaceAll("[: ]", ""));

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile);
    }

}
