package com.termux.app.terminal.io;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Direction;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Result;

/**
 * The pager of the terminal toolbar: the normal extra keys row on page 0 and the text input on
 * page 1.
 *
 * Horizontal swipes over the normal row reveal the pending action in a pill above the toolbar.
 * The row moves slightly as the gesture progresses. Crossing either threshold gives a tick;
 * releasing commits the armed action, and a shorter drag cancels.
 *
 * The pager itself never drags from the normal row to the text input: that is the
 * {@code toggle-text-input} action. From the text input, dragging back to the normal row is the
 * pager's own, untouched. Once a drag is a swipe, the keys get a cancel, so that no key is pressed;
 * everything else is passed on, so key presses, key repeat and popups keep working.
 */
public class ExtraKeysSwipeViewPager extends ViewPager {

    /** How far the finger moves before the row follows it, in dp. */
    static final float DRAG_START_DP = 12;

    /** The swipe distance: this fraction of the row's width, but at least {@link #MIN_SWIPE_DP}. */
    static final float SWIPE_FRACTION = 0.2f;
    static final float MIN_SWIPE_DP = 64;

    /** The long swipe distance: this fraction of the row's width, but at least twice the swipe distance. */
    static final float LONG_SWIPE_FRACTION = 0.5f;

    /** The duration of the snap back at the end of a swipe. */
    static final long RELEASE_DURATION_MS = 200;

    static final float MAX_FOLLOW_DP = 24;

    public interface Listener extends ExtraKeysSwipeDetector.Bindings {

        /** A swipe over the normal extra keys row is done. */
        void onSwipe(@NonNull Direction direction, boolean longSwipe);

        /**
         * Whether a swipe has an action right now. Asked for each touch: without one, the swipe is
         * not recognized at all and nothing is consumed or drawn.
         */
        @Override
        default boolean hasSwipe(@NonNull Direction direction) {
            return true;
        }

        /** Whether the direction has a long swipe right now. */
        @Override
        default boolean hasLongSwipe(@NonNull Direction direction) {
            return false;
        }

        /** The name of the action of a swipe for its preview, or {@code null} if it has none. */
        @Nullable
        default String getSwipeLabel(@NonNull Direction direction, boolean longSwipe) {
            return null;
        }

        /** Show or clear the action preview above the keys. */
        default void onSwipePreview(@Nullable String label, boolean armed) {}

        /** Whether the row may follow the finger. The preview is shown even without motion. */
        default boolean isFeedbackEnabled() {
            return true;
        }
    }

    private final ExtraKeysSwipeDetector mSwipeDetector;
    private Listener mListener;
    private boolean mKeyFlingLeft;
    private boolean mKeyFlingRight;
    private final ExtraKeysSwipeDetector.Bindings mRowSwipes = new ExtraKeysSwipeDetector.Bindings() {
        private boolean reserved(Direction direction) {
            // Row swipe labels describe page movement, opposite to the finger.
            return direction == Direction.LEFT ? mKeyFlingRight : mKeyFlingLeft;
        }

        @Override public boolean hasSwipe(Direction direction) {
            return mListener != null && !reserved(direction) && mListener.hasSwipe(direction);
        }

        @Override public boolean hasLongSwipe(Direction direction) {
            return mListener != null && !reserved(direction) && mListener.hasLongSwipe(direction);
        }
    };

    /** Set when the running touch is a swipe, which then owns the touch. */
    private boolean mSwallowTouch;

    /** Set when the running touch started on the settled normal row, where the pager may not drag. */
    private boolean mPagingBlocked;

    private ValueAnimator mReleaseAnimator;

    private final float mDensity;
    @Nullable private Direction mFollowDirection;
    @Nullable private String mSwipeLabel;
    @Nullable private String mLongLabel;
    @NonNull private Result mPending = Result.NONE;
    private boolean mAnimateFollow;

    public ExtraKeysSwipeViewPager(@NonNull Context context) {
        this(context, null);
    }

