package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import com.termux.R;

/** A brief terminal outline that leaves the terminal content and touch handling alone. */
final class GestureFeedback {
    private ValueAnimator running;

    void pulse(View terminal) {
        if (running != null) running.cancel();
        if (!terminal.isAttachedToWindow() || terminal.getWidth() == 0 || terminal.getHeight() == 0
            || Settings.Global.getFloat(terminal.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f) return;

        float density = terminal.getResources().getDisplayMetrics().density;
        int inset = Math.round(3 * density);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(8 * density);
        outline.setStroke(Math.max(1, Math.round(2 * density)),
            MaterialColors.getColor(terminal, R.attr.termuxUiPrimary));
        outline.setBounds(inset, inset, terminal.getWidth() - inset, terminal.getHeight() - inset);
        outline.setAlpha(0);
        terminal.getOverlay().add(outline);

        ValueAnimator pulse = ValueAnimator.ofInt(0, 210, 0);
        pulse.setDuration(240);
        pulse.addUpdateListener(animation -> outline.setAlpha((int) animation.getAnimatedValue()));
        pulse.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                terminal.getOverlay().remove(outline);
                if (running == animation) running = null;
            }
        });
        running = pulse;
        pulse.start();
    }
}
