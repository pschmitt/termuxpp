package dev.pschmitt.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * An action that a terminal tap or an extra keys swipe can be mapped to with the
 * {@link TermuxPropertyConstants#KEY_TERMINAL_SINGLE_TAP_ACTION},
 * {@link TermuxPropertyConstants#KEY_TERMINAL_DOUBLE_TAP_ACTION},
 * {@link TermuxPropertyConstants#KEY_TERMINAL_TRIPLE_TAP_ACTION} and
 * {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION},
 * {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION},
 * {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION} and
 * {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION} properties of
 * {@code termux.properties}, and that a volume button can be mapped to with the
 * {@link TermuxPropertyConstants#KEY_VOLUME_UP_ACTION} and
 * {@link TermuxPropertyConstants#KEY_VOLUME_DOWN_ACTION} properties. {@link #RUN_COMMAND} is
 * only supported for taps and extra-keys swipes, not volume buttons.
 *
 * This is pure policy without any Android dependency, so that it can be unit tested.
 */
public enum TerminalGestureAction {

    /** Do nothing. For a tap, the terminal handles it alone, for example as a mouse click. */
    NONE("none"),

    /** Show the soft keyboard. Showing it while it is already visible does nothing. */
    SHOW_KEYBOARD("show-keyboard"),

    /** Hide the soft keyboard if it is visible, otherwise show it. */
    TOGGLE_KEYBOARD("toggle-keyboard"),

    /**
     * Switch the extra keys toolbar to its text input, or back to the extra keys if the text input
     * is shown. Does nothing while the toolbar is hidden.
     */
    TOGGLE_TEXT_INPUT("toggle-text-input"),

    /** Switch to the next terminal session, wrapping around after the last one. */
    NEXT_SESSION("next-session"),

    /** Switch to the previous terminal session, wrapping around before the first one. */
    PREVIOUS_SESSION("previous-session"),

    /** Open a new terminal session and switch to it, like the "New session" button does. */
    NEW_SESSION("new-session"),

    /** Open the sidebar (the drawer) with the list of sessions. */
    OPEN_SIDEBAR("open-left-sidebar"),
    OPEN_RIGHT_SIDEBAR("open-right-sidebar"),

    /** Open the compact session picker. */
    OPEN_SESSION_SWITCHER("open-session-switcher"),
    OPEN_TMUX_SWITCHER("open-tmux-switcher"),

    /**
     * Open the terminal menu, the same bottom sheet that a long press on the terminal and then
     * "More" opens.
     */
    OPEN_MENU("open-menu"),

    /** Increase the font size of the terminal by one step, like pinching out does. */
    FONT_SIZE_UP("font-size-up"),

    /** Decrease the font size of the terminal by one step, like pinching in does. */
    FONT_SIZE_DOWN("font-size-down"),

    /** Run the command configured for the gesture in a new terminal session. */
    RUN_COMMAND("run-command"),

    /**
     * Send keys to the terminal, like Escape or Ctrl+L, given by the {@code *-keys} property of the
     * gesture, for example {@link TermuxPropertyConstants#KEY_TERMINAL_DOUBLE_TAP_KEYS}. Without
     * usable keys the action is {@link #NONE}, see {@link #effective(TerminalGestureAction, boolean)}.
     */
    SEND_KEYS("send-keys");

    /** What to do with the soft keyboard, see {@link #keyboardOperation(boolean, boolean)}. */
    public enum KeyboardOperation { NONE, SHOW, HIDE }

    /** Which way to switch sessions, see {@link #sessionSwitch(int)}. */
    public enum SessionSwitch { NONE, NEXT, PREVIOUS }

    private final String mValue;

    TerminalGestureAction(String value) {
        mValue = value;
    }

    /** The value to use in {@code termux.properties}, like {@code toggle-keyboard}. */
    @NonNull
    public String getValue() {
        return mValue;
    }

    /**
     * Parses the value of a property, ignoring case and surrounding whitespace.
     *
     * @return The action or {@code null} if the value is {@code null}, blank or unknown.
     */
    @Nullable
    public static TerminalGestureAction parse(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("open-sidebar".equals(normalized)) return OPEN_SIDEBAR;
        for (TerminalGestureAction action : values()) {
            if (action.mValue.equals(normalized)) return action;
        }
        return null;
    }

    /** Returns the action for the value, or {@code defaultAction} if it is {@code null} or invalid. */
    @NonNull
    public static TerminalGestureAction parseOrDefault(@Nullable String value, @NonNull TerminalGestureAction defaultAction) {
        TerminalGestureAction action = parse(value);
        return action == null ? defaultAction : action;
    }

    /**
     * A short name of the action for people, like "Next session", shown while a swipe is in
     * progress to tell which action it will run.
     */
    @NonNull
    public String getLabel() {
        switch (this) {
            case SHOW_KEYBOARD: return "Show keyboard";
            case TOGGLE_KEYBOARD: return "Toggle keyboard";
            case TOGGLE_TEXT_INPUT: return "Text input";
            case NEXT_SESSION: return "Next session";
            case PREVIOUS_SESSION: return "Previous session";
            case NEW_SESSION: return "New session";
            case OPEN_SIDEBAR: return "Open left sidebar";
            case OPEN_RIGHT_SIDEBAR: return "Open right sidebar";
            case OPEN_SESSION_SWITCHER: return "Session switcher";
            case OPEN_TMUX_SWITCHER: return "Open tmux window picker";
            case OPEN_MENU: return "Menu";
            case FONT_SIZE_UP: return "Larger text";
            case FONT_SIZE_DOWN: return "Smaller text";
            case RUN_COMMAND: return "Run command";
            case SEND_KEYS: return "Send keys";
            case NONE:
            default: return "Nothing";
        }
    }

    /** All the values that are valid in {@code termux.properties}, comma separated. */
    @NonNull
    public static String getValidValues() {
        StringBuilder builder = new StringBuilder();
        for (TerminalGestureAction action : values()) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(action.mValue);
        }
        return builder.toString();
    }

    /**
     * Whether holding a key that the action is mapped to repeats the action. This is only for the
     * font size actions, so that holding the key keeps growing or shrinking the text. Every other
     * action (a session switch, the sidebar, the menu, the keyboard, keys) runs once per press.
     */
    public boolean isRepeatable() {
        return this == FONT_SIZE_UP || this == FONT_SIZE_DOWN;
    }

    /**
     * Whether the action runs for a key down event.
     *
     * @param repeatCount {@code KeyEvent#getRepeatCount()}: 0 for the initial press, more for the
     *                    auto repeated events of a held key.
     */
    public boolean runsOnKeyDown(int repeatCount) {
        return repeatCount <= 0 || isRepeatable();
    }

    /**
     * The action that is really performed for a configured action. {@link #SEND_KEYS} without
     * usable keys or {@link #RUN_COMMAND} without a configured command is {@link #NONE}, so that
     * a broken configuration is a no-op that neither animates, consumes a swipe nor delays taps.
     *
     * @param configured The action configured for the gesture.
     * @param hasUsableKeys Whether the {@code *-keys} property of the gesture is a valid sequence.
     */
    @NonNull
    public static TerminalGestureAction effective(@NonNull TerminalGestureAction configured, boolean hasUsableKeys) {
        if (configured == RUN_COMMAND) return NONE;
        return configured == SEND_KEYS && !hasUsableKeys ? NONE : configured;
    }

    /** Returns {@link #NONE} when the configured command is missing or blank. */
    @NonNull
    public static TerminalGestureAction effective(@NonNull TerminalGestureAction configured,
                                                  boolean hasUsableKeys, boolean hasCommand) {
        if (configured == RUN_COMMAND) return hasCommand ? RUN_COMMAND : NONE;
        return effective(configured, hasUsableKeys);
    }

    /**
     * What a soft keyboard action should do now.
     *
     * Nothing is done if the keyboard is disabled by the user's flags, which always win, nor for
     * actions that do not concern the keyboard. A toggle hides a visible keyboard and shows a hidden
     * one, and showing is idempotent.
     *
     * @param keyboardDisabled Whether the soft keyboard is disabled, see
     *                         {@code KeyboardUtils#areDisableSoftKeyboardFlagsSet}.
     * @param keyboardVisible Whether the soft keyboard is currently visible.
     */
    @NonNull
    public KeyboardOperation keyboardOperation(boolean keyboardDisabled, boolean keyboardVisible) {
        if (keyboardDisabled) return KeyboardOperation.NONE;
        switch (this) {
            case SHOW_KEYBOARD:
                return KeyboardOperation.SHOW;
            case TOGGLE_KEYBOARD:
                return keyboardVisible ? KeyboardOperation.HIDE : KeyboardOperation.SHOW;
            default:
                return KeyboardOperation.NONE;
        }
    }

    /**
     * Which way to navigate when there is a current session. The session handler decides whether
     * to create a session when there is only one.
     */
    @NonNull
    public SessionSwitch sessionSwitch(int sessionCount) {
        if (sessionCount <= 0) return SessionSwitch.NONE;
        switch (this) {
            case NEXT_SESSION:
                return SessionSwitch.NEXT;
            case PREVIOUS_SESSION:
                return SessionSwitch.PREVIOUS;
            default:
                return SessionSwitch.NONE;
        }
    }

}
