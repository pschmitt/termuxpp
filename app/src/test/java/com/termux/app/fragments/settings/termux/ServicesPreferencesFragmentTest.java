package com.termux.app.fragments.settings.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import com.termux.R;

import org.junit.Test;

import java.util.List;

public class ServicesPreferencesFragmentTest {
    @Test
    public void parsesServiceState() throws Exception {
        List<ServicesPreferencesFragment.Service> services =
            ServicesPreferencesFragment.parseServices(
                "[{\"name\":\"sshd\",\"status\":\"running\",\"enabled\":true}]");

        assertEquals(1, services.size());
        assertEquals("sshd", services.get(0).name);
        assertEquals("running", services.get(0).status);
        assertTrue(services.get(0).enabled);
    }

    @Test
    public void rejectsUnsafeServiceNames() throws Exception {
        try {
            ServicesPreferencesFragment.parseServices(
                "[{\"name\":\"sshd; rm -rf /\",\"status\":\"running\",\"enabled\":true}]");
            fail("service names must stay a single argument");
        } catch (JSONException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void parsesBootAndWifiSettings() throws Exception {
        List<ServicesPreferencesFragment.Service> services =
            ServicesPreferencesFragment.parseServices(
                "[{\"name\":\"sshd\",\"status\":\"down\",\"enabled\":false,\"wifiRestricted\":true,"
                    + "\"allowedSsids\":[\"home\",\"work\"],\"ssidsOverridden\":true,\"waitingForNetwork\":true,"
                    + "\"settings\":{\"enabled\":false},\"network\":\"blocked\"},"
                    + "{\"name\":\"other\",\"status\":\"running\",\"enabled\":true}]");

        ServicesPreferencesFragment.Service sshd = services.get(0);
        assertFalse(sshd.enabled);
        assertTrue(sshd.wifiRestricted);
        assertEquals(java.util.Arrays.asList("home", "work"), sshd.allowedSsids);
        assertTrue(sshd.ssidsOverridden);
        assertTrue(sshd.hasSettings);
        assertTrue(sshd.waitingForNetwork);
        assertEquals("blocked", sshd.network);

        ServicesPreferencesFragment.Service other = services.get(1);
        assertTrue(other.enabled);
        assertFalse(other.wifiRestricted);
        assertTrue(other.allowedSsids.isEmpty());
        assertFalse(other.hasSettings);
        assertNull(other.network);
    }
    @Test
    public void serviceStatusColorsDistinguishRunningFailurePendingAndInactive() {
        assertEquals(R.color.ui_service_running, serviceStatusColor("running"));
        assertEquals(R.color.ui_service_failed, serviceStatusColor("failed"));
        assertEquals(R.color.ui_service_pending, serviceStatusColor("starting"));
        assertEquals(R.color.ui_service_inactive, serviceStatusColor("stopped"));
        assertEquals(R.color.ui_service_inactive, serviceStatusColor("unknown"));
    }

    private int serviceStatusColor(String status) {
        return ServicesPreferencesFragment.serviceStatusColor(
            new ServicesPreferencesFragment.Service("example", status, true));
    }

}
