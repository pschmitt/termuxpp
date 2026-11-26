package com.termux.app.settings;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExtraKeysLayoutEditingTest {
    @Test public void keysMoveAcrossRowsAndClampToTheTargetRow() throws Exception {
        ExtraKeysLayout layout = new ExtraKeysLayout("[['ESC','TAB','CTRL'],['UP']]");
        layout.moveKeyTo(0, 1, 1, 0);
        assertEquals("[[\"ESC\",\"CTRL\"],[\"TAB\",\"UP\"]]", layout.toString());
        layout.moveKeyTo(0, 0, 1, 99);
        assertEquals("[[\"CTRL\"],[\"TAB\",\"UP\",\"ESC\"]]", layout.toString());
        layout.moveKeyTo(1, 2, 1, 0);
        assertEquals("[[\"CTRL\"],[\"ESC\",\"TAB\",\"UP\"]]", layout.toString());
    }

    @Test public void insertingAndDuplicatingCopiesInsteadOfSharingKeys() throws Exception {
        ExtraKeysLayout layout = new ExtraKeysLayout("[[{key:'ESC',popup:'TAB'}]]");
        layout.duplicateKey(0, 0);
        layout.putKey(0, 1, new JSONObject().put("key", "F1"));
        assertEquals("ESC", layout.label(0, 0));
        layout.duplicateRow(0);
        layout.putKey(1, 0, new JSONObject().put("key", "F2"));
        assertEquals("ESC", layout.label(0, 0));
        layout.insertRow(0);
        assertEquals(0, layout.keyCount(0));
        layout.insertKey(0, 0, new JSONObject().put("key", ""));
        assertTrue(layout.isBlank(0, 0));
        assertFalse(layout.isBlank(1, 0));
        assertThrows(JSONException.class, () -> layout.insertKey(0, 0, new JSONObject().put("key", "a").put("command", "b")));
        assertEquals(1, layout.keyCount(0));
        assertEquals(new ExtraKeysLayout(layout.toString()).toString(), layout.toString());
    }
}
