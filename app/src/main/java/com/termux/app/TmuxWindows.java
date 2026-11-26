package com.termux.app;

import android.os.Handler;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The tmux windows shown in a terminal, from two sources: reports the TermuxPP tmux plugin writes into the
 * terminal's output (OSC 7769), and snapshots of the shell-owned SSH helper over the session socket.
 * Main thread only, except {@link #awaitSelection}, which a socket thread uses to hand a chosen window to the
 * helper the moment it is chosen instead of with the next snapshot.
 */
final class TmuxWindows {
    private static final long LEASE_MS = 6000;
    /** The prefix of a plugin report: the protocol name and version, then the base64 window list. */
    static final String REPORT_PREFIX = "tmux-windows;1;";
    /**
     * The plugin's select key ({@code user-keys[77]}, key {@code User77}): typed into the terminal, followed
     * by the digits of a window ID and Return, it selects that window.
     */
    static final String SELECT_KEYS = "\033[5;30077~";
    private final Handler handler;
    private final Runnable onChange;
    private final Map<String, Attachment> attachments = new HashMap<>();
    /** The window chosen for each attachment token and not yet handed to its helper. */
    private final Map<String, String> selections = new ConcurrentHashMap<>();
    private final Object signal = new Object();

    private static final class Attachment {
        /** The helper's token, or null for windows the plugin reports through the terminal. */
        @Nullable final String token;
        JSONArray windows;
        String signature;
        Runnable expire;

        Attachment(@Nullable String token) {
            this.token = token;
        }
    }

    TmuxWindows(Handler handler, Runnable onChange) {
        this.handler = handler;
        this.onChange = onChange;
    }

    Object update(String terminal, JSONArray args) throws JSONException {
        String token = args.getString(2);
        if (!token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid attachment token");
        Attachment entry = attachments.get(terminal);
        if (args.length() == 3) {
            if (entry != null && entry.token.equals(token)) remove(terminal);
            return JSONObject.NULL;
        }
        JSONArray snapshot = args.getJSONArray(3);
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject row = snapshot.getJSONObject(i);
            if (!row.getString("id").matches("@[0-9]+")) throw new IllegalArgumentException("Invalid tmux window ID");
            row.getString("label");
            row.getBoolean("active");
        }
        if (entry == null || !token.equals(entry.token)) {
            if (entry != null) forget(entry);
            entry = new Attachment(token);
            entry.expire = () -> remove(terminal);
            attachments.put(terminal, entry);
        }
        handler.removeCallbacks(entry.expire);
        handler.postDelayed(entry.expire, LEASE_MS);
        String signature = snapshot.toString();
        if (!signature.equals(entry.signature)) {
            entry.signature = signature;
            entry.windows = snapshot;
            onChange.run();
        }
        // Helpers without the wait request still pick the choice up here, with their next snapshot.
        String selected = selections.remove(token);
        for (int i = 0; selected != null && i < snapshot.length(); i++) {
            if (selected.equals(snapshot.getJSONObject(i).getString("id"))) return selected;
        }
        return JSONObject.NULL;
    }

    /**
     * Take a plugin report for a terminal: show its windows, or clear them for an empty or null report.
     *
     * @return False when the report is not one this version understands.
     */
    boolean report(String terminal, @Nullable String report) {
        JSONArray rows = report == null ? new JSONArray() : parseReport(report);
        if (rows == null) return false;
        Attachment entry = attachments.get(terminal);
        if (rows.length() == 0) {
            if (entry != null && entry.token == null) remove(terminal);
            return true;
        }
        if (entry == null || entry.token != null) {
            if (entry != null) forget(entry);
            entry = new Attachment(null);
            attachments.put(terminal, entry);
        }
        String signature = rows.toString();
        if (!signature.equals(entry.signature)) {
            entry.signature = signature;
            entry.windows = rows;
            onChange.run();
        }
        return true;
    }

    /** Whether a terminal's windows come from the plugin, so a choice is typed into the terminal. */
    boolean isReported(String terminal) {
        Attachment entry = attachments.get(terminal);
        return entry != null && entry.token == null;
    }

    /**
     * Parse a plugin report: {@code tmux-windows;1;} and the base64 of one line per window, with the ID,
     * index, active flag (0 or 1), the active pane's command and the name, separated by tabs.
     *
     * @return The rows, or null for a report of another protocol or version, or one that does not parse.
     */
    @Nullable
    static JSONArray parseReport(String report) {
        if (!report.startsWith(REPORT_PREFIX)) return null;
        String text;
        try {
            text = new String(Base64.decode(report.substring(REPORT_PREFIX.length()), Base64.DEFAULT),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        JSONArray rows = new JSONArray();
        try {
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 5);
                if (fields.length != 5 || !fields[0].matches("@[0-9]+") || !fields[1].matches("[0-9]+")
                    || !fields[2].matches("[01]")) return null;
                String name = fields[4].length() > 100 ? fields[4].substring(0, 100) : fields[4];
                JSONObject row = new JSONObject()
                    .put("id", fields[0])
                    .put("label", fields[1] + "  " + name)
                    .put("active", fields[2].equals("1"));
                if (!fields[3].isEmpty())
                    row.put("program", fields[3].length() > 64 ? fields[3].substring(0, 64) : fields[3]);
                rows.put(row);
            }
        } catch (JSONException e) {
            return null;
        }
        return rows;
    }

    JSONArray get(String terminal) {
        Attachment entry = attachments.get(terminal);
        return entry == null ? new JSONArray() : entry.windows;
    }

    enum Selection { UNAVAILABLE, ALREADY_ACTIVE, SWITCH_REQUESTED }

    Selection select(String terminal, String id) {
        Attachment entry = attachments.get(terminal);
        if (entry == null) return Selection.UNAVAILABLE;
        JSONArray windows = new JSONArray();
        boolean found = false;
        try {
            for (int i = 0; i < entry.windows.length(); i++) {
                // A copy keeps the key order, so the helper's next snapshot of the switched window
                // matches this one and does not redraw the sidebar again.
                JSONObject previous = entry.windows.getJSONObject(i);
                boolean chosen = id.equals(previous.optString("id"));
                if (chosen && previous.optBoolean("active")) return Selection.ALREADY_ACTIVE;
                JSONObject row = new JSONObject(previous.toString());
                found |= chosen;
                windows.put(row.put("active", chosen));
            }
        } catch (JSONException e) {
            return Selection.UNAVAILABLE;
        }
        if (!found) return Selection.UNAVAILABLE;

        if (entry.token != null) {
            synchronized (signal) {
                selections.put(entry.token, id);
                signal.notifyAll();
            }
        }
        // Show the choice at once; the helper's next snapshot confirms or corrects it.
        entry.windows = windows;
        entry.signature = windows.toString();
        onChange.run();
        return Selection.SWITCH_REQUESTED;
    }

    /**
     * Wait until a window is chosen for the attachment with this token, and take the choice. Any thread.
     *
     * @return The window ID, or null when nothing was chosen in time.
     */
    String awaitSelection(String token, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (signal) {
            while (true) {
                String id = selections.remove(token);
                if (id != null) return id;
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) return null;
                signal.wait(left);
            }
        }
    }

    void remove(String terminal) {
        Attachment entry = attachments.remove(terminal);
        if (entry == null) return;
        forget(entry);
        onChange.run();
    }

    private void forget(Attachment entry) {
        if (entry.expire != null) handler.removeCallbacks(entry.expire);
        if (entry.token != null) selections.remove(entry.token);
    }
}
