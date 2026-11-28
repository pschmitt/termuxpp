package com.termux.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * A {@code tpp ui} request from the session socket: {@code ["ui", ACTION]}, {@code ["ui", "settings", QUERY]} (the
 * original forms, a plain show) or {@code ["ui", ACTION, {"op": ..., "query": ..., "side": ...}]}.
 */
final class TerminalUiRequest {

    static final List<String> ACTIONS = Arrays.asList(
        "clear", "more", "palette", "session-switcher", "settings", "sidebar", "tmux-switcher");
    private static final List<String> OPERATIONS = Arrays.asList("show", "hide", "toggle");

    @NonNull final String action;
    @NonNull final String operation;
    @Nullable final String query;
    final boolean right;

    private TerminalUiRequest(@NonNull String action, @NonNull String operation, @Nullable String query,
                              boolean right) {
        this.action = action;
        this.operation = operation;
        this.query = query;
        this.right = right;
    }

    @NonNull
    static TerminalUiRequest parse(@NonNull JSONArray args) throws JSONException {
        if (args.length() < 2 || args.length() > 3) throw invalid();
        String action = args.getString(1);
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unknown terminal UI action: " + action);
        if (args.length() == 2) return new TerminalUiRequest(action, "show", null, false);

        Object extra = args.get(2);
        if (extra instanceof String) {
            if (!"settings".equals(action)) throw invalid();
            return new TerminalUiRequest(action, "show", (String) extra, false);
        }
        if (!(extra instanceof JSONObject) || "clear".equals(action)) throw invalid();
        JSONObject options = (JSONObject) extra;
        for (java.util.Iterator<String> keys = options.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!key.equals("op") && !key.equals("query") && !key.equals("side")) throw invalid();
        }
        String operation = options.optString("op", "show");
        if (!OPERATIONS.contains(operation)) throw invalid();
        String query = options.has("query") ? options.getString("query") : null;
        if (query != null && (!("settings".equals(action) || "palette".equals(action)) || "hide".equals(operation)))
            throw invalid();
        String side = options.optString("side", "left");
        if (!side.equals("left") && !side.equals("right")) throw invalid();
        boolean right = side.equals("right");
        if (right && !"sidebar".equals(action)) throw invalid();
        return new TerminalUiRequest(action, operation, query, right);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid terminal UI command arguments");
    }
}
