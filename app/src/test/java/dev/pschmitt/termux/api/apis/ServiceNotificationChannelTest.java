package dev.pschmitt.termux.api.apis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ServiceNotificationChannelTest {
    @Test
    public void createsDistinctLowImportanceChannels() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (String service : new String[]{"sshd", "example"}) {
            Intent intent = new Intent().putExtra("channel", "nixpp-service-" + service)
                .putExtra("priority", "low");
            NotificationAPI.createDefaultChannel(manager, intent);
            NotificationChannel channel = manager.getNotificationChannel("nixpp-service-" + service);
            assertNotNull(channel);
            assertEquals("Service: " + service, channel.getName());
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        }
    }
}
