package dev.pschmitt.termux.shared.activity;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

import dev.pschmitt.termux.shared.R;
import dev.pschmitt.termux.shared.android.PackageUtils;
import dev.pschmitt.termux.shared.logger.Logger;

/** Shared parts of the plugin screens (widget, API, Tasker): the launcher switch and status lines. */
public final class PluginScreen {

    private static final String LOG_TAG = "PluginScreen";

    private PluginScreen() {
    }

    /**
     * Bind {@code launcherSwitch} to the launcher icon of a plugin screen: the {@code activity-alias}
     * {@code launcherActivity} of this app. Off removes the icon from the app drawer; the screen
     * stays reachable from the TermuxPP settings.
     */
    public static void bindLauncherSwitch(@NonNull Activity activity, @NonNull SwitchMaterial launcherSwitch,
                                          @NonNull String launcherActivity, @NonNull String appName) {
        String packageName = activity.getPackageName();
        launcherSwitch.setOnCheckedChangeListener(null);
        Boolean disabled = PackageUtils.isComponentDisabled(activity, packageName, launcherActivity, false);
        if (disabled == null) {
            Logger.logError(LOG_TAG, "Failed to check if \"" + packageName + "/" + launcherActivity + "\" is disabled");
            launcherSwitch.setEnabled(false);
            return;
        }
        launcherSwitch.setEnabled(true);
        launcherSwitch.setChecked(!disabled);
        launcherSwitch.setOnCheckedChangeListener((button, show) -> {
            String message = activity.getString(show ? R.string.msg_enabling_launcher_icon
                : R.string.msg_disabling_launcher_icon, appName);
            Logger.logInfo(LOG_TAG, message);
            String error = PackageUtils.setComponentState(activity, packageName, launcherActivity, show, message, true);
            if (error != null) Logger.logError(LOG_TAG, error);
            bindLauncherSwitch(activity, launcherSwitch, launcherActivity, appName);
        });
    }

    /**
     * Show whether a requirement is met: {@code status} reads {@code okText} in the accent, or
     * {@code neededText} in the error color with {@code action} shown to fix it.
     */
    public static void setStatus(@NonNull TextView status, @NonNull View action, boolean ok,
                                 @NonNull CharSequence okText, @NonNull CharSequence neededText) {
        status.setText(ok ? okText : neededText);
        status.setTextColor(ok
            ? MaterialColors.getColor(status, R.attr.termuxUiPrimary)
            : MaterialColors.getColor(status, com.google.android.material.R.attr.colorError));
        action.setVisibility(ok ? View.GONE : View.VISIBLE);
    }
}
