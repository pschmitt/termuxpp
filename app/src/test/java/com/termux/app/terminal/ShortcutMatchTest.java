package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.termux.app.terminal.io.KeyboardShortcut;

import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ShortcutMatchTest {

    private static final int NEW = TermuxPropertyConstants.ACTION_SHORTCUT_CREATE_SESSION;
    private static final int RENAME = TermuxPropertyConstants.ACTION_SHORTCUT_RENAME_SESSION;

    @Test
    public void ctrlShiftAndCtrlOnTheSameLetterDiffer() {
        List<KeyboardShortcut> shortcuts = Arrays.asList(
            new KeyboardShortcut('t' | TermuxPropertyConstants.SHORTCUT_SHIFT, NEW),
            new KeyboardShortcut('t', RENAME));
        assertEquals(NEW, TermuxTerminalViewClient.matchShortcut(shortcuts, 'T').shortcutAction);
        assertEquals(RENAME, TermuxTerminalViewClient.matchShortcut(shortcuts, 't').shortcutAction);
    }

    @Test
    public void ctrlShiftNeedsShiftAndCtrlIgnoresCase() {
        List<KeyboardShortcut> shift = Arrays.asList(new KeyboardShortcut('t' | TermuxPropertyConstants.SHORTCUT_SHIFT, NEW));
        assertNull(TermuxTerminalViewClient.matchShortcut(shift, 't'));
        List<KeyboardShortcut> plain = Arrays.asList(new KeyboardShortcut('r', RENAME));
        assertEquals(RENAME, TermuxTerminalViewClient.matchShortcut(plain, 'R').shortcutAction);
        assertNull(TermuxTerminalViewClient.matchShortcut(null, 'r'));
    }
}
