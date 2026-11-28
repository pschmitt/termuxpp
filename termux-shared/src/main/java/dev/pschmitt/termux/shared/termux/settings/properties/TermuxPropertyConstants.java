package dev.pschmitt.termux.shared.termux.settings.properties;

import android.view.Gravity;

import com.google.common.collect.ImmutableBiMap;
import dev.pschmitt.termux.shared.termux.shell.am.TermuxAmSocketServer;
import dev.pschmitt.termux.shared.theme.NightMode;
import dev.pschmitt.termux.shared.file.FileUtils;
import dev.pschmitt.termux.shared.file.filesystem.FileType;
import dev.pschmitt.termux.shared.settings.properties.SharedProperties;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.logger.Logger;
import com.termux.terminal.TerminalEmulator;
import com.termux.view.TerminalView;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * Version: v0.18.0
 * SPDX-License-Identifier: MIT
 *
 * Changelog
 *
 * - 0.1.0 (2021-03-11)
 *      - Initial Release.
 *
 * - 0.2.0 (2021-03-11)
 *      - Renamed `HOME_PATH` to `TERMUX_HOME_DIR_PATH`.
 *      - Renamed `TERMUX_PROPERTIES_PRIMARY_PATH` to `TERMUX_PROPERTIES_PRIMARY_FILE_PATH`.
 *      - Renamed `TERMUX_PROPERTIES_SECONDARY_FILE_PATH` to `TERMUX_PROPERTIES_SECONDARY_FILE_PATH`.
 *
 * - 0.3.0 (2021-03-16)
 *      - Add `*TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR*`.
 *
 * - 0.4.0 (2021-03-16)
 *      - Removed `MAP_GENERIC_BOOLEAN` and `MAP_GENERIC_INVERTED_BOOLEAN`.
 *
 * - 0.5.0 (2021-03-25)
 *      - Add `KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP`.
 *
 * - 0.6.0 (2021-04-07)
 *      - Updated javadocs.
 *
 * - 0.7.0 (2021-05-09)
 *      - Add `*SOFT_KEYBOARD_TOGGLE_BEHAVIOUR*`.
 *
 * - 0.8.0 (2021-05-10)
 *      - Change the `KEY_USE_BACK_KEY_AS_ESCAPE_KEY` and `KEY_VIRTUAL_VOLUME_KEYS_DISABLED` booleans
 *          to `KEY_BACK_KEY_BEHAVIOUR` and `KEY_VOLUME_KEYS_BEHAVIOUR` String internal values.
 *      - Renamed `SOFT_KEYBOARD_TOGGLE_BEHAVIOUR` to `KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR`.
 *
 * - 0.9.0 (2021-05-14)
 *      - Add `*KEY_TERMINAL_CURSOR_BLINK_RATE*`.
 *
 * - 0.10.0 (2021-05-15)
 *      - Add `MAP_BACK_KEY_BEHAVIOUR`, `MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR`, `MAP_VOLUME_KEYS_BEHAVIOUR`.
 *
 * - 0.11.0 (2021-06-10)
 *      - Add `*KEY_TERMINAL_TRANSCRIPT_ROWS*`.
 *
 * - 0.12.0 (2021-06-10)
 *      - Add `*KEY_TERMINAL_CURSOR_STYLE*`.
 *
 * - 0.13.0 (2021-08-25)
 *      - Add `*KEY_TERMINAL_MARGIN_HORIZONTAL*` and `*KEY_TERMINAL_MARGIN_VERTICAL*`.
 *
 * - 0.14.0 (2021-09-02)
 *      - Add `getTermuxFloatPropertiesFile()`.
 *
 * - 0.15.0 (2021-09-05)
 *      - Add `KEY_EXTRA_KEYS_TEXT_ALL_CAPS`.
 *
 * - 0.16.0 (2021-10-21)
 *      - Add `KEY_NIGHT_MODE`.
 *
 * - 0.17.0 (2022-03-17)
 *      - Add `KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT`.
 *
 * - TermuxPP
 *      - Add `KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR`, `KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR`
 *        and `KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS`.
 *      - Add `KEY_TERMINAL_SINGLE_TAP_ACTION`, `KEY_TERMINAL_DOUBLE_TAP_ACTION`,
 *        `KEY_TERMINAL_TRIPLE_TAP_ACTION` and `KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION`.
 *      - Add `KEY_TERMINAL_SINGLE_TAP_KEYS`, `KEY_TERMINAL_DOUBLE_TAP_KEYS`,
 *        `KEY_TERMINAL_TRIPLE_TAP_KEYS` and `KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS`, the keys that the
 *        `send-keys` gesture action sends.
 *      - Add `KEY_VOLUME_UP_ACTION` and `KEY_VOLUME_DOWN_ACTION`, with their `KEY_VOLUME_UP_KEYS` and
 *        `KEY_VOLUME_DOWN_KEYS`, to map the volume buttons to a gesture action.
 *
 * - 0.18.0 (2022-06-13)
 *      - Add `KEY_DISABLE_FILE_SHARE_RECEIVER` and `KEY_DISABLE_FILE_VIEW_RECEIVER`.
 */

/**
 * A class that defines shared constants of the SharedProperties used by Termux app and its plugins.
 * This class will be hosted by termux-shared lib and should be imported by other termux plugin
 * apps as is instead of copying constants to random classes. The 3rd party apps can also import
 * it for interacting with termux apps. If changes are made to this file, increment the version number
 * and add an entry in the Changelog section above.
 *
 * The properties are loaded from the first file found at
 * {@link TermuxConstants#TERMUX_PROPERTIES_PRIMARY_FILE_PATH} or
 * {@link TermuxConstants#TERMUX_PROPERTIES_SECONDARY_FILE_PATH}
 */
