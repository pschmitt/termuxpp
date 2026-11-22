package com.termux.app.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.color.MaterialColors;
import com.termux.R;

/** A switch row with a prominent terminal icon and a compact On or Off status. */
public final class AdbShellStatusPreference extends SwitchPreferenceCompat {
    public AdbShellStatusPreference(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
        TextView status = (TextView) holder.findViewById(android.R.id.summary);
        if (icon == null || status == null) return;

        float density = getContext().getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams iconSize = icon.getLayoutParams();
        iconSize.width = iconSize.height = Math.round(40 * density);
        icon.setLayoutParams(iconSize);
        int iconPadding = Math.round(8 * density);
        icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        icon.setBackgroundResource(R.drawable.ui_icon_container);
        int foreground = MaterialColors.getColor(holder.itemView, R.attr.termuxUiOnContainer);
        icon.setImageTintList(ColorStateList.valueOf(foreground));

        ViewGroup.LayoutParams statusSize = status.getLayoutParams();
        statusSize.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        status.setLayoutParams(statusSize);
        status.setBackgroundResource(R.drawable.ui_settings_status_badge);
        status.setTextColor(foreground);
        int horizontalPadding = Math.round(8 * density);
        status.setPadding(horizontalPadding, 0, horizontalPadding, 0);
    }
}
