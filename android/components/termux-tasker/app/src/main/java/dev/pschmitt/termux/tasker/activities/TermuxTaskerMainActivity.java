package dev.pschmitt.termux.tasker.activities;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.android.PackageUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.markdown.MarkdownUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.theme.TermuxThemeUtils;
import dev.pschmitt.termux.shared.theme.NightMode;
import dev.pschmitt.termux.tasker.R;
import dev.pschmitt.termux.tasker.TermuxTaskerApplication;

public class TermuxTaskerMainActivity extends AppCompatActivity {

    public static final String LOG_TAG = "TermuxTaskerMainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_tasker_main);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        AppCompatActivityUtils.setToolbar(this, dev.pschmitt.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setToolbarTitle(this, dev.pschmitt.termux.shared.R.id.toolbar, TermuxConstants.TERMUX_TASKER_APP_NAME, 0);

        TextView pluginInfo = findViewById(R.id.textview_plugin_info);
        pluginInfo.setText(getString(R.string.plugin_info,
                "https://github.com/pschmitt/termuxpp",
                TermuxConstants.TERMUX_TASKER_GITHUB_REPO_URL,
                "https://github.com/termux/termux-app",
                "https://github.com/termux/termux-tasker"));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set log level for the app
        TermuxTaskerApplication.setLogConfig(this, false);

        Logger.logVerbose(LOG_TAG, "onResume");

        setChangeLauncherActivityStateViews();
    }



    private void setChangeLauncherActivityStateViews() {
        String packageName = TermuxConstants.TERMUX_PACKAGE_NAME;
        String className = TermuxConstants.TERMUX_TASKER_APP.TERMUX_TASKER_LAUNCHER_ACTIVITY_NAME;

        TextView changeLauncherActivityStateTextView = findViewById(R.id.textview_change_launcher_activity_state_details);
        changeLauncherActivityStateTextView.setText(MarkdownUtils.getSpannedMarkdownText(this,
                getString(R.string.msg_change_launcher_activity_state_info, packageName, getClass().getName())));

        Button changeLauncherActivityStateButton = findViewById(R.id.button_change_launcher_activity_state);
        String stateChangeMessage;
        boolean newState;

        Boolean currentlyDisabled = PackageUtils.isComponentDisabled(this,
                packageName, className, false);
        if (currentlyDisabled == null) {
            Logger.logError(LOG_TAG, "Failed to check if \"" + packageName + "/" + className + "\" launcher activity is disabled");
            changeLauncherActivityStateButton.setEnabled(false);
            changeLauncherActivityStateButton.setAlpha(.5f);
            changeLauncherActivityStateButton.setText(dev.pschmitt.termux.shared.R.string.action_disable_launcher_icon);
            changeLauncherActivityStateButton.setOnClickListener(null);
            return;
        }

        changeLauncherActivityStateButton.setEnabled(true);
        changeLauncherActivityStateButton.setAlpha(1f);
        if (currentlyDisabled) {
            changeLauncherActivityStateButton.setText(dev.pschmitt.termux.shared.R.string.action_enable_launcher_icon);
            stateChangeMessage = getString(dev.pschmitt.termux.shared.R.string.msg_enabling_launcher_icon, TermuxConstants.TERMUX_TASKER_APP_NAME);
            newState = true;
        } else {
            changeLauncherActivityStateButton.setText(dev.pschmitt.termux.shared.R.string.action_disable_launcher_icon);
            stateChangeMessage = getString(dev.pschmitt.termux.shared.R.string.msg_disabling_launcher_icon, TermuxConstants.TERMUX_TASKER_APP_NAME);
            newState = false;
        }

        changeLauncherActivityStateButton.setOnClickListener(v -> {
            Logger.logInfo(LOG_TAG, stateChangeMessage);
            String errmsg = PackageUtils.setComponentState(this,
                    packageName, className, newState, stateChangeMessage, true);
            if (errmsg == null)
                setChangeLauncherActivityStateViews();
            else
                Logger.logError(LOG_TAG, errmsg);
        });
    }

}
