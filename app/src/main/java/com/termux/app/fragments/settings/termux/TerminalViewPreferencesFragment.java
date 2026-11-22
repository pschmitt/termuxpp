package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.util.Linkify;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.app.settings.AppAccent;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.termux.app.terminal.TerminalAppearance;
import com.termux.app.terminal.TerminalAppearance.Setting;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TextStyle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConfigDirs;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

@Keep
public class TerminalViewPreferencesFragment extends GroupedPreferencesFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TerminalViewPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_terminal_view_preferences, rootKey);
        ListPreference accent = findPreference(AppAccent.KEY);
        if (accent != null) accent.setOnPreferenceChangeListener((preference, value) -> {
            if (!value.equals(accent.getValue())) {
                FragmentActivity activity = requireActivity();
                activity.getWindow().getDecorView().post(activity::recreate);
            }
            return true;
        });
        for (Setting setting : Setting.values()) {
            Preference preference = findPreference(setting.key);
            preference.setOnPreferenceClickListener(row -> {
                showChoices(row, setting);
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        for (Setting setting : Setting.values()) updateSummary(findPreference(setting.key), setting);
    }

    private void updateSummary(Preference preference, Setting setting) {
        String choice = setting.selection(requireContext());
        if (!TerminalAppearance.CONFIG.equals(choice)) {
            preference.setSummary(label(choice));
            return;
        }

        List<String> paths = TerminalAppearance.configPaths();
        File file = TermuxConfigDirs.resolveFile(paths, setting.configFile, setting == Setting.FONT);
        if (setting == Setting.FONT && (!file.isFile() || file.length() == 0)) {
            for (String variant : new String[]{"font-bold.ttf", "font-italic.ttf", "font-bold-italic.ttf"}) {
                File candidate = TermuxConfigDirs.resolveFile(paths, variant, true);
                if (candidate.isFile() && candidate.length() > 0) {
                    file = candidate;
                    break;
                }
            }
        }
        if (file.isFile() && (setting == Setting.COLORS || file.length() > 0)) {
            boolean nativeConfig = TermuxConstants.TERMUX_CONFIG_DIR_PATHS_LIST.contains(file.getParent());
            preference.setSummary(nativeConfig ? R.string.appearance_native_config : R.string.appearance_guest_config);
        } else {
            preference.setSummary(R.string.appearance_config_default);
        }
    }

    private String label(String choice) {
        if (TerminalAppearance.CONFIG.equals(choice)) return getString(R.string.appearance_config_files);
        if (TerminalAppearance.DEFAULT.equals(choice)) return getString(R.string.appearance_default);
        int extension = choice.lastIndexOf('.');
        String title = (extension < 0 ? choice : choice.substring(0, extension)).replace('-', ' ');
        return title.isEmpty() ? title : Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }

    private void showChoices(Preference row, Setting setting) {
        Context context = requireContext();
        List<String> paths = TerminalAppearance.configPaths();
        List<String> choices = new ArrayList<>();
        choices.add(TerminalAppearance.CONFIG);
        choices.add(TerminalAppearance.DEFAULT);
        try {
            String[] assets = context.getAssets().list(setting.assetDirectory);
            if (assets != null) {
                for (String asset : assets) {
                    if (asset.endsWith(setting.extension)) choices.add(asset);
                }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage("TerminalAppearance", "Could not list presets", e);
        }

        String selected = setting.selection(context);
        String font = Setting.FONT.selection(context);
        String colors = Setting.COLORS.selection(context);
        Typeface currentFont = TerminalAppearance.loadFont(context, font, paths);
        TerminalColorScheme currentColors = TerminalAppearance.loadColors(context, colors, paths);

        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(R.string.appearance_filter);
        ListView list = new ListView(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1) {
            @Override
            public @NonNull View getView(int position, View recycled, @NonNull ViewGroup parent) {
                String choice = getItem(position);
                TextView preview = (TextView) super.getView(position, recycled, parent);
                int[] palette = setting == Setting.COLORS
                    ? TerminalAppearance.loadColors(context, choice, paths).mDefaultColors
                    : currentColors.mDefaultColors;
                String title = label(choice);
                if (choice.equals(selected)) title = getString(R.string.appearance_selected, title);
                if (setting == Setting.COLORS) {
                    SpannableString swatches = new SpannableString(title + "\n● ● ● ● ● ●");
                    for (int color = 1; color <= 6; color++) {
                        int start = title.length() + color * 2 - 1;
                        swatches.setSpan(new ForegroundColorSpan(palette[color]), start, start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    preview.setContentDescription(title);
                    preview.setText(swatches);
                } else {
                    preview.setContentDescription(title);
                    preview.setText(getString(R.string.appearance_font_sample, title));
                }
                preview.setTypeface(setting == Setting.FONT
                    ? TerminalAppearance.loadFont(context, choice, paths) : currentFont);
                preview.setTextColor(palette[TextStyle.COLOR_INDEX_FOREGROUND]);
                preview.setBackgroundColor(palette[TextStyle.COLOR_INDEX_BACKGROUND]);
                int vertical = Math.round(8 * getResources().getDisplayMetrics().density);
                preview.setPadding(preview.getPaddingLeft(), vertical, preview.getPaddingRight(), vertical);
                return preview;
            }
        };
        adapter.addAll(choices);
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                adapter.setNotifyOnChange(false);
                adapter.clear();
                for (String choice : choices) {
                    if (label(choice).toLowerCase(Locale.ROOT).contains(query)) adapter.add(choice);
                }
                adapter.notifyDataSetChanged();
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
        content.addView(search, new LinearLayout.LayoutParams(-1, -2));
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = DialogUtils.builder(context, R.drawable.ui_ic_palette, false)
            .setTitle(row.getTitle())
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        list.setOnItemClickListener((parent, item, position, id) -> {
            setting.select(context, adapter.getItem(position));
            updateSummary(row, setting);
            dialog.dismiss();
        });
        list.setOnItemLongClickListener((parent, item, position, id) ->
            showLicense(setting, adapter.getItem(position)));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getWindow().setLayout(dialog.getWindow().getAttributes().width,
            Math.round(getResources().getDisplayMetrics().heightPixels * 0.7f));
    }
    private boolean showLicense(Setting setting, String choice) {
        if (!choice.endsWith(setting.extension)) return false;
        Context context = requireContext();
        String file = choice.substring(0, choice.length() - setting.extension.length()) + ".txt";
        try (InputStream input = context.getAssets().open(setting.assetDirectory + "/" + file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) content.append(buffer, 0, count);
            SpannableString license = new SpannableString(content);
            Linkify.addLinks(license, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
            AlertDialog dialog = DialogUtils.builder(context)
                .setTitle(label(choice))
                .setMessage(license)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) message.setMovementMethod(LinkMovementMethod.getInstance());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

class TerminalViewPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalViewPreferencesDataStore mInstance;

    private TerminalViewPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalViewPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalViewPreferencesDataStore(context);
        }
        return mInstance;
    }



    @Override
    public void putString(String key, String value) {
        if (AppAccent.KEY.equals(key)) AppAccent.set(mContext, value);
    }

    @Override
    public String getString(String key, String defaultValue) {
        return AppAccent.KEY.equals(key) ? AppAccent.get(mContext) : defaultValue;
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "terminal_margin_adjustment":
                    mPreferences.setTerminalMarginAdjustment(value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "terminal_margin_adjustment":
                return mPreferences.isTerminalMarginAdjustmentEnabled();
            default:
                return false;
        }
    }

}
