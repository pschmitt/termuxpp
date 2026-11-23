package com.termux.app.fragments.settings.termux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.app.settings.PropertyInputSpec;
import com.termux.app.settings.TermuxPropertiesEditor;
import com.google.android.material.color.MaterialColors;
import com.termux.R;

import java.io.IOException;
import java.util.Properties;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import dev.pschmitt.termux.shared.theme.ThemeUtils;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/** Small, non-interactive samples for properties whose effect is visual. */
final class SettingsPreview extends FrameLayout {
    private final Properties values;
    private final String key;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ExtraKeysView keyboard;

    static boolean supports(String key) {
        return key.equals("terminal-cursor-style") || key.equals("terminal-cursor-blink-rate")
            || key.equals("terminal-toast-position") || key.equals("terminal-margin-horizontal")
            || key.equals("terminal-margin-vertical") || key.equals("extra-keys-style")
            || key.equals("extra-keys-text-all-caps") || key.equals("extra-keys-button-active-text-color")
            || key.equals("extra-keys-button-active-background-color") || key.equals("extra-keys-button-corner-radius");
    }

    SettingsPreview(Context context, String key) {
        super(context);
        this.key = key;
        density = getResources().getDisplayMetrics().density;
        Properties loaded;
        try { loaded = new TermuxPropertiesEditor().read(); }
        catch (IOException | IllegalArgumentException e) { loaded = new Properties(); }
        values = loaded;
        setWillNotDraw(false);
        setBackgroundColor(Color.rgb(12, 14, 21));
        setContentDescription(context.getString(R.string.settings_visual_preview));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setValue(String value) {
        if (PropertyInputSpec.validationError(key, value) != null) return;
        if (value == null || value.isEmpty()) values.remove(key);
        else values.setProperty(key, value);
        if (key.startsWith("extra-keys")) renderKeys();
        invalidate();
    }

    private String value(String name, String fallback) {
        String result = values.getProperty(name);
        return result == null || result.isEmpty() ? fallback : result;
    }

    private float dp(float value) { return value * density; }

    private void renderKeys() {
        removeAllViews();
        keyboard = new ExtraKeysView(getContext(), null);
        Integer fg = ExtraKeysColorParser.parseColor(values.getProperty("extra-keys-button-active-text-color"));
        Integer bg = ExtraKeysColorParser.parseColor(values.getProperty("extra-keys-button-active-background-color"));
        Float radius = ExtraKeysColorParser.parseCornerRadiusDp(values.getProperty("extra-keys-button-corner-radius"));
        keyboard.setButtonActiveColorOverrides(fg, bg);
        keyboard.setButtonCornerRadiusDp(radius);
        keyboard.setButtonTextAllCaps(!"false".equalsIgnoreCase(value("extra-keys-text-all-caps", "true")));
        addView(keyboard, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, (int) dp(48)));
        try {
            keyboard.reload(new ExtraKeysInfo("[[\"ESC\",\"CTRL\",\"ALT\",\"LEFT\",\"DOWN\",\"UP\",\"RIGHT\"]]",
                value("extra-keys-style", "default"), ExtraKeysConstants.CONTROL_CHARS_ALIASES), dp(48));
            // Show a latched Ctrl and a pressed arrow, without invoking terminal actions.
            for (int i = 0; i < keyboard.getChildCount(); i++) {
                View child = keyboard.getChildAt(i);
                child.setOnTouchListener(null);
                child.setClickable(false);
                child.setLongClickable(false);
                if (i == 1 && child instanceof TextView) {
                    int active = fg != null ? fg : ThemeUtils.getSystemAttrColor(getContext(),
                        ExtraKeysView.ATTR_BUTTON_ACTIVE_TEXT_COLOR, ExtraKeysView.DEFAULT_BUTTON_ACTIVE_TEXT_COLOR);
                    ((TextView) child).setTextColor(active);
                    if (bg != null) child.setBackgroundColor(bg);
                }
                if (i == 4) {
                    int active = bg != null ? bg : ThemeUtils.getSystemAttrColor(getContext(),
                        ExtraKeysView.ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR, ExtraKeysView.DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR);
                    child.setBackgroundColor(active);
                }
            }
        } catch (org.json.JSONException ignored) { }
    }

    @Override protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (key.startsWith("extra-keys")) return;
        float w = getWidth(), h = getHeight();
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(dp(13));
        paint.setColor(Color.rgb(210, 215, 226));
        float mx = Math.min(w / 3, dp(parseInt(value("terminal-margin-horizontal", "3"), 3)));
        float my = Math.min(h / 3, dp(parseInt(value("terminal-margin-vertical", "0"), 0)));
        float x = dp(10) + mx, baseline = dp(26) + my;
        canvas.drawText("~ ❯ ls", x, baseline, paint);
        canvas.drawText("projects  notes", x, baseline + dp(21), paint);
        canvas.drawText("~ ❯", x, baseline + dp(42), paint);
        float cursorX = x + paint.measureText("~ ❯ ");
        float cursorY = baseline + dp(42);
        int blink = parseInt(value("terminal-cursor-blink-rate", "0"), 0);
        boolean animations = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
        if (blink == 0 || !animations || (SystemClock.uptimeMillis() / blink) % 2 == 0) {
            paint.setColor(Color.rgb(194, 166, 248));
            String style = value("terminal-cursor-style", "block");
            if (style.equals("underline")) canvas.drawRect(cursorX, cursorY, cursorX + dp(8), cursorY + dp(2), paint);
            else if (style.equals("bar")) canvas.drawRect(cursorX, cursorY - dp(12), cursorX + dp(2), cursorY + dp(2), paint);
            else canvas.drawRect(cursorX, cursorY - dp(12), cursorX + dp(8), cursorY + dp(2), paint);
        }
        if (blink > 0 && animations && isAttachedToWindow()) postInvalidateDelayed(Math.max(50, blink));
        if (key.equals("terminal-toast-position")) drawToast(canvas, w, h);
    }

    private void drawToast(Canvas canvas, float w, float h) {
        String position = value("terminal-toast-position", "top-center");
        float width = Math.min(dp(100), w - dp(12)), height = dp(27), inset = dp(6);
        float left = position.endsWith("left") ? inset : position.endsWith("right") ? w - width - inset : (w - width) / 2;
        float top = position.startsWith("top") ? inset : position.startsWith("bottom") ? h - height - inset : (h - height) / 2;
        paint.setColor(MaterialColors.getColor(this, R.attr.termuxUiPrimary));
        canvas.drawRoundRect(left, top, left + width, top + height, dp(13), dp(13), paint);
        paint.setColor(MaterialColors.getColor(this, R.attr.termuxUiOnPrimary));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(12));
        String text = getContext().getString(R.string.settings_preview_toast);
        canvas.drawText(text, left + (width - paint.measureText(text)) / 2, top + dp(18), paint);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }
}
