package com.termux.app.fragments.settings.termux;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import com.termux.app.settings.SettingsSearchIndex;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;

import com.termux.R;
import com.termux.app.settings.PropertyInputSpec;

public class PropertyTextDialog extends DialogFragment {
    private EditText text;

    static void show(PropertiesPreferencesFragment parent, EditTextPreference preference) {
        PropertyTextDialog dialog = new PropertyTextDialog();
        Bundle args = new Bundle();
        args.putString("key", preference.getKey());
        args.putString("value", preference.getText());
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "property_text");
    }

    @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
        String key = requireArguments().getString("key");
        View editor = LayoutInflater.from(requireContext()).inflate(R.layout.ui_property_editor, null);
        text = editor.findViewById(R.id.property_value);
        text.setInputType(PropertyInputSpec.inputType(key));
        text.setText(state == null ? requireArguments().getString("value", "") : state.getString("value"));
        ((TextView) editor.findViewById(R.id.property_help)).setText(PropertyInputSpec.help(key));
        ((TextView) editor.findViewById(R.id.property_key)).setText(key);
        if (SettingsPreview.supports(key)) {
            SettingsPreview preview = new SettingsPreview(requireContext(), key);
            preview.setTag("property-visual-preview");
            int height = Math.round((key.startsWith("extra-keys") ? 48 : 124) * getResources().getDisplayMetrics().density);
            LinearLayout container = (LinearLayout) ((ScrollView) editor).getChildAt(0);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
            layout.topMargin = Math.round(12 * getResources().getDisplayMetrics().density);
            container.addView(preview, 1, layout);
            preview.setValue(text.getText().toString());
            text.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { preview.setValue(s.toString()); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
        return DialogUtils.builder(requireContext()).setTitle(SettingsSearchIndex.propertyTitle(key))
            .setView(editor)
            .setPositiveButton(R.string.extra_save, null)
            .setNeutralButton(R.string.properties_default, null)
            .setNegativeButton(android.R.string.cancel, null).create();
    }

    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            text.setText("");
            text.setError(null);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = requireArguments().getString("key");
            String value = text.getText().toString();
            String error = PropertyInputSpec.validationError(key, value);
            if (error != null) { text.setError(error); return; }
            PropertiesPreferencesFragment parent = (PropertiesPreferencesFragment) getTargetFragment();
            EditTextPreference preference = parent == null ? null : parent.findPreference(key);
            if (preference != null && preference.callChangeListener(value)) {
                preference.setText(value);
                dismiss();
            }
        });
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("value", text.getText().toString());
    }
}
