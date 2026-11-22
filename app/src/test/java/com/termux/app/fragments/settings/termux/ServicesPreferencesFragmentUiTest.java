package com.termux.app.fragments.settings.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.PopupMenu;

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
        assertNull(services.findPreference("boot_script_setup"));
        assertNull(services.findPreference("boot_script_example"));

        Menu menu = new PopupMenu(activity, services.requireView()).getMenu();
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu);
        MenuItem help = menu.findItem(R.id.boot_script_help);
        assertNotNull(help);
        assertTrue(activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, help));
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
    }
}
