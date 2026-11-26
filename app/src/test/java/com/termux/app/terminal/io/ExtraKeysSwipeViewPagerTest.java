package com.termux.app.terminal.io;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeyButton;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ExtraKeysSwipeViewPagerTest {

    /** Stands for an extra key: takes every touch and remembers what it was given. */
    private static final class RecordingView extends View {
        final List<Integer> actions = new ArrayList<>();

        RecordingView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            actions.add(event.getActionMasked());
            return true;
        }
    }

    private static final class TwoPageAdapter extends PagerAdapter {
        final RecordingView[] views;

        TwoPageAdapter(Context context) {
            views = new RecordingView[] {new RecordingView(context), new RecordingView(context)};
        }

        @Override public int getCount() { return 2; }
        @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            container.addView(views[position]);
            return views[position];
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    private ExtraKeysSwipeViewPager mPager;
    private TwoPageAdapter mAdapter;
    private int mSwipes;
    private final List<String> mEvents = new ArrayList<>();
    private long mDownTime;

    @Before
    public void setUp() {
        // A ViewPager only populates its pages once it is attached to a window.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Context context = activity;
        mPager = new ExtraKeysSwipeViewPager(context);
        mAdapter = new TwoPageAdapter(context);
        mPager.setAdapter(mAdapter);
        mSwipes = 0;
        mEvents.clear();
        mPager.setListener((direction, longSwipe) -> {
            mSwipes++;
            mEvents.add((longSwipe ? "long-" : "") + direction.name().toLowerCase(java.util.Locale.ROOT));
        });
        activity.setContentView(mPager, new ViewGroup.LayoutParams(1000, 150));
        layout();
    }

    private void layout() {
        mPager.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY));
        mPager.layout(0, 0, 1000, 150);
    }

    private boolean send(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) mDownTime = now;
        MotionEvent event = MotionEvent.obtain(mDownTime, now, action, x, y, 0);
        try {
            return mPager.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private float swipe() {
        return mPager.getSwipeDistance();
    }

    private float longSwipe() {
        return mPager.getLongSwipeDistance();
    }

    /** Drag from fromX to toX and release there. */
    private void drag(float fromX, float toX) {
        send(MotionEvent.ACTION_DOWN, fromX, 75);
        send(MotionEvent.ACTION_MOVE, fromX + Math.signum(toX - fromX) * 30, 75);
        send(MotionEvent.ACTION_MOVE, toX, 75);
        send(MotionEvent.ACTION_UP, toX, 75);
    }

    private void useLongSwipes() {
        mPager.setListener(new ExtraKeysSwipeViewPager.Listener() {
            @Override public boolean hasLongSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) { return true; }
            @Override public String getSwipeLabel(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                return longSwipe ? "New session" : "Next session";
            }
            @Override public void onSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                mSwipes++;
                mEvents.add((longSwipe ? "long-" : "") + direction.name().toLowerCase(java.util.Locale.ROOT));
            }
        });
    }

    private void assertRestingAtZero() {
        for (int i = 0; i < mPager.getChildCount(); i++)
            assertEquals(0f, mPager.getChildAt(i).getTranslationX(), 0f);
    }

    @Test
    public void keyFlingReservesOnlyItsPhysicalDirection() throws Exception {
        Context context = new ContextThemeWrapper(mPager.getContext(), R.style.Theme_TermuxApp_DayNight_NoActionBar);
        ExtraKeysView keys = new ExtraKeysView(context, null);
        keys.setId(R.id.terminal_toolbar_extra_keys);
        keys.reload(new ExtraKeysInfo("[[{key:'RIGHT','fling-right':'END'}]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES), 150);
        List<String> pressed = new ArrayList<>();
        keys.setExtraKeysViewClient(new ExtraKeysView.IExtraKeysView() {
            @Override public void onExtraKeyButtonClick(View view, ExtraKeyButton key, MaterialButton button) {
                pressed.add(key.getKey());
            }

            @Override public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton key,
                                                                          MaterialButton button) {
                return true;
            }
        });
        mPager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return 1; }

            @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object item) {
                return view == item;
            }

            @NonNull @Override public Object instantiateItem(@NonNull ViewGroup parent, int position) {
                parent.addView(keys);
                return keys;
            }

            @Override public void destroyItem(@NonNull ViewGroup parent, int position, @NonNull Object item) {
                parent.removeView((View) item);
            }
        });
        layout();

        send(MotionEvent.ACTION_DOWN, 400, 75);
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS);
        send(MotionEvent.ACTION_MOVE, 520, 75);
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS);
        send(MotionEvent.ACTION_UP, 640, 75);
        assertEquals(0, mSwipes);
        assertEquals(List.of("END"), pressed);

        drag(600, 350);
        assertEquals(List.of("right"), mEvents);
        assertEquals(List.of("END"), pressed);
    }

    @Test
    public void distancesScaleWithTheRow() {
        // 1000 px wide at density 1: a fifth of the row, and half of it.
        assertEquals(200f, swipe(), 0.5f);
        assertEquals(500f, longSwipe(), 0.5f);
    }

    @Test
    public void swipeRunsOnReleaseAndCancelsTheKey() {
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_MOVE, 130, 76);
        send(MotionEvent.ACTION_MOVE, 100 + swipe() + 10, 78);
        assertEquals("nothing runs before the release", 0, mSwipes);
        assertTrue("the row follows the finger", mAdapter.views[0].getTranslationX() > 0f);
        send(MotionEvent.ACTION_UP, 100 + swipe() + 10, 78);
        assertEquals(List.of("left"), mEvents);

        // The key saw the press and then a cancel, never a release, so it presses nothing.
        List<Integer> seen = mAdapter.views[0].actions;
        assertEquals(MotionEvent.ACTION_DOWN, (int) seen.get(0));
        assertEquals(MotionEvent.ACTION_CANCEL, (int) seen.get(seen.size() - 1));
        assertFalse(seen.contains(MotionEvent.ACTION_UP));
        assertEquals(0, mPager.getCurrentItem());
    }

    @Test
    public void rightSwipeDoesNotPageToTheTextInput() {
        drag(900, 900 - swipe() - 10);
        assertEquals(List.of("right"), mEvents);
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertEquals(0, mPager.getCurrentItem());
    }

    @Test
    public void tooShortASwipeIsCalledOff() {
        drag(100, 100 + swipe() - 20);
        assertEquals(0, mSwipes);
    }

    @Test
    public void tapIsLeftToTheKey() {
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_UP, 100, 75);
        assertEquals(0, mSwipes);
        List<Integer> seen = mAdapter.views[0].actions;
        assertTrue(seen.contains(MotionEvent.ACTION_DOWN));
        assertTrue(seen.contains(MotionEvent.ACTION_UP));
        assertFalse(seen.contains(MotionEvent.ACTION_CANCEL));
    }

    @Test
    public void verticalDragIsLeftToTheKey() {
        send(MotionEvent.ACTION_DOWN, 100, 10);
        send(MotionEvent.ACTION_MOVE, 120, 140);
        send(MotionEvent.ACTION_UP, 120, 140);
        assertEquals(0, mSwipes);
    }

    @Test
    public void noSwipeOnTheTextInputPage() {
        mPager.setCurrentItem(1, false);
        layout();
        drag(100, 100 + longSwipe());
        assertEquals(0, mSwipes);
    }

    @Test
    public void withoutAnActionTheTouchIsLeftAlone() {
        mPager.setListener(new ExtraKeysSwipeViewPager.Listener() {
            @Override public boolean hasSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) { return false; }
            @Override public void onSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) { mSwipes++; }
        });
        drag(800, 100);
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertEquals(0, mSwipes);
        assertFalse(mPager.isPreviewActive());
        assertEquals(0, mPager.getCurrentItem());
        assertEquals(MotionEvent.ACTION_DOWN, (int) mAdapter.views[0].actions.get(0));
    }

    @Test
    public void thePreviewTellsWhatReleasingRuns() {
        useLongSwipes();
        send(MotionEvent.ACTION_DOWN, 50, 75);
        send(MotionEvent.ACTION_MOVE, 80, 75);
        assertTrue(mPager.isPreviewActive());
        assertSame(ExtraKeysSwipeDetector.Result.NONE, mPager.getPreviewPending());
        send(MotionEvent.ACTION_MOVE, 50 + swipe() + 10, 75);
        assertSame(ExtraKeysSwipeDetector.Result.SWIPE, mPager.getPreviewPending());
        send(MotionEvent.ACTION_MOVE, 50 + longSwipe() + 10, 75);
        assertSame(ExtraKeysSwipeDetector.Result.LONG_SWIPE, mPager.getPreviewPending());
        assertEquals("the row moves only as far as the action cue needs",
            ExtraKeysSwipeViewPager.MAX_FOLLOW_DP * mPager.getResources().getDisplayMetrics().density,
            mAdapter.views[0].getTranslationX(), 1f);
        send(MotionEvent.ACTION_UP, 50 + longSwipe() + 10, 75);
        assertEquals(List.of("long-left"), mEvents);
    }

    @Test
    public void onlyTheShownRowSlidesNotTheTextInput() {
        useLongSwipes();
        send(MotionEvent.ACTION_DOWN, 900, 75);
        send(MotionEvent.ACTION_MOVE, 870, 75);
        send(MotionEvent.ACTION_MOVE, 900 - swipe() - 30, 75);
        assertTrue("the row follows the finger to the left", mAdapter.views[0].getTranslationX() < 0f);
        assertEquals("the text input page stays off screen", 0f, mAdapter.views[1].getTranslationX(), 0f);
        send(MotionEvent.ACTION_UP, 900 - swipe() - 30, 75);
        assertEquals(List.of("right"), mEvents);
    }

    @Test
    public void releaseSnapsBackToRest() {
        drag(100, 100 + swipe() + 10);
        android.animation.ValueAnimator animator = mPager.getReleaseAnimator();
        assertTrue("snapping back", animator != null);
        animator.end();
        assertFalse(mPager.isPreviewActive());
        assertRestingAtZero();
    }

    @Test
    public void cancelOrSecondFingerCallsTheSwipeOff() {
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_MOVE, 130, 75);
        send(MotionEvent.ACTION_MOVE, 100 + swipe() + 10, 75);
        send(MotionEvent.ACTION_CANCEL, 100 + swipe() + 10, 75);
        assertEquals(0, mSwipes);
    }

    @Test
    public void withoutSystemAnimationsTheRowIsAtRestAtOnce() {
        Settings.Global.putFloat(mPager.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        drag(100, 100 + swipe() + 10);
        assertEquals("the action still runs", 1, mSwipes);
        assertFalse(mPager.isPreviewActive());
        assertRestingAtZero();
    }

    @Test
    public void goingAwayMidSwipeLeavesTheRowAtRest() {
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_MOVE, 130, 75);
        send(MotionEvent.ACTION_MOVE, 100 + swipe(), 75);
        ((ViewGroup) mPager.getParent()).removeView(mPager);
        assertFalse(mPager.isPreviewActive());
        assertRestingAtZero();
    }

}
