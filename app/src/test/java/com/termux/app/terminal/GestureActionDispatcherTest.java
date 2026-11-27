package com.termux.app.terminal;

import androidx.annotation.NonNull;

import dev.pschmitt.termux.shared.termux.extrakeys.TerminalKeySequence;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GestureActionDispatcherTest {

    private static final class FakeTarget implements GestureActionDispatcher.Target {
        int sessionCount = 3;
        boolean keyboardDisabled;
        boolean keyboardVisible;
        boolean extraKeysVisible = true;
        int visibilityQueries;
        final List<String> calls = new ArrayList<>();
        final List<TerminalKeySequence.Step> sent = new ArrayList<>();
        String command;
        GestureCommandContext commandContext;

        @Override public int getSessionCount() { return sessionCount; }
        @Override public void switchSession(boolean forward) { calls.add(forward ? "next" : "previous"); }
        @Override public void createSession() { calls.add("newSession"); }
        @Override public void toggleTextInput() { calls.add("textInput"); }
        @Override public boolean isExtraKeysVisible() { return extraKeysVisible; }
        @Override public void setExtraKeysVisible(boolean visible) {
            extraKeysVisible = visible;
            calls.add(visible ? "showExtraKeys" : "hideExtraKeys");
        }
        @Override public void openSidebar(boolean right) { calls.add(right ? "right-sidebar" : "sidebar"); }
        @Override public void openSessionSwitcher() { calls.add("switcher"); }
        @Override public void openTmuxSwitcher() { calls.add("tmuxSwitcher"); }
        @Override public void openMenu() { calls.add("menu"); }
        @Override public void changeFontSize(boolean increase) { calls.add(increase ? "fontUp" : "fontDown"); }
        @Override public boolean isSoftKeyboardDisabled() { return keyboardDisabled; }
        @Override public boolean isSoftKeyboardVisible() { visibilityQueries++; return keyboardVisible; }
        @Override public void showSoftKeyboard() { calls.add("show"); }
        @Override public void hideSoftKeyboard() { calls.add("hide"); }
        @Override public void sendKeys(@NonNull TerminalKeySequence keys) {
            calls.add("keys");
            keys.send((key, ctrl, alt, shift, fn) -> sent.add(new TerminalKeySequence.Step(key, ctrl, alt, shift, fn)));
        }
        @Override public void runCommand(@NonNull String command, @NonNull GestureCommandContext context) {
            this.command = command;
            this.commandContext = context;
            calls.add("command");
        }
    }

    private FakeTarget mTarget;

    @Before
    public void setUp() {
        mTarget = new FakeTarget();
    }

    @Test
    public void extraKeysActionsShowHideAndToggleTheToolbar() {
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_EXTRA_KEYS, mTarget);
        assertTrue(mTarget.calls.isEmpty());
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_EXTRA_KEYS, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.HIDE_EXTRA_KEYS, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_EXTRA_KEYS, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.HIDE_EXTRA_KEYS, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_EXTRA_KEYS, mTarget);
        assertEquals(java.util.Arrays.asList("hideExtraKeys", "showExtraKeys", "hideExtraKeys", "showExtraKeys"), mTarget.calls);
        assertEquals(0, mTarget.visibilityQueries);
    }

    @Test
    public void hideKeyboardHidesEvenWhenTheVisibilityIsUnknown() {
        GestureActionDispatcher.perform(TerminalGestureAction.HIDE_KEYBOARD, mTarget);
        assertEquals(java.util.Collections.singletonList("hide"), mTarget.calls);
        mTarget.calls.clear();
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.HIDE_KEYBOARD, mTarget);
        assertTrue(mTarget.calls.isEmpty());
    }

    @Test
    public void sessionSwitcherOpensThePickerWithoutChangingTheKeyboard() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_SESSION_SWITCHER, mTarget);
        assertEquals(java.util.Collections.singletonList("switcher"), mTarget.calls);
        assertEquals(0, mTarget.visibilityQueries);
    }

    @Test
    public void tmuxSwitcherOpensTheTmuxPickerWithoutChangingTheKeyboard() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_TMUX_SWITCHER, mTarget);
        assertEquals(java.util.Collections.singletonList("tmuxSwitcher"), mTarget.calls);
        assertEquals(0, mTarget.visibilityQueries);
    }

    @Test
    public void noneDoesNothing() {
        GestureActionDispatcher.perform(TerminalGestureAction.NONE, mTarget);
        assertTrue(mTarget.calls.isEmpty());
    }

    @Test
    public void newSessionAndTextInputReachTheirTargets() {
        GestureActionDispatcher.perform(TerminalGestureAction.NEW_SESSION, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_TEXT_INPUT, mTarget);
        assertEquals(List.of("newSession", "textInput"), mTarget.calls);
    }

    @Test
    public void nextAndPreviousSessionSwitchSessions() {
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.PREVIOUS_SESSION, mTarget);
        assertEquals(List.of("next", "previous"), mTarget.calls);
    }

    @Test
    public void sessionActionsReachTheSessionHandlerWithOneSession() {
        mTarget.sessionCount = 1;
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.PREVIOUS_SESSION, mTarget);
        assertEquals(List.of("next", "previous"), mTarget.calls);
    }

    @Test
    public void sessionActionsAreHarmlessWithNoSessions() {
        mTarget.sessionCount = 0;
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.PREVIOUS_SESSION, mTarget);
        assertTrue(mTarget.calls.isEmpty());
    }

    @Test
    public void sessionActionsWorkWithTheKeyboardDisabled() {
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, mTarget);
        assertEquals(List.of("next"), mTarget.calls);
    }

    @Test
    public void openSidebarOpensTheSidebar() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_SIDEBAR, mTarget);
        assertEquals(List.of("sidebar"), mTarget.calls);
    }

    @Test
    public void openRightSidebarTargetsTheOtherDrawer() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_RIGHT_SIDEBAR, mTarget);
        assertEquals(List.of("right-sidebar"), mTarget.calls);
    }

    @Test
    public void openSidebarWorksWithTheKeyboardDisabled() {
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_SIDEBAR, mTarget);
        assertEquals(List.of("sidebar"), mTarget.calls);
    }

    @Test
    public void openMenuOpensTheMenuOnlyAndNeverTouchesTheKeyboard() {
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_MENU, mTarget);
        assertEquals(List.of("menu"), mTarget.calls);
        assertEquals(0, mTarget.visibilityQueries);
    }

    @Test
    public void openMenuWorksWithTheKeyboardDisabledOrASingleSession() {
        mTarget.keyboardDisabled = true;
        mTarget.sessionCount = 1;
        GestureActionDispatcher.perform(TerminalGestureAction.OPEN_MENU, mTarget);
        assertEquals(List.of("menu"), mTarget.calls);
    }

    @Test
    public void fontSizeActionsChangeTheFontSizeOnlyAndNeverTouchTheKeyboard() {
        GestureActionDispatcher.perform(TerminalGestureAction.FONT_SIZE_UP, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.FONT_SIZE_DOWN, mTarget);
        assertEquals(List.of("fontUp", "fontDown"), mTarget.calls);
        assertEquals(0, mTarget.visibilityQueries);
    }

    @Test
    public void fontSizeActionsWorkWithTheKeyboardDisabledOrASingleSession() {
        mTarget.keyboardDisabled = true;
        mTarget.sessionCount = 1;
        GestureActionDispatcher.perform(TerminalGestureAction.FONT_SIZE_UP, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.FONT_SIZE_DOWN, mTarget);
        assertEquals(List.of("fontUp", "fontDown"), mTarget.calls);
    }

    @Test
    public void showKeyboardShowsEvenIfAlreadyVisible() {
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_KEYBOARD, mTarget);
        mTarget.keyboardVisible = true;
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_KEYBOARD, mTarget);
        assertEquals(List.of("show", "show"), mTarget.calls);
    }

    @Test
    public void toggleKeyboardShowsWhenHiddenAndHidesWhenVisible() {
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        mTarget.keyboardVisible = true;
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        assertEquals(List.of("show", "hide"), mTarget.calls);
    }

    @Test
    public void disabledKeyboardWinsOverKeyboardActions() {
        mTarget.keyboardDisabled = true;
        GestureActionDispatcher.perform(TerminalGestureAction.SHOW_KEYBOARD, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        mTarget.keyboardVisible = true;
        GestureActionDispatcher.perform(TerminalGestureAction.TOGGLE_KEYBOARD, mTarget);
        assertTrue(mTarget.calls.isEmpty());
        assertEquals(0, mTarget.visibilityQueries);
    }


    @Test
    public void sendKeysSendsEscape() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, TerminalKeySequence.parse("ESC"), mTarget);
        assertEquals(java.util.Arrays.asList("keys"), mTarget.calls);
        assertEquals(java.util.Arrays.asList(new TerminalKeySequence.Step("ESC", false, false, false, false)), mTarget.sent);
    }

    @Test
    public void sendKeysSendsCtrlL() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, TerminalKeySequence.parse("CTRL l"), mTarget);
        assertEquals(java.util.Arrays.asList(new TerminalKeySequence.Step("l", true, false, false, false)), mTarget.sent);
    }

    @Test
    public void sendKeysSendsTheSequenceInOrderWithPerKeyModifiers() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS,
            TerminalKeySequence.parse("ESC :wq ENTER CTRL ALT x UP"), mTarget);
        assertEquals(java.util.Arrays.asList(
            new TerminalKeySequence.Step("ESC", false, false, false, false),
            new TerminalKeySequence.Step(":wq", false, false, false, false),
            new TerminalKeySequence.Step("ENTER", false, false, false, false),
            new TerminalKeySequence.Step("x", true, true, false, false),
            new TerminalKeySequence.Step("UP", false, false, false, false)), mTarget.sent);
    }

    @Test
    public void sendKeysWithoutKeysDoesNothing() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, null, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, mTarget);
        assertTrue(mTarget.calls.isEmpty());
        assertTrue(mTarget.sent.isEmpty());
    }

    @Test
    public void runCommandPassesTextAndContextOnce() {
        GestureActionDispatcher.perform(TerminalGestureAction.RUN_COMMAND, null,
            "printf '%s' 'two words'", GestureCommandContext.NIX, mTarget);
        assertEquals(java.util.Collections.singletonList("command"), mTarget.calls);
        assertEquals("printf '%s' 'two words'", mTarget.command);
        assertEquals(GestureCommandContext.NIX, mTarget.commandContext);
    }

    @Test
    public void runCommandWithoutTextDoesNothing() {
        GestureActionDispatcher.perform(TerminalGestureAction.RUN_COMMAND, null, null,
            GestureCommandContext.SESSION, mTarget);
        GestureActionDispatcher.perform(TerminalGestureAction.RUN_COMMAND, null, "  ",
            GestureCommandContext.SESSION, mTarget);
        assertTrue(mTarget.calls.isEmpty());
    }

    @Test
    public void otherActionsIgnoreKeys() {
        GestureActionDispatcher.perform(TerminalGestureAction.NEXT_SESSION, TerminalKeySequence.parse("ESC"), mTarget);
        assertEquals(java.util.Arrays.asList("next"), mTarget.calls);
        assertTrue(mTarget.sent.isEmpty());
    }

    @Test
    public void sendKeysNeverTouchesKeyboardOrSessions() {
        GestureActionDispatcher.perform(TerminalGestureAction.SEND_KEYS, TerminalKeySequence.parse("ESC"), mTarget);
        assertEquals(0, mTarget.visibilityQueries);
        assertEquals(java.util.Arrays.asList("keys"), mTarget.calls);
    }

}
