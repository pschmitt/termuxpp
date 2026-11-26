package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;

import com.termux.app.event.SystemEventReceiver;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AdbShellRecoveryTest {
    private Application application;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(AdbShellService.PREFS_NAME, 0).edit().clear().commit();
    }

    private SharedPreferences preferences() {
        return application.getSharedPreferences(AdbShellService.PREFS_NAME, 0);
    }

    @Test public void disabledAccessIsNeverRecoveredOrModified() {
        preferences().edit().putString(AdbShellService.PREF_TOKEN, "stale-token").commit();
        AtomicInteger starts = new AtomicInteger();
        assertFalse(AdbShellRecovery.recover(application, "test", context -> starts.incrementAndGet()));
        assertEquals(0, starts.get());
        assertFalse(preferences().getBoolean(AdbShellService.PREF_ENABLED, false));
        assertEquals("stale-token", preferences().getString(AdbShellService.PREF_TOKEN, null));
    }

    @Test public void missingPreferencesDoNotEnableOrCreateToken() {
        AtomicInteger starts = new AtomicInteger();
        assertFalse(AdbShellRecovery.recover(application, "test", context -> starts.incrementAndGet()));
        assertEquals(0, starts.get());
        assertFalse(preferences().contains(AdbShellService.PREF_ENABLED));
        assertFalse(preferences().contains(AdbShellService.PREF_TOKEN));
    }

    @Test public void enabledWithoutTokenOrTlsMaterialIsNotRecovered() {
        preferences().edit().putBoolean(AdbShellService.PREF_ENABLED, true).commit();
        AtomicInteger starts = new AtomicInteger();
        assertFalse(AdbShellRecovery.recover(application, "test", context -> starts.incrementAndGet()));
        assertEquals(0, starts.get());
        assertFalse(preferences().contains(AdbShellService.PREF_TOKEN));
    }

    @Test public void enabledWithTokenIsRecoveredWithoutRotatingToken() {
        preferences().edit().putBoolean(AdbShellService.PREF_ENABLED, true)
            .putString(AdbShellService.PREF_TOKEN, "existing-token").commit();
        AtomicInteger starts = new AtomicInteger();
        assertTrue(AdbShellRecovery.recover(application, "test", context -> starts.incrementAndGet()));
        assertEquals(1, starts.get());
        assertEquals("existing-token", preferences().getString(AdbShellService.PREF_TOKEN, null));
    }

    @Test public void enabledWithProvisionedMaterialIsRecoveredWithoutToken() throws Exception {
        preferences().edit().putBoolean(AdbShellService.PREF_ENABLED, true).commit();
        File directory = new File(application.getFilesDir(), "adb-shell");
        assertTrue(directory.mkdirs() || directory.isDirectory());
        for (String name : new String[]{"adb-shell-server.pk8", "adb-shell-server.crt", "adb-shell-client-ca.crt"}) {
            assertTrue(new File(directory, name).createNewFile() || new File(directory, name).isFile());
        }
        assertTrue(AdbShellRecovery.recover(application, "test", context -> {}));
        assertFalse(preferences().contains(AdbShellService.PREF_TOKEN));
    }

    @Test public void manualStopPreservesAccessAndSuppressesRecoveryUntilStart() {
        preferences().edit().putBoolean(AdbShellService.PREF_ENABLED, true)
            .putString(AdbShellService.PREF_TOKEN, "existing-token").commit();

        AdbShellService.stopFromSettings(application);
        assertFalse(AdbShellRecovery.isRecoverable(application));
        assertTrue(preferences().getBoolean(AdbShellService.PREF_ENABLED, false));
        assertEquals("existing-token", preferences().getString(AdbShellService.PREF_TOKEN, null));

        assertTrue(AdbShellService.startFromSettings(application, false));
        assertTrue(AdbShellRecovery.isRecoverable(application));
        assertEquals(AdbShellService.ACTION_START, shadowOf(application).getNextStartedService().getAction());
    }

    @Test public void starterFailureIsContained() {
        preferences().edit().putBoolean(AdbShellService.PREF_ENABLED, true)
            .putString(AdbShellService.PREF_TOKEN, "existing-token").commit();
        assertFalse(AdbShellRecovery.recover(application, "test", context -> {
            throw new IllegalStateException("background start not allowed");
        }));
    }

    @Test public void packageReplacedStartsEnabledServiceOnly() {
        SystemEventReceiver receiver = new SystemEventReceiver();
        receiver.onReceive(application, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertNull(shadowOf(application).getNextStartedService());

        preferences().edit().putBoolean(AdbShellService.PREF_ENABLED, true)
            .putString(AdbShellService.PREF_TOKEN, "existing-token").commit();
        receiver.onReceive(application, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        Intent started = shadowOf(application).getNextStartedService();
        assertNotNull(started);
        assertEquals(AdbShellService.ACTION_START, started.getAction());
        assertEquals(AdbShellService.class.getName(), started.getComponent().getClassName());
    }
}
