package com.termux.app.fragments.settings.termux;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.view.ViewGroup;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;
import java.util.Properties;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.termux.R;
import com.termux.app.terminal.TerminalAppearance;
import com.termux.app.settings.ExtraKeysLayout;

import org.json.JSONException;
import org.json.JSONObject;

public class ExtraKeysEditorDialog extends DialogFragment {
    private ExtraKeysLayout layout;
    private String source;
    private boolean useDefault;
    private LinearLayout content;
    private FrameLayout previewHost;

    static void show(PropertiesPreferencesFragment parent, Properties values) {
        String value = values.getProperty("extra-keys", "");
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", value);
        for (String key : new String[]{"extra-keys-style", "extra-keys-font", "extra-keys-theme", "extra-keys-row-background-color", "extra-keys-text-all-caps", "terminal-toolbar-height",
                "extra-keys-button-active-text-color", "extra-keys-button-active-background-color", "extra-keys-button-corner-radius"}) {
            args.putString(key, values.getProperty(key));
        }
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "extra_keys_editor");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        source = state == null ? requireArguments().getString("value", "") : state.getString("value", "");
        useDefault = state == null ? source.isEmpty() : state.getBoolean("default");
        try {
            layout = source.isEmpty() ? ExtraKeysLayout.defaults() : new ExtraKeysLayout(source);
        } catch (JSONException e) {
            layout = null;
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("value", layout == null ? source : layout.toString());
        out.putBoolean("default", useDefault);
    }

    @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
        LinearLayout root = column();
        TextView title = new TextView(requireContext());
        title.setText(R.string.extra_preview);
        title.setTextAppearance(requireContext(), com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
        root.addView(title);
        TextView hint = new TextView(requireContext());
        hint.setText(R.string.extra_preview_help);
        root.addView(hint);
        previewHost = new FrameLayout(requireContext());
        ScrollView previewScroll = new ScrollView(requireContext());
        previewScroll.addView(previewHost);
        root.addView(previewScroll);
        content = column();
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        render();
        return DialogUtils.builder(requireContext()).setTitle(R.string.extra_editor_title)
            .setView(root).setPositiveButton(R.string.extra_save, null)
            .setNegativeButton(android.R.string.cancel, null).create();
    }

