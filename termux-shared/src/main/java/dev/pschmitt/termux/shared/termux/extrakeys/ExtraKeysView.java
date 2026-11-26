package dev.pschmitt.termux.shared.termux.extrakeys;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import dev.pschmitt.termux.shared.R;
import dev.pschmitt.termux.shared.termux.terminal.io.TerminalExtraKeys;
import dev.pschmitt.termux.shared.theme.ThemeUtils;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/**
 * A {@link View} showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboards.
 *
 * To use it, add following to a layout file and import it in your activity layout file or inflate
 * it with a {@link androidx.viewpager.widget.ViewPager}.:
 * {@code
 * <?xml version="1.0" encoding="utf-8"?>
 * <dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:id="@+id/extra_keys"
 *     style="?android:attr/buttonBarStyle"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     android:layout_alignParentBottom="true"
 *     android:orientation="horizontal" />
 * }
 *
 * Then in your activity, get its reference by a call to {@link android.app.Activity#findViewById(int)}
 * or {@link LayoutInflater#inflate(int, ViewGroup)} if using {@link androidx.viewpager.widget.ViewPager}.
 * Then call {@link #setExtraKeysViewClient(IExtraKeysView)} and pass it the implementation of
 * {@link IExtraKeysView} so that you can receive callbacks. You can also override other values set
 * in {@link ExtraKeysView#ExtraKeysView(Context, AttributeSet)} by calling the respective functions.
 * If you extend {@link ExtraKeysView}, you can also set them in the constructor, but do call super().
 *
 * After this you will have to make a call to {@link ExtraKeysView#reload(ExtraKeysInfo, float) and pass
 * it the {@link ExtraKeysInfo} to load and display the extra keys. Read its class javadocs for more
 * info on how to create it.
 *
 * Termux app defines the view in res/layout/view_terminal_toolbar_extra_keys and
 * inflates it in TerminalToolbarViewPager.instantiateItem() and sets the {@link ExtraKeysView} client
 * and calls {@link ExtraKeysView#reload(ExtraKeysInfo).
 * The {@link ExtraKeysInfo} is created by TermuxAppSharedProperties.setExtraKeys().
 * Then its got and the view height is adjusted in TermuxActivity.setTerminalToolbarHeight().
 * The client used is TermuxTerminalExtraKeys, which extends
 * {@link TerminalExtraKeys } to handle Termux app specific logic and
 * leave the rest to the super class.
 */
public final class ExtraKeysView extends GridLayout {

