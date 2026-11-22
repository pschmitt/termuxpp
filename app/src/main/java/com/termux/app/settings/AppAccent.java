package com.termux.app.settings;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.termux.R;

/** The app accent belongs to Android preferences, independent of the terminal palette. */
public final class AppAccent {
    public static final String KEY = "app_accent";

    private AppAccent() {
    }

    public static String get(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "default");
    }

    public static void set(Context context, String value) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, value).apply();
    }

    public static void apply(Context context, String accent) {
        int overlay;
        switch (accent) {
            case "adaptive": overlay = R.style.TermuxPPAccentAdaptive; break;
            case "blue": overlay = R.style.TermuxPPAccentBlue; break;
            case "teal": overlay = R.style.TermuxPPAccentTeal; break;
            case "rose": overlay = R.style.TermuxPPAccentRose; break;
            default: return;
        }
        context.getTheme().applyStyle(overlay, true);
    }
}
