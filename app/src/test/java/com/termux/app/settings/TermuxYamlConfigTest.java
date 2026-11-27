package com.termux.app.settings;

import dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigMigration;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigSchema;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxYamlConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28, shadows = TermuxPropertiesEditorTest.HostOs.class)
public class TermuxYamlConfigTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void everyAppSettingHasExactlyOnePath() {
        assertEquals(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST,
            new HashSet<>(TermuxConfigSchema.paths().values()));
        assertEquals(TermuxConfigSchema.paths().size(), new HashSet<>(TermuxConfigSchema.paths().values()).size());
        for (String key : TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST)
            assertEquals(key, TermuxConfigSchema.keyFor(TermuxConfigSchema.pathFor(key)));
    }

    @Test
    public void groupedValuesAreReadAsFlatSettings() {
        List<String> unknown = new ArrayList<>();
        Properties properties = TermuxYamlConfig.read(""
            + "appearance:\n  night-mode: true\n"
            + "terminal:\n  margin:\n    vertical: 0\n  cursor:\n    blink-rate: 600\n"
            + "keyboard:\n  shortcuts:\n    next-session: ctrl + 2\n"
            + "gestures:\n  tap:\n    triple:\n      keys: CTRL SHIFT F12\n"
            + "  swipe:\n    two-finger:\n      right:\n        action: open-sidebar\n"
            + "extra-keys:\n  corner-radius: 6.5\n  active:\n    text-color: '#D8B4FE'\n"
            + "  keys:\n    - [ESC, {key: /, popup: BACKSLASH}]\n"
            + "typo:\n  here: 1\n", unknown);
        assertEquals("true", properties.getProperty("night-mode"));
        assertEquals("0", properties.getProperty("terminal-margin-vertical"));
        assertEquals("600", properties.getProperty("terminal-cursor-blink-rate"));
        assertEquals("ctrl + 2", properties.getProperty("shortcut.next-session"));
        assertEquals("CTRL SHIFT F12", properties.getProperty("terminal-triple-tap-keys"));
        assertEquals("open-sidebar", properties.getProperty("terminal-2-finger-swipe-right-action"));
        assertEquals("6.5", properties.getProperty("extra-keys-button-corner-radius"));
        assertEquals("#D8B4FE", properties.getProperty("extra-keys-button-active-text-color"));
        assertTrue(properties.getProperty("extra-keys").startsWith("[[\"ESC\",{"));
        assertEquals(Arrays.asList("typo.here"), unknown);
    }

    @Test
    public void writingGroupsTypesAndRoundTrips() {
        Properties properties = new Properties();
        properties.setProperty("night-mode", "true");
        properties.setProperty("terminal-margin-vertical", "0");
        properties.setProperty("extra-keys-button-corner-radius", "6.5");
        properties.setProperty("extra-keys-button-active-text-color", "#D8B4FE");
        properties.setProperty("shortcut.next-session", "ctrl + 2");
        properties.setProperty("terminal-2-finger-swipe-right-action", "open-sidebar");
        properties.setProperty("terminal-toolbar-height", "007");
        properties.setProperty("extra-keys", "[['ESC', 'TAB'], [{key: CTRL, popup: SHIFT}]]");
        properties.setProperty("not-a-setting", "x");
        List<String> dropped = new ArrayList<>();
        String yaml = TermuxYamlConfig.write(properties, dropped);

        assertTrue(yaml, yaml.contains("appearance:\n  night-mode: true\n"));
        assertTrue(yaml, yaml.contains("terminal:\n  margin:\n    vertical: 0\n"));
        assertTrue(yaml, yaml.contains("  shortcuts:\n    next-session: ctrl + 2\n"));
        assertTrue(yaml, yaml.contains("  keys:\n    - [ESC, TAB]\n    - [{key: CTRL, popup: SHIFT}]\n"));
        assertTrue(yaml, yaml.contains("swipe:\n    two-finger:\n      right:\n        action: open-sidebar\n"));
        assertEquals(Arrays.asList("not-a-setting"), dropped);

        Properties back = TermuxYamlConfig.read(yaml, null);
        for (String key : Arrays.asList("night-mode", "terminal-margin-vertical", "extra-keys-button-corner-radius",
            "extra-keys-button-active-text-color", "shortcut.next-session", "terminal-2-finger-swipe-right-action",
            "terminal-toolbar-height"))
            assertEquals(key, properties.getProperty(key), back.getProperty(key));
        assertNull(back.getProperty("not-a-setting"));
        assertEquals(yaml, TermuxYamlConfig.write(back, null));
    }

    @Test
    public void formerPathsAreReadAndCurrentPathsWin() {
        List<String> former = new ArrayList<>();
        Properties properties = TermuxYamlConfig.read(""
            + "tmux:\n  sidebar:\n    style: subtle\n    right: true\n"
            + "terminal:\n  toolbar-height: 1.5\n"
            + "extra-keys:\n  style: arrows-all\n  swipe:\n    left:\n      action: previous-session\n"
            + "keyboard:\n  volume-keys: volume\n"
            + "sidebar:\n  style: rounded\n", null, former);
        assertEquals("rounded", properties.getProperty("terminal-tmux-sidebar-style"));
        assertEquals("true", properties.getProperty("tmux-right-sidebar"));
        assertEquals("1.5", properties.getProperty("terminal-toolbar-height"));
        assertEquals("arrows-all", properties.getProperty("extra-keys-style"));
        assertEquals("previous-session", properties.getProperty("extra-keys-swipe-left-action"));
        assertEquals("volume", properties.getProperty("volume-keys"));
        assertTrue(former.toString(), former.contains("tmux.sidebar.style"));
        assertFalse(former.toString(), former.contains("sidebar.style"));
    }

    @Test
    public void extraKeySwipesAreNamedByTheFingerDirection() {
        Properties properties = TermuxYamlConfig.read(""
            + "gestures:\n  extra-keys:\n    swipe:\n      right:\n        action: previous-session\n"
            + "    long-swipe:\n      left:\n        action: new-session\n", null);
        // The internal keys are named after the toolbar page, the opposite of the finger.
        assertEquals("previous-session", properties.getProperty("extra-keys-swipe-left-action"));
        assertEquals("new-session", properties.getProperty("extra-keys-long-swipe-right-action"));
    }

    @Test
    public void positiveBooleansAreInverted() {
        Properties properties = TermuxYamlConfig.read(""
            + "appearance:\n  session-change-toast: false\n"
            + "keyboard:\n  hardware-shortcuts: true\n"
            + "integrations:\n  share-target: false\n  disable-file-view-receiver: true\n", null);
        assertEquals("true", properties.getProperty("disable-terminal-session-change-toast"));
        assertEquals("false", properties.getProperty("disable-hardware-keyboard-shortcuts"));
        assertEquals("true", properties.getProperty("disable-file-share-receiver"));
        // A former, negative path keeps its meaning.
        assertEquals("true", properties.getProperty("disable-file-view-receiver"));

        String yaml = TermuxYamlConfig.write(properties, null);
        assertTrue(yaml, yaml.contains("  session-change-toast: false\n"));
        assertTrue(yaml, yaml.contains("  hardware-shortcuts: true\n"));
        assertTrue(yaml, yaml.contains("  share-target: false\n  open-with-target: false\n"));
    }

    @Test
    public void updateKeepsCommentsAndReplacesFormerPaths() {
        String text = "# Managed by hand.\n"
            + "appearance:\n  night-mode: true  # dark\n"
            + "\n# The tmux bits\n"
            + "tmux:\n  sidebar:\n    style: subtle\n"
            + "extra-keys:\n  keys:\n    - [ESC, TAB]\n";
        String out = TermuxYamlConfig.update(text, "terminal-tmux-sidebar-style", "rounded");
        assertTrue(out, out.startsWith("# Managed by hand.\n"));
        assertTrue(out, out.contains("night-mode: true  # dark") || out.contains("night-mode: true # dark"));
        assertFalse(out, out.contains("tmux:"));
        assertTrue(out, out.contains("sidebar:\n  style: rounded\n"));
        assertTrue(out, out.contains("    - [ESC, TAB]\n"));
        // The new group goes where the schema puts it: after appearance, before extra-keys.
        assertTrue(out, out.indexOf("sidebar:") > out.indexOf("appearance:"));
        assertTrue(out, out.indexOf("sidebar:") < out.indexOf("extra-keys:"));
        assertEquals("rounded", TermuxYamlConfig.read(out, null).getProperty("terminal-tmux-sidebar-style"));

        String removed = TermuxYamlConfig.update(out, "terminal-tmux-sidebar-style", null);
        assertFalse(removed, removed.contains("sidebar:"));
        assertTrue(removed, removed.contains("# dark"));

        String inverted = TermuxYamlConfig.update(removed, "disable-hardware-keyboard-shortcuts", "true");
        assertTrue(inverted, inverted.contains("keyboard:\n  hardware-shortcuts: false\n"));

        String rows = TermuxYamlConfig.update("", "extra-keys", "[['ESC'], ['CTRL', {key: '-', popup: '|'}]]");
        assertTrue(rows, rows.startsWith(TermuxYamlConfig.HEADER));
        assertTrue(rows, rows.contains("extra-keys:\n  keys:\n    - [ESC]\n    - [CTRL, {key: '-', popup: '|'}]\n"));
        assertThrows(IllegalArgumentException.class,
            () -> TermuxYamlConfig.update("a: [unclosed\n", "fullscreen", "true"));

        String commentsOnly = TermuxYamlConfig.update("# just a note\n", "fullscreen", "true");
        assertTrue(commentsOnly, commentsOnly.startsWith("# just a note\n"));
        assertTrue(commentsOnly, commentsOnly.contains("appearance:\n  fullscreen: true\n"));
    }

    @Test
    public void invalidDocumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TermuxYamlConfig.read("appearance: [oops", null));
        assertThrows(IllegalArgumentException.class, () -> TermuxYamlConfig.read("- a\n- b\n", null));
        assertThrows(IllegalArgumentException.class,
            () -> TermuxYamlConfig.read("appearance:\n  fullscreen: true\n  fullscreen: false\n", null));
        assertTrue(TermuxYamlConfig.read("", null).isEmpty());
    }

    @Test
    public void migrationConvertsTheFirstLegacyFileOnce() throws Exception {
        File first = temp.newFile("termuxpp.properties");
        File second = temp.newFile("termux.properties");
        Files.write(first.toPath(), "fullscreen=true\nuse-black-ui=true\nextra-keys=[['ESC']]\n".getBytes(StandardCharsets.UTF_8));
        Files.write(second.toPath(), "fullscreen=false\n".getBytes(StandardCharsets.UTF_8));
        File yaml = new File(temp.getRoot(), "config/config.yaml");
        List<String> legacy = Arrays.asList(first.toString(), second.toString());

        assertEquals(first, TermuxConfigMigration.migrate(yaml, legacy));
        String text = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("appearance:\n  night-mode: true\n  fullscreen: true\n"));
        assertFalse(first.exists());
        assertTrue(new File(first + TermuxConfigMigration.MIGRATED_SUFFIX).isFile());
        // Once the YAML file exists, the other legacy file is never read.
        assertNull(TermuxConfigMigration.migrate(yaml, legacy));
        assertTrue(second.exists());
    }
}
