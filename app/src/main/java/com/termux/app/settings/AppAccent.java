package com.termux.app.settings;

import android.content.Context;

import com.termux.R;

import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/** The app accent, {@code appearance.accent} in config.yaml, independent of the terminal palette. */
public final class AppAccent {
    public static final String KEY = "app_accent";

    private AppAccent() {
    }

    public static String get(Context context) {
        return ConfigSettings.get(context, TermuxPropertyConstants.KEY_APP_ACCENT,
            TermuxPropertyConstants.DEFAULT_APP_ACCENT);
    }

    public static void set(Context context, String value) {
        ConfigSettings.set(context, TermuxPropertyConstants.KEY_APP_ACCENT,
            TermuxPropertyConstants.DEFAULT_APP_ACCENT.equals(value) ? null : value);
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
