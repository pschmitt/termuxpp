package com.termux.app;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Window snapshots and selection requests for shell-owned tmux attachments. Main thread only, except
 * {@link #awaitSelection}, which a socket thread uses to hand a chosen window to the helper the moment
 * it is chosen instead of with the next snapshot.
 */
final class TmuxWindows {
    private static final long LEASE_MS = 6000;
    private final Handler handler;
    private final Runnable onChange;
    private final Map<String, Attachment> attachments = new HashMap<>();
    /** The window chosen for each attachment token and not yet handed to its helper. */
    private final Map<String, String> selections = new ConcurrentHashMap<>();
    private final Object signal = new Object();

    private static final class Attachment {
        final String token;
        JSONArray windows;
        String signature;
        Runnable expire;

        Attachment(String token) {
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
        if (entry == null || !entry.token.equals(token)) {
            if (entry != null) {
                handler.removeCallbacks(entry.expire);
                selections.remove(entry.token);
            }
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

    JSONArray get(String terminal) {
        Attachment entry = attachments.get(terminal);
        return entry == null ? new JSONArray() : entry.windows;
    }

    boolean select(String terminal, String id) {
        Attachment entry = attachments.get(terminal);
        if (entry == null) return false;
        JSONArray windows = new JSONArray();
        boolean found = false;
        try {
            for (int i = 0; i < entry.windows.length(); i++) {
                // A copy keeps the key order, so the helper's next snapshot of the switched window
                // matches this one and does not redraw the sidebar again.
                JSONObject row = new JSONObject(entry.windows.getJSONObject(i).toString());
                boolean chosen = id.equals(row.optString("id"));
                found |= chosen;
                windows.put(row.put("active", chosen));
            }
        } catch (JSONException e) {
            return false;
        }
        if (!found) return false;

        synchronized (signal) {
            selections.put(entry.token, id);
            signal.notifyAll();
        }
        // Show the choice at once; the helper's next snapshot confirms or corrects it.
        entry.windows = windows;
        entry.signature = windows.toString();
        onChange.run();
        return true;
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
        handler.removeCallbacks(entry.expire);
        selections.remove(entry.token);
        onChange.run();
    }
}
