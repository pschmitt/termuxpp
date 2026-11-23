package com.termux.app.fragments.settings.termux;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.widget.Button;
import android.widget.Toast;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.ListPreferenceDialogFragmentCompat;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.terminal.GestureFeedback;
import com.termux.app.settings.TermuxPropertiesEditor;

import java.io.IOException;
import java.util.Locale;

import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import dev.pschmitt.termux.shared.termux.terminal.io.BellHandler;

public class PropertyChoiceDialog extends ListPreferenceDialogFragmentCompat {
    private static final String SELECTED_INDEX = "gesture-preview-selected-index";
    private final GestureFeedback feedback = new GestureFeedback();
    private int selectedIndex = -1;
    private TextView terminalPreview;
    private SettingsPreview settingsPreview;
    private MediaPlayer bellPlayer;

    static void show(PreferenceFragmentCompat parent, ListPreference preference) {
        PropertyChoiceDialog dialog = new PropertyChoiceDialog();
        Bundle args = new Bundle();
        args.putString("key", preference.getKey());
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "property_choice");
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) selectedIndex = savedInstanceState.getInt(SELECTED_INDEX, -1);
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SELECTED_INDEX, selectedIndex);
    }

    private boolean isGestureStyle() {
        return TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE.equals(getPreference().getKey());
    }

    private boolean hasChoicePreview() {
        String key = getPreference().getKey();
        return isGestureStyle() || SettingsPreview.supports(key)
            || TermuxPropertyConstants.KEY_BELL_BEHAVIOUR.equals(key);
    }

    @Override protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        super.onPrepareDialogBuilder(builder);
        // AlertController shows a message instead of its choice list, not alongside it.
        builder.setMessage(null);
        TextView help = new TextView(requireContext());
        help.setText(getPreference().getDialogMessage());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        help.setPadding(padding, padding / 2, padding, padding / 2);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(help);
        if (hasChoicePreview()) {
            ListPreference preference = (ListPreference) getPreference();
            if (selectedIndex < 0 || selectedIndex >= preference.getEntryValues().length) {
                selectedIndex = preference.findIndexOfValue(preference.getValue());
                if (selectedIndex < 0) selectedIndex = 0;
            }
            if (!isGestureStyle()) {
                if (TermuxPropertyConstants.KEY_BELL_BEHAVIOUR.equals(preference.getKey())) {
                    Button tryBell = new Button(requireContext());
                    tryBell.setText(R.string.settings_try_bell);
                    tryBell.setOnClickListener(v -> playBell());
                    content.addView(tryBell);
                } else {
                    settingsPreview = new SettingsPreview(requireContext(), preference.getKey());
                    settingsPreview.setTag("property-visual-preview");
                    int height = Math.round((preference.getKey().startsWith("extra-keys") ? 48 : 124)
                        * getResources().getDisplayMetrics().density);
                    LinearLayout.LayoutParams visualLayout = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, height);
                    visualLayout.setMargins(padding, 0, padding, padding / 2);
                    content.addView(settingsPreview, visualLayout);
                    updateSettingsPreview();
                }
                builder.setSingleChoiceItems(preference.getEntries(), selectedIndex, (dialog, which) -> {
                    selectedIndex = which;
                    updateSettingsPreview();
                });
                builder.setPositiveButton(android.R.string.ok, this);
            } else {
                terminalPreview = new TextView(requireContext());
                terminalPreview.setTag("gesture-terminal-preview");
                terminalPreview.setText("~ ❯ git status\nOn branch main\nworking tree clean\n\n~ ❯ ▌");
                terminalPreview.setTextColor(Color.rgb(210, 215, 226));
                terminalPreview.setTextSize(12);
                terminalPreview.setTypeface(Typeface.MONOSPACE);
                terminalPreview.setGravity(Gravity.TOP | Gravity.START);
                terminalPreview.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);
                terminalPreview.setContentDescription(getString(R.string.gesture_feedback_preview_description));
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.rgb(12, 14, 21));
                background.setCornerRadius(padding / 2f);
                terminalPreview.setBackground(background);
                LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.round(148 * getResources().getDisplayMetrics().density));
                previewLayout.setMargins(padding, 0, padding, 0);
                content.addView(terminalPreview, previewLayout);
                TextView replayHint = new TextView(requireContext());
                replayHint.setText(R.string.gesture_feedback_preview_replay);
                replayHint.setTextSize(12);
                replayHint.setGravity(Gravity.END);
                replayHint.setPadding(padding, padding / 4, padding, padding / 2);
                content.addView(replayHint);
                terminalPreview.setOnClickListener(v -> playPreview());
                builder.setSingleChoiceItems(preference.getEntries(), selectedIndex, (dialog, which) -> {
                    selectedIndex = which;
                    playPreview();
                });
                builder.setPositiveButton(android.R.string.ok, this);
            }
        }
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(content);
        builder.setView(scroll);
    }

    @Override public void onStart() {
        super.onStart();
        if (terminalPreview != null) terminalPreview.post(this::playPreview);
    }

    private void playPreview() {
        if (terminalPreview == null || selectedIndex < 0) return;
        ListPreference preference = (ListPreference) getPreference();
        CharSequence[] values = preference.getEntryValues();
        if (selectedIndex >= values.length) return;
        String style = values[selectedIndex].toString();
        if (style.isEmpty()) style = TermuxPropertyConstants.DEFAULT_GESTURE_FEEDBACK_STYLE;
        int gravity = TermuxPropertyConstants.DEFAULT_GESTURE_FEEDBACK_GRAVITY;
        try {
            String position = new TermuxPropertiesEditor().read().getProperty(
                TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION, "bottom-center");
            Integer configured = TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION.get(position.toLowerCase(Locale.ROOT));
            if (configured != null) gravity = configured;
        } catch (IOException | IllegalArgumentException ignored) { }
        feedback.confirm(terminalPreview, getString(R.string.gesture_feedback_preview_label), style,
            Float.NaN, Float.NaN, gravity);
    }

    private String selectedValue() {
        CharSequence[] values = ((ListPreference) getPreference()).getEntryValues();
        return selectedIndex >= 0 && selectedIndex < values.length ? values[selectedIndex].toString() : "";
    }

    private void updateSettingsPreview() {
        if (settingsPreview != null) settingsPreview.setValue(selectedValue());
    }

    private void playBell() {
        String value = selectedValue();
        if (value.isEmpty()) value = TermuxPropertyConstants.DEFAULT_VALUE_BELL_BEHAVIOUR;
        if (value.equals(TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_VIBRATE)) {
            BellHandler.getInstance(requireContext()).doBell();
        } else if (value.equals(TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_BEEP)) {
            if (bellPlayer != null) bellPlayer.release();
            bellPlayer = MediaPlayer.create(requireContext(), dev.pschmitt.termux.shared.R.raw.bell,
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(), 0);
            if (bellPlayer != null) {
                MediaPlayer player = bellPlayer;
                player.setOnCompletionListener(done -> {
                    if (bellPlayer == done) bellPlayer = null;
                    done.release();
                });
                player.start();
            }
        } else {
            Toast.makeText(requireContext(), R.string.settings_bell_ignored, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onDialogClosed(boolean positiveResult) {
        if (!hasChoicePreview()) {
            super.onDialogClosed(positiveResult);
            return;
        }
        ListPreference preference = (ListPreference) getPreference();
        CharSequence[] values = preference.getEntryValues();
        if (positiveResult && selectedIndex >= 0 && selectedIndex < values.length) {
            String value = values[selectedIndex].toString();
            if (preference.callChangeListener(value)) preference.setValue(value);
        }
    }

    @Override public void onDestroyView() {
        feedback.clear();
        if (bellPlayer != null) { bellPlayer.release(); bellPlayer = null; }
        settingsPreview = null;
        terminalPreview = null;
        super.onDestroyView();
    }
}
