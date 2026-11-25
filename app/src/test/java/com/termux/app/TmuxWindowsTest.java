package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class TmuxWindowsTest {
    private static final String TERMINAL = "terminal";
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private TmuxWindows windows;
    private int changes;

    @Before
    public void setUp() throws Exception {
        windows = new TmuxWindows(new Handler(Looper.getMainLooper()), () -> changes++);
        windows.update(TERMINAL, snapshot("@1"));
        changes = 0;
    }

    private static JSONArray snapshot(String active) throws Exception {
        JSONArray rows = new JSONArray();
        for (String id : new String[]{"@1", "@2"}) {
            rows.put(new JSONObject().put("id", id).put("label", id.substring(1) + "  shell")
                .put("active", id.equals(active)));
        }
        return new JSONArray().put("tmux-windows").put(TERMINAL).put(TOKEN).put(rows);
    }

    @Test
    public void choosingAWindowHighlightsItAtOnce() throws Exception {
        assertTrue(windows.select(TERMINAL, "@2"));
        assertEquals(1, changes);
        JSONArray rows = windows.get(TERMINAL);
        assertFalse(rows.getJSONObject(0).getBoolean("active"));
        assertTrue(rows.getJSONObject(1).getBoolean("active"));

        // tmux confirming the switch matches the highlight, so the sidebar is not redrawn again.
        windows.update(TERMINAL, snapshot("@2"));
        assertEquals(1, changes);
    }

    @Test
    public void aWaitingHelperGetsTheChoiceAsSoonAsItIsMade() throws Exception {
        AtomicReference<String> chosen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread helper = new Thread(() -> {
            try {
                chosen.set(windows.awaitSelection(TOKEN, 5000));
            } catch (InterruptedException ignored) {
                // The assertion below reports it.
            }
            done.countDown();
        });
        helper.start();
        Thread.sleep(50);
        long start = System.nanoTime();
        windows.select(TERMINAL, "@2");
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
        assertEquals("@2", chosen.get());

        // The choice is handed over once, not again with the next snapshot.
        assertEquals(JSONObject.NULL, windows.update(TERMINAL, snapshot("@2")));
    }

    @Test
    public void aWaitEndsEmptyWhenNothingIsChosen() throws Exception {
        assertNull(windows.awaitSelection(TOKEN, 20));
        assertNull(windows.awaitSelection("ffffffffffffffffffffffffffffffff", 20));
    }

    @Test
    public void helpersWithoutTheWaitStillGetTheChoiceWithTheirSnapshot() throws Exception {
        windows.select(TERMINAL, "@2");
        assertEquals("@2", windows.update(TERMINAL, snapshot("@1")));
        assertEquals(JSONObject.NULL, windows.update(TERMINAL, snapshot("@1")));
    }

    @Test
    public void unknownWindowsAreNotChosen() {
        assertFalse(windows.select(TERMINAL, "@9"));
        assertFalse(windows.select("other", "@1"));
        assertEquals(0, changes);
    }
}
