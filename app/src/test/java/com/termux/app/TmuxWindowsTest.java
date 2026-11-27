package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
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
    public void choosingAWindowWaitsForTmuxToConfirm() throws Exception {
        AtomicReference<Boolean> confirmed = new AtomicReference<>();
        assertEquals(TmuxWindows.Selection.SWITCH_REQUESTED, windows.select(TERMINAL, "@2", confirmed::set));
        assertTrue(windows.isSwitchPending(TERMINAL, "@2"));
        assertTrue(windows.get(TERMINAL).getJSONObject(0).getBoolean("active"));

        assertEquals("@2", windows.update(TERMINAL, snapshot("@1")));
        assertNull(confirmed.get());
        assertTrue(windows.isSwitchPending(TERMINAL, "@2"));

        windows.update(TERMINAL, snapshot("@2"));
        assertEquals(Boolean.TRUE, confirmed.get());
        assertFalse(windows.isSwitchPending(TERMINAL, "@2"));
        assertTrue(windows.get(TERMINAL).getJSONObject(1).getBoolean("active"));
        assertEquals(1, changes);
    }

    @Test
    public void aTimedOutChoiceCanBeRetried() throws Exception {
        AtomicReference<Boolean> confirmed = new AtomicReference<>();
        windows.select(TERMINAL, "@2", confirmed::set);
        assertEquals(TmuxWindows.Selection.SWITCH_PENDING, windows.select(TERMINAL, "@2", ignored -> {}));

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TmuxWindows.SWITCH_TIMEOUT_MS));
        assertEquals(Boolean.FALSE, confirmed.get());
        assertFalse(windows.isSwitchPending(TERMINAL, "@2"));
        assertEquals(JSONObject.NULL, windows.update(TERMINAL, snapshot("@1")));
        assertEquals(TmuxWindows.Selection.SWITCH_REQUESTED, windows.select(TERMINAL, "@2", ignored -> {}));
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
        windows.select(TERMINAL, "@2", ignored -> {});
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
        windows.select(TERMINAL, "@2", ignored -> {});
        assertEquals("@2", windows.update(TERMINAL, snapshot("@1")));
        assertEquals(JSONObject.NULL, windows.update(TERMINAL, snapshot("@1")));
    }

    private static String report(String lines) {
        return TmuxWindows.REPORT_PREFIX + android.util.Base64.encodeToString(
            lines.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    @Test
    public void pluginReportsShowTheirWindowsWithProgramAndName() throws Exception {
        assertTrue(windows.report("plugin", report("@4\t1\t1\tclaude\tcode review\n@5\t2\t0\t\tsh\n")));
        assertTrue(windows.isReported("plugin"));
        JSONArray rows = windows.get("plugin");
        assertEquals(2, rows.length());
        assertEquals("1  code review", rows.getJSONObject(0).getString("label"));
        assertEquals("claude", rows.getJSONObject(0).getString("program"));
        assertTrue(rows.getJSONObject(0).getBoolean("active"));
        assertFalse(rows.getJSONObject(1).has("program"));
    }

    @Test
    public void choosingAReportedWindowDoesNotWaitForAHelper() throws Exception {
        windows.report("plugin", report("@4\t1\t1\tzsh\ta\n@5\t2\t0\tzsh\tb\n"));
        AtomicReference<Boolean> confirmed = new AtomicReference<>();
        assertEquals(TmuxWindows.Selection.SWITCH_REQUESTED, windows.select("plugin", "@5", confirmed::set));
        assertFalse(windows.get("plugin").getJSONObject(1).getBoolean("active"));
        // Nothing is queued for a helper: the service types the choice into the terminal instead.
        assertNull(windows.awaitSelection(TOKEN, 20));
        windows.report("plugin", report("@4\t1\t0\tzsh\ta\n@5\t2\t1\tzsh\tb\n"));
        assertEquals(Boolean.TRUE, confirmed.get());
        assertTrue(windows.get("plugin").getJSONObject(1).getBoolean("active"));
    }

    @Test
    public void anEmptyOrNullReportClearsTheWindows() throws Exception {
        windows.report("plugin", report("@4\t1\t1\tzsh\ta\n"));
        changes = 0;
        windows.report("plugin", null);
        assertEquals(0, windows.get("plugin").length());
        assertFalse(windows.isReported("plugin"));
        assertEquals(1, changes);
        // The helper's attachment of another terminal is not touched.
        assertEquals(2, windows.get(TERMINAL).length());
    }

    @Test
    public void reportsOfAnotherVersionOrShapeAreIgnored() {
        assertNull(TmuxWindows.parseReport("tmux-windows;2;" + "QA=="));
        assertNull(TmuxWindows.parseReport(report("@4\t1\t1\tzsh\n")));
        assertNull(TmuxWindows.parseReport(report("4\t1\t1\tzsh\tname\n")));
        assertFalse(windows.report("plugin", "something else"));
    }

    @Test
    public void choosingAnAlreadyActiveWindowDoesNotRedrawOrQueueACommand() throws Exception {
        assertEquals(TmuxWindows.Selection.ALREADY_ACTIVE, windows.select(TERMINAL, "@1", ignored -> {}));
        assertNull(windows.awaitSelection(TOKEN, 20));
        assertEquals(0, changes);
    }

    @Test
    public void unknownWindowsAreNotChosen() {
        assertEquals(TmuxWindows.Selection.UNAVAILABLE, windows.select(TERMINAL, "@9", ignored -> {}));
        assertEquals(TmuxWindows.Selection.UNAVAILABLE, windows.select("other", "@1", ignored -> {}));
        assertEquals(0, changes);
    }
}
