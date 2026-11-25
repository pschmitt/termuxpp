package com.termux.app.settings;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.termux.ExtraKeysEditorDialog;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;
import com.termux.app.fragments.settings.termux.PropertyTextDialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PropertyDialogsTest {
    private SettingsActivity activity() {
        return Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    private static <T extends View> T view(View root, Class<T> type, String text) {
        if (type.isInstance(root) && (text == null || root instanceof android.widget.TextView
            && text.equals(((android.widget.TextView) root).getText().toString()))) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = view(group.getChildAt(i), type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void visualEditorAddsRowsAndKeysWithoutSavingAndKeepsDraftState() {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", "[['ESC']]");
        dialog.setArguments(args);
        dialog.showNow(activity.getSupportFragmentManager(), "layout");
        view(dialog.requireDialog().getWindow().getDecorView(), Button.class, "Add row").performClick();
        Bundle state = new Bundle();
        dialog.onSaveInstanceState(state);
        assertEquals("[[\"ESC\"],[]]", state.getString("value"));
        assertFalse(state.getBoolean("default"));
        dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView preview = view(dialog.requireDialog().getWindow().getDecorView(),
            dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView.class, null);
        assertNotNull(preview);
        assertEquals(1, preview.getChildCount());
        assertEquals(2, preview.getRowCount());
        preview.getChildAt(0).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        DialogFragment keyDialog = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("extra_key");
        EditText key = view(keyDialog.requireDialog().getWindow().getDecorView(), EditText.class, null);
        key.setText("");
        ((AlertDialog) keyDialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(key.getError());
        key.setText("TAB");
        ((AlertDialog) keyDialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        activity.getSupportFragmentManager().executePendingTransactions();
        Bundle edited = new Bundle();
        dialog.onSaveInstanceState(edited);
        assertEquals("[[{\"key\":\"TAB\"}],[]]", edited.getString("value"));
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void previewDraftSurvivesActivityRecreation() {
        org.robolectric.android.controller.ActivityController<SettingsActivity> controller =
            Robolectric.buildActivity(SettingsActivity.class).setup();
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", "[['ESC']]");
        dialog.setArguments(args);
        dialog.showNow(controller.get().getSupportFragmentManager(), "layout");
        view(dialog.requireDialog().getWindow().getDecorView(), Button.class, "Add row").performClick();
        controller.recreate();
        ExtraKeysEditorDialog restored = (ExtraKeysEditorDialog) controller.get().getSupportFragmentManager().findFragmentByTag("layout");
        assertNotNull(restored);
        Bundle draft = new Bundle();
        restored.onSaveInstanceState(draft);
        assertEquals("[[\"ESC\"],[]]", draft.getString("value"));
        assertNotNull(view(restored.requireDialog().getWindow().getDecorView(),
            dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView.class, null));
        restored.dismiss();
        controller.get().getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void invalidRawLayoutRemainsOpenForCorrection() {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog.RawDialog dialog = new ExtraKeysEditorDialog.RawDialog();
        Bundle args = new Bundle();
        args.putString("value", "[['ESC']]");
        dialog.setArguments(args);
        dialog.showNow(activity.getSupportFragmentManager(), "raw");
        EditText text = view(dialog.requireDialog().getWindow().getDecorView(), EditText.class, null);
        text.setText("not json");
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(text.getError());
        assertTrue(dialog.requireDialog().isShowing());
        assertEquals("not json", text.getText().toString());
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void everyChoiceDialogShowsOptionsAndHelpAndDispatchesTheSelectedValue() {
        SettingsActivity activity = activity();
        PropertiesPreferencesFragment parent = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, parent).commitNow();
        int tested = 0;
        for (String key : dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            androidx.preference.Preference preference = parent.findPreference(key);
            if (!(preference instanceof androidx.preference.ListPreference)) continue;
            androidx.preference.ListPreference choice = (androidx.preference.ListPreference) preference;
            java.util.concurrent.atomic.AtomicReference<String> selected = new java.util.concurrent.atomic.AtomicReference<>();
            choice.setOnPreferenceChangeListener((p, value) -> { selected.set(value.toString()); return false; });
            parent.onDisplayPreferenceDialog(choice);
            activity.getSupportFragmentManager().executePendingTransactions();
            DialogFragment fragment = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("property_choice");
            assertNotNull(key, fragment);
            AlertDialog dialog = (AlertDialog) fragment.requireDialog();
            android.widget.ListView list = dialog.getListView();
            assertNotNull(key, list);
            assertNotNull(key + " choices must be attached, not displaced by help", list.getParent());
            assertEquals(key, View.VISIBLE, list.getVisibility());
            assertEquals(key, choice.getEntries().length, list.getAdapter().getCount());
            assertNotNull(key + " help", view(dialog.getWindow().getDecorView(), android.widget.TextView.class,
                choice.getDialogMessage().toString()));
            int index = choice.getEntryValues().length - 1;
            list.performItemClick(null, index, list.getAdapter().getItemId(index));
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (dialog.isShowing() && confirm != null && confirm.getVisibility() == View.VISIBLE)
                confirm.performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            activity.getSupportFragmentManager().executePendingTransactions();
            assertEquals(key, choice.getEntryValues()[index].toString(), selected.get());
            tested++;
        }
        assertTrue("Boolean and enum dialogs must both be covered", tested > 10);
    }

    @Test public void sidebarChoicesShowPreviewsAndWaitForConfirmation() {
        SettingsActivity activity = activity();
        PropertiesPreferencesFragment parent = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, parent).commitNow();
        for (String key : new String[]{"terminal-tmux-sidebar-layout", "terminal-tmux-sidebar-style"}) {
            androidx.preference.ListPreference choice = parent.findPreference(key);
            java.util.concurrent.atomic.AtomicReference<String> saved = new java.util.concurrent.atomic.AtomicReference<>();
            choice.setOnPreferenceChangeListener((preference, value) -> {
                saved.set(value.toString());
                return false;
            });
            parent.onDisplayPreferenceDialog(choice);
            activity.getSupportFragmentManager().executePendingTransactions();
            DialogFragment fragment = (DialogFragment) activity.getSupportFragmentManager()
                .findFragmentByTag("property_choice");
            assertNotNull(fragment);
            AlertDialog dialog = (AlertDialog) fragment.requireDialog();
            assertNotNull(dialog.getWindow().getDecorView().findViewWithTag("property-visual-preview"));
            android.widget.ListView list = dialog.getListView();
            int index = choice.getEntryValues().length - 1;
            list.performItemClick(null, index, list.getAdapter().getItemId(index));
            assertNull(saved.get());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            activity.getSupportFragmentManager().executePendingTransactions();
            assertEquals(choice.getEntryValues()[index].toString(), saved.get());
        }
    }

    @Test public void choiceDialogSurvivesRecreationAndCancelDoesNotSave() {
        org.robolectric.android.controller.ActivityController<SettingsActivity> controller =
            Robolectric.buildActivity(SettingsActivity.class).setup();
        PropertiesPreferencesFragment parent = new PropertiesPreferencesFragment();
        controller.get().getSupportFragmentManager().beginTransaction().replace(R.id.settings, parent).commitNow();
        parent.onDisplayPreferenceDialog(parent.findPreference("fullscreen"));
        controller.get().getSupportFragmentManager().executePendingTransactions();
        controller.recreate();
        DialogFragment fragment = (DialogFragment) controller.get().getSupportFragmentManager().findFragmentByTag("property_choice");
        assertNotNull(fragment);
        PropertiesPreferencesFragment restored = (PropertiesPreferencesFragment) fragment.getTargetFragment();
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        restored.findPreference("fullscreen").setOnPreferenceChangeListener((p, value) -> { changed.set(true); return false; });
        AlertDialog dialog = (AlertDialog) fragment.requireDialog();
        assertNotNull(dialog.getListView().getParent());
        assertEquals(3, dialog.getListView().getAdapter().getCount());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        controller.get().getSupportFragmentManager().executePendingTransactions();
        assertFalse(changed.get());
    }

    @Test public void numericDialogValidatesWithoutClosingAndDefaultIsOnlyADraft() {
        SettingsActivity activity = activity();
        PropertiesPreferencesFragment parent = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, parent).commitNow();
        EditTextPreference preference = parent.findPreference("terminal-cursor-blink-rate");
        parent.onDisplayPreferenceDialog(preference);
        activity.getSupportFragmentManager().executePendingTransactions();
        DialogFragment dialog = (DialogFragment) activity.getSupportFragmentManager().findFragmentByTag("property_text");
        assertTrue(dialog instanceof PropertyTextDialog);
        EditText text = view(dialog.requireDialog().getWindow().getDecorView(), EditText.class, null);
        text.setText("99");
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(text.getError());
        assertTrue(dialog.requireDialog().isShowing());
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        assertEquals("", text.getText().toString());
        assertNull(text.getError());
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }
}
