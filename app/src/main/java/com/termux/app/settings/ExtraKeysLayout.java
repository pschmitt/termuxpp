package com.termux.app.settings;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Editable JSON tree; unknown key attributes survive edits to other fields. */
public final class ExtraKeysLayout {
    private final List<List<Object>> rows = new ArrayList<>();

    public ExtraKeysLayout(String value) throws JSONException {
        new ExtraKeysInfo(value, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        JSONArray array = new JSONArray(value);
        for (int i = 0; i < array.length(); i++) {
            List<Object> row = new ArrayList<>();
            JSONArray source = array.getJSONArray(i);
            for (int j = 0; j < source.length(); j++) row.add(source.get(j));
            rows.add(row);
        }
    }

    public static ExtraKeysLayout defaults() throws JSONException {
        return new ExtraKeysLayout(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
    }

    public int rowCount() { return rows.size(); }
    public int keyCount(int row) { return rows.get(row).size(); }
    public void addRow() { rows.add(new ArrayList<>()); }
    public void resizeRow(int row, int columns) {
        List<Object> keys = rows.get(row);
        if (columns < keys.size()) keys.subList(columns, keys.size()).clear();
        while (keys.size() < columns) keys.add("");
    }

    public void insertRow(int at) { rows.add(at, new ArrayList<>()); }

    public void duplicateRow(int row) throws JSONException {
        List<Object> copy = new ArrayList<>();
        for (Object key : rows.get(row)) copy.add(key instanceof JSONObject ? new JSONObject(key.toString()) : key);
        rows.add(row + 1, copy);
    }

    public void removeRow(int row) { rows.remove(row); }
    public void moveRow(int row, int direction) { Collections.swap(rows, row, row + direction); }
    public void removeKey(int row, int key) { rows.get(row).remove(key); }
    public void moveKey(int row, int key, int direction) { Collections.swap(rows.get(row), key, key + direction); }

    /** Inserts a validated key at {@code index}, shifting later keys to the right. */
    public void insertKey(int row, int index, JSONObject value) throws JSONException {
        validate(value);
        rows.get(row).add(index, value);
    }

    public void duplicateKey(int row, int key) throws JSONException {
        Object value = rows.get(row).get(key);
        rows.get(row).add(key + 1, value instanceof JSONObject ? new JSONObject(value.toString()) : value);
    }

    /**
     * Moves a key to another position, possibly in another row. {@code toKey} is the index the key
     * occupies afterwards and is clamped to the target row.
     */
    public void moveKeyTo(int fromRow, int fromKey, int toRow, int toKey) {
        Object value = rows.get(fromRow).remove(fromKey);
        List<Object> target = rows.get(toRow);
        target.add(Math.max(0, Math.min(toKey, target.size())), value);
    }

    /** Whether the slot is an empty spacer that renders as a gap. */
    public boolean isBlank(int row, int key) throws JSONException {
        JSONObject object = key(row, key);
        return object.length() == 1 && "".equals(object.optString("key", null));
    }

    public JSONObject key(int row, int key) throws JSONException {
        Object value = rows.get(row).get(key);
        return value instanceof JSONObject ? new JSONObject(value.toString()) : new JSONObject().put("key", value);
    }

    /** Throws if the key would not load in the terminal toolbar. */
    public static void validate(JSONObject value) throws JSONException {
        new ExtraKeysInfo(new JSONArray().put(new JSONArray().put(value)).toString(),
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
    }

    public void putKey(int row, int index, JSONObject value) throws JSONException {
        validate(value);
        if (index == rows.get(row).size()) rows.get(row).add(value);
        else rows.get(row).set(index, value);
    }

    public String label(int row, int key) throws JSONException {
        JSONObject object = key(row, key);
        return object.optString("display", object.optString("key", object.optString("macro")));
    }

    @Override public String toString() {
        JSONArray result = new JSONArray();
        for (List<Object> row : rows) {
            JSONArray array = new JSONArray();
            for (Object key : row) array.put(key);
            result.put(array);
        }
        return result.toString();
    }
}