    @Override public void onStart() {
        super.onStart();
        requireDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (layout == null) {
                Toast.makeText(requireContext(), R.string.extra_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            PropertiesPreferencesFragment parent = (PropertiesPreferencesFragment) getTargetFragment();
            if (parent != null && parent.saveExtraKeys(useDefault ? "" : layout.toString())) dismiss();
        });
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(requireContext());
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding / 2, padding, padding / 2);
        return view;
    }

    private void button(LinearLayout parent, String title, View.OnClickListener action) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setOnClickListener(action);
        parent.addView(button);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void renderPreview() {
        previewHost.removeAllViews();
        previewHost.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (layout == null || layout.rowCount() == 0) {
            ((View) previewHost.getParent()).getLayoutParams().height = dp(64);
            return;
        }
        try {
            Bundle options = requireArguments();
            ExtraKeysView keyboard = new ExtraKeysView(requireContext(), null);
            keyboard.setKeyTypeface(TerminalAppearance.loadExtraKeysFont(requireContext(), options.getString("extra-keys-font")));
            keyboard.setButtonTextAllCaps(!"false".equalsIgnoreCase(options.getString("extra-keys-text-all-caps")));
            keyboard.setAppearance(options.getString("extra-keys-theme"),
                ExtraKeysColorParser.parseColor(options.getString("extra-keys-row-background-color")),
                ExtraKeysColorParser.parseColor(options.getString("extra-keys-button-active-text-color")),
                ExtraKeysColorParser.parseColor(options.getString("extra-keys-button-active-background-color")),
                ExtraKeysColorParser.parseCornerRadiusDp(options.getString("extra-keys-button-corner-radius")));
            float scale = 1;
            try { scale = Float.parseFloat(options.getString("terminal-toolbar-height", "1")); }
            catch (NumberFormatException ignored) { }
            if (Float.isNaN(scale) || scale < 0.4f || scale > 3) scale = 1;
            int height = dp(37.5f * scale * layout.rowCount());
            previewHost.addView(keyboard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            int viewport = Math.min(height, getResources().getDisplayMetrics().heightPixels / 3);
            ((View) previewHost.getParent()).getLayoutParams().height = viewport;
            keyboard.reload(new ExtraKeysInfo(layout.toString(), options.getString("extra-keys-style", "default"),
                ExtraKeysConstants.CONTROL_CHARS_ALIASES), height);
            int child = 0;
            for (int r = 0; r < layout.rowCount(); r++) {
                for (int k = 0; k < layout.keyCount(r); k++) {
                    final int row = r;
                    final int key = k;
                    View button = keyboard.getChildAt(child++);
                    // Preview keys edit the draft; they must never start terminal input or repeat timers.
                    if (button.getVisibility() == View.INVISIBLE) {
                        button.setVisibility(View.VISIBLE);
                        ((TextView) button).setText("+");
                    }
                    button.setOnTouchListener(null);
                    button.setOnLongClickListener(null);
                    button.setOnClickListener(v -> editKey(row, key));
                    button.setContentDescription(getString(R.string.extra_preview_key, layout.label(row, key), row + 1));
                }
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private void rowMenu(View anchor, int row) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        if (row > 0) menu.getMenu().add(0, 1, 0, R.string.extra_row_up);
        if (row + 1 < layout.rowCount()) menu.getMenu().add(0, 2, 1, R.string.extra_row_down);
        menu.getMenu().add(0, 4, 2, R.string.extra_row_columns);
        menu.getMenu().add(0, 3, 3, R.string.extra_remove_row);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 4) {
                selectColumns(row);
            } else if (item.getItemId() == 3) {
                DialogUtils.builder(requireContext()).setMessage(R.string.extra_remove_row_confirm)
                    .setPositiveButton(R.string.extra_remove_row, (d, w) -> { layout.removeRow(row); changed(); })
                    .setNegativeButton(android.R.string.cancel, null).show();
            } else {
                layout.moveRow(row, item.getItemId() == 1 ? -1 : 1);
                changed();
            }
            return true;
        });
        menu.show();
    }

    private void selectColumns(int row) {
        int previous = layout.keyCount(row);
        String[] choices = new String[Math.max(12, previous)];
        for (int i = 0; i < choices.length; i++) choices[i] = Integer.toString(i + 1);
        DialogUtils.builder(requireContext()).setTitle(R.string.extra_row_columns)
            .setSingleChoiceItems(choices, previous - 1, (dialog, item) -> {
                dialog.dismiss();
                int columns = item + 1;
                if (columns == previous) return;
                Runnable resize = () -> { layout.resizeRow(row, columns); changed(); };
                if (columns < previous) {
                    DialogUtils.builder(requireContext()).setMessage(R.string.extra_fewer_columns)
                        .setPositiveButton(R.string.extra_apply, (d, w) -> resize.run())
                        .setNegativeButton(android.R.string.cancel, null).show();
                } else {
                    resize.run();
                }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void render() {
        content.removeAllViews();
        renderPreview();
        if (layout == null || layout.rowCount() == 0) {
            TextView empty = new TextView(requireContext());
            empty.setText(layout == null ? R.string.extra_invalid_help : R.string.extra_empty);
            content.addView(empty);
        }
        if (layout != null) {
            for (int r = 0; r < layout.rowCount(); r++) {
                final int row = r;
                LinearLayout line = new LinearLayout(requireContext());
                line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView heading = new TextView(requireContext());
                heading.setText(getResources().getQuantityString(R.plurals.extra_row_count, layout.keyCount(row), row + 1, layout.keyCount(row)));
                heading.setTextAppearance(requireContext(), com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
                line.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                button(line, getString(R.string.extra_add_key), v -> editKey(row, layout.keyCount(row)));
                MaterialButton more = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
                more.setText("⋮");
                more.setTextSize(24);
                more.setContentDescription(getString(R.string.extra_row_options, row + 1));
                more.setOnClickListener(v -> rowMenu(v, row));
                line.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
                content.addView(line);
            }
            button(content, getString(R.string.extra_add_row), v -> { layout.addRow(); changed(); });
        }
        TextView draft = new TextView(requireContext());
        draft.setText(R.string.extra_draft_help);
        draft.setPadding(0, dp(16), 0, dp(8));
        content.addView(draft);
        button(content, getString(R.string.extra_more), v -> {
            PopupMenu menu = new PopupMenu(requireContext(), v);
            menu.getMenu().add(0, 1, 0, R.string.extra_raw);
            menu.getMenu().add(0, 2, 1, R.string.extra_reset);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 2) {
                    DialogUtils.builder(requireContext()).setMessage(R.string.extra_reset_confirm)
                        .setPositiveButton(R.string.extra_reset, (d, w) -> {
                            try { layout = ExtraKeysLayout.defaults(); useDefault = true; render(); }
                            catch (JSONException e) { throw new IllegalStateException(e); }
                        }).setNegativeButton(android.R.string.cancel, null).show();
                } else {
                    RawDialog raw = new RawDialog();
                    Bundle args = new Bundle();
                    args.putString("value", layout == null ? source : layout.toString());
                    raw.setArguments(args);
                    raw.setTargetFragment(this, 0);
                    raw.show(getParentFragmentManager(), "extra_keys_raw");
                }
                return true;
            });
            menu.show();
        });
    }

    private void changed() { useDefault = false; render(); }

    private void editKey(int row, int key) {
        try {
            KeyDialog dialog = new KeyDialog();
            Bundle args = new Bundle();
            args.putInt("row", row);
            args.putInt("key", key);
            args.putString("value", key < layout.keyCount(row) ? layout.key(row, key).toString() : "{}");
            dialog.setArguments(args);
            dialog.setTargetFragment(this, 0);
            dialog.show(getParentFragmentManager(), "extra_key");
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public static class RawDialog extends DialogFragment {
        private EditText text;
        @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
            text = new EditText(requireContext());
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setMinLines(5);
            text.setText(state == null ? requireArguments().getString("value") : state.getString("value"));
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(text);
            return DialogUtils.builder(requireContext()).setTitle(R.string.extra_raw)
                .setMessage(R.string.extra_raw_help).setView(scroll)
                .setPositiveButton(R.string.extra_apply, null).setNegativeButton(android.R.string.cancel, null).create();
        }
        @Override public void onStart() {
            super.onStart();
            ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    ExtraKeysLayout value = new ExtraKeysLayout(text.getText().toString());
                    ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
                    if (parent == null) return;
                    parent.layout = value;
                    parent.changed();
                    dismiss();
                } catch (JSONException e) { text.setError(getString(R.string.extra_invalid)); }
            });
        }
        @Override public void onSaveInstanceState(@NonNull Bundle out) {
            super.onSaveInstanceState(out);
            out.putString("value", text.getText().toString());
        }
    }

    public static class KeyDialog extends DialogFragment {
        private JSONObject original;
        private AutoCompleteTextView key;
        private EditText label;
        private AutoCompleteTextView popup;
        private AutoCompleteTextView hold;
        private EditText holdLabel;
        private EditText textColor;
        private EditText backgroundColor;
        private EditText hint;
        private EditText inset;
        private EditText popupLabel;
        private EditText flingLeft;
        private EditText flingRight;
        private EditText flingDown;
        private CheckBox macro;
        private CheckBox popupMacro;
        private int row;
        private int index;

        private AutoCompleteTextView input(LinearLayout form, int title, String value) {
            TextInputLayout field = new TextInputLayout(requireContext());
            field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            field.setHint(getString(title));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
            form.addView(field, params);
            MaterialAutoCompleteTextView input = new MaterialAutoCompleteTextView(requireContext());
            input.setId(View.generateViewId());
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setText(value);
            if (title == R.string.extra_key_value || title == R.string.extra_popup || title == R.string.extra_hold
                || title == R.string.extra_fling_left || title == R.string.extra_fling_right
                || title == R.string.extra_fling_down) {
                input.setThreshold(0);
                input.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line,
                    new String[]{"ESC", "TAB", "CTRL", "ALT", "SHIFT", "FN", "ENTER", "BKSP", "DEL", "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "KEYBOARD", "DRAWER", "F1", "F2", "F3", "F4", "/", "-", "|"}));
                field.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
            }
            field.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return input;
        }

        @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
            row = requireArguments().getInt("row");
            index = requireArguments().getInt("key");
            try { original = new JSONObject(requireArguments().getString("value")); }
            catch (JSONException e) { throw new IllegalStateException(e); }
            LinearLayout form = new LinearLayout(requireContext());
            form.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            form.setPadding(padding, padding, padding, padding);
            TextView help = new TextView(requireContext());
            help.setText(R.string.extra_key_help);
            form.addView(help);
            key = input(form, R.string.extra_key_value, original.optString("macro", original.optString("key")));
            macro = new CheckBox(requireContext());
            macro.setText(R.string.extra_macro);
            macro.setChecked(original.has("macro"));
            form.addView(macro);
            label = input(form, R.string.extra_key_label, original.optString("display"));
            textColor = input(form, R.string.extra_key_text_color, original.optString("text-color"));
            backgroundColor = input(form, R.string.extra_key_background_color, original.optString("background-color"));
            hint = input(form, R.string.extra_key_hint, original.optString("hint"));
            inset = input(form, R.string.extra_key_inset, original.optString("inset"));
            inset.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            hold = input(form, R.string.extra_hold, original.optString("hold"));
            holdLabel = input(form, R.string.extra_hold_label, original.optString("hold-display"));
            Object popupValue = original.opt("popup");
            JSONObject popupObject = popupValue instanceof JSONObject ? (JSONObject) popupValue : new JSONObject();
            popup = input(form, R.string.extra_popup, popupValue instanceof String ? (String) popupValue
                : popupObject.optString("macro", popupObject.optString("key")));
            popupMacro = new CheckBox(requireContext());
            popupMacro.setText(R.string.extra_macro);
            popupMacro.setChecked(popupObject.has("macro"));
            form.addView(popupMacro);
            popupLabel = input(form, R.string.extra_popup_label, popupObject.optString("display"));
            flingLeft = input(form, R.string.extra_fling_left, alternateLabel("fling-left"));
            flingRight = input(form, R.string.extra_fling_right, alternateLabel("fling-right"));
            flingDown = input(form, R.string.extra_fling_down, alternateLabel("fling-down"));
            if (state != null) {
                flingLeft.setText(state.getString("flingLeft", alternateLabel("fling-left")));
                flingRight.setText(state.getString("flingRight", alternateLabel("fling-right")));
                flingDown.setText(state.getString("flingDown", alternateLabel("fling-down")));
                key.setText(state.getString("key"));
                label.setText(state.getString("label"));
                textColor.setText(state.getString("textColor", original.optString("text-color")));
                backgroundColor.setText(state.getString("backgroundColor", original.optString("background-color")));
                hint.setText(state.getString("hint", original.optString("hint")));
                inset.setText(state.getString("inset", original.optString("inset")));
                hold.setText(state.getString("hold", original.optString("hold")));
                holdLabel.setText(state.getString("holdLabel", original.optString("hold-display")));
                popup.setText(state.getString("popup"));
                popupLabel.setText(state.getString("popupLabel"));
                macro.setChecked(state.getBoolean("macro"));
                popupMacro.setChecked(state.getBoolean("popupMacro"));
            }
            ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
            if (parent != null && index < parent.layout.keyCount(row)) {
                MaterialButton remove = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
                remove.setText(R.string.extra_remove_key);
                remove.setOnClickListener(v -> { parent.layout.removeKey(row, index); parent.changed(); dismiss(); });
                form.addView(remove);
                LinearLayout moves = new LinearLayout(requireContext());
                form.addView(moves);
                if (index > 0) parent.button(moves, getString(R.string.extra_key_left), v -> {
                    parent.layout.moveKey(row, index, -1); parent.changed(); dismiss();
                });
                if (index + 1 < parent.layout.keyCount(row)) parent.button(moves, getString(R.string.extra_key_right), v -> {
                    parent.layout.moveKey(row, index, 1); parent.changed(); dismiss();
                });
            }
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(form);
            return DialogUtils.builder(requireContext()).setTitle(R.string.extra_edit_key).setView(scroll)
                .setPositiveButton(R.string.extra_apply, null).setNegativeButton(android.R.string.cancel, null).create();
        }

        @Override public void onStart() {
            super.onStart();
            ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (key.getText().length() == 0) { key.setError(getString(R.string.extra_required)); return; }
                try {
                    JSONObject edited = new JSONObject(original.toString());
                    edited.remove("key"); edited.remove("macro");
                    edited.put(macro.isChecked() ? "macro" : "key", key.getText().toString());
                    if (label.getText().length() == 0) edited.remove("display");
                    else edited.put("display", label.getText().toString());
                    if (!storeColor(edited, "text-color", textColor)
                        || !storeColor(edited, "background-color", backgroundColor)) return;
                    String hintText = hint.getText().toString().trim();
                    if (hintText.isEmpty()) edited.remove("hint");
                    else edited.put("hint", hintText);
                    String insetValue = inset.getText().toString().trim();
                    if (insetValue.isEmpty()) {
                        edited.remove("inset");
                    } else {
                        Float dp = ExtraKeysColorParser.parseCornerRadiusDp(insetValue);
                        if (dp == null || dp > 8) {
                            inset.setError(getString(R.string.extra_key_invalid_inset));
                            return;
                        }
                        edited.put("inset", dp);
                    }
                    String holdKey = hold.getText().toString().trim();
                    if (holdKey.isEmpty()) edited.remove("hold");
                    else edited.put("hold", holdKey);
                    if (holdKey.isEmpty() || holdLabel.getText().length() == 0) edited.remove("hold-display");
                    else edited.put("hold-display", holdLabel.getText().toString());
                    if (popup.getText().length() == 0) edited.remove("popup");
                    else {
                        Object previous = original.opt("popup");
                        JSONObject p = previous instanceof JSONObject ? new JSONObject(previous.toString()) : new JSONObject();
                        p.remove("key"); p.remove("macro");
                        p.put(popupMacro.isChecked() ? "macro" : "key", popup.getText().toString());
                        if (popupLabel.getText().length() == 0) p.remove("display");
                        else p.put("display", popupLabel.getText().toString());
                        edited.put("popup", p);
                    }
                    storeAlternate(edited, "fling-left", flingLeft);
                    storeAlternate(edited, "fling-right", flingRight);
                    storeAlternate(edited, "fling-down", flingDown);
                    ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
                    if (parent == null) return;
                    parent.layout.putKey(row, index, edited);
                    parent.changed();
                    dismiss();
                } catch (JSONException e) { key.setError(getString(R.string.extra_invalid)); }
            });
        }

        private String alternateLabel(String name) {
            JSONObject alternate = original.optJSONObject(name);
            return alternate == null ? original.optString(name)
                : alternate.optString("key", alternate.optString("macro", alternate.optString("command")));
        }

        private void storeAlternate(JSONObject edited, String name, EditText field) throws JSONException {
            String value = field.getText().toString();
            if (value.equals(alternateLabel(name))) return;
            if (value.isEmpty()) edited.remove(name);
            else edited.put(name, value);
        }

        private boolean storeColor(JSONObject edited, String name, EditText field) throws JSONException {
            String color = field.getText().toString().trim();
            if (color.isEmpty()) edited.remove(name);
            else if (ExtraKeysColorParser.parseColor(color) != null) edited.put(name, color);
            else {
                field.setError(getString(R.string.extra_key_invalid_color));
                return false;
            }
            return true;
        }

        @Override public void onSaveInstanceState(@NonNull Bundle out) {
            super.onSaveInstanceState(out);
            out.putString("textColor", textColor.getText().toString());
            out.putString("backgroundColor", backgroundColor.getText().toString());
            out.putString("key", key.getText().toString());
            out.putString("label", label.getText().toString());
            out.putString("hint", hint.getText().toString());
            out.putString("inset", inset.getText().toString());
            out.putString("hold", hold.getText().toString());
            out.putString("holdLabel", holdLabel.getText().toString());
            out.putString("popup", popup.getText().toString());
            out.putString("popupLabel", popupLabel.getText().toString());
            out.putString("flingLeft", flingLeft.getText().toString());
            out.putString("flingRight", flingRight.getText().toString());
            out.putString("flingDown", flingDown.getText().toString());
            out.putBoolean("macro", macro.isChecked());
            out.putBoolean("popupMacro", popupMacro.isChecked());
        }
    }
}
