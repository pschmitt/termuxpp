package com.termux.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.settings.SettingsSearchIndex;
import com.termux.terminal.TerminalSession;

import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;
import dev.pschmitt.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The command palette: one search over the actions, the sessions, their tmux windows and the settings. Enter or
 * a tap runs an entry. It is a picker dialog like the session and tmux switchers, placed the same way.
 */
public final class CommandPalette {

    /** The kinds of entries, in the order they rank on equal matches. */
    enum Kind { SESSION, WINDOW, ACTION, SETTING, LINE }

    /** The outcome of {@code tpp ui picker}: the chosen line, or {@code null} when the picker was closed. */
    public interface Picked {
        void done(@Nullable String line);
    }

    @VisibleForTesting
    static final class Entry {
        final Kind kind;
        final String title;
        final String detail;
        final Runnable run;
        private final String searchable;

        Entry(@NonNull Kind kind, @NonNull String title, @NonNull String detail, @NonNull Runnable run) {
            this(kind, title, detail, "", run);
        }

        /** {@code keywords} match too without being shown: an action's config name, a setting's key. */
        Entry(@NonNull Kind kind, @NonNull String title, @NonNull String detail, @NonNull String keywords,
              @NonNull Runnable run) {
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.run = run;
            this.searchable = normalize(title + " " + detail + " " + keywords);
        }
    }

    /** Most entries a search shows: the rest is a refinement away. */
    private static final int LIMIT = 60;
    /** Most lines a picker search shows. */
    private static final int PICKER_LIMIT = 300;

    private final TermuxActivity activity;
    @Nullable private AlertDialog dialog;
    @Nullable private List<SettingsSearchIndex.Result> settings;
    private final List<Entry> entries = new ArrayList<>();
    private List<Entry> shown = Collections.emptyList();
    private int selected;
    private EditText search;
    private LinearLayout list;
    private ScrollView scroll;
    /** Set while the dialog is a {@code tpp ui picker} instead of the palette. */
    @Nullable private Picked picked;
    /** Whether the open dialog's choice was made; one flag per dialog, see {@link #open}. */
    private boolean[] answered = {false};

