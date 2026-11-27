package com.termux.app.settings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = TermuxPropertiesEditorTest.HostOs.class)
public class TermuxPropertiesEditorTest {
    @org.robolectric.annotation.Implements(android.system.Os.class)
    public static class HostOs {
        // Robolectric's legacy lstat shadow does not distinguish symlinks or missing files.
        @org.robolectric.annotation.Implementation
        protected static android.system.StructStat lstat(String path) throws android.system.ErrnoException {
            java.nio.file.Path file = new File(path).toPath();
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new android.system.ErrnoException("lstat", android.system.OsConstants.ENOENT);
            int mode = Files.isSymbolicLink(file) ? android.system.OsConstants.S_IFLNK
                : Files.isRegularFile(file) ? android.system.OsConstants.S_IFREG : android.system.OsConstants.S_IFDIR;
            return new android.system.StructStat(0, 0, mode, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        @org.robolectric.annotation.Implementation
        protected static android.system.StructStat stat(String path) throws android.system.ErrnoException {
            java.nio.file.Path file = new File(path).toPath();
            if (!Files.exists(file))
                throw new android.system.ErrnoException("stat", android.system.OsConstants.ENOENT);
            int mode = Files.isRegularFile(file) ? android.system.OsConstants.S_IFREG : android.system.OsConstants.S_IFDIR;
            return new android.system.StructStat(0, 0, mode, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void legacyIsMigratedOnFirstUse() throws Exception {
        File legacy = temp.newFile("termuxpp.properties");
        Files.write(legacy.toPath(), "# old\nfullscreen=false\nterminal-tmux-sidebar-style=subtle\nunknown=dropped\n"
            .getBytes(StandardCharsets.UTF_8));
        File target = new File(temp.getRoot(), "config.yaml");
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList(legacy.toString()));
        assertEquals("false", editor.read().getProperty("fullscreen"));
        assertNull(editor.read().getProperty("unknown"));
        assertFalse(legacy.exists());
        assertTrue(new File(legacy + ".migrated").isFile());
        assertTrue(text(target).contains("appearance:\n  fullscreen: false\n"));
        assertTrue(text(target).contains("sidebar:\n  style: subtle\n"));

        editor.set("fullscreen", "true");
        assertEquals("true", editor.read().getProperty("fullscreen"));
        editor.set("fullscreen", null);
        assertNull(editor.read().getProperty("fullscreen"));
        assertEquals("subtle", editor.read().getProperty("terminal-tmux-sidebar-style"));
    }

    @Test public void extraKeysAreWrittenAsRows() throws Exception {
        File target = new File(temp.getRoot(), "config.yaml");
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList());
        editor.set("extra-keys", "[['ESC', {key: '/', popup: BACKSLASH}], ['CTRL']]");
        assertTrue(text(target), text(target).contains("extra-keys:\n  keys:\n    - [ESC, {key: /, popup: BACKSLASH}]\n    - [CTRL]\n"));
        assertEquals("[[\"ESC\",{\"key\":\"\\/\",\"popup\":\"BACKSLASH\"}],[\"CTRL\"]]",
            editor.read().getProperty("extra-keys"));
    }

    @Test public void interruptedAtomicWriteRecoversBeforeEditing() throws Exception {
        File target = new File(temp.getRoot(), "config.yaml");
        Files.write(new File(target + ".bak").toPath(), "appearance:\n  night-mode: true\n".getBytes(StandardCharsets.UTF_8));
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList());
        assertEquals("true", editor.read().getProperty("night-mode"));
        editor.set("fullscreen", "true");
        assertEquals("true", editor.read().getProperty("night-mode"));
        assertEquals("true", editor.read().getProperty("fullscreen"));
    }

    @Test public void literalUtf8IsPreserved() throws Exception {
        File target = new File(temp.getRoot(), "config.yaml");
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList());
        editor.set("terminal-triple-tap-focus-title", "Grüße ☃ 日本語");
        assertEquals("Grüße ☃ 日本語", editor.read().getProperty("terminal-triple-tap-focus-title"));
        assertTrue(text(target).contains("Grüße ☃ 日本語"));
    }

    @Test public void symlinksAreReadButNotReplaced() throws Exception {
        File real = temp.newFile("real.yaml");
        byte[] original = "appearance:\n  fullscreen: false\n".getBytes(StandardCharsets.UTF_8);
        Files.write(real.toPath(), original);
        File link = new File(temp.getRoot(), "config.yaml");
        Files.createSymbolicLink(link.toPath(), real.toPath());
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(link, Arrays.asList());
        assertEquals("false", editor.read().getProperty("fullscreen"));
        java.io.IOException error = assertThrows(java.io.IOException.class, () -> editor.set("fullscreen", "true"));
        assertTrue(error.getMessage(), error.getMessage().contains("symlink"));
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertArrayEquals(original, Files.readAllBytes(real.toPath()));
    }

    @Test public void commentsSurviveAChange() throws Exception {
        File target = temp.newFile("config.yaml");
        Files.write(target.toPath(), ("# mine\nappearance:\n  # dark please\n  night-mode: true\n")
            .getBytes(StandardCharsets.UTF_8));
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList());
        editor.set("fullscreen", "true");
        String text = text(target);
        assertTrue(text, text.startsWith("# mine\n"));
        assertTrue(text, text.contains("# dark please\n  night-mode: true\n"));
        assertEquals("true", editor.read().getProperty("fullscreen"));
    }

    @Test public void malformedFileIsNotOverwritten() throws Exception {
        File target = temp.newFile("config.yaml");
        byte[] original = "appearance: [unclosed\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), original);
        TermuxPropertiesEditor editor = new TermuxPropertiesEditor(target, Arrays.asList());
        assertThrows(java.io.IOException.class, () -> editor.set("fullscreen", "true"));
        assertArrayEquals(original, Files.readAllBytes(target.toPath()));
    }
}
