package com.termux.app.fragments.settings.termux;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.termux.app.settings.PropertyInputSpec;
import com.termux.app.settings.AppAccent;
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
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;

/** Small, non-interactive samples for properties whose effect is visual. */
final class SettingsPreview extends FrameLayout {
    private final Properties values;
    private final String key;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ExtraKeysView keyboard;

    static boolean supports(String key) {
        return key.equals("terminal-cursor-style") || key.equals("terminal-cursor-blink-rate")
            || key.equals("terminal-toast-position") || key.equals("gesture-feedback-position") || key.equals("terminal-margin-horizontal")
            || key.equals("terminal-margin-vertical") || key.equals("extra-keys-style")
            || key.equals("extra-keys-text-all-caps") || key.equals("extra-keys-button-active-text-color")
            || key.equals("extra-keys-button-active-background-color") || key.equals("extra-keys-button-corner-radius")
            || key.equals("terminal-toolbar-height") || key.equals("night-mode") || key.equals(AppAccent.KEY)
            || key.equals(TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT)
            || key.equals(TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE)
            || key.endsWith("-action");
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
        if (!key.equals(AppAccent.KEY) && PropertyInputSpec.validationError(key, value) != null) return;
        if (value == null || value.isEmpty()) values.remove(key);
        else values.setProperty(key, value);
        if (isKeyboardPreview()) renderKeys();
        invalidate();
    }

    private String value(String name, String fallback) {
        String result = values.getProperty(name);
        return result == null || result.isEmpty() ? fallback : result;
    }

    private float dp(float value) { return value * density; }

    private boolean isKeyboardPreview() {
        return key.equals("terminal-toolbar-height") || (key.startsWith("extra-keys") && !key.endsWith("-action"));
    }

    private void renderKeys() {
        removeAllViews();
        keyboard = new ExtraKeysView(getContext(), null);
        Integer fg = ExtraKeysColorParser.parseColor(values.getProperty("extra-keys-button-active-text-color"));
        Integer bg = ExtraKeysColorParser.parseColor(values.getProperty("extra-keys-button-active-background-color"));
        Float radius = ExtraKeysColorParser.parseCornerRadiusDp(values.getProperty("extra-keys-button-corner-radius"));
        keyboard.setButtonActiveColorOverrides(fg, bg);
        keyboard.setButtonCornerRadiusDp(radius);
        keyboard.setButtonTextAllCaps(!"false".equalsIgnoreCase(value("extra-keys-text-all-caps", "true")));
        float scale;
        try { scale = Float.parseFloat(value("terminal-toolbar-height", "1")); }
        catch (NumberFormatException e) { scale = 1; }
        if (Float.isNaN(scale) || scale < .4f || scale > 3) scale = 1;
        int height = Math.round(dp(37.5f * scale));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.CENTER_VERTICAL);
        addView(keyboard, params);
        try {
            keyboard.reload(new ExtraKeysInfo("[[\"ESC\",\"CTRL\",\"ALT\",\"LEFT\",\"DOWN\",\"UP\",\"RIGHT\"]]",
                value("extra-keys-style", "default"), ExtraKeysConstants.CONTROL_CHARS_ALIASES), height);
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
        if (isKeyboardPreview()) return;
        if (key.equals("night-mode") || key.equals(AppAccent.KEY)) { drawTheme(canvas); return; }
        if (key.equals(TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT)
            || key.equals(TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE)) {
            drawTmuxSidebar(canvas);
            return;
        }
        if (key.endsWith("-action")) { drawAction(canvas); return; }
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
        if (key.equals("terminal-toast-position") || key.equals("gesture-feedback-position")) drawToast(canvas, w, h);
    }

