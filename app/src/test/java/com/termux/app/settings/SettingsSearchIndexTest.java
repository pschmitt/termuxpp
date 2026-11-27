package com.termux.app.settings;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;

import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SettingsSearchIndexTest {

    @Test
    public void indexesPreferencesInChildScreensAndDynamicProperties() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        List<SettingsSearchIndex.Result> results = SettingsSearchIndex.build(activity);

        SettingsSearchIndex.Result tripleTap = resultFor(results, "terminal-triple-tap-action");
        assertNotNull(tripleTap);
        // Titled for people; the path gestures.tap.triple.action is searchable too.
        assertEquals("Triple tap: action", tripleTap.title);
        assertTrue(tripleTap.matches("gestures.tap.triple.action"));
        assertEquals("com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment",
            tripleTap.fragmentPath.get(0));
        assertTrue(tripleTap.matches("triple"));
        assertTrue(tripleTap.matches("triple tap action"));

        SettingsSearchIndex.Result tripleFocusTitle = resultFor(results,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE);
        assertNotNull(tripleFocusTitle);
        assertTrue(tripleFocusTitle.breadcrumb.endsWith(activity.getString(R.string.properties_gestures)));
        assertEquals(R.string.properties_gestures,
            PropertiesPreferencesFragment.categoryFor(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE));

        SettingsSearchIndex.Result keyboard = resultFor(results,
            TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR);
        assertNotNull(keyboard);
        assertTrue(keyboard.breadcrumb.endsWith(activity.getString(R.string.properties_keyboard)));

        SettingsSearchIndex.Result logLevel = resultFor(results, "api_log_level");
        assertNotNull(logLevel);
        assertTrue(logLevel.title + " :: " + logLevel.breadcrumb, logLevel.matches("api log level"));
        assertEquals("com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment",
            logLevel.fragmentPath.get(0));
    }

    @Test
    public void everySettingHasAWrittenTitle() {
        for (String path : dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigSchema.paths().keySet())
            assertNotNull(path, SettingTitles.forPath(path));
    }

    @Test
    public void doesNotIndexConfiguredPropertyValues() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        for (SettingsSearchIndex.Result result : SettingsSearchIndex.build(activity)) {
            assertTrue(result.title, !result.title.contains("printf"));
            assertTrue(result.breadcrumb, !result.breadcrumb.contains("secret command"));
        }
    }

    private static SettingsSearchIndex.Result resultFor(List<SettingsSearchIndex.Result> results, String key) {
        for (SettingsSearchIndex.Result result : results) {
            if (key.equals(result.preferenceKey)) return result;
        }
        return null;
    }
}
