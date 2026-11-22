package com.termux.app.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.color.MaterialColors;
import com.termux.R;

/** A navigation row that shares its outer corners with the visible rows in its group. */
public final class SettingsNavigationPreference extends Preference {
    private final boolean showSummary;

    public SettingsNavigationPreference(Context context) {
        this(context, null);
    }

    public SettingsNavigationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        showSummary = attrs == null;
        setLayoutResource(R.layout.ui_settings_navigation_row);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        PreferenceGroup group = getParent();
        boolean first = true;
        boolean last = true;
        if (group != null) {
            boolean found = false;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference sibling = group.getPreference(i);
                if (sibling == this) found = true;
                else if (sibling.isVisible()) {
                    if (found) last = false;
                    else first = false;
                }
            }
        }
        bindGroup(holder, getContext(), first, last);
        if (!showSummary) holder.findViewById(android.R.id.summary).setVisibility(View.GONE);
        holder.findViewById(R.id.settings_row_chevron).setVisibility(isSelectable() ? View.VISIBLE : View.GONE);
    }

    static void bindGroup(PreferenceViewHolder holder, Context context, boolean first, boolean last) {
        float radius = 20 * context.getResources().getDisplayMetrics().density;
        float top = first ? radius : 0;
        float bottom = last ? radius : 0;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(MaterialColors.getColor(context, R.attr.termuxUiGroup,
            ContextCompat.getColor(context, R.color.ui_settings_group)));
        surface.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
        ColorStateList ripple = ColorStateList.valueOf(MaterialColors.getColor(context, R.attr.termuxUiRipple,
            ContextCompat.getColor(context, R.color.ui_ripple)));
        holder.itemView.setBackground(new RippleDrawable(ripple, surface, null));
        holder.findViewById(R.id.settings_row_divider).setVisibility(last ? View.GONE : View.VISIBLE);
    }
}
