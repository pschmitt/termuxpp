package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Edits one extra-key action: what a tap, swipe up, long press or fling does. The action is a key,
 * a space-separated macro, a shell command, a blank spacer or nothing, plus an optional label.
 */
final class ExtraKeyActionEditor extends LinearLayout {
    enum Mode { NONE, KEY, MACRO, COMMAND, BLANK }

    /** Keys offered as one-tap chips. Special keys first, then common terminal symbols. */
    static final String[] PALETTE = {"ESC", "TAB", "CTRL", "ALT", "SHIFT", "FN", "ENTER", "BKSP", "DEL", "INS",
        "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "SPACE", "KEYBOARD", "DRAWER", "PASTE",
        "SCROLL", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        "/", "-", "|", "~", "_", ":", ";", "&", "*", "$", "#", "\\", "'", "\"", "`", "<", ">", "[", "]", "{", "}"};

    interface Listener { void onActionChanged(); }

    private final Mode[] modes;
    private final MaterialButtonToggleGroup toggle;
    private final TextView help;
    private final TextInputLayout valueField;
    private final TextInputEditText value;
    private final HorizontalScrollView palette;
    private final TextInputLayout labelField;
    private final TextInputEditText label;
    private final int valueHint;
    @Nullable private Listener listener;
    private boolean binding;

