package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Adapter;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * The sidebar session list. Besides scrolling and choosing a session it lets the user reorder the sessions:
 * a row is picked up by holding its number (the row calls {@link #beginDrag}). From then on the row is drawn
 * under the finger, lifted above the others, wherever the finger goes inside the list. The sessions are
 * reordered each time the dragged row passes the middle of a neighbour, so the other rows make room as it
 * goes, and the list scrolls while the finger is held near its top or bottom. Releasing the finger lets the row
 * settle into its place. A gesture that is cancelled, or a drag that is interrupted (the drawer closes, the
 * window loses focus, the list is detached), puts the row back where it was picked up.
 */
public class SessionsListView extends ListView {

    /** Receives the reordering. */
    public interface ReorderListener {
        /**
         * The dragged row passed a neighbour, or a cancelled drag puts it back.
         *
         * @return Whether the sessions were reordered. The list then rebinds its rows.
         */
        boolean onMove(int from, int to);

        /** The drag started ({@code dragging}) or ended, at the given position. */
        void onDragChanged(int position, boolean dragging);
    }

    private static final int AUTO_SCROLL_EDGE_DP = 56;
    private static final int AUTO_SCROLL_MAX_STEP_DP = 12;

    /** How the row being dragged stands out from the others. */
    private static final float DRAGGED_ROW_SCALE = 1.03f;
    private static final float DRAGGED_ROW_ALPHA = 0.92f;
    private static final float DRAGGED_ROW_ELEVATION_DP = 8f;
    private static final long LIFT_DURATION_MS = 120;
    private static final long SETTLE_DURATION_MS = 160;

    @Nullable private ReorderListener mReorderListener;

    /** A flag of its own: the view can be called before its fields are initialised, with a position of 0. */
    private boolean mDragging;
    private int mDragPosition = INVALID_POSITION;
    /** The item of the dragged row, so that the drag survives the list changing underneath it. */
    @Nullable private Object mDragItem;
    private int mStartPosition = INVALID_POSITION;
    /** How far below the top of the row the finger grabbed it. */
    private float mGrabOffset;
    private float mLastY;
    private int mPointerId;
    /** Set once the dragged row moved and cleared by the next layout, which is what places the rows anew. */
    private boolean mAwaitingLayout;

    /** The row that settles into place after the finger was lifted, and how far from its place it was. */
    private int mSettlePosition = INVALID_POSITION;
    private float mSettleOffset;
    /** The lift the settling began at, which the offset fades out from. */
    private float mSettleStartLift;
    /** How far the dragged (or settling) row is lifted: 0 is flat in the list, 1 is fully picked up. */
    private float mLift;
    @Nullable private ValueAnimator mLiftAnimator;

    private final Runnable mAutoScroll = this::autoScroll;

    public SessionsListView(Context context) {
        super(context);
    }

    public SessionsListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SessionsListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setReorderListener(@Nullable ReorderListener listener) {
        mReorderListener = listener;
    }

    public boolean isDragging() {
        return mDragging;
    }

    /** The position of the row being dragged, or {@link #INVALID_POSITION}. */
    public int getDragPosition() {
        return mDragPosition;
    }

    /**
     * Pick up the row at a position. The finger is expected to be down, so the rest of its gesture drives the
     * drag.
     *
     * @return Whether a drag started: there must be a listener, something to swap with, and the row on screen.
     */
    public boolean beginDrag(int position) {
        return beginDrag(position, mLastY);
    }

    /**
     * Pick up the row at a position, with the finger at the given vertical position of the list.
     *
     * @see #beginDrag(int)
     */
    public boolean beginDrag(int position, float fingerY) {
        Adapter adapter = getAdapter();
        if (mReorderListener == null || adapter == null || adapter.getCount() < 2) return false;
        if (position < 0 || position >= adapter.getCount()) return false;
        View row = getRowView(position);
        if (row == null) return false;
        if (isDragging()) endDrag(true);
        stopLiftAnimation();
        finishSettle();

        mLastY = fingerY;
        mGrabOffset = Math.max(0f, Math.min(row.getHeight(), fingerY - row.getTop()));
        mDragging = true;
        mDragPosition = position;
        mStartPosition = position;
        mDragItem = adapter.getItem(position);
        mAwaitingLayout = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        animateLift(0f, 1f, LIFT_DURATION_MS, null);
        mReorderListener.onDragChanged(position, true);
        styleChildren();
        post(mAutoScroll);
        return true;
    }

    /**
     * End the drag.
     *
     * @param cancel Put the row back where it was picked up, instead of leaving it where it was dropped.
     */
    private void endDrag(boolean cancel) {
        if (!isDragging()) return;
        removeCallbacks(mAutoScroll);
        int position = mDragPosition;
        View row = getRowView(position);
        float offset = row == null ? 0f : row.getTranslationY();

        Adapter adapter = getAdapter();
        if (cancel && mReorderListener != null && adapter != null && adapter.getCount() > 0) {
            int back = Math.min(mStartPosition, adapter.getCount() - 1);
            if (back != position && mReorderListener.onMove(position, back)) {
                position = back;
                offset = 0f;
            }
        }

        mDragging = false;
        mDragPosition = INVALID_POSITION;
        mDragItem = null;
        mStartPosition = INVALID_POSITION;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);

        // The row lets go of the finger and glides into its place while the rest of the list is already still.
        stopLiftAnimation();
        mSettlePosition = position;
        mSettleOffset = offset;
        mSettleStartLift = mLift;
        if (isAttachedToWindow() && mLift > 0f) {
            animateLift(mLift, 0f, SETTLE_DURATION_MS, new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finishSettle();
                }
            });
        } else {
            finishSettle();
        }

        if (mReorderListener != null) mReorderListener.onDragChanged(position, false);
        styleChildren();
    }

    private void animateLift(float from, float to, long duration, @Nullable Animator.AnimatorListener listener) {
        stopLiftAnimation();
        mLift = from;
        if (!isAttachedToWindow()) {
            mLift = to;
            if (listener != null) listener.onAnimationEnd(null);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        animator.addUpdateListener(a -> {
            mLift = (Float) a.getAnimatedValue();
            styleChildren();
        });
        if (listener != null) animator.addListener(listener);
        mLiftAnimator = animator;
        animator.start();
    }

    private void stopLiftAnimation() {
        ValueAnimator animator = mLiftAnimator;
        mLiftAnimator = null;
        if (animator == null) return;
        animator.removeAllListeners();
        animator.removeAllUpdateListeners();
        animator.cancel();
    }

    private void finishSettle() {
        mLiftAnimator = null;
        if (mSettlePosition == INVALID_POSITION && !isDragging()) return;
        if (!isDragging()) mLift = 0f;
        mSettlePosition = INVALID_POSITION;
        mSettleOffset = 0f;
        styleChildren();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // A new gesture while a drag is still open means the end of the last one got lost.
            if (isDragging()) endDrag(true);
            mPointerId = ev.getPointerId(0);
        }
        if (!isDragging()) {
            int index = ev.findPointerIndex(mPointerId);
            mLastY = index >= 0 ? ev.getY(index) : ev.getY();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // The row's number started the drag from a long press, so the gesture still belongs to that child.
        if (isDragging()) return true;
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isDragging()) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int index = ev.findPointerIndex(mPointerId);
                if (index < 0) {
                    endDrag(true);
                    break;
                }
                mLastY = ev.getY(index);
                moveDraggedRow();
                styleChildren();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                // The finger that holds the row was lifted while another one stayed down.
                if (ev.getPointerId(ev.getActionIndex()) == mPointerId) endDrag(false);
                break;
            case MotionEvent.ACTION_UP:
                endDrag(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                endDrag(true);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        mAwaitingLayout = false;
        if (isDragging()) syncDragPosition();
        styleChildren();
    }

    @Override
    protected void dispatchDraw(@NonNull android.graphics.Canvas canvas) {
        // Scrolling moves the rows without laying them out, and the dragged one has to stay under the finger.
        if (isDragging() || mSettlePosition != INVALID_POSITION) styleChildren();
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) endDrag(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) endDrag(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        endDrag(true);
        stopLiftAnimation();
        finishSettle();
        removeCallbacks(mAutoScroll);
        super.onDetachedFromWindow();
    }

    /**
     * Find the dragged row again after the sessions changed under it. If it is gone, or fewer than two rows
     * are left, the drag is over.
     */
    private boolean syncDragPosition() {
        Adapter adapter = getAdapter();
        int count = adapter == null ? 0 : adapter.getCount();
        if (count < 2) {
            endDrag(false);
            return false;
        }
        if (mDragPosition < count && Objects.equals(adapter.getItem(mDragPosition), mDragItem)) return true;
        for (int i = 0; i < count; i++) {
            if (Objects.equals(adapter.getItem(i), mDragItem)) {
                mDragPosition = i;
                return true;
            }
        }
        endDrag(false);
        return false;
    }

    /** Move the dragged row one step if its middle passed the middle of the row above or below it. */
    private void moveDraggedRow() {
        if (!isDragging() || mAwaitingLayout || mReorderListener == null || !syncDragPosition()) return;
        View row = getRowView(mDragPosition);
        if (row == null) return;
        int first = getFirstVisiblePosition();
        int count = getAdapter().getCount();
        int center = SessionReorder.draggedTop(mLastY, mGrabOffset, row.getHeight(), getHeight()) + row.getHeight() / 2;

        View previous = mDragPosition > 0 ? getChildAt(mDragPosition - 1 - first) : null;
        View next = mDragPosition < count - 1 ? getChildAt(mDragPosition + 1 - first) : null;
        int target = SessionReorder.dragTarget(mDragPosition, count, center,
            previous == null ? Integer.MIN_VALUE : previous.getTop(), previous == null ? 0 : previous.getHeight(),
            next == null ? Integer.MIN_VALUE : next.getTop(), next == null ? 0 : next.getHeight());
        if (target == mDragPosition) return;

        if (mReorderListener.onMove(mDragPosition, target)) {
            mDragPosition = target;
            mAwaitingLayout = true;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /** Every frame of a drag: scroll while the finger is held near an edge, then look for a neighbour to pass. */
    private void autoScroll() {
        if (!isDragging()) return;
        float density = getResources().getDisplayMetrics().density;
        int step = SessionReorder.autoScrollStep(mLastY, getHeight(),
            AUTO_SCROLL_EDGE_DP * density, AUTO_SCROLL_MAX_STEP_DP * density);
        if (step != 0 && !mAwaitingLayout && canScrollList(step)) scrollListBy(step);
        if (!isDragging()) return;
        styleChildren();
        moveDraggedRow();
        postOnAnimation(mAutoScroll);
    }

    /**
     * Draw the dragged row under the finger and lifted, the settling row on its way into place, and every other
     * row flat. The rows are recycled, so each visible one is set explicitly.
     */
    private void styleChildren() {
        if (mAwaitingLayout) return;
        int lifted = isDragging() ? mDragPosition : mSettlePosition;
        int first = getFirstVisiblePosition();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (first + i != lifted) {
                resetRowStyle(child);
                continue;
            }
            float translationY = isDragging()
                ? SessionReorder.draggedTop(mLastY, mGrabOffset, child.getHeight(), getHeight()) - child.getTop()
                : mSettleStartLift > 0f ? mSettleOffset * Math.min(1f, mLift / mSettleStartLift) : 0f;
            child.setScaleX(1f + (DRAGGED_ROW_SCALE - 1f) * mLift);
            child.setScaleY(1f + (DRAGGED_ROW_SCALE - 1f) * mLift);
            child.setAlpha(1f - (1f - DRAGGED_ROW_ALPHA) * mLift);
            child.setTranslationZ(DRAGGED_ROW_ELEVATION_DP * density * mLift);
            child.setTranslationY(translationY);
        }
    }

    /** Put a row back to how it looks when it is not being dragged, as rows are reused for other sessions. */
    public static void resetRowStyle(@NonNull View row) {
        row.setScaleX(1f);
        row.setScaleY(1f);
        row.setAlpha(1f);
        row.setTranslationZ(0f);
        row.setTranslationY(0f);
    }

    /** The row view of a position, or null if it is not on screen. */
    @Nullable
    public View getRowView(int position) {
        return getChildAt(position - getFirstVisiblePosition());
    }

}
