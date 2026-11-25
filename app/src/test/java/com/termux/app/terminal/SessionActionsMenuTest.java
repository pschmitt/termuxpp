package com.termux.app.terminal;

import android.app.Activity;
import android.content.res.Resources;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class SessionActionsMenuTest {

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static List<Integer> ids(SessionActionsMenu.State state) {
        Activity activity = activity();
        return SessionActionsMenu.populateSheet(new TermuxContextMenuSheet(activity), activity.getResources(), state)
            .getItemIds();
    }

    private static SessionActionsMenu.State state(int number, int count, boolean running) {
        return new SessionActionsMenu.State(null, null, number, count, running);
    }

    @Test
    public void aMiddleSessionOffersEveryMoveAndKill() {
        Assert.assertEquals(Arrays.asList(
                SessionActionsMenu.MOVE_UP, SessionActionsMenu.MOVE_DOWN,
                SessionActionsMenu.MOVE_TOP, SessionActionsMenu.MOVE_BOTTOM,
                SessionActionsMenu.KILL, SessionActionsMenu.KILL_NOW, SessionActionsMenu.RENAME),
            ids(state(2, 3, true)));
    }

    @Test
    public void theEndsDoNotOfferMovesThatGoNowhere() {
        Assert.assertEquals(Arrays.asList(
                SessionActionsMenu.MOVE_UP, SessionActionsMenu.MOVE_DOWN,
                SessionActionsMenu.MOVE_BOTTOM, SessionActionsMenu.KILL, SessionActionsMenu.KILL_NOW, SessionActionsMenu.RENAME),
            ids(state(1, 3, true)));
        Assert.assertEquals(Arrays.asList(
                SessionActionsMenu.MOVE_UP, SessionActionsMenu.MOVE_DOWN,
                SessionActionsMenu.MOVE_TOP, SessionActionsMenu.KILL, SessionActionsMenu.KILL_NOW, SessionActionsMenu.RENAME),
            ids(state(3, 3, true)));
    }

    @Test
    public void aSingleSessionOffersNoMoves() {
        Assert.assertEquals(Arrays.asList(
                SessionActionsMenu.KILL, SessionActionsMenu.KILL_NOW, SessionActionsMenu.RENAME),
            ids(state(1, 1, true)));
    }

    @Test
    public void tmuxActionsFollowTheAttachmentAndLayout() {
        List<Integer> nested = ids(new SessionActionsMenu.State(null, null, 1, 1, true, true,
            R.string.ui_tmux_sidebar_collapse));
        Assert.assertTrue(nested.contains(SessionActionsMenu.TMUX_PICKER));
        Assert.assertTrue(nested.contains(SessionActionsMenu.TOGGLE_TMUX_WINDOWS));

        List<Integer> drillDown = ids(new SessionActionsMenu.State(null, null, 1, 1, true, true, 0));
        Assert.assertTrue(drillDown.contains(SessionActionsMenu.TMUX_PICKER));
        Assert.assertFalse(drillDown.contains(SessionActionsMenu.TOGGLE_TMUX_WINDOWS));
    }

    @Test
    public void anExitedSessionIsRemovedInsteadOfKilled() {
        Assert.assertEquals(Arrays.asList(
                SessionActionsMenu.MOVE_UP, SessionActionsMenu.MOVE_DOWN,
                SessionActionsMenu.MOVE_TOP, SessionActionsMenu.MOVE_BOTTOM, SessionActionsMenu.REMOVE, SessionActionsMenu.RENAME),
            ids(state(2, 3, false)));
    }

    @Test
    public void headerNamesTheSessionAndItsPosition() {
        Resources resources = activity().getResources();
        Assert.assertEquals("build", SessionActionsMenu.headerTitle(resources,
            new SessionActionsMenu.State("build", "vim", 2, 3, true)));
        Assert.assertEquals("vim", SessionActionsMenu.headerTitle(resources,
            new SessionActionsMenu.State(null, "vim", 2, 3, true)));
        Assert.assertEquals("Session 2", SessionActionsMenu.headerTitle(resources, state(2, 3, true)));

        Assert.assertEquals("2 of 3", SessionActionsMenu.headerDetail(resources, state(2, 3, true)));
        Assert.assertEquals("not running · 2 of 3", SessionActionsMenu.headerDetail(resources, state(2, 3, false)));
        Assert.assertEquals("", SessionActionsMenu.headerDetail(resources, state(1, 1, true)));
    }

    @Test
    public void emptySessionDetailDoesNotOccupyHeaderSpace() {
        Activity activity = activity();
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = SessionActionsMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), state(1, 1, true)).show(id -> {});
        Assert.assertEquals(android.view.View.GONE, dialog.findViewById(R.id.context_header_subtitle).getVisibility());
        dialog.dismiss();
    }

    @Test
    public void penInSessionHeaderSelectsRename() {
        Activity activity = activity();
        TermuxContextMenuSheet sheet = SessionActionsMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), state(2, 3, true));
        int[] selected = {-1};
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = sheet.show(id -> selected[0] = id);
        android.view.View pen = dialog.findViewById(R.id.context_header_action);
        Assert.assertNotNull(pen);
        Assert.assertEquals(android.view.View.VISIBLE, pen.getVisibility());
        Assert.assertEquals(activity.getString(R.string.ui_session_action_rename), pen.getContentDescription());
        pen.performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Assert.assertEquals(SessionActionsMenu.RENAME, selected[0]);
    }

    @Test
    public void movesMapToTheirReorderMove() {
        Assert.assertEquals(SessionReorder.Move.UP, SessionActionsMenu.moveFor(SessionActionsMenu.MOVE_UP));
        Assert.assertEquals(SessionReorder.Move.DOWN, SessionActionsMenu.moveFor(SessionActionsMenu.MOVE_DOWN));
        Assert.assertEquals(SessionReorder.Move.TOP, SessionActionsMenu.moveFor(SessionActionsMenu.MOVE_TOP));
        Assert.assertEquals(SessionReorder.Move.BOTTOM, SessionActionsMenu.moveFor(SessionActionsMenu.MOVE_BOTTOM));
        Assert.assertNull(SessionActionsMenu.moveFor(SessionActionsMenu.RENAME));
        Assert.assertNull(SessionActionsMenu.moveFor(SessionActionsMenu.KILL));
    }

}
