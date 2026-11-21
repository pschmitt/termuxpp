package com.termux.app.terminal;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class SessionsListViewTest {

    private static final int ROW_HEIGHT = 100;
    private static final int LIST_HEIGHT = 1000;

    private static final class Recorder implements SessionsListView.ReorderListener {
        final List<int[]> moves = new ArrayList<>();
        final List<Boolean> drags = new ArrayList<>();
        final List<String> items;
        final ArrayAdapter<String> adapter;

        Recorder(List<String> items, ArrayAdapter<String> adapter) {
            this.items = items;
            this.adapter = adapter;
        }

        @Override
        public boolean onMove(int from, int to) {
            moves.add(new int[]{from, to});
            items.add(to, items.remove(from));
            adapter.notifyDataSetChanged();
            return true;
        }

        @Override
        public void onDragChanged(int position, boolean dragging) {
            drags.add(dragging);
        }
    }

    private static SessionsListView list(List<String> items, ArrayAdapter<String> adapter) {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        SessionsListView list = new SessionsListView(activity);
        // The themed divider would shift every row by a pixel; the rows are meant to be ROW_HEIGHT apart.
        list.setDividerHeight(0);
        list.setAdapter(adapter);
        list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(LIST_HEIGHT, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 400, LIST_HEIGHT);
        return list;
    }

    private static ArrayAdapter<String> adapter(Activity activity, List<String> items) {
        return new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = new TextView(getContext());
                view.setText(getItem(position));
                view.setMinimumHeight(ROW_HEIGHT);
                view.setHeight(ROW_HEIGHT);
                return view;
            }
        };
    }

    private static MotionEvent event(int action, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, 200f, y, 0);
    }

    @Test
    public void aSingleSessionCannotBeDragged() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        List<String> items = new ArrayList<>(Arrays.asList("a"));
        ArrayAdapter<String> adapter = adapter(activity, items);
        SessionsListView list = list(items, adapter);
        list.setReorderListener(new Recorder(items, adapter));

        Assert.assertFalse(list.beginDrag(0));
        Assert.assertFalse(list.isDragging());
    }

    @Test
    public void noListenerMeansNoDrag() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        List<String> items = new ArrayList<>(Arrays.asList("a", "b"));
        SessionsListView list = list(items, adapter(activity, items));

        Assert.assertFalse(list.beginDrag(0));
    }

    private static void relayout(SessionsListView list) {
        list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(LIST_HEIGHT, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 400, LIST_HEIGHT);
    }

    private static final class Setup {
        final List<String> items;
        final ArrayAdapter<String> adapter;
        final SessionsListView list;
        final Recorder recorder;

        Setup(String... names) {
            Activity activity = Robolectric.buildActivity(Activity.class).create().get();
            items = new ArrayList<>(Arrays.asList(names));
            adapter = adapter(activity, items);
            list = list(items, adapter);
            recorder = new Recorder(items, adapter);
            list.setReorderListener(recorder);
        }
    }

    @Test
    public void draggingMovesTheRowDownAndEndsOnRelease() {
        Setup t = new Setup("a", "b", "c");

        // The finger grabs row "a" (0..100) in the middle.
        Assert.assertTrue(t.list.beginDrag(0, 50f));
        Assert.assertTrue(t.list.isDragging());
        Assert.assertEquals(Arrays.asList(true), t.recorder.drags);

        // The row moves with the finger, so its middle (finger + 0) has to cross the middle of row "b" (150).
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 120f));
        Assert.assertTrue(t.recorder.moves.isEmpty());
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 160f));
        Assert.assertEquals(1, t.recorder.moves.size());
        Assert.assertArrayEquals(new int[]{0, 1}, t.recorder.moves.get(0));
        Assert.assertEquals(Arrays.asList("b", "a", "c"), t.items);
        Assert.assertEquals(1, t.list.getDragPosition());

        t.list.onTouchEvent(event(MotionEvent.ACTION_UP, 160f));
        Assert.assertFalse(t.list.isDragging());
        Assert.assertEquals(Arrays.asList(true, false), t.recorder.drags);
        Assert.assertEquals(Arrays.asList("b", "a", "c"), t.items);
    }

    @Test
    public void theGrabOffsetDecidesWhenTheRowMoves() {
        Setup t = new Setup("a", "b", "c");

        // Grabbed by its top edge: the row's middle is 50px below the finger, so 110 is already past 150.
        Assert.assertTrue(t.list.beginDrag(0, 0f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 90f));
        Assert.assertTrue(t.recorder.moves.isEmpty());
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 101f));
        Assert.assertEquals(1, t.recorder.moves.size());
    }

    @Test
    public void theDraggedRowFollowsTheFingerAndStandsOut() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));

        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 120f));
        View row = t.list.getRowView(0);
        Assert.assertEquals(70f, row.getTranslationY(), 0.5f);
        Assert.assertTrue(row.getScaleX() > 1f);
        Assert.assertTrue(row.getAlpha() < 1f);
        Assert.assertTrue(row.getTranslationZ() > 0f);

        // The others stay flat.
        Assert.assertEquals(0f, t.list.getRowView(1).getTranslationY(), 0f);
        Assert.assertEquals(1f, t.list.getRowView(1).getScaleX(), 0f);
    }

    @Test
    public void theRowStaysUnderTheFingerWhenTheRowsAreLaidOutAnew() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 160f));
        relayout(t.list);

        // "a" now sits at position 1 (100..200) but is drawn at 110, where the finger holds it.
        Assert.assertEquals(1, t.list.getDragPosition());
        Assert.assertEquals(10f, t.list.getRowView(1).getTranslationY(), 0.5f);
        Assert.assertEquals(0f, t.list.getRowView(0).getTranslationY(), 0f);

        // The row can move on once the layout is done.
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 260f));
        Assert.assertEquals(2, t.recorder.moves.size());
        Assert.assertArrayEquals(new int[]{1, 2}, t.recorder.moves.get(1));
    }

    @Test
    public void aRowCannotJumpTwiceBeforeTheListIsLaidOutAgain() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 160f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 290f));
        Assert.assertEquals(1, t.recorder.moves.size());
    }

    @Test
    public void releasingLeavesEveryRowFlat() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 120f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_UP, 120f));

        for (int i = 0; i < 3; i++) {
            View row = t.list.getRowView(i);
            Assert.assertEquals(0f, row.getTranslationY(), 0f);
            Assert.assertEquals(1f, row.getScaleX(), 0f);
            Assert.assertEquals(1f, row.getAlpha(), 0f);
            Assert.assertEquals(0f, row.getTranslationZ(), 0f);
        }
    }

    @Test
    public void aCancelledGesturePutsTheRowBack() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 160f));
        relayout(t.list);
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 260f));
        relayout(t.list);
        Assert.assertEquals(Arrays.asList("b", "c", "a"), t.items);

        t.list.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 260f));
        Assert.assertFalse(t.list.isDragging());
        Assert.assertEquals(Arrays.asList("a", "b", "c"), t.items);
        Assert.assertEquals(Arrays.asList(true, false), t.recorder.drags);
    }

    @Test
    public void hidingTheListCancelsTheDrag() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));
        t.list.onTouchEvent(event(MotionEvent.ACTION_MOVE, 160f));
        relayout(t.list);

        // A view that is not attached to a window does not report visibility changes by itself.
        t.list.onVisibilityChanged(t.list, View.GONE);
        Assert.assertFalse(t.list.isDragging());
        Assert.assertEquals(Arrays.asList("a", "b", "c"), t.items);
    }

    @Test
    public void aNewGestureEndsADragWhoseEndWasLost() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(0, 50f));

        t.list.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 300f));
        Assert.assertFalse(t.list.isDragging());
    }

    @Test
    public void theDragFollowsTheSessionWhenOthersComeAndGo() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(1, 150f));

        t.items.remove("a");
        t.adapter.notifyDataSetChanged();
        relayout(t.list);

        Assert.assertTrue(t.list.isDragging());
        Assert.assertEquals(0, t.list.getDragPosition());
    }

    @Test
    public void theDragEndsWhenTheDraggedSessionIsGone() {
        Setup t = new Setup("a", "b", "c");
        Assert.assertTrue(t.list.beginDrag(1, 150f));

        t.items.remove("b");
        t.adapter.notifyDataSetChanged();
        relayout(t.list);

        Assert.assertFalse(t.list.isDragging());
        Assert.assertEquals(Arrays.asList(true, false), t.recorder.drags);
    }

    @Test
    public void theDragEndsWhenOnlyOneSessionIsLeft() {
        Setup t = new Setup("a", "b");
        Assert.assertTrue(t.list.beginDrag(0, 50f));

        t.items.remove("b");
        t.adapter.notifyDataSetChanged();
        relayout(t.list);

        Assert.assertFalse(t.list.isDragging());
    }

    @Test
    public void draggingClaimsTheGestureFromTheRow() {
        Setup t = new Setup("a", "b");

        Assert.assertFalse(t.list.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 10f)));
        Assert.assertTrue(t.list.beginDrag(1, 150f));
        Assert.assertTrue(t.list.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 10f)));

        t.list.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 10f));
        Assert.assertFalse(t.list.isDragging());
    }

}
