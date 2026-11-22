package com.termux.app.terminal;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
public class TermuxContextMenuSheetTest {

    private static final int USERNAME = 11;
    private static final int PASSWORD = 2;
    private static final int RESET = 3;
    private static final int KILL = 4;
    private static final int KILL_NOW = 12;
    private static final int SETTINGS = 8;
    private static final int SHARE = 1;
    private static final int OTHER = 99;

    private static Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        activity.setTheme(R.style.Theme_TermuxApp_DayNight_NoActionBar);
        return activity;
    }

    private static TermuxContextMenuSheet sheet(Activity activity, boolean killEnabled) {
        return new TermuxContextMenuSheet(activity)
            .setHeader("zsh", "~/devel · pid 4182", SETTINGS, "Settings", R.drawable.ui_ic_menu_settings)
            .addTile(PASSWORD, "Autofill", "username or password", R.drawable.ui_ic_menu_key)
            .addTile(SHARE, "Share", "transcript", R.drawable.ui_ic_menu_share)
            .addTile(RESET, "Reset", null, R.drawable.ui_ic_menu_reset)
            .addDangerTile(KILL, "Kill zsh", "hold to kill now", R.drawable.ui_ic_menu_power, killEnabled,
                KILL_NOW, "Kill now")
            .addTile(TerminalContextMenu.SELECT_URL, "Open URL", "2 found", R.drawable.ui_ic_menu_link)
            .addItem(OTHER, R.string.action_toggle_keep_screen_on, R.drawable.ui_ic_menu_sun);
    }

    private static TermuxContextMenuSheet sheet(Activity activity) {
        return sheet(activity, true);
    }

    private static View tile(BottomSheetDialog dialog, String title) {
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == R.id.context_tile_title && title.contentEquals(((TextView) view).getText())) {
                View tile = view;
                while (tile.getId() != R.id.context_tile) tile = (View) tile.getParent();
                return tile;
            }
        }
        throw new AssertionError("Missing tile: " + title);
    }

    private static int clickTile(Activity activity, String title, boolean longPress) {
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        View view = tile(dialog, title);
        Assert.assertTrue(longPress ? view.performLongClick() : view.performClick());
        // The dismiss callback is delivered through the main looper.
        ShadowLooper.idleMainLooper();
        return chosen[0];
    }

    @Test
    public void tilesChooseTheirOwnIds() {
        Activity activity = activity();
        Assert.assertEquals(PASSWORD, clickTile(activity, "Autofill", false));
        Assert.assertEquals(SHARE, clickTile(activity, "Share", false));
        Assert.assertEquals(RESET, clickTile(activity, "Reset", false));
        Assert.assertEquals(KILL, clickTile(activity, "Kill zsh", false));
    }

    @Test
    public void holdingKillChoosesKillNowAndIsOfferedToScreenReaders() {
        Activity activity = activity();
        Assert.assertEquals(KILL_NOW, clickTile(activity, "Kill zsh", true));

        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain();
        ViewCompat.onInitializeAccessibilityNodeInfo(tile(dialog, "Kill zsh"), info);
        boolean labelled = false;
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : info.getActionList()) {
            if (action.getId() == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                labelled = "Kill now".contentEquals(action.getLabel());
        }
        Assert.assertTrue(labelled);
    }

    @Test
    public void cardsShowAPreviewAndChooseTheirId() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = new TermuxContextMenuSheet(activity)
            .setHeader(R.drawable.ui_ic_menu_key, "Autofill", "zsh")
            .addCard(USERNAME, "Username", "from your password manager", R.drawable.ui_ic_menu_user, "login: ▌")
            .addCard(PASSWORD, "Password", null, R.drawable.ui_ic_menu_lock, null)
            .show(id -> chosen[0] = id);

        View username = tile(dialog, "Username");
        TextView preview = username.findViewById(R.id.context_card_preview);
        Assert.assertEquals(View.VISIBLE, preview.getVisibility());
        Assert.assertEquals("login: ▌", preview.getText().toString());
        Assert.assertEquals(View.GONE, tile(dialog, "Password").findViewById(R.id.context_card_preview).getVisibility());
        Assert.assertSame(username.getParent(), tile(dialog, "Password").getParent());
        Assert.assertEquals(View.GONE, dialog.findViewById(R.id.context_header_action).getVisibility());
        Assert.assertEquals(java.util.Arrays.asList(USERNAME, PASSWORD),
            new TermuxContextMenuSheet(activity).setHeader(R.drawable.ui_ic_menu_key, "Autofill", "zsh")
                .addCard(USERNAME, "Username", null, R.drawable.ui_ic_menu_user, null)
                .addCard(PASSWORD, "Password", null, R.drawable.ui_ic_menu_lock, null).getItemIds());

        // A row of cards is as tall as its cards, not as its gap (cards once collapsed to a 1px row).
        ViewGroup row = (ViewGroup) username.getParent();
        row.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertTrue(row.getMeasuredHeight() >= Math.round(148 * density));
        Assert.assertTrue(username.getMeasuredHeight() >= Math.round(148 * density));

        Assert.assertTrue(username.performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(USERNAME, chosen[0]);
    }

    @Test
    public void tilesWithoutALongPressIgnoreHolding() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        tile(dialog, "Share").performLongClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(Integer.MIN_VALUE, chosen[0]);
    }

    @Test
    public void disabledKillTileChoosesNothing() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity, false).show(id -> chosen[0] = id);
        View kill = tile(dialog, "Kill zsh");
        Assert.assertFalse(kill.isEnabled());
        Assert.assertFalse(kill.performClick());
        Assert.assertFalse(kill.performLongClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(Integer.MIN_VALUE, chosen[0]);
    }

    @Test
    public void tilesAreLaidOutTwoPerRowAndKeepTheTouchTarget() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        ViewGroup first = (ViewGroup) tile(dialog, "Autofill").getParent();
        Assert.assertSame(first, tile(dialog, "Share").getParent());
        Assert.assertNotSame(first, tile(dialog, "Reset").getParent());

        // The fifth tile is alone in its row and spans it.
        ViewGroup last = (ViewGroup) tile(dialog, "Open URL").getParent();
        Assert.assertEquals(1, last.getChildCount());
        Assert.assertEquals(1f, ((LinearLayout.LayoutParams) last.getChildAt(0).getLayoutParams()).weight, 0f);

        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertTrue(tile(dialog, "Autofill").getMinimumHeight() >= Math.round(48 * density));
    }

    @Test
    public void tilesShowTheirDetailOnlyWhenThereIsOne() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        TextView withDetail = tile(dialog, "Open URL").findViewById(R.id.context_tile_detail);
        TextView without = tile(dialog, "Reset").findViewById(R.id.context_tile_detail);
        Assert.assertEquals(View.VISIBLE, withDetail.getVisibility());
        Assert.assertEquals("2 found", withDetail.getText().toString());
        Assert.assertEquals(View.GONE, without.getVisibility());
    }

    @Test
    public void headerNamesTheSessionAndItsActionChoosesItsId() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertEquals(View.VISIBLE, header.getVisibility());
        Assert.assertEquals("zsh", ((TextView) header.findViewById(R.id.context_header_title)).getText().toString());
        Assert.assertEquals("~/devel · pid 4182",
            ((TextView) header.findViewById(R.id.context_header_subtitle)).getText().toString());
        ImageView action = header.findViewById(R.id.context_header_action);
        Assert.assertEquals("Settings", action.getContentDescription().toString());
        Assert.assertTrue(action.performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(SETTINGS, chosen[0]);
    }

    @Test
    public void longPressesFollowTheirTileAndTheHeaderActionIsListedLast() {
        Assert.assertEquals(java.util.Arrays.asList(PASSWORD, SHARE, RESET, KILL, KILL_NOW,
                TerminalContextMenu.SELECT_URL, OTHER, SETTINGS),
            sheet(activity()).getItemIds());
    }

    @Test
    public void withoutAHeaderNothingIsShownAboveTheList() {
        Activity activity = activity();
        BottomSheetDialog dialog = new TermuxContextMenuSheet(activity)
            .addItem(OTHER, R.string.action_select_url, R.drawable.ui_ic_menu_link).show(id -> { });
        Assert.assertEquals(View.GONE, dialog.findViewById(R.id.context_sheet_header).getVisibility());
    }

    @Test
    public void regularItemsStillChooseTheirId() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = sheet(activity).show(id -> chosen[0] = id);
        TextView title = null;
        for (View v : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (v.getId() == R.id.context_item_title) {
                title = (TextView) v;
                break;
            }
        }
        Assert.assertNotNull(title);
        Assert.assertTrue(((View) title.getParent()).performClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(OTHER, chosen[0]);
    }

    private static TermuxContextMenuSheet grouped(Activity activity, boolean killEnabled, boolean keepOn) {
        return new TermuxContextMenuSheet(activity)
            .setHeader(R.drawable.ui_ic_menu_terminal, "zsh", "~/devel · pid 4182")
            .addShortcut(TerminalContextMenu.SELECT_URL, "Open URL", "2 found", R.drawable.ui_ic_menu_link)
            .addShortcut(SHARE, "Share", null, R.drawable.ui_ic_menu_share)
            .addStateRow(OTHER, "Keep screen on", R.drawable.ui_ic_menu_sun, keepOn)
            .addSecondaryRow(RESET, "Reset", "terminal state", R.drawable.ui_ic_menu_reset)
            .addGroupBreak()
            .addRow(SETTINGS, "Settings", null, R.drawable.ui_ic_menu_settings)
            .addGroupBreak()
            .addDangerRow(KILL, "Kill zsh", "hold to kill now", R.drawable.ui_ic_menu_power, killEnabled,
                KILL_NOW, "Kill now");
    }

    private static View titled(BottomSheetDialog dialog, int titleId, int rootId, String title) {
        for (View view : allViews(dialog.findViewById(R.id.context_sheet_items))) {
            if (view.getId() == titleId && title.contentEquals(((TextView) view).getText())) {
                View root = view;
                while (root.getId() != rootId) root = (View) root.getParent();
                return root;
            }
        }
        throw new AssertionError("Missing view: " + title);
    }

    private static View row(BottomSheetDialog dialog, String title) {
        return titled(dialog, R.id.context_row_title, R.id.context_row, title);
    }

    private static View shortcut(BottomSheetDialog dialog, String title) {
        return titled(dialog, R.id.context_shortcut_title, R.id.context_shortcut, title);
    }

    private static int choose(Activity activity, boolean shortcut, String title, boolean longPress) {
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = grouped(activity, true, false).show(id -> chosen[0] = id);
        if (!shortcut) {
            // Expand first so that the folded rows can be reached the way a user would.
            dialog.findViewById(R.id.context_row_expander).performClick();
        }
        View view = shortcut ? shortcut(dialog, title) : row(dialog, title);
        Assert.assertTrue(longPress ? view.performLongClick() : view.performClick());
        Assert.assertFalse("The sheet closes with the choice", dialog.isShowing());
        ShadowLooper.idleMainLooper();
        return chosen[0];
    }

    @Test
    public void groupedActionsAreListedInOrderWithLongPressesAndNoHeaderAction() {
        Assert.assertEquals(java.util.Arrays.asList(TerminalContextMenu.SELECT_URL, SHARE, OTHER, RESET, SETTINGS,
                KILL, KILL_NOW),
            grouped(activity(), true, false).getItemIds());
    }

    @Test
    public void groupedShortcutsRowsAndSettingsChooseTheirOwnIds() {
        Activity activity = activity();
        Assert.assertEquals(TerminalContextMenu.SELECT_URL, choose(activity, true, "Open URL", false));
        Assert.assertEquals(SHARE, choose(activity, true, "Share", false));
        Assert.assertEquals(OTHER, choose(activity, false, "Keep screen on", false));
        Assert.assertEquals(RESET, choose(activity, false, "Reset", false));
        Assert.assertEquals(SETTINGS, choose(activity, false, "Settings", false));
        Assert.assertEquals(KILL, choose(activity, false, "Kill zsh", false));
    }

    @Test
    public void groupedChoiceIsDeliveredOnlyAfterTheSheetIsDismissed() {
        Activity activity = activity();
        java.util.List<String> events = new java.util.ArrayList<>();
        BottomSheetDialog[] holder = new BottomSheetDialog[1];
        holder[0] = grouped(activity, true, false).show(id -> events.add("chosen " + id + " showing=" + holder[0].isShowing()));
        row(holder[0], "Settings").performClick();
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(java.util.Collections.singletonList("chosen " + SETTINGS + " showing=false"), events);
    }

    @Test
    public void holdingTheGroupedKillRowChoosesKillNowAndIsLabelledForScreenReaders() {
        Activity activity = activity();
        Assert.assertEquals(KILL_NOW, choose(activity, false, "Kill zsh", true));

        BottomSheetDialog dialog = grouped(activity, true, false).show(id -> { });
        AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain();
        ViewCompat.onInitializeAccessibilityNodeInfo(row(dialog, "Kill zsh"), info);
        boolean labelled = false;
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : info.getActionList()) {
            if (action.getId() == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                labelled = "Kill now".contentEquals(action.getLabel());
        }
        Assert.assertTrue(labelled);
    }

    @Test
    public void groupedRowsWithoutALongPressIgnoreHolding() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = grouped(activity, true, false).show(id -> chosen[0] = id);
        Assert.assertFalse(row(dialog, "Settings").performLongClick());
        Assert.assertFalse(shortcut(dialog, "Share").performLongClick());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(Integer.MIN_VALUE, chosen[0]);
        Assert.assertTrue(dialog.isShowing());
    }

    @Test
    public void disabledGroupedKillRowChoosesNothingAndLooksDisabled() {
        Activity activity = activity();
        int[] chosen = {Integer.MIN_VALUE};
        BottomSheetDialog dialog = grouped(activity, false, false).show(id -> chosen[0] = id);
        View kill = row(dialog, "Kill zsh");
        Assert.assertFalse(kill.isEnabled());
        Assert.assertFalse(kill.performClick());
        Assert.assertFalse(kill.performLongClick());
        Assert.assertFalse(kill.findViewById(R.id.context_row_title).isEnabled());
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(Integer.MIN_VALUE, chosen[0]);
        Assert.assertTrue(dialog.isShowing());
    }

    @Test
    public void groupedStateRowShowsAnOnOrOffPillAndIsASwitch() {
        Activity activity = activity();
        for (boolean on : new boolean[]{true, false}) {
            BottomSheetDialog dialog = grouped(activity, true, on).show(id -> { });
            View keep = row(dialog, "Keep screen on");
            TextView pill = keep.findViewById(R.id.context_row_state);
            Assert.assertEquals(View.VISIBLE, pill.getVisibility());
            Assert.assertEquals(on ? "On" : "Off", pill.getText().toString());
            Assert.assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, pill.getImportantForAccessibility());

            AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain();
            ViewCompat.onInitializeAccessibilityNodeInfo(keep, info);
            Assert.assertEquals("android.widget.Switch", String.valueOf(info.getClassName()));
            Assert.assertTrue(info.isCheckable());
            Assert.assertEquals(on, info.isChecked());

            // Plain rows carry no pill and are not switches.
            Assert.assertEquals(View.GONE, row(dialog, "Settings").findViewById(R.id.context_row_state).getVisibility());
            AccessibilityNodeInfoCompat plain = AccessibilityNodeInfoCompat.obtain();
            ViewCompat.onInitializeAccessibilityNodeInfo(row(dialog, "Settings"), plain);
            Assert.assertFalse(plain.isCheckable());
        }
    }

    @Test
    public void groupedSecondaryRowsAreFoldedUntilTheExplicitExpanderIsUsed() {
        Activity activity = activity();
        java.util.List<Integer> chosen = new java.util.ArrayList<>();
        BottomSheetDialog dialog = grouped(activity, true, false).show(chosen::add);
        View expander = dialog.findViewById(R.id.context_row_expander);
        View reset = row(dialog, "Reset");
        View folded = (View) reset.getParent();
        TextView label = expander.findViewById(R.id.context_row_title);

        Assert.assertEquals(View.GONE, folded.getVisibility());
        Assert.assertEquals("More commands", label.getText().toString());
        Assert.assertEquals(View.VISIBLE, expander.findViewById(R.id.context_row_chevron).getVisibility());
        Assert.assertEquals("Collapsed", String.valueOf(ViewCompat.getStateDescription(expander)));
        Assert.assertTrue(expander.isClickable() && expander.isFocusable());

        // The expander is offered to screen readers as an expandable control.
        AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain();
        ViewCompat.onInitializeAccessibilityNodeInfo(expander, info);
        Assert.assertTrue(hasAction(info, AccessibilityNodeInfoCompat.ACTION_EXPAND));
        Assert.assertFalse(hasAction(info, AccessibilityNodeInfoCompat.ACTION_COLLAPSE));

        Assert.assertTrue(expander.performClick());
        Assert.assertEquals(View.VISIBLE, folded.getVisibility());
        Assert.assertEquals("Fewer commands", label.getText().toString());
        Assert.assertEquals("Expanded", String.valueOf(ViewCompat.getStateDescription(expander)));
        info = AccessibilityNodeInfoCompat.obtain();
        ViewCompat.onInitializeAccessibilityNodeInfo(expander, info);
        Assert.assertTrue(hasAction(info, AccessibilityNodeInfoCompat.ACTION_COLLAPSE));

        // Expanding is not a choice: the sheet stays and nothing is delivered.
        ShadowLooper.idleMainLooper();
        Assert.assertTrue(dialog.isShowing());
        Assert.assertTrue(chosen.isEmpty());

        Assert.assertTrue(expander.performClick());
        Assert.assertEquals(View.GONE, folded.getVisibility());
        Assert.assertEquals("More commands", label.getText().toString());
    }

    @Test
    public void groupedExpanderCanBeDrivenByAccessibilityActions() {
        Activity activity = activity();
        BottomSheetDialog dialog = grouped(activity, true, false).show(id -> { });
        View expander = dialog.findViewById(R.id.context_row_expander);
        View folded = (View) row(dialog, "Reset").getParent();

        Assert.assertFalse("Already collapsed", ViewCompat.performAccessibilityAction(expander,
            AccessibilityNodeInfoCompat.ACTION_COLLAPSE, null));
        Assert.assertTrue(ViewCompat.performAccessibilityAction(expander,
            AccessibilityNodeInfoCompat.ACTION_EXPAND, null));
        Assert.assertEquals(View.VISIBLE, folded.getVisibility());
        Assert.assertTrue(ViewCompat.performAccessibilityAction(expander,
            AccessibilityNodeInfoCompat.ACTION_COLLAPSE, null));
        Assert.assertEquals(View.GONE, folded.getVisibility());
    }

    private static boolean hasAction(AccessibilityNodeInfoCompat info, int id) {
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : info.getActionList()) {
            if (action.getId() == id) return true;
        }
        return false;
    }

    @Test
    public void groupedLayoutIsAStripThenOneCardPerGroup() {
        Activity activity = activity();
        BottomSheetDialog dialog = grouped(activity, true, false).show(id -> { });
        ViewGroup items = dialog.findViewById(R.id.context_sheet_items);

        // Strip, the setting group, Settings on its own, and the destructive Kill on its own.
        Assert.assertEquals(4, items.getChildCount());
        View strip = items.getChildAt(0);
        Assert.assertNotNull(strip.findViewById(R.id.context_strip));
        Assert.assertSame(shortcut(dialog, "Open URL").getParent(), shortcut(dialog, "Share").getParent());
        Assert.assertSame(strip.findViewById(R.id.context_strip), shortcut(dialog, "Share").getParent());
        Assert.assertSame(items.getChildAt(1), row(dialog, "Keep screen on").getParent());
        Assert.assertSame(items.getChildAt(1), dialog.findViewById(R.id.context_row_expander).getParent());
        Assert.assertSame(items.getChildAt(2), row(dialog, "Settings").getParent());
        Assert.assertSame(items.getChildAt(3), row(dialog, "Kill zsh").getParent());

        // Only the rows after the first of a group have a divider above them.
        Assert.assertEquals(View.GONE, row(dialog, "Keep screen on").findViewById(R.id.context_row_divider).getVisibility());
        Assert.assertEquals(View.VISIBLE,
            dialog.findViewById(R.id.context_row_expander).findViewById(R.id.context_row_divider).getVisibility());
        Assert.assertEquals(View.GONE, row(dialog, "Settings").findViewById(R.id.context_row_divider).getVisibility());

        // The shortcuts share the strip and keep the touch target; the detail only shows where there is one.
        float density = activity.getResources().getDisplayMetrics().density;
        Assert.assertTrue(shortcut(dialog, "Share").getMinimumHeight() >= Math.round(48 * density));
        Assert.assertEquals("2 found",
            ((TextView) shortcut(dialog, "Open URL").findViewById(R.id.context_shortcut_detail)).getText().toString());
        Assert.assertEquals(View.GONE,
            shortcut(dialog, "Share").findViewById(R.id.context_shortcut_detail).getVisibility());

        // Every row is at least a touch target tall.
        View settings = row(dialog, "Settings");
        settings.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Assert.assertTrue(settings.getMeasuredHeight() >= Math.round(48 * density));
    }

    @Test
    public void groupedHeaderHasNoActionAndNoSectionLabels() {
        Activity activity = activity();
        BottomSheetDialog dialog = grouped(activity, true, false).show(id -> { });
        View header = dialog.findViewById(R.id.context_sheet_header);
        Assert.assertEquals("zsh", ((TextView) header.findViewById(R.id.context_header_title)).getText().toString());
        Assert.assertEquals(View.GONE, header.findViewById(R.id.context_header_action).getVisibility());
        ViewGroup root = (ViewGroup) dialog.findViewById(R.id.context_sheet_items).getParent().getParent();
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_text)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_session)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_app)));
    }

    @Test
    public void choiceSheetsKeepTheirCardsAndAreNotGrouped() {
        Activity activity = activity();
        BottomSheetDialog dialog = new TermuxContextMenuSheet(activity)
            .setHeader(R.drawable.ui_ic_menu_key, "Autofill", "zsh")
            .addCard(USERNAME, "Username", null, R.drawable.ui_ic_menu_user, "login: ▌")
            .addCard(PASSWORD, "Password", null, R.drawable.ui_ic_menu_lock, null)
            .show(id -> { });
        ViewGroup items = dialog.findViewById(R.id.context_sheet_items);
        Assert.assertNotNull(tile(dialog, "Username"));
        Assert.assertTrue(allViews(items).stream().noneMatch(v -> v.getId() == R.id.context_row
            || v.getId() == R.id.context_shortcut || v.getId() == R.id.context_row_expander));
    }

    @NonNull
    private static java.util.List<View> allViews(View root) {
        java.util.List<View> out = new java.util.ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(allViews(group.getChildAt(i)));
        }
        return out;
    }

    @Test
    public void maxHeightIsAboutTwoThirdsOfAPortraitScreen() {
        // 2400px tall display at 2.625 density: 65% = 1560px.
        int max = TermuxContextMenuSheet.computeMaxHeight(2200, 2.625f);
        Assert.assertEquals(Math.round(2200 * 0.65f), max);
        Assert.assertTrue(max < 2200 * 0.7f);
    }

    @Test
    public void maxHeightKeepsAGapAtTheTopAndAFloorInLandscape() {
        float density = 2.625f;
        // Landscape: only 400px are left. The floor of 300dp would be 787px, the top gap wins.
        int max = TermuxContextMenuSheet.computeMaxHeight(400, density);
        Assert.assertEquals(400 - Math.round(48 * density), max);
        Assert.assertTrue(max < 400);
    }

    @Test
    public void maxHeightShrinksWithTheKeyboardAndIsUnboundedWhenUnknown() {
        float density = 2.625f;
        int full = TermuxContextMenuSheet.computeMaxHeight(2200, density);
        int withKeyboard = TermuxContextMenuSheet.computeMaxHeight(1300, density);
        Assert.assertTrue(withKeyboard < full);
        Assert.assertTrue(withKeyboard <= 1300 - Math.round(48 * density));
        Assert.assertEquals(-1, TermuxContextMenuSheet.computeMaxHeight(0, density));
    }
    @Test
    public void headerIsTheDragHandleAndTheSessionWithoutAMenuTitle() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View items = dialog.findViewById(R.id.context_sheet_items);
        Assert.assertNotNull(items);

        // Content root: the handle, the session header, then the scrolling list.
        ViewGroup root = (ViewGroup) items.getParent().getParent();
        Assert.assertEquals(3, root.getChildCount());
        Assert.assertFalse(root.getChildAt(0) instanceof TextView);
        Assert.assertEquals(R.id.context_sheet_header, root.getChildAt(1).getId());
        Assert.assertTrue(root.getChildAt(2) instanceof androidx.core.widget.NestedScrollView);

        // Neither section labels nor a visible menu title are rendered.
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_title)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_text)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_session)));
        Assert.assertFalse(containsVisibleText(root, activity.getString(R.string.ui_menu_group_app)));
    }

    @Test
    public void screenReadersStillGetATitle() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        View items = dialog.findViewById(R.id.context_sheet_items);
        ViewGroup root = (ViewGroup) items.getParent().getParent();
        Assert.assertEquals(activity.getString(R.string.ui_menu_title),
            String.valueOf(androidx.core.view.ViewCompat.getAccessibilityPaneTitle(root)));
    }

    @Test
    public void expandedSheetKeepsItsRoundedTopCorners() {
        Activity activity = activity();
        BottomSheetDialog dialog = sheet(activity).show(id -> { });
        ShadowLooper.idleMainLooper();
        Assert.assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,
            dialog.getBehavior().getState());
        Assert.assertFalse(dialog.getBehavior().isShouldRemoveExpandedCorners());

        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        Assert.assertNotNull(sheet);
        Assert.assertTrue(sheet.getBackground() instanceof com.google.android.material.shape.MaterialShapeDrawable);
        com.google.android.material.shape.ShapeAppearanceModel shape =
            ((com.google.android.material.shape.MaterialShapeDrawable) sheet.getBackground()).getShapeAppearanceModel();
        android.graphics.RectF bounds = new android.graphics.RectF(0, 0, 1080, 1200);
        float expected = 28 * activity.getResources().getDisplayMetrics().density;
        Assert.assertEquals(expected, shape.getTopLeftCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(expected, shape.getTopRightCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(0f, shape.getBottomLeftCornerSize().getCornerSize(bounds), 0.5f);
        Assert.assertEquals(0f, shape.getBottomRightCornerSize().getCornerSize(bounds), 0.5f);
    }

    private static boolean containsVisibleText(View view, String text) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
            && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsVisibleText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

}
