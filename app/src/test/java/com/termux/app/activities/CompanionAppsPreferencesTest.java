package com.termux.app.activities;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.fragments.settings.CompanionAppsPreferencesFragment;
import com.termux.app.fragments.settings.termux.TerminalViewPreferencesFragment;
import com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CompanionAppsPreferencesTest {

    @Test
    public void rootSettingsOmitsReadOnlyIntegrationEntries() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Fragment rootFragment = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        Assert.assertTrue(rootFragment instanceof PreferenceFragmentCompat);
        PreferenceFragmentCompat root = (PreferenceFragmentCompat) rootFragment;

        Assert.assertNull(root.findPreference("companion_apps"));
        Assert.assertNull(root.findPreference("termux_api"));
        Assert.assertNull(root.findPreference("termux_tasker"));
        Assert.assertNull(root.findPreference("termux_float"));
        Assert.assertNull(root.findPreference("termux_boot"));
        Assert.assertNull(root.findPreference("termux_styling"));
        Assert.assertNull(root.findPreference("termux_widget"));
    }

    @Test
    public void navigatingAndGoingBackUpdatesTheToolbarTitle() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = (PreferenceFragmentCompat)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        Preference services = root.findPreference("termux_services");
        Assert.assertTrue(activity.onPreferenceStartFragment(root, services));
        activity.getSupportFragmentManager().executePendingTransactions();

        Assert.assertEquals(services.getTitle(), activity.getTitle());
        Assert.assertTrue(activity.getSupportFragmentManager().popBackStackImmediate());
        Assert.assertEquals(activity.getString(R.string.title_activity_termux_settings), activity.getTitle());
    }

    @Test
    public void displayContainsAppearanceAndViewControls() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TerminalViewPreferencesFragment display = new TerminalViewPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, display).commitNow();

        Assert.assertNotNull(display.findPreference("terminal_color_scheme"));
        Assert.assertNotNull(display.findPreference("terminal_font"));
        Assert.assertNotNull(display.findPreference("app_accent"));
        Assert.assertNotNull(display.findPreference("terminal_margin_adjustment"));
        Assert.assertNull(display.findPreference("termux_styling"));
    }

    @Test
    public void debuggingContainsIntegrationLogLevels() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        DebuggingPreferencesFragment debugging = new DebuggingPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, debugging).commitNow();

        for (String key : new String[]{"api_log_level", "tasker_log_level", "float_log_level", "widget_log_level"}) {
            ListPreference level = debugging.findPreference(key);
            Assert.assertNotNull(level);
            Assert.assertNotNull(level.getEntries());
        }
        Assert.assertNotNull(debugging.findPreference("float_key_logging"));
    }

    @Test
    public void companionScreenIncludesEverySupportedTermuxppApp() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        CompanionAppsPreferencesFragment fragment = new CompanionAppsPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, fragment).commitNow();

        Assert.assertNotNull(fragment.findPreference("termux_api"));
        Assert.assertNotNull(fragment.findPreference("termux_tasker"));
        Assert.assertNotNull(fragment.findPreference("termux_float"));
        Assert.assertNull(fragment.findPreference("termux_boot"));
        Assert.assertNull(fragment.findPreference("termux_styling"));
        Assert.assertNotNull(fragment.findPreference("termux_widget"));

        for (String key : new String[]{"termux_api", "termux_tasker", "termux_float", "termux_widget"}) {
            Preference companion = fragment.findPreference(key);
            Assert.assertNotNull(companion.getIcon());
            Assert.assertTrue(companion.isVisible());
            Assert.assertFalse(companion.isSelectable());
        }
    }
}
