package com.termux.app.fragments.settings;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.app.settings.GroupedPreferencesFragment;
import com.termux.app.terminal.StylingApp;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

@Keep
public class TermuxPreferencesFragment extends GroupedPreferencesFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TermuxPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_preferences, rootKey);
        Preference styling = findPreference("termux_styling");
        if (styling != null) {
            styling.setOnPreferenceClickListener(preference -> {
                try {
                    if (StylingApp.isAvailable(context.getPackageManager())) {
                        startActivity(StylingApp.createIntent());
                    } else {
                        showStylingInstallPrompt();
                    }
                } catch (ActivityNotFoundException | IllegalArgumentException e) {
                    showStylingInstallPrompt();
                }
                return true;
            });
        }
    }

    private void showStylingInstallPrompt() {
        DialogUtils.builder(requireContext(), R.drawable.ui_ic_palette, false)
            .setTitle(R.string.termux_update_app_styling)
            .setMessage(R.string.termux_styling_unavailable_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

}

class TermuxPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TermuxPreferencesDataStore mInstance;

    private TermuxPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TermuxPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TermuxPreferencesDataStore(context);
        }
        return mInstance;
    }

}
