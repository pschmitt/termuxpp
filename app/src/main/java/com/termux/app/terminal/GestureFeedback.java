package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.termux.R;

/** An action label drawn above the terminal keys without intercepting touch. */
public final class GestureFeedback {
    private View anchor;
    private Pill pill;
    private Effect effect;
    private ValueAnimator running;
    private boolean preview;
    private final Runnable expire = this::clear;

    void preview(View terminal, @Nullable String label, boolean armed, int gravity) {
        if (label == null) {
            if (preview) clear();
            return;
        }
        if (preview && terminal == anchor && pill != null && label.equals(pill.label) && armed == pill.armed && gravity == pill.gravity) return;
        show(terminal, label, armed, true, gravity);
    }

    public void confirm(View terminal, String label, String style, float x, float y) {
        confirm(terminal, label, style, x, y, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    }

    public void confirm(View terminal, String label, String style, float x, float y, int gravity) {
        if (!show(terminal, "✓  " + label, true, false, gravity)) return;
        if (Settings.Global.getFloat(terminal.getContext().getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f) {
            terminal.postDelayed(expire, 600);
            return;
        }
        effect = new Effect(terminal, style, x, y);
        terminal.getOverlay().add(effect);
        // Keep the confirmation label above the effect.
        terminal.getOverlay().remove(pill);
        terminal.getOverlay().add(pill);
        Pill shown = pill;
        Effect shownEffect = effect;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        running = animator;
        animator.setDuration(700);
        animator.addUpdateListener(animation -> {
            float time = animation.getAnimatedFraction();
            float opacity = Math.min(1f, Math.min(time / .13f, (1f - time) / .24f));
            shown.setAlpha(Math.round(255 * opacity));
            shownEffect.setProgress(time);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (running == animation) clear();
            }
        });
        animator.start();
    }

    private boolean show(View terminal, String text, boolean armed, boolean isPreview, int gravity) {
        clear();
        if (!terminal.isAttachedToWindow() || terminal.getWidth() == 0 || terminal.getHeight() == 0) return false;
        anchor = terminal;
        preview = isPreview;
        pill = new Pill(terminal, text, armed, gravity);
        terminal.getOverlay().add(pill);
        return true;
    }

    public void clear() {
        ValueAnimator previous = running;
        running = null;
        if (previous != null) previous.cancel();
        if (anchor != null) {
            anchor.removeCallbacks(expire);
            if (pill != null) anchor.getOverlay().remove(pill);
            if (effect != null) anchor.getOverlay().remove(effect);
        }
        anchor = null;
        pill = null;
        effect = null;
        preview = false;
    }

    /** Transient overlay; it never handles touch or modifies terminal content. */
    private static final class Effect extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String style;
        private final float x, y, density;
        private final int color;
        private final Path[] brackets = new Path[4];
        private float progress;

        Effect(View view, String style, float x, float y) {
            this.style = style == null ? "soft-flash" : style.toLowerCase(java.util.Locale.ROOT);
            this.x = Float.isNaN(x) ? view.getWidth() / 2f : x;
            this.y = Float.isNaN(y) ? view.getHeight() / 2f : y;
            density = view.getResources().getDisplayMetrics().density;
            color = MaterialColors.getColor(view, R.attr.termuxUiPrimary);
            setBounds(0, 0, view.getWidth(), view.getHeight());
            float inset = 10 * density, len = 24 * density, radius = 10 * density;
            int index = 0;
            for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) {
                float cx = sx == 1 ? inset : view.getWidth() - inset;
                float cy = sy == 1 ? inset : view.getHeight() - inset;
                Path bracket = new Path();
                bracket.moveTo(cx + sx * len, cy);
                bracket.lineTo(cx + sx * radius, cy);
                bracket.quadTo(cx, cy, cx, cy + sy * radius);
                bracket.lineTo(cx, cy + sy * len);
                brackets[index++] = bracket;
            }
        }

        void setProgress(float progress) {
            this.progress = progress;
            invalidateSelf();
        }

        @Override public void draw(@NonNull Canvas canvas) {
            float w = getBounds().width(), h = getBounds().height();
            float fade = 1f - progress;
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            switch (style) {
                case "touch-ripples":
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * density);
                    for (int i = 0; i < 2; i++) {
                        float t = Math.max(0f, Math.min(1f, (progress - i * .13f) / .87f));
                        if (progress < i * .13f) continue;
                        paint.setAlpha(Math.round(210 * (1f - t)));
                        canvas.drawCircle(x, y, (10 * density + t * Math.max(w, h)), paint);
                    }
                    break;
                case "edge-glow":
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(4 * density);
                    paint.setShadowLayer(18 * density, 0, 0, color);
                    paint.setAlpha(Math.round(210 * fade));
                    float edgeRadius = Math.min(20 * density, Math.min(w, h) / 2f);
                    canvas.drawRoundRect(2 * density, 2 * density, w - 2 * density, h - 2 * density,
                        edgeRadius, edgeRadius, paint);
                    break;
                case "corner-brackets":
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * density);
                    paint.setAlpha(Math.round(220 * fade));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    for (Path bracket : brackets) canvas.drawPath(bracket, paint);
                    break;
                case "light-sweep":
                    float center = -w + progress * w * 3;
                    paint.setShader(new LinearGradient(center - w * .4f, 0, center + w * .4f, h,
                        new int[]{android.graphics.Color.TRANSPARENT, color, android.graphics.Color.TRANSPARENT},
                        null, Shader.TileMode.CLAMP));
                    paint.setAlpha(Math.round(120 * fade));
                    canvas.drawRect(0, 0, w, h, paint);
                    break;
                case "touch-bloom":
                    float radius = Math.max(w, h) * (.35f + .45f * progress);
                    paint.setShader(new RadialGradient(x, y, radius,
                        new int[]{color, color, android.graphics.Color.TRANSPARENT},
                        new float[]{0f, .15f, 1f}, Shader.TileMode.CLAMP));
                    paint.setAlpha(Math.round(150 * fade));
                    canvas.drawRect(0, 0, w, h, paint);
                    break;
                default:
                    paint.setAlpha(Math.round(90 * fade));
                    canvas.drawRect(0, 0, w, h, paint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static Rect positionBounds(int viewportWidth, int viewportHeight, int width, int height, int inset, int gravity) {
        int horizontalInset = Math.min(inset, Math.max(0, (viewportWidth - width) / 2));
        int verticalInset = Math.min(inset, Math.max(0, (viewportHeight - height) / 2));
        int horizontal = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        int vertical = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        int left = horizontal == Gravity.LEFT ? horizontalInset : horizontal == Gravity.RIGHT
            ? viewportWidth - width - horizontalInset : (viewportWidth - width) / 2;
        int top = vertical == Gravity.TOP ? verticalInset : vertical == Gravity.BOTTOM
            ? viewportHeight - height - verticalInset : (viewportHeight - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    private static final class Pill extends Drawable {
        private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final String visibleLabel;
        private final boolean armed;
        private final int gravity;
        private final float radius;

        Pill(View view, String label, boolean armed, int gravity) {
            this.label = label;
            this.armed = armed;
            this.gravity = gravity;
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
            int inset = Math.round(10 * density);
            Rect bounds = positionBounds(view.getWidth(), view.getHeight(), width, height, inset, gravity);
            radius = height / 2f;
            setBounds(bounds);
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
