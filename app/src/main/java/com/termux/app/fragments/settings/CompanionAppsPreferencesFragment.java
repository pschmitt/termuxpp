package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.settings.GroupedPreferencesFragment;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;

@Keep
public class CompanionAppsPreferencesFragment extends GroupedPreferencesFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setPreferencesFromResource(R.xml.companion_apps_preferences, rootKey);
        configurePreference("termux_api",
            TermuxAPIAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_extension);
        configurePreference("termux_float",
            TermuxFloatAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_float);
        configurePreference("termux_tasker",
            TermuxTaskerAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_bolt);
        configurePreference("termux_widget",
            TermuxWidgetAppSharedPreferences.build(context, false) != null, R.drawable.ui_ic_widgets);
    }

    private void configurePreference(@NonNull String key, boolean available,
                                     @DrawableRes int fallbackIcon) {
        Preference preference = findPreference(key);
        if (preference == null) return;
        preference.setVisible(true);
        preference.setIcon(fallbackIcon);
        if (!available) {
            preference.setSummary(R.string.termux_companion_unavailable_summary);
        }
    }
}