    /** The client for the {@link ExtraKeysView}. */
    public interface IExtraKeysView {

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked. This is also called
         * for {@link #mRepetitiveKeys} and {@link ExtraKeyButton} that have a popup set.
         * However, this is not called for {@link #mSpecialButtons}, whose state can instead be read
         * via a call to {@link #readSpecialButton(SpecialButton, boolean)}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         *                   The button may be a {@link ExtraKeyButton#KEY_MACRO} set which can be
         *                   checked with a call to {@link ExtraKeyButton#isMacro()}.
         * @param button The {@link MaterialButton} that was clicked.
         */
        void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button);

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked so that the client
         * can perform any hepatic feedback. This is only called in the {@link MaterialButton.OnClickListener}
         * and not for every repeat. Its also called for {@link #mSpecialButtons}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         * @param button The {@link MaterialButton} that was clicked.
         * @return Return {@code true} if the client handled the feedback, otherwise {@code false}
         * so that {@link ExtraKeysView#performExtraKeyButtonHapticFeedback(View, ExtraKeyButton, MaterialButton)}
         * can handle it depending on system settings.
         */
        boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button);

    }


    /** Defines the default value for {@link #mButtonTextColor} defined by current theme. */
    public static final int ATTR_BUTTON_TEXT_COLOR = R.attr.extraKeysButtonTextColor;
    /** Defines the default value for {@link #mButtonActiveTextColor} defined by current theme. */
    public static final int ATTR_BUTTON_ACTIVE_TEXT_COLOR = R.attr.extraKeysButtonActiveTextColor;
    /** Defines the default value for {@link #mButtonBackgroundColor} defined by current theme. */
    public static final int ATTR_BUTTON_BACKGROUND_COLOR = R.attr.extraKeysButtonBackgroundColor;
    /** Defines the default value for {@link #mButtonActiveBackgroundColor} defined by current theme. */
    public static final int ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR = R.attr.extraKeysButtonActiveBackgroundColor;

    /** Defines the default fallback value for {@link #mButtonTextColor} if {@link #ATTR_BUTTON_TEXT_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    /** Defines the default fallback value for {@link #mButtonActiveTextColor} if {@link #ATTR_BUTTON_ACTIVE_TEXT_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_ACTIVE_TEXT_COLOR = 0xFF80DEEA;
    /** Defines the default fallback value for {@link #mButtonBackgroundColor} if {@link #ATTR_BUTTON_BACKGROUND_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_BACKGROUND_COLOR = 0x00000000;
    /** Defines the default fallback value for {@link #mButtonActiveBackgroundColor} if {@link #ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR = 0xFF7F7F7F;



    /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int MIN_LONG_PRESS_DURATION = 200;
    /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int MAX_LONG_PRESS_DURATION = 3000;
    /** Defines the fallback duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int FALLBACK_LONG_PRESS_DURATION = 400;

    /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int MIN_LONG_PRESS__REPEAT_DELAY = 5;
    /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int MAX_LONG_PRESS__REPEAT_DELAY = 2000;
    /** Defines the default duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int DEFAULT_LONG_PRESS_REPEAT_DELAY = 80;



    /** The implementation of the {@link IExtraKeysView} that acts as a client for the {@link ExtraKeysView}. */
    protected IExtraKeysView mExtraKeysViewClient;

    /** The map for the {@link SpecialButton} and their {@link SpecialButtonState}. Defaults to
     * the one returned by {@link #getDefaultSpecialButtons(ExtraKeysView)}. */
    protected Map<SpecialButton, SpecialButtonState> mSpecialButtons;

    /** The keys for the {@link SpecialButton} added to {@link #mSpecialButtons}. This is automatically
     * set when the call to {@link #setSpecialButtons(Map)} is made. */
    protected Set<String> mSpecialButtonsKeys;


    /**
     * The list of keys for which auto repeat of key should be triggered if its extra keys button
     * is long pressed. This is done by calling {@link IExtraKeysView#onExtraKeyButtonClick(View, ExtraKeyButton, MaterialButton)}
     * every {@link #mLongPressRepeatDelay} seconds after {@link #mLongPressTimeout} has passed.
     * The default keys are defined by {@link ExtraKeysConstants#PRIMARY_REPETITIVE_KEYS}.
     */
    protected List<String> mRepetitiveKeys;


    /** The text color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_TEXT_COLOR}. */
    protected int mButtonTextColor;
    /** The text color for the extra keys button when its active.
     * Defaults to {@link #DEFAULT_BUTTON_ACTIVE_TEXT_COLOR}. */
    protected int mButtonActiveTextColor;
    /** The background color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_BACKGROUND_COLOR}. */
    protected int mButtonBackgroundColor;
    /** The background color for the extra keys button when its active. Defaults to
     * {@link #DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR}. */
    protected int mButtonActiveBackgroundColor;

    /** If an active (latched or locked) {@link SpecialButton} should also get the active background
     * color. Only enabled if {@code extra-keys-button-active-background-color} is set, so that the
     * theme behavior (colored text only) does not change for existing configurations. */
    protected boolean mFillActiveSpecialButtons = false;

    /** The corner radius in pixels of the extra keys button backgrounds, or {@code -1} to keep the
     * shape defined by the current theme. */
    protected int mButtonCornerRadiusPx = -1;

    /** Defines whether text for the extra keys button should be all capitalized automatically. */
    protected boolean mButtonTextAllCaps = true;


    /**
     * Defines the duration in milliseconds before a press turns into a long press. The default
     * duration used is the one returned by a call to {@link ViewConfiguration#getLongPressTimeout()}
     * which will return the system defined duration which can be changed in accessibility settings.
     * The duration must be in between {@link #MIN_LONG_PRESS_DURATION} and {@link #MAX_LONG_PRESS_DURATION},
     * otherwise {@link #FALLBACK_LONG_PRESS_DURATION} is used.
     */
    protected int mLongPressTimeout;

    /**
     * Defines the duration in milliseconds for the delay between trigger of each repeat of
     * {@link #mRepetitiveKeys}. The default value is defined by {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY}.
     * The duration must be in between {@link #MIN_LONG_PRESS__REPEAT_DELAY} and
     * {@link #MAX_LONG_PRESS__REPEAT_DELAY}, otherwise {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY} is used.
     */
    protected int mLongPressRepeatDelay;


    private final Drawable mDefaultRowBackground;
    private boolean mRoundedTheme;
    private int mRoundedNavigationTextColor;
    private int mRoundedNavigationBackgroundColor;
    private final Map<MaterialButton, ExtraKeyButton> mFlingKeys = new HashMap<>();
    private final Map<MaterialButton, String> mKeyHints = new HashMap<>();
    /** The key behind each button, for {@link #demoKeyPress(int, long)}. */
    private final Map<MaterialButton, ExtraKeyButton> mKeyInfos = new HashMap<>();
    private final TextPaint mHintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        mHintPaint.setTextSize(8 * getResources().getDisplayMetrics().scaledDensity);
        mHintPaint.setTextAlign(Paint.Align.RIGHT);
        for (Map.Entry<MaterialButton, String> entry : mKeyHints.entrySet()) {
            MaterialButton button = entry.getKey();
            if (button.getVisibility() != VISIBLE || button.getHeight() < 22 * density) continue;
            mHintPaint.setTypeface(button.getTypeface());
            mHintPaint.setColor(button.getCurrentTextColor());
            // Gboard's corner labels are about 70 % of the key label over the key color.
            mHintPaint.setAlpha(Math.round(mHintPaint.getAlpha() * 0.7f));
            canvas.save();
            canvas.translate(button.getLeft() + button.getTranslationX(), button.getTop() + button.getTranslationY());
            canvas.scale(button.getScaleX(), button.getScaleY(), button.getPivotX(), button.getPivotY());
            canvas.clipRect(0, 0, button.getWidth(), button.getHeight());
            CharSequence hint = TextUtils.ellipsize(entry.getValue(), mHintPaint,
                Math.max(0, button.getWidth() - 14 * density), TextUtils.TruncateAt.END);
            canvas.drawText(hint.toString(), button.getWidth() - 7 * density,
                3 * density - mHintPaint.ascent(), mHintPaint);
            canvas.restore();
        }
    }

    /** Whether a touch starts on a key that owns a fling in this physical direction. */
    public boolean hasFlingAt(float rawX, float rawY, boolean right) {
        int[] location = new int[2];
        for (Map.Entry<MaterialButton, ExtraKeyButton> entry : mFlingKeys.entrySet()) {
            MaterialButton button = entry.getKey();
            if (entry.getValue().getFling(right) == null || !button.isShown()) continue;
            button.getLocationOnScreen(location);
            if (rawX >= location[0] && rawX < location[0] + button.getWidth()
                && rawY >= location[1] && rawY < location[1] + button.getHeight()) return true;
        }
        return false;
    }

    private final class KeyFling {
        private final ExtraKeyButton key;
        private final float minDistance;
        private final float minVelocity;
        private float downX;
        private float downY;
        private boolean dragged;
        @Nullable private VelocityTracker velocity;

        KeyFling(ExtraKeyButton key) {
            this.key = key;
            float density = getResources().getDisplayMetrics().density;
            ViewConfiguration config = ViewConfiguration.get(getContext());
            minDistance = Math.max(18 * density, 2 * config.getScaledTouchSlop());
            minVelocity = Math.max(300 * density, config.getScaledMinimumFlingVelocity());
        }

        void start(MotionEvent event) {
            finish();
            dragged = false;
            downX = event.getRawX();
            downY = event.getRawY();
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
        }

        @Nullable ExtraKeyButton candidate(MotionEvent event) {
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (Math.abs(dx) >= minDistance && Math.abs(dx) > 1.5f * Math.abs(dy))
                return key.getFling(dx > 0);
            return dy >= minDistance && dy > 1.5f * Math.abs(dx) ? key.getDownFling() : null;
        }

        boolean move(MotionEvent event) {
            if (velocity == null) return dragged;
            velocity.addMovement(event);
            dragged |= candidate(event) != null;
            return dragged;
        }

        @Nullable ExtraKeyButton release(MotionEvent event) {
            if (velocity == null) return null;
            velocity.addMovement(event);
            velocity.computeCurrentVelocity(1000);
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            boolean vertical = Math.abs(dy) > Math.abs(dx);
            float speed = vertical ? velocity.getYVelocity() : velocity.getXVelocity();
            float distance = vertical ? dy : dx;
            ExtraKeyButton alternate = candidate(event);
            dragged |= alternate != null;
            finish();
            return alternate != null && distance * speed > 0 && Math.abs(speed) >= minVelocity ? alternate : null;
        }

        void cancel() {
            dragged = true;
            finish();
        }

        void finish() {
            if (velocity == null) return;
            velocity.recycle();
            velocity = null;
        }
    }

    /** The popup window shown if {@link ExtraKeyButton#getPopup()} returns a {@code non-null} value
     * and a swipe up action is done on an extra key. */
    protected PopupWindow mPopupWindow;
    @Nullable private PopupWindow mKeyPreview;

    protected ScheduledExecutorService mScheduledExecutor;
    protected Handler mHandler;
    protected Runnable mLongPressRunnable;
    protected int mLongPressCount;
    /** The long-press menu that is open, see {@link ExtraKeyButton#getMenu()}. */
    @Nullable private ExtraKeyMenu mKeyMenu;
    /** The finger position of the current press, so a long-press menu opens under it. */
    private float mPressRawX;
    private float mPressRawY;
    /** A single tap waiting to see whether a second tap makes it a double tap. */
    @Nullable private Runnable mPendingTap;
    @Nullable private MaterialButton mPendingTapButton;
    @Nullable private Typeface mKeyTypeface;
    private ExtraKeyAnimation mKeyAnimation = ExtraKeyAnimation.DEFAULT;
    private final DecelerateInterpolator mKeyReleaseInterpolator = new DecelerateInterpolator();


    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDefaultRowBackground = getBackground();

        setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);
        setSpecialButtons(getDefaultSpecialButtons(this));

        setButtonColors(
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_TEXT_COLOR, DEFAULT_BUTTON_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_TEXT_COLOR, DEFAULT_BUTTON_ACTIVE_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_BACKGROUND_COLOR, DEFAULT_BUTTON_BACKGROUND_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR, DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR));

