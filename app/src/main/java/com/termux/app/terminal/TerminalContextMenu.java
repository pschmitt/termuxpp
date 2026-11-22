package com.termux.app.terminal;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.Menu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The entries of the terminal long-press menu, shared by the bottom sheet and by the system context
 * menu that is used as a fallback, so that both offer the same actions.
 *
 * Help and Report issue are deliberately not part of this menu: Report issue lives in Settings
 * (see {@link ReportIssueRequest}) and Help is reachable from the documentation.
 */
public final class TerminalContextMenu {

    public static final int SELECT_URL = 0;
    public static final int SHARE_TRANSCRIPT = 1;
    public static final int AUTOFILL_PASSWORD = 2;
    public static final int RESET_TERMINAL = 3;
    public static final int KILL_PROCESS = 4;
    public static final int TOGGLE_KEEP_SCREEN_ON = 6;
    public static final int SETTINGS = 8;
    public static final int SHARE_SELECTED_TEXT = 10;
    public static final int AUTOFILL_USERNAME = 11;
    /** Holding the kill tile: kill without asking. Only the sheet offers it, as a long press. */
    public static final int KILL_PROCESS_NOW = 12;
    /** Open the share sheet, which offers the selected text and the transcript. Only the sheet offers it. */
    public static final int SHARE_MENU = 13;
    /** Open the autofill sheet, which offers the username and the password. Only the sheet offers it. */
    public static final int AUTOFILL_MENU = 14;
    /** Acquire or release the wake lock, like the notification's button. */
    public static final int TOGGLE_WAKE_LOCK = 15;

    /** What decides which entries are shown, and how. */
    public static final class State {
        public final boolean autoFillEnabled;
        public final boolean hasSelectedText;
        public final int sessionPid;
        public final boolean sessionRunning;
        public final boolean keepScreenOn;
        /** Whether the service holds its wake lock. */
        public final boolean wakeLockHeld;
        /** The name the user gave the session, or null. */
        @Nullable public final String sessionName;
        /** The name of the session's process, such as zsh, or null if unknown. */
        @Nullable public final String processName;
        /** The working directory of the session's process, as it should be shown, or null if unknown. */
        @Nullable public final String cwd;
        /** The 1-based position of the session, or 0 if unknown. */
        public final int sessionNumber;
        public final int sessionCount;
        /** The number of URLs that Open URL would list, or -1 if unknown. */
        public final int urlCount;

        public State(boolean autoFillEnabled, boolean hasSelectedText,
                     int sessionPid, boolean sessionRunning, boolean keepScreenOn) {
            this(autoFillEnabled, hasSelectedText, sessionPid, sessionRunning, keepScreenOn,
                null, null, null, 0, 0, -1);
        }

        public State(boolean autoFillEnabled, boolean hasSelectedText,
                     int sessionPid, boolean sessionRunning, boolean keepScreenOn,
                     @Nullable String sessionName, @Nullable String processName, @Nullable String cwd,
                     int sessionNumber, int sessionCount, int urlCount) {
            this(autoFillEnabled, hasSelectedText, sessionPid, sessionRunning, keepScreenOn, false,
                sessionName, processName, cwd, sessionNumber, sessionCount, urlCount);
        }

        public State(boolean autoFillEnabled, boolean hasSelectedText,
                     int sessionPid, boolean sessionRunning, boolean keepScreenOn, boolean wakeLockHeld,
                     @Nullable String sessionName, @Nullable String processName, @Nullable String cwd,
                     int sessionNumber, int sessionCount, int urlCount) {
            this.wakeLockHeld = wakeLockHeld;
            this.autoFillEnabled = autoFillEnabled;
            this.hasSelectedText = hasSelectedText;
            this.sessionPid = sessionPid;
            this.sessionRunning = sessionRunning;
            this.keepScreenOn = keepScreenOn;
            this.sessionName = sessionName;
            this.processName = processName;
            this.cwd = cwd;
            this.sessionNumber = sessionNumber;
            this.sessionCount = sessionCount;
            this.urlCount = urlCount;
        }
    }

    private TerminalContextMenu() {}

    /** The header title: the session name, else its process, else its number. */
    @NonNull
    static String headerTitle(@NonNull Resources resources, @NonNull State state) {
        if (!TextUtils.isEmpty(state.sessionName)) return state.sessionName;
        if (!TextUtils.isEmpty(state.processName)) return state.processName;
        return resources.getString(R.string.ui_session_unnamed, Math.max(state.sessionNumber, 1));
    }

