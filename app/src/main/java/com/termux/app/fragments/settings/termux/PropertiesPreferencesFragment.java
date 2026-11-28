package com.termux.app.fragments.settings.termux;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.DialogPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.termux.app.settings.TermuxPropertiesEditor;
import com.termux.app.settings.PropertyInputSpec;
import com.termux.app.settings.SettingsSearchIndex;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigSchema;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Keep
public class PropertiesPreferencesFragment extends GroupedPreferencesFragment {
    private TermuxPropertiesEditor editor;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        editor = new TermuxPropertiesEditor();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        Properties values;
        try {
            values = editor.read();
        } catch (IOException | IllegalArgumentException e) {
            Preference error = new Preference(requireContext());
            error.setIcon(R.drawable.ui_ic_info);
            error.setTitle(R.string.properties_read_error);
            error.setSelectable(false);
            screen.addPreference(error);
            return;
        }

        Preference help = new Preference(requireContext());
        help.setIcon(R.drawable.ui_ic_info);
        help.setTitle(R.string.properties_help);
        help.setOnPreferenceClickListener(clicked -> {
            DialogUtils.builder(requireContext()).setTitle(R.string.properties_help)
                .setMessage(R.string.properties_explanation)
                .setPositiveButton(android.R.string.ok, null).show();
            return true;
        });
        screen.addPreference(help);
        Map<Integer, PreferenceCategory> categories = new LinkedHashMap<>();
        // One category per group of config.yaml, in the order of the file.
        for (int title : new int[]{R.string.properties_appearance, R.string.properties_terminal,
                R.string.properties_sessions, R.string.properties_sidebars, R.string.properties_keyboard,
                R.string.properties_extra_keys, R.string.properties_gestures, R.string.properties_integrations,
                R.string.properties_power}) {
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(title);
            screen.addPreference(category);
            categories.put(title, category);
        }
        // In the order of config.yaml; the Display screen edits the accent, color scheme and font.
        ArrayList<String> keys = new ArrayList<>(TermuxConfigSchema.paths().values());
        for (String key : keys) {
            if (TermuxConfigSchema.isDisplayKey(key)) continue;
            Preference preference;
            boolean inverted = TermuxConfigSchema.isInverted(key);
            boolean internalDefaultTrue = TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key);
            boolean isBoolean = internalDefaultTrue || TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key);
            // An inverted setting is shown as it is written in config.yaml, the positive way round.
            boolean defaultTrue = inverted != internalDefaultTrue;
            Set<String> options = choices(key);
            if (key.equals("extra-keys")) {
                preference = new Preference(requireContext());
                preference.setPersistent(false);
                preference.setSummary(R.string.extra_editor_help);
                preference.setOnPreferenceClickListener(p -> {
                    try {
                        ExtraKeysEditorDialog.show(this, editor.read());
                    } catch (IOException | IllegalArgumentException e) {
                        Toast.makeText(requireContext(), R.string.properties_read_error, Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
            } else if (isBoolean || !options.isEmpty()) {
                ListPreference choice = new ListPreference(requireContext());
                choice.setPersistent(false);
                ArrayList<String> entries = new ArrayList<>();
                ArrayList<String> entryValues = new ArrayList<>();
                String current = values.getProperty(key, "");
                if (inverted) current = TermuxConfigSchema.invert(current);
                if (key.endsWith("-action")) {
                    TerminalGestureAction action = TerminalGestureAction.parse(current);
                    if (action != null && options.contains(action.getValue())) current = action.getValue();
                }
                entries.add(isBoolean ? getString(R.string.properties_default_boolean,
                    getString(defaultTrue ? R.string.properties_enabled : R.string.properties_disabled))
                    : key.equals(TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE)
                        ? "Default (Soft flash)" : key.equals(TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION)
                        ? "Default (Bottom center)" : key.equals(TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT)
                        ? getString(R.string.properties_default_boolean, getString(R.string.ui_tmux_layout_nested))
                        : key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_COMPACT)
                        ? getString(R.string.properties_default_boolean, "Normal")
                        : TermuxPropertyConstants.PICKER_FULLSCREEN_OVERRIDES.contains(key)
                        ? getString(R.string.properties_default_boolean, "follow Full-screen pickers")
                        : key.equals(TermuxPropertyConstants.KEY_TERMINAL_PICKER_POSITION)
                        ? getString(R.string.properties_default_boolean, getString(R.string.ui_picker_position_top))
                        : getString(R.string.properties_default));
                entryValues.add("");
                if (isBoolean) {
                    entries.add(getString(R.string.properties_enabled));
                    entries.add(getString(R.string.properties_disabled));
                    entryValues.add("true");
                    entryValues.add("false");
                } else {
                    for (String option : options) {
                        entries.add(choiceLabel(key, option));
                        entryValues.add(option);
                    }
                }
                if (!entryValues.contains(current)) {
                    entries.add(current);
                    entryValues.add(current);
                }
                choice.setEntries(entries.toArray(new String[0]));
                choice.setEntryValues(entryValues.toArray(new String[0]));
                choice.setValue(current);
                choice.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                choice.setDialogMessage(PropertyInputSpec.help(key));
                if (key.equals(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS)) {
                    choice.setDialogMessage(R.string.properties_external_apps_warning);
                }
                preference = choice;
            } else {
                EditTextPreference text = new EditTextPreference(requireContext());
                text.setPersistent(false);
                text.setText(values.getProperty(key, ""));
                text.setDialogMessage(PropertyInputSpec.help(key));
                text.setSummaryProvider(p -> {
                    String value = ((EditTextPreference) p).getText();
                    return value == null || value.isEmpty() ? getString(R.string.properties_default) : value;
                });
                preference = text;
            }
            preference.setKey(key);
            preference.setIcon(iconFor(key));
            preference.setTitle(SettingsSearchIndex.propertyTitle(key));
            if (preference instanceof DialogPreference)
                ((DialogPreference) preference).setDialogTitle(preference.getTitle());
            preference.setOnPreferenceChangeListener((p, value) -> save(key,
                inverted ? TermuxConfigSchema.invert(value.toString()) : value.toString()));
            if ("extra-keys".equals(key)) preference.setOrder(-1);
            categories.get(categoryFor(key)).addPreference(preference);
        }
    }

    @Override public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof EditTextPreference) {
            PropertyTextDialog.show(this, (EditTextPreference) preference);
        } else if (preference instanceof ListPreference) {
            PropertyChoiceDialog.show(this, (ListPreference) preference);
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    /** The category of a setting: the group of its path in config.yaml. */
    public static int categoryFor(@NonNull String key) {
        String path = TermuxConfigSchema.pathFor(key);
        String group = path == null ? "" : path.substring(0, path.indexOf('.'));
        switch (group) {
            case "appearance": return R.string.properties_appearance;
            case "terminal": return R.string.properties_terminal;
            case "sessions": return R.string.properties_sessions;
            case "sidebar": return R.string.properties_sidebars;
            case "keyboard": return R.string.properties_keyboard;
            case "extra-keys": return R.string.properties_extra_keys;
            case "gestures": return R.string.properties_gestures;
            case "power": return R.string.properties_power;
            default: return R.string.properties_integrations;
        }
    }

    /** The icon of a property's preference; the command palette shows it too. */
    public static int iconFor(String key) {
        if (key.contains("color") || key.endsWith("style") || key.equals("night-mode")
            || key.equals("use-black-ui")) return R.drawable.ui_ic_palette;
        if (key.contains("keyboard") || key.startsWith("extra-keys") || key.startsWith("shortcut.")
            || key.equals("back-key") || key.equals("volume-keys") || key.equals("ctrl-space-workaround")
            || key.equals("enforce-char-based-input")) return R.drawable.ui_ic_keyboard;
        if (key.endsWith("-action") || key.endsWith("-keys")) return R.drawable.ui_ic_select_text;
        if (key.contains("share")) return R.drawable.ui_ic_share;
        if (key.contains("url")) return R.drawable.ui_ic_link;
        if (key.contains("delete")) return R.drawable.ui_ic_delete;
        if (key.equals(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS)) return R.drawable.ui_ic_lock;
        if (key.equals("terminal-transcript-rows")) return R.drawable.ui_ic_transcript;
        if (key.equals("bell-character") || key.contains("toast")) return R.drawable.ui_ic_info;
        if (categoryFor(key) == R.string.properties_appearance) return R.drawable.ui_ic_terminal;
        return R.drawable.ui_ic_settings;
    }

    private String choiceLabel(String key, String value) {
        if (TermuxPropertyConstants.PICKER_FULLSCREEN_OVERRIDES.contains(key))
            return "true".equals(value) ? "Full screen" : "Dialog";
        if (TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT.equals(key) && "google-sans".equals(value))
            return "Google Sans (Gboard)";
        if (TermuxPropertyConstants.KEY_EXTRA_KEYS_COMPACT.equals(key)) {
            if (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_TRUE.equals(value)) return "Compact (three quarters)";
            if (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_CUSTOM.equals(value)) return "Custom row height";
            return "Normal";
        }
        if (TermuxPropertyConstants.KEY_EXTRA_KEYS_THEME.equals(key)) {
            return getString(TermuxPropertyConstants.EXTRA_KEYS_THEME_ROUNDED.equals(value)
                ? R.string.extra_theme_rounded : R.string.extra_theme_default);
        }
        if (TermuxPropertyConstants.KEY_TERMINAL_PICKER_POSITION.equals(key)) {
            if (TermuxPropertyConstants.PICKER_POSITION_CENTER.equals(value))
                return getString(R.string.ui_picker_position_center);
            if (TermuxPropertyConstants.PICKER_POSITION_TERMINAL.equals(value))
                return getString(R.string.ui_picker_position_terminal);
            return getString(R.string.ui_picker_position_top);
        }
        if (TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE.equals(key))
            return getString(TermuxPropertyConstants.TMUX_SIDEBAR_ROUNDED.equals(value)
                ? R.string.ui_tmux_style_rounded : R.string.ui_tmux_style_subtle);
        if (TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT.equals(key)) {
            if (TermuxPropertyConstants.TMUX_SIDEBAR_DRILL_DOWN.equals(value))
                return getString(R.string.ui_tmux_layout_drill_down);
            if (TermuxPropertyConstants.TMUX_SIDEBAR_MODES.equals(value))
                return getString(R.string.ui_tmux_layout_modes);
            return getString(R.string.ui_tmux_layout_nested);
        }
        if (TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE.equals(key)
            || TermuxPropertyConstants.KEY_EXTRA_KEYS_ANIMATION.equals(key)
            || TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT.equals(key)) {
            String label = value.replace('-', ' ');
            return Character.toUpperCase(label.charAt(0)) + label.substring(1);
        }
        if (TermuxPropertyConstants.KEY_TERMINAL_ENGINE.equals(key))
            return TermuxPropertyConstants.TERMINAL_ENGINE_GHOSTTY.equals(value) ? "libghostty" : "Termux";
        if (!key.endsWith("-action")) return value;
        TerminalGestureAction action = TerminalGestureAction.parse(value);
        return action == null ? value : action.getLabel();
    }

    private Set<String> choices(String key) {
        if (TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(key)
            && key.endsWith("-action")) {
            Set<String> actions = new LinkedHashSet<>();
            for (TerminalGestureAction action : TerminalGestureAction.values()) actions.add(action.getValue());
            return actions;
        }
        String[] typedChoices = PropertyInputSpec.choices(key);
        if (typedChoices != null && typedChoices.length != 0)
            return new LinkedHashSet<>(java.util.Arrays.asList(typedChoices));
        switch (key) {
            case TermuxPropertyConstants.KEY_BELL_BEHAVIOUR: return TermuxPropertyConstants.MAP_BELL_BEHAVIOUR.keySet();
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE: return TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE.keySet();
            case TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR: return TermuxPropertyConstants.MAP_BACK_KEY_BEHAVIOUR.keySet();
            case TermuxPropertyConstants.KEY_NIGHT_MODE: return TermuxPropertyConstants.MAP_NIGHT_MODE.keySet();
            case TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR: return TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR.keySet();
            case TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR: return TermuxPropertyConstants.MAP_VOLUME_KEYS_BEHAVIOUR.keySet();
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION:
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION:
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION:
            case TermuxPropertyConstants.KEY_VOLUME_UP_ACTION:
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION:
                Set<String> actions = new LinkedHashSet<>();
                boolean volumeAction = key.startsWith("volume-");
                if (volumeAction) actions.add(TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER);
                for (TerminalGestureAction action : TerminalGestureAction.values()) {
                    if (volumeAction && action == TerminalGestureAction.RUN_COMMAND) continue;
                    actions.add(action.getValue());
                }
                return actions;
            default: return Collections.emptySet();
        }
    }

    boolean saveExtraKeys(String value) {
        return save("extra-keys", value);
    }

    private boolean save(String key, String value) {
        try {
            editor.set(key, value.isEmpty() ? null : value);
            TermuxActivity.onPropertiesEditedFromSettings(requireContext());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.properties_save_error, Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
