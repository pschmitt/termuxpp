package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SessionReorderTest {

    private static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    public void moveShiftsTheSessionsInBetween() {
        List<String> sessions = list("a", "b", "c", "d");
        Assert.assertTrue(SessionReorder.move(sessions, 0, 2));
        Assert.assertEquals(Arrays.asList("b", "c", "a", "d"), sessions);
        Assert.assertTrue(SessionReorder.move(sessions, 3, 1));
        Assert.assertEquals(Arrays.asList("b", "d", "c", "a"), sessions);
    }

    @Test
    public void moveIgnoresNoopsAndOutOfRangeIndexes() {
        List<String> sessions = list("a", "b", "c");
        Assert.assertFalse(SessionReorder.move(sessions, 1, 1));
        Assert.assertFalse(SessionReorder.move(sessions, -1, 1));
        Assert.assertFalse(SessionReorder.move(sessions, 0, 3));
        Assert.assertFalse(SessionReorder.move(sessions, 3, 0));
        Assert.assertFalse(SessionReorder.move(Collections.<String>emptyList(), 0, 0));
        Assert.assertEquals(Arrays.asList("a", "b", "c"), sessions);
    }

    @Test
    public void targetForClampsToTheList() {
        Assert.assertEquals(1, SessionReorder.targetFor(SessionReorder.Move.UP, 2, 4));
        Assert.assertEquals(0, SessionReorder.targetFor(SessionReorder.Move.UP, 0, 4));
        Assert.assertEquals(3, SessionReorder.targetFor(SessionReorder.Move.DOWN, 2, 4));
        Assert.assertEquals(3, SessionReorder.targetFor(SessionReorder.Move.DOWN, 3, 4));
        Assert.assertEquals(0, SessionReorder.targetFor(SessionReorder.Move.TOP, 2, 4));
        Assert.assertEquals(3, SessionReorder.targetFor(SessionReorder.Move.BOTTOM, 1, 4));
        Assert.assertEquals(2, SessionReorder.targetFor(SessionReorder.Move.BOTTOM, 2, 0));
    }

    @Test
    public void theDraggedRowIsDrawnUnderTheFingerButInsideTheList() {
        Assert.assertEquals(70, SessionReorder.draggedTop(120f, 50f, 100, 1000));
        Assert.assertEquals(0, SessionReorder.draggedTop(20f, 50f, 100, 1000));
        Assert.assertEquals(900, SessionReorder.draggedTop(5000f, 50f, 100, 1000));
        // A list shorter than the row keeps the row at the top.
        Assert.assertEquals(0, SessionReorder.draggedTop(300f, 10f, 100, 60));
    }

    @Test
    public void autoScrollOnlyHappensNearAnEdgeAndGetsFasterTowardsIt() {
        Assert.assertEquals(0, SessionReorder.autoScrollStep(500f, 1000, 100f, 20f));
        Assert.assertEquals(0, SessionReorder.autoScrollStep(100f, 1000, 100f, 20f));
        Assert.assertEquals(-10, SessionReorder.autoScrollStep(50f, 1000, 100f, 20f));
        Assert.assertEquals(-20, SessionReorder.autoScrollStep(0f, 1000, 100f, 20f));
        Assert.assertEquals(-20, SessionReorder.autoScrollStep(-300f, 1000, 100f, 20f));
        Assert.assertEquals(10, SessionReorder.autoScrollStep(950f, 1000, 100f, 20f));
        Assert.assertEquals(20, SessionReorder.autoScrollStep(1400f, 1000, 100f, 20f));
    }

    @Test
    public void autoScrollNeverGoesBothWaysInAShortList() {
        // Both edges overlap: the nearer one wins.
        Assert.assertEquals(-Math.round(20f * 0.5f), SessionReorder.autoScrollStep(50f, 150, 100f, 20f));
        Assert.assertEquals(Math.round(20f * 0.5f), SessionReorder.autoScrollStep(100f, 150, 100f, 20f));
        Assert.assertEquals(0, SessionReorder.autoScrollStep(10f, 0, 100f, 20f));
        Assert.assertEquals(0, SessionReorder.autoScrollStep(10f, 1000, 0f, 20f));
    }

    private static final int NONE = Integer.MIN_VALUE;

    @Test
    public void dragMovesOnlyOncePastTheMiddleOfANeighbour() {
        // Rows of 100px: the dragged row 1 sits at 100..200, row 0 at 0..100 and row 2 at 200..300.
        Assert.assertEquals(1, SessionReorder.dragTarget(1, 3, 150, 0, 100, 200, 100));
        Assert.assertEquals(1, SessionReorder.dragTarget(1, 3, 250, 0, 100, 200, 100));
        Assert.assertEquals(2, SessionReorder.dragTarget(1, 3, 251, 0, 100, 200, 100));
        Assert.assertEquals(1, SessionReorder.dragTarget(1, 3, 50, 0, 100, 200, 100));
        Assert.assertEquals(0, SessionReorder.dragTarget(1, 3, 49, 0, 100, 200, 100));
    }

    @Test
    public void dragStaysInsideTheListAndOnLaidOutRows() {
        Assert.assertEquals(0, SessionReorder.dragTarget(0, 3, -500, NONE, 0, 100, 100));
        Assert.assertEquals(2, SessionReorder.dragTarget(2, 3, 5000, 100, 100, NONE, 0));
        // A neighbour that is not laid out (scrolled away) is not a target.
        Assert.assertEquals(1, SessionReorder.dragTarget(1, 3, 5000, NONE, 0, NONE, 0));
        Assert.assertEquals(5, SessionReorder.dragTarget(5, 3, 0, 0, 100, 200, 100));
    }

    @Test
    public void dragDoesNotSwapBackAfterMovingPastATallerNeighbour() {
        // A short row (40px) dragged down past a tall one (120px). Before: short 0..40, tall 40..160.
        int finger = 40 + 60 + 1;
        Assert.assertEquals(1, SessionReorder.dragTarget(0, 2, finger, NONE, 0, 40, 120));
        // After the swap: tall 0..120, short 120..160. The finger is within the short row, which is the dragged
        // row, and does not pass the middle of the row above it.
        Assert.assertEquals(1, SessionReorder.dragTarget(1, 2, finger, 0, 120, NONE, 0));
    }

}
