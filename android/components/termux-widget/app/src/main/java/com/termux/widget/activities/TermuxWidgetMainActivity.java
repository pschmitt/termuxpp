package com.termux.widget.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import dev.pschmitt.termux.shared.activity.PluginScreen;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.android.PackageUtils;
import dev.pschmitt.termux.shared.errors.Error;
import dev.pschmitt.termux.shared.file.FileUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.file.TermuxFileUtils;
import dev.pschmitt.termux.shared.theme.NightMode;
import com.termux.widget.R;
import com.termux.widget.ShortcutFile;
import com.termux.widget.TermuxWidgetApplication;
import com.termux.widget.TermuxWidgetProvider;
import com.termux.widget.utils.ShortcutUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TermuxWidgetMainActivity extends AppCompatActivity {

    public static final String LOG_TAG = "TermuxWidgetMainActivity";
    public static final String TERMUXPP_APP_GITHUB_REPO_URL = "https://github.com/pschmitt/termuxpp";
    public static final String TERMUXPP_WIDGET_GITHUB_REPO_URL = "https://github.com/pschmitt/termuxpp-widget";

    /** Canonical dynamic shortcut directory under ~/.config/termuxpp. */
    public static final String TERMUX_WIDGET_DYNAMIC_SHORTCUTS_DIR_PATH =
            TermuxConstants.TERMUX_CONFIG_TERMUXPP_DIR_PATH + "/widget/dynamic_shortcuts";
    /** Legacy directory retained as a fallback while existing setups migrate. */
    public static final String LEGACY_TERMUX_WIDGET_DYNAMIC_SHORTCUTS_DIR_PATH =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/widget/dynamic_shortcuts";

    private static String getDynamicShortcutsDirPath() {
        File canonical = new File(TERMUX_WIDGET_DYNAMIC_SHORTCUTS_DIR_PATH);
        File legacy = new File(LEGACY_TERMUX_WIDGET_DYNAMIC_SHORTCUTS_DIR_PATH);
        return canonical.exists() || !legacy.exists()
                ? TERMUX_WIDGET_DYNAMIC_SHORTCUTS_DIR_PATH
                : LEGACY_TERMUX_WIDGET_DYNAMIC_SHORTCUTS_DIR_PATH;
    }

    public static final String MAX_SHORTCUTS_LIMIT_DOCS_URL = TERMUXPP_WIDGET_GITHUB_REPO_URL + "#max-shortcuts-limit-optional";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Logger.logDebug(LOG_TAG, "onCreate");

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        setContentView(R.layout.activity_termux_widget_main);
        applySystemBarInsets();

        AppCompatActivityUtils.setToolbar(this, dev.pschmitt.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setToolbarTitle(this, dev.pschmitt.termux.shared.R.id.toolbar, getString(R.string.widget_main_title), 0);

        setDynamicShortcutsViews();
        setRefreshAllWidgetsViews();
        sendIntentToRefreshAllWidgets();
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.widget_main_root);
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(initialLeft + insets.left, initialTop + insets.top,
                    initialRight + insets.right, initialBottom + insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Set log level for the app
        TermuxWidgetApplication.setLogConfig(this, false);

        Logger.logVerbose(LOG_TAG, "onResume");

        setChangeLauncherActivityStateViews();
        setMaxShortcutsLimitView();
    }

    private void setMaxShortcutsLimitView() {
        LinearLayout maxShortcutsInfoLinearLayout = findViewById(R.id.linearlayout_max_shortcuts_limit_info);
        maxShortcutsInfoLinearLayout.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            ShortcutManager shortcutManager = ShortcutUtils.getShortcutManager(this, LOG_TAG, true);
            TextView maxShortcutsInfoTextView = findViewById(R.id.textview_max_shortcuts_limit_info);
            if (shortcutManager != null) {
                maxShortcutsInfoLinearLayout.setVisibility(View.VISIBLE);
                maxShortcutsInfoTextView.setText(getString(R.string.msg_max_shortcuts_limit_info,
                        shortcutManager.getMaxShortcutCountPerActivity()));
            }
        }
    }

    private void setChangeLauncherActivityStateViews() {
        TextView details = findViewById(R.id.textview_change_launcher_activity_state_details);
        details.setText(getString(R.string.widget_launcher_icon_info));
        // The launcher icon: the activity-alias of this activity, in this class's package.
        PluginScreen.bindLauncherSwitch(this, findViewById(R.id.switch_launcher_icon),
            TermuxWidgetMainActivity.class.getPackage().getName() + ".TermuxWidgetLauncherActivity",
            TermuxConstants.TERMUX_WIDGET_APP_NAME);
    }

    private void setDynamicShortcutsViews() {
        View dynamicShortcutsLinearLayout = findViewById(R.id.linearlayout_dynamic_shortcuts);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            dynamicShortcutsLinearLayout.setVisibility(View.VISIBLE);

            TextView dynamicShortcutsInfoTextView = findViewById(R.id.textview_dynamic_shortcuts_info);
            dynamicShortcutsInfoTextView.setText(getString(R.string.msg_dynamic_shortcuts_info,
                    TermuxFileUtils.getUnExpandedTermuxPath(getDynamicShortcutsDirPath())));

            Button createDynamicShortcutsButton = findViewById(R.id.button_create_dynamic_shortcuts);
            createDynamicShortcutsButton.setOnClickListener(v -> createDynamicShortcuts(this));

            Button removeDynamicShortcutsButton = findViewById(R.id.button_remove_dynamic_shortcuts);
            removeDynamicShortcutsButton.setOnClickListener(v -> removeDynamicShortcuts(this));
        } else {
            dynamicShortcutsLinearLayout.setVisibility(View.GONE);
        }
    }

    private void setRefreshAllWidgetsViews() {
        Button refreshAllWidgetsIconButton = findViewById(R.id.button_refresh_all_widgets);
        refreshAllWidgetsIconButton.setOnClickListener(
                v -> sendIntentToRefreshAllWidgets());
    }

    public void sendIntentToRefreshAllWidgets() {
        TermuxWidgetProvider.sendIntentToRefreshAllWidgets(this, LOG_TAG);
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private void createDynamicShortcuts(@NonNull Context context) {
        ShortcutManager shortcutManager = ShortcutUtils.getShortcutManager(context, LOG_TAG, true);
        if (shortcutManager == null) return;

        // Create directory if necessary so user more easily finds where to put shortcuts
        Error error = FileUtils.createDirectoryFile(getDynamicShortcutsDirPath());
        if (error != null) {
            Logger.logError(LOG_TAG, error.toString());
            Logger.showToast(this, error.getMinimalErrorLogString(), true);
        }

        List<ShortcutFile> shortcutFiles = new ArrayList<>();
        ShortcutUtils.enumerateShortcutFiles(shortcutFiles, new File(getDynamicShortcutsDirPath()), false);

        if (shortcutFiles.size() == 0) {
            Logger.showToast(context, getString(R.string.msg_no_shortcut_files_found_in_directory,
                    TermuxFileUtils.getUnExpandedTermuxPath(getDynamicShortcutsDirPath())), true);
            return;
        }

        List<ShortcutInfo> shortcuts = new ArrayList<>();
        for (ShortcutFile shortcutFile : shortcutFiles) {
            shortcuts.add(shortcutFile.getShortcutInfo(context, false));
        }

        // Remove shortcuts that can not be added.
        int maxShortcuts = shortcutManager.getMaxShortcutCountPerActivity();
        Logger.logDebug(LOG_TAG, "Found " + shortcutFiles.size() + " shortcuts and max shortcuts limit is " + maxShortcuts);
        if (shortcuts.size() > maxShortcuts) {
            Logger.logErrorAndShowToast(context, LOG_TAG, getString(R.string.msg_dynamic_shortcuts_limit_reached, maxShortcuts));
            while (shortcuts.size() > maxShortcuts) {
                String message = getString(R.string.msg_skipping_shortcut,
                        shortcuts.get(shortcuts.size() - 1).getId().replaceAll(
                                "^" + Pattern.quote(getDynamicShortcutsDirPath() + "/"), ""));
                Logger.showToast(context, message, false);
                Logger.logDebug(LOG_TAG, message);
                shortcuts.remove(shortcuts.size() - 1);
            }
        }

        shortcutManager.removeAllDynamicShortcuts();
        shortcutManager.addDynamicShortcuts(shortcuts);
        Logger.logDebugAndShowToast(context, LOG_TAG, getString(R.string.msg_created_dynamic_shortcuts_successfully, shortcuts.size()));
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private void removeDynamicShortcuts(@NonNull Context context) {
        ShortcutManager shortcutManager = ShortcutUtils.getShortcutManager(context, LOG_TAG, true);
        if (shortcutManager == null) return;

        List<ShortcutInfo> shortcuts = shortcutManager.getDynamicShortcuts();
        if (shortcuts != null && shortcuts.size() == 0) {
            Logger.logDebugAndShowToast(context, LOG_TAG, getString(R.string.msg_no_dynamic_shortcuts_currently_created));
            return;
        }

        shortcutManager.removeAllDynamicShortcuts();
        Logger.logDebugAndShowToast(context, LOG_TAG, getString(R.string.msg_removed_dynamic_shortcuts_successfully));
    }

}
