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
        assertTrue(text, text.contains("appearance:\n  fullscreen: true\n  night-mode: true\n"));
        assertFalse(first.exists());
        assertTrue(new File(first + TermuxConfigMigration.MIGRATED_SUFFIX).isFile());
        // Once the YAML file exists, the other legacy file is never read.
        assertNull(TermuxConfigMigration.migrate(yaml, legacy));
        assertTrue(second.exists());
    }
}
