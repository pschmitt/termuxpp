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
import android.text.TextUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.termux.app.settings.PropertyInputSpec;
import com.termux.app.terminal.TerminalAppearance;
import com.termux.app.settings.AppAccent;
import com.termux.app.settings.TermuxPropertiesEditor;
import com.google.android.material.color.MaterialColors;
import com.termux.R;

import java.io.IOException;
import java.util.Properties;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeyAnimation;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import dev.pschmitt.termux.shared.theme.ThemeUtils;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;

/** Interactive samples for visual settings and gesture actions. */
final class SettingsPreview extends FrameLayout {
    private final Properties values;
    private final String key;
    private final float density;
    private final android.text.TextPaint paint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private ExtraKeysView keyboard;
    private ValueAnimator actionAnimator;
    private float actionProgress = 1;

    static boolean supports(String key) {
        return key.equals("terminal-cursor-style") || key.equals("terminal-cursor-blink-rate")
            || key.equals("terminal-toast-position") || key.equals("gesture-feedback-position") || key.equals("terminal-margin-horizontal")
            || key.equals("terminal-margin-vertical") || key.equals("extra-keys-style")
            || key.equals("extra-keys-text-all-caps") || key.equals("extra-keys-button-active-text-color")
            || key.equals("extra-keys-button-active-background-color") || key.equals("extra-keys-button-corner-radius")
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_THEME)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_ANIMATION)
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
        if (key.endsWith("-action")) setOnClickListener(view -> replayAction());
    }

    void setValue(String value) {
        if (!key.equals(AppAccent.KEY) && PropertyInputSpec.validationError(key, value) != null) return;
        if (value == null || value.isEmpty()) values.remove(key);
        else values.setProperty(key, value);
        if (isKeyboardPreview()) renderKeys();
        if (key.endsWith("-action")) replayAction();
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
        keyboard.setAppearance(values.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_THEME),
            ExtraKeysColorParser.parseColor(values.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR)),
            fg, bg, radius);
        keyboard.setKeyTypeface(TerminalAppearance.loadExtraKeysFont(getContext(),
            values.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT)));
        keyboard.setKeyAnimation(ExtraKeyAnimation.parseOrDefault(values.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS_ANIMATION)));
        keyboard.setButtonTextAllCaps(!"false".equalsIgnoreCase(value("extra-keys-text-all-caps", "true")));
        float scale;
        try { scale = Float.parseFloat(value("terminal-toolbar-height", "1")); }
        catch (NumberFormatException e) { scale = 1; }
        if (Float.isNaN(scale) || scale < .4f || scale > 3) scale = 1;
        boolean interactive = key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_ANIMATION)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_THEME)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR);
        String sample = "[[\"ESC\",\"CTRL\",\"ALT\",\"LEFT\",\"DOWN\",\"UP\",\"RIGHT\"]]";
        try {
            String style = value("extra-keys-style", "default");
            ExtraKeysInfo info;
            try {
                info = new ExtraKeysInfo(interactive ? value("extra-keys", sample) : sample,
                    style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (org.json.JSONException invalidLayout) {
                info = new ExtraKeysInfo(sample, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            }
            int height = Math.round(dp(Math.min(interactive ? 100 : 56, 37.5f * scale * info.getMatrix().length)));
            addView(keyboard, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.CENTER_VERTICAL));
            keyboard.reload(info, height);
            if (interactive) return;
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

    private void replayAction() {
        setContentDescription(actionTitle(TerminalGestureAction.parse(value(key, defaultAction()))) + ". "
            + getContext().getString(R.string.gesture_feedback_preview_replay));
        if (actionAnimator != null) actionAnimator.cancel();
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            actionProgress = 1;
            invalidate();
            return;
        }
        actionAnimator = ValueAnimator.ofFloat(0, 1);
        actionAnimator.setDuration(240);
        actionAnimator.setInterpolator(new DecelerateInterpolator());
        actionAnimator.addUpdateListener(animation -> {
            actionProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        actionAnimator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (actionAnimator != null) actionAnimator.cancel();
        actionProgress = 1;
        super.onDetachedFromWindow();
    }

    private void drawAction(Canvas canvas) {
        String action = value(key, defaultAction());
        TerminalGestureAction parsed = TerminalGestureAction.parse(action);
        if (parsed != null) action = parsed.getValue();
        String title = actionTitle(parsed);
        float left = dp(12), right = getWidth() - left, top = dp(45), bottom = getHeight() - dp(10);
        int foreground = ContextCompat.getColor(getContext(), R.color.ui_on_surface);
        int surface = MaterialColors.getColor(getContext(), R.attr.termuxUiShelf, Color.rgb(42, 35, 58));
        int accent = MaterialColors.getColor(getContext(), R.attr.termuxUiPrimary, Color.rgb(216, 180, 254));

        previewText(canvas, title, left, dp(27), right - left, 14, Color.rgb(230, 220, 247), Typeface.DEFAULT_BOLD);
        paint.setColor(Color.rgb(23, 22, 32));
        canvas.drawRoundRect(left, top, right, bottom, dp(9), dp(9), paint);
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        float lineX = left + dp(10);
        boolean fontUp = action.equals("font-size-up");
        boolean fontDown = action.equals("font-size-down");
        float fontSize = fontUp ? 13 + 3 * actionProgress : fontDown ? 13 - 3 * actionProgress : 12;
        String prompt = action.equals("run-command") ? "~ ❯ ./script" : action.equals("send-keys") ? "~ ❯ ↑"
            : action.equals("next-session") || action.equals("previous-session") ? "~/projects ❯ ▌" : "~ ❯ ▌";
        if (action.equals("modifier")) prompt += "   Ctrl";
        previewText(canvas, prompt, lineX, top + dp(23), right - lineX, fontSize, Color.rgb(193, 183, 212), Typeface.MONOSPACE);
        if (action.equals("next-session") || action.equals("previous-session") || action.equals("new-session")) {
            previewText(canvas, action.equals("new-session") ? "New terminal" : "Session 2", lineX, top + dp(49),
                right - lineX, 11, accent, Typeface.DEFAULT_BOLD);
        } else if (action.equals("open-left-sidebar") || action.equals("open-right-sidebar")) {
            boolean end = action.equals("open-right-sidebar");
            float panelWidth = (right - left) * .51f;
            float slide = panelWidth * (1 - actionProgress);
            float x = end ? right - panelWidth + slide : left - slide;
            drawActionPanel(canvas, x, top, x + panelWidth, bottom, surface, foreground, accent,
                getContext().getString(end ? R.string.ui_tmux_switcher_title : R.string.ui_drawer_title),
                end ? "0  shell" : "1  local", end ? "1  editor" : "2  remote");
        } else if (action.equals("open-tmux-switcher") || action.equals("open-session-switcher") || action.equals("open-menu")) {
            boolean tmux = action.equals("open-tmux-switcher");
            boolean menu = action.equals("open-menu");
            float inset = (right - left) * .16f;
            float y = top + dp(8 + 20 * (1 - actionProgress));
            if (menu) y += dp(12);
            drawActionPanel(canvas, left + inset, y, right - inset, bottom,
                surface, foreground, accent, tmux ? getContext().getString(R.string.ui_tmux_switcher_title)
                    : menu ? getContext().getString(R.string.ui_menu_title) : getContext().getString(R.string.ui_drawer_title),
                tmux ? "0  shell" : menu ? "Copy text" : "1  local", tmux ? "1  editor" : menu ? "Share transcript" : "2  remote");
        } else if (action.equals("show-keyboard") || action.equals("toggle-keyboard") || action.equals("toggle-text-input")) {
            float y = bottom - dp(54 * actionProgress);
            paint.setColor(surface);
            canvas.drawRoundRect(left, y, right, bottom + dp(8), dp(8), dp(8), paint);
            String keys = action.equals("toggle-text-input") ? "Enter text…" : "Q  W  E  R  T  Y  U  I  O";
            previewText(canvas, keys, lineX, y + dp(24), right - lineX, 11, foreground, Typeface.MONOSPACE);
            if (!action.equals("toggle-text-input"))
                previewText(canvas, "  A  S  D  F  G  H  J  K", lineX, y + dp(43), right - lineX, 11, accent, Typeface.MONOSPACE);
        }
        canvas.restore();
    }

    private String actionTitle(TerminalGestureAction action) {
        return action == null ? getContext().getString(R.string.settings_preview_modifier) : action.getLabel();
    }

    private void drawActionPanel(Canvas canvas, float left, float top, float right, float bottom,
                                 int surface, int foreground, int accent, String title, String first, String second) {
        paint.setColor(surface);
        canvas.drawRoundRect(left, top, right, bottom, dp(7), dp(7), paint);
        float textWidth = right - left - dp(18);
        previewText(canvas, title, left + dp(9), top + dp(18), textWidth, 11, foreground, Typeface.DEFAULT_BOLD);
        paint.setColor(ColorUtils.setAlphaComponent(accent, 52));
        canvas.drawRoundRect(left + dp(6), top + dp(24), right - dp(6), top + dp(44), dp(6), dp(6), paint);
        previewText(canvas, first, left + dp(9), top + dp(38), textWidth, 10, foreground, Typeface.MONOSPACE);
        previewText(canvas, second, left + dp(9), top + dp(59), textWidth, 10, foreground, Typeface.MONOSPACE);
    }

    private void previewText(Canvas canvas, String text, float x, float baseline, float width,
                             float sp, int color, Typeface typeface) {
        paint.setColor(color);
        paint.setTextSize(dp(sp));
        paint.setTypeface(typeface);
        canvas.drawText(TextUtils.ellipsize(text, paint, Math.max(0, width), TextUtils.TruncateAt.END).toString(),
            x, baseline, paint);
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
