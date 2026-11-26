package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.termux.R;
import com.termux.app.AdbShellRecovery;
import com.termux.app.AdbShellService;
import com.termux.app.ServiceNotificationPolicy;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.google.android.material.color.MaterialColors;
import com.termux.boot.BootReceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.interact.ShareUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;
import dev.pschmitt.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

/** Shows managed Nix services and the scripts scheduled by Android at boot. */
@Keep
public class ServicesPreferencesFragment extends GroupedPreferencesFragment {

    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final String SERVICE_NAME_PATTERN = "[A-Za-z0-9_.@-]+";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable adbShellRefresh = this::updateAdbShellRow;
    @Nullable private SwipeRefreshLayout swipeRefresh;
    private PreferenceCategory servicesCategory;
    private boolean busy;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.termux_services_preferences, rootKey);
        servicesCategory = findPreference("services_list");
        Preference defaultNotifications = findPreference("service_notification_default");
        if (defaultNotifications != null) {
            defaultNotifications.setOnPreferenceClickListener(preference -> {
                showNotificationChoice(ServiceNotificationPolicy.DEFAULTS,
                    getString(R.string.nix_services_notification_default));
                return true;
            });
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View preferences = super.onCreateView(inflater, container, savedInstanceState);
        SwipeRefreshLayout refresh = new SwipeRefreshLayout(requireContext());
        refresh.setColorSchemeColors(MaterialColors.getColor(requireContext(), R.attr.termuxUiPrimary,
            ContextCompat.getColor(requireContext(), R.color.ui_primary)));
        refresh.addView(preferences);
        refresh.setOnChildScrollUpCallback((layout, child) -> getListView().canScrollVertically(-1));
        refresh.setOnRefreshListener(this::refreshPage);
        swipeRefresh = refresh;
        return refresh;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (swipeRefresh != null) swipeRefresh.setRefreshing(busy);
        refreshPage();
        updateAdbShellRow();
    }

    @Override
    public void onPause() {
        mainHandler.removeCallbacks(adbShellRefresh);
        super.onPause();
    }

    private void updateAdbShellRow() {
        Preference row = servicesCategory.findPreference(ServiceNotificationPolicy.ADB_SHELL);
        if (row != null) row.setSummary(serviceRowSummary(adbShellStatus()));
        mainHandler.postDelayed(adbShellRefresh, 1000);
    }

    @Override
    public void onDestroyView() {
        swipeRefresh = null;
        super.onDestroyView();
    }

    private void refreshPage() {
        Preference notificationDefault = findPreference("service_notification_default");
        if (notificationDefault != null) {
            boolean enabled = ServiceNotificationPolicy.enabled(ServiceNotificationPolicy.readSettings(),
                ServiceNotificationPolicy.DEFAULTS, ServiceNotificationPolicy.configuredDefault());
            notificationDefault.setSummary(enabled ? R.string.nix_services_notification_default_on
                : R.string.nix_services_notification_default_off);
        }
        refreshBootScripts();
        refreshServices();
    }

    private void showBootSetup() {
        Context context = requireContext();
        View setup = LayoutInflater.from(context).inflate(R.layout.ui_boot_script_setup, null);
        setup.findViewById(R.id.boot_script_copy).setOnClickListener(button ->
            ShareUtils.copyTextToClipboard(context, getString(R.string.boot_script_example_title),
                getString(R.string.boot_script_example), Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ? getString(R.string.boot_script_example_copied) : null));
        DialogUtils.builder(context)
            .setTitle(R.string.boot_setup_title)
            .setView(setup)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void refreshBootScripts() {
        PreferenceCategory scripts = findPreference("boot_scripts");
        scripts.removeAll();
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        List<File> files = BootReceiver.getBootScripts(home);
        File defaultDirectory = new File(TermuxConstants.TERMUX_CONFIG_TERMUXPP_DIR_PATH, "boot");
        for (File file : files) {
            Preference script = new Preference(requireContext());
            script.setTitle(file.getName());
            if (!defaultDirectory.equals(file.getParentFile()))
                script.setSummary("~" + file.getParent().substring(home.length()) + "/");
            script.setIconSpaceReserved(false);
            script.setSelectable(false);
            scripts.addPreference(script);
        }
        Preference help = new Preference(requireContext()) {
            @Override
            protected void onClick() {
                showBootSetup();
            }
        };
        help.setKey("boot_script_setup");
        help.setTitle(files.isEmpty() ? R.string.boot_scripts_empty : R.string.boot_setup_title);
        help.setIconSpaceReserved(false);
        help.setWidgetLayoutResource(R.layout.ui_boot_script_help_icon);
        scripts.addPreference(help);
    }

    private void refreshServices() {
        if (busy || servicesCategory == null) return;
        boolean provisioned = TermuxNix.isProvisioned();
        if (!provisioned || !TermuxNix.isReady()) {
            int message = provisioned
                ? R.string.nix_services_guest_not_ready : R.string.nix_services_not_available;
            showServicePlaceholder(getString(message));
            addAdbShellIfMissing();
            setBusy(false);
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            CommandResult result = runGuestCommand("svc", "--json", "list");
            List<Service> services = null;
            String error = result.output;
            if (result.exitCode == 0) {
                try {
                    services = parseServices(result.output);
                } catch (JSONException e) {
                    error = e.getMessage();
                }
            }
            List<Service> loaded = services;
            String failure = error;
            mainHandler.post(() -> {
                if (!isAdded() || servicesCategory == null) return;
                setBusy(false);
                if (loaded == null) {
                    String message = getString(R.string.nix_services_load_error, concise(failure));
                    if (servicesCategory.getPreferenceCount() == 0 || servicesCategory.findPreference("services_empty") != null)
                        showServicePlaceholder(message);
                    else
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    addAdbShellIfMissing();
                    return;
                }
                servicesCategory.removeAll();
                if (loaded.isEmpty()) showServicePlaceholder(getString(R.string.nix_services_empty));
                for (Service service : loaded)
                    if (!ServiceNotificationPolicy.ADB_SHELL.equals(service.name)) addServicePreference(service);
                addAdbShellIfMissing();
            });
        });
    }

    private void addAdbShellIfMissing() {
        if (servicesCategory.findPreference(ServiceNotificationPolicy.ADB_SHELL) != null) return;
        addServicePreference(adbShellStatus());
    }

    private Service adbShellStatus() {
        Context context = requireContext();
        String status = AdbShellService.listenerStatus(context);
        boolean enabled = AdbShellRecovery.isConfigured(context) && !AdbShellService.isManuallyStopped(context);
        List<String> ssids = new ArrayList<>();
        for (String ssid : AdbShellService.allowedSsids().split(","))
            if (!ssid.trim().isEmpty()) ssids.add(ssid.trim());
        return new Service(ServiceNotificationPolicy.ADB_SHELL, status, enabled,
            !ssids.isEmpty(), ssids, false, false, "waiting".equals(status), null);
    }

    private void showServicePlaceholder(CharSequence message) {
        servicesCategory.removeAll();
        Preference empty = new Preference(requireContext());
        empty.setKey("services_empty");
        empty.setTitle(message);
        empty.setSelectable(false);
        empty.setIconSpaceReserved(false);
        servicesCategory.addPreference(empty);
    }

    private void addServicePreference(@NonNull Service service) {
        boolean adbShell = ServiceNotificationPolicy.ADB_SHELL.equals(service.name);
        Preference preference = new Preference(requireContext()) {
            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
                if (icon != null) {
                    int color = ContextCompat.getColor(getContext(),
                        serviceStatusColor(adbShell ? adbShellStatus() : service));
                    icon.setImageTintList(ColorStateList.valueOf(color));
                }
            }
        };
        preference.setIcon(R.drawable.ui_ic_service_status);
        preference.setKey(service.name);
        preference.setTitle(service.name);
        preference.setSummary(serviceRowSummary(service));
        preference.setOnPreferenceClickListener(clicked -> {
            showServiceActions(adbShell ? adbShellStatus() : service);
            return true;
        });
        servicesCategory.addPreference(preference);
    }

    private void showServiceActions(@NonNull Service service) {
        Context context = requireContext();
        String[] actions = {
            getString(R.string.nix_services_start),
            getString(R.string.nix_services_stop),
            getString(R.string.nix_services_restart),
            getString(R.string.nix_services_logs),
            getString(R.string.nix_services_settings)
        };
        View header = LayoutInflater.from(context).inflate(R.layout.ui_guest_service_details, null);
        ((TextView) header.findViewById(R.id.service_detail_title)).setText(service.name);
        ((TextView) header.findViewById(R.id.service_detail_summary))
            .setText(serviceRowSummary(service) + "\n" + wifiSummary(service));
        DialogUtils.builder(context)
            .setCustomTitle(header)
            .setItems(actions, (dialog, which) -> {
                if (which == 4) {
                    if (ServiceNotificationPolicy.ADB_SHELL.equals(service.name))
                        showNotificationChoice(service.name, getString(R.string.nix_services_notification_adb_title));
                    else
                        editSettings(service);
                    return;
                }
                String action = new String[]{"start", "stop", "restart", "logs"}[which];
                runServiceAction(service, action);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNotificationChoice(@NonNull String service, @NonNull String title) {
        Boolean selected = ServiceNotificationPolicy.override(ServiceNotificationPolicy.readSettings(), service);
        int current = selected == null ? 0 : selected ? 1 : 2;
        DialogUtils.builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(R.array.nix_services_notification_options, current, (dialog, which) -> {
                try {
                    JSONObject settings = null;
                    if (which != 0) settings = new JSONObject().put("notification", which == 1);
                    saveSettings(service, settings);
                    dialog.dismiss();
                } catch (JSONException e) {
                    Toast.makeText(requireContext(), getString(R.string.nix_services_settings_save_error,
                        e.getMessage()), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void runServiceAction(@NonNull Service service, @NonNull String action) {
        if (busy) return;
        boolean adbShell = ServiceNotificationPolicy.ADB_SHELL.equals(service.name);
        if (adbShell && !"logs".equals(action)) {
            CommandResult result;
            try {
                if ("stop".equals(action)) {
                    AdbShellService.stopFromSettings(requireContext());
                    result = new CommandResult(0, "");
                } else if (AdbShellService.startFromSettings(requireContext(), "restart".equals(action))) {
                    result = new CommandResult(0, "");
                } else {
                    result = new CommandResult(1, getString(R.string.nix_services_adb_enable_first));
                }
            } catch (RuntimeException e) {
                result = new CommandResult(1, e.getMessage());
            }
            showServiceActionResult(service, action, result);
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            CommandResult result = adbShell
                ? runCommand(new ProcessBuilder("/system/bin/logcat", "-b", "main", "-d", "-t", "200",
                    "-v", "time", Logger.getFullTag(AdbShellService.LOG_TAG) + ":I", "*:S"))
                : runGuestCommand("svc", action, service.name);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                setBusy(false);
                showServiceActionResult(service, action, result);
                if (!adbShell) refreshServices();
            });
        });
    }

    private void showServiceActionResult(Service service, String action, CommandResult result) {
        if (result.exitCode != 0) {
            showTextDialog(getString(R.string.nix_services_action_failed, action), concise(result.output));
        } else if ("logs".equals(action)) {
            showTextDialog(getString(R.string.nix_services_logs_title, service.name), result.output);
        } else {
            Toast.makeText(requireContext(), getString(R.string.nix_services_action_done, action, service.name),
                Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    private CommandResult runGuestCommand(@NonNull String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(TermuxNix.NIXPP_PATH);
        command.add("enter");
        command.add("--");
        for (String argument : arguments) command.add(argument);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        Context context = getContext();
        if (context == null) return new CommandResult(1, "");
        builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        return runCommand(builder);
    }

    private static CommandResult runCommand(ProcessBuilder builder) {
        Process process = null;
        try {
            builder.redirectErrorStream(true);
            process = builder.start();
            process.getOutputStream().close();
            String output = readBounded(process.getInputStream(), OUTPUT_LIMIT);
            int exit = process.waitFor();
            return new CommandResult(exit, output);
        } catch (IOException e) {
            return new CommandResult(1, e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new CommandResult(1, "Service command was interrupted.");
        }
    }

    @NonNull
    static List<Service> parseServices(@NonNull String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Service> services = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String name = item.optString("name", "");
            if (!name.matches(SERVICE_NAME_PATTERN)) {
                throw new JSONException("Invalid service name in guest response");
            }
            List<String> ssids = new ArrayList<>();
            JSONArray allowed = item.optJSONArray("allowedSsids");
            if (allowed != null)
                for (int j = 0; j < allowed.length(); j++) ssids.add(allowed.getString(j));
            JSONObject settings = item.optJSONObject("settings");
            Service service = new Service(name,
                item.optString("status", "unknown"),
                item.optBoolean("enabled", false),
                item.optBoolean("wifiRestricted", false),
                ssids, item.optBoolean("ssidsOverridden", false),
                settings != null && settings.length() > 0,
                item.optBoolean("waitingForNetwork", false),
                item.has("network") ? item.optString("network") : null);
            if (settings != null && settings.opt("notification") instanceof Boolean)
                service.notificationOverride = settings.optBoolean("notification");
            services.add(service);
        }
        return services;
    }

    @NonNull
    private static String readBounded(@NonNull InputStream input, int maximum) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int count;
            boolean truncated = false;
            while ((count = reader.read(buffer)) != -1) {
                int remaining = maximum - output.length();
                if (remaining > 0) output.append(buffer, 0, Math.min(count, remaining));
                if (count > remaining) truncated = true;
            }
            if (truncated) output.append("\n").append("…");
        }
        return output.toString();
    }

    private void showTextDialog(@NonNull String title, @NonNull String text) {
        TextView view = new TextView(requireContext());
        view.setText(TextUtils.isEmpty(text) ? getString(R.string.nix_services_no_output) : text);
        view.setTextIsSelectable(true);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.addView(view);
        DialogUtils.builder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @ColorRes
    static int serviceStatusColor(Service service) {
        if (service.waitingForNetwork) return R.color.ui_service_pending;
        switch (service.status) {
            case "running":
                return R.color.ui_service_running;
            case "failed":
            case "error":
            case "crashed":
                return R.color.ui_service_failed;
            case "starting":
            case "stopping":
            case "restarting":
                return R.color.ui_service_pending;
            default:
                return R.color.ui_service_inactive;
        }
    }

    private String serviceStatus(Service service) {
        if (service.waitingForNetwork)
            return getString(R.string.nix_services_waiting_for_wifi);
        if (service.status.isEmpty()) return service.status;
        return Character.toUpperCase(service.status.charAt(0)) + service.status.substring(1);
    }

    private String serviceRowSummary(Service service) {
        String boot = getString(service.enabled ? R.string.nix_services_boot_on : R.string.nix_services_boot_off);
        return getString(R.string.nix_services_service_summary, serviceStatus(service), boot);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (swipeRefresh != null) swipeRefresh.setRefreshing(value);
        for (int i = 0; i < servicesCategory.getPreferenceCount(); i++)
            servicesCategory.getPreference(i).setEnabled(!value);
    }

    @NonNull
    private String wifiSummary(@NonNull Service service) {
        String text;
        if (!service.wifiRestricted) {
            text = getString(R.string.nix_services_wifi_any);
        } else {
            String names = TextUtils.join(", ", service.allowedSsids);
            text = service.waitingForNetwork || "blocked".equals(service.network)
                ? getString(R.string.nix_services_wifi_waiting, names)
                : getString(R.string.nix_services_wifi_only, names);
        }
        return service.hasSettings ? getString(R.string.nix_services_set_here, text) : text;
    }

    private void editSettings(@NonNull Service service) {
        Context context = requireContext();
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);

        android.widget.Switch boot = new android.widget.Switch(context);
        boot.setText(R.string.nix_services_start_on_boot);
        boot.setChecked(service.enabled);
        layout.addView(boot);

        TextView notificationLabel = new TextView(context);
        notificationLabel.setText(R.string.nix_services_notification_label);
        layout.addView(notificationLabel);
        android.widget.Spinner notification = new android.widget.Spinner(context);
        android.widget.ArrayAdapter<CharSequence> notificationOptions = android.widget.ArrayAdapter.createFromResource(
            context, R.array.nix_services_notification_options, android.R.layout.simple_spinner_item);
        notificationOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        notification.setAdapter(notificationOptions);
        notification.setSelection(service.notificationOverride == null ? 0 : service.notificationOverride ? 1 : 2);
        layout.addView(notification);

        android.widget.Switch restricted = new android.widget.Switch(context);
        restricted.setText(R.string.nix_services_wifi_restricted);
        restricted.setChecked(service.wifiRestricted);
        layout.addView(restricted);

        TextView help = new TextView(context);
        help.setText(R.string.nix_services_wifi_restricted_help);
        layout.addView(help);

        android.widget.EditText networks = new android.widget.EditText(context);
        networks.setSingleLine(true);
        networks.setHint(R.string.nix_services_wifi_networks_hint);
        networks.setText(TextUtils.join(", ", service.allowedSsids));
        layout.addView(networks);

        DialogUtils.builder(context)
            .setTitle(getString(R.string.nix_services_settings_title, service.name))
            .setView(layout)
            .setPositiveButton(R.string.nix_services_settings_save, (dialog, which) -> {
                List<String> names = new ArrayList<>();
                for (String name : String.valueOf(networks.getText()).split(","))
                    if (!name.trim().isEmpty()) names.add(name.trim());
                if (restricted.isChecked() && names.isEmpty()) {
                    Toast.makeText(context, R.string.nix_services_settings_no_networks, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    JSONObject settings = new JSONObject();
                    settings.put("enabled", boot.isChecked());
                    settings.put("wifiRestricted", restricted.isChecked());
                    settings.put("allowedSsids", new JSONArray(names));
                    if (notification.getSelectedItemPosition() != 0)
                        settings.put("notification", notification.getSelectedItemPosition() == 1);
                    saveSettings(service.name, settings);
                } catch (JSONException e) {
                    Toast.makeText(context, getString(R.string.nix_services_settings_save_error, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                }
            })
            .setNeutralButton(R.string.nix_services_settings_default, (dialog, which) ->
                saveSettings(service.name, null))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Record the service's settings in service-state/settings.json, which the guest's
     * {@code svc} reads within a second; {@code null} returns to the configured values.
     */
    private void saveSettings(@NonNull String service, @Nullable JSONObject settings) {
        executor.execute(() -> {
            String error = null;
            File state = new File(TermuxNix.RUNTIME_DIR_PATH, "service-state");
            File target = new File(state, "settings.json");
            File temporary = new File(state, ".settings.json.tmp");
            try {
                JSONObject all = new JSONObject();
                if (target.isFile()) {
                    try (InputStream in = new java.io.FileInputStream(target)) {
                        all = new JSONObject(readBounded(in, OUTPUT_LIMIT));
                    } catch (JSONException e) {
                        all = new JSONObject();
                    }
                }
                if (settings == null) all.remove(service);
                else all.put(service, settings);
                if (!state.isDirectory() && !state.mkdirs()) throw new IOException("cannot create " + state);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) {
                    out.write((all.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!temporary.renameTo(target)) throw new IOException("cannot replace " + target);
            } catch (IOException | JSONException e) {
                error = e.getMessage();
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            String failure = error;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (failure != null) {
                    Toast.makeText(requireContext(), getString(R.string.nix_services_settings_save_error, failure),
                        Toast.LENGTH_LONG).show();
                }
                // Give svc time to observe settings before reloading the page.
                mainHandler.postDelayed(this::refreshPage, 1500);
            });
        });
    }

    @NonNull
    private static String concise(@Nullable String text) {
        if (TextUtils.isEmpty(text)) return "Unknown error";
        String trimmed = text.trim();
        int lineEnd = trimmed.indexOf('\n');
        if (lineEnd >= 0) trimmed = trimmed.substring(0, lineEnd);
        return trimmed.length() > 180 ? trimmed.substring(0, 177) + "…" : trimmed;
    }

    static final class Service {
        final String name;
        final String status;
        /** Start on boot, as in effect (the app's setting or the configuration). */
        final boolean enabled;
        final boolean wifiRestricted;
        /** The effective Wi-Fi allow-list. */
        final List<String> allowedSsids;
        /** Whether the list was set on this page rather than in the configuration. */
        final boolean ssidsOverridden;
        /** Whether this page set any setting for the service. */
        final boolean hasSettings;
        final boolean waitingForNetwork;
        /** "allowed", "blocked", or null for an unrestricted service. */
        @Nullable final String network;
        @Nullable Boolean notificationOverride;

        Service(@NonNull String name, @NonNull String status, boolean enabled) {
            this(name, status, enabled, false, new ArrayList<>(), false, false, false, null);
        }

        Service(@NonNull String name, @NonNull String status, boolean enabled, boolean wifiRestricted,
                @NonNull List<String> allowedSsids, boolean ssidsOverridden, boolean hasSettings,
                boolean waitingForNetwork, @Nullable String network) {
            this.name = name;
            this.status = status;
            this.enabled = enabled;
            this.wifiRestricted = wifiRestricted;
            this.allowedSsids = allowedSsids;
            this.ssidsOverridden = ssidsOverridden;
            this.hasSettings = hasSettings;
            this.waitingForNetwork = waitingForNetwork;
            this.network = network;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        @Nullable final String output;

        CommandResult(int exitCode, @Nullable String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
