package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;
import com.termux.app.settings.ExtraKeysLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;

/**
 * Property editor for one extra key. Every change is validated and reported as a complete key
 * object; attributes the editor does not know survive because edits start from the original object.
 */
final class ExtraKeyInspector extends LinearLayout {
    interface Listener { void onKeyEdited(@NonNull JSONObject key); }

    static final String[] HINT_MODES = {"auto", "custom", "off"};
    static final String[] ALTERNATES = {"popup", "fling-left", "fling-right", "fling-down"};

    private final ExtraKeyActionEditor tap;
    private final ExtraKeyColorField textColor;
    private final ExtraKeyColorField backgroundColor;
    private final MaterialButtonToggleGroup hintMode;
    private final TextInputLayout hintField;
    private final TextInputEditText hint;
    private final SwitchMaterial customInset;
    private final Slider inset;
    private final TextView insetValue;
    private final ExtraKeyActionEditor[] alternates;
    private final ExtraKeyActionEditor hold;
    private JSONObject original = new JSONObject();
    @Nullable private Listener listener;
    private boolean binding;

    ExtraKeyInspector(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
        ExtraKeyActionEditor.Mode none = ExtraKeyActionEditor.Mode.NONE;
        ExtraKeyActionEditor.Mode key = ExtraKeyActionEditor.Mode.KEY;
        ExtraKeyActionEditor.Mode macro = ExtraKeyActionEditor.Mode.MACRO;
        ExtraKeyActionEditor.Mode command = ExtraKeyActionEditor.Mode.COMMAND;

        tap = new ExtraKeyActionEditor(context, R.string.extra_editor_section_tap, R.string.extra_editor_value_key,
            key, macro, command, ExtraKeyActionEditor.Mode.BLANK);
        addView(tap);

        addView(heading(context, R.string.extra_editor_section_look));
        textColor = new ExtraKeyColorField(context, R.string.extra_editor_text_color);
        addView(textColor);
        backgroundColor = new ExtraKeyColorField(context, R.string.extra_editor_background_color);
        addView(backgroundColor);
        TextView hintTitle = new TextView(context);
        hintTitle.setText(R.string.extra_editor_hint_title);
        hintTitle.setPadding(0, dp(8), 0, 0);
        addView(hintTitle);
        hintMode = new MaterialButtonToggleGroup(context);
        hintMode.setSingleSelection(true);
        hintMode.setSelectionRequired(true);
        for (String mode : HINT_MODES) {
            MaterialButton button = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setId(View.generateViewId());
            button.setTag(mode);
            button.setAllCaps(false);
            button.setText(mode.equals("auto") ? R.string.extra_editor_hint_auto
                : mode.equals("custom") ? R.string.extra_editor_hint_custom : R.string.extra_editor_hint_off);
            hintMode.addView(button);
        }
        addView(hintMode);
        hintField = new TextInputLayout(context);
        hintField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        hintField.setHint(context.getString(R.string.extra_editor_hint));
        hint = new TextInputEditText(context);
        hint.setId(View.generateViewId());
        hint.setSaveEnabled(false);
        hint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hintField.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        addView(hintField, params);
        customInset = new SwitchMaterial(context);
        customInset.setText(R.string.extra_editor_custom_inset);
        addView(customInset);
        insetValue = new TextView(context);
        addView(insetValue);
        inset = new Slider(context);
        inset.setValueFrom(0);
        inset.setValueTo(8);
        inset.setStepSize(0.5f);
        addView(inset);

        addView(heading(context, R.string.extra_editor_section_gestures));
        alternates = new ExtraKeyActionEditor[]{
            new ExtraKeyActionEditor(context, R.string.extra_editor_swipe_up, R.string.extra_editor_value_key, none, key, macro, command),
            new ExtraKeyActionEditor(context, R.string.extra_editor_fling_left, R.string.extra_editor_value_key, none, key, macro, command),
            new ExtraKeyActionEditor(context, R.string.extra_editor_fling_right, R.string.extra_editor_value_key, none, key, macro, command),
            new ExtraKeyActionEditor(context, R.string.extra_editor_fling_down, R.string.extra_editor_value_key, none, key, macro, command),
        };
        addView(alternates[0]);
        hold = new ExtraKeyActionEditor(context, R.string.extra_editor_long_press, R.string.extra_editor_value_hold, none, key);
        addView(hold);
        addView(alternates[1]);
        addView(alternates[2]);
        addView(alternates[3]);

        ExtraKeyActionEditor.Listener changed = this::changed;
        tap.setListener(changed);
        hold.setListener(changed);
        for (ExtraKeyActionEditor alternate : alternates) alternate.setListener(changed);
        textColor.setListener(this::changed);
        backgroundColor.setListener(this::changed);
        hint.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { changed(); }
        });
        hintMode.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            updateHint();
            changed();
        });
        customInset.setOnCheckedChangeListener((button, checked) -> { updateInset(); changed(); });
        inset.addOnChangeListener((slider, value, fromUser) -> { updateInset(); if (fromUser) changed(); });
    }

    void setListener(@Nullable Listener listener) { this.listener = listener; }

    void bind(@NonNull JSONObject key) {
        binding = true;
        original = key;
        tap.bind(key);
        textColor.bind(key.optString("text-color", null));
        backgroundColor.bind(key.optString("background-color", null));
        hint.setText(key.optString("hint"));
        // Without a mode, a hint text is custom and no hint is the hidden legacy default.
        String mode = key.optString("hint-mode", key.has("hint") ? "custom" : "off");
        checkHintMode(mode);
        updateHint();
        boolean hasInset = key.has("inset");
        customInset.setChecked(hasInset);
        float dp = (float) key.optDouble("inset", 2);
        inset.setValue(Math.max(0, Math.min(8, Math.round(dp * 2) / 2f)));
        for (int i = 0; i < ALTERNATES.length; i++) alternates[i].bind(key.opt(ALTERNATES[i]));
        hold.bindHold(key.optString("hold", null), key.optString("hold-display", null));
        updateInset();
        binding = false;
    }

    /** Prepares a freshly inserted spacer for typing its key; it is applied once a value is entered. */
    void startNewKey() { tap.startEntry(ExtraKeyActionEditor.Mode.KEY); }

    /** Builds the edited key, or returns {@code null} and shows errors when the input is incomplete. */
    @Nullable
    JSONObject build() {
        boolean valid = tap.check() & textColor.check() & backgroundColor.check() & hold.check();
        for (ExtraKeyActionEditor alternate : alternates) valid &= alternate.check();
        if (!valid) return null;
        try {
            JSONObject edited = new JSONObject(original.toString());
            tap.writeTo(edited);
            put(edited, "text-color", textColor.value());
            put(edited, "background-color", backgroundColor.value());
            String mode = hintMode();
            edited.remove("hint");
            if (mode.equals("custom")) {
                String text = text(hint).trim();
                if (text.isEmpty()) {
                    hintField.setError(getContext().getString(R.string.extra_editor_hint_required));
                    return null;
                }
                hintField.setError(null);
                edited.put("hint", text);
                edited.put("hint-mode", "custom");
            } else if (mode.equals("off") && !original.has("hint-mode") && !original.has("hint")) {
                // Leave untouched keys in the legacy form.
                edited.remove("hint-mode");
            } else {
                edited.put("hint-mode", mode);
            }
            edited.remove("inset");
            if (customInset.isChecked()) {
                float dp = inset.getValue();
                if (dp == Math.rint(dp)) edited.put("inset", (int) dp);
                else edited.put("inset", (double) dp);
            }
            for (int i = 0; i < ALTERNATES.length; i++) {
                Object nested = alternates[i].nested(original.opt(ALTERNATES[i]));
                if (nested == null) edited.remove(ALTERNATES[i]);
                else edited.put(ALTERNATES[i], nested);
            }
            edited.remove("hold");
            edited.remove("hold-display");
            if (hold.mode() == ExtraKeyActionEditor.Mode.KEY) {
                edited.put("hold", hold.value().trim());
                if (!hold.label().isEmpty()) edited.put("hold-display", hold.label());
            }
            ExtraKeysLayout.validate(edited);
            tap.showError(null);
            return edited;
        } catch (JSONException e) {
            tap.showError(e.getMessage());
            return null;
        }
    }

    private void changed() {
        if (binding || listener == null) return;
        JSONObject edited = build();
        if (edited != null) {
            original = edited;
            listener.onKeyEdited(edited);
        }
    }

    private String hintMode() {
        View checked = hintMode.findViewById(hintMode.getCheckedButtonId());
        return checked == null ? "off" : (String) checked.getTag();
    }

    private void checkHintMode(String mode) {
        for (int i = 0; i < hintMode.getChildCount(); i++) {
            View child = hintMode.getChildAt(i);
            if (mode.equals(child.getTag())) hintMode.check(child.getId());
        }
        if (hintMode.getCheckedButtonId() == View.NO_ID) checkHintMode("off");
    }

    private void updateHint() {
        hintField.setVisibility(hintMode().equals("custom") ? VISIBLE : GONE);
    }

    private void updateInset() {
        inset.setEnabled(customInset.isChecked());
        inset.setVisibility(customInset.isChecked() ? VISIBLE : GONE);
        insetValue.setVisibility(customInset.isChecked() ? VISIBLE : GONE);
        insetValue.setText(getContext().getString(R.string.extra_editor_inset_value,
            new DecimalFormat("0.#").format(inset.getValue())));
    }

    private static void put(JSONObject target, String name, String value) throws JSONException {
        if (value.isEmpty()) target.remove(name);
        else target.put(name, value);
    }

    private TextView heading(Context context, int title) {
        TextView view = new TextView(context);
        view.setText(title);
        view.setTextAppearance(context, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
        view.setPadding(0, dp(20), 0, 0);
        return view;
    }

    private static String text(TextView view) { return view.getText() == null ? "" : view.getText().toString(); }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