    /**
     * @param title Section heading, for example "Swipe up".
     * @param valueHint Hint for the value field in key mode; macro and command modes use their own.
     */
    ExtraKeyActionEditor(@NonNull Context context, int title, int valueHint, @NonNull Mode... modes) {
        super(context);
        this.modes = modes;
        this.valueHint = valueHint;
        setOrientation(VERTICAL);
        setPadding(0, dp(12), 0, 0);

        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextAppearance(context, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2);
        addView(heading);

        toggle = new MaterialButtonToggleGroup(context);
        toggle.setSingleSelection(true);
        toggle.setSelectionRequired(true);
        for (Mode mode : modes) {
            MaterialButton button = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setId(View.generateViewId());
            button.setTag(mode);
            button.setText(modeTitle(mode));
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(10), 0, dp(10), 0);
            toggle.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        HorizontalScrollView toggleScroll = new HorizontalScrollView(context);
        toggleScroll.setHorizontalScrollBarEnabled(false);
        toggleScroll.addView(toggle);
        addView(toggleScroll);
        toggle.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            updateVisibility();
            notifyChanged();
        });

        help = new TextView(context);
        help.setPadding(0, dp(4), 0, 0);
        addView(help);

        valueField = field(context);
        value = input(valueField);
        palette = buildPalette(context);
        addView(palette);
        labelField = field(context);
        labelField.setHint(context.getString(R.string.extra_editor_label));
        label = input(labelField);
        setMode(modes[0]);
    }

    void setListener(@Nullable Listener listener) { this.listener = listener; }

    /** Reads the action from a key object, for example a top-level key or a popup object. */
    void bind(@Nullable Object source) {
        binding = true;
        Mode mode = Mode.NONE;
        String text = "";
        String display = "";
        if (source instanceof String) {
            text = (String) source;
            mode = text.isEmpty() && supports(Mode.BLANK) ? Mode.BLANK : Mode.KEY;
        } else if (source instanceof JSONObject) {
            JSONObject object = (JSONObject) source;
            display = object.optString("display");
            if (object.has("command")) { mode = Mode.COMMAND; text = object.optString("command"); }
            else if (object.has("macro")) { mode = Mode.MACRO; text = object.optString("macro"); }
            else if (object.has("key")) {
                text = object.optString("key");
                mode = text.isEmpty() && display.isEmpty() && supports(Mode.BLANK) ? Mode.BLANK : Mode.KEY;
            }
        }
        if (!supports(mode)) mode = modes[0];
        setMode(mode);
        value.setText(text);
        label.setText(display);
        valueField.setError(null);
        binding = false;
    }

    /** Sets only a hold-style value with a separate label, for {@code hold} and {@code hold-display}. */
    void bindHold(@Nullable String key, @Nullable String display) {
        binding = true;
        setMode(key == null || key.isEmpty() ? Mode.NONE : Mode.KEY);
        value.setText(key == null ? "" : key);
        label.setText(display == null ? "" : display);
        valueField.setError(null);
        binding = false;
    }

    /** Switches mode without reporting a change and focuses the value field. */
    void startEntry(Mode mode) {
        binding = true;
        setMode(mode);
        value.setText("");
        binding = false;
        value.requestFocus();
    }

    Mode mode() {
        View checked = toggle.findViewById(toggle.getCheckedButtonId());
        return checked == null ? modes[0] : (Mode) checked.getTag();
    }

    String value() { return text(value); }
    String label() { return text(label); }

    /** Validates required input and shows the error inline. */
    boolean check() {
        Mode mode = mode();
        boolean needsValue = mode == Mode.KEY || mode == Mode.MACRO || mode == Mode.COMMAND;
        if (needsValue && value().trim().isEmpty()) {
            valueField.setError(getContext().getString(R.string.extra_required));
            return false;
        }
        valueField.setError(null);
        return true;
    }

    void showError(@Nullable String error) { valueField.setError(error); }

    /**
     * Writes the action into {@code target}, replacing its key, macro, command and display fields.
     */
    void writeTo(@NonNull JSONObject target) throws JSONException {
        target.remove("key");
        target.remove("macro");
        target.remove("command");
        target.remove("display");
        switch (mode()) {
            case KEY: target.put("key", value()); break;
            case MACRO: target.put("macro", value().trim()); break;
            case COMMAND: target.put("command", value()); break;
            case BLANK: target.put("key", ""); return;
            default: return;
        }
        if (!label().isEmpty()) target.put("display", label());
    }

    /**
     * Builds a nested alternate action, keeping unknown attributes of {@code previous}. Returns
     * {@code null} for {@link Mode#NONE}. A plain key without label is written compactly as a string.
     */
    @Nullable
    Object nested(@Nullable Object previous) throws JSONException {
        if (mode() == Mode.NONE) return null;
        JSONObject object = previous instanceof JSONObject ? new JSONObject(previous.toString()) : new JSONObject();
        writeTo(object);
        if (object.length() == 1 && object.has("key")) return object.getString("key");
        return object;
    }

    private boolean supports(Mode mode) {
        for (Mode candidate : modes) if (candidate == mode) return true;
        return false;
    }

    private void setMode(Mode mode) {
        for (int i = 0; i < toggle.getChildCount(); i++) {
            View child = toggle.getChildAt(i);
            if (child.getTag() == mode) toggle.check(child.getId());
        }
        updateVisibility();
    }

    private void updateVisibility() {
        Mode mode = mode();
        boolean hasValue = mode == Mode.KEY || mode == Mode.MACRO || mode == Mode.COMMAND;
        valueField.setVisibility(hasValue ? VISIBLE : GONE);
        labelField.setVisibility(hasValue ? VISIBLE : GONE);
        palette.setVisibility(mode == Mode.KEY || mode == Mode.MACRO ? VISIBLE : GONE);
        valueField.setHint(getContext().getString(mode == Mode.MACRO ? R.string.extra_editor_value_macro
            : mode == Mode.COMMAND ? R.string.extra_editor_value_command : valueHint));
        int helpText = mode == Mode.BLANK ? R.string.extra_editor_blank_help
            : mode == Mode.NONE ? R.string.extra_editor_none_help
            : mode == Mode.COMMAND ? 0 : R.string.extra_editor_palette_help;
        help.setVisibility(helpText == 0 ? GONE : VISIBLE);
        if (helpText != 0) help.setText(helpText);
        value.setInputType(mode == Mode.COMMAND
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    private HorizontalScrollView buildPalette(Context context) {
        ChipGroup chips = new ChipGroup(context);
        chips.setSingleLine(true);
        for (String key : PALETTE) {
            Chip chip = new Chip(context);
            chip.setText(key);
            chip.setContentDescription(key);
            chip.setOnClickListener(v -> {
                if (mode() == Mode.MACRO) {
                    String current = value().trim();
                    value.setText(current.isEmpty() ? key : current + " " + key);
                } else {
                    value.setText(key);
                }
                value.setSelection(value.length());
            });
            chips.addView(chip);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(chips);
        return scroll;
    }

    private TextInputLayout field(Context context) {
        TextInputLayout field = new TextInputLayout(context);
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, 0);
        addView(field, params);
        return field;
    }

    private TextInputEditText input(TextInputLayout field) {
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setId(View.generateViewId());
        input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { notifyChanged(); }
        });
        field.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private void notifyChanged() {
        if (!binding && listener != null) listener.onActionChanged();
    }

    private int modeTitle(Mode mode) {
        switch (mode) {
            case KEY: return R.string.extra_editor_mode_key;
            case MACRO: return R.string.extra_editor_mode_macro;
            case COMMAND: return R.string.extra_editor_mode_command;
            case BLANK: return R.string.extra_editor_mode_blank;
            default: return R.string.extra_editor_mode_none;
        }
    }

    private static String text(TextView view) { return view.getText() == null ? "" : view.getText().toString(); }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
