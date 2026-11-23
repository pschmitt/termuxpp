package com.termux.app.activities;

import android.os.Looper;
import android.view.View;
import android.widget.EditText;

import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;


import com.termux.R;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;
import com.termux.app.fragments.settings.termux.PropertyChoiceDialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class SettingsActivitySearchTest {

    @Test
    public void focusAndTypingKeepTheSearchFieldInItsHeader() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = (PreferenceFragmentCompat)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        View bar = activity.findViewById(R.id.settings_search_bar);
        EditText field = activity.findViewById(R.id.settings_search_query);
        assertEquals(bar, field.getParent());

        field.requestFocus();
        field.setText("triple tap action");
        assertNotNull(root.findPreference("settings-search-result-0"));
        assertEquals(bar, field.getParent());
        assertEquals(View.VISIBLE, bar.getVisibility());

        activity.getOnBackPressedDispatcher().onBackPressed();
        assertEquals("", field.getText().toString());
        assertNull(root.findPreference("settings-search-result-0"));
        assertEquals(root, activity.getSupportFragmentManager().findFragmentById(R.id.settings));
    }

    @Test
    public void rootSearchOpensTheExactDynamicPropertyEditor() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = (PreferenceFragmentCompat)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        activity.startSearch(root);
        activity.filterSearch("triple tap action");
        Preference result = root.findPreference("settings-search-result-0");
        assertNotNull(result);
        assertEquals("Terminal triple tap action", result.getTitle());
        result.performClick();
        activity.getSupportFragmentManager().executePendingTransactions();

        Fragment current = activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        assertTrue(current instanceof PropertiesPreferencesFragment);
        PropertiesPreferencesFragment properties = (PropertiesPreferencesFragment) current;
        assertNotNull(properties.findPreference("terminal-triple-tap-action"));
        shadowOf(Looper.getMainLooper()).idle();
        activity.getSupportFragmentManager().executePendingTransactions();
        Fragment dialog = activity.getSupportFragmentManager().findFragmentByTag("property_choice");
        assertTrue(dialog instanceof PropertyChoiceDialog);
        assertEquals("terminal-triple-tap-action", dialog.requireArguments().getString("key"));
    }

    @Test
    public void gestureStylePickerPreviewsBeforeSaving() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = (PreferenceFragmentCompat)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        activity.startSearch(root);
        activity.filterSearch("gesture feedback style");
        Preference result = root.findPreference("settings-search-result-0");
        assertNotNull(result);
        result.performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        PropertiesPreferencesFragment properties = (PropertiesPreferencesFragment)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        ListPreference preference = properties.findPreference("gesture-feedback-style");
        assertNotNull(preference);
        AtomicReference<String> saved = new AtomicReference<>();
        preference.setOnPreferenceChangeListener((p, value) -> {
            saved.set(value.toString());
            return false;
        });
        shadowOf(Looper.getMainLooper()).idle();
        activity.getSupportFragmentManager().executePendingTransactions();
        PropertyChoiceDialog fragment = (PropertyChoiceDialog)
            activity.getSupportFragmentManager().findFragmentByTag("property_choice");
        assertNotNull(fragment);
        AlertDialog dialog = (AlertDialog) fragment.requireDialog();
        assertNotNull(dialog.getListView());
        assertNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertNotNull(dialog.getWindow().getDecorView().findViewWithTag("gesture-terminal-preview"));
        dialog.getListView().performItemClick(dialog.getListView().getChildAt(2), 2,
            dialog.getListView().getAdapter().getItemId(2));
        assertTrue(dialog.isShowing());
        assertNull(saved.get());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        activity.getSupportFragmentManager().executePendingTransactions();
        assertEquals("touch-ripples", saved.get());
    }

    @Test
    public void clearingAndChangingTheQueryKeepsTheOriginalVisibilitySnapshot() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PreferenceFragmentCompat root = (PreferenceFragmentCompat)
            activity.getSupportFragmentManager().findFragmentById(R.id.settings);
        activity.startSearch(root);

        activity.filterSearch("triple tap action");
        Preference properties = root.findPreference("termux_properties");
        assertNotNull(properties);
        assertFalse(properties.isVisible());

        activity.filterSearch("");
        assertTrue(properties.isVisible());
        assertNull(root.findPreference("settings-search-result-0"));

        activity.filterSearch("triple tap action");
        assertFalse(properties.isVisible());
        assertNotNull(root.findPreference("settings-search-result-0"));
    }
}