    public ExtraKeysSwipeViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float minDistance = MIN_SWIPE_DP * mDensity;
        mSwipeDetector = new ExtraKeysSwipeDetector(DRAG_START_DP * mDensity, minDistance, 2 * minDistance, touchSlop);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float minDistance = Math.max(MIN_SWIPE_DP * mDensity, SWIPE_FRACTION * width);
        mSwipeDetector.setDistances(minDistance, Math.max(2 * minDistance, LONG_SWIPE_FRACTION * width));
    }

    /** The swipe distance in px, for tests. */
    float getSwipeDistance() {
        return mSwipeDetector.getMinDistance();
    }

    /** The long swipe distance in px, for tests. */
    float getLongSwipeDistance() {
        return mSwipeDetector.getLongDistance();
    }

    private boolean isOnSettledNormalRow() {
        return getCurrentItem() == 0 && getScrollX() == 0;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN && isKeyMenuOpen()) {
            // A long-press menu owns the finger: sliding picks an item, it is never a row swipe.
            mSwipeDetector.cancel();
            return super.dispatchTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwallowTouch = false;
                // Only on the settled normal row: not while the text input is selected or the
                // pager is still scrolling or settling on its way there or back.
                boolean eligible = mListener != null && isOnSettledNormalRow();
                ExtraKeysView keys = eligible ? findViewById(R.id.terminal_toolbar_extra_keys) : null;
                mKeyFlingLeft = keys != null && keys.hasFlingAt(event.getRawX(), event.getRawY(), false);
                mKeyFlingRight = keys != null && keys.hasFlingAt(event.getRawX(), event.getRawY(), true);
                mPagingBlocked = eligible;
                mSwipeDetector.onDown(event.getX(), event.getY(), eligible ? mRowSwipes : null);
                break;

            case MotionEvent.ACTION_MOVE:
                if (mSwipeDetector.isFollowing()) {
                    mSwipeDetector.onMove(event.getX(), event.getY());
                    updateFollow();
                    return true;
                }
                if (mSwallowTouch) return true;
                if (mSwipeDetector.onMove(event.getX(), event.getY()) == Result.RECOGNIZED
                    && getCurrentItem() == 0) {
                    mSwallowTouch = true;
                    cancelTouchOfChildren(event);
                    startFollow();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mSwipeDetector.isFollowing()) {
                    finishFollow(mSwipeDetector.onUp(event.getX(), event.getY()));
                    return true;
                }
                mSwipeDetector.onUp(event.getX(), event.getY());
                if (mSwallowTouch) return true;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                if (mSwipeDetector.isFollowing()) {
                    mSwipeDetector.cancel();
                    finishFollow(Result.NONE);
                    return true;
                }
                mSwipeDetector.cancel();
                if (mSwallowTouch) return true;
                break;

            default:
                if (mSwallowTouch) return true;
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isKeyMenuOpen() {
        ExtraKeysView keys = findViewById(R.id.terminal_toolbar_extra_keys);
        return keys != null && keys.isKeyMenuOpen();
    }

    /** No drag of the pager from the normal row: both directions are swipes there. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mPagingBlocked) return false;
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mPagingBlocked) return false;
        return super.onTouchEvent(event);
    }

    /** Releases the pressed key and the pager's drag, as if the touch had been taken away from them. */
    private void cancelTouchOfChildren(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }



    /* The followed swipe. */

    private void startFollow() {
        stopRelease();
        Direction direction = mSwipeDetector.getDirection();
        mFollowDirection = direction;
        mSwipeLabel = mListener == null || direction == null || !mSwipeDetector.hasSwipe()
            ? null : mListener.getSwipeLabel(direction, false);
        mLongLabel = mListener == null || direction == null || !mSwipeDetector.hasLongSwipe()
            ? null : mListener.getSwipeLabel(direction, true);
        mPending = Result.NONE;
        mAnimateFollow = mListener != null && mListener.isFeedbackEnabled() && areSystemAnimationsEnabled();
        updateFollow();
    }

    private void updateFollow() {
        if (mFollowDirection == null) return;
        Result pending = mSwipeDetector.getPending();
        if (pending != mPending && pending != Result.NONE)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        mPending = pending;
        float progress = Math.min(1f, mSwipeDetector.getDistance() / mSwipeDetector.getMinDistance());
        float translation = mAnimateFollow ? MAX_FOLLOW_DP * mDensity * progress : 0f;
        setPagesTranslationX(mFollowDirection.sign() * translation);
        if (mListener != null) {
            String label = pending == Result.LONG_SWIPE || mSwipeLabel == null ? mLongLabel : mSwipeLabel;
            mListener.onSwipePreview(label, pending != Result.NONE);
        }
    }

    private void finishFollow(@NonNull Result result) {
        Direction direction = mFollowDirection;
        clearFollow();
        startRelease();
        if (direction != null && mListener != null && (result == Result.SWIPE || result == Result.LONG_SWIPE))
            mListener.onSwipe(direction, result == Result.LONG_SWIPE);
    }

    /** Settles the row from its release position. */
    private void startRelease() {
        ValueAnimator previous = mReleaseAnimator;
        mReleaseAnimator = null;
        if (previous != null) previous.cancel();

        View page = currentPage();
        final float startTranslation = page == null ? 0f : page.getTranslationX();
        if (startTranslation == 0f || !areSystemAnimationsEnabled()) {
            setPagesTranslationX(0f);
            return;
        }

        final ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(RELEASE_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> {
            float remaining = (float) a.getAnimatedValue();
            setPagesTranslationX(startTranslation * remaining);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (mReleaseAnimator == animator) {
                    mReleaseAnimator = null;
                    setPagesTranslationX(0f);
                }
            }
        });
        mReleaseAnimator = animator;
        animator.start();
    }

    private void stopRelease() {
        ValueAnimator animator = mReleaseAnimator;
        mReleaseAnimator = null;
        if (animator != null) animator.cancel();
        clearFollow();
        setPagesTranslationX(0f);
    }

    private void clearFollow() {
        if (mFollowDirection != null && mListener != null) mListener.onSwipePreview(null, false);
        mFollowDirection = null;
        mSwipeLabel = null;
        mLongLabel = null;
        mPending = Result.NONE;
    }

    /**
     * Slides only the page that is shown. The other pages, like the text input right of the extra
     * keys, stay where the pager put them, off screen: moving them along would pull them into view.
     */
    private void setPagesTranslationX(float translationX) {
        View shown = currentPage();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float wanted = child == shown ? translationX : 0f;
            if (child.getTranslationX() != wanted) child.setTranslationX(wanted);
        }
    }

    /** The page that fills the pager at its current scroll position, or null. */
    @Nullable
    private View currentPage() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getLeft() == getScrollX()) return child;
        }
        return null;
    }

    private boolean areSystemAnimationsEnabled() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    /** The running snap back or null, for tests. */
    ValueAnimator getReleaseAnimator() {
        return mReleaseAnimator;
    }

    /** Whether a swipe preview is active, for tests. */
    boolean isPreviewActive() {
        return mFollowDirection != null;
    }

    /** What releasing now would do, for tests. */
    @NonNull
    Result getPreviewPending() {
        return mPending;
    }

    @Override
    protected void onDetachedFromWindow() {
        mSwipeDetector.cancel();
        stopRelease();
        super.onDetachedFromWindow();
    }

}
