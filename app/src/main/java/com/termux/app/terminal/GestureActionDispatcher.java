package com.termux.app.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.termux.extrakeys.TerminalKeySequence;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;

/**
 * Carries out a {@link TerminalGestureAction} that a tap or a swipe was mapped to, on a
 * {@link Target} that does the actual work, so that the dispatch can be unit tested.
 */
public final class GestureActionDispatcher {

    /** What the actions act on. */
    public interface Target {
        /** The number of terminal sessions. */
        int getSessionCount();

        /** Switch to the next or previous session, wrapping around, like the hardware shortcuts do. */
        void switchSession(boolean forward);

        /** Open a new terminal session and switch to it. */
        void createSession();

        /** Switch the terminal toolbar to its text input, or back to the extra keys. */
        void toggleTextInput();

        /** Open the sidebar, if it may be opened now. */
        void openSidebar();

        /** Open the compact session picker. */
        void openSessionSwitcher();

        /** Open the terminal menu (the bottom sheet), the same one that the long press opens. */
        void openMenu();

        /** Increase or decrease the terminal font size by one step, like pinching does. */
        void changeFontSize(boolean increase);

        /** Whether the user's flags disable the soft keyboard. */
        boolean isSoftKeyboardDisabled();

        /** Whether the soft keyboard is visible now. */
        boolean isSoftKeyboardVisible();

        void showSoftKeyboard();

        void hideSoftKeyboard();

        /** Send the keys to the current terminal session. */
        void sendKeys(@NonNull TerminalKeySequence keys);

        /** Open a new terminal session and run the configured command in the chosen context. */
        void runCommand(@NonNull String command, @NonNull GestureCommandContext context);
    }

    private GestureActionDispatcher() {
    }

    /** Carries out an action that needs no keys, see {@link #perform(TerminalGestureAction, TerminalKeySequence, Target)}. */
    public static void perform(@NonNull TerminalGestureAction action, @NonNull Target target) {
        perform(action, null, target);
    }

    /**
     * Carries out an action.
     *
     * @param keys The keys of the gesture, sent by {@link TerminalGestureAction#SEND_KEYS}. That
     *             action does nothing without keys.
     */
    public static void perform(@NonNull TerminalGestureAction action, @Nullable TerminalKeySequence keys, @NonNull Target target) {
        perform(action, keys, null, GestureCommandContext.SESSION, target);
    }

    /** Carries out an action, including command text and environment when applicable. */
    public static void perform(@NonNull TerminalGestureAction action, @Nullable TerminalKeySequence keys,
                               @Nullable String command, @NonNull GestureCommandContext context,
                               @NonNull Target target) {
        switch (action) {
            case SEND_KEYS:
                if (keys != null) target.sendKeys(keys);
                break;

            case RUN_COMMAND:
                if (command != null && !command.trim().isEmpty()) target.runCommand(command, context);
                break;

            case NEXT_SESSION:
            case PREVIOUS_SESSION:
                // Switching never touches the keyboard.
                switch (action.sessionSwitch(target.getSessionCount())) {
                    case NEXT:
                        target.switchSession(true);
                        break;
                    case PREVIOUS:
                        target.switchSession(false);
                        break;
                    default:
                        break;
                }
                break;

            case NEW_SESSION:
                target.createSession();
                break;

            case TOGGLE_TEXT_INPUT:
                target.toggleTextInput();
                break;

            case OPEN_SIDEBAR:
                target.openSidebar();
                break;

            case OPEN_SESSION_SWITCHER:
                target.openSessionSwitcher();
                break;

            case OPEN_MENU:
                target.openMenu();
                break;

            case FONT_SIZE_UP:
                target.changeFontSize(true);
                break;

            case FONT_SIZE_DOWN:
                target.changeFontSize(false);
                break;

            case SHOW_KEYBOARD:
            case TOGGLE_KEYBOARD:
                boolean disabled = target.isSoftKeyboardDisabled();
                // Do not even ask for the visibility if the keyboard is disabled.
                boolean visible = !disabled && target.isSoftKeyboardVisible();
                switch (action.keyboardOperation(disabled, visible)) {
                    case SHOW:
                        target.showSoftKeyboard();
                        break;
                    case HIDE:
                        target.hideSoftKeyboard();
                        break;
                    default:
                        break;
                }
                break;

            case NONE:
            default:
                break;
        }
    }

}