        setLongPressTimeout(ViewConfiguration.getLongPressTimeout());
        setLongPressRepeatDelay(DEFAULT_LONG_PRESS_REPEAT_DELAY);
    }


    /** Get {@link #mExtraKeysViewClient}. */
    public IExtraKeysView getExtraKeysViewClient() {
        return mExtraKeysViewClient;
    }

    /** Set {@link #mExtraKeysViewClient}. */
    public void setExtraKeysViewClient(IExtraKeysView extraKeysViewClient) {
        mExtraKeysViewClient = extraKeysViewClient;
    }


    /** Get {@link #mRepetitiveKeys}. */
    public List<String> getRepetitiveKeys() {
        if (mRepetitiveKeys == null) return null;
        return mRepetitiveKeys.stream().map(String::new).collect(Collectors.toList());
    }

    /** Set {@link #mRepetitiveKeys}. Must not be {@code null}. */
    public void setRepetitiveKeys(@NonNull List<String> repetitiveKeys) {
        mRepetitiveKeys = repetitiveKeys;
    }


    /** Get {@link #mSpecialButtons}. */
    public Map<SpecialButton, SpecialButtonState> getSpecialButtons() {
        if (mSpecialButtons == null) return null;
        return mSpecialButtons.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Get {@link #mSpecialButtonsKeys}. */
    public Set<String> getSpecialButtonsKeys() {
        if (mSpecialButtonsKeys == null) return null;
        return mSpecialButtonsKeys.stream().map(String::new).collect(Collectors.toSet());
    }

    /** Set {@link #mSpecialButtonsKeys}. Must not be {@code null}. */
    public void setSpecialButtons(@NonNull Map<SpecialButton, SpecialButtonState> specialButtons) {
        mSpecialButtons = specialButtons;
        mSpecialButtonsKeys = this.mSpecialButtons.keySet().stream().map(SpecialButton::getKey).collect(Collectors.toSet());
    }


    /**
     * Set the {@link ExtraKeysView} button colors.
     *
     * @param buttonTextColor The value for {@link #mButtonTextColor}.
     * @param buttonActiveTextColor The value for {@link #mButtonActiveTextColor}.
     * @param buttonBackgroundColor The value for {@link #mButtonBackgroundColor}.
     * @param buttonActiveBackgroundColor The value for {@link #mButtonActiveBackgroundColor}.
     */
    public void setButtonColors(int buttonTextColor, int buttonActiveTextColor, int buttonBackgroundColor, int buttonActiveBackgroundColor) {
        mButtonTextColor = buttonTextColor;
        mButtonActiveTextColor = buttonActiveTextColor;
        mButtonBackgroundColor = buttonBackgroundColor;
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
    }


    /**
     * Override the active colors of the {@link ExtraKeysView} buttons, for example from the
     * {@code extra-keys-button-active-text-color} and {@code extra-keys-button-active-background-color}
     * properties. A {@code null} color restores the value defined by the current theme. Call
     * {@link #reload(ExtraKeysInfo, float)} afterwards so that already created buttons are rebuilt
     * with the new colors.
     *
     * @param activeTextColor The value for {@link #mButtonActiveTextColor} or {@code null}.
     * @param activeBackgroundColor The value for {@link #mButtonActiveBackgroundColor} or {@code null}.
     */
    public void setButtonActiveColorOverrides(@Nullable Integer activeTextColor, @Nullable Integer activeBackgroundColor) {
        Context context = getContext();
        mFillActiveSpecialButtons = activeBackgroundColor != null;
        setButtonActiveTextColor(activeTextColor != null ? activeTextColor :
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_TEXT_COLOR, DEFAULT_BUTTON_ACTIVE_TEXT_COLOR));
        setButtonActiveBackgroundColor(activeBackgroundColor != null ? activeBackgroundColor :
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR, DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR));
    }

