package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;

/** A color choice for one key: theme default, a swatch, or any hex value. */
final class ExtraKeyColorField extends LinearLayout {
    static final String[] SWATCHES = {"#FFFFFF", "#000000", "#303030", "#5F6368", "#9AA0A6",
        "#E53935", "#FB8C00", "#FDD835", "#43A047", "#00ACC1", "#1E88E5", "#8E24AA", "#D81B60", "#80DEEA"};

    private final LinearLayout swatches;
    private final TextInputLayout hexField;
    private final TextInputEditText hex;
    @Nullable private Runnable listener;
    private boolean binding;

    ExtraKeyColorField(@NonNull Context context, int title) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, dp(8), 0, 0);
        TextView heading = new TextView(context);
        heading.setText(title);
        addView(heading);

        swatches = new LinearLayout(context);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        swatches.addView(swatch(context, null));
        for (String color : SWATCHES) swatches.addView(swatch(context, color));
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(swatches);
        addView(scroll);

        hexField = new TextInputLayout(context);
        hexField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        hexField.setHint(context.getString(R.string.extra_editor_color_hex));
        hex = new TextInputEditText(context);
        hex.setId(View.generateViewId());
        hex.setSaveEnabled(false);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                updateSelection();
                if (!binding && listener != null) listener.run();
            }
        });
        hexField.addView(hex, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, 0);
        addView(hexField, params);
    }

    void setListener(@Nullable Runnable listener) { this.listener = listener; }

    void bind(@Nullable String color) {
        binding = true;
        hex.setText(color == null ? "" : color);
        hexField.setError(null);
        binding = false;
        updateSelection();
    }

    /** The entered color, or an empty string for the theme default. */
    String value() { return hex.getText() == null ? "" : hex.getText().toString().trim(); }

    boolean check() {
        String value = value();
        boolean valid = value.isEmpty() || ExtraKeysColorParser.parseColor(value) != null;
        hexField.setError(valid ? null : getContext().getString(R.string.extra_key_invalid_color));
        return valid;
    }

    private View swatch(Context context, @Nullable String color) {
        View view = new View(context);
        view.setTag(color);
        view.setContentDescription(color == null ? context.getString(R.string.extra_editor_color_default) : color);
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(v -> hex.setText(color == null ? "" : color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
        params.setMargins(dp(3), dp(6), dp(3), dp(6));
        view.setLayoutParams(params);
        paint(view, false);
        return view;
    }

    private void paint(View view, boolean selected) {
        String color = (String) view.getTag();
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        Integer parsed = color == null ? null : ExtraKeysColorParser.parseColor(color);
        // The theme default is a hollow circle.
        shape.setColor(parsed == null ? Color.TRANSPARENT : parsed);
        int accent = resolveAccent();
        shape.setStroke(selected ? dp(3) : dp(1), selected ? accent : 0x80808080);
        view.setBackground(shape);
        view.setSelected(selected);
    }

    private void updateSelection() {
        String value = value();
        Integer current = value.isEmpty() ? null : ExtraKeysColorParser.parseColor(value);
        for (int i = 0; i < swatches.getChildCount(); i++) {
            View view = swatches.getChildAt(i);
            String color = (String) view.getTag();
            boolean selected = color == null ? value.isEmpty()
                : current != null && current.equals(ExtraKeysColorParser.parseColor(color));
            paint(view, selected);
        }
    }

    private int resolveAccent() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)
            && value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT ? value.data : 0xFF80DEEA;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
