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

    public void removeRow(int row) { rows.remove(row); }
    public void moveRow(int row, int direction) { Collections.swap(rows, row, row + direction); }
    public void removeKey(int row, int key) { rows.get(row).remove(key); }
    public void moveKey(int row, int key, int direction) { Collections.swap(rows.get(row), key, key + direction); }

    public JSONObject key(int row, int key) throws JSONException {
        Object value = rows.get(row).get(key);
        return value instanceof JSONObject ? new JSONObject(value.toString()) : new JSONObject().put("key", value);
    }

    public void putKey(int row, int index, JSONObject value) throws JSONException {
        new ExtraKeysInfo(new JSONArray().put(new JSONArray().put(value)).toString(),
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
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
