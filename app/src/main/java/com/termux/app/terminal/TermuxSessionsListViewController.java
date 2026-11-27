package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession>
    implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener, SessionsListView.ReorderListener {

    final TermuxActivity mActivity;
    private final SessionsListView list;
    private final boolean rightSidebar;
    @Nullable private android.app.Dialog mActionsDialog;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList,
                                            SessionsListView list, boolean rightSidebar) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
        this.list = list;
        this.rightSidebar = rightSidebar;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionIndexView = sessionRowView.findViewById(R.id.session_index);
        bindNumberGestures(sessionRowView, sessionIndexView);
        // Rows are reused, so one that was lifted for a drag must not carry that into another session. The list
        // itself lifts the row that is being dragged.
        SessionsListView.resetRowStyle(sessionRowView);

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        LinearLayout tmux = sessionRowView.findViewById(R.id.session_tmux_windows);
        mActivity.bindTmuxWindows(tmux, sessionAtRow, rightSidebar);
        bindSessionSummary(mActivity, sessionRowView, sessionAtRow, position + 1, mActivity.isTmuxSidebarRounded());
        return sessionRowView;
    }

    /**
     * Fill the number, title and subtitle of an {@code item_terminal_sessions_list} row. The sidebar and the
     * session picker share this, so both show a session the same way.
     */
    @SuppressLint("SetTextI18n")
    public static void bindSessionSummary(@NonNull Context context, @NonNull View sessionRowView,
                                          @Nullable TerminalSession sessionAtRow, int number, boolean rounded) {
        TextView sessionIndexView = sessionRowView.findViewById(R.id.session_index);
        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        TextView sessionSubtitleView = sessionRowView.findViewById(R.id.session_subtitle);
        sessionIndexView.setText(String.valueOf(number));

        View header = sessionRowView.findViewById(R.id.session_header);
        applyRowStyle(context, header, sessionIndexView, sessionSubtitleView, rounded);
        int rowText = rounded ? R.color.ui_session_row_text : R.color.ui_on_surface;
        if (sessionAtRow == null) {
            sessionTitleView.setText(R.string.ui_session_null);
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            sessionTitleView.setTextColor(ContextCompat.getColorStateList(context, rowText));
            sessionSubtitleView.setVisibility(View.GONE);
            header.setContentDescription(number + ", " + context.getString(R.string.ui_session_null));
            return;
        }

        String name = sessionAtRow.mSessionName;
        String terminalTitle = sessionAtRow.getTitle();
        boolean hasName = !TextUtils.isEmpty(name);
        boolean hasTitle = !TextUtils.isEmpty(terminalTitle);
        boolean sessionRunning = sessionAtRow.isRunning();

        // The name is what the user chose, so it leads; the terminal title (usually the running
        // program or directory) follows beneath it. Unnamed sessions lead with their title.
        String title;
        String subtitle = "";
        if (hasName) {
            title = name;
            if (hasTitle) subtitle = terminalTitle;
        } else if (hasTitle) {
            title = terminalTitle;
        } else {
            title = context.getString(R.string.ui_session_unnamed, number);
        }

        if (!sessionRunning) {
            String exited = context.getString(R.string.ui_session_exited, sessionAtRow.getExitStatus());
            subtitle = subtitle.isEmpty() ? exited : subtitle + " - " + exited;
        }

        sessionTitleView.setText(title);
        sessionSubtitleView.setText(subtitle);
        sessionSubtitleView.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        boolean failed = !sessionRunning && sessionAtRow.getExitStatus() != 0;
        sessionTitleView.setTextColor(ContextCompat.getColorStateList(context,
            failed ? rounded ? R.color.ui_session_failed_text : R.color.ui_danger : rowText));

        header.setContentDescription(subtitle.isEmpty() ? number + ", " + title : number + ", " + title + ", " + subtitle);
    }

    /**
     * Mark the current session like the active tmux window: an accent pill in the rounded sidebar style, a
     * tinted outline with an accent number badge in the subtle style.
     */
    private static void applyRowStyle(@NonNull Context context, @NonNull View header, @NonNull TextView badge,
                                      @NonNull TextView subtitle, boolean rounded) {
        // A background with insets sets its insets as the view's padding, so keep the padding of the layout.
        int start = header.getPaddingStart(), top = header.getPaddingTop();
        int end = header.getPaddingEnd(), bottom = header.getPaddingBottom();
        header.setBackgroundResource(rounded ? R.drawable.ui_session_bg : R.drawable.ui_session_bg_subtle);
        header.setPaddingRelative(start, top, end, bottom);
        badge.setBackgroundResource(rounded ? R.drawable.ui_session_badge_bg : R.drawable.ui_session_badge_bg_subtle);
        badge.setTextColor(ContextCompat.getColorStateList(context,
            rounded ? R.color.ui_session_badge_text : R.color.ui_session_badge_text_subtle));
        subtitle.setTextColor(ContextCompat.getColorStateList(context,
            rounded ? R.color.ui_session_row_text : R.color.ui_on_surface));
    }

    /**
     * Holding the number of a row picks the row up to be dragged to another position. A tap on the number
     * still chooses the session, which it would not do on its own once the number is long-clickable.
     */
    private void bindNumberGestures(@NonNull View row, @NonNull View numberView) {
        numberView.setOnClickListener(v -> {
            int position = list.getPositionForView(row);
            if (position >= 0) list.performItemClick(row, position, getItemId(position));
        });
        numberView.setOnLongClickListener(v -> {
            int position = list.getPositionForView(row);
            // With a single session there is nothing to reorder: the hold opens the actions like the rest of the row.
            if (position >= 0 && list.beginDrag(position)) return true;
            if (position >= 0) showActions(position);
            return position >= 0;
        });
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        showActions(position);
        return true;
    }

    // Reordering

    @Override
    public boolean onMove(int from, int to) {
        return moveSession(from, to);
    }

    @Override
    public void onDragChanged(int position, boolean dragging) {
        notifyDataSetChanged();
    }

    /**
     * Move a session to another position, as the drag and the actions dialog do. The list is the service's
     * session list, so the next and previous session actions follow the new order too.
     */
    public boolean moveSession(int from, int to) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null || !service.moveTermuxSession(from, to)) return false;
        mActivity.termuxSessionListNotifyUpdated();
        // The list keeps its chosen row by position, not by session, so point it at the current session again.
        TerminalSession current = mActivity.getCurrentSession();
        int currentIndex = current == null ? -1 : service.getIndexOfSession(current);
        if (currentIndex >= 0) mActivity.checkSidebarSession(currentIndex, false);
        return true;
    }

    // Actions dialog

    /** Show the dialog of what can be done with the session at a position. */
    public void showActions(int position) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        if (mActionsDialog != null && mActionsDialog.isShowing()) return;
        TermuxSession termuxSession = position >= 0 && position < getCount() ? getItem(position) : null;
        TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
        if (session == null) return;

        TermuxService service = mActivity.getTermuxService();
        boolean hasTmuxWindows = service != null && service.getTmuxWindows(session).length() > 0;
        SessionActionsMenu.State state = new SessionActionsMenu.State(session.mSessionName, session.getTitle(),
            position + 1, getCount(), session.isRunning(), hasTmuxWindows,
            mActivity.tmuxWindowVisibilityAction(session, rightSidebar));
        try {
            mActionsDialog = SessionActionsMenu.populateSheet(new TermuxContextMenuSheet(mActivity),
                mActivity.getResources(), state).show(chosenId -> {
                    mActionsDialog = null;
                    if (chosenId >= 0) handleAction(chosenId, session);
                });
        } catch (RuntimeException e) {
            // The sheet is the only way to reach these actions from a tap, so fall back to renaming.
            mActivity.getTermuxTerminalSessionClient().renameSession(session);
        }
    }

    /** Close the dialog, for when the activity goes away. */
    public void dismissDialogs() {
        if (mActionsDialog != null) mActionsDialog.dismiss();
        mActionsDialog = null;
    }

    private void handleAction(int actionId, @NonNull TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        // The session may have moved or gone while the dialog was open, so look its position up again.
        int index = service.getIndexOfSession(session);
        if (index < 0) return;

        SessionReorder.Move move = SessionActionsMenu.moveFor(actionId);
        if (move != null) {
            int target = SessionReorder.targetFor(move, index, service.getTermuxSessionsSize());
            if (moveSession(index, target)) list.smoothScrollToPosition(target);
            return;
        }

        switch (actionId) {
            case SessionActionsMenu.TMUX_PICKER:
                mActivity.showTmuxSwitcher(session);
                break;
            case SessionActionsMenu.TOGGLE_TMUX_WINDOWS:
                mActivity.toggleTmuxWindows(session, rightSidebar);
                break;
            case SessionActionsMenu.RENAME:
                mActivity.getTermuxTerminalSessionClient().renameSession(session);
                break;
            case SessionActionsMenu.KILL:
                confirmKill(session);
                break;
            case SessionActionsMenu.KILL_NOW:
                session.finishIfRunning();
                break;
            case SessionActionsMenu.REMOVE:
                removeExitedSession(service, session);
                break;
            default:
                break;
        }
    }

    private void confirmKill(@NonNull TerminalSession session) {
        DialogUtils.builder(mActivity, R.drawable.ui_ic_delete, true)
            .setTitle(R.string.title_confirm_kill_process)
            .setMessage(R.string.msg_confirm_kill_process)
            .setPositiveButton(R.string.action_kill_session, (dialog, id) -> {
                dialog.dismiss();
                session.finishIfRunning();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Take a session whose process exited out of the list, like pressing Return in it does. */
    private void removeExitedSession(@NonNull TermuxService service, @NonNull TerminalSession session) {
        if (session.isRunning()) return;
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        if (session == mActivity.getCurrentSession()) {
            client.removeFinishedSession(session);
        } else {
            service.removeTermuxSession(session);
            notifyDataSetChanged();
        }
    }

}
