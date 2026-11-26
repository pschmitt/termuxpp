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
public class ExtraKeysLayoutTest {
    @Test public void defaultsAndRowOperationsRoundTrip() throws Exception {
        ExtraKeysLayout layout = ExtraKeysLayout.defaults();
        assertEquals(2, layout.rowCount());
        layout.addRow();
        layout.putKey(2, 0, new JSONObject().put("key", "F1"));
        layout.moveRow(2, -1);
        assertEquals("F1", layout.label(1, 0));
        layout.removeRow(0);
        ExtraKeysLayout reread = new ExtraKeysLayout(layout.toString());
        assertEquals(2, reread.rowCount());
        assertEquals("F1", reread.label(0, 0));
    }

    @Test public void richKeysAndUnknownFieldsSurviveEditing() throws Exception {
        ExtraKeysLayout layout = new ExtraKeysLayout("[[{macro:'CTRL f d',display:'Exit',custom:42,popup:{key:'END',display:'End',custom:7}},'ESC']]");
        JSONObject key = layout.key(0, 0);
        key.put("display", "Detach");
        layout.putKey(0, 0, key);
        layout.moveKey(0, 0, 1);
        JSONObject reread = new ExtraKeysLayout(layout.toString()).key(0, 1);
        assertEquals("CTRL f d", reread.getString("macro"));
        assertEquals(42, reread.getInt("custom"));
        assertEquals(7, reread.getJSONObject("popup").getInt("custom"));
        assertEquals("Detach", layout.label(0, 1));
        layout.removeKey(0, 0);
        assertEquals(1, layout.keyCount(0));
    }

    @Test public void editsAreCopiesUntilAppliedAndInvalidInputIsRejected() throws Exception {
        ExtraKeysLayout layout = new ExtraKeysLayout("[['ESC']]");
        layout.key(0, 0).put("key", "TAB");
        assertEquals("ESC", layout.label(0, 0));
        assertThrows(JSONException.class, () -> layout.putKey(0, 0, new JSONObject().put("key", "ESC").put("macro", "CTRL c")));
        assertEquals("ESC", layout.label(0, 0));
        assertThrows(JSONException.class, () -> new ExtraKeysLayout("not json"));
    }

    @Test public void longPressKeyRoundTripsWithAnIndependentSwipeKey() throws Exception {
        ExtraKeysLayout layout = new ExtraKeysLayout("[[{key:'CTRL',hold:'SHIFT',popup:'ALT'}]]");
        JSONObject key = new ExtraKeysLayout(layout.toString()).key(0, 0);
        assertEquals("CTRL", key.getString("key"));
        assertEquals("SHIFT", key.getString("hold"));
        assertEquals("ALT", key.getString("popup"));
        assertThrows(JSONException.class, () -> layout.putKey(0, 0, key.put("hold", new JSONObject())));
    }
}
