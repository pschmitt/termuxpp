package com.termux.app.fragments.settings.termux;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.LinearLayout;

import android.widget.TextView;
import dev.pschmitt.termux.shared.interact.DialogUtils;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.app.AdbShellService;
import com.termux.app.settings.TermuxPropertiesEditor;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import com.termux.R;
import com.termux.app.settings.GroupedPreferencesFragment;
import java.util.function.IntConsumer;

import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import dev.pschmitt.termux.shared.logger.Logger;

@Keep
public class DebuggingPreferencesFragment extends GroupedPreferencesFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_debugging_preferences, rootKey);

        configureLoggingPreferences(context);
        configureIntegrationLogging(context);
        configureAdbShellPreference(context);
        configureAllowedSsidsPreference();
        configureCurrentNetworkPreference(context);

        Preference notificationSettings = findPreference("adb_shell_notification_settings");
        if (notificationSettings != null) {
            notificationSettings.setOnPreferenceClickListener(preference -> {
                AdbShellService.createNotificationChannel(context);
                Intent intent;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    intent = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, AdbShellService.CHANNEL_ID);
                } else {
                    intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
                }
                startActivity(intent);
                return true;
            });
        }

        for (String key : new String[] {"log_level", "terminal_view_key_logging_enabled",
            "plugin_error_notifications_enabled", "crash_report_notifications_enabled",
            "adb_shell_current_network", "adb_shell_allowed_ssids", "adb_shell_authorizations",
            "adb_shell_notification_settings"}) {
            Preference row = findPreference(key);
            if (row != null) row.setIcon(null);
        }
    }

    private void configureAllowedSsidsPreference() {
        androidx.preference.EditTextPreference edit = findPreference("adb_shell_allowed_ssids");
        if (edit == null) return;
        edit.setSummaryProvider(preference -> {
            String text = ((androidx.preference.EditTextPreference) preference).getText();
            int count = 0;
            if (text != null) {
                for (String name : text.split(",")) {
                    if (!name.trim().isEmpty()) count++;
                }
            }
            return count == 0 ? getString(R.string.settings_adb_network_any)
                : getResources().getQuantityString(R.plurals.settings_adb_network_count, count, count);
        });
        edit.setOnPreferenceChangeListener((preference, value) -> {
            postNetworkRefresh();
            return true;
        });
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (!"adb_shell_allowed_ssids".equals(preference.getKey())) {
            super.onDisplayPreferenceDialog(preference);
            return;
        }

        androidx.preference.EditTextPreference allowed = (androidx.preference.EditTextPreference) preference;
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setText(allowed.getText());
        input.setSelection(input.length());
        LinearLayout content = new LinearLayout(context);
        int inset = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));

        DialogUtils.builder(context)
            .setTitle(allowed.getDialogTitle())
            .setMessage(allowed.getDialogMessage())
            .setView(content)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText().toString();
                if (allowed.callChangeListener(value)) allowed.setText(value);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        Context context = getContext();
        if (context == null) return;
        refreshCurrentNetwork(context);
        // Follow Wi-Fi changes while the screen is visible; Android does not
        // resume the fragment when the phone joins or leaves a network.
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null && wifiCallback == null) {
            wifiCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull android.net.Network network) { postNetworkRefresh(); }
                @Override public void onLost(@NonNull android.net.Network network) { postNetworkRefresh(); }
                @Override public void onCapabilitiesChanged(@NonNull android.net.Network network,
                                                            @NonNull android.net.NetworkCapabilities capabilities) { postNetworkRefresh(); }
            };
            try {
                connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback);
            } catch (RuntimeException e) {
                wifiCallback = null;
            }
        }
    }

    @Override
    public void onPause() {
        Context context = getContext();
        if (context != null && wifiCallback != null) {
            android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                if (connectivity != null) connectivity.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
            }
        }
        wifiCallback = null;
        super.onPause();
    }

    @Nullable
    private android.net.ConnectivityManager.NetworkCallback wifiCallback;

    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // Always posted: a preference change listener runs before the new value is
    // stored, and network callbacks arrive on a binder thread.
    private void postNetworkRefresh() {
        uiHandler.post(() -> {
            Context context = getContext();
            if (context != null && isResumed()) refreshCurrentNetwork(context);
        });
    }

    private final androidx.activity.result.ActivityResultLauncher<String> locationPermission =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            granted -> {
                Context context = getContext();
                if (context != null) refreshCurrentNetwork(context);
            });

    private void configureCurrentNetworkPreference(@NonNull Context context) {
        androidx.preference.Preference network = findPreference("adb_shell_current_network");
        if (network == null) return;
        network.setOnPreferenceClickListener(preference -> {
            refreshCurrentNetwork(context);
            CharSequence summary = network.getSummary();
            String ssid = AdbShellService.currentSsid(context);
            String list = currentAllowedSsids();
            com.google.android.material.dialog.MaterialAlertDialogBuilder dialog = DialogUtils.builder(context)
                .setTitle(network.getTitle())
                .setMessage(ssid == null ? summary : getString(R.string.settings_adb_network_details, ssid, summary))
                .setNegativeButton(android.R.string.cancel, null);
            if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                dialog.setPositiveButton(R.string.settings_adb_network_allow_location, (ignored, which) ->
                    locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION));
            } else if (!hasBackgroundLocation(context)) {
                dialog.setMessage(getString(R.string.settings_adb_network_details,
                    ssid == null ? summary : ssid, getString(R.string.adb_shell_current_network_background)));
                dialog.setPositiveButton(R.string.settings_adb_network_open_settings, (ignored, which) ->
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.getPackageName(), null))));
            } else if (ssid != null && (list.isEmpty() || !AdbShellService.permitsSsid(list, '"' + ssid + '"'))) {
                dialog.setPositiveButton(R.string.adb_shell_trust_network_confirm, (ignored, which) ->
                    confirmTrustNetwork(context, ssid, list));
            } else {
                dialog.setNegativeButton(android.R.string.ok, null);
            }
            dialog.show();
            return true;
        });
        refreshCurrentNetwork(context);
    }

    private void confirmTrustNetwork(Context context, String ssid, String list) {
        DialogUtils.builder(context, R.drawable.ui_ic_terminal, false)
            .setTitle(getString(R.string.adb_shell_trust_network_title, ssid))
            .setMessage(R.string.adb_shell_trust_network_message)
            .setPositiveButton(R.string.adb_shell_trust_network_confirm, (dialog, which) -> {
                androidx.preference.EditTextPreference edit = findPreference("adb_shell_allowed_ssids");
                if (edit == null) return;
                edit.setText(list.isEmpty() ? ssid : list + "," + ssid);
                refreshCurrentNetwork(context);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private String currentAllowedSsids() {
        androidx.preference.EditTextPreference edit = findPreference("adb_shell_allowed_ssids");
        String text = edit == null ? null : edit.getText();
        return text == null ? "" : text.trim();
    }

    private static boolean hasBackgroundLocation(@NonNull Context context) {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
            || androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void refreshCurrentNetwork(@NonNull Context context) {
        androidx.preference.Preference network = findPreference("adb_shell_current_network");
        if (network == null) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            network.setSummary(R.string.settings_adb_network_permission);
            return;
        }
        String ssid = AdbShellService.currentSsid(context);
        String list = currentAllowedSsids();
        int state;
        if (ssid == null) state = R.string.settings_adb_network_none;
        else if (list.isEmpty()) state = R.string.settings_adb_network_any;
        else if (AdbShellService.permitsSsid(list, "\"" + ssid + "\"")) state = R.string.settings_adb_network_trusted;
        else state = R.string.settings_adb_network_untrusted;
        network.setSummary(hasBackgroundLocation(context) ? getString(state)
            : getString(R.string.settings_adb_network_background, getString(state)));
    }

    private void configureAdbShellPreference(@NonNull Context context) {
        SwitchPreferenceCompat adbShellPreference = findPreference("adb_shell_enabled");
        if (adbShellPreference == null) return;

        adbShellPreference.setOnPreferenceChangeListener((preference, value) -> {
            if (Boolean.TRUE.equals(value)) {
                String token = AdbShellService.createAccessToken(context);
                TextView tokenView = new TextView(context);
                tokenView.setText(token);
                tokenView.setTextIsSelectable(true);
                int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
                tokenView.setPadding(padding, padding, padding, padding);

                androidx.appcompat.app.AlertDialog tokenDialog = DialogUtils.builder(context, R.drawable.ui_ic_terminal, false)
                    .setTitle(R.string.adb_shell_token_title)
                    .setMessage(R.string.adb_shell_token_warning)
                    .setView(tokenView)
                    .setPositiveButton(R.string.adb_shell_copy_token, (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("TermuxPP ADB shell token", token));
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .create();
                if (tokenDialog.getWindow() != null) {
                    tokenDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                }
                tokenDialog.show();
            }
            return true;
        });
    }

    private void configureLoggingPreferences(@NonNull Context context) {
        PreferenceCategory loggingCategory = findPreference("logging");
        if (loggingCategory == null) return;

        ListPreference logLevelListPreference = findPreference("log_level");
        if (logLevelListPreference != null) {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, true);
            if (preferences == null) return;

            setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel());
            loggingCategory.addPreference(logLevelListPreference);
        }
    }

    private void configureIntegrationLogging(Context context) {
        TermuxAPIAppSharedPreferences api = TermuxAPIAppSharedPreferences.build(context, true);
        if (api != null) configureLogLevel("api_log_level", api.getLogLevel(true),
            level -> api.setLogLevel(context, level, true));

        TermuxTaskerAppSharedPreferences tasker = TermuxTaskerAppSharedPreferences.build(context, true);
        if (tasker != null) configureLogLevel("tasker_log_level", tasker.getLogLevel(true),
            level -> tasker.setLogLevel(context, level, true));

        TermuxWidgetAppSharedPreferences widget = TermuxWidgetAppSharedPreferences.build(context, true);
        if (widget != null) configureLogLevel("widget_log_level", widget.getLogLevel(true),
            level -> widget.setLogLevel(context, level, true));
    }

    private void configureLogLevel(String key, int currentLevel, IntConsumer save) {
        ListPreference preference = findPreference(key);
        setLogLevelListPreferenceData(preference, requireContext(), currentLevel);
        preference.setEnabled(true);
        preference.setOnPreferenceChangeListener((row, value) -> {
            save.accept(Integer.parseInt(value.toString()));
            return true;
        });
    }

    public static ListPreference setLogLevelListPreferenceData(ListPreference logLevelListPreference, Context context, int logLevel) {
        if (logLevelListPreference == null)
            logLevelListPreference = new ListPreference(context);

        CharSequence[] logLevels = Logger.getLogLevelsArray();
        CharSequence[] logLevelLabels = Logger.getLogLevelLabelsArray(context, logLevels, true);

        logLevelListPreference.setEntryValues(logLevels);
        logLevelListPreference.setEntries(logLevelLabels);

        logLevelListPreference.setValue(String.valueOf(logLevel));
        logLevelListPreference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL);

        return logLevelListPreference;
    }

}

class DebuggingPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static DebuggingPreferencesDataStore mInstance;

    private DebuggingPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized DebuggingPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new DebuggingPreferencesDataStore(context);
        }
        return mInstance;
    }



    @Override
    @Nullable
    public String getString(String key, @Nullable String defValue) {
        if (mPreferences == null) return null;
        if (key == null) return null;

        switch (key) {
            case "log_level":
                return String.valueOf(mPreferences.getLogLevel());
            case "adb_shell_allowed_ssids":
                try {
                    return new TermuxPropertiesEditor().read().getProperty(
                        TermuxPropertyConstants.KEY_ADB_SHELL_ALLOWED_SSIDS, "");
                } catch (java.io.IOException e) {
                    return "";
                }
            default:
                return null;
        }
    }

    @Override
    public void putString(String key, @Nullable String value) {
        if (key == null) return;

        if ("adb_shell_allowed_ssids".equals(key)) {
            try {
                String list = value == null ? "" : value.trim();
                new TermuxPropertiesEditor().set(
                    TermuxPropertyConstants.KEY_ADB_SHELL_ALLOWED_SSIDS, list.isEmpty() ? null : list);
                Intent reload = new Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
                reload.setPackage(mContext.getPackageName());
                mContext.sendBroadcast(reload);
            } catch (java.io.IOException | IllegalArgumentException e) {
                android.widget.Toast.makeText(mContext, R.string.properties_save_error, android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (mPreferences == null) return;
        switch (key) {
            case "log_level":
                if (value != null) {
                    mPreferences.setLogLevel(mContext, Integer.parseInt(value));
                }
                break;
            default:
                break;
        }
    }



    @Override
    public void putBoolean(String key, boolean value) {
        if (key == null) return;

        if ("adb_shell_enabled".equals(key)) {
            android.content.SharedPreferences preferences = mContext.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = preferences.edit().putBoolean(AdbShellService.PREF_ENABLED, value);
            if (!value) editor.remove(AdbShellService.PREF_TOKEN);
            editor.apply();

            Intent serviceIntent = new Intent(mContext, AdbShellService.class);
            if (!value) serviceIntent.setAction(AdbShellService.ACTION_STOP);
            mContext.startService(serviceIntent);
            return;
        }

        if (mPreferences == null) return;
        switch (key) {
            case "terminal_view_key_logging_enabled":
                    mPreferences.setTerminalViewKeyLoggingEnabled(value);
                break;
            case "plugin_error_notifications_enabled":
                mPreferences.setPluginErrorNotificationsEnabled(value);
                break;
            case "crash_report_notifications_enabled":
                mPreferences.setCrashReportNotificationsEnabled(value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (key == null) return defValue;
        if ("adb_shell_enabled".equals(key)) {
            return mContext.getSharedPreferences(AdbShellService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AdbShellService.PREF_ENABLED, defValue);
        }
        if (mPreferences == null) return defValue;
        switch (key) {
            case "terminal_view_key_logging_enabled":
                return mPreferences.isTerminalViewKeyLoggingEnabled();
            case "plugin_error_notifications_enabled":
                return mPreferences.arePluginErrorNotificationsEnabled(false);
            case "crash_report_notifications_enabled":
                return mPreferences.areCrashReportNotificationsEnabled(false);
            default:
                return false;
        }
    }

}
