package com.termux.app;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Window snapshots and selection requests for shell-owned tmux attachments. Main thread only. */
final class TmuxWindows {
    private static final long LEASE_MS = 6000;
    private final Handler handler;
    private final Runnable onChange;
    private final Map<String, Attachment> attachments = new HashMap<>();

    private static final class Attachment {
        final String token;
        JSONArray windows;
        String signature;
        String selection;
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
            if (entry != null) handler.removeCallbacks(entry.expire);
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
        String selected = entry.selection;
        entry.selection = null;
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
        for (int i = 0; i < entry.windows.length(); i++) {
            if (id.equals(entry.windows.optJSONObject(i).optString("id"))) {
                entry.selection = id;
                return true;
            }
        }
        return false;
    }

    void remove(String terminal) {
        Attachment entry = attachments.remove(terminal);
        if (entry == null) return;
        handler.removeCallbacks(entry.expire);
        onChange.run();
    }
}
