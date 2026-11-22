package com.termux.app.settings;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

/** Groups adjacent standard rows while leaving specialized preference layouts in charge of their own styling. */
public abstract class GroupedPreferencesFragment extends PreferenceFragmentCompat {
    @Override
    protected RecyclerView.Adapter<?> onCreateAdapter(@NonNull PreferenceScreen screen) {
        return new PreferenceGroupAdapter(screen) {
            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                Preference preference = getItem(position);
                if (preference == null || preference.getLayoutResource() != R.layout.ui_preference_row) return;
                boolean first = !isSameGroup(preference, getItem(position - 1));
                boolean last = !isSameGroup(preference, getItem(position + 1));
                SettingsNavigationPreference.bindGroup(holder, preference.getContext(), first, last);
                boolean hasDestination = preference.getFragment() != null || preference instanceof DialogPreference
                    || preference.getIntent() != null || preference.getOnPreferenceClickListener() != null;
                holder.findViewById(R.id.settings_row_chevron).setVisibility(hasDestination ? View.VISIBLE : View.GONE);
            }
        };
    }

    private static boolean isSameGroup(Preference preference, Preference adjacent) {
        return adjacent != null && adjacent.getLayoutResource() == R.layout.ui_preference_row
            && preference.getParent() == adjacent.getParent();
    }
}