    /** The header detail line, such as {@code ~/devel · pid 4182 · 2 of 3}. */
    @NonNull
    static String headerDetail(@NonNull Resources resources, @NonNull State state) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(state.cwd)) parts.add(state.cwd);
        parts.add(state.sessionRunning && state.sessionPid > 0
            ? resources.getString(R.string.ui_menu_session_pid, state.sessionPid)
            : resources.getString(R.string.ui_menu_not_running));
        if (state.sessionCount > 1 && state.sessionNumber > 0)
            parts.add(resources.getString(R.string.ui_menu_session_position, state.sessionNumber, state.sessionCount));
        return TextUtils.join(resources.getString(R.string.ui_menu_session_detail_separator), parts);
    }

    /**
     * Add the entries of the bottom sheet, in the grouped presentation: a session header, a strip of the frequent
     * actions, power states, Settings, and expandable secondary commands.
     */
    public static TermuxContextMenuSheet populateSheet(@NonNull TermuxContextMenuSheet sheet,
                                                       @NonNull Resources resources, @NonNull State state) {
        sheet.setSessionHeader(headerTitle(resources, state), headerDetail(resources, state));

        // The frequent actions. Where there is a choice, the shortcut opens a sheet of its own.
        if (state.autoFillEnabled) {
            sheet.addShortcut(AUTOFILL_MENU, resources.getString(R.string.ui_menu_autofill), null,
                R.drawable.ui_ic_menu_key);
        }
        sheet.addShortcut(SELECT_URL, resources.getString(R.string.ui_menu_open_url), null,
            R.drawable.ui_ic_menu_link, state.urlCount != 0);
        sheet.addShortcut(state.hasSelectedText ? SHARE_MENU : SHARE_TRANSCRIPT,
            resources.getString(R.string.ui_menu_share), null, R.drawable.ui_ic_menu_share);

        sheet.addStateRow(TOGGLE_KEEP_SCREEN_ON, resources.getString(R.string.action_toggle_keep_screen_on),
            R.drawable.ui_ic_menu_sun, state.keepScreenOn);
        sheet.addStateRow(TOGGLE_WAKE_LOCK, resources.getString(R.string.action_toggle_wake_lock),
            R.drawable.ui_ic_bolt, state.wakeLockHeld);
        sheet.addRow(SETTINGS, resources.getString(R.string.action_open_settings), null, R.drawable.ui_ic_menu_settings);
        sheet.addSecondaryRow(RESET_TERMINAL, resources.getString(R.string.action_reset_terminal),
            resources.getString(R.string.ui_menu_reset_detail), R.drawable.ui_ic_menu_reset);

        String killTarget = TextUtils.isEmpty(state.processName)
            ? resources.getString(R.string.ui_menu_kill_session_target) : state.processName;
        sheet.addDangerRow(KILL_PROCESS, resources.getString(R.string.ui_menu_kill, killTarget),
            resources.getString(state.sessionRunning ? R.string.ui_menu_kill_detail : R.string.ui_menu_not_running),
            R.drawable.ui_ic_menu_power, state.sessionRunning,
            KILL_PROCESS_NOW, resources.getString(R.string.ui_menu_kill_now), true);
        return sheet;
    }

    /** How many lines a card preview shows. */
    static final int PREVIEW_LINES = 3;
    /** How many characters of a line a card preview shows, about the width of a card on a phone. */
    static final int PREVIEW_COLUMNS = 18;

    /**
     * Add the entries of the sheet that a tile of the menu sheet opens ({@link #SHARE_MENU} or
     * {@link #AUTOFILL_MENU}), or return null for any other id.
     *
     * @param selectedText The stored selected text, or null.
     * @param transcript The transcript of the session, or null if unknown.
     */
    @Nullable
    public static TermuxContextMenuSheet populateChoiceSheet(int menuId, @NonNull TermuxContextMenuSheet sheet,
                                                             @NonNull Resources resources, @NonNull State state,
                                                             @Nullable String selectedText, @Nullable String transcript) {
        String session = headerTitle(resources, state);
        switch (menuId) {
            case SHARE_MENU:
                sheet.setHeader(R.drawable.ui_ic_menu_share, resources.getString(R.string.ui_menu_share), session);
                if (state.hasSelectedText) {
                    int chars = selectedText == null ? 0 : selectedText.length();
                    sheet.addCard(SHARE_SELECTED_TEXT, resources.getString(R.string.ui_menu_share_selection),
                        resources.getQuantityString(R.plurals.ui_menu_share_selection_chars, chars, chars),
                        R.drawable.ui_ic_menu_selection, firstLines(selectedText, PREVIEW_LINES));
                }
                int lines = countLines(transcript);
                sheet.addCard(SHARE_TRANSCRIPT, resources.getString(R.string.ui_menu_share_transcript),
                    lines < 0 ? resources.getString(R.string.ui_menu_share_transcript_whole)
                        : resources.getQuantityString(R.plurals.ui_menu_share_transcript_lines, lines, lines),
                    R.drawable.ui_ic_menu_transcript, lastLines(transcript, PREVIEW_LINES));
                return sheet;
            case AUTOFILL_MENU:
                sheet.setHeader(R.drawable.ui_ic_menu_key, resources.getString(R.string.ui_menu_autofill), session);
                sheet.addCard(AUTOFILL_USERNAME, resources.getString(R.string.ui_menu_username),
                    resources.getString(R.string.ui_menu_autofill_username_detail), R.drawable.ui_ic_menu_user,
                    resources.getString(R.string.ui_menu_autofill_username_preview));
                sheet.addCard(AUTOFILL_PASSWORD, resources.getString(R.string.ui_menu_password),
                    resources.getString(R.string.ui_menu_autofill_password_detail), R.drawable.ui_ic_menu_lock,
                    resources.getString(R.string.ui_menu_autofill_password_preview));
                return sheet;
            default:
                return null;
        }
    }

    /** The first non-blank lines of a text, each cut to the width of a preview, or null if there are none. */
    @Nullable
    static String firstLines(@Nullable String text, int count) {
        if (text == null) return null;
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.trim().isEmpty()) continue;
            out.add(previewLine(line));
            if (out.size() == count) break;
        }
        return out.isEmpty() ? null : TextUtils.join("\n", out);
    }

    /** The last non-blank lines of a text, each cut to the width of a preview, or null if there are none. */
    @Nullable
    static String lastLines(@Nullable String text, int count) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = lines.length - 1; i >= 0 && out.size() < count; i--) {
            if (!lines[i].trim().isEmpty()) out.add(0, previewLine(lines[i]));
        }
        return out.isEmpty() ? null : TextUtils.join("\n", out);
    }

    /** One line of a preview: no trailing space, and cut with an ellipsis like a terminal that does not wrap. */
    static String previewLine(String line) {
        String trimmed = stripEnd(line);
        return trimmed.length() <= PREVIEW_COLUMNS ? trimmed : trimmed.substring(0, PREVIEW_COLUMNS - 1) + "…";
    }

    /** The number of lines up to the last non-blank one, or -1 for an unknown transcript. */
    static int countLines(@Nullable String text) {
        if (text == null) return -1;
        String[] lines = text.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) end--;
        return end;
    }

    private static String stripEnd(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    /** Add the flat entries of the system context menu fallback. */
    public static void populateMenu(@NonNull Menu menu, @NonNull Resources resources, @NonNull State state) {
        menu.add(Menu.NONE, SELECT_URL, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, SHARE_TRANSCRIPT, Menu.NONE, R.string.action_share_transcript);
        if (state.hasSelectedText)
            menu.add(Menu.NONE, SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (state.autoFillEnabled) {
            menu.add(Menu.NONE, AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
            menu.add(Menu.NONE, AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        }
        menu.add(Menu.NONE, RESET_TERMINAL, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, KILL_PROCESS, Menu.NONE, resources.getString(R.string.action_kill_process, state.sessionPid))
            .setEnabled(state.sessionRunning);
        menu.add(Menu.NONE, TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true).setChecked(state.keepScreenOn);
        menu.add(Menu.NONE, TOGGLE_WAKE_LOCK, Menu.NONE, R.string.action_toggle_wake_lock)
            .setCheckable(true).setChecked(state.wakeLockHeld);
        menu.add(Menu.NONE, SETTINGS, Menu.NONE, R.string.action_open_settings);
    }

}
