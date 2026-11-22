package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.termux.app.terminal.StylingApp;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import dev.pschmitt.termux.shared.interact.DialogUtils;

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
        boolean bootAvailable = isActivityAvailable(context.getPackageManager(),
            TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_BOOT_APP.TERMUX_BOOT_LAUNCHER_ACTIVITY_NAME);
        configurePreference("termux_boot", bootAvailable, R.drawable.ui_ic_power);
        Preference boot = findPreference("termux_boot");
        if (boot != null) {
            boot.setOnPreferenceClickListener(preference -> {
                if (bootAvailable) {
                    Intent intent = new Intent().setClassName(TermuxConstants.TERMUX_PACKAGE_NAME,
                        TermuxConstants.TERMUX_BOOT_APP.TERMUX_BOOT_LAUNCHER_ACTIVITY_NAME);
                    try {
                        startActivity(intent);
                    } catch (RuntimeException e) {
                        showCompanionUnavailable(context);
                    }
                } else {
                    showCompanionUnavailable(context);
                }
                return true;
            });
        }

        Preference styling = findPreference("termux_styling");
        if (styling != null) {
            boolean stylingAvailable = StylingApp.isAvailable(context.getPackageManager());
            if (!stylingAvailable) styling.setSummary(R.string.termux_styling_unavailable_summary);
            styling.setOnPreferenceClickListener(preference -> {
                if (StylingApp.isAvailable(context.getPackageManager())) {
                    try {
                        startActivity(StylingApp.createIntent());
                    } catch (RuntimeException e) {
                        showStylingUnavailable(context);
                    }
                } else {
                    showStylingUnavailable(context);
                }
                return true;
            });
        }
    }

    private void configurePreference(@NonNull String key, boolean available,
                                     @DrawableRes int fallbackIcon) {
        Preference preference = findPreference(key);
        if (preference == null) return;
        preference.setVisible(true);
        preference.setIcon(fallbackIcon);
        if (!available) {
            preference.setSummary(R.string.termux_companion_unavailable_summary);
            preference.setOnPreferenceClickListener(ignored -> {
                showCompanionUnavailable(requireContext());
                return true;
            });
        }
    }

    private void showCompanionUnavailable(@NonNull Context context) {
        DialogUtils.builder(context, R.drawable.ui_ic_extension, false)
            .setTitle(R.string.companion_apps_title)
            .setMessage(R.string.termux_companion_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private boolean isActivityAvailable(@NonNull PackageManager packageManager, @NonNull String packageName,
                                        @NonNull String activityName) {
        Intent intent = new Intent().setClassName(packageName, activityName);
        try {
            ResolveInfo info = packageManager.resolveActivity(intent, 0);
            return info != null && info.activityInfo != null
                && packageName.equals(info.activityInfo.packageName)
                && activityName.equals(info.activityInfo.name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void showStylingUnavailable(@NonNull Context context) {
        DialogUtils.builder(context, R.drawable.ui_ic_palette, false)
            .setTitle(R.string.termux_styling_title)
            .setMessage(R.string.termux_styling_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
