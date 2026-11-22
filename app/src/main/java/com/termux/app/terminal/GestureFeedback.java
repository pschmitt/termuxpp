package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.termux.R;

/** An action label drawn above the terminal keys without intercepting touch. */
final class GestureFeedback {
    private View anchor;
    private Pill pill;
    private ValueAnimator running;
    private boolean preview;
    private final Runnable expire = this::clear;

    void preview(View terminal, @Nullable String label, boolean armed) {
        if (label == null) {
            if (preview) clear();
            return;
        }
        if (preview && terminal == anchor && pill != null && label.equals(pill.label) && armed == pill.armed) return;
        show(terminal, label, armed, true);
    }

    void confirm(View terminal, String label) {
        if (!show(terminal, "✓  " + label, true, false)) return;
        if (Settings.Global.getFloat(terminal.getContext().getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f) {
            terminal.postDelayed(expire, 600);
            return;
        }
        Pill shown = pill;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        running = animator;
        animator.setDuration(700);
        animator.addUpdateListener(animation -> {
            float time = animation.getAnimatedFraction();
            float opacity = Math.min(1f, Math.min(time / .13f, (1f - time) / .24f));
            shown.setAlpha(Math.round(255 * opacity));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (running == animation) clear();
            }
        });
        animator.start();
    }

    private boolean show(View terminal, String text, boolean armed, boolean isPreview) {
        clear();
        if (!terminal.isAttachedToWindow() || terminal.getWidth() == 0 || terminal.getHeight() == 0) return false;
        anchor = terminal;
        preview = isPreview;
        pill = new Pill(terminal, text, armed);
        terminal.getOverlay().add(pill);
        return true;
    }

    private void clear() {
        ValueAnimator previous = running;
        running = null;
        if (previous != null) previous.cancel();
        if (anchor != null) {
            anchor.removeCallbacks(expire);
            if (pill != null) anchor.getOverlay().remove(pill);
        }
        anchor = null;
        pill = null;
        preview = false;
    }

    private static final class Pill extends Drawable {
        private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final String visibleLabel;
        private final boolean armed;
        private final float radius;

        Pill(View view, String label, boolean armed) {
            this.label = label;
            this.armed = armed;
            float density = view.getResources().getDisplayMetrics().density;
            surface.setColor(MaterialColors.getColor(view, armed ? R.attr.termuxUiPrimary : R.attr.termuxUiContainer));
            text.setColor(MaterialColors.getColor(view, armed ? R.attr.termuxUiOnPrimary : R.attr.termuxUiOnContainer));
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                view.getResources().getDisplayMetrics()));
            text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            float padding = 12 * density;
            visibleLabel = TextUtils.ellipsize(label, text, Math.max(0, view.getWidth() - 4 * padding),
                TextUtils.TruncateAt.END).toString();
            int width = Math.round(text.measureText(visibleLabel) + 2 * padding);
            int height = Math.round(Math.max(32 * density, text.getFontSpacing() + 12 * density));
            int left = (view.getWidth() - width) / 2;
            int bottom = Math.max(height, view.getHeight() - Math.round(10 * density));
            radius = height / 2f;
            setBounds(left, bottom - height, left + width, bottom);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, radius, radius, surface);
            float baseline = bounds.exactCenterY() - (text.ascent() + text.descent()) / 2f;
            canvas.drawText(visibleLabel, bounds.exactCenterX() - text.measureText(visibleLabel) / 2f, baseline, text);
        }

        @Override public void setAlpha(int alpha) {
            surface.setAlpha(alpha);
            text.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
