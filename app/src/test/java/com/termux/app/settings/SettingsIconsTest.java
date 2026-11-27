package com.termux.app.settings;

import android.content.res.XmlResourceParser;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsIconsTest {
    @Test public void staticSettingsRowsDeclareLoadableIcons() throws Exception {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        int[] screens = {R.xml.root_preferences, R.xml.about_preferences, R.xml.companion_apps_preferences,
            R.xml.termux_preferences, R.xml.termux_debugging_preferences,
            R.xml.termux_terminal_io_preferences, R.xml.termux_terminal_view_preferences,
            R.xml.termux_api_preferences, R.xml.termux_api_debugging_preferences,
            R.xml.termux_tasker_preferences, R.xml.termux_tasker_debugging_preferences,
            R.xml.termux_widget_preferences, R.xml.termux_widget_debugging_preferences,
            R.xml.adb_shell_authorizations_preferences, R.xml.adb_shell_authorization_preferences};
        for (int screen : screens) {
            try (XmlResourceParser xml = activity.getResources().getXml(screen)) {
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.getEventType() != XmlPullParser.START_TAG
                        || xml.getName().equals("PreferenceScreen") || xml.getName().equals("PreferenceCategory")) continue;
                    int icon = xml.getAttributeResourceValue("http://schemas.android.com/apk/res-auto", "icon", 0);
                    String row = activity.getResources().getResourceEntryName(screen) + ":" + xml.getPositionDescription();
                    assertNotEquals(row, 0, icon);
                    assertNotNull(row, androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, icon));
                }
            }
        }
    }

    @Test public void everyGeneratedPropertyRowHasAnIconButCategoryHeadingsDoNot() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PropertiesPreferencesFragment fragment = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, fragment).commitNow();
        for (String key : dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            if (dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigSchema.isDisplayKey(key)) continue;
            Preference preference = fragment.findPreference(key);
            assertNotNull(key, preference);
            assertNotNull(key, preference.getIcon());
        }
        assertIcons(fragment.getPreferenceScreen());
    }

    private void assertIcons(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceCategory) {
                assertNull(preference.getTitle().toString(), preference.getIcon());
                assertIcons((PreferenceGroup) preference);
            } else {
                assertNotNull(preference.getTitle().toString(), preference.getIcon());
            }
        }
    }
}
