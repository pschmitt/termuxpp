package com.termux.app.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.text.InputType;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

// Robolectric because ExtraKeysInfo needs android.text.TextUtils, which the plain JVM stubs out.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PropertyInputSpecTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static void valid(String key, String... values) {
        for (String value : values) assertNull(key + "=" + value, PropertyInputSpec.validationError(key, value));
    }

    private static void invalid(String key, String... values) {
        for (String value : values) {
            String error = PropertyInputSpec.validationError(key, value);
            assertNotNull(key + "=" + value + " should be rejected", error);
            assertFalse(error.isEmpty());
        }
    }

    @Test
    public void everyPropertyHasHelp() {
        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            String help = PropertyInputSpec.help(key);
            assertFalse(key + " has no help", help.trim().isEmpty());
            assertTrue(key + " help is too short: " + help, help.length() > 30);
        }
    }

    @Test
    public void helpMentionsDefaultsAndRanges() {
        assertTrue(PropertyInputSpec.help("terminal-transcript-rows").contains("100 to 50000"));
        assertTrue(PropertyInputSpec.help("terminal-transcript-rows").contains("Default: 2000"));
        assertTrue(PropertyInputSpec.help("terminal-cursor-blink-rate").contains("100 to 2000"));
        assertTrue(PropertyInputSpec.help("terminal-cursor-blink-rate").contains("0"));
        assertTrue(PropertyInputSpec.help("terminal-toolbar-height").contains("0.4 to 3"));
        assertTrue(PropertyInputSpec.help("extra-keys-button-corner-radius").contains("32"));
        assertTrue(PropertyInputSpec.help("night-mode").contains("system"));
        assertTrue(PropertyInputSpec.help("fullscreen").contains("Default: false"));
        assertTrue(PropertyInputSpec.help("extra-keys-text-all-caps").contains("Default: true"));
        assertTrue(PropertyInputSpec.help("extra-keys-style").contains("arrows-only"));
        assertTrue(PropertyInputSpec.help("volume-keys").contains("virtual"));
    }

    @Test
    public void helpOfUnknownKeyIsEmpty() {
        assertEquals("", PropertyInputSpec.help("nope"));
        assertEquals("", PropertyInputSpec.help(null));
    }

    @Test
    public void emptyAndNullResetEveryProperty() {
        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            assertNull(key, PropertyInputSpec.validationError(key, null));
            assertNull(key, PropertyInputSpec.validationError(key, ""));
        }
    }

    @Test
    public void unknownPropertyIsRejected() {
        invalid("nope", "1");
        assertNotNull(PropertyInputSpec.validationError(null, "1"));
    }

    @Test
    public void booleans() {
        for (String key : TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST) {
            valid(key, "true", "false", "TRUE", "False");
            invalid(key, "yes", "1", "on", " true", "true ");
        }
        for (String key : TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST) {
            valid(key, "true", "false");
            invalid(key, "no");
        }
        valid(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, "true");
    }

    @Test
    public void enums() {
        valid("bell-character", "vibrate", "beep", "ignore", "BEEP");
        invalid("bell-character", "silent", "1");
        valid("terminal-cursor-style", "block", "underline", "bar");
        invalid("terminal-cursor-style", "line");
        valid("back-key", "back", "escape");
        invalid("back-key", "home");
        valid("night-mode", "true", "false", "system");
        invalid("night-mode", "auto");
        valid("soft-keyboard-toggle-behaviour", "show/hide", "enable/disable");
        invalid("soft-keyboard-toggle-behaviour", "show", "enable-disable");
        valid(TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE, "soft-flash", "touch-ripples",
            "edge-glow", "light-sweep", "touch-bloom", "corner-brackets", "SOFT-FLASH");
        invalid(TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE, "sparkles");
        valid("volume-keys", "virtual", "volume");
        invalid("volume-keys", "mute");
        valid(TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT, "session", "nix", "outer");
        valid(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE, "^tmux(:[^@]+)?@server\\b");
        invalid(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE, "[");
        invalid(TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT, "native", "guest");
        valid(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION, "run-command");
        invalid(TermuxPropertyConstants.KEY_VOLUME_UP_ACTION, "run-command");
        invalid(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND, "  ", "\t");
    }

    @Test
    public void gestureCommandContextIsOfferedInSettings() {
        assertArrayEquals(new String[] {"session", "nix", "outer"},
            PropertyInputSpec.choices(TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT));
        assertTrue(PropertyInputSpec.help(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND)
            .contains("new visible terminal session"));
    }

    @Test
    public void wholeNumbers() {
        valid("terminal-margin-horizontal", "0", "3", "100");
        invalid("terminal-margin-horizontal", "-1", "101", "3.5", "abc", "1 ", " 1");
        valid("terminal-margin-vertical", "0", "100");
        invalid("terminal-margin-vertical", "101", "-1");
        valid("terminal-transcript-rows", "100", "2000", "50000");
        invalid("terminal-transcript-rows", "99", "50001", "0", "-5");
        valid("delete-tmpdir-files-older-than-x-days-on-exit", "-1", "0", "3", "100000");
        invalid("delete-tmpdir-files-older-than-x-days-on-exit", "-2", "100001", "1.5", "x");
    }

    @Test
    public void cursorBlinkRateAllowsZeroForOff() {
        valid("terminal-cursor-blink-rate", "0", "100", "500", "2000");
        invalid("terminal-cursor-blink-rate", "1", "99", "2001", "-100", "slow");
    }

    @Test
    public void toolbarHeight() {
        valid("terminal-toolbar-height", "0.4", "1", "1.5", "3", "3.0", "2");
        invalid("terminal-toolbar-height", "0.39", "3.01", "0", "-1", "NaN", "Infinity", "-Infinity", "big");
    }

    @Test
    public void cornerRadius() {
        valid("extra-keys-button-corner-radius", "0", "8", "6.5", "32", "32.0", " 8 ");
        invalid("extra-keys-button-corner-radius", "-1", "+8", "33", "8dp", "1e1", "0.12345", ".5", "abc");
    }

    @Test
    public void colors() {
        for (String key : new String[] {"extra-keys-button-active-text-color", "extra-keys-button-active-background-color"}) {
            valid(key, "#fff", "#FF8800", "#ff8800", "#80ff8800", " #123456 ");
            invalid(key, "ff8800", "#ff88", "#ff88001", "#gggggg", "red", "#", "#12345678 9");
        }
    }

    @Test
    public void gestureActions() {
        String[] keys = {"terminal-single-tap-action", "terminal-double-tap-action",
            "terminal-triple-tap-action", "extra-keys-swipe-left-action", "extra-keys-swipe-right-action",
            "extra-keys-long-swipe-left-action", "extra-keys-long-swipe-right-action"};
        for (String key : keys) {
            valid(key, "none", "show-keyboard", "toggle-keyboard", "next-session", "previous-session",
                "new-session", "toggle-text-input",
                "open-sidebar", "open-menu", "font-size-up", "font-size-down", "send-keys", " Open-Menu ");
            invalid(key, "modifier", "keyboard", "tap");
        }
    }

    @Test
    public void volumeActionsAllowModifierToo() {
        for (String key : new String[] {"volume-up-action", "volume-down-action"}) {
            valid(key, "modifier", " Modifier ", "none", "send-keys", "font-size-up");
            invalid(key, "ctrl", "fn");
        }
    }

    @Test
    public void keySequences() {
        String[] keys = {"terminal-single-tap-keys", "terminal-double-tap-keys", "terminal-triple-tap-keys",
            "extra-keys-swipe-left-keys", "extra-keys-swipe-right-keys", "extra-keys-long-swipe-left-keys",
            "extra-keys-long-swipe-right-keys", "volume-up-keys", "volume-down-keys"};
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 65; i++) tooLong.append("ESC ");
        for (String key : keys) {
            valid(key, "ESC", "CTRL l", "ALT x", "UP UP ENTER", "CTRL ALT l");
            invalid(key, "   ", "CTRL", "ESC CTRL", "KEYBOARD", "PASTE", tooLong.toString());
        }
    }

    @Test
    public void shortcuts() {
        for (String key : new String[] {"shortcut.create-session", "shortcut.next-session",
            "shortcut.previous-session", "shortcut.rename-session"}) {
            valid(key, "ctrl+t", "CTRL+T", "ctrl + 2", " ctrl+r ", "ctrl+ab", "disabled", " DISABLED ");
            invalid(key, "t", "disable", "off", "ctrl", "ctrl+", "ctrl++", "alt+t", "ctrl+alt+t", "ctrl+abc", "ctrl-t", "ctrl+ ");
        }
    }

    @Test
    public void defaultWorkingDirectory() throws Exception {
        File directory = temporary.newFolder("work");
        File file = temporary.newFile("file");
        valid("default-working-directory", directory.getAbsolutePath());
        invalid("default-working-directory", file.getAbsolutePath(),
            new File(directory, "missing").getAbsolutePath(), "relative/missing");
    }

    @Test
    public void extraKeys() {
        valid("extra-keys",
            "[]",
            "[[\"ESC\"]]",
            "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]",
            "[[{macro: \"CTRL f d\", display: \"tmux exit\", popup: {macro: \"CTRL f BKSP\", display: \"x\"}}]]",
            "[[{key: 'ESC', popup: 'HOME'}, {display: 'a', key: 'b'}]]",
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
        invalid("extra-keys",
            "ESC TAB",
            "[[\"ESC\"",
            "[\"ESC\"]",
            "[[1]]",
            "[[{}]]",
            "[[{key: 'a', macro: 'b'}]]",
            "{\"a\": 1}");
    }

    @Test
    public void extraKeysErrorExplainsTheFormat() {
        String error = PropertyInputSpec.validationError("extra-keys", "ESC");
        assertNotNull(error);
        assertTrue(error, error.contains("JSON array of rows"));
    }

    @Test
    public void extraKeysStyle() {
        valid("extra-keys-style", "default", "arrows-only", "arrows-all", "all", "none");
        invalid("extra-keys-style", "Default", "ALL", "arrows", "fancy");
        String error = PropertyInputSpec.validationError("extra-keys-style", "fancy");
        assertTrue(error, error.contains("arrows-only"));
    }

    @Test
    public void choicesForPickerProperties() {
        assertArrayEquals(new String[] {"default", "arrows-only", "arrows-all", "all", "none"},
            PropertyInputSpec.choices("extra-keys-style"));
        assertArrayEquals(new String[] {"session", "nix", "outer"},
            PropertyInputSpec.choices(TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT));
        String feedbackKey = TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE;
        assertArrayEquals(new String[] {"soft-flash", "touch-ripples", "edge-glow", "light-sweep",
            "touch-bloom", "corner-brackets"}, PropertyInputSpec.choices(feedbackKey));
        String engineKey = TermuxPropertyConstants.KEY_TERMINAL_ENGINE;
        assertArrayEquals(new String[] {"termux", "libghostty"}, PropertyInputSpec.choices(engineKey));
        valid(engineKey, "termux", "libghostty", "LIBGHOSTTY");
        invalid(engineKey, "ghostty", "libghostty-vt");
        String toastKey = TermuxPropertyConstants.KEY_TERMINAL_TOAST_POSITION;
        String[] toastPositions = {
            "top-left", "top-center", "top-right", "center-left", "center", "center-right",
            "bottom-left", "bottom-center", "bottom-right"
        };
        assertArrayEquals(toastPositions, PropertyInputSpec.choices(toastKey));
        String gesturePositionKey = TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION;
        assertArrayEquals(toastPositions, PropertyInputSpec.choices(gesturePositionKey));
        valid(gesturePositionKey, toastPositions);
        invalid(gesturePositionKey, "top", "center-top", "bottom right");
        valid(toastKey, toastPositions);
        invalid(toastKey, "top", "center-top", "bottom right");
        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            if (key.equals("extra-keys-style") || key.equals(TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT)
                || key.equals(toastKey) || key.equals(engineKey) || key.equals(feedbackKey)
                || key.equals(gesturePositionKey)) continue;
            assertEquals(key, 0, PropertyInputSpec.choices(key).length);
        }
        assertEquals(0, PropertyInputSpec.choices(null).length);
    }

    @Test
    public void choicesAreACopy() {
        PropertyInputSpec.choices("extra-keys-style")[0] = "changed";
        assertEquals("default", PropertyInputSpec.choices("extra-keys-style")[0]);
    }

    @Test
    public void everyChoiceIsValid() {
        for (String choice : PropertyInputSpec.choices("extra-keys-style")) valid("extra-keys-style", choice);
    }

    @Test
    public void numberInputTypes() {
        assertEquals(InputType.TYPE_CLASS_NUMBER, PropertyInputSpec.inputType("terminal-margin-horizontal"));
        assertEquals(InputType.TYPE_CLASS_NUMBER, PropertyInputSpec.inputType("terminal-margin-vertical"));
        assertEquals(InputType.TYPE_CLASS_NUMBER, PropertyInputSpec.inputType("terminal-transcript-rows"));
        assertEquals(InputType.TYPE_CLASS_NUMBER, PropertyInputSpec.inputType("terminal-cursor-blink-rate"));
        assertEquals(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED,
            PropertyInputSpec.inputType("delete-tmpdir-files-older-than-x-days-on-exit"));
        int decimal = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        assertEquals(decimal, PropertyInputSpec.inputType("terminal-toolbar-height"));
        assertEquals(decimal, PropertyInputSpec.inputType("extra-keys-button-corner-radius"));
    }

    @Test
    public void textInputTypes() {
        int plain = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        assertEquals(plain | InputType.TYPE_TEXT_VARIATION_URI, PropertyInputSpec.inputType("default-working-directory"));
        assertEquals(plain | InputType.TYPE_TEXT_FLAG_MULTI_LINE, PropertyInputSpec.inputType("extra-keys"));
        assertEquals(plain, PropertyInputSpec.inputType("shortcut.create-session"));
        assertEquals(plain, PropertyInputSpec.inputType("extra-keys-button-active-text-color"));
        assertEquals(plain, PropertyInputSpec.inputType("terminal-double-tap-keys"));
        assertEquals(plain, PropertyInputSpec.inputType("extra-keys-style"));
        assertEquals(InputType.TYPE_CLASS_TEXT, PropertyInputSpec.inputType("nope"));
        assertEquals(InputType.TYPE_CLASS_TEXT, PropertyInputSpec.inputType(null));
    }

    @Test
    public void everyPropertyHasATextOrNumberClass() {
        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST) {
            int type = PropertyInputSpec.inputType(key);
            int typeClass = type & InputType.TYPE_MASK_CLASS;
            assertTrue(key, typeClass == InputType.TYPE_CLASS_TEXT || typeClass == InputType.TYPE_CLASS_NUMBER);
        }
    }
}
