package com.termux.app.settings;

import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.settings.properties.SharedProperties;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.TerminalKeySequence;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import com.google.common.collect.BiMap;

import java.io.File;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Describes how each property of {@code termuxpp.properties} is edited in the settings GUI: help
 * text, validation, the Android input type and (for the extra keys style) the allowed names.
 *
 * Validation mirrors the runtime parsing of
 * {@link dev.pschmitt.termux.shared.termux.settings.properties.TermuxSharedProperties} without its
 * logging: a value that the runtime would silently replace with the default is reported as an
 * error here, and a value that the runtime accepts is never rejected. The runtime policy itself is
 * not changed and nothing here touches the Android framework except the {@link InputType}
 * constants.
 *
 * An empty or {@code null} value is always valid: it means "remove the property and use the
 * default".
 */
public final class PropertyInputSpec {

    /** The names that {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_STYLE} understands, see {@link ExtraKeysInfo#getCharDisplayMapForStyle(String)}. */
    private static final String[] EXTRA_KEYS_STYLES = {"default", "arrows-only", "arrows-all", "all", "none"};

    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

    private PropertyInputSpec() {}

    /**
     * Help for a property in clear English: what it does, the format or range and the default.
     *
     * @param key The property key, like {@code terminal-margin-horizontal}.
     * @return The help text, or an empty string for a key that is not a property of the app.
     */
    @NonNull
    public static String help(@Nullable String key) {
        if (key == null) return "";
        if (TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(key)) {
            String base = key.substring(0, key.lastIndexOf('-') + 1);
            String trigger = base.substring("terminal-".length(), base.length() - 1).replace('-', ' ');
            if (key.endsWith("-action"))
                return gesture("a " + trigger + " on the terminal", base + "keys", TerminalGestureAction.NONE);
            if (key.endsWith("-keys")) return keySequence("a " + trigger, base + "action");
            return commandHelp("a " + trigger + " bound to run-command");
        }
        switch (key) {
            /* boolean */
            case TermuxPropertyConstants.KEY_DISABLE_FILE_SHARE_RECEIVER:
                return bool(key, "Do not offer the app as a target in the Share menu of other apps, which is used to"
                    + " send files or text to a terminal.");
            case TermuxPropertyConstants.KEY_DISABLE_FILE_VIEW_RECEIVER:
                return bool(key, "Do not offer the app as a target for opening files or links from other apps"
                    + " (\"Open with\").");
            case TermuxPropertyConstants.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS:
                return bool(key, "Turn off the Ctrl+Alt keyboard shortcuts of a hardware keyboard, like creating or"
                    + " switching sessions, so that those keys reach the terminal programs.");
            case TermuxPropertyConstants.KEY_CREATE_SESSION_ON_SINGLE_SESSION_SWITCH:
                return "Open a new terminal when previous or next session is invoked with only one open. Default: disabled.";
            case TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST:
                return bool(key, "Do not show the short message with the session number and name when you switch to"
                    + " another terminal session.");
            case TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT:
                return bool(key, "Force character based input. Enable it if the letters you type do not appear until"
                    + " you press Enter, which happens with some keyboards, for example on Samsung devices.");
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS:
                return bool(key, "Show the text of the extra keys buttons in capital letters.");
            case TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP:
                return bool(key, "Do not show the soft keyboard when the app starts.");
            case TermuxPropertyConstants.KEY_NIX_SESSION:
                return bool(key, "Start new sessions in the Nix environment when the bootstrap provides it."
                    + " Turn it off to start the native Termux shell. The failsafe session always starts the"
                    + " native shell.");
            case TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER:
                return bool(key, "Run the socket server of the faster \"am\" command at app start. Turn it off"
                    + " only if you do not use that command.");
            case TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN:
                return bool(key, "Open a link in the terminal output right when you tap it, instead of asking"
                    + " first through the long press menu.");
            case TermuxPropertyConstants.KEY_USE_CTRL_SPACE_WORKAROUND:
                return bool(key, "Work around devices or keyboards where Ctrl+Space does not send a NUL character.");
            case TermuxPropertyConstants.KEY_USE_FULLSCREEN:
                return bool(key, "Show the terminal in fullscreen, hiding the status bar.");
            case TermuxPropertyConstants.KEY_USE_FULLSCREEN_WORKAROUND:
                return bool(key, "Fix the layout when the soft keyboard is shown in fullscreen mode, where the"
                    + " keyboard can cover the terminal. Only used with fullscreen.");
            case TermuxConstants.PROP_ALLOW_EXTERNAL_APPS:
                return bool(key, "Allow other apps to run commands in the terminal through the RUN_COMMAND"
                    + " intent. This lets any app with the permission run commands as your user, so enable it"
                    + " only if you need it and trust those apps.");

            /* int */
            case TermuxPropertyConstants.KEY_BELL_BEHAVIOUR:
                return "What the terminal does when a program rings the bell (the BEL character)."
                    + " Values: " + keys(TermuxPropertyConstants.MAP_BELL_BEHAVIOUR) + "."
                    + " Default: " + inverse(TermuxPropertyConstants.MAP_BELL_BEHAVIOUR, TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR) + ".";
            case TermuxPropertyConstants.KEY_WAKE_LOCK_TIMEOUT:
                return "Release a wake lock by itself after this many minutes, so a forgotten one does not keep"
                    + " the phone awake. Format: a whole number from "
                    + TermuxPropertyConstants.IVALUE_WAKE_LOCK_TIMEOUT_MIN + " to "
                    + TermuxPropertyConstants.IVALUE_WAKE_LOCK_TIMEOUT_MAX
                    + ", like 120. Default: " + TermuxPropertyConstants.DEFAULT_IVALUE_WAKE_LOCK_TIMEOUT
                    + ", keep it until it is released.";
            case TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT:
                return "Delete files from the TMPDIR directory when the last session exits, if they were not used"
                    + " for this many days. Format: a whole number from "
                    + TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN + " to "
                    + TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX
                    + ". -1 deletes nothing and 0 deletes all files. Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT + ".";
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE:
                return "How fast the cursor blinks, as the time in milliseconds of each blink phase. Format: a whole"
                    + " number from " + TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN + " to "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX
                    + ", or 0 for a cursor that does not blink. Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE + " (no blinking).";
            case TermuxPropertyConstants.KEY_TERMINAL_ENGINE:
                return "Terminal engine for new sessions. libghostty is available on ARM64. Default is Termux.";
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE:
                return "The shape of the terminal cursor. Values: " + keys(TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE) + "."
                    + " Default: " + inverse(TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE) + ".";
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL:
                return "The empty space at the left and right of the terminal text, in dp. Format: a whole number from "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN + " to "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL + ".";
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL:
                return "The empty space at the top and bottom of the terminal text, in dp. Format: a whole number from "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN + " to "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL + ".";
            case TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS:
                return "How many lines of output each session keeps for scrolling back, besides the visible screen."
                    + " More lines need more memory. Format: a whole number from "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN + " to "
                    + TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS + ".";

            /* float */
            case TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
                return "The height of the extra keys toolbar, as a factor of its normal height: 2 is twice as high."
                    + " Format: a decimal number from "
                    + number(TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN) + " to "
                    + number(TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX) + ", like 1.5. Default: "
                    + number(TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR) + ".";
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS:
                return "How round the corners of the extra keys buttons are, in dp. Format: a decimal number from 0 to "
                    + number(ExtraKeysColorParser.MAX_CORNER_RADIUS_DP) + ", like 8 or 6.5, without a sign or a unit."
                    + " Default: not set, the corners of the current theme.";

            case TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION:
                return "Where gesture action labels appear in the terminal. Default: bottom-center.";
            case TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE:
                return "How the active tmux window looks. Subtle uses an outlined row with an accent dot. "
                    + "Rounded uses a filled accent pill. Default: subtle.";
            case TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT:
                return "How the sidebar organizes tmux windows. Nested shows them under their terminal session. "
                    + "Drill-down opens a window list for one terminal. Modes adds a switch between sessions "
                    + "and tmux windows. Default: nested.";
            case TermuxPropertyConstants.KEY_TERMINAL_TOAST_POSITION:
                return "Where terminal feedback appears, including session-switch indicators. Default: top-center.";

            case TermuxPropertyConstants.KEY_ADB_SHELL_ALLOWED_SSIDS:
                return "Restrict the ADB shell service to these Wi-Fi networks. Enter exact, comma-separated SSIDs. "
                    + "Leave empty to allow any network. Android must allow TermuxPP to read the connected SSID.";

            case TermuxPropertyConstants.KEY_WAKE_LOCK_WIFI:
                return "Also keep the Wi-Fi radio out of power save while a wake lock is held (a high-performance"
                    + " Wi-Fi lock). Costs battery; the wake lock alone keeps programs and SSH sessions running."
                    + " Disabled by default.";
            case TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE:
                return "Visual effect shown when a gesture is recognized. Default: soft flash.";
            case TermuxPropertyConstants.KEY_GESTURE_ANIMATIONS:
                return "Show visual feedback for custom taps, extra-key swipes, and volume button actions. Enabled by default.";

            /* TerminalGestureAction */
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION:
                return gesture("a single tap on the terminal", TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_KEYS,
                    TermuxPropertyConstants.DEFAULT_TERMINAL_SINGLE_TAP_ACTION);
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION:
                return gesture("a double tap on the terminal", TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_KEYS,
                    TermuxPropertyConstants.DEFAULT_TERMINAL_DOUBLE_TAP_ACTION);
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION:
                return gesture("a triple tap on the terminal", TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_KEYS,
                    TermuxPropertyConstants.DEFAULT_TERMINAL_TRIPLE_TAP_ACTION);
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE:
                return "Focus a running session with a matching terminal title before running the triple-tap action."
                    + " Enter a regular expression. Leave blank to always run the action.";
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION:
                return gesture("a swipe on the extra keys with the finger moving to the right", TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS,
                    TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_LEFT_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION:
                return gesture("a swipe on the extra keys with the finger moving to the left (toggle-text-input opens the text input)",
                    TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_RIGHT_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION:
                return gesture("a long swipe on the extra keys with the finger moving to the right (a hint shows which action runs on release)",
                    TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION:
                return gesture("a long swipe on the extra keys with the finger moving to the left (a hint shows which action runs on release)",
                    TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION);

            case TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT:
                return "Where commands assigned to taps, extra-key swipes, and extra-key buttons run. "
                    + "session follows nix-session, nix always enters the managed guest, and outer uses the native Termux prefix. "
                    + "Default: session.";
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_COMMAND:
                return commandHelp("a single tap");
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND:
                return commandHelp("a double tap");
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_COMMAND:
                return commandHelp("a triple tap");
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND:
                return commandHelp("a left swipe on the extra keys row");
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND:
                return commandHelp("a right swipe on the extra keys row");
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND:
                return commandHelp("a long left swipe on the extra keys row");
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND:
                return commandHelp("a long right swipe on the extra keys row");

            /* Volume key action */
            case TermuxPropertyConstants.KEY_VOLUME_UP_ACTION:
                return volume("volume up", "the virtual Fn key while it is held", TermuxPropertyConstants.KEY_VOLUME_UP_KEYS);
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION:
                return volume("volume down", "the virtual Ctrl key while it is held", TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS);

            /* TerminalKeySequence */
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_KEYS:
                return keySequence("a single tap on the terminal", TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION);
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_KEYS:
                return keySequence("a double tap on the terminal", TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION);
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_KEYS:
                return keySequence("a triple tap on the terminal", TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS:
                return keySequence("a swipe to the left on the extra keys", TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS:
                return keySequence("a swipe to the right on the extra keys", TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS:
                return keySequence("a long swipe to the left on the extra keys", TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS:
                return keySequence("a long swipe to the right on the extra keys", TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION);
            case TermuxPropertyConstants.KEY_VOLUME_UP_KEYS:
                return keySequence("the volume up button", TermuxPropertyConstants.KEY_VOLUME_UP_ACTION);
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS:
                return keySequence("the volume down button", TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION);

            /* colors */
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR:
                return "The text color of an active extra keys button, like Ctrl or Alt after you tapped it. Format: "
                    + "#RGB, #RRGGBB or #AARRGGBB in hexadecimal, like #ff8800 (the first two digits of #AARRGGBB"
                    + " are the opacity). Default: not set, the color of the current theme.";
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR:
                return "The background color of an extra keys button while it is pressed. Format: "
                    + "#RGB, #RRGGBB or #AARRGGBB in hexadecimal, like #335577 (the first two digits of #AARRGGBB"
                    + " are the opacity). Default: not set, the color of the current theme.";

            /* session shortcuts */
            case TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION:
                return shortcut("create a new terminal session", "ctrl+t");
            case TermuxPropertyConstants.KEY_SHORTCUT_NEXT_SESSION:
                return shortcut("switch to the next terminal session", "ctrl+2");
            case TermuxPropertyConstants.KEY_SHORTCUT_PREVIOUS_SESSION:
                return shortcut("switch to the previous terminal session", "ctrl+1");
            case TermuxPropertyConstants.KEY_SHORTCUT_RENAME_SESSION:
                return shortcut("rename the current terminal session", "ctrl+r");

            /* String */
            case TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR:
                return "What the Back button does when the terminal has the focus. back goes back like in every app,"
                    + " escape sends the Escape key to the terminal. Values: "
                    + keys(TermuxPropertyConstants.MAP_BACK_KEY_BEHAVIOUR) + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR + ".";
            case TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY:
                return "The directory in which new sessions start. Format: the full path of an existing directory"
                    + " that can be read, like " + TermuxConstants.TERMUX_HOME_DIR_PATH + "/projects. Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY + " (the home directory).";
            case TermuxPropertyConstants.KEY_NIX_USER:
                return "The user name in the Nix environment. It is used only when the Nix environment is set"
                    + " up, so change it before the first start or after you remove the environment. Format: a"
                    + " lowercase letter or _, then up to 31 lowercase letters, digits, _ or -. Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_NIX_USER + ".";
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return "The buttons of the extra keys toolbar. Format: a JSON array of rows, each row an array of"
                    + " keys. A key is a name like \"ESC\" or \"TAB\", a character like \"/\", or an object like"
                    + " {key: '-', popup: '|'} (a popup is sent when you swipe up on the button). An object can"
                    + " also have display (the label) and macro (several space separated keys sent together, like"
                    + " \"CTRL f\") instead of key. Use {command: '...', display: 'CMD'} to start a new terminal"
                    + " session with a shell command; command cannot be combined with key or macro. Commands are trusted"
                    + " shell text. Default: " + TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                return "How the names of the extra keys are shown, for example whether LEFT is shown as an arrow."
                    + " Values: " + join(EXTRA_KEYS_STYLES) + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE + ".";
            case TermuxPropertyConstants.KEY_NIGHT_MODE:
                return "Whether the app uses the dark theme: true always, false never, system follows the setting of"
                    + " the device. Values: " + keys(TermuxPropertyConstants.MAP_NIGHT_MODE) + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_NIGHT_MODE + ".";
            case TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR:
                return "What the keyboard toggle (the KEYBOARD extra key or the toggle gesture) does: show/hide shows"
                    + " or hides the soft keyboard, enable/disable enables or disables it altogether so that it"
                    + " never pops up. Values: " + keys(TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR)
                    + ". Default: " + TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR + ".";
            case TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR:
                return "What the volume buttons do in the terminal: virtual uses them as virtual keys (see the volume"
                    + " up and down actions), volume changes the volume of the device. Values: "
                    + keys(TermuxPropertyConstants.MAP_VOLUME_KEYS_BEHAVIOUR) + ". Default: "
                    + TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR + ".";
            default:
                return "";
        }
    }

    /**
     * Validates a value for a property the way the runtime parses it.
     *
     * @param key The property key.
     * @param value The new value, {@code null} or empty to reset the property to its default.
     * @return {@code null} if the value is valid, otherwise a short message in English that says
     * what is wrong and what is expected.
     */
    @Nullable
    public static String validationError(@Nullable String key, @Nullable String value) {
        if (key == null || !TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key))
            return "Unknown property: " + key;
        if (value == null || value.isEmpty()) return null;
        if (TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(key)) {
            if (key.endsWith("-action")) return TerminalGestureAction.parse(value) != null ? null
                : "Expected one of: " + TerminalGestureAction.getValidValues();
            if (key.endsWith("-keys")) {
                String error = TerminalKeySequence.getError(value);
                return error == null ? null : "Invalid keys: " + error;
            }
            return value.indexOf(0) < 0 ? null : "A command cannot contain NUL";
        }

        if (isBoolean(key)) {
            return SharedProperties.getBooleanValueForStringValue(value) != null ? null
                : "Expected true or false";
        }

        switch (key) {
            case TermuxPropertyConstants.KEY_BELL_BEHAVIOUR:
                return inMap(TermuxPropertyConstants.MAP_BELL_BEHAVIOUR, value);
            case TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE:
                for (String style : choices(key)) {
                    if (style.equalsIgnoreCase(value)) return null;
                }
                return "Choose one of the available feedback styles";
            case TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE:
                for (String style : choices(key)) {
                    if (style.equalsIgnoreCase(value)) return null;
                }
                return "Expected subtle or rounded";
            case TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT:
                for (String layout : choices(key)) {
                    if (layout.equalsIgnoreCase(value)) return null;
                }
                return "Expected nested, drill-down, or modes";
            case TermuxPropertyConstants.KEY_TERMINAL_ENGINE:
                for (String engine : choices(key)) {
                    if (engine.equalsIgnoreCase(value)) return null;
                }
                return "Expected termux or libghostty";
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE:
                return inMap(TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE, value);
            case TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR:
                return inMap(TermuxPropertyConstants.MAP_BACK_KEY_BEHAVIOUR, value);
            case TermuxPropertyConstants.KEY_NIGHT_MODE:
                return inMap(TermuxPropertyConstants.MAP_NIGHT_MODE, value);
            case TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR:
                return inMap(TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, value);
            case TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR:
                return inMap(TermuxPropertyConstants.MAP_VOLUME_KEYS_BEHAVIOUR, value);
            case TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION:
            case TermuxPropertyConstants.KEY_TERMINAL_TOAST_POSITION:
                return inMap(TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION, value);
            case TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT:
                return GestureCommandContext.parse(value) != null ? null : "Expected one of: session, nix, outer";
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE:
                try {
                    Pattern.compile(value);
                    return null;
                } catch (PatternSyntaxException e) {
                    return e.getDescription();
                }
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_COMMAND:
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND:
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND:
                return value.trim().isEmpty() ? "Command cannot be blank" : null;

            case TermuxPropertyConstants.KEY_WAKE_LOCK_TIMEOUT:
                return intInRange(value,
                    TermuxPropertyConstants.IVALUE_WAKE_LOCK_TIMEOUT_MIN,
                    TermuxPropertyConstants.IVALUE_WAKE_LOCK_TIMEOUT_MAX, false);
            case TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT:
                return intInRange(value,
                    TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN,
                    TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX, false);
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE:
                // 0 is outside of the range but valid: it turns the blinking off
                return intInRange(value,
                    TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN,
                    TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX, true);
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL:
                return intInRange(value,
                    TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN,
                    TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX, false);
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL:
                return intInRange(value,
                    TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN,
                    TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX, false);
            case TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS:
                return intInRange(value,
                    TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN,
                    TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX, false);

            case TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
                return toolbarHeight(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS:
                return ExtraKeysColorParser.parseCornerRadiusDp(value) != null ? null
                    : "Expected a number from 0 to " + number(ExtraKeysColorParser.MAX_CORNER_RADIUS_DP)
                        + " like 8 or 6.5, without a sign or a unit";

            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION:
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION:
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION:
                return TerminalGestureAction.parse(value) != null ? null
                    : "Expected one of: " + TerminalGestureAction.getValidValues();
            case TermuxPropertyConstants.KEY_VOLUME_UP_ACTION:
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION:
                TerminalGestureAction volumeAction = TerminalGestureAction.parse(value);
                return TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER.equals(SharedProperties.toLowerCase(value.trim()))
                    || (volumeAction != null && volumeAction != TerminalGestureAction.RUN_COMMAND) ? null
                    : "Expected one of: " + TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER + ", "
                        + TerminalGestureAction.getValidValues().replace(", run-command", "");

            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_KEYS:
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_KEYS:
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS:
            case TermuxPropertyConstants.KEY_VOLUME_UP_KEYS:
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS: {
                String error = TerminalKeySequence.getError(value);
                return error == null ? null : "Invalid keys: " + error;
            }

            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR:
                return ExtraKeysColorParser.parseColor(value) != null ? null
                    : "Expected a color like #RGB, #RRGGBB or #AARRGGBB";

            case TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_NEXT_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_PREVIOUS_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_RENAME_SESSION:
                return shortcut(value);

            case TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY: {
                File directory = new File(value);
                return directory.isDirectory() && directory.canRead() ? null
                    : "Expected the full path of an existing directory that can be read";
            }
            case TermuxPropertyConstants.KEY_NIX_USER:
                return value.matches("[a-z_][a-z0-9_-]{0,31}") ? null
                    : "Expected a lowercase letter or _, then up to 31 lowercase letters, digits, _ or -";
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return extraKeys(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                return isExtraKeysStyle(value) ? null : "Expected one of: " + join(EXTRA_KEYS_STYLES);
            default:
                return null;
        }
    }

    /**
     * The {@link InputType} for the text field that edits a property.
     *
     * Whole numbers use the number keyboard (signed for the property that allows -1), decimal
     * numbers allow the decimal point, the extra keys layout is multi line, a path uses the URI
     * keyboard and every other text turns suggestions off, since a value like {@code ctrl+t} or
     * {@code #ff8800} must not be autocorrected.
     */
    public static int inputType(@Nullable String key) {
        if (key == null) return InputType.TYPE_CLASS_TEXT;
        switch (key) {
            case TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT:
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE:
            case TermuxPropertyConstants.KEY_WAKE_LOCK_TIMEOUT:
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL:
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL:
            case TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS:
                return InputType.TYPE_CLASS_NUMBER;
            case TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS:
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            case TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY:
                return TEXT | InputType.TYPE_TEXT_VARIATION_URI;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            default:
                return TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key)
                    ? TEXT : InputType.TYPE_CLASS_TEXT;
        }
    }

    /**
     * The values to offer in a picker for a property.
     *
     * @return Values for a property with a fixed set of choices, or an empty array.
     */
    @NonNull
    public static String[] choices(@Nullable String key) {
        if (TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE.equals(key))
            return new String[] {TermuxPropertyConstants.TMUX_SIDEBAR_SUBTLE,
                TermuxPropertyConstants.TMUX_SIDEBAR_ROUNDED};
        if (TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT.equals(key))
            return new String[] {TermuxPropertyConstants.TMUX_SIDEBAR_NESTED,
                TermuxPropertyConstants.TMUX_SIDEBAR_DRILL_DOWN, TermuxPropertyConstants.TMUX_SIDEBAR_MODES};
        if (TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE.equals(key))
            return TermuxPropertyConstants.GESTURE_FEEDBACK_STYLES.clone();
        if (TermuxPropertyConstants.KEY_TERMINAL_ENGINE.equals(key))
            return new String[] {TermuxPropertyConstants.TERMINAL_ENGINE_TERMUX, TermuxPropertyConstants.TERMINAL_ENGINE_GHOSTTY};
        if (TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE.equals(key)) return EXTRA_KEYS_STYLES.clone();
        if (TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT.equals(key)) return new String[] {"session", "nix", "outer"};
        if (TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION.equals(key)
            || TermuxPropertyConstants.KEY_TERMINAL_TOAST_POSITION.equals(key))
            return TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION.keySet().toArray(new String[0]);
        return new String[0];
    }

    private static String commandHelp(String gesture) {
        return "Shell command run in a new visible terminal session when " + gesture + " occurs. "
            + "The command is interpreted by the selected shell context; it is trusted shell code. "
            + "Command text is stored in termux.properties and is not written to command logs. Leave empty to unset.";
    }

    private static boolean isBoolean(String key) {
        return TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key)
            || TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key);
    }

    private static boolean isExtraKeysStyle(String value) {
        for (String style : EXTRA_KEYS_STYLES) {
            if (style.equals(value)) return true;
        }
        return false;
    }

    /** The runtime looks the lower cased value up in the map, without trimming it. */
    @Nullable
    private static String inMap(BiMap<String, ?> map, String value) {
        return map.containsKey(SharedProperties.toLowerCase(value)) ? null
            : "Expected one of: " + keys(map);
    }

    /** The runtime uses {@link Integer#parseInt(String)}, which accepts a sign but no spaces. */
    @Nullable
    private static String intInRange(String value, int min, int max, boolean zeroAllowed) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return "Expected a whole number from " + min + " to " + max + (zeroAllowed ? " or 0" : "")
                + ", without spaces";
        }
        if (number >= min && number <= max || zeroAllowed && number == 0) return null;
        return "Expected a number from " + min + " to " + max + (zeroAllowed ? " or 0" : "");
    }

    /**
     * The runtime uses {@link Float#parseFloat(String)} and the range. NaN passes its range check
     * but is not a usable height, so it and infinity are rejected.
     */
    @Nullable
    private static String toolbarHeight(String value) {
        String expected = "Expected a decimal number from "
            + number(TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN) + " to "
            + number(TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX);
        float factor;
        try {
            factor = Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return expected;
        }
        if (Float.isNaN(factor) || Float.isInfinite(factor)
            || factor < TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN
            || factor > TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX)
            return expected;
        return null;
    }

    /**
     * The same rules as {@code TermuxSharedProperties#getCodePointForSessionShortcuts}: Ctrl, a
     * plus and then one character (or one surrogate pair), ignoring case and spaces around the parts.
     */
    @Nullable
    private static String shortcut(String value) {
        if (value.trim().equalsIgnoreCase("disabled")) return null;
        String error = "Expected ctrl+ and a key, like ctrl+t, or disabled";
        String[] parts = value.toLowerCase(java.util.Locale.ROOT).trim().split("\\+");
        String input = parts.length == 2 ? parts[1].trim() : null;
        if (!(parts.length == 2 && parts[0].trim().equals("ctrl")) || input.isEmpty() || input.length() > 2)
            return error;
        char c = input.charAt(0);
        if (Character.isLowSurrogate(c) && (input.length() != 2 || Character.isHighSurrogate(input.charAt(1))))
            return error;
        return null;
    }

    /** Creates the layout the way the runtime does, the style only changes the labels. */
    @Nullable
    private static String extraKeys(String value) {
        try {
            new ExtraKeysInfo(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
                ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return "Invalid extra keys layout" + (message == null || message.isEmpty() ? "" : ": " + message)
                + ". Expected a JSON array of rows like [[\"ESC\",\"TAB\"],[\"CTRL\",\"ALT\"]]";
        }
    }

    private static String bool(String key, String purpose) {
        boolean defaultTrue = TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key);
        return purpose + " Values: true or false. Default: " + defaultTrue + ".";
    }

    private static String gesture(String trigger, String keysKey, TerminalGestureAction defaultAction) {
        return "What " + trigger + " does. Values: " + TerminalGestureAction.getValidValues()
            + ". With send-keys, the keys of " + keysKey + " are sent to the terminal. Default: "
            + defaultAction.getValue() + ".";
    }

    private static String volume(String button, String modifier, String keysKey) {
        return "What the " + button + " button does when the volume keys are virtual keys. modifier is "
            + modifier + ". Values: " + TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER + ", "
            + TerminalGestureAction.getValidValues() + ". With send-keys, the keys of " + keysKey
            + " are sent to the terminal. Default: " + TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER + ".";
    }

    private static String keySequence(String trigger, String actionKey) {
        return "The keys to send to the terminal for " + trigger + ", used when " + actionKey
            + " is send-keys. Format: keys separated by spaces, like the macro of an extra key: names like ESC, TAB,"
            + " ENTER or UP, characters like l, and the modifiers CTRL, ALT, SHIFT and FN that apply to the next"
            + " key. For example ESC or CTRL l. At most " + TerminalKeySequence.MAX_TOKENS
            + " keys. Default: not set, nothing is sent.";
    }

    private static String shortcut(String action, String example) {
        return "The keyboard shortcut to " + action + ". Format: ctrl+ and one key, like " + example
            + ", where Ctrl is the virtual Ctrl key of the extra keys (or Ctrl on a hardware keyboard), or"
            + " disabled to switch the shortcut off. Case is ignored. Default: not set, there is no shortcut.";
    }

    private static String keys(BiMap<String, ?> map) {
        return join(map.keySet().toArray(new String[0]));
    }

    private static String inverse(BiMap<String, Integer> map, int value) {
        String name = map.inverse().get(value);
        return name == null ? String.valueOf(value) : name;
    }

    private static String join(String[] values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }

    /** 3 for 3.0 and 0.4 for 0.4f, without the noise of a float. */
    private static String number(float value) {
        return value == (long) value ? String.valueOf((long) value) : String.valueOf(value);
    }

}
