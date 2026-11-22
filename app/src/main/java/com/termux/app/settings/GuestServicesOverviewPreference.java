package com.termux.app.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.termux.R;

/** Keeps the refresh action beside the guest status without an extra list row. */
@Keep
public final class GuestServicesOverviewPreference extends Preference {
    private Runnable refresh;
    private boolean busy;

    public GuestServicesOverviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setRefreshAction(Runnable action) {
        refresh = action;
        notifyChanged();
    }

    public void setBusy(boolean value) {
        if (busy == value) return;
        busy = value;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View button = holder.findViewById(R.id.guest_services_refresh);
        button.setEnabled(!busy && refresh != null);
        button.setOnClickListener(view -> {
            if (refresh != null) refresh.run();
        });
        holder.findViewById(R.id.guest_services_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        button.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
    }
}