    /**
     * Set the corner radius of the {@link ExtraKeysView} button backgrounds, for example from the
     * {@code extra-keys-button-corner-radius} property. A {@code null} radius restores the shape
     * defined by the current theme. Call {@link #reload(ExtraKeysInfo, float)} afterwards so that
     * already created buttons are rebuilt.
     *
     * @param cornerRadiusDp The radius in dp or {@code null}.
     */
    public void setButtonCornerRadiusDp(@Nullable Float cornerRadiusDp) {
        if (cornerRadiusDp == null) {
            mButtonCornerRadiusPx = -1;
        } else {
            mButtonCornerRadiusPx = Math.round(cornerRadiusDp * getResources().getDisplayMetrics().density);
        }
    }

    /** Apply the row appearance and explicit overrides before reloading the keys. */
    public void setAppearance(@Nullable String theme, @Nullable Integer rowColor,
                              @Nullable Integer activeText, @Nullable Integer activeBackground,
                              @Nullable Float radiusDp) {
        Context context = getContext();
        mRoundedTheme = TermuxPropertyConstants.EXTRA_KEYS_THEME_ROUNDED.equalsIgnoreCase(theme);
        if (mRoundedTheme) {
            boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
            // Dark colors measured from Gboard's dark theme: letter keys, function keys
            // (Esc and the arrows here, like Gboard's shift and enter) and the keyboard background.
            mButtonTextColor = dark ? 0xFFE6E4F0 : 0xFF222629;
            mButtonBackgroundColor = dark ? 0xFF2B2B34 : 0xFFE0E3E7;
            mRoundedNavigationTextColor = dark ? 0xFFBCBED5 : 0xFF25394A;
            mRoundedNavigationBackgroundColor = dark ? 0xFF383A4D : 0xFFCBD7E2;
            if (rowColor == null) rowColor = dark ? 0xFF191920 : 0xFFF2F4F7;
            if (activeText == null) activeText = dark ? 0xFFF2F0FA : 0xFF222629;
            if (activeBackground == null) activeBackground = dark ? 0xFF4A4D63 : 0xFFCED3D8;
            if (radiusDp == null) radiusDp = 7f;
        } else {
            mButtonTextColor = ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_TEXT_COLOR, DEFAULT_BUTTON_TEXT_COLOR);
            mButtonBackgroundColor = ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_BACKGROUND_COLOR, DEFAULT_BUTTON_BACKGROUND_COLOR);
        }
        // setBackgroundColor can mutate an existing ColorDrawable, so detach the default first.
        if (rowColor != null) setBackground(new android.graphics.drawable.ColorDrawable(rowColor));
        else setBackground(mDefaultRowBackground);
        setButtonActiveColorOverrides(activeText, activeBackground);
        setButtonCornerRadiusDp(radiusDp);
    }

    /** Get {@link #mButtonCornerRadiusPx}. */
    public int getButtonCornerRadiusPx() {
        return mButtonCornerRadiusPx;
    }

    /** Apply {@link #mButtonCornerRadiusPx} to a button, if one is configured. */
    protected void applyButtonShape(MaterialButton button) {
        if (mButtonCornerRadiusPx >= 0)
            button.setCornerRadius(mButtonCornerRadiusPx);
    }

    /**
     * Get the background color of a button while it is not being pressed: the active background
     * color for an active (latched or locked) {@link SpecialButton}, otherwise the normal one.
     */
    public int getRestingBackgroundColor(ExtraKeyButton buttonInfo) {
        ExtraKeyButton hold = buttonInfo.getHold();
        if (mFillActiveSpecialButtons && (isActiveModifier(buttonInfo) || (hold != null && isActiveModifier(hold))))
            return mButtonActiveBackgroundColor;
        if (buttonInfo.backgroundColor != null) return buttonInfo.backgroundColor;
        return usesRoundedNavigationTint(buttonInfo) ? mRoundedNavigationBackgroundColor : mButtonBackgroundColor;
    }

    /** Get the background color of a {@link SpecialButton} that is not being pressed. */
    public int getSpecialButtonBackgroundColor(boolean active) {
        return active && mFillActiveSpecialButtons ? mButtonActiveBackgroundColor : mButtonBackgroundColor;
    }

    /** Get {@link #mButtonTextColor}. */
    public int getButtonTextColor() {
        return mButtonTextColor;
    }

    /** Set {@link #mButtonTextColor}. */
    public void setButtonTextColor(int buttonTextColor) {
        mButtonTextColor = buttonTextColor;
    }


    /** Get {@link #mButtonActiveTextColor}. */
    public int getButtonActiveTextColor() {
        return mButtonActiveTextColor;
    }

    /** Set {@link #mButtonActiveTextColor}. */
    public void setButtonActiveTextColor(int buttonActiveTextColor) {
        mButtonActiveTextColor = buttonActiveTextColor;
    }


    /** Get {@link #mButtonBackgroundColor}. */
    public int getButtonBackgroundColor() {
        return mButtonBackgroundColor;
    }

    /** Set {@link #mButtonBackgroundColor}. */
    public void setButtonBackgroundColor(int buttonBackgroundColor) {
        mButtonBackgroundColor = buttonBackgroundColor;
    }


    /** Get {@link #mButtonActiveBackgroundColor}. */
    public int getButtonActiveBackgroundColor() {
        return mButtonActiveBackgroundColor;
    }

    /** Set {@link #mButtonActiveBackgroundColor}. */
    public void setButtonActiveBackgroundColor(int buttonActiveBackgroundColor) {
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
    }

    /** Set {@link #mButtonTextAllCaps}. */
    public void setButtonTextAllCaps(boolean buttonTextAllCaps) {
        mButtonTextAllCaps = buttonTextAllCaps;
    }


    /** Get {@link #mLongPressTimeout}. */
    public int getLongPressTimeout() {
        return mLongPressTimeout;
    }

    /** Set {@link #mLongPressTimeout}. */
    public void setLongPressTimeout(int longPressDuration) {
        if (longPressDuration >= MIN_LONG_PRESS_DURATION && longPressDuration <= MAX_LONG_PRESS_DURATION) {
            mLongPressTimeout = longPressDuration;
        } else {
            mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION;
        }
    }

    /** Get {@link #mLongPressRepeatDelay}. */
    public int getLongPressRepeatDelay() {
        return mLongPressRepeatDelay;
    }

    /** Set {@link #mLongPressRepeatDelay}. */
    public void setLongPressRepeatDelay(int longPressRepeatDelay) {
        if (mLongPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY && mLongPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY) {
            mLongPressRepeatDelay = longPressRepeatDelay;
        } else {
            mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY;
        }
    }


    /** Get the default map that can be used for {@link #mSpecialButtons}. */
    @NonNull
    public Map<SpecialButton, SpecialButtonState> getDefaultSpecialButtons(ExtraKeysView extraKeysView) {
        return new HashMap<SpecialButton, SpecialButtonState>() {{
            put(SpecialButton.CTRL, new SpecialButtonState(extraKeysView));
            put(SpecialButton.ALT, new SpecialButtonState(extraKeysView));
            put(SpecialButton.SHIFT, new SpecialButtonState(extraKeysView));
            put(SpecialButton.FN, new SpecialButtonState(extraKeysView));
        }};
    }



    /**
     * Reload this instance of {@link ExtraKeysView} with the info passed in {@code extraKeysInfo}.
     *
     * @param extraKeysInfo The {@link ExtraKeysInfo} that defines the necessary info for the extra keys.
     * @param heightPx The height in pixels of the parent surrounding the {@link ExtraKeysView}. It must
     *                 be a single child.
     */
    @SuppressLint("ClickableViewAccessibility")
    public void reload(ExtraKeysInfo extraKeysInfo, float heightPx) {
        if (extraKeysInfo == null)
            return;

        stopScheduledExecutors();
        if (mPopupWindow != null) dismissPopup();
        dismissKeyPreview();
        dismissKeyMenu();
        cancelPendingTap();
        for (SpecialButtonState state : mSpecialButtons.values()) {
            state.buttons.clear();
            state.presentationUpdates.clear();
        }

        mKeyHints.clear();
        mKeyInfos.clear();
        mFlingKeys.clear();
        removeAllViews();

        ExtraKeyButton[][] buttons = extraKeysInfo.getMatrix();

        setRowCount(buttons.length);
        setColumnCount(maximumLength(buttons));

        for (int row = 0; row < buttons.length; row++) {
            for (int col = 0; col < buttons[row].length; col++) {
                final ExtraKeyButton buttonInfo = buttons[row][col];

                ExtraKeyButton hold = buttonInfo.getHold();
                boolean hasHeldModifier = hold != null && isSpecialButton(hold);
                boolean customColors = mRoundedTheme || buttonInfo.textColor != null || buttonInfo.backgroundColor != null;
                MaterialButton button;
                if (isSpecialButton(buttonInfo)) {
                    button = createSpecialButton(buttonInfo.getKey(), !hasHeldModifier && !customColors);
                    if (button == null) return;
                } else {
                    button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
                }

                mKeyInfos.put(button, buttonInfo);
                button.setText(buttonInfo.getDisplay());
                if (mRoundedTheme) button.setLetterSpacing(0f);
                String hint = buttonInfo.getHint();
                if (hint != null && !hint.isEmpty()) {
                    mKeyHints.put(button, hint.replace('\n', ' ').replace('\r', ' '));
                }
                if (mKeyTypeface != null) button.setTypeface(mKeyTypeface);
                button.setTextColor(mButtonTextColor);
                button.setAllCaps(mButtonTextAllCaps && !buttonInfo.hasCustomDisplay());
                int inset = 0;
                if (buttonInfo.insetDp != null || mRoundedTheme) {
                    // The grid margins supply all the spacing for an inset keycap.
                    button.setInsetTop(0);
                    button.setInsetBottom(0);
                    float insetDp = buttonInfo.insetDp != null ? buttonInfo.insetDp : 2f;
                    inset = Math.round(insetDp * getResources().getDisplayMetrics().density);
                }
                button.setPadding(0, 0, 0, 0);
                applyButtonShape(button);
                if (customColors) updateKeyColors(buttonInfo, button, isActiveModifier(buttonInfo));
                if (hasHeldModifier) {
                    bindHeldModifier(buttonInfo, hold, button);
                } else if (customColors && isSpecialButton(buttonInfo)) {
                    SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
                    state.presentationUpdates.add(() -> updateKeyColors(buttonInfo, button, state.isActive));
                }
                if (hold != null) {
                    button.setOnLongClickListener(view -> {
                        performHold(view, hold, button);
                        return true;
                    });
                }

                ExtraKeyButton doubleTap = buttonInfo.getDoubleTap();
                registerAlternateModifier(doubleTap);
                if (buttonInfo.getMenu() != null) {
                    for (ExtraKeyButton item : buttonInfo.getMenu()) registerAlternateModifier(item);
                }
                button.setOnClickListener(view -> {
                    performExtraKeyButtonHapticFeedback(view, buttonInfo, button);
                    if (doubleTap != null) {
                        // Only keys with a double tap wait for a second tap; others stay instant.
                        if (mPendingTap != null && mPendingTapButton == button) {
                            cancelPendingTap();
                            onAnyExtraKeyButtonClick(view, doubleTap, button);
                        } else {
                            flushPendingTap();
                            mPendingTapButton = button;
                            mPendingTap = () -> {
                                mPendingTap = null;
                                mPendingTapButton = null;
                                onAnyExtraKeyButtonClick(view, buttonInfo, button);
                            };
                            if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
                            mHandler.postDelayed(mPendingTap, ViewConfiguration.getDoubleTapTimeout());
                        }
                        return;
                    }
                    flushPendingTap();
                    if (hasHeldModifier && mSpecialButtons.get(SpecialButton.valueOf(hold.getKey()))
                        .onAlternateTap(SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout())) return;
                    onAnyExtraKeyButtonClick(view, buttonInfo, button);
                });

                boolean horizontalFling = buttonInfo.getFling(false) != null || buttonInfo.getFling(true) != null;
                KeyFling fling = horizontalFling || buttonInfo.getDownFling() != null ? new KeyFling(buttonInfo) : null;
                if (fling != null) {
                    if (horizontalFling) mFlingKeys.put(button, buttonInfo);
                    registerAlternateModifier(buttonInfo.getFling(false));
                    registerAlternateModifier(buttonInfo.getFling(true));
                    registerAlternateModifier(buttonInfo.getDownFling());
                }
                button.setOnTouchListener((view, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mPressRawX = event.getRawX();
                            mPressRawY = event.getRawY();
                            if (fling != null) fling.start(event);
                            animateKeyPress(button, true);
                            view.setBackgroundColor(mButtonActiveBackgroundColor);
                            // Start long press scheduled executors which will be stopped in next MotionEvent
                            startScheduledExecutors(view, buttonInfo, button);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            if (mKeyMenu != null) {
                                if (mKeyMenu.track(event.getRawX(), event.getRawY()))
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                                return true;
                            }
                            mPressRawX = event.getRawX();
                            mPressRawY = event.getRawY();
                            if (fling != null && mPopupWindow == null && mLongPressCount == 0 && fling.move(event)) {
                                stopScheduledExecutors();
                                dismissKeyPreview();
                                return true;
                            }
                            if (hold != null && mLongPressCount > 0) return true;
                            if (buttonInfo.getPopup() != null) {
                                // Measure the swipe against the key's resting bounds.
                                float y = button.getPivotY() + (event.getY() - button.getPivotY()) * button.getScaleY()
                                    + button.getTranslationY();
                                if (mPopupWindow == null && y < 0) {
                                    stopScheduledExecutors();
                                    view.setBackgroundColor(getRestingBackgroundColor(buttonInfo));
                                    dismissKeyPreview();
                                    showPopup(view, buttonInfo.getPopup());
                                }
                                if (mPopupWindow != null && y > 0) {
                                    view.setBackgroundColor(mButtonActiveBackgroundColor);
                                    dismissPopup();
                                    if (mKeyAnimation.showsPreview()) showKeyPreview(button);
                                }
                            }
                            return true;

                        case MotionEvent.ACTION_POINTER_DOWN:
                            if (fling != null) {
                                fling.cancel();
                                stopScheduledExecutors();
                            }
                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            if (fling != null) fling.cancel();
                            animateKeyPress(button, false);
                            view.setBackgroundColor(getRestingBackgroundColor(buttonInfo));
                            stopScheduledExecutors();
                            if (mPopupWindow != null) dismissPopup();
                            dismissKeyMenu();
                            return true;

                        case MotionEvent.ACTION_UP:
                            animateKeyPress(button, false);
                            view.setBackgroundColor(getRestingBackgroundColor(buttonInfo));
                            stopScheduledExecutors();
                            if (mKeyMenu != null) {
                                mKeyMenu.track(event.getRawX(), event.getRawY());
                                ExtraKeyButton choice = mKeyMenu.selected();
                                dismissKeyMenu();
                                if (fling != null) fling.finish();
                                if (choice != null) {
                                    performExtraKeyButtonHapticFeedback(view, choice, button);
                                    onAnyExtraKeyButtonClick(view, choice, button);
                                }
                                return true;
                            }
                            if (fling != null) {
                                if (mPopupWindow == null && mLongPressCount == 0) {
                                    ExtraKeyButton alternate = fling.release(event);
                                    if (alternate != null) {
                                        performExtraKeyButtonHapticFeedback(view, alternate, button);
                                        onAnyExtraKeyButtonClick(view, alternate, button);
                                    }
                                    if (fling.dragged) return true;
                                } else {
                                    fling.finish();
                                }
                            }
                            // If ACTION_UP up was not from a repetitive key or was with a key with a popup button
                            if (mLongPressCount == 0 || mPopupWindow != null) {
                                // Trigger popup button click if swipe up complete
                                if (mPopupWindow != null) {
                                    dismissPopup();
                                    if (buttonInfo.getPopup() != null) {
                                        onAnyExtraKeyButtonClick(view, buttonInfo.getPopup(), button);
                                    }
                                } else {
                                    view.performClick();
                                }
                            }
                            return true;

                        default:
                            return true;
                    }
                });

                LayoutParams param = new GridLayout.LayoutParams();
                param.width = 0;
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                   param.height = (int)(heightPx + 0.5);
                } else {
                    param.height = 0;
                }
                param.setMargins(inset, inset, inset, inset);
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.f);
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.f);
                button.setLayoutParams(param);

                if (buttonInfo.getKey().isEmpty() && buttonInfo.getDisplay().isEmpty()
                    && buttonInfo.getCommand() == null && buttonInfo.getAction() == null && hold == null
                    && buttonInfo.getPopup() == null && buttonInfo.getMenu() == null && doubleTap == null) {
                    button.setVisibility(INVISIBLE);
                }
                addView(button);
            }
        }
    }



    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (mExtraKeysViewClient != null)
            mExtraKeysViewClient.onExtraKeyButtonClick(view, buttonInfo, button);
    }

    public void performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (mExtraKeysViewClient != null) {
            // If client handled the feedback, then just return
            if (mExtraKeysViewClient.performExtraKeyButtonHapticFeedback(view, buttonInfo, button))
                return;
        }

        if (Settings.System.getInt(getContext().getContentResolver(),
            Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {

            if (Build.VERSION.SDK_INT >= 28) {
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } else {
                // Perform haptic feedback only if no total silence mode enabled.
                if (Settings.Global.getInt(getContext().getContentResolver(), "zen_mode", 0) != 2) {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            }
        }
    }



    public void onAnyExtraKeyButtonClick(View view, @NonNull ExtraKeyButton buttonInfo, MaterialButton button) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressCount > 0) return;
            SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
            if (state == null) return;

            // A tap latches the button for the next key, a double tap locks it until it is tapped
            // again, and a tap on an active button deactivates it and the lock
            state.onTap(SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout());
        } else {
            onExtraKeyButtonClick(view, buttonInfo, button);
        }
    }


    public void setKeyTypeface(@Nullable Typeface typeface) {
        mKeyTypeface = typeface;
        if (typeface == null) return;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof MaterialButton) ((MaterialButton) child).setTypeface(typeface);
        }
    }

    public void setKeyAnimation(@NonNull ExtraKeyAnimation animation) {
        if (mKeyAnimation != animation) dismissKeyPreview();
        mKeyAnimation = animation;
    }

    private void animateKeyPress(MaterialButton button, boolean pressed) {
        if (mKeyAnimation.showsPreview()) {
            if (pressed) showKeyPreview(button);
            else dismissKeyPreview();
            return;
        }
        button.animate().cancel();
        boolean motionEnabled = mKeyAnimation != ExtraKeyAnimation.OFF
            && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled());
        if (!motionEnabled) {
            button.setScaleX(1);
            button.setScaleY(1);
            button.setTranslationY(0);
        } else if (pressed) {
            button.setScaleX(mKeyAnimation.pressedScale);
            button.setScaleY(mKeyAnimation.pressedScale);
            button.setTranslationY(mKeyAnimation.pressedOffsetDp * getResources().getDisplayMetrics().density);
        } else {
            button.animate().scaleX(1).scaleY(1).translationY(0)
                .setDuration(mKeyAnimation.releaseDurationMs)
                .setInterpolator(mKeyReleaseInterpolator)
                .start();
        }
    }

    private void showKeyPreview(MaterialButton key) {
        dismissKeyPreview();
        if (key.getWindowToken() == null) return;

        float density = getResources().getDisplayMetrics().density;
        boolean bubble = mKeyAnimation == ExtraKeyAnimation.KEY_BUBBLE;
        int padding = Math.round((bubble ? 12 : 10) * density);
        int gap = Math.round((bubble ? -3 : 3) * density);
        TextView label = new TextView(getContext());
        CharSequence text = key.getText();
        if (key.getTransformationMethod() != null)
            text = key.getTransformationMethod().getTransformation(text, key);
        label.setText(text);
        label.setTypeface(key.getTypeface());
        label.setTextColor(mButtonActiveTextColor);
        label.setTextSize(24);
        label.setGravity(Gravity.CENTER);
        label.setPadding(padding, 0, padding, 0);
        int size = Math.round((bubble ? 48 : 50) * density);
        label.setMinWidth(bubble ? size : Math.max(key.getWidth(), Math.round(48 * density)));
        label.setMinHeight(size);
        if (bubble) {
            label.setIncludeFontPadding(false);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable background = new GradientDrawable();
        background.setColor(mButtonActiveBackgroundColor);
        background.setCornerRadius(bubble ? size / 2f : 12 * density);
        label.setBackground(background);
        label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        int width = Math.min(label.getMeasuredWidth(), getResources().getDisplayMetrics().widthPixels);
        int height = label.getMeasuredHeight();
        mKeyPreview = new PopupWindow(label, width, height, false);
        mKeyPreview.setTouchable(false);
        mKeyPreview.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        mKeyPreview.setAnimationStyle(0);
        mKeyPreview.setElevation(5 * density);
        mKeyPreview.showAsDropDown(key, (key.getWidth() - width) / 2,
            -key.getHeight() - height - gap, Gravity.LEFT);
        if (bubble && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())) {
            label.setScaleX(0.92f);
            label.setScaleY(0.92f);
            label.animate().scaleX(1).scaleY(1).setDuration(90)
                .setInterpolator(mKeyReleaseInterpolator).start();
        }
    }

    /**
     * Plays the press and release effect of the key at {@code index} without sending it, so a
     * settings preview can show the selected effect without being touched.
     */
    public void demoKeyPress(int index, long holdMs) {
        View child = getChildAt(index);
        if (!(child instanceof MaterialButton) || child.getVisibility() != VISIBLE) return;
        MaterialButton button = (MaterialButton) child;
        ExtraKeyButton info = mKeyInfos.get(button);
        if (info == null) return;
        animateKeyPress(button, true);
        button.setBackgroundColor(mButtonActiveBackgroundColor);
        button.postDelayed(() -> {
            animateKeyPress(button, false);
            button.setBackgroundColor(getRestingBackgroundColor(info));
        }, holdMs);
    }

    private void dismissKeyPreview() {
        if (mKeyPreview == null) return;
        mKeyPreview.dismiss();
        mKeyPreview = null;
    }

    private void registerAlternateModifier(@Nullable ExtraKeyButton key) {
        if (key != null && isSpecialButton(key))
            mSpecialButtons.get(SpecialButton.valueOf(key.getKey())).setIsCreated(true);
    }

    private boolean isActiveModifier(ExtraKeyButton key) {
        return isSpecialButton(key) && mSpecialButtons.get(SpecialButton.valueOf(key.getKey())).isActive;
    }

    private boolean usesRoundedNavigationTint(ExtraKeyButton key) {
        if (!mRoundedTheme) return false;
        switch (key.getKey()) {
            case "ESC":
            case "LEFT":
            case "RIGHT":
            case "UP":
            case "DOWN":
                return true;
            default:
                return false;
        }
    }

    private void updateKeyColors(ExtraKeyButton key, MaterialButton button, boolean active) {
        int defaultTextColor = usesRoundedNavigationTint(key) ? mRoundedNavigationTextColor : mButtonTextColor;
        int textColor = key.textColor != null ? key.textColor : defaultTextColor;
        button.setTextColor(active ? mButtonActiveTextColor : textColor);
        button.setBackgroundColor(getRestingBackgroundColor(key));
    }

    private void bindHeldModifier(ExtraKeyButton primary, ExtraKeyButton hold, MaterialButton button) {
        SpecialButtonState held = mSpecialButtons.get(SpecialButton.valueOf(hold.getKey()));
        held.setIsCreated(true);
        Runnable update = () -> {
            boolean heldActive = held.isActive;
            boolean active = heldActive || isActiveModifier(primary);
            ExtraKeyButton shown = heldActive ? hold : primary;
            button.setText(shown.getDisplay());
            button.setAllCaps(mButtonTextAllCaps && !shown.hasCustomDisplay());
            updateKeyColors(primary, button, active);
        };
        held.presentationUpdates.add(update);
        if (isSpecialButton(primary) && !primary.getKey().equals(hold.getKey()))
            mSpecialButtons.get(SpecialButton.valueOf(primary.getKey())).presentationUpdates.add(update);
        update.run();
    }

    private void performHold(View view, ExtraKeyButton hold, MaterialButton button) {
        performExtraKeyButtonHapticFeedback(view, hold, button);
        if (isSpecialButton(hold)) {
            mSpecialButtons.get(SpecialButton.valueOf(hold.getKey())).toggleLatch();
            if (mKeyPreview != null) showKeyPreview(button);
        } else {
            onExtraKeyButtonClick(view, hold, button);
        }
    }

    public void startScheduledExecutors(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        stopScheduledExecutors();
        mLongPressCount = 0;
        ExtraKeyButton hold = buttonInfo.getHold();
        if (buttonInfo.getMenu() != null) {
            scheduleLongPress(() -> showKeyMenu(view, buttonInfo, button));
        } else if (hold != null) {
            scheduleLongPress(() -> performHold(view, hold, button));
        } else if (mRepetitiveKeys.contains(buttonInfo.getKey())) {
            // Auto repeat key if long pressed until ACTION_UP stops it by calling stopScheduledExecutors.
            // Currently, only one (last) repeat key can run at a time. Old ones are stopped.
            mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            mScheduledExecutor.scheduleWithFixedDelay(() -> {
                mLongPressCount++;
                onExtraKeyButtonClick(view, buttonInfo, button);
            }, mLongPressTimeout, mLongPressRepeatDelay, TimeUnit.MILLISECONDS);
        } else if (isSpecialButton(buttonInfo)) {
            SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
            if (state != null) scheduleLongPress(state::onLongPress);
        }
    }

    private void scheduleLongPress(Runnable action) {
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        mLongPressRunnable = () -> {
            mLongPressCount++;
            action.run();
        };
        mHandler.postDelayed(mLongPressRunnable, mLongPressTimeout);
    }

    public void stopScheduledExecutors() {
        if (mScheduledExecutor != null) {
            mScheduledExecutor.shutdownNow();
            mScheduledExecutor = null;
        }

        if (mLongPressRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mLongPressRunnable);
            mLongPressRunnable = null;
        }
    }

    private void showKeyMenu(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        dismissKeyPreview();
        if (mPopupWindow != null) dismissPopup();
        dismissKeyMenu();
        int panel = mRoundedTheme ? mRoundedNavigationBackgroundColor : getRestingBackgroundColor(buttonInfo);
        if (Color.alpha(panel) < 0xF0) panel = 0xF0303134;
        mKeyMenu = new ExtraKeyMenu(view, buttonInfo.getMenu(), mPressRawX, mPressRawY, panel,
            mButtonTextColor, mButtonActiveTextColor, mButtonActiveBackgroundColor,
            getButtonCornerRadiusPx(), mKeyTypeface, mButtonTextAllCaps);
        view.setBackgroundColor(getRestingBackgroundColor(buttonInfo));
        // Sliding sideways picks an item; the toolbar must not take it as a row swipe.
        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
        performExtraKeyButtonHapticFeedback(view, buttonInfo, button);
    }

    private void dismissKeyMenu() {
        if (mKeyMenu == null) return;
        mKeyMenu.dismiss();
        mKeyMenu = null;
    }

    /** Runs a waiting single tap now, before a different key is handled. */
    private void flushPendingTap() {
        Runnable tap = mPendingTap;
        cancelPendingTap();
        if (tap != null) tap.run();
    }

    private void cancelPendingTap() {
        if (mPendingTap != null && mHandler != null) mHandler.removeCallbacks(mPendingTap);
        mPendingTap = null;
        mPendingTapButton = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingTap();
        dismissKeyMenu();
        stopScheduledExecutors();
        if (mPopupWindow != null) dismissPopup();
        dismissKeyPreview();
        super.onDetachedFromWindow();
    }

    void showPopup(View view, ExtraKeyButton extraButton) {
        int width = view.getMeasuredWidth();
        int height = view.getMeasuredHeight();
        MaterialButton button;
        if (isSpecialButton(extraButton)) {
            button = createSpecialButton(extraButton.getKey(), false);
            if (button == null) return;
        } else {
            button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
            button.setTextColor(mButtonTextColor);
        }
        button.setText(extraButton.getDisplay());
        if (mKeyTypeface != null) button.setTypeface(mKeyTypeface);
        button.setAllCaps(mButtonTextAllCaps && !extraButton.hasCustomDisplay());
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setWidth(width);
        button.setHeight(height);
        applyButtonShape(button);
        if (extraButton.textColor != null) button.setTextColor(extraButton.textColor);
        button.setBackgroundColor(extraButton.backgroundColor != null ? extraButton.backgroundColor : mButtonActiveBackgroundColor);
        mPopupWindow = new PopupWindow(this);
        mPopupWindow.setWidth(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setHeight(LayoutParams.WRAP_CONTENT);
        mPopupWindow.setContentView(button);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(false);
        mPopupWindow.showAsDropDown(view, 0, -2 * height);
    }

    public void dismissPopup() {
        mPopupWindow.setContentView(null);
        mPopupWindow.dismiss();
        mPopupWindow = null;
    }



    /** Check whether a {@link ExtraKeyButton} is a {@link SpecialButton}. */
    public boolean isSpecialButton(ExtraKeyButton button) {
        return mSpecialButtonsKeys.contains(button.getKey());
    }

    /**
     * Read whether {@link SpecialButton} registered in {@link #mSpecialButtons} is active or not.
     *
     * @param specialButton The {@link SpecialButton} to read.
     * @param autoSetInActive Set to {@code true} if {@link SpecialButtonState#isActive} should be
     *                        set {@code false} if button is not locked.
     * @return Returns {@code null} if button does not exist in {@link #mSpecialButtons}. If button
     *         exists, then returns {@code true} if the button is created in {@link ExtraKeysView}
     *         and is active, otherwise {@code false}.
     */
    @Nullable
    public Boolean readSpecialButton(SpecialButton specialButton, boolean autoSetInActive) {
        SpecialButtonState state = mSpecialButtons.get(specialButton);
        if (state == null) return null;

        // Disable active state only if not locked
        return state.read(autoSetInActive);
    }

    public MaterialButton createSpecialButton(String buttonKey, boolean needUpdate) {
        SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonKey));
        if (state == null) return null;
        state.setIsCreated(true);
        MaterialButton button = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
        button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
        applyButtonShape(button);
        if (state.isActive && mFillActiveSpecialButtons)
            button.setBackgroundColor(mButtonActiveBackgroundColor);
        if (needUpdate) {
            state.buttons.add(button);
        }
        return button;
    }



    /**
     * General util function to compute the longest column length in a matrix.
     */
    public static int maximumLength(Object[][] matrix) {
        int m = 0;
        for (Object[] row : matrix)
            m = Math.max(m, row.length);
        return m;
    }

}