public final class TermuxPropertyConstants {

    private static final String LOG_TAG = "TermuxPropertyConstants";

    /* boolean */

    /** Defines the key for whether file share receiver of the app is enabled. */
    public static final String KEY_DISABLE_FILE_SHARE_RECEIVER =  "disable-file-share-receiver"; // Default: "disable-file-share-receiver"

    /** Defines the key for whether file view receiver of the app is enabled. */
    public static final String KEY_DISABLE_FILE_VIEW_RECEIVER =  "disable-file-view-receiver"; // Default: "disable-file-view-receiver"



    /** Defines the key for whether hardware keyboard shortcuts are enabled. */
    public static final String KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS =  "disable-hardware-keyboard-shortcuts"; // Default: "disable-hardware-keyboard-shortcuts"



    /** Defines the key for whether a toast will be shown when user changes the terminal session */
    public static final String KEY_CREATE_SESSION_ON_SINGLE_SESSION_SWITCH = "create-session-on-single-session-switch";
    public static final String KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST =  "disable-terminal-session-change-toast"; // Default: "disable-terminal-session-change-toast"



    /** Defines the key for whether to enforce character based input to fix the issue where for some devices like Samsung, the letters might not appear until enter is pressed */
    public static final String KEY_ENFORCE_CHAR_BASED_INPUT =  "enforce-char-based-input"; // Default: "enforce-char-based-input"



    /** Defines the key for whether text for the extra keys buttons should be all capitalized automatically */
    public static final String KEY_EXTRA_KEYS_TEXT_ALL_CAPS =  "extra-keys-text-all-caps"; // Default: "extra-keys-text-all-caps"

    /** Defines the key for how high the rows of the extra keys toolbar are: {@link #EXTRA_KEYS_COMPACT_FALSE}
     * (normal), {@link #EXTRA_KEYS_COMPACT_TRUE} (lower) or {@link #EXTRA_KEYS_COMPACT_CUSTOM}
     * ({@link #KEY_EXTRA_KEYS_ROW_HEIGHT}). */
    public static final String KEY_EXTRA_KEYS_COMPACT =  "extra-keys-compact"; // Default: "extra-keys-compact"
    /** Rows of normal height, scaled by {@link #KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR}. */
    public static final String EXTRA_KEYS_COMPACT_FALSE = "false";
    /** Rows three quarters of their normal height. */
    public static final String EXTRA_KEYS_COMPACT_TRUE = "true";
    /** Rows exactly {@link #KEY_EXTRA_KEYS_ROW_HEIGHT} dp high. */
    public static final String EXTRA_KEYS_COMPACT_CUSTOM = "custom";
    public static final String DEFAULT_EXTRA_KEYS_COMPACT = EXTRA_KEYS_COMPACT_FALSE;

    /** Defines the key for the height of one extra keys row in dp, used when
     * {@link #KEY_EXTRA_KEYS_COMPACT} is {@link #EXTRA_KEYS_COMPACT_CUSTOM}. */
    public static final String KEY_EXTRA_KEYS_ROW_HEIGHT = "extra-keys-row-height"; // Default: "extra-keys-row-height"
    public static final int IVALUE_EXTRA_KEYS_ROW_HEIGHT_MIN = 16;
    public static final int IVALUE_EXTRA_KEYS_ROW_HEIGHT_MAX = 120;
    public static final int DEFAULT_IVALUE_EXTRA_KEYS_ROW_HEIGHT = 28;
    /** The normal height of one extra keys row in dp, the height of the toolbar in the layout. */
    public static final float EXTRA_KEYS_DEFAULT_ROW_HEIGHT_DP = 37.5f;

    /** Defines the key for whether the extra keys toolbar is shown only while the soft keyboard is. */
    public static final String KEY_EXTRA_KEYS_AUTO_HIDE =  "extra-keys-auto-hide"; // Default: "extra-keys-auto-hide"

    /** The factor that {@link #KEY_EXTRA_KEYS_COMPACT} applies to the row height, on top of
     * {@link #KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR}. */
    public static final float EXTRA_KEYS_COMPACT_ROW_SCALE = 0.75f;



    /** Defines the key for whether to hide soft keyboard when termux app is started */
    public static final String KEY_HIDE_SOFT_KEYBOARD_ON_SIDEBAR = "hide-soft-keyboard-on-sidebar";

    public static final String KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP =  "hide-soft-keyboard-on-startup"; // Default: "hide-soft-keyboard-on-startup"



    /** Defines the key for whether the {@link TermuxAmSocketServer} should be run at app startup */
    public static final String KEY_RUN_TERMUX_AM_SOCKET_SERVER =  "run-termux-am-socket-server"; // Default: "run-termux-am-socket-server"



    /** Defines the key for whether new default sessions enter the Nix guest (`nixpp login`) when the
     * bootstrap provides it. The failsafe session always starts the native shell. */
    public static final String KEY_NIX_SESSION =  "nix-session"; // Default: "nix-session"



    /** Defines the key for whether url links in terminal transcript will automatically open on click or on tap */
    public static final String KEY_TERMINAL_ONCLICK_URL_OPEN =  "terminal-onclick-url-open"; // Default: "terminal-onclick-url-open"



    /** Defines the key for whether to use black UI */
    @Deprecated
    public static final String KEY_USE_BLACK_UI =  "use-black-ui"; // Default: "use-black-ui"



    /** Defines the key for whether to use ctrl space workaround to fix the issue where ctrl+space does not work on some ROMs */
    public static final String KEY_USE_CTRL_SPACE_WORKAROUND =  "ctrl-space-workaround"; // Default: "ctrl-space-workaround"



