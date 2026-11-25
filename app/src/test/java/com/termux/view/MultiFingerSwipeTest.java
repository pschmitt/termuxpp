package com.termux.view;

import android.view.InputDevice;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MultiFingerSwipeTest {
    @Test
    public void recognizesFourDirectionsForTwoAndThreeFingers() {
        for (int count : new int[] {2, 3}) {
            assertDirection(MultiFingerSwipe.Direction.UP, count, 1, -80);
            assertDirection(MultiFingerSwipe.Direction.DOWN, count, -4, 80);
            assertDirection(MultiFingerSwipe.Direction.LEFT, count, -80, 4);
            assertDirection(MultiFingerSwipe.Direction.RIGHT, count, 80, -4);
        }
    }

    @Test
    public void rejectsPinchesDiagonalDragsAndStationaryFingers() {
        assertNull(MultiFingerSwipe.classify(new float[] {100, -10}, new float[] {0, 0}, 2, 40));
        assertNull(MultiFingerSwipe.classify(new float[] {80, 80}, new float[] {65, 65}, 2, 40));
        assertNull(MultiFingerSwipe.classify(new float[] {80, 80, 0}, new float[] {0, 0, 0}, 3, 40));
        assertNull(MultiFingerSwipe.classify(new float[] {20, 20}, new float[] {0, 0}, 2, 40));
    }

    @Test
    public void threeFingersOnlyTriggerOnceEvenWhenPointersAreReordered() {
        MultiFingerSwipe swipe = new MultiFingerSwipe(40);
        feed(swipe, MotionEvent.ACTION_DOWN, new int[] {0}, 0);
        feed(swipe, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[] {0, 1}, 0);
        feed(swipe, MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[] {0, 1, 2}, 0);
        assertNull(feed(swipe, MotionEvent.ACTION_MOVE, new int[] {0, 1, 2}, 80));
        assertEquals(MultiFingerSwipe.Direction.RIGHT, feed(swipe, MotionEvent.ACTION_POINTER_UP,
            new int[] {1, 0, 2}, 80));
        assertNull(feed(swipe, MotionEvent.ACTION_POINTER_UP, new int[] {0, 2}, 120));
    }

    @Test
    public void addingAFourthFingerCancelsTheGesture() {
        MultiFingerSwipe swipe = new MultiFingerSwipe(40);
        feed(swipe, MotionEvent.ACTION_DOWN, new int[] {0}, 0);
        feed(swipe, MotionEvent.ACTION_POINTER_DOWN, new int[] {0, 1}, 0);
        feed(swipe, MotionEvent.ACTION_POINTER_DOWN, new int[] {0, 1, 2}, 0);
        feed(swipe, MotionEvent.ACTION_POINTER_DOWN, new int[] {0, 1, 2, 3}, 0);
        assertNull(feed(swipe, MotionEvent.ACTION_POINTER_UP, new int[] {0, 1, 2, 3}, 80));
        assertNull(feed(swipe, MotionEvent.ACTION_POINTER_UP, new int[] {0, 1, 2}, 80));
    }

    private static MultiFingerSwipe.Direction feed(MultiFingerSwipe swipe, int action, int[] ids, float dx) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 100 + dx;
            coords[i].y = 100 + ids[i] * 50;
        }
        MotionEvent event = MotionEvent.obtain(0, 50, action, ids.length, properties, coords,
            0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            return swipe.observe(event);
        } finally {
            event.recycle();
        }
    }

    private static void assertDirection(MultiFingerSwipe.Direction direction, int count, float x, float y) {
        assertEquals(direction, MultiFingerSwipe.classify(
            new float[] {x, x + 3, x - 3}, new float[] {y, y - 3, y + 3}, count, 40));
    }
}
