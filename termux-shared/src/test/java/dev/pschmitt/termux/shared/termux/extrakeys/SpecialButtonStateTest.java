package dev.pschmitt.termux.shared.termux.extrakeys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the tap, double tap and long press behaviour of a modifier of the extra keys row.
 *
 * The state is read the way {@code TerminalView} reads the latched modifiers for every key, see
 * {@link #typeKey(SpecialButtonState, char)}.
 */
public class SpecialButtonStateTest {

    private static final long TIMEOUT = 300;

    private SpecialButtonState ctrl;
    private SpecialButtonState alt;

    @Before
    public void setUp() {
        ctrl = newState();
        alt = newState();
    }

    private static SpecialButtonState newState() {
        // No buttons are attached, so the state does not touch any view
        SpecialButtonState state = new SpecialButtonState(null);
        state.setIsCreated(true);
        return state;
    }

    /**
     * What the terminal sends for a letter key typed while the modifier may be active: reading the
     * modifier consumes a latched one, a locked one stays. Ctrl+letter is the letter without the
     * bits above 0x1f, so Ctrl+D is 0x04.
     */
    private static int typeKey(SpecialButtonState ctrl, char letter) {
        boolean ctrlDown = ctrl.read(true);
        return ctrlDown ? (letter & 0x1f) : letter;
    }

    @Test
    public void singleTapLatchesForOneKey() {
        ctrl.onTap(1000, TIMEOUT);
        assertTrue(ctrl.isActive);
        assertFalse(ctrl.isLocked);

        assertEquals(0x04, typeKey(ctrl, 'd'));
        // Consumed: the next key is plain
        assertFalse(ctrl.isActive);
        assertEquals('d', typeKey(ctrl, 'd'));
    }

    @Test
    public void doubleTapLocksUntilTappedAgain() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onTap(1200, TIMEOUT);
        assertTrue(ctrl.isActive);
        assertTrue(ctrl.isLocked);

        // Ctrl+D twice, and the button stays active after each key
        assertEquals(0x04, typeKey(ctrl, 'd'));
        assertTrue(ctrl.isActive);
        assertEquals(0x04, typeKey(ctrl, 'd'));
        assertTrue(ctrl.isActive);
        assertTrue(ctrl.isLocked);
    }

    @Test
    public void tapOnLockedModifierUnlocksAndKeysArePlainAgain() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onTap(1200, TIMEOUT);
        assertEquals(0x04, typeKey(ctrl, 'd'));

        ctrl.onTap(5000, TIMEOUT);
        assertFalse(ctrl.isActive);
        assertFalse(ctrl.isLocked);
        assertEquals('d', typeKey(ctrl, 'd'));
    }

    @Test
    public void tapOnLockedModifierUnlocksEvenIfFast() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onTap(1100, TIMEOUT);
        assertTrue(ctrl.isLocked);

        // A third quick tap does not lock again, it releases
        ctrl.onTap(1200, TIMEOUT);
        assertFalse(ctrl.isActive);
        assertFalse(ctrl.isLocked);
    }

    @Test
    public void slowSecondTapDoesNotLock() {
        ctrl.onTap(1000, TIMEOUT);
        // Later than the double tap timeout: this is a plain tap on a latched button, which
        // deactivates it like before
        ctrl.onTap(1000 + TIMEOUT + 1, TIMEOUT);
        assertFalse(ctrl.isActive);
        assertFalse(ctrl.isLocked);
        assertEquals('d', typeKey(ctrl, 'd'));
    }

    @Test
    public void secondTapAtExactlyTheTimeoutStillLocks() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onTap(1000 + TIMEOUT, TIMEOUT);
        assertTrue(ctrl.isLocked);
    }

    @Test
    public void tapAfterTheLatchWasConsumedStartsANewTap() {
        ctrl.onTap(1000, TIMEOUT);
        assertEquals(0x04, typeKey(ctrl, 'd'));

        // Tapped quickly again, but the key between the taps used up the latch: it latches again
        // and is not locked
        ctrl.onTap(1100, TIMEOUT);
        assertTrue(ctrl.isActive);
        assertFalse(ctrl.isLocked);
        assertEquals(0x04, typeKey(ctrl, 'd'));
        assertEquals('d', typeKey(ctrl, 'd'));
    }

    @Test
    public void unlockedModifierNeedsAFreshDoubleTapToLockAgain() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onTap(1100, TIMEOUT);
        ctrl.onTap(1200, TIMEOUT);
        assertFalse(ctrl.isActive);

        ctrl.onTap(1300, TIMEOUT);
        assertTrue(ctrl.isActive);
        assertFalse(ctrl.isLocked);
        ctrl.onTap(1400, TIMEOUT);
        assertTrue(ctrl.isLocked);
    }

    @Test
    public void longPressStillLocks() {
        ctrl.onLongPress();
        assertTrue(ctrl.isActive);
        assertTrue(ctrl.isLocked);
        assertEquals(0x04, typeKey(ctrl, 'd'));
        assertEquals(0x04, typeKey(ctrl, 'd'));

        // A tap releases a long press lock
        ctrl.onTap(1000, TIMEOUT);
        assertFalse(ctrl.isActive);
        assertFalse(ctrl.isLocked);
    }

    @Test
    public void longPressOnActiveModifierDeactivates() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onLongPress();
        assertFalse(ctrl.isActive);
        assertFalse(ctrl.isLocked);
    }

    @Test
    public void longPressForgetsThePreviousTap() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onLongPress();
        // Deactivated by the long press, so this tap latches
        ctrl.onTap(1100, TIMEOUT);
        assertTrue(ctrl.isActive);
        assertFalse(ctrl.isLocked);
    }

    @Test
    public void modifiersAreIndependent() {
        ctrl.onTap(1000, TIMEOUT);
        ctrl.onTap(1100, TIMEOUT);
        alt.onTap(1150, TIMEOUT);

        assertTrue(ctrl.isLocked);
        assertTrue(alt.isActive);
        // A tap on another modifier does not count as the second tap of a double tap
        assertFalse(alt.isLocked);

        assertTrue(alt.read(true));
        assertFalse(alt.isActive);
        assertTrue(ctrl.isActive);
        assertTrue(ctrl.isLocked);

        alt.onTap(1200, TIMEOUT);
        ctrl.onTap(1300, TIMEOUT);
        assertFalse(ctrl.isActive);
        assertTrue(alt.isActive);
    }

    @Test
    public void alternateHoldLatchesOnceAndCanBeCancelled() {
        alt.toggleLatch();
        assertTrue(alt.read(true));
        assertFalse(alt.read(true));
        assertFalse(ctrl.read(true));

        alt.toggleLatch();
        alt.toggleLatch();
        assertFalse(alt.read(true));
    }

    @Test
    public void tapCancelsAlternateAndDoubleTapLocksIt() {
        alt.toggleLatch();
        assertTrue(alt.onAlternateTap(1000, TIMEOUT));
        assertFalse(alt.read(false));

        assertTrue(alt.onAlternateTap(1100, TIMEOUT));
        assertTrue(alt.isLocked);
        assertTrue(alt.read(true));
        assertTrue(alt.read(true));

        assertTrue(alt.onAlternateTap(1200, TIMEOUT));
        assertFalse(alt.read(false));
        assertFalse(alt.onAlternateTap(1250, TIMEOUT));
    }

    @Test
    public void alternateDoesNotTakeALaterPrimaryTap() {
        alt.toggleLatch();
        assertTrue(alt.onAlternateTap(1000, TIMEOUT));
        assertFalse(alt.onAlternateTap(1000 + TIMEOUT + 1, TIMEOUT));

        alt.toggleLatch();
        assertTrue(alt.onAlternateTap(2000, TIMEOUT));
        assertFalse(alt.read(true));
        assertFalse(alt.onAlternateTap(2100, TIMEOUT));
    }

    @Test
    public void readIsFalseForAButtonThatIsNotCreated() {
        SpecialButtonState notCreated = new SpecialButtonState(null);
        notCreated.setIsActive(true);
        assertFalse(notCreated.read(true));
    }

}
