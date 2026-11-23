package com.termux.app.terminal;

import android.graphics.Rect;
import android.view.Gravity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class GestureFeedbackPositionTest {
    @Test public void positionsPillAtAllNineLocations() {
        int[] horizontal = {Gravity.LEFT, Gravity.CENTER_HORIZONTAL, Gravity.RIGHT};
        int[] vertical = {Gravity.TOP, Gravity.CENTER_VERTICAL, Gravity.BOTTOM};
        int[] xs = {10, 150, 290};
        int[] ys = {10, 230, 450};
        for (int row = 0; row < vertical.length; row++) {
            for (int col = 0; col < horizontal.length; col++) {
                Rect bounds = GestureFeedback.positionBounds(400, 500, 100, 40, 10,
                    horizontal[col] | vertical[row]);
                assertEquals(new Rect(xs[col], ys[row], xs[col] + 100, ys[row] + 40), bounds);
            }
        }
    }

    @Test public void largeInsetsDoNotPushPillOutsideSmallViewport() {
        assertEquals(new Rect(2, 2, 98, 38), GestureFeedback.positionBounds(100, 40, 96, 36, 10,
            Gravity.TOP | Gravity.RIGHT));
    }
}
