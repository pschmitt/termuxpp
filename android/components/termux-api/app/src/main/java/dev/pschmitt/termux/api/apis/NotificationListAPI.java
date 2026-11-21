package dev.pschmitt.termux.api.apis;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.JsonWriter;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

import dev.pschmitt.termux.api.TermuxApiReceiver;
import dev.pschmitt.termux.api.util.ResultReturner;
import dev.pschmitt.termux.api.util.ResultReturner.ResultJsonWriter;
import dev.pschmitt.termux.shared.logger.Logger;


public class NotificationListAPI {

    private static final String LOG_TAG = "NotificationListAPI";

    /** Name of the key of the JSON object returned instead of the list when it cannot be produced. */
    static final String ERROR_KEY = "API_ERROR";

    static final String ERROR_ACCESS_DISABLED =
            "Notification access is not granted to TermuxPP. Enable it in the Android settings "
                    + "(Notification access), then run the command again.";

    static final String ERROR_NOT_CONNECTED =
            "The notification listener of TermuxPP is not connected. Toggle Notification access "
                    + "for TermuxPP off and on in the Android settings, then run the command again.";

    /** How long to wait for the listener service to (re)connect before giving up. */
    private static final long CONNECT_TIMEOUT_MS = 3000;
    private static final long CONNECT_POLL_MS = 100;

    /**
     * Always answers the client: with the notifications, or with a JSON object holding
     * {@link #ERROR_KEY} when the listener is not usable. Never leave the client waiting, since the
     * `tpp` command blocks until the result is returned.
     */
    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final boolean accessEnabled = isListenerEnabled(context);
        if (!accessEnabled) {
            // Best effort hint for the user, this must not prevent the error from being returned.
            try {
                Toast.makeText(context, "Please give TermuxPP Notification Access", Toast.LENGTH_LONG).show();
                context.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open the notification access settings", e);
            }
        }

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                if (!accessEnabled) {
                    writeError(out, ERROR_ACCESS_DISABLED);
                    return;
                }
                listNotifications(context, out);
            }
        });
    }


    static boolean isListenerEnabled(Context context) {
        ComponentName cn = new ComponentName(context, NotificationService.class);
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        for (String entry : flat.split(":")) {
            if (cn.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }


    static void writeError(JsonWriter out, String message) throws Exception {
        out.beginObject().name(ERROR_KEY).value(message).endObject();
    }


    /**
     * Get the connected listener service. Access may be granted while the service is not bound
     * (after an update, a crash or a revoked and re-granted permission), so ask for a rebind and
     * wait briefly. Must not be called on the main thread.
     */
    private static NotificationService awaitService(Context context) throws InterruptedException {
        NotificationService service = NotificationService.get();
        if (service != null) return service;

        try {
            NotificationListenerService.requestRebind(new ComponentName(context, NotificationService.class));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request a rebind of the listener", e);
        }
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while ((service = NotificationService.get()) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(CONNECT_POLL_MS);
        }
        return service;
    }


    static void listNotifications(Context context, JsonWriter out) throws Exception {
        NotificationService notificationService = awaitService(context);
        if (notificationService == null) {
            writeError(out, ERROR_NOT_CONNECTED);
            return;
        }

        StatusBarNotification[] notifications;
        try {
            notifications = notificationService.getActiveNotifications();
        } catch (SecurityException e) {
            writeError(out, ERROR_ACCESS_DISABLED);
            return;
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get the active notifications", e);
            writeError(out, "Failed to get the active notifications: " + e);
            return;
        }
        if (notifications == null) {
            writeError(out, ERROR_NOT_CONNECTED);
            return;
        }

        out.beginArray();
        for (StatusBarNotification n : notifications) {
            int id = n.getId();
            String key = "";
            String title = "";
            String text = "";
            CharSequence[] lines = null;
            String packageName = "";
            String tag = "";
            String group = "";
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String when = dateFormat.format(new Date(n.getNotification().when));

            if (n.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE) != null) {
                title = n.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString();
            }
            if (n.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null) {
                text = n.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();
            } else if (n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT) != null) {
                text = n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT).toString();
            }
            if (n.getNotification().extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) != null) {
                lines = n.getNotification().extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            }
            if (n.getTag() != null) {
                tag = n.getTag();
            }
            if (n.getNotification().getGroup() != null) {
                group = n.getNotification().getGroup();
            }
            if (n.getKey() != null) {
                key = n.getKey();
            }
            if (n.getPackageName() != null) {
                packageName = n.getPackageName();
            }
            out.beginObject()
                    .name("id").value(id)
                    .name("tag").value(tag)
                    .name("key").value(key)
                    .name("group").value(group)
                    .name("packageName").value(packageName)
                    .name("title").value(title)
                    .name("content").value(text)
                    .name("when").value(when);
            if (lines != null) {
                out.name("lines").beginArray();
                for (CharSequence line : lines) {
                    out.value(line.toString());
                }
                out.endArray();
            }
            out.endObject();
        }
        out.endArray();
    }



    public static class NotificationService extends NotificationListenerService {
        static volatile NotificationService _this;

        public static NotificationService get() {
            return _this;
        }

        @Override
        public void onListenerConnected() {
            _this = this;
        }

        @Override
        public void onListenerDisconnected() {
            _this = null;
        }
    }

}
