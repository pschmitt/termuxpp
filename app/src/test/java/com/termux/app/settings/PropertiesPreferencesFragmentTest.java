package com.termux.app.settings;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.TermuxPreferencesFragment;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PropertiesPreferencesFragmentTest {
    @Test public void settingsLinksToCompleteFileBackedEditor() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TermuxPreferencesFragment settings = new TermuxPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, settings).commitNow();
        assertEquals(PropertiesPreferencesFragment.class.getName(), settings.findPreference("termux_properties").getFragment());
        PropertiesPreferencesFragment editor = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, editor).commitNow();
        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            Preference preference = editor.findPreference(key);
            assertNotNull(key, preference);
            assertFalse(key, preference.isPersistent());
        }
        assertTrue(editor.findPreference("fullscreen") instanceof ListPreference);
        assertTrue(editor.findPreference("terminal-single-tap-action") instanceof ListPreference);
        assertFalse(editor.findPreference("extra-keys") instanceof EditTextPreference);
        assertTrue(editor.findPreference("extra-keys-style") instanceof ListPreference);
        assertNotNull(((ListPreference) editor.findPreference(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS)).getDialogMessage());
    }

    @Test public void theYamlFileIsTheOnlySettingsFile() {
        assertTrue(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH.endsWith("/.config/termuxpp/config.yaml"));
        assertEquals(java.util.Collections.singletonList(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH),
            TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST);
        assertEquals(TermuxConstants.TERMUX_LEGACY_PROPERTIES_FILE_PATH, TermuxConstants.TERMUX_LEGACY_PROPERTIES_FILE_PATHS_LIST.get(0));
        assertEquals(4, TermuxConstants.TERMUX_LEGACY_PROPERTIES_FILE_PATHS_LIST.size());
    }
}
