package com.termux.app.terminal;

import android.content.res.Resources;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The entries of the dialog that a long press on a row of the sidebar session list opens: rename, reorder,
 * and end or remove the session. Reordering can also be done by holding the number of a row and dragging it
 * (see {@link SessionsListView}); the dialog is the way that works without a drag.
 */
public final class SessionActionsMenu {

    public static final int RENAME = 0;
    public static final int MOVE_UP = 1;
    public static final int MOVE_DOWN = 2;
    public static final int MOVE_TOP = 3;
    public static final int MOVE_BOTTOM = 4;
    /** Kill a running session, after asking. */
    public static final int KILL = 5;
    /** Holding the kill row: kill without asking. */
    public static final int KILL_NOW = 6;
    /** Remove a session whose process has exited. */
    public static final int REMOVE = 7;
    public static final int TMUX_PICKER = 8;
    public static final int TOGGLE_TMUX_WINDOWS = 9;

    /** What decides which entries are shown. */
    public static final class State {
        @Nullable public final String sessionName;
        @Nullable public final String terminalTitle;
        /** The 1-based position of the session. */
        public final int number;
        public final int count;
        public final boolean running;
        public final boolean hasTmuxWindows;
        public final int tmuxVisibilityAction;

        public State(@Nullable String sessionName, @Nullable String terminalTitle, int number, int count,
                     boolean running) {
            this(sessionName, terminalTitle, number, count, running, false, 0);
        }

        public State(@Nullable String sessionName, @Nullable String terminalTitle, int number, int count,
                     boolean running, boolean hasTmuxWindows, int tmuxVisibilityAction) {
            this.sessionName = sessionName;
            this.terminalTitle = terminalTitle;
            this.number = number;
            this.count = count;
            this.running = running;
            this.hasTmuxWindows = hasTmuxWindows;
            this.tmuxVisibilityAction = tmuxVisibilityAction;
        }

        public boolean canMoveUp() {
            return number > 1 && count > 1;
        }

        public boolean canMoveDown() {
            return number >= 1 && number < count;
        }
    }

    private SessionActionsMenu() {}

    /** The header title: the name the user gave the session, else its terminal title, else its number. */
    @NonNull
    static String headerTitle(@NonNull Resources resources, @NonNull State state) {
        if (!TextUtils.isEmpty(state.sessionName)) return state.sessionName;
        if (!TextUtils.isEmpty(state.terminalTitle)) return state.terminalTitle;
        return resources.getString(R.string.ui_session_unnamed, Math.max(state.number, 1));
    }

    /** The header detail line, such as {@code 2 of 3} or {@code not running · 2 of 3}. */
    @NonNull
    static String headerDetail(@NonNull Resources resources, @NonNull State state) {
        List<String> parts = new ArrayList<>();
        if (!state.running) parts.add(resources.getString(R.string.ui_menu_not_running));
        if (state.count > 1 && state.number > 0)
            parts.add(resources.getString(R.string.ui_menu_session_position, state.number, state.count));
        return TextUtils.join(resources.getString(R.string.ui_menu_session_detail_separator), parts);
    }

    /**
     * Put Rename in the session header, single steps in the strip, jumps to either end behind More,
     * and the destructive action last.
     */
    public static TermuxContextMenuSheet populateSheet(@NonNull TermuxContextMenuSheet sheet,
                                                       @NonNull Resources resources, @NonNull State state) {
        sheet.setSessionHeader(headerTitle(resources, state), headerDetail(resources, state), RENAME,
            resources.getString(R.string.ui_session_action_rename), R.drawable.ui_ic_menu_edit);
        if (state.count > 1) {
            sheet.addShortcut(MOVE_UP, resources.getString(R.string.ui_session_action_move_up), null,
                R.drawable.ui_ic_menu_arrow_up, state.canMoveUp());
            sheet.addShortcut(MOVE_DOWN, resources.getString(R.string.ui_session_action_move_down), null,
                R.drawable.ui_ic_menu_arrow_down, state.canMoveDown());
            if (state.canMoveUp()) {
                sheet.addSecondaryRow(MOVE_TOP, resources.getString(R.string.ui_session_action_move_top), null,
                    R.drawable.ui_ic_menu_arrow_top);
            }
            if (state.canMoveDown()) {
                sheet.addSecondaryRow(MOVE_BOTTOM, resources.getString(R.string.ui_session_action_move_bottom), null,
                    R.drawable.ui_ic_menu_arrow_bottom);
            }
        }

        if (state.hasTmuxWindows) {
            sheet.addRow(TMUX_PICKER, resources.getString(R.string.ui_tmux_sidebar_picker), null,
                R.drawable.ui_ic_terminal);
            if (state.tmuxVisibilityAction != 0)
                sheet.addRow(TOGGLE_TMUX_WINDOWS, resources.getString(state.tmuxVisibilityAction), null,
                    R.drawable.ui_ic_menu_chevron_down);
        }

        if (state.running) {
            sheet.addDangerRow(KILL, resources.getString(R.string.ui_menu_kill,
                    resources.getString(R.string.ui_menu_kill_session_target)),
                resources.getString(R.string.ui_menu_kill_detail), R.drawable.ui_ic_menu_power, true,
                KILL_NOW, resources.getString(R.string.ui_menu_kill_now), false);
        } else {
            sheet.addDangerRow(REMOVE, resources.getString(R.string.ui_session_action_remove),
                resources.getString(R.string.ui_session_action_remove_detail), R.drawable.ui_ic_delete, true,
                -1, null, false);
        }
        return sheet;
    }

    /** The move that an action id stands for, or null if it is not a move. */
    @Nullable
    public static SessionReorder.Move moveFor(int actionId) {
        switch (actionId) {
            case MOVE_UP: return SessionReorder.Move.UP;
            case MOVE_DOWN: return SessionReorder.Move.DOWN;
            case MOVE_TOP: return SessionReorder.Move.TOP;
            case MOVE_BOTTOM: return SessionReorder.Move.BOTTOM;
            default: return null;
        }
    }

}
