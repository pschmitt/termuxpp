package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TerminalUiRequestTest {

    private static TerminalUiRequest parse(String json) throws Exception {
        return TerminalUiRequest.parse(new JSONArray(json));
    }

    @Test
    public void theOriginalFormsAreAPlainShow() throws Exception {
        TerminalUiRequest more = parse("[\"ui\", \"more\"]");
        assertEquals("more", more.action);
        assertEquals("show", more.operation);
        assertNull(more.query);
        TerminalUiRequest settings = parse("[\"ui\", \"settings\", \"font size\"]");
        assertEquals("show", settings.operation);
        assertEquals("font size", settings.query);
    }

    @Test
    public void optionsCarryTheOperationQueryAndSide() throws Exception {
        TerminalUiRequest sidebar = parse("[\"ui\", \"sidebar\", {\"op\": \"toggle\", \"side\": \"right\"}]");
        assertEquals("toggle", sidebar.operation);
        assertTrue(sidebar.right);
        TerminalUiRequest palette = parse("[\"ui\", \"palette\", {\"op\": \"show\", \"query\": \"font\"}]");
        assertEquals("font", palette.query);
        assertFalse(palette.right);
        assertEquals("clear", parse("[\"ui\", \"clear\"]").action);
    }

    @Test
    public void invalidRequestsAreRefused() {
        for (String json : new String[] {
            "[\"ui\"]",
            "[\"ui\", \"nope\"]",
            "[\"ui\", \"more\", \"query\"]",
            "[\"ui\", \"clear\", {\"op\": \"toggle\"}]",
            "[\"ui\", \"more\", {\"op\": \"flip\"}]",
            "[\"ui\", \"more\", {\"query\": \"x\"}]",
            "[\"ui\", \"palette\", {\"op\": \"hide\", \"query\": \"x\"}]",
            "[\"ui\", \"palette\", {\"side\": \"right\"}]",
            "[\"ui\", \"sidebar\", {\"side\": \"up\"}]",
            "[\"ui\", \"sidebar\", {\"op\": \"show\", \"extra\": 1}]",
            "[\"ui\", \"more\", {}, 1]",
        }) {
            assertThrows(json, IllegalArgumentException.class, () -> parse(json));
        }
    }

    @Test
    public void fullscreenOverridesPickersOnly() throws Exception {
        assertNull(parse("[\"ui\", \"palette\"]").fullscreen);
        assertEquals(Boolean.TRUE, parse("[\"ui\", \"palette\", {\"op\": \"show\", \"fullscreen\": true}]").fullscreen);
        assertEquals(Boolean.FALSE,
            parse("[\"ui\", \"tmux-switcher\", {\"op\": \"toggle\", \"fullscreen\": false}]").fullscreen);
        assertThrows(IllegalArgumentException.class,
            () -> parse("[\"ui\", \"sidebar\", {\"fullscreen\": true}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parse("[\"ui\", \"palette\", {\"op\": \"hide\", \"fullscreen\": true}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parse("[\"ui\", \"palette\", {\"fullscreen\": \"yes\"}]"));
    }
}