    /** Defines the key for whether to use fullscreen */
    public static final String KEY_USE_FULLSCREEN =  "fullscreen"; // Default: "fullscreen"



    /** Defines the key for whether to use fullscreen workaround */
    public static final String KEY_USE_FULLSCREEN_WORKAROUND =  "use-fullscreen-workaround"; // Default: "use-fullscreen-workaround"





    /* int */

    /** Defines the key for the bell behaviour */
    public static final String KEY_BELL_BEHAVIOUR =  "bell-character"; // Default: "bell-character"

    public static final String VALUE_BELL_BEHAVIOUR_VIBRATE = "vibrate";
    public static final String VALUE_BELL_BEHAVIOUR_BEEP = "beep";
    public static final String VALUE_BELL_BEHAVIOUR_IGNORE = "ignore";
    public static final String DEFAULT_VALUE_BELL_BEHAVIOUR = VALUE_BELL_BEHAVIOUR_VIBRATE;

    public static final int IVALUE_BELL_BEHAVIOUR_VIBRATE = 1;
    public static final int IVALUE_BELL_BEHAVIOUR_BEEP = 2;
    public static final int IVALUE_BELL_BEHAVIOUR_IGNORE = 3;
    public static final int DEFAULT_IVALUE_BELL_BEHAVIOUR = IVALUE_BELL_BEHAVIOUR_VIBRATE;

