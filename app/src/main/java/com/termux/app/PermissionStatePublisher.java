package com.termux.app;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

/**
 * Publishes which permissions TermuxPP holds to {@code permissions.json} in the nixpp runtime
 * directory, so {@code termuxpp-setup-permissions status} works without Shizuku: an app cannot
 * read another view of its own grants from the guest.
 *
 * <p>{@code runtime} maps each requested dangerous permission to its grant state; {@code special}
 * maps the special access Android grants on separate settings pages (overlay, storage, install,
 * alarms, usage, settings, battery) to its state, for those the app requests.
 */
public final class PermissionStatePublisher {

    private static final String LOG_TAG = "PermissionStatePublisher";
    private static final String FILE_NAME = "permissions.json";

    private final Context context;
    private final HandlerThread thread = new HandlerThread("termuxpp-permission-state");
    private Handler handler;
    // Published on start, when the app returns to the foreground and when
    // termuxpp-setup-permissions asks (ShizukuCommandReceiver), not on a timer:
    // nothing reads the file in between.
    private final Runnable refresh = this::publish;

    public PermissionStatePublisher(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(refresh);
    }

    /** Publish once now, off the calling thread (for a receiver without the service at hand). */
    public static void publishNow(@NonNull Context context) {
        PermissionStatePublisher publisher = new PermissionStatePublisher(context);
        new Thread(publisher::publish, "termuxpp-permission-state-once").start();
    }

    /** Publish again soon, for example after the user answered a permission prompt. */
    public void refreshSoon() {
        if (handler != null) handler.post(refresh);
    }

    public void stop() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void publish() {
        File runtime = new File(TermuxNix.RUNTIME_DIR_PATH);
        if (!runtime.isDirectory()) return;
        File target = new File(runtime, FILE_NAME);
        File temporary = new File(runtime, "." + FILE_NAME + ".tmp");
        try {
            JSONObject state = new JSONObject();
            state.put("updated", System.currentTimeMillis() / 1000);
            state.put("sdk", Build.VERSION.SDK_INT);
            Set<String> requested = requestedPermissions();
            state.put("runtime", runtimePermissions(requested));
            state.put("special", specialAccess(requested));
            // How far the privileged path is: termuxpp-setup-permissions sets Shizuku up first.
            state.put("shizuku", com.termux.app.update.ShizukuInstaller.state(context).name());
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write((state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temporary.setReadable(false, false) || !temporary.setReadable(true, true)
                || !temporary.setWritable(false, false) || !temporary.setWritable(true, true)) {
                Logger.logWarn(LOG_TAG, "Cannot restrict " + temporary);
            }
            if (!temporary.renameTo(target)) throw new IOException("rename failed");
        } catch (IOException | JSONException | RuntimeException e) {
            Logger.logWarn(LOG_TAG, "Cannot publish the permission state: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    @NonNull
    private Set<String> requestedPermissions() {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return new HashSet<>();
            return new HashSet<>(Arrays.asList(info.requestedPermissions));
        } catch (PackageManager.NameNotFoundException e) {
            return new HashSet<>();
        }
    }

    @NonNull
    private JSONObject runtimePermissions(@NonNull Set<String> requested) throws JSONException {
        JSONObject result = new JSONObject();
        PackageManager packageManager = context.getPackageManager();
        for (String permission : requested) {
            PermissionInfo info;
            try {
                info = packageManager.getPermissionInfo(permission, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if ((info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) != PermissionInfo.PROTECTION_DANGEROUS)
                continue;
            result.put(permission, ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED);
        }
        return result;
    }

    @NonNull
    private JSONObject specialAccess(@NonNull Set<String> requested) throws JSONException {
        JSONObject result = new JSONObject();
        int sdk = Build.VERSION.SDK_INT;
        result.put("notification-access", NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.getPackageName()));
        if (sdk >= 23 && requested.contains("android.permission.SYSTEM_ALERT_WINDOW"))
            result.put("overlay", Settings.canDrawOverlays(context));
        if (sdk >= 30 && requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
            result.put("storage", Environment.isExternalStorageManager());
        if (sdk >= 26 && requested.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
            result.put("install", context.getPackageManager().canRequestPackageInstalls());
        if (sdk >= 31 && requested.contains("android.permission.SCHEDULE_EXACT_ALARM")) {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            result.put("alarms", alarms != null && alarms.canScheduleExactAlarms());
        }
        if (requested.contains("android.permission.PACKAGE_USAGE_STATS")) {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps != null) {
                //noinspection deprecation
                int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
                result.put("usage", mode == AppOpsManager.MODE_ALLOWED);
            }
        }
        if (sdk >= 23 && requested.contains("android.permission.WRITE_SETTINGS"))
            result.put("settings", Settings.System.canWrite(context));
        if (sdk >= 23 && requested.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            result.put("battery", power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()));
        }
        // Android 12L+ kills app child processes (the nixpp controller, guest sessions) beyond a
        // device-wide limit unless "Disable child process restrictions" is set.
        if (sdk >= 32) {
            String monitor = Settings.Global.getString(context.getContentResolver(),
                "settings_enable_monitor_phantom_procs");
            result.put("processes", "false".equals(monitor));
        }
        return result;
    }
}