    CommandPalette(@NonNull TermuxActivity activity) {
        this.activity = activity;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    /** Show the palette, or type {@code query} into the open one. */
    boolean show(@Nullable String query) {
        if (isShowing() && picked == null) {
            if (query != null) {
                search.setText(query);
                search.setSelection(query.length());
            }
            return true;
        }
        dismiss();
        picked = null;
        collectEntries();
        return open(activity.getString(R.string.ui_palette_title), query);
    }

    /**
     * Show {@code lines} to pick one from, like fzf: {@code result} gets the chosen line, or {@code null} when the
     * picker closes without one (also when another picker or the palette replaces it).
     */
    boolean showPicker(@Nullable String title, @NonNull List<String> lines, @Nullable String query,
                       @NonNull Picked result) {
        dismiss();
        entries.clear();
        for (String line : lines) entries.add(new Entry(Kind.LINE, line, "", () -> result.done(line)));
        picked = result;
        return open(title == null ? activity.getString(R.string.ui_picker_title) : title, query);
    }

    private boolean open(@NonNull String title, @Nullable String query) {

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        search = new EditText(activity);
        search.setHint(picked == null ? R.string.ui_palette_hint : R.string.ui_picker_hint);
        search.setSingleLine(true);
        // No autocorrect: keyboards like Gboard replace a query ("font") on Enter despite
        // NO_SUGGESTIONS; a visible password field is never corrected and stays readable.
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTypeface(Typeface.DEFAULT);
        // A password-like field invites password managers ("Unlock …" chips): it is a search, not a login.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLayout.setMarginStart(dp(20));
        searchLayout.setMarginEnd(dp(20));
        content.addView(search, searchLayout);

        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPaddingRelative(0, dp(4), 0, dp(8));
        scroll = new ScrollView(activity);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        // A fixed height, so the dialog does not jump while the results change.
        int height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.42f);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                refresh(text.toString());
            }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runSelected();
                return true;
            }
            return false;
        });
        // Hardware keyboards: arrows move the selection, Enter runs it.
        search.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN: select(selected + 1); return true;
                case KeyEvent.KEYCODE_DPAD_UP: select(selected - 1); return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: runSelected(); return true;
                default: return false;
            }
        });

        AlertDialog created = activity.pickerBuilder()
            .setTitle(title)
            .setView(content)
            .create();
        dialog = created;
        // Dismissal is reported later through a message: by then another dialog may have replaced this
        // one, so everything here belongs to this dialog alone.
        Picked owner = picked;
        boolean[] choice = {false};
        answered = choice;
        created.setOnDismissListener(closed -> {
            if (dialog == created) {
                dialog = null;
                picked = null;
            }
            if (owner != null && !choice[0]) owner.done(null);
        });
        if (created.getWindow() != null)
            created.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        created.show();
        activity.placePicker(created);
        if (query != null) {
            search.setText(query);
            search.setSelection(query.length());
        } else {
            refresh("");
        }
        search.requestFocus();
        return true;
    }

    /** The entries of this moment: sessions and tmux windows change while the palette is closed. */
    private void collectEntries() {
        entries.clear();
        TermuxService service = activity.getTermuxService();
        TerminalSession current = activity.getCurrentSession();
        if (service != null) {
            for (int index = 0; index < service.getTermuxSessionsSize(); index++) {
                TermuxSession termuxSession = service.getTermuxSession(index);
                TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
                if (session == null) continue;
                String name = sessionName(session);
                String detail = activity.getString(session == current
                    ? R.string.ui_palette_kind_current_session : R.string.ui_palette_kind_session);
                entries.add(new Entry(Kind.SESSION, (index + 1) + "  " + name, detail, () -> {
                    if (activity.getTermuxService() != null
                        && activity.getTermuxService().getTermuxSessionForTerminalSession(session) != null)
                        activity.getTermuxTerminalSessionClient().setCurrentSession(session);
                }));
                JSONArray windows = service.getTmuxWindows(session);
                for (int window = 0; window < windows.length(); window++) {
                    JSONObject object = windows.optJSONObject(window);
                    if (object == null) continue;
                    String id = object.optString("id");
                    String label = object.optString("label");
                    if (id.isEmpty() || label.isEmpty()) continue;
                    String detail1 = activity.getString(R.string.ui_palette_kind_window, name)
                        + (object.optBoolean("active") ? " · " + activity.getString(R.string.ui_palette_active) : "");
                    entries.add(new Entry(Kind.WINDOW, label, detail1, () -> activity.selectTmuxWindow(session, id)));
                }
            }
        }
        String action = activity.getString(R.string.ui_palette_kind_action);
        entries.add(new Entry(Kind.ACTION, activity.getString(R.string.action_open_settings), action,
            () -> activity.openTerminalUi("settings", null)));
        for (TerminalGestureAction gesture : TerminalGestureAction.values()) {
            switch (gesture) {
                case NONE: case RUN_COMMAND: case SEND_KEYS: case OPEN_PALETTE: continue;
                default: break;
            }
            entries.add(new Entry(Kind.ACTION, gesture.getLabel(), action, gesture.getValue(),
                () -> activity.getTermuxTerminalViewClient().performGestureAction(gesture)));
        }
    }

    /** The settings join the results once something is typed: there are hundreds of them. */
    private List<Entry> settingsEntries() {
        if (settings == null) settings = SettingsSearchIndex.build(activity);
        String kind = activity.getString(R.string.ui_palette_kind_setting);
        List<Entry> result = new ArrayList<>(settings.size());
        for (SettingsSearchIndex.Result setting : settings) {
            String detail = setting.breadcrumb.isEmpty() ? kind : kind + " · " + setting.breadcrumb;
            result.add(new Entry(Kind.SETTING, setting.title, detail, setting.keywords(), () -> activity.startActivity(
                new Intent(activity, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_OPEN_SETTING, setting.id()))));
        }
        return result;
    }

    private void refresh(String query) {
        List<Entry> candidates = new ArrayList<>(entries);
        if (picked == null && !query.trim().isEmpty()) candidates.addAll(settingsEntries());
        shown = filter(candidates, query, picked == null ? LIMIT : PICKER_LIMIT);
        list.removeAllViews();
        if (shown.isEmpty()) {
            TextView empty = text(activity.getString(R.string.ui_palette_empty), 14, false);
            empty.setPaddingRelative(dp(24), dp(16), dp(24), dp(16));
            list.addView(empty);
        }
        for (int index = 0; index < shown.size(); index++) list.addView(row(shown.get(index), index));
        select(0);
    }

    private View row(Entry entry, int index) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPaddingRelative(dp(24), dp(8), dp(24), dp(8));
        row.setMinimumHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            row.setForeground(activity.getDrawable(ripple.resourceId));
        TextView title = text(entry.title, entry.kind == Kind.LINE ? 13 : 15, true);
        // Picker lines are often columns (ls -l): keep them aligned, and wrap them, since what matters
        // (a file name) is often at the end.
        if (entry.kind == Kind.LINE) {
            title.setTypeface(Typeface.MONOSPACE);
            title.setSingleLine(false);
            title.setMaxLines(3);
        }
        row.addView(title);
        if (!entry.detail.isEmpty()) row.addView(text(entry.detail, 12, false));
        row.setOnClickListener(view -> run(entry));
        row.setOnHoverListener((view, event) -> {
            select(index);
            return false;
        });
        return row;
    }

    private TextView text(String value, int sp, boolean primary) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTextColor(MaterialColors.getColor(list, primary
            ? android.R.attr.textColorPrimary : android.R.attr.textColorSecondary));
        return view;
    }

    /** Highlight entry {@code index} (Enter runs it) and keep it in view. */
    private void select(int index) {
        if (shown.isEmpty()) return;
        selected = Math.max(0, Math.min(shown.size() - 1, index));
        int accent = MaterialColors.getColor(list, dev.pschmitt.termux.shared.R.attr.termuxUiPrimary);
        for (int child = 0; child < list.getChildCount(); child++) {
            View row = list.getChildAt(child);
            if (child == selected) {
                GradientDrawable fill = new GradientDrawable();
                fill.setCornerRadius(dp(16));
                fill.setColor(ColorUtils.setAlphaComponent(accent, 40));
                row.setBackground(fill);
            } else {
                row.setBackground(null);
            }
        }
        View row = list.getChildAt(selected);
        if (row != null) scroll.post(() -> {
            if (row.getTop() < scroll.getScrollY()) scroll.smoothScrollTo(0, row.getTop());
            else if (row.getBottom() > scroll.getScrollY() + scroll.getHeight())
                scroll.smoothScrollTo(0, row.getBottom() - scroll.getHeight());
        });
    }

    private void runSelected() {
        if (selected < shown.size()) run(shown.get(selected));
    }

    /** Close the palette first: an entry may open another picker or move the keyboard. */
    private void run(Entry entry) {
        // The choice reports itself: the dismissal must not report a cancel too.
        answered[0] = true;
        dismiss();
        activity.getWindow().getDecorView().post(entry.run);
    }

    private String sessionName(TerminalSession session) {
        if (!TextUtils.isEmpty(session.mSessionName)) return session.mSessionName;
        if (!TextUtils.isEmpty(session.getTitle())) return session.getTitle();
        return activity.getString(R.string.ui_palette_unnamed_session);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * The entries that contain every word of {@code query}, best first: a title that starts with the query, then a
     * word of the title that does, then the rest; equal matches keep the kind order and then their own order.
     */
    @VisibleForTesting
    static List<Entry> filter(@NonNull List<Entry> entries, @NonNull String query, int limit) {
        String normalized = normalize(query);
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split(" ");
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            boolean all = true;
            for (String term : terms) {
                if (!entry.searchable.contains(term)) {
                    all = false;
                    break;
                }
            }
            if (all) matches.add(entry);
        }
        if (!normalized.isEmpty()) {
            // A stable sort: equal entries keep their order.
            Collections.sort(matches, (first, second) -> {
                int byRank = Integer.compare(rank(first, normalized), rank(second, normalized));
                return byRank != 0 ? byRank : first.kind.compareTo(second.kind);
            });
        }
        return matches.size() > limit ? new ArrayList<>(matches.subList(0, limit)) : matches;
    }

    private static int rank(Entry entry, String query) {
        String title = normalize(entry.title);
        if (title.startsWith(query)) return 0;
        if (title.contains(" " + query)) return 1;
        if (title.contains(query)) return 2;
        return 3;
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
