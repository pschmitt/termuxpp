package dev.pschmitt.termux.shared.termux.extrakeys;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** The {@link Class} that maintains a state of a {@link SpecialButton} */
public class SpecialButtonState {

    /** If special button has been created for the {@link ExtraKeysView}. */
    boolean isCreated = false;
    /** If special button is active. */
    boolean isActive = false;
    /** If special button is locked due to long hold on it and should not be deactivated if its
     * state is read. */
    boolean isLocked = false;

    List<MaterialButton> buttons = new ArrayList<>();
    final List<Runnable> presentationUpdates = new ArrayList<>();

    ExtraKeysView mExtraKeysView;

    /**
     * Initialize a {@link SpecialButtonState} to maintain state of a {@link SpecialButton}.
     *
     * @param extraKeysView The {@link ExtraKeysView} instance in which the {@link SpecialButton}
     *                      is to be registered.
     */
    public SpecialButtonState(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    /** Set {@link #isCreated}. */
    public void setIsCreated(boolean value) {
        isCreated = value;
    }

    /** Set {@link #isActive}. */
    public void setIsActive(boolean value) {
        isActive = value;
        for (MaterialButton button : buttons) {
            button.setTextColor(value ? mExtraKeysView.getButtonActiveTextColor() : mExtraKeysView.getButtonTextColor());
            // Fill the background of an active (latched or locked) button if configured
            button.setBackgroundColor(mExtraKeysView.getSpecialButtonBackgroundColor(value));
        }
        for (Runnable update : presentationUpdates) update.run();
    }

    /** Set {@link #isLocked}. */
    public void setIsLocked(boolean value) {
        isLocked = value;
    }

    /** Value of {@link #mLastTapTime} when there is no tap that could start a double tap. */
    private static final long NO_TAP = Long.MIN_VALUE;

    /** The time of the tap that latched the button, to detect a double tap on it. */
    private long mLastTapTime = NO_TAP;
    private long mLastAlternateTapTime = NO_TAP;

    /**
     * Handle a tap (a click that is not a long press) on the button.
     *
     * <ul>
     *   <li>Not active: latch it for the next key only, and remember when.</li>
     *   <li>Latched (active, not locked) and tapped again within {@code doubleTapTimeoutMs} of the
     *   tap that latched it: a double tap, lock it, so it stays active for any number of keys.</li>
     *   <li>Latched and tapped again later, or locked: deactivate and unlock it.</li>
     * </ul>
     *
     * @param now The time of the tap in milliseconds, from a monotonic clock.
     * @param doubleTapTimeoutMs The most milliseconds between the two taps of a double tap.
     */
    public void onTap(long now, long doubleTapTimeoutMs) {
        mLastAlternateTapTime = NO_TAP;
        if (!isActive) {
            setIsActive(true);
            mLastTapTime = now;
        } else if (!isLocked && mLastTapTime != NO_TAP && now - mLastTapTime <= doubleTapTimeoutMs) {
            setIsLocked(true);
            mLastTapTime = NO_TAP;
        } else {
            setIsActive(false);
            setIsLocked(false);
            mLastTapTime = NO_TAP;
        }
    }

    /** Toggle a one-shot modifier selected by holding another key. */
    void toggleLatch() {
        mLastAlternateTapTime = NO_TAP;
        setIsLocked(false);
        mLastTapTime = NO_TAP;
        setIsActive(!isActive);
    }

    /** Consume a tap on a shared key when its alternate modifier owns the tap. */
    boolean onAlternateTap(long now, long doubleTapTimeoutMs) {
        if (isActive) {
            boolean wasLocked = isLocked;
            toggleLatch();
            if (!wasLocked) mLastAlternateTapTime = now;
            return true;
        }

        boolean doubleTap = mLastAlternateTapTime != NO_TAP && now - mLastAlternateTapTime <= doubleTapTimeoutMs;
        mLastAlternateTapTime = NO_TAP;
        if (!doubleTap) return false;
        setIsLocked(true);
        setIsActive(true);
        return true;
    }

    /** Handle a long press on the button: lock it if it is not active, otherwise deactivate and unlock it. */
    public void onLongPress() {
        mLastAlternateTapTime = NO_TAP;
        setIsLocked(!isActive);
        setIsActive(!isActive);
        mLastTapTime = NO_TAP;
    }

    /**
     * Read whether the button is active, as the next key is sent.
     *
     * @param autoSetInActive Deactivate the button if it is only latched. A locked one stays active.
     */
    public boolean read(boolean autoSetInActive) {
        if (autoSetInActive) mLastAlternateTapTime = NO_TAP;
        if (!isCreated || !isActive)
            return false;
        if (autoSetInActive && !isLocked)
            setIsActive(false);
        return true;
    }

}
