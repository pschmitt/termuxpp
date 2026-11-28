package dev.pschmitt.termux.shared.termux.settings.properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SessionShortcutTest {

    private static final String KEY = TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION;

    @Test
    public void parsesCtrlShortcut() {
        assertEquals(Integer.valueOf('t'), TermuxSharedProperties.getCodePointForSessionShortcuts(KEY, "Ctrl+T"));
    }

    @Test
    public void parsesCtrlShiftLetters() {
        assertEquals(Integer.valueOf('t' | TermuxPropertyConstants.SHORTCUT_SHIFT),
            TermuxSharedProperties.getCodePointForSessionShortcuts(KEY, "Ctrl + Shift + T"));
    }

    @Test
    public void createSessionDefaultsToCtrlShiftT() {
        assertEquals(Integer.valueOf('t' | TermuxPropertyConstants.SHORTCUT_SHIFT),
            TermuxSharedProperties.getCodePointForSessionShortcuts(KEY, null));
        assertNull(TermuxSharedProperties.getCodePointForSessionShortcuts(
            TermuxPropertyConstants.KEY_SHORTCUT_PALETTE, null));
    }

    @Test
    public void disabledRemovesTheShortcut() {
        assertNull(TermuxSharedProperties.getCodePointForSessionShortcuts(KEY, "disabled"));
        assertNull(TermuxSharedProperties.getCodePointForSessionShortcuts(KEY, " DISABLED "));
    }
}