    /** Defines the bidirectional map for bell behaviour values and their internal values */
    public static final ImmutableBiMap<String, Integer> MAP_BELL_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_BELL_BEHAVIOUR_VIBRATE, IVALUE_BELL_BEHAVIOUR_VIBRATE)
            .put(VALUE_BELL_BEHAVIOUR_BEEP, IVALUE_BELL_BEHAVIOUR_BEEP)
            .put(VALUE_BELL_BEHAVIOUR_IGNORE, IVALUE_BELL_BEHAVIOUR_IGNORE)
            .build();



    /** Defines the key for the terminal cursor blink rate */
    public static final String KEY_TERMINAL_CURSOR_BLINK_RATE =  "terminal-cursor-blink-rate"; // Default: "terminal-cursor-blink-rate"
    public static final int IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN;
    public static final int IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX;
    public static final int DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE = 0;



    /** Defines the key for the terminal cursor style */
    public static final String KEY_TERMINAL_CURSOR_STYLE =  "terminal-cursor-style"; // Default: "terminal-cursor-style"

    public static final String VALUE_TERMINAL_CURSOR_STYLE_BLOCK = "block";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = "underline";
    public static final String VALUE_TERMINAL_CURSOR_STYLE_BAR = "bar";

    public static final int IVALUE_TERMINAL_CURSOR_STYLE_BLOCK = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
    public static final int IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE;
    public static final int IVALUE_TERMINAL_CURSOR_STYLE_BAR = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR;
    public static final int DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;

    /** Defines the bidirectional map for terminal cursor styles and their internal values */
    public static final ImmutableBiMap<String, Integer> MAP_TERMINAL_CURSOR_STYLE =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(VALUE_TERMINAL_CURSOR_STYLE_BLOCK, IVALUE_TERMINAL_CURSOR_STYLE_BLOCK)
            .put(VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE)
            .put(VALUE_TERMINAL_CURSOR_STYLE_BAR, IVALUE_TERMINAL_CURSOR_STYLE_BAR)
            .build();




    /**
     * Defines the key for how many days old the access time should be of files that should be
     * deleted from $TMPDIR on termux exit.
     * `-1` for none, `0` for all and `> 0` for x days.
     */
    public static final String KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT =  "delete-tmpdir-files-older-than-x-days-on-exit"; // Default: "delete-tmpdir-files-older-than-x-days-on-exit"
    public static final int IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN = -1;
    public static final int IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX = 100000;
    public static final int DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT = 3;



    /** Defines the key for the terminal margin on left and right in dp units */
    public static final String KEY_TERMINAL_MARGIN_HORIZONTAL =  "terminal-margin-horizontal"; // Default: "terminal-margin-horizontal"
    public static final int IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL = 3;

    /** Defines the key for the terminal margin on top and bottom in dp units */
    public static final String KEY_TERMINAL_MARGIN_VERTICAL =  "terminal-margin-vertical"; // Default: "terminal-margin-vertical"
    public static final int IVALUE_TERMINAL_MARGIN_VERTICAL_MIN = 0;
    public static final int IVALUE_TERMINAL_MARGIN_VERTICAL_MAX = 100;
    public static final int DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL = 0;



    /** Defines the key for the terminal transcript rows */
    public static final String KEY_TERMINAL_ENGINE = "terminal-engine";
    public static final String TERMINAL_ENGINE_TERMUX = "termux";
    public static final String TERMINAL_ENGINE_GHOSTTY = "libghostty";

    public static final String KEY_TERMINAL_TRANSCRIPT_ROWS =  "terminal-transcript-rows"; // Default: "terminal-transcript-rows"
    public static final int IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN;
    public static final int IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX;
    public static final int DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS = TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;





    /* float */

    /** Defines the key for the terminal toolbar height */
    public static final String KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR =  "terminal-toolbar-height"; // Default: "terminal-toolbar-height"
    public static final float IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN = 0.4f;
    public static final float IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX = 3;
    public static final float DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR = 1;





    /* Integer */

    /** Defines the key for create session shortcut */
    public static final String KEY_SHORTCUT_CREATE_SESSION =  "shortcut.create-session"; // Default: "shortcut.create-session"
    /** Defines the key for next session shortcut */
    public static final String KEY_SHORTCUT_NEXT_SESSION =  "shortcut.next-session"; // Default: "shortcut.next-session"
    /** Defines the key for previous session shortcut */
    public static final String KEY_SHORTCUT_PREVIOUS_SESSION =  "shortcut.previous-session"; // Default: "shortcut.previous-session"
    /** Defines the key for rename session shortcut */
    public static final String KEY_SHORTCUT_RENAME_SESSION =  "shortcut.rename-session"; // Default: "shortcut.rename-session"
    /** Defines the key for the command palette shortcut */
    public static final String KEY_SHORTCUT_PALETTE =  "shortcut.palette"; // Default: "shortcut.palette"

    public static final int ACTION_SHORTCUT_CREATE_SESSION = 1;
    public static final int ACTION_SHORTCUT_NEXT_SESSION = 2;
    public static final int ACTION_SHORTCUT_PREVIOUS_SESSION = 3;
    public static final int ACTION_SHORTCUT_RENAME_SESSION = 4;
    public static final int ACTION_SHORTCUT_PALETTE = 5;

    /** Defines the bidirectional map for session shortcut values and their internal actions */
    public static final ImmutableBiMap<String, Integer> MAP_SESSION_SHORTCUTS =
        new ImmutableBiMap.Builder<String, Integer>()
            .put(KEY_SHORTCUT_CREATE_SESSION, ACTION_SHORTCUT_CREATE_SESSION)
            .put(KEY_SHORTCUT_NEXT_SESSION, ACTION_SHORTCUT_NEXT_SESSION)
            .put(KEY_SHORTCUT_PREVIOUS_SESSION, ACTION_SHORTCUT_PREVIOUS_SESSION)
            .put(KEY_SHORTCUT_RENAME_SESSION, ACTION_SHORTCUT_RENAME_SESSION)
            .put(KEY_SHORTCUT_PALETTE, ACTION_SHORTCUT_PALETTE)
            .build();





    /* String */

    /** Defines the key for whether back key will behave as escape key or literal back key */
    public static final String KEY_BACK_KEY_BEHAVIOUR =  "back-key"; // Default: "back-key"

    public static final String IVALUE_BACK_KEY_BEHAVIOUR_BACK = "back";
    public static final String IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE = "escape";
    public static final String DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR = IVALUE_BACK_KEY_BEHAVIOUR_BACK;

    /** Defines the bidirectional map for back key behaviour values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_BACK_KEY_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_BACK_KEY_BEHAVIOUR_BACK, IVALUE_BACK_KEY_BEHAVIOUR_BACK)
            .put(IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE, IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE)
            .build();



    /** Defines the key for the guest user name of a new Nix runtime */
    public static final String KEY_NIX_USER =  "nix-user"; // Default: "nix-user"

    public static final String DEFAULT_IVALUE_NIX_USER = "nix";



    /** Defines the key for the default working directory */
    public static final String KEY_DEFAULT_WORKING_DIRECTORY =  "default-working-directory"; // Default: "default-working-directory"
    /** Defines the default working directory */
    public static final String DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY = TermuxConstants.TERMUX_HOME_DIR_PATH;



    /** Defines the key for extra keys */
    public static final String KEY_EXTRA_KEYS =  "extra-keys"; // Default: "extra-keys"
    //public static final String DEFAULT_IVALUE_EXTRA_KEYS = "[[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"; // Single row
    public static final String DEFAULT_IVALUE_EXTRA_KEYS = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]"; // Double row

    /** Defines the key for extra keys style */
    public static final String KEY_EXTRA_KEYS_THEME = "extra-keys-theme";
    public static final String EXTRA_KEYS_THEME_DEFAULT = "default";
    public static final String EXTRA_KEYS_THEME_ROUNDED = "rounded";
    public static final String KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR = "extra-keys-row-background-color";

    public static final String KEY_EXTRA_KEYS_FONT = "extra-keys-font";
    public static final String DEFAULT_EXTRA_KEYS_FONT = "system";
    public static final String[] EXTRA_KEYS_FONTS = {"system", "terminal", "monospace", "serif"};

    public static final String KEY_EXTRA_KEYS_ANIMATION = "extra-keys-animation";

    public static final String KEY_EXTRA_KEYS_STYLE =  "extra-keys-style"; // Default: "extra-keys-style"
    public static final String DEFAULT_IVALUE_EXTRA_KEYS_STYLE = "default";



    /** Defines the key for the text color of an active (toggled or locked) extra keys special
     * button like CTRL and ALT. Value is {@code #RGB}, {@code #RRGGBB} or {@code #AARRGGBB}. If
     * not set or invalid, then the color defined by the current theme is used. */
    public static final String KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR =  "extra-keys-button-active-text-color"; // Default: "extra-keys-button-active-text-color"

    /** Defines the key for the background color of a pressed extra keys button. Value is
     * {@code #RGB}, {@code #RRGGBB} or {@code #AARRGGBB}. If not set or invalid, then the color
     * defined by the current theme is used. */
    public static final String KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR =  "extra-keys-button-active-background-color"; // Default: "extra-keys-button-active-background-color"

    /** Defines the key for the corner radius in dp of the extra keys button backgrounds, a number
     * from 0 to 32 like {@code 8} or {@code 6.5}. If not set or invalid, then the shape defined by
     * the current theme is used. */
    public static final String KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS =  "extra-keys-button-corner-radius"; // Default: "extra-keys-button-corner-radius"



    /* TerminalGestureAction (never null) */

    /** Layout used for tmux attachments in the terminal sidebar. */
    public static final String KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT = "terminal-tmux-sidebar-layout";
    public static final String TMUX_SIDEBAR_NESTED = "nested";
    public static final String TMUX_SIDEBAR_DRILL_DOWN = "drill-down";
    public static final String TMUX_SIDEBAR_MODES = "modes";
    public static final String KEY_SIDEBAR_LEFT_SESSIONS = "sidebar-left-show-sessions";
    public static final String KEY_SIDEBAR_LEFT_TMUX_WINDOWS = "sidebar-left-show-tmux-windows";
    public static final String KEY_SIDEBAR_LEFT_NEW_SESSION = "sidebar-left-show-new-session";
    public static final String KEY_SIDEBAR_LEFT_KEYBOARD = "sidebar-left-show-keyboard-button";
    public static final String KEY_SIDEBAR_RIGHT_SESSIONS = "sidebar-right-show-sessions";
    public static final String KEY_SIDEBAR_RIGHT_TMUX_WINDOWS = "sidebar-right-show-tmux-windows";
    public static final String KEY_SIDEBAR_RIGHT_NEW_SESSION = "sidebar-right-show-new-session";
    public static final String KEY_SIDEBAR_RIGHT_KEYBOARD = "sidebar-right-show-keyboard-button";

    public static final String KEY_TMUX_RIGHT_SIDEBAR = "tmux-right-sidebar";

    public static final String KEY_TERMINAL_TMUX_SIDEBAR_STYLE = "terminal-tmux-sidebar-style";
    public static final String TMUX_SIDEBAR_SUBTLE = "subtle";
    public static final String TMUX_SIDEBAR_ROUNDED = "rounded";
    public static final String DEFAULT_TMUX_SIDEBAR_STYLE = TMUX_SIDEBAR_ROUNDED;

    /** Where the session picker and the tmux window picker open. */
    public static final String KEY_TERMINAL_PICKER_POSITION = "terminal-picker-position";
    /** At the top of the screen. */
    public static final String PICKER_POSITION_TOP = "top";
    /** In the middle of the screen. */
    public static final String PICKER_POSITION_CENTER = "center";
    /** In the middle of the terminal view, which shrinks while the keyboard is shown. */
    public static final String PICKER_POSITION_TERMINAL = "terminal";
    public static final String DEFAULT_PICKER_POSITION = PICKER_POSITION_TOP;

    public static final String KEY_TERMINAL_TOAST_POSITION = "terminal-toast-position";
    public static final int DEFAULT_TERMINAL_TOAST_GRAVITY = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    public static final ImmutableBiMap<String, Integer> MAP_TERMINAL_TOAST_POSITION =
        new ImmutableBiMap.Builder<String, Integer>()
            .put("top-left", Gravity.TOP | Gravity.LEFT)
            .put("top-center", DEFAULT_TERMINAL_TOAST_GRAVITY)
            .put("top-right", Gravity.TOP | Gravity.RIGHT)
            .put("center-left", Gravity.CENTER_VERTICAL | Gravity.LEFT)
            .put("center", Gravity.CENTER)
            .put("center-right", Gravity.CENTER_VERTICAL | Gravity.RIGHT)
            .put("bottom-left", Gravity.BOTTOM | Gravity.LEFT)
            .put("bottom-center", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
            .put("bottom-right", Gravity.BOTTOM | Gravity.RIGHT)
            .build();

    public static final String KEY_ADB_SHELL_ALLOWED_SSIDS = "adb-shell-allowed-ssids";

    public static final String KEY_GESTURE_ANIMATIONS = "gesture-animations";
    public static final String KEY_GESTURE_FEEDBACK_POSITION = "gesture-feedback-position";
    public static final int DEFAULT_GESTURE_FEEDBACK_GRAVITY = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    public static final String KEY_GESTURE_FEEDBACK_STYLE = "gesture-feedback-style";
    public static final String DEFAULT_GESTURE_FEEDBACK_STYLE = "soft-flash";
    public static final String[] GESTURE_FEEDBACK_STYLES = {"soft-flash", "touch-ripples", "edge-glow",
        "light-sweep", "touch-bloom", "corner-brackets"};

    /** Defines the key for whether "Acquire wakelock" also holds a high-performance Wi-Fi lock, which
     * keeps the Wi-Fi radio out of power save. Default: false, the CPU wake lock alone. */
    public static final String KEY_WAKE_LOCK_WIFI = "wake-lock-wifi"; // Default: "wake-lock-wifi"

    /** Defines the key for after how many minutes a wake lock is released by itself.
     * `0` keeps it until it is released. */
    public static final String KEY_WAKE_LOCK_TIMEOUT = "wake-lock-timeout"; // Default: "wake-lock-timeout"
    public static final int IVALUE_WAKE_LOCK_TIMEOUT_MIN = 0;
    public static final int IVALUE_WAKE_LOCK_TIMEOUT_MAX = 7 * 24 * 60;
    public static final int DEFAULT_IVALUE_WAKE_LOCK_TIMEOUT = 0;

    public static final String KEY_TERMINAL_SINGLE_TAP_ACTION =  "terminal-single-tap-action"; // Default: "terminal-single-tap-action"
    public static final TerminalGestureAction DEFAULT_TERMINAL_SINGLE_TAP_ACTION = TerminalGestureAction.SHOW_KEYBOARD;

    /** Defines the key for the {@link TerminalGestureAction} of two quick finger taps on the terminal. */
    public static final String KEY_TERMINAL_DOUBLE_TAP_ACTION =  "terminal-double-tap-action"; // Default: "terminal-double-tap-action"
    public static final TerminalGestureAction DEFAULT_TERMINAL_DOUBLE_TAP_ACTION = TerminalGestureAction.NONE;

    /** Defines the key for the {@link TerminalGestureAction} of three quick finger taps on the terminal. */
    public static final String KEY_TERMINAL_TRIPLE_TAP_ACTION =  "terminal-triple-tap-action"; // Default: "terminal-triple-tap-action"
    public static final TerminalGestureAction DEFAULT_TERMINAL_TRIPLE_TAP_ACTION = TerminalGestureAction.NONE;
    public static final String KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE = "terminal-triple-tap-focus-title";

    /** Defines the key for the {@link TerminalGestureAction} of a swipe on the normal extra keys row
     * with the finger moving from left to right, toward the (non-existing) page on the left. */
    public static final String KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION =  "extra-keys-swipe-left-action"; // Default: "extra-keys-swipe-left-action"
    public static final TerminalGestureAction DEFAULT_EXTRA_KEYS_SWIPE_LEFT_ACTION = TerminalGestureAction.NONE;

    /** Defines the key for the {@link TerminalGestureAction} of a swipe on the normal extra keys row
     * with the finger moving from right to left, toward the text input page on the right. Opening
     * the text input is {@link TerminalGestureAction#TOGGLE_TEXT_INPUT}. */
    public static final String KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION =  "extra-keys-swipe-right-action"; // Default: "extra-keys-swipe-right-action"
    public static final TerminalGestureAction DEFAULT_EXTRA_KEYS_SWIPE_RIGHT_ACTION = TerminalGestureAction.NEXT_SESSION;

    /** Defines the key for the {@link TerminalGestureAction} of a long (further) swipe in the
     * direction of {@link #KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}. */
    public static final String KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION =  "extra-keys-long-swipe-left-action"; // Default: "extra-keys-long-swipe-left-action"
    public static final TerminalGestureAction DEFAULT_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION = TerminalGestureAction.NONE;

    /** Defines the key for the {@link TerminalGestureAction} of a long (further) swipe in the
     * direction of {@link #KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION}. */
    public static final String KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION =  "extra-keys-long-swipe-right-action"; // Default: "extra-keys-long-swipe-right-action"
    public static final TerminalGestureAction DEFAULT_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION = TerminalGestureAction.NONE;

    /** Defines the shell context used when a gesture or extra-key command starts a session. */
    public static final String KEY_GESTURE_COMMAND_CONTEXT = "gesture-command-context";
    public static final GestureCommandContext DEFAULT_GESTURE_COMMAND_CONTEXT = GestureCommandContext.SESSION;

    /** Defines the shell command for a single terminal tap action. */
    public static final String KEY_TERMINAL_SINGLE_TAP_COMMAND = "terminal-single-tap-command";

    /** Defines the shell command for a double terminal tap action. */
    public static final String KEY_TERMINAL_DOUBLE_TAP_COMMAND = "terminal-double-tap-command";

    /** Defines the shell command for a triple terminal tap action. */
    public static final String KEY_TERMINAL_TRIPLE_TAP_COMMAND = "terminal-triple-tap-command";

    /** Defines the shell command for a left swipe on the extra keys row. */
    public static final String KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND = "extra-keys-swipe-left-command";

    /** Defines the shell command for a right swipe on the extra keys row. */
    public static final String KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND = "extra-keys-swipe-right-command";

    /** Defines the shell command for a long left swipe on the extra keys row. */
    public static final String KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND = "extra-keys-long-swipe-left-command";

    /** Defines the shell command for a long right swipe on the extra keys row. */
    public static final String KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND = "extra-keys-long-swipe-right-command";



    /* Volume key action (never null) */

    /** The value of the volume key actions for the virtual modifier that the volume buttons are
     * without configuration: volume down is Ctrl and volume up is Fn while held. */
    public static final String VOLUME_KEY_ACTION_MODIFIER = "modifier";

    /** Defines the key for what the volume up button does, either {@link #VOLUME_KEY_ACTION_MODIFIER}
     * (the virtual Fn key while held, the default) or a {@link TerminalGestureAction}. Only used if
     * {@link #KEY_VOLUME_KEYS_BEHAVIOUR} is {@code virtual}. */
    public static final String KEY_VOLUME_UP_ACTION =  "volume-up-action"; // Default: "modifier"

    /** Defines the key for what the volume down button does, either {@link #VOLUME_KEY_ACTION_MODIFIER}
     * (the virtual Ctrl key while held, the default) or a {@link TerminalGestureAction}. Only used if
     * {@link #KEY_VOLUME_KEYS_BEHAVIOUR} is {@code virtual}. */
    public static final String KEY_VOLUME_DOWN_ACTION =  "volume-down-action"; // Default: "modifier"



    /* TerminalKeySequence String (may be null) */

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for a single tap
     * on the terminal, in the grammar of an extra keys macro, like "ESC" or "CTRL l". */
    public static final String KEY_TERMINAL_SINGLE_TAP_KEYS =  "terminal-single-tap-keys"; // Default: "terminal-single-tap-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for a double tap. */
    public static final String KEY_TERMINAL_DOUBLE_TAP_KEYS =  "terminal-double-tap-keys"; // Default: "terminal-double-tap-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for a triple tap. */
    public static final String KEY_TERMINAL_TRIPLE_TAP_KEYS =  "terminal-triple-tap-keys"; // Default: "terminal-triple-tap-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for the swipe on
     * the normal extra keys row, see {@link #KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}. */
    public static final String KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS =  "extra-keys-swipe-left-keys"; // Default: "extra-keys-swipe-left-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for {@link #KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION}. */
    public static final String KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS =  "extra-keys-swipe-right-keys"; // Default: "extra-keys-swipe-right-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for {@link #KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION}. */
    public static final String KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS =  "extra-keys-long-swipe-left-keys"; // Default: "extra-keys-long-swipe-left-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for {@link #KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION}. */
    public static final String KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS =  "extra-keys-long-swipe-right-keys"; // Default: "extra-keys-long-swipe-right-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for the volume up button. */
    public static final String KEY_VOLUME_UP_KEYS =  "volume-up-keys"; // Default: "volume-up-keys"

    /** Defines the key for the keys that {@link TerminalGestureAction#SEND_KEYS} sends for the volume down button. */
    public static final String KEY_VOLUME_DOWN_KEYS =  "volume-down-keys"; // Default: "volume-down-keys"



    /** Defines the key for {@link NightMode}. */
    /** The accent color of the app interface: default, adaptive, blue, teal or rose. */
    public static final String KEY_APP_ACCENT = "app-accent";
    public static final String DEFAULT_APP_ACCENT = "default";
    public static final String[] APP_ACCENTS = {"default", "adaptive", "blue", "teal", "rose"};

    /** The terminal color scheme: "config" for colors.properties, "default", or a bundled preset. */
    public static final String KEY_TERMINAL_COLOR_SCHEME = "terminal-color-scheme";

    /** The terminal font: "config" for font.ttf, "default", or a bundled preset. */
    public static final String KEY_TERMINAL_FONT = "terminal-font";

    /** The value of {@link #KEY_TERMINAL_COLOR_SCHEME} and {@link #KEY_TERMINAL_FONT} that reads the files. */
    public static final String TERMINAL_APPEARANCE_CONFIG = "config";

    public static final String KEY_NIGHT_MODE = "night-mode"; // Default: "night-mode"

    public static final String IVALUE_NIGHT_MODE_TRUE = NightMode.TRUE.getName();
    public static final String IVALUE_NIGHT_MODE_FALSE = NightMode.FALSE.getName();
    public static final String IVALUE_NIGHT_MODE_SYSTEM = NightMode.SYSTEM.getName();
    public static final String DEFAULT_IVALUE_NIGHT_MODE = IVALUE_NIGHT_MODE_SYSTEM;

    /** Defines the bidirectional map for {@link NightMode} values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_NIGHT_MODE =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_NIGHT_MODE_TRUE, IVALUE_NIGHT_MODE_TRUE)
            .put(IVALUE_NIGHT_MODE_FALSE, IVALUE_NIGHT_MODE_FALSE)
            .put(IVALUE_NIGHT_MODE_SYSTEM, IVALUE_NIGHT_MODE_SYSTEM)
            .build();



    /** Defines the key for whether toggle soft keyboard request will show/hide or enable/disable keyboard */
    public static final String KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR =  "soft-keyboard-toggle-behaviour"; // Default: "soft-keyboard-toggle-behaviour"

    public static final String IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE = "show/hide";
    public static final String IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE = "enable/disable";
    public static final String DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR = IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE;

    /** Defines the bidirectional map for toggle soft keyboard behaviour values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE)
            .put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE)
            .build();



    /** Defines the key for whether volume keys will behave as virtual or literal volume keys */
    public static final String KEY_VOLUME_KEYS_BEHAVIOUR =  "volume-keys"; // Default: "volume-keys"

    public static final String IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL = "virtual";
    public static final String IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME = "volume";
    public static final String DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR = IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL;

    /** Defines the bidirectional map for volume keys behaviour values and their internal values */
    public static final ImmutableBiMap<String, String> MAP_VOLUME_KEYS_BEHAVIOUR =
        new ImmutableBiMap.Builder<String, String>()
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL, IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL)
            .put(IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME, IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME)
            .build();





    public static final Set<String> TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES = multiFingerSwipeProperties();

    private static Set<String> multiFingerSwipeProperties() {
        Set<String> properties = new HashSet<>();
        for (int fingers = 2; fingers <= 3; fingers++) {
            for (String direction : Arrays.asList("up", "down", "left", "right")) {
                for (String type : Arrays.asList("action", "keys", "command"))
                    properties.add(terminalMultiFingerSwipeProperty(fingers, direction, type));
            }
        }
        return java.util.Collections.unmodifiableSet(properties);
    }

    public static TerminalGestureAction defaultMultiFingerSwipeAction(String key) {
        return "terminal-2-finger-swipe-down-action".equals(key)
            ? TerminalGestureAction.OPEN_TMUX_SWITCHER : TerminalGestureAction.NONE;
    }

    public static String terminalMultiFingerSwipeProperty(int fingers, String direction, String type) {
        return "terminal-" + fingers + "-finger-swipe-" + direction + "-" + type;
    }

    /** Defines the set for keys loaded by termux
     * Setting this to {@code null} will make {@link SharedProperties} throw an exception.
     * */
    public static final Set<String> TERMUX_APP_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        /* boolean */
        KEY_GESTURE_ANIMATIONS,
        KEY_GESTURE_FEEDBACK_STYLE,
        KEY_GESTURE_FEEDBACK_POSITION,
        KEY_WAKE_LOCK_WIFI,
        KEY_ADB_SHELL_ALLOWED_SSIDS,
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_CREATE_SESSION_ON_SINGLE_SESSION_SWITCH,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_EXTRA_KEYS_COMPACT,
        KEY_EXTRA_KEYS_ROW_HEIGHT,
        KEY_EXTRA_KEYS_AUTO_HIDE,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_HIDE_SOFT_KEYBOARD_ON_SIDEBAR,
        KEY_NIX_SESSION,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS,

        /* int */
        KEY_BELL_BEHAVIOUR,
        KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
        KEY_WAKE_LOCK_TIMEOUT,
        KEY_TERMINAL_CURSOR_BLINK_RATE,
        KEY_TERMINAL_CURSOR_STYLE,
        KEY_TERMINAL_MARGIN_HORIZONTAL,
        KEY_TERMINAL_MARGIN_VERTICAL,
        KEY_TERMINAL_ENGINE,
        KEY_TERMINAL_TRANSCRIPT_ROWS,

        /* float */
        KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,

        /* Float (may be null) */
        KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS,
        KEY_EXTRA_KEYS_ANIMATION,
        KEY_EXTRA_KEYS_FONT,
        KEY_EXTRA_KEYS_THEME,

        /* TerminalGestureAction */
        KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION,
        KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION,
        KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION,
        KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION,
        KEY_TERMINAL_DOUBLE_TAP_ACTION,
        KEY_TERMINAL_SINGLE_TAP_ACTION,
        KEY_TERMINAL_TRIPLE_TAP_ACTION,
        KEY_TERMINAL_TRIPLE_TAP_FOCUS_TITLE,
        KEY_TERMINAL_TOAST_POSITION,
        KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT,
        KEY_TERMINAL_TMUX_SIDEBAR_STYLE,
        KEY_TMUX_RIGHT_SIDEBAR,
        KEY_SIDEBAR_LEFT_SESSIONS,
        KEY_SIDEBAR_LEFT_TMUX_WINDOWS,
        KEY_SIDEBAR_LEFT_NEW_SESSION,
        KEY_SIDEBAR_LEFT_KEYBOARD,
        KEY_SIDEBAR_RIGHT_SESSIONS,
        KEY_SIDEBAR_RIGHT_TMUX_WINDOWS,
        KEY_SIDEBAR_RIGHT_NEW_SESSION,
        KEY_SIDEBAR_RIGHT_KEYBOARD,
        KEY_TERMINAL_PICKER_POSITION,

        /* gesture command configuration */
        KEY_GESTURE_COMMAND_CONTEXT,
        KEY_TERMINAL_SINGLE_TAP_COMMAND,
        KEY_TERMINAL_DOUBLE_TAP_COMMAND,
        KEY_TERMINAL_TRIPLE_TAP_COMMAND,
        KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND,
        KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND,
        KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND,
        KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND,

        /* Volume key action: VOLUME_KEY_ACTION_MODIFIER or a TerminalGestureAction */
        KEY_VOLUME_DOWN_ACTION,
        KEY_VOLUME_UP_ACTION,

        /* TerminalKeySequence String (may be null) */
        KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS,
        KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS,
        KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS,
        KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS,
        KEY_TERMINAL_DOUBLE_TAP_KEYS,
        KEY_TERMINAL_SINGLE_TAP_KEYS,
        KEY_TERMINAL_TRIPLE_TAP_KEYS,
        KEY_VOLUME_DOWN_KEYS,
        KEY_VOLUME_UP_KEYS,

        /* Integer */
        KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR,
        KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR,
        KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR,
        KEY_SHORTCUT_CREATE_SESSION,
        KEY_SHORTCUT_NEXT_SESSION,
        KEY_SHORTCUT_PREVIOUS_SESSION,
        KEY_SHORTCUT_RENAME_SESSION,
        KEY_SHORTCUT_PALETTE,

        /* String */
        KEY_BACK_KEY_BEHAVIOUR,
        KEY_DEFAULT_WORKING_DIRECTORY,
        KEY_NIX_USER,
        KEY_EXTRA_KEYS,
        KEY_EXTRA_KEYS_STYLE,
        KEY_NIGHT_MODE,
        KEY_APP_ACCENT,
        KEY_TERMINAL_COLOR_SCHEME,
        KEY_TERMINAL_FONT,
        KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR,
        KEY_VOLUME_KEYS_BEHAVIOUR
    ));

    static {
        TERMUX_APP_PROPERTIES_LIST.addAll(TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES);
    }

    /** Defines the set for keys loaded by termux that have default boolean behaviour with false as default.
     * "true" -> true
     * "false" -> false
     * default: false
     */
    public static final Set<String> TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_SIDEBAR_RIGHT_SESSIONS,
        KEY_SIDEBAR_RIGHT_NEW_SESSION,
        KEY_SIDEBAR_RIGHT_KEYBOARD,
        KEY_WAKE_LOCK_WIFI,
        KEY_TMUX_RIGHT_SIDEBAR,
        KEY_DISABLE_FILE_SHARE_RECEIVER,
        KEY_DISABLE_FILE_VIEW_RECEIVER,
        KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_CREATE_SESSION_ON_SINGLE_SESSION_SWITCH,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST,
        KEY_ENFORCE_CHAR_BASED_INPUT,
        KEY_EXTRA_KEYS_AUTO_HIDE,
        KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_TERMINAL_ONCLICK_URL_OPEN,
        KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_USE_FULLSCREEN,
        KEY_USE_FULLSCREEN_WORKAROUND,
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS
    ));

    /** Defines the set for keys loaded by termux that have default boolean behaviour with true as default.
     * "true" -> true
     * "false" -> false
     * default: true
     */
    public static final Set<String> TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
        KEY_SIDEBAR_LEFT_SESSIONS,
        KEY_SIDEBAR_LEFT_TMUX_WINDOWS,
        KEY_SIDEBAR_LEFT_NEW_SESSION,
        KEY_SIDEBAR_LEFT_KEYBOARD,
        KEY_SIDEBAR_RIGHT_TMUX_WINDOWS,
        KEY_GESTURE_ANIMATIONS,
        KEY_HIDE_SOFT_KEYBOARD_ON_SIDEBAR,
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS,
        KEY_NIX_SESSION,
        KEY_RUN_TERMUX_AM_SOCKET_SERVER
    ));

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with false as default.
     * "false" -> true
     * "true" -> false
     * default: false
     */
    public static final Set<String> TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
    ));

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with true as default.
     * "false" -> true
     * "true" -> false
     * default: true
     */
    public static final Set<String> TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST = new HashSet<>(Arrays.asList(
    ));

}
