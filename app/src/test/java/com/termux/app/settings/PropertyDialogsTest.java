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

    private static View described(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The {@code index}-th visible text field whose outlined box shows {@code hint}. */
    private static EditText field(View root, String hint, int index) {
        java.util.List<EditText> found = new java.util.ArrayList<>();
        collectFields(root, hint, found);
        return found.get(index);
    }

    private static void collectFields(View root, String hint, java.util.List<EditText> found) {
        if (root.getVisibility() != View.VISIBLE) return;
        if (root instanceof com.google.android.material.textfield.TextInputLayout) {
            com.google.android.material.textfield.TextInputLayout layout = (com.google.android.material.textfield.TextInputLayout) root;
            if (layout.getHint() != null && hint.contentEquals(layout.getHint()) && layout.getEditText() != null) {
                found.add(layout.getEditText());
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectFields(group.getChildAt(i), hint, found);
        }
    }

    private static String draft(ExtraKeysEditorDialog dialog) {
        Bundle state = new Bundle();
        dialog.onSaveInstanceState(state);
        return state.getString("value");
    }

    private static ExtraKeysEditorDialog editor(SettingsActivity activity, String value) {
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", value);
        dialog.setArguments(args);
        dialog.showNow(activity.getSupportFragmentManager(), "layout");
        return dialog;
    }

    private static dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView preview(DialogFragment dialog) {
        return view(dialog.requireDialog().getWindow().getDecorView(),
            dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView.class, null);
    }

    @Test public void visualEditorAddsRowsAndKeysWithoutSavingAndKeepsDraftState() {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog dialog = editor(activity, "[['ESC']]");
        View root = dialog.requireDialog().getWindow().getDecorView();
        view(root, Button.class, "Add row").performClick();
        assertEquals("[[\"ESC\"],[]]", draft(dialog));
        Bundle state = new Bundle();
        dialog.onSaveInstanceState(state);
        assertFalse(state.getBoolean("default"));
        // The empty row shows a placeholder key so it stays visible and tappable.
        assertEquals(2, preview(dialog).getChildCount());
        assertEquals(2, preview(dialog).getRowCount());

        // Selecting a key opens the inline inspector; edits apply to the preview while typing.
        preview(dialog).getChildAt(0).performClick();
        EditText tap = field(root, "Key or text", 0);
        assertEquals("ESC", tap.getText().toString());
        tap.setText("");
        assertNotNull(((com.google.android.material.textfield.TextInputLayout) tap.getParent().getParent()).getError());
        assertEquals("[[\"ESC\"],[]]", draft(dialog));
        tap.setText("TAB");
        assertEquals("[[{\"key\":\"TAB\"}],[]]", draft(dialog));
        assertEquals("↹", ((android.widget.TextView) preview(dialog).getChildAt(0)).getText().toString());

        // One undo step reverts the whole edit session of that key.
        described(root, "Undo").performClick();
        assertEquals("[[\"ESC\"],[]]", draft(dialog));
        described(root, "Redo").performClick();
        assertEquals("[[{\"key\":\"TAB\"}],[]]", draft(dialog));

        // The placeholder adds a key to the empty row and focuses it for typing.
        preview(dialog).getChildAt(1).performClick();
        assertEquals("[[{\"key\":\"TAB\"}],[{\"key\":\"\"}]]", draft(dialog));
        field(root, "Key or text", 0).setText("F1");
        assertEquals("[[{\"key\":\"TAB\"}],[{\"key\":\"F1\"}]]", draft(dialog));
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void inspectorEditsActionsColorsAndGesturesAndKeepsUnknownAttributes() throws Exception {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog dialog = editor(activity, "[[{key:'LEFT',popup:'HOME',custom:1},'ESC']]");
        View root = dialog.requireDialog().getWindow().getDecorView();
        preview(dialog).getChildAt(0).performClick();
        field(root, "Hex color (optional)", 1).setText("#303030");
        view(root, Button.class, "Custom text").performClick();
        field(root, "Corner label text", 0).setText("⇱");
        // Swipe up is the second visible "Key or text" field after the tap action.
        field(root, "Key or text", 1).setText("PGUP");
        org.json.JSONObject key = new org.json.JSONArray(draft(dialog)).getJSONArray(0).getJSONObject(0);
        assertEquals("LEFT", key.getString("key"));
        assertEquals("#303030", key.getString("background-color"));
        assertEquals("⇱", key.getString("hint"));
        assertEquals("custom", key.getString("hint-mode"));
        assertEquals("PGUP", key.getString("popup"));
        assertEquals(1, key.getInt("custom"));
        view(root, Button.class, "Auto").performClick();
        key = new org.json.JSONArray(draft(dialog)).getJSONArray(0).getJSONObject(0);
        assertEquals("auto", key.getString("hint-mode"));
        assertFalse(key.has("hint"));
        field(root, "Hex color (optional)", 0).setText("not a color");
        key = new org.json.JSONArray(draft(dialog)).getJSONArray(0).getJSONObject(0);
        assertFalse("an invalid color is not applied", key.has("text-color"));
        field(root, "Hex color (optional)", 0).setText("");

        // Switching the tap action to a command keeps the rest of the key.
        Button command = view(root, Button.class, "Command");
        command.performClick();
        field(root, "Shell command, run in a new session", 0).setText("htop");
        key = new org.json.JSONArray(draft(dialog)).getJSONArray(0).getJSONObject(0);
        assertEquals("htop", key.getString("command"));
        assertFalse(key.has("key"));
        assertEquals("PGUP", key.getString("popup"));

        described(root, "Move right").performClick();
        assertEquals("ESC", new org.json.JSONArray(draft(dialog)).getJSONArray(0).getString(0));
        described(root, "Duplicate key").performClick();
        assertEquals(3, new org.json.JSONArray(draft(dialog)).getJSONArray(0).length());
        described(root, "Delete key").performClick();
        described(root, "Delete key").performClick();
        assertEquals("[[\"ESC\"]]", draft(dialog));
        dialog.dismiss();
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    @Test public void cancellingAChangedDraftAsksBeforeDiscarding() {
        SettingsActivity activity = activity();
        ExtraKeysEditorDialog dialog = editor(activity, "[['ESC']]");
        View root = dialog.requireDialog().getWindow().getDecorView();
        view(root, Button.class, "Add row").performClick();
        ((AlertDialog) dialog.requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(dialog.requireDialog().isShowing());
        AlertDialog confirm = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotSame(dialog.requireDialog(), confirm);
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // Alert dialog buttons deliver their click through the main looper.
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        activity.getSupportFragmentManager().executePendingTransactions();
        assertNull(activity.getSupportFragmentManager().findFragmentByTag("layout"));
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

    @Test public void visualChoicesShowPreviewsAndWaitForConfirmation() {
        SettingsActivity activity = activity();
        PropertiesPreferencesFragment parent = new PropertiesPreferencesFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings, parent).commitNow();
        for (String key : new String[]{"terminal-tmux-sidebar-layout", "terminal-tmux-sidebar-style",
            "extra-keys-animation", "extra-keys-font", "terminal-double-tap-action", "extra-keys-swipe-left-action", "volume-up-action"}) {
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
