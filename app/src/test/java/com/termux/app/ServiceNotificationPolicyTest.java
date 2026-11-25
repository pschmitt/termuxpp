package com.termux.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ServiceNotificationPolicyTest {
    @Test
    public void serviceOverrideWinsOverGlobalAndConfiguredDefault() throws Exception {
        JSONObject settings = new JSONObject("{\"_defaults\":{\"notification\":true},"
            + "\"adb-shell\":{\"notification\":false}}");
        assertFalse(ServiceNotificationPolicy.enabled(settings, "adb-shell", true));
        assertTrue(ServiceNotificationPolicy.enabled(settings, "sshd", false));
        settings.getJSONObject("_defaults").put("notification", false);
        settings.getJSONObject("adb-shell").put("notification", true);
        assertTrue(ServiceNotificationPolicy.enabled(settings, "adb-shell", false));
        assertFalse(ServiceNotificationPolicy.enabled(settings, "sshd", true));
    }

    @Test
    public void unsetAndInvalidValuesUseConfiguredDefault() throws Exception {
        JSONObject settings = new JSONObject("{\"_defaults\":{\"notification\":\"invalid\"}}");
        assertTrue(ServiceNotificationPolicy.enabled(settings, "adb-shell", true));
        assertFalse(ServiceNotificationPolicy.enabled(settings, "adb-shell", false));
    }

    @Test
    public void configuredServiceOverridesGlobalButAppServiceSettingWins() throws Exception {
        JSONObject settings = new JSONObject("{\"_defaults\":{\"notification\":true}}");
        assertFalse(ServiceNotificationPolicy.enabled(settings, "adb-shell", true, false));
        settings.put("adb-shell", new JSONObject().put("notification", true));
        assertTrue(ServiceNotificationPolicy.enabled(settings, "adb-shell", false, false));
    }
}
