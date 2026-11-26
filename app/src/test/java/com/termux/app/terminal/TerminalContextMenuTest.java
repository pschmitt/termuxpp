package com.termux.app.terminal;

import android.app.Activity;
import android.content.res.Resources;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.fakes.RoboMenu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TerminalContextMenuTest {

    // The ids that Help and Report issue used to have in the terminal menu.
    private static final int FORMER_HELP_ID = 7;
    private static final int FORMER_REPORT_ISSUE_ID = 9;

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static TerminalContextMenu.State everything() {
        return new TerminalContextMenu.State(true, true, 1234, true, false);
    }

    private static TerminalContextMenu.State minimal() {
        return new TerminalContextMenu.State(false, false, 1234, false, false);
    }

    private static List<Integer> sheetIds(Activity activity, TerminalContextMenu.State state) {
        return TerminalContextMenu.populateSheet(new TermuxContextMenuSheet(activity), activity.getResources(), state)
            .getItemIds();
    }

    private static List<Integer> menuIds(Activity activity, TerminalContextMenu.State state) {
        Menu menu = new RoboMenu(activity);
        TerminalContextMenu.populateMenu(menu, activity.getResources(), state);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) ids.add(menu.getItem(i).getItemId());
        return ids;
    }

    private static void assertNoHelpOrReportIssue(List<Integer> ids) {
        Assert.assertFalse("Help is gone from the menu", ids.contains(FORMER_HELP_ID));
        Assert.assertFalse("Report issue is gone from the menu", ids.contains(FORMER_REPORT_ISSUE_ID));
    }

    @Test
    public void sheetOmitsHelpAndReportIssue() {
        Activity activity = activity();
        assertNoHelpOrReportIssue(sheetIds(activity, everything()));
        assertNoHelpOrReportIssue(sheetIds(activity, minimal()));
    }

    @Test
    public void fallbackMenuOmitsHelpAndReportIssue() {
        Activity activity = activity();
        assertNoHelpOrReportIssue(menuIds(activity, everything()));
        assertNoHelpOrReportIssue(menuIds(activity, minimal()));
    }

    @Test
    public void sheetKeepsTheOtherActionsInOrder() {
        // Frequent actions first, then the setting and the folded Reset, Settings, and the destructive Kill last.
        Assert.assertEquals(Arrays.asList(
                TerminalContextMenu.AUTOFILL_MENU, TerminalContextMenu.SELECT_URL,
                TerminalContextMenu.SHARE_MENU, TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON,
                TerminalContextMenu.TOGGLE_WAKE_LOCK,
                TerminalContextMenu.RESET_TERMINAL, TerminalContextMenu.KILL_PROCESS,
                TerminalContextMenu.KILL_PROCESS_NOW, TerminalContextMenu.SETTINGS),
            sheetIds(activity(), everything()));
        Assert.assertEquals(Arrays.asList(
                TerminalContextMenu.SELECT_URL, TerminalContextMenu.SHARE_TRANSCRIPT,
                TerminalContextMenu.TOGGLE_KEEP_SCREEN_ON, TerminalContextMenu.TOGGLE_WAKE_LOCK,
                TerminalContextMenu.RESET_TERMINAL,
                TerminalContextMenu.KILL_PROCESS, TerminalContextMenu.KILL_PROCESS_NOW,
                TerminalContextMenu.SETTINGS),
            sheetIds(activity(), minimal()));
    }

    private static TerminalContextMenu.State session(String name, boolean running, int number, int count) {
        return new TerminalContextMenu.State(true, true, 4182, running, false,
            name, "zsh", "~/devel", number, count, 2);
    }

    @Test
    public void headerNamesTheSessionItsDirectoryPidAndPosition() {
        Resources resources = activity().getResources();
        Assert.assertEquals("zsh", TerminalContextMenu.headerTitle(resources, session(null, true, 2, 3)));
        Assert.assertEquals("build", TerminalContextMenu.headerTitle(resources, session("build", true, 2, 3)));
        Assert.assertEquals("Session 1", TerminalContextMenu.headerTitle(resources, minimal()));

        Assert.assertEquals("~/devel · 2 of 3",
            TerminalContextMenu.headerDetail(resources, session(null, true, 2, 3)));
        Assert.assertEquals("~/devel",
            TerminalContextMenu.headerDetail(resources, session(null, true, 1, 1)));
        Assert.assertEquals("~/devel",
            TerminalContextMenu.headerDetail(resources, session(null, false, 1, 1)));
    }

    @Test
    public void sessionHeaderSeparatesPidAndCopiesOnlyTheDirectory() {
        Activity activity = activity();
        BottomSheetDialog dialog = mainSheet(activity, session(null, true, 2, 3));
        TextView pid = dialog.findViewById(R.id.context_header_pid);
        TextView detail = dialog.findViewById(R.id.context_header_subtitle);
        Assert.assertEquals("pid 4182", pid.getText().toString());
        Assert.assertEquals("~/devel · 2 of 3", detail.getText().toString());
        Assert.assertTrue(detail.performLongClick());
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        Assert.assertEquals("~/devel", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        Assert.assertTrue(dialog.isShowing());
        dialog.dismiss();
    }

    @Test
    public void holdingTheSessionIconChoosesTheConfirmationAction() {
        Activity activity = activity();
        List<Integer> chosen = new ArrayList<>();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(new TermuxContextMenuSheet(activity),
            activity.getResources(), session(null, true, 1, 1)).show(chosen::add);
        Assert.assertTrue(dialog.findViewById(R.id.context_header_icon).performLongClick());
        Assert.assertEquals(Arrays.asList(TerminalContextMenu.KILL_PROCESS), chosen);
    }

    private static BottomSheetDialog mainSheet(Activity activity, TerminalContextMenu.State state) {
        return TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), state).show(id -> { });
    }

    @Test
    public void mainSheetUsesTheGroupedPresentation() {
        Activity activity = activity();
        BottomSheetDialog dialog = mainSheet(activity, session(null, true, 2, 3));
        Assert.assertEquals(Arrays.asList("Autofill", "Open URL", "Share"), texts(dialog, R.id.context_shortcut_title));
        Assert.assertTrue(texts(dialog, R.id.context_shortcut_detail).isEmpty());
        View strip = dialog.findViewById(R.id.context_strip_scroll);
        Assert.assertNull(strip.getBackground());
        android.widget.LinearLayout.LayoutParams stripParams =
            (android.widget.LinearLayout.LayoutParams) strip.getLayoutParams();
        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.ui_gutter);
        Assert.assertEquals(gutter, stripParams.leftMargin);
        Assert.assertEquals(gutter, stripParams.rightMargin);
        float density = activity.getResources().getDisplayMetrics().density;
        View shortcutIcon = dialog.findViewById(R.id.context_shortcut_icon);
        Assert.assertEquals((int) (32 * density), shortcutIcon.getLayoutParams().height);
        Assert.assertEquals((int) (4 * density), strip.getPaddingTop());
        // Reset is folded away behind More, so it is not shown yet.
        Assert.assertEquals(Arrays.asList("More", "Settings"),
            texts(dialog, R.id.context_row_title));
        Assert.assertTrue(texts(dialog, R.id.context_row_detail).isEmpty());
        // No tile of the old grid is left in the main menu.
        Assert.assertTrue(texts(dialog, R.id.context_tile_title).isEmpty());
        // Header keeps the session; Settings is a row now, not a header icon.
        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertEquals("zsh", ((TextView) header.findViewById(R.id.context_header_title)).getText().toString());
        Assert.assertEquals(View.GONE, header.findViewById(R.id.context_header_stopped).getVisibility());
        Assert.assertEquals(View.GONE, header.findViewById(R.id.context_header_action).getVisibility());
        dialog.dismiss();
        BottomSheetDialog exited = mainSheet(activity, session(null, false, 2, 3));
        Assert.assertEquals(View.VISIBLE, exited.findViewById(R.id.context_header_stopped).getVisibility());
        exited.dismiss();
    }

    @Test
    public void openUrlIsDisabledOnlyWhenTheCountIsKnownToBeZero() {
        for (int count : new int[] {0, 2, -1}) {
            Activity activity = activity();
            TerminalContextMenu.State state = new TerminalContextMenu.State(false, false, 4182, true, false,
                null, "zsh", "~", 1, 1, count);
            BottomSheetDialog dialog = mainSheet(activity, state);
            View shortcut = viewTitled(dialog, R.id.context_shortcut_title, R.id.context_shortcut, "Open URL");
            Assert.assertEquals(count != 0, shortcut.isEnabled());
            Assert.assertEquals(count != 0, shortcut.isClickable());
            View icon = shortcut.findViewById(R.id.context_shortcut_icon);
            Assert.assertEquals(count == 0, icon.getBackgroundTintList() != null);
            if (count == 0) {
                shortcut.performClick();
                Assert.assertTrue(dialog.isShowing());
            }
            dialog.dismiss();
        }
    }

    @Test
    public void sessionHeaderIsCompactWithOneSessionBadge() {
        Activity activity = activity();
        BottomSheetDialog dialog = mainSheet(activity, session(null, true, 1, 1));
        View icon = dialog.findViewById(R.id.context_header_icon);
        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertEquals((int) (40 * density), icon.getLayoutParams().width);
        Assert.assertNotNull(icon.getBackground());
        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertNull(header.getBackground());
        android.widget.LinearLayout.LayoutParams params =
            (android.widget.LinearLayout.LayoutParams) header.getLayoutParams();
        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.ui_gutter);
        Assert.assertEquals(gutter, params.leftMargin);
        Assert.assertEquals(gutter, params.rightMargin);
        Assert.assertFalse(header.isClickable());
        TextView title = dialog.findViewById(R.id.context_header_title);
        Assert.assertEquals(18f * activity.getResources().getDisplayMetrics().scaledDensity, title.getTextSize(), .1f);
        dialog.dismiss();
    }

    private static View viewTitled(BottomSheetDialog dialog, int titleId, int rootId, String title) {
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == titleId && title.contentEquals(((TextView) view).getText())) {
                View root = view;
                while (root.getId() != rootId) root = (View) root.getParent();
                return root;
            }
        }
        throw new AssertionError("Missing view: " + title);
    }

    private static View rowTitled(BottomSheetDialog dialog, String title) {
        return viewTitled(dialog, R.id.context_row_title, R.id.context_row, title);
    }

    @Test
    public void killIsFoldedWithSecondaryActionsAndSettingsIsLast() {
        Activity activity = activity();
        for (TerminalContextMenu.State state : Arrays.asList(session(null, true, 1, 1), minimal())) {
            BottomSheetDialog dialog = mainSheet(activity, state);
            String kill = state.processName == null ? "Kill session" : "Kill zsh";
            View killRow = rowTitled(dialog, kill);
            View settingsRow = rowTitled(dialog, "Settings");
            View keepRow = rowTitled(dialog, "Keep screen on");
            Assert.assertNotSame(killRow.getParent(), settingsRow.getParent());
            Assert.assertSame(killRow.getParent(), keepRow.getParent());
            Assert.assertNotSame(settingsRow.getParent(), keepRow.getParent());
            Assert.assertFalse(killRow.isShown());
            android.view.ViewGroup items = dialog.findViewById(R.id.context_sheet_items);
            Assert.assertEquals(3, items.getChildCount());
            Assert.assertSame(dialog.findViewById(R.id.context_row_expander).getParent(), items.getChildAt(1));
            Assert.assertSame(settingsRow.getParent(), items.getChildAt(2));
            dialog.findViewById(R.id.context_row_expander).performClick();
            Assert.assertTrue(killRow.isShown());
        }
    }

    @Test
    public void resetIsFoldedBehindAnExplicitMoreActionsRowWithItsStateAnnounced() {
        Activity activity = activity();
        List<Integer> chosen = new ArrayList<>();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), minimal()).show(chosen::add);
        View reset = rowTitled(dialog, "Reset");
        View expander = dialog.findViewById(R.id.context_row_expander);
        View folded = (View) reset.getParent();
        Assert.assertNotNull(expander);
        Assert.assertEquals(View.GONE, folded.getVisibility());
        Assert.assertEquals("More", ((TextView) expander.findViewById(R.id.context_row_title)).getText().toString());
        Assert.assertEquals("Collapsed", String.valueOf(androidx.core.view.ViewCompat.getStateDescription(expander)));

        Assert.assertTrue(expander.performClick());
        Assert.assertEquals(View.VISIBLE, folded.getVisibility());
        Assert.assertEquals("Fewer actions", ((TextView) expander.findViewById(R.id.context_row_title)).getText().toString());
        Assert.assertEquals("Expanded", String.valueOf(androidx.core.view.ViewCompat.getStateDescription(expander)));
        // Expanding chooses nothing and keeps the sheet open.
        Assert.assertTrue(dialog.isShowing());
        Assert.assertTrue(chosen.isEmpty());

        Assert.assertTrue(expander.performClick());
        Assert.assertEquals(View.GONE, folded.getVisibility());
    }

    @Test
    public void resetChoosesItsIdAfterTheSheetIsExpanded() {
        Activity activity = activity();
        List<Integer> chosen = new ArrayList<>();
        BottomSheetDialog dialog = TerminalContextMenu.populateSheet(
            new TermuxContextMenuSheet(activity), activity.getResources(), everything()).show(chosen::add);
        dialog.findViewById(R.id.context_row_expander).performClick();
        Assert.assertTrue(rowTitled(dialog, "Reset").performClick());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Assert.assertEquals(Arrays.asList(TerminalContextMenu.RESET_TERMINAL), chosen);
    }

    @Test
    public void keepScreenOnShowsItsStateAsAPillAndAsASwitch() {
        Activity activity = activity();
        for (boolean keepOn : new boolean[]{true, false}) {
            TerminalContextMenu.State state = new TerminalContextMenu.State(false, false, 4182, true, keepOn,
                null, "zsh", "~", 1, 1, 0);
            BottomSheetDialog dialog = mainSheet(activity, state);
            View row = rowTitled(dialog, "Keep screen on");
            Assert.assertEquals(keepOn, row.isShown());
            if (!keepOn) {
                dialog.findViewById(R.id.context_row_expander).performClick();
                Assert.assertTrue(row.isShown());
            }
            TextView pill = row.findViewById(R.id.context_row_state);
            Assert.assertEquals(View.VISIBLE, pill.getVisibility());
            Assert.assertEquals(keepOn ? "On" : "Off", pill.getText().toString());
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat info =
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.obtain();
            androidx.core.view.ViewCompat.onInitializeAccessibilityNodeInfo(row, info);
            Assert.assertEquals("android.widget.Switch", String.valueOf(info.getClassName()));
            Assert.assertTrue(info.isCheckable());
            Assert.assertEquals(keepOn, info.isChecked());
            // The pill repeats what the switch announces: screen readers read it once.
            Assert.assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, pill.getImportantForAccessibility());
        }
    }

    @Test
    public void onlyTheKeepScreenOnAndWakeLockRowsHavePills() {
        BottomSheetDialog dialog = mainSheet(activity(), everything());
        dialog.findViewById(R.id.context_row_expander).performClick();
        List<String> pills = texts(dialog, R.id.context_row_state);
        Assert.assertEquals(2, pills.size());
    }

    @Test
    public void wakeLockIsUnderMoreUnlessHeld() {
        Activity activity = activity();
        for (boolean held : new boolean[]{true, false}) {
            TerminalContextMenu.State state = new TerminalContextMenu.State(false, false, 4182, true, false, held,
                null, "zsh", "~", 1, 1, 0);
            BottomSheetDialog dialog = mainSheet(activity, state);
            View row = rowTitled(dialog, "Wake lock");
            Assert.assertEquals(held, row.isShown());
            if (!held) {
                dialog.findViewById(R.id.context_row_expander).performClick();
                Assert.assertTrue(row.isShown());
            }
            TextView pill = row.findViewById(R.id.context_row_state);
            Assert.assertEquals(held ? "On" : "Off", pill.getText().toString());
        }
    }

    @Test
    public void withoutASelectionShareIsTheTranscript() {
        Activity activity = activity();
        BottomSheetDialog dialog = mainSheet(activity, minimal());
        Assert.assertTrue(texts(dialog, R.id.context_shortcut_title).contains("Share"));
        Assert.assertTrue(sheetIds(activity, minimal()).contains(TerminalContextMenu.SHARE_TRANSCRIPT));
        Assert.assertFalse(sheetIds(activity, minimal()).contains(TerminalContextMenu.SHARE_SELECTED_TEXT));
        Assert.assertFalse(sheetIds(activity, minimal()).contains(TerminalContextMenu.SHARE_MENU));
        Assert.assertTrue(sheetIds(activity, everything()).contains(TerminalContextMenu.SHARE_MENU));
    }

    private static List<Integer> choiceIds(Activity activity, int menuId, TerminalContextMenu.State state) {
        TermuxContextMenuSheet sheet = TerminalContextMenu.populateChoiceSheet(menuId,
            new TermuxContextMenuSheet(activity), activity.getResources(), state, "hello", "a\nb\n");
        return sheet == null ? new ArrayList<>() : sheet.getItemIds();
    }

    private static List<String> texts(BottomSheetDialog dialog, int viewId) {
        List<String> out = new ArrayList<>();
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == viewId && isShown(view, dialog.findViewById(R.id.context_sheet_items)))
                out.add(((TextView) view).getText().toString());
        }
        return out;
    }

    /** Whether the view and every ancestor up to the root are visible: a folded row is not. */
    private static boolean isShown(View view, View root) {
        for (View v = view; ; v = (View) v.getParent()) {
            if (v.getVisibility() != View.VISIBLE) return false;
            if (v == root) return true;
        }
    }

    @Test
    public void shareSheetPreviewsTheSelectionAndTheTranscript() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateChoiceSheet(TerminalContextMenu.SHARE_MENU,
            new TermuxContextMenuSheet(activity), activity.getResources(), session(null, true, 1, 1),
            "\n6806d1b0 Normalize  \nline two\nthree\nfour", "$ git log\nfirst\nsecond\n~ $ \n\n").show(id -> { });

        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertEquals("Share", ((TextView) header.findViewById(R.id.context_header_title)).getText().toString());
        Assert.assertEquals("zsh", ((TextView) header.findViewById(R.id.context_header_subtitle)).getText().toString());
        Assert.assertEquals(View.GONE, header.findViewById(R.id.context_header_action).getVisibility());

        Assert.assertEquals(Arrays.asList("Selected text", "Transcript"), texts(dialog, R.id.context_tile_title));
        Assert.assertEquals(Arrays.asList("41 characters", "4 lines"), texts(dialog, R.id.context_tile_detail));
        Assert.assertEquals(Arrays.asList("6806d1b0 Normalize\nline two\nthree", "first\nsecond\n~ $"),
            texts(dialog, R.id.context_card_preview));
        Assert.assertEquals(Arrays.asList(TerminalContextMenu.SHARE_SELECTED_TEXT, TerminalContextMenu.SHARE_TRANSCRIPT),
            choiceIds(activity, TerminalContextMenu.SHARE_MENU, session(null, true, 1, 1)));
    }

    @Test
    public void autofillSheetOffersUsernameAndPassword() {
        Activity activity = activity();
        BottomSheetDialog dialog = TerminalContextMenu.populateChoiceSheet(TerminalContextMenu.AUTOFILL_MENU,
            new TermuxContextMenuSheet(activity), activity.getResources(), session("build", true, 1, 1),
            null, null).show(id -> { });
        Assert.assertEquals(Arrays.asList("Username", "Password"), texts(dialog, R.id.context_tile_title));
        Assert.assertEquals(Arrays.asList("login: ▌", "password: ••••••••"), texts(dialog, R.id.context_card_preview));
        Assert.assertEquals(Arrays.asList(TerminalContextMenu.AUTOFILL_USERNAME, TerminalContextMenu.AUTOFILL_PASSWORD),
            choiceIds(activity, TerminalContextMenu.AUTOFILL_MENU, everything()));
        Assert.assertNull(TerminalContextMenu.populateChoiceSheet(TerminalContextMenu.SETTINGS,
            new TermuxContextMenuSheet(activity), activity.getResources(), everything(), null, null));
    }

    @Test
    public void previewLinesSkipBlankEdgesAndTrailingSpaces() {
        Assert.assertNull(TerminalContextMenu.firstLines(null, 3));
        Assert.assertNull(TerminalContextMenu.firstLines(" \n\n", 3));
        Assert.assertEquals(" a\nb", TerminalContextMenu.firstLines("\n a  \nb\nc", 2));
        Assert.assertNull(TerminalContextMenu.lastLines("\n \n", 2));
        Assert.assertEquals("a\nc", TerminalContextMenu.lastLines("a\n\nc\n\n", 2));
        Assert.assertEquals("https://github.co…",
            TerminalContextMenu.firstLines("https://github.com/pschmitt/termux-app", 3));
        Assert.assertEquals(TerminalContextMenu.PREVIEW_COLUMNS,
            TerminalContextMenu.previewLine("https://github.com/pschmitt/termux-app").length());
        Assert.assertEquals("b\nc", TerminalContextMenu.lastLines("a\nb\nc \n\n", 2));
        Assert.assertEquals(3, TerminalContextMenu.countLines("a\n\nc\n \n"));
        Assert.assertEquals(0, TerminalContextMenu.countLines(""));
        Assert.assertEquals(-1, TerminalContextMenu.countLines(null));
    }

    @Test
    public void killRowFallsBackToTheSessionAndSaysWhenNothingRuns() {
        Activity activity = activity();
        BottomSheetDialog dialog = mainSheet(activity, minimal());
        View row = rowTitled(dialog, "Kill session");
        Assert.assertFalse(row.isEnabled());
        Assert.assertFalse(row.performClick());
        Assert.assertFalse(row.performLongClick());
        Assert.assertEquals("not running", ((TextView) row.findViewById(R.id.context_row_detail)).getText().toString());
    }

    @Test
    public void foldedKillPreservesTapAndLongPressDispatch() {
        for (boolean hold : new boolean[] {false, true}) {
            Activity activity = activity();
            List<Integer> chosen = new ArrayList<>();
            BottomSheetDialog dialog = TerminalContextMenu.populateSheet(new TermuxContextMenuSheet(activity),
                activity.getResources(), session(null, true, 1, 1)).show(chosen::add);
            dialog.findViewById(R.id.context_row_expander).performClick();
            View kill = rowTitled(dialog, "Kill zsh");
            Assert.assertTrue(hold ? kill.performLongClick() : kill.performClick());
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            Assert.assertEquals(Arrays.asList(hold ? TerminalContextMenu.KILL_PROCESS_NOW
                : TerminalContextMenu.KILL_PROCESS), chosen);
        }
    }

    @Test
    public void processInfoDisplaysAbsoluteNativeAndGuestPaths() {
        String home = "/data/data/com.termux/files/home";
        String root = home + "/.local/share/termuxpp-nix/root";
        Assert.assertEquals(home, ProcessInfo.displayCwd(home, root));
        Assert.assertEquals("/", ProcessInfo.displayCwd(root, root));
        Assert.assertEquals("/home/user/dev", ProcessInfo.displayCwd(root + "/home/user/dev", root));
        Assert.assertEquals(root + "-backup", ProcessInfo.displayCwd(root + "-backup", root));
        Assert.assertNull(ProcessInfo.readName(-1));
        Assert.assertNull(ProcessInfo.readCwd(0));
    }

    private static List<View> allViews(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(allViews(group.getChildAt(i)));
        }
        return out;
    }

    @Test
    public void fallbackMenuOffersTheSameActionsAsTheSheet() {
        Activity activity = activity();
        for (TerminalContextMenu.State state : Arrays.asList(everything(), minimal())) {
            // The sheet reaches some actions through a sheet of choices; killing without asking is only
            // offered by holding the kill tile.
            java.util.Set<Integer> sheet = new java.util.HashSet<>(sheetIds(activity, state));
            for (int menuId : new int[]{TerminalContextMenu.SHARE_MENU, TerminalContextMenu.AUTOFILL_MENU}) {
                if (sheet.remove(menuId)) sheet.addAll(choiceIds(activity, menuId, state));
            }
            sheet.remove(TerminalContextMenu.KILL_PROCESS_NOW);
            Assert.assertEquals(new java.util.HashSet<>(menuIds(activity, state)), sheet);
        }
    }

    @Test
    public void settingsStaysInTheMenu() {
        Assert.assertTrue(sheetIds(activity(), minimal()).contains(TerminalContextMenu.SETTINGS));
        Assert.assertTrue(menuIds(activity(), minimal()).contains(TerminalContextMenu.SETTINGS));
    }

    @Test
    public void urlEntryIsCalledExtractUrlInSheetAndFallbackMenu() {
        Activity activity = activity();
        Assert.assertEquals("Extract URL", activity.getString(R.string.action_select_url));

        Menu menu = new RoboMenu(activity);
        TerminalContextMenu.populateMenu(menu, activity.getResources(), minimal());
        Assert.assertEquals("Extract URL", menu.findItem(TerminalContextMenu.SELECT_URL).getTitle().toString());
    }

}
