package dev.pschmitt.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction.KeyboardOperation;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction.SessionSwitch;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;

public class TerminalGestureActionTest {

    @Test
    public void gestureFeedbackPositionHasIndependentBottomCenterDefault() {
        assertEquals(TermuxPropertyConstants.DEFAULT_GESTURE_FEEDBACK_GRAVITY,
            TermuxSharedProperties.getGestureFeedbackGravityFromValue(null));
        assertEquals(TermuxPropertyConstants.DEFAULT_GESTURE_FEEDBACK_GRAVITY,
            TermuxSharedProperties.getGestureFeedbackGravityFromValue("invalid"));
        for (String value : TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION.keySet()) {
            assertEquals((int) TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION.get(value),
                TermuxSharedProperties.getGestureFeedbackGravityFromValue(value.toUpperCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    public void pickerPositionDefaultsToTopAndParses() {
        String key = TermuxPropertyConstants.KEY_TERMINAL_PICKER_POSITION;
        assertEquals("top", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
        assertEquals("top", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "bottom"));
        assertEquals("center", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "Center"));
        assertEquals("terminal", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "terminal"));
    }

    @Test
    public void tmuxSidebarStyleDefaultsToRounded() {
        String key = TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE;
        assertEquals("rounded", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
        assertEquals("rounded", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "invalid"));
        assertEquals("subtle", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "Subtle"));
    }

    @Test
    public void feedbackStyleDefaultsAndParses() {
        String key = TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE;
        assertEquals("soft-flash", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
        assertEquals("soft-flash", TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "invalid"));
        for (String style : TermuxPropertyConstants.GESTURE_FEEDBACK_STYLES) {
            assertEquals(style, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, style.toUpperCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    public void parsesEveryValue() {
        assertSame(TerminalGestureAction.NONE, TerminalGestureAction.parse("none"));
        assertSame(TerminalGestureAction.SHOW_KEYBOARD, TerminalGestureAction.parse("show-keyboard"));
        assertSame(TerminalGestureAction.TOGGLE_KEYBOARD, TerminalGestureAction.parse("toggle-keyboard"));
        assertSame(TerminalGestureAction.NEXT_SESSION, TerminalGestureAction.parse("next-session"));
        assertSame(TerminalGestureAction.PREVIOUS_SESSION, TerminalGestureAction.parse("previous-session"));
        assertSame(TerminalGestureAction.OPEN_SIDEBAR, TerminalGestureAction.parse("open-sidebar"));
        assertSame(TerminalGestureAction.SEND_KEYS, TerminalGestureAction.parse("send-keys"));
        assertSame(TerminalGestureAction.SEND_KEYS, TerminalGestureAction.parse(" Send-Keys "));
    }

    @Test
    public void sendKeysWithoutUsableKeysIsNone() {
        assertSame(TerminalGestureAction.NONE, TerminalGestureAction.effective(TerminalGestureAction.SEND_KEYS, false));
        assertSame(TerminalGestureAction.SEND_KEYS, TerminalGestureAction.effective(TerminalGestureAction.SEND_KEYS, true));
    }

    @Test
    public void runCommandWithoutConfiguredTextIsNone() {
        assertSame(TerminalGestureAction.NONE,
            TerminalGestureAction.effective(TerminalGestureAction.RUN_COMMAND, false, false));
        assertSame(TerminalGestureAction.RUN_COMMAND,
            TerminalGestureAction.effective(TerminalGestureAction.RUN_COMMAND, false, true));
    }

    @Test
    public void runCommandParsesAndIsAcceptedForGestureActionProperties() {
        assertSame(TerminalGestureAction.RUN_COMMAND, TerminalGestureAction.parse("run-command"));
        for (String key : new String[] {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}) {
            assertSame(TerminalGestureAction.RUN_COMMAND,
                TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(key, "run-command",
                    TerminalGestureAction.NONE));
        }
    }

    @Test
    public void otherActionsDoNotDependOnKeys() {
        for (TerminalGestureAction action : TerminalGestureAction.values()) {
            if (action == TerminalGestureAction.SEND_KEYS || action == TerminalGestureAction.RUN_COMMAND) continue;
            assertSame(action, TerminalGestureAction.effective(action, false));
            assertSame(action, TerminalGestureAction.effective(action, true));
        }
    }

    @Test
    public void sendKeysIsNeitherKeyboardNorSessionAction() {
        assertEquals(KeyboardOperation.NONE, TerminalGestureAction.SEND_KEYS.keyboardOperation(false, true));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.SEND_KEYS.sessionSwitch(5));
    }

    @Test
    public void everyActionRoundTripsThroughItsValue() {
        for (TerminalGestureAction action : TerminalGestureAction.values())
            assertSame(action, TerminalGestureAction.parse(action.getValue()));
    }

    @Test
    public void parseIgnoresCaseAndWhitespace() {
        assertSame(TerminalGestureAction.TOGGLE_KEYBOARD, TerminalGestureAction.parse("  Toggle-Keyboard \t"));
        assertSame(TerminalGestureAction.OPEN_SIDEBAR, TerminalGestureAction.parse("OPEN-SIDEBAR"));
    }

    @Test
    public void parseRejectsNullBlankAndUnknown() {
        assertNull(TerminalGestureAction.parse(null));
        assertNull(TerminalGestureAction.parse(""));
        assertNull(TerminalGestureAction.parse("   "));
        assertNull(TerminalGestureAction.parse("toggle"));
        assertNull(TerminalGestureAction.parse("toggle_keyboard"));
        assertNull(TerminalGestureAction.parse("2"));
    }

    @Test
    public void openMenuParsesAndIsAcceptedForEveryGestureKey() {
        assertSame(TerminalGestureAction.OPEN_MENU, TerminalGestureAction.parse("open-menu"));
        assertSame(TerminalGestureAction.OPEN_MENU, TerminalGestureAction.parse("  Open-Menu "));
        assertEquals("open-menu", TerminalGestureAction.OPEN_MENU.getValue());
        for (String key : new String[] {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}) {
            assertSame(TerminalGestureAction.OPEN_MENU, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
                key, "open-menu", TerminalGestureAction.NONE));
        }
        // Needs no keys, so it is never turned into none.
        assertSame(TerminalGestureAction.OPEN_MENU, TerminalGestureAction.effective(TerminalGestureAction.OPEN_MENU, false));
    }

    @Test
    public void twoFingerSwipeDownDefaultsToTmuxPickerAndCanBeDisabled() {
        String key = TermuxPropertyConstants.terminalMultiFingerSwipeProperty(2, "down", "action");
        assertEquals("open-tmux-switcher",
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
        assertEquals("none",
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "none"));
        String otherKey = TermuxPropertyConstants.terminalMultiFingerSwipeProperty(3, "down", "action");
        assertEquals("none",
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, otherKey, null));
    }

    @Test
    public void parseOrDefaultFallsBack() {
        assertSame(TerminalGestureAction.SHOW_KEYBOARD,
            TerminalGestureAction.parseOrDefault("bogus", TerminalGestureAction.SHOW_KEYBOARD));
        assertSame(TerminalGestureAction.NONE,
            TerminalGestureAction.parseOrDefault("none", TerminalGestureAction.SHOW_KEYBOARD));
    }

    @Test
    public void everyActionHasALabel() {
        for (TerminalGestureAction action : TerminalGestureAction.values())
            assertFalse(action.toString(), action.getLabel().isEmpty());
        assertEquals("New session", TerminalGestureAction.NEW_SESSION.getLabel());
        assertSame(TerminalGestureAction.TOGGLE_TEXT_INPUT, TerminalGestureAction.parse("toggle-text-input"));
    }

    @Test
    public void validValuesListsEveryValue() {
        assertEquals("none, show-keyboard, toggle-keyboard, toggle-text-input, next-session, previous-session, new-session, open-sidebar, open-session-switcher, open-tmux-switcher, open-menu, font-size-up, font-size-down, run-command, send-keys",
            TerminalGestureAction.getValidValues());
    }

    @Test
    public void commandContextParsingAndSessionInheritance() {
        assertSame(GestureCommandContext.NIX, GestureCommandContext.parse(" NIX "));
        assertSame(GestureCommandContext.SESSION,
            GestureCommandContext.parseOrDefault("invalid", GestureCommandContext.SESSION));
        assertTrue(GestureCommandContext.SESSION.shouldEnterNix(true));
        assertFalse(GestureCommandContext.SESSION.shouldEnterNix(false));
        assertTrue(GestureCommandContext.NIX.shouldEnterNix(false));
        assertFalse(GestureCommandContext.OUTER.shouldEnterNix(true));
    }

    @Test
    public void commandTextIsPreservedAndBlankTextIsUnset() {
        assertEquals("printf '%s' 'two words'", TermuxSharedProperties.getGestureCommandInternalPropertyValueFromValue(
            "printf '%s' 'two words'"));
        assertNull(TermuxSharedProperties.getGestureCommandInternalPropertyValueFromValue(" \t"));
    }

    @Test
    public void commandPropertiesAreRedactedFromPropertyDumps() {
        assertEquals("[redacted]", TermuxSharedProperties.getPropertyValueForLog(
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND, "secret command"));
        assertEquals("[redacted]", TermuxSharedProperties.getPropertyValueForLog(
            TermuxPropertyConstants.KEY_EXTRA_KEYS, "[[{\"command\":\"secret command\"}]]"));
        assertEquals("[[ESC,TAB]]", TermuxSharedProperties.getPropertyValueForLog(
            TermuxPropertyConstants.KEY_EXTRA_KEYS, "[[ESC,TAB]]"));
    }

    @Test
    public void showKeyboardAlwaysShowsUnlessDisabled() {
        assertEquals(KeyboardOperation.SHOW, TerminalGestureAction.SHOW_KEYBOARD.keyboardOperation(false, false));
        assertEquals(KeyboardOperation.SHOW, TerminalGestureAction.SHOW_KEYBOARD.keyboardOperation(false, true));
    }

    @Test
    public void toggleKeyboardHidesVisibleAndShowsHidden() {
        assertEquals(KeyboardOperation.SHOW, TerminalGestureAction.TOGGLE_KEYBOARD.keyboardOperation(false, false));
        assertEquals(KeyboardOperation.HIDE, TerminalGestureAction.TOGGLE_KEYBOARD.keyboardOperation(false, true));
    }

    @Test
    public void disabledKeyboardWins() {
        for (boolean visible : new boolean[] {false, true}) {
            assertEquals(KeyboardOperation.NONE, TerminalGestureAction.SHOW_KEYBOARD.keyboardOperation(true, visible));
            assertEquals(KeyboardOperation.NONE, TerminalGestureAction.TOGGLE_KEYBOARD.keyboardOperation(true, visible));
        }
    }

    @Test
    public void nonKeyboardActionsLeaveTheKeyboardAlone() {
        for (TerminalGestureAction action : new TerminalGestureAction[] {
            TerminalGestureAction.NONE, TerminalGestureAction.NEXT_SESSION,
            TerminalGestureAction.PREVIOUS_SESSION, TerminalGestureAction.OPEN_SIDEBAR,
            TerminalGestureAction.OPEN_MENU, TerminalGestureAction.FONT_SIZE_UP,
            TerminalGestureAction.FONT_SIZE_DOWN}) {
            assertEquals(KeyboardOperation.NONE, action.keyboardOperation(false, false));
            assertEquals(KeyboardOperation.NONE, action.keyboardOperation(false, true));
        }
    }

    @Test
    public void sessionActionsNavigateWithAtLeastOneSession() {
        assertEquals(SessionSwitch.NEXT, TerminalGestureAction.NEXT_SESSION.sessionSwitch(1));
        assertEquals(SessionSwitch.PREVIOUS, TerminalGestureAction.PREVIOUS_SESSION.sessionSwitch(1));
        assertEquals(SessionSwitch.NEXT, TerminalGestureAction.NEXT_SESSION.sessionSwitch(2));
        assertEquals(SessionSwitch.PREVIOUS, TerminalGestureAction.PREVIOUS_SESSION.sessionSwitch(5));
        for (int count : new int[] {-1, 0}) {
            assertEquals(SessionSwitch.NONE, TerminalGestureAction.NEXT_SESSION.sessionSwitch(count));
            assertEquals(SessionSwitch.NONE, TerminalGestureAction.PREVIOUS_SESSION.sessionSwitch(count));
        }
    }

    @Test
    public void otherActionsDoNotSwitchSessions() {
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.NONE.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.SHOW_KEYBOARD.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.TOGGLE_KEYBOARD.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.OPEN_SIDEBAR.sessionSwitch(3));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.OPEN_MENU.sessionSwitch(3));
    }

    @Test
    public void propertiesConverterAcceptsValidAndMissingValues() {
        // Invalid values are logged through the Android Logger, which is not available here.
        assertSame(TerminalGestureAction.TOGGLE_KEYBOARD, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION, "toggle-keyboard", TerminalGestureAction.NONE));
        assertSame(TerminalGestureAction.OPEN_SIDEBAR, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION, " Open-Sidebar", TerminalGestureAction.NONE));
        assertSame(TerminalGestureAction.SHOW_KEYBOARD, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION, null, TerminalGestureAction.SHOW_KEYBOARD));
    }

    /** SharedProperties only caches primitives and Strings: an enum would silently be dropped and the default used. */
    @Test
    public void loadedValuesAreCacheableByTheLoaderAndParseBack() {
        String[] keys = {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION, TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION, TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION};
        for (String key : keys) {
            for (TerminalGestureAction action : TerminalGestureAction.values()) {
                Object internal = TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, action.getValue());
                assertTrue(key + " " + action, dev.pschmitt.termux.shared.settings.properties.SharedProperties.putToMap(new java.util.HashMap<>(), key, internal));
                assertSame(action, TerminalGestureAction.parse((String) internal));
            }
            Object missing = TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null);
            assertTrue(dev.pschmitt.termux.shared.settings.properties.SharedProperties.putToMap(new java.util.HashMap<>(), key, missing));
        }
    }

    @Test
    public void propertyKeysDefaultsAndLoading() {
        assertEquals("terminal-single-tap-action", TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION);
        assertEquals("terminal-double-tap-action", TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION);
        assertEquals("terminal-triple-tap-action", TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION);
        assertEquals("extra-keys-swipe-left-action", TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION);

        assertSame(TerminalGestureAction.SHOW_KEYBOARD, TermuxPropertyConstants.DEFAULT_TERMINAL_SINGLE_TAP_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_TERMINAL_DOUBLE_TAP_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_TERMINAL_TRIPLE_TAP_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_LEFT_ACTION);
        assertSame(TerminalGestureAction.NEXT_SESSION, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_RIGHT_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION);
        assertSame(TerminalGestureAction.NONE, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION);
        assertEquals("extra-keys-swipe-right-action", TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION);
        assertEquals("extra-keys-long-swipe-left-action", TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION);
        assertEquals("extra-keys-long-swipe-right-action", TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION);
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION));

        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION));
    }

    @Test
    public void fontSizeActionsParseAndNeedNoKeys() {
        assertSame(TerminalGestureAction.FONT_SIZE_UP, TerminalGestureAction.parse("font-size-up"));
        assertSame(TerminalGestureAction.FONT_SIZE_DOWN, TerminalGestureAction.parse(" Font-Size-Down "));
        assertEquals("font-size-up", TerminalGestureAction.FONT_SIZE_UP.getValue());
        assertEquals("font-size-down", TerminalGestureAction.FONT_SIZE_DOWN.getValue());
        assertSame(TerminalGestureAction.FONT_SIZE_UP, TerminalGestureAction.effective(TerminalGestureAction.FONT_SIZE_UP, false));
        assertSame(TerminalGestureAction.FONT_SIZE_DOWN, TerminalGestureAction.effective(TerminalGestureAction.FONT_SIZE_DOWN, false));
        assertEquals(SessionSwitch.NONE, TerminalGestureAction.FONT_SIZE_UP.sessionSwitch(5));
        for (String key : new String[] {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}) {
            assertSame(TerminalGestureAction.FONT_SIZE_UP, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
                key, "font-size-up", TerminalGestureAction.NONE));
            assertSame(TerminalGestureAction.FONT_SIZE_DOWN, TermuxSharedProperties.getGestureActionInternalPropertyValueFromValue(
                key, "font-size-down", TerminalGestureAction.NONE));
        }
    }

    @Test
    public void onlyTheFontSizeActionsRepeatWhileAKeyIsHeld() {
        for (TerminalGestureAction action : TerminalGestureAction.values()) {
            boolean font = action == TerminalGestureAction.FONT_SIZE_UP || action == TerminalGestureAction.FONT_SIZE_DOWN;
            assertEquals(action.getValue(), font, action.isRepeatable());
            // The initial press always runs, repeats only for repeatable actions.
            assertTrue(action.runsOnKeyDown(0));
            assertEquals(action.getValue(), font, action.runsOnKeyDown(1));
            assertEquals(action.getValue(), font, action.runsOnKeyDown(7));
        }
    }

    @Test
    public void volumeKeyActionValuesAreModifierOrAnAction() {
        assertEquals("modifier", TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER);
        // Missing is the modifier, the behaviour without configuration.
        for (String key : new String[] {TermuxPropertyConstants.KEY_VOLUME_UP_ACTION, TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION}) {
            assertEquals("modifier", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, null));
            assertEquals("modifier", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, " Modifier "));
            assertEquals("font-size-up", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, "FONT-SIZE-UP"));
            assertEquals("font-size-down", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, "font-size-down"));
            assertEquals("none", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, "none"));
            assertEquals("send-keys", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, "send-keys"));
            assertEquals("next-session", TermuxSharedProperties.getVolumeKeyActionInternalPropertyValueFromValue(key, "next-session"));
        }
        // The two keys are independent properties that are loaded and kept apart.
        assertEquals("volume-up-action", TermuxPropertyConstants.KEY_VOLUME_UP_ACTION);
        assertEquals("volume-down-action", TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION);
        assertEquals("volume-up-keys", TermuxPropertyConstants.KEY_VOLUME_UP_KEYS);
        assertEquals("volume-down-keys", TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS);
    }

}
