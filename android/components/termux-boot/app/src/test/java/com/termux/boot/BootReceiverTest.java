package com.termux.boot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BootReceiverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bootScriptsUsePerNameConfigPrecedenceWithoutDuplicates() throws IOException {
        File home = temporaryFolder.newFolder("home");
        File canonical = new File(home, ".config/termuxpp/boot");
        File legacyXdg = new File(home, ".config/termux/boot");
        File legacy = new File(home, ".termux/boot");
        assertTrue(canonical.mkdirs());
        assertTrue(legacyXdg.mkdirs());
        assertTrue(legacy.mkdirs());

        File canonicalDuplicate = new File(canonical, "same-name");
        File xdgDuplicate = new File(legacyXdg, "same-name");
        File legacyDuplicate = new File(legacy, "same-name");
        File legacyOnly = new File(legacy, "legacy-only");
        assertTrue(canonicalDuplicate.createNewFile());
        assertTrue(xdgDuplicate.createNewFile());
        assertTrue(legacyDuplicate.createNewFile());
        assertTrue(legacyOnly.createNewFile());

        List<File> scripts = BootReceiver.getBootScripts(home.getAbsolutePath());

        assertEquals(2, scripts.size());
        assertEquals("legacy-only", scripts.get(0).getName());
        assertEquals(legacyOnly.getCanonicalFile(), scripts.get(0).getCanonicalFile());
        assertEquals("same-name", scripts.get(1).getName());
        assertEquals(canonicalDuplicate.getCanonicalFile(), scripts.get(1).getCanonicalFile());
    }
}
