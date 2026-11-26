package com.termux.app.fragments.settings.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.content.ClipboardManager;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.TermuxPreferencesFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ServicesPreferencesFragmentUiTest {
    @Test
    public void termuxSettingsExposeServicesAndServicePage() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TermuxPreferencesFragment preferences = new TermuxPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction()
            .replace(R.id.settings, preferences)
            .commitNow();
        assertEquals(ServicesPreferencesFragment.class.getName(),
            preferences.findPreference("termux_services").getFragment());

        ServicesPreferencesFragment services = new ServicesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction()
            .replace(R.id.settings, services)
            .commitNow();
        assertTrue(services.requireView() instanceof SwipeRefreshLayout);
        assertNull(services.findPreference("services_status"));
        assertNotNull(services.findPreference("services_list"));
        assertNotNull(services.findPreference("boot_scripts"));
        assertNull(services.findPreference("boot_script_example"));

        Preference help = services.findPreference("boot_script_setup");
        assertNotNull(help);
        assertEquals("boot_scripts", help.getParent().getKey());
        assertEquals(R.layout.ui_boot_script_help_icon, help.getWidgetLayoutResource());
        help.performClick();
        Dialog setup = ShadowDialog.getLatestDialog();
        assertTrue(setup.isShowing());
        assertTrue(setup.findViewById(R.id.boot_script_copy).performClick());
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        assertEquals(activity.getString(R.string.boot_script_example),
            clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertTrue(setup.isShowing());
    }

    @Test
    public void adbShellOpensTheServiceMenuWithoutTheGuest() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ServicesPreferencesFragment services = new ServicesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction()
            .replace(R.id.settings, services)
            .commitNow();

        Preference adbShell = services.findPreference("adb-shell");
        assertNotNull(adbShell);
        assertTrue(adbShell.getSummary().toString().contains(activity.getString(R.string.nix_services_boot_off)));
        adbShell.performClick();
        AlertDialog actions = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(actions.isShowing());
        int[] labels = {R.string.nix_services_start, R.string.nix_services_stop,
            R.string.nix_services_restart, R.string.nix_services_logs, R.string.nix_services_settings};
        assertEquals(labels.length, actions.getListView().getCount());
        for (int i = 0; i < labels.length; i++)
            assertEquals(activity.getString(labels[i]), actions.getListView().getItemAtPosition(i));
    }
}