    private void drawTmuxSidebar(Canvas canvas) {
        String layout = value(TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT,
            TermuxPropertyConstants.TMUX_SIDEBAR_NESTED);
        boolean rounded = TermuxPropertyConstants.TMUX_SIDEBAR_ROUNDED.equalsIgnoreCase(value(
            TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE, TermuxPropertyConstants.DEFAULT_TMUX_SIDEBAR_STYLE));
        boolean nested = !TermuxPropertyConstants.TMUX_SIDEBAR_DRILL_DOWN.equalsIgnoreCase(layout)
            && !TermuxPropertyConstants.TMUX_SIDEBAR_MODES.equalsIgnoreCase(layout);
        boolean modes = TermuxPropertyConstants.TMUX_SIDEBAR_MODES.equalsIgnoreCase(layout);
        int foreground = ContextCompat.getColor(getContext(), R.color.ui_on_surface);
        int muted = ContextCompat.getColor(getContext(), R.color.ui_on_surface_variant);
        int accent = MaterialColors.getColor(getContext(), R.attr.termuxUiPrimary, foreground);
        int onAccent = MaterialColors.getColor(getContext(), R.attr.termuxUiOnPrimary, Color.BLACK);
        float right = getWidth() - dp(12);
        float left = dp(nested ? 36 : 18);
        float rowsTop = dp(57);
        canvas.drawColor(ContextCompat.getColor(getContext(), R.color.ui_surface));

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(12));
        paint.setColor(foreground);
        if (modes) {
            canvas.drawText(getContext().getString(R.string.ui_tmux_sidebar_sessions), dp(16), dp(24), paint);
            paint.setColor(accent);
            canvas.drawLine(dp(16), dp(30), dp(36), dp(30), paint);
            paint.setColor(foreground);
        } else {
            String heading = getContext().getString(nested ? R.string.ui_drawer_title : R.string.ui_tmux_sidebar_back);
            canvas.drawText(heading, dp(14), dp(24), paint);
        }
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(getContext().getString(R.string.ui_tmux_preview_terminal), dp(16), dp(47), paint);
        if (nested) {
            paint.setColor(ColorUtils.setAlphaComponent(foreground, 30));
            canvas.drawRect(left - dp(9), rowsTop, left - dp(8), dp(104), paint);
        }

        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(dp(11));
        paint.setColor(muted);
        canvas.drawText("0  editor", left + dp(8), rowsTop + dp(15), paint);
        float radius = dp(rounded ? 12 : 7);
        float top = rowsTop + dp(23);
        float bottom = rowsTop + dp(46);
        paint.setColor(rounded ? accent : ColorUtils.setAlphaComponent(foreground, 18));
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        if (!rounded) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(ColorUtils.setAlphaComponent(foreground, 34));
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setColor(rounded ? onAccent : foreground);
        canvas.drawText("1  tests", left + dp(8), top + dp(15), paint);
        paint.setColor(rounded ? onAccent : accent);
        canvas.drawCircle(right - dp(12), top + dp(12), dp(3), paint);
    }

    private void drawTheme(Canvas canvas) {
        Configuration config = new Configuration(getResources().getConfiguration());
        if (key.equals("night-mode")) {
            String mode = value(key, "system");
            if (mode.equals("true")) config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            else if (mode.equals("false")) config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        }
        Context base = getContext().createConfigurationContext(config);
        ContextThemeWrapper themed = new ContextThemeWrapper(base, R.style.Theme_TermuxApp_DayNight_NoActionBar);
        AppAccent.apply(themed, key.equals(AppAccent.KEY) ? value(key, "default") : AppAccent.get(getContext()));
        int surface = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorSurface, Color.BLACK);
        int foreground = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        int group = MaterialColors.getColor(themed, R.attr.termuxUiGroup, surface);
        int accent = MaterialColors.getColor(themed, R.attr.termuxUiPrimary, foreground);
        int onAccent = MaterialColors.getColor(themed, R.attr.termuxUiOnPrimary, surface);
        canvas.drawColor(surface);
        paint.setColor(group);
        canvas.drawRoundRect(dp(10), dp(12), getWidth() - dp(10), getHeight() - dp(12), dp(12), dp(12), paint);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(15));
        paint.setColor(foreground);
        canvas.drawText(getContext().getString(R.string.settings_theme_preview_title), dp(22), dp(39), paint);
        paint.setColor(accent);
        canvas.drawRoundRect(dp(22), dp(54), dp(128), dp(91), dp(18), dp(18), paint);
        paint.setColor(onAccent);
        paint.setTextSize(dp(12));
        canvas.drawText(getContext().getString(R.string.settings_theme_preview_button), dp(39), dp(77), paint);
        paint.setColor(accent);
        canvas.drawCircle(getWidth() - dp(42), dp(72), dp(10), paint);
    }

    private String defaultAction() {
        if (key.equals("terminal-single-tap-action")) return "show-keyboard";
        if (key.equals("extra-keys-swipe-right-action")) return "next-session";
        if (key.startsWith("volume-")) return "modifier";
        return TermuxPropertyConstants.defaultMultiFingerSwipeAction(key).getValue();
    }

    private void drawAction(Canvas canvas) {
        String action = value(key, defaultAction());
        float w = getWidth(), h = getHeight();
        paint.setColor(Color.rgb(45, 44, 62));
        canvas.drawRoundRect(dp(12), dp(12), w - dp(12), h - dp(12), dp(10), dp(10), paint);
        paint.setColor(Color.rgb(200, 180, 245));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(14));
        TerminalGestureAction parsed = TerminalGestureAction.parse(action);
        String title = parsed == null ? getContext().getString(R.string.settings_preview_modifier) : parsed.getLabel();
        canvas.drawText(title, dp(24), dp(39), paint);
        paint.setColor(Color.rgb(220, 220, 230));
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(dp(12));
        int description;
        switch (action) {
            case "show-keyboard": case "toggle-keyboard": description = R.string.settings_action_keyboard; break;
            case "toggle-text-input": description = R.string.settings_action_text_input; break;
            case "next-session": case "previous-session": description = R.string.settings_action_switch_session; break;
            case "new-session": description = R.string.settings_action_new_session; break;
            case "open-sidebar": case "open-left-sidebar": description = R.string.settings_action_sidebar; break;
            case "open-right-sidebar": description = R.string.settings_action_right_sidebar; break;
            case "open-session-switcher": description = R.string.settings_action_picker; break;
            case "open-tmux-switcher": description = R.string.settings_action_tmux_picker; break;
            case "open-menu": description = R.string.settings_action_menu; break;
            case "font-size-up": case "font-size-down": description = R.string.settings_action_font; break;
            case "run-command": description = R.string.settings_action_command; break;
            case "send-keys": description = R.string.settings_action_keys; break;
            case "modifier": description = R.string.settings_action_modifier; break;
            default: description = R.string.settings_action_none;
        }
        String text = getContext().getString(description);
        drawActionIcon(canvas, action, w - dp(90), dp(42));
        paint.setColor(Color.rgb(220, 220, 230));
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(dp(12));
        float maxWidth = Math.max(0, w - dp(125));
        android.text.TextPaint textPaint = new android.text.TextPaint(paint);
        @SuppressWarnings("deprecation")
        android.text.StaticLayout layout = new android.text.StaticLayout(text, textPaint,
            Math.max(1, (int) maxWidth), android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        canvas.save();
        canvas.translate(dp(24), dp(53));
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawActionIcon(Canvas canvas, String action, float x, float y) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.rgb(200, 180, 245));
        canvas.drawRoundRect(x, y, x + dp(62), y + dp(48), dp(5), dp(5), paint);
        paint.setStyle(Paint.Style.FILL);
        if (action.contains("keyboard") || action.equals("send-keys")) {
            for (int row = 0; row < 3; row++) for (int col = 0; col < 5; col++)
                canvas.drawRoundRect(x + dp(6 + col * 10), y + dp(8 + row * 10),
                    x + dp(13 + col * 10), y + dp(14 + row * 10), dp(1), dp(1), paint);
        } else if (action.equals("open-right-sidebar")) {
            canvas.drawRect(x + dp(40), y, x + dp(62), y + dp(48), paint);
        } else if (action.equals("open-sidebar") || action.equals("open-left-sidebar")) {
            canvas.drawRect(x, y, x + dp(22), y + dp(48), paint);
        } else if (action.equals("open-menu") || action.equals("open-session-switcher")
            || action.equals("open-tmux-switcher")) {
            for (int row = 0; row < 3; row++) canvas.drawRoundRect(x + dp(9), y + dp(9 + row * 11),
                x + dp(53), y + dp(14 + row * 11), dp(2), dp(2), paint);
        } else {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(22));
            String glyph = action.equals("new-session") ? "+" : action.equals("previous-session") ? "‹"
                : action.equals("next-session") ? "›" : action.equals("font-size-up") ? "A+"
                : action.equals("font-size-down") ? "A−" : action.equals("none") ? "—"
                : action.equals("modifier") ? "Ctrl" : action.equals("toggle-text-input") ? "I"
                : action.equals("run-command") ? ">_" : "·";
            paint.setTextSize(dp(glyph.length() > 2 ? 13 : 22));
            canvas.drawText(glyph, x + (dp(62) - paint.measureText(glyph)) / 2, y + dp(32), paint);
        }
    }

    private void drawToast(Canvas canvas, float w, float h) {
        boolean gesture = key.equals("gesture-feedback-position");
        String position = value(key, gesture ? "bottom-center" : "top-center");
        float width = Math.min(dp(100), w - dp(12)), height = dp(27), inset = dp(6);
        float left = position.endsWith("left") ? inset : position.endsWith("right") ? w - width - inset : (w - width) / 2;
        float top = position.startsWith("top") ? inset : position.startsWith("bottom") ? h - height - inset : (h - height) / 2;
        paint.setColor(MaterialColors.getColor(this, R.attr.termuxUiPrimary));
        canvas.drawRoundRect(left, top, left + width, top + height, dp(13), dp(13), paint);
        paint.setColor(MaterialColors.getColor(this, R.attr.termuxUiOnPrimary));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(12));
        String text = gesture ? "✓  " + getContext().getString(R.string.gesture_feedback_preview_label)
            : getContext().getString(R.string.settings_preview_toast);
        canvas.drawText(text, left + (width - paint.measureText(text)) / 2, top + dp(18), paint);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }
}
