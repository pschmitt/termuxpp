package com.termux.app.fragments.settings.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.TermuxPreferencesFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
        assertNotNull(services.findPreference("services_status"));
        assertEquals(R.layout.ui_guest_services_refresh,
            services.findPreference("services_status").getWidgetLayoutResource());
        assertNotNull(services.findPreference("services_list"));
        assertNotNull(services.findPreference("boot_scripts"));
        assertNotNull(services.findPreference("boot_script_setup"));
    }
}
