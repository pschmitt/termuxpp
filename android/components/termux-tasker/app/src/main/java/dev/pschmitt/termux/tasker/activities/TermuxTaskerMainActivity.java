package dev.pschmitt.termux.tasker.activities;

import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import dev.pschmitt.termux.shared.activity.PluginScreen;
import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.termux.file.TermuxFileUtils;
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
        applySystemBarInsets();

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(this);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        AppCompatActivityUtils.setToolbar(this, dev.pschmitt.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setToolbarTitle(this, dev.pschmitt.termux.shared.R.id.toolbar, TermuxConstants.TERMUX_TASKER_APP_NAME, 0);

        TextView usage = findViewById(R.id.textview_tasker_usage);
        usage.setText(getString(R.string.tasker_usage,
                TermuxFileUtils.getUnExpandedTermuxPath(TermuxConstants.TERMUX_TASKER_SCRIPTS_DIR_PATH)));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set log level for the app
        TermuxTaskerApplication.setLogConfig(this, false);

        Logger.logVerbose(LOG_TAG, "onResume");

        setChangeLauncherActivityStateViews();
    }



    private void applySystemBarInsets() {
        View root = findViewById(R.id.tasker_main_root);
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom + insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setChangeLauncherActivityStateViews() {
        PluginScreen.bindLauncherSwitch(this, findViewById(R.id.switch_launcher_icon),
            TermuxConstants.TERMUX_TASKER_APP.TERMUX_TASKER_LAUNCHER_ACTIVITY_NAME, TermuxConstants.TERMUX_TASKER_APP_NAME);
    }

}
