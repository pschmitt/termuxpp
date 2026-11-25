package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.termux.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import dev.pschmitt.termux.shared.termux.TermuxConstants;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class AdbShellForegroundNotificationTest {
    @Test
    public void reusesTheExistingTerminalForegroundNotification() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf((Application) context).grantPermissions(
            context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID;
        manager.createNotificationChannel(new NotificationChannel(channel, "TermuxPP", NotificationManager.IMPORTANCE_LOW));
        Notification original = new Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_service_notification).setContentTitle("Existing terminal").build();
        manager.notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, original);

        org.robolectric.android.controller.ServiceController<AdbShellService> controller =
            Robolectric.buildService(AdbShellService.class).create();
        AdbShellService service = controller.get();
        assertEquals(TermuxConstants.TERMUX_APP_NOTIFICATION_ID,
            shadowOf(service).getLastForegroundNotificationId());
        assertEquals("Existing terminal",
            shadowOf(service).getLastForegroundNotification().extras.getCharSequence(Notification.EXTRA_TITLE));
        controller.destroy();
    }
}
