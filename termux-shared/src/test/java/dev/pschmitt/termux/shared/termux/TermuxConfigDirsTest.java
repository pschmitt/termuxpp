package dev.pschmitt.termux.shared.termux;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TermuxConfigDirsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File pp;
    private File xdg;
    private File legacy;
    private List<String> dirs;

    @Before
    public void setUp() throws IOException {
        pp = tmp.newFolder("home", ".config", "termuxpp");
        xdg = tmp.newFolder("home", ".config", "termux");
        legacy = tmp.newFolder("home", ".termux");
        dirs = Arrays.asList(pp.getPath(), xdg.getPath(), legacy.getPath());
    }

    private static File write(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes("UTF-8"));
        return file;
    }

    @Test
    public void constantsUseTheRequestedOrder() {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        assertEquals(Arrays.asList(
            home + "/.config/termuxpp", home + "/.config/termux", home + "/.termux"),
            TermuxConstants.TERMUX_CONFIG_DIR_PATHS_LIST);
        assertEquals(Arrays.asList(
            home + "/.config/termuxpp/termuxpp.properties",
            home + "/.config/termuxpp/termux.properties",
            home + "/.config/termux/termux.properties",
            home + "/.termux/termux.properties"),
            TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST);
        assertEquals(Arrays.asList(
            home + "/.config/termuxpp/tasker",
            home + "/.config/termux/tasker",
            home + "/.termux/tasker"),
            TermuxConstants.TERMUX_TASKER_SCRIPTS_DIR_PATHS_LIST);
    }

    @Test
    public void canonicalPathsAreWrittenToTermuxpp() {
        String canonical = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/termuxpp";
        assertEquals(canonical + "/colors.properties", TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE_PATH);
        assertEquals(canonical + "/font.ttf", TermuxConstants.TERMUX_FONT_FILE_PATH);
        assertEquals(canonical + "/font-italic.ttf", TermuxConstants.TERMUX_ITALIC_FONT_FILE_PATH);
        assertEquals(canonical + "/font-bold.ttf", TermuxConstants.TERMUX_BOLD_FONT_FILE_PATH);
        assertEquals(canonical + "/font-bold-italic.ttf", TermuxConstants.TERMUX_BOLD_ITALIC_FONT_FILE_PATH);
        assertEquals(canonical + "/tasker", TermuxConstants.TERMUX_TASKER_SCRIPTS_DIR_PATH);
        assertEquals(canonical + "/boot", TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH);
    }

    @Test
    public void resolveFilePrefersCanonical() throws IOException {
        write(pp, "colors.properties", "a");
        write(xdg, "colors.properties", "b");
        write(legacy, "colors.properties", "c");
        assertEquals(new File(pp, "colors.properties"), TermuxConfigDirs.resolveFile(dirs, "colors.properties", false));
    }

    @Test
    public void resolveFileFallsBackInOrder() throws IOException {
        write(xdg, "colors.properties", "b");
        write(legacy, "colors.properties", "c");
        assertEquals(new File(xdg, "colors.properties"), TermuxConfigDirs.resolveFile(dirs, "colors.properties", false));

        assertTrue(new File(xdg, "colors.properties").delete());
        assertEquals(new File(legacy, "colors.properties"), TermuxConfigDirs.resolveFile(dirs, "colors.properties", false));
    }

    @Test
    public void resolveFileIsPerFileForAPartialCanonicalDir() throws IOException {
        write(pp, "font.ttf", "font");
        write(legacy, "colors.properties", "colors");
        write(legacy, "font.ttf", "old font");
        assertEquals(new File(pp, "font.ttf"), TermuxConfigDirs.resolveFile(dirs, "font.ttf", true));
        assertEquals(new File(legacy, "colors.properties"), TermuxConfigDirs.resolveFile(dirs, "colors.properties", false));
    }

    @Test
    public void resolveFileReturnsCanonicalWhenNothingExists() {
        assertEquals(new File(pp, "font.ttf"), TermuxConfigDirs.resolveFile(dirs, "font.ttf", true));
    }

    @Test
    public void resolveFileSkipsEmptyFontsOnlyWhenRequested() throws IOException {
        write(pp, "font.ttf", "");
        write(legacy, "font.ttf", "font");
        assertEquals(new File(legacy, "font.ttf"), TermuxConfigDirs.resolveFile(dirs, "font.ttf", true));
        assertEquals(new File(pp, "font.ttf"), TermuxConfigDirs.resolveFile(dirs, "font.ttf", false));
    }

    @Test
    public void resolveFileFollowsSymlinksButSkipsDanglingOnes() throws IOException {
        File target = write(xdg, "fonts/real.ttf", "font");
        Files.createSymbolicLink(new File(pp, "font.ttf").toPath(), new File("../termux/fonts/real.ttf").toPath());
        assertEquals(new File(pp, "font.ttf"), TermuxConfigDirs.resolveFile(dirs, "font.ttf", true));

        assertTrue(target.delete());
        write(legacy, "font.ttf", "fallback");
        assertEquals(new File(legacy, "font.ttf"), TermuxConfigDirs.resolveFile(dirs, "font.ttf", true));
    }

    @Test
    public void resolveFileIgnoresDirectories() throws IOException {
        assertTrue(new File(pp, "colors.properties").mkdir());
        write(xdg, "colors.properties", "b");
        assertEquals(new File(xdg, "colors.properties"), TermuxConfigDirs.resolveFile(dirs, "colors.properties", false));
    }

    @Test
    public void unreadableCanonicalFileFallsBack() throws IOException {
        File file = write(pp, "colors.properties", "a");
        write(xdg, "colors.properties", "b");
        if (!file.setReadable(false) || file.canRead()) return; // running as root, cannot test
        assertEquals(new File(xdg, "colors.properties"), TermuxConfigDirs.resolveFile(dirs, "colors.properties", false));
    }

    @Test
    public void scriptsResolveToTheFirstDirThatHasThem() throws IOException {
        List<String> taskerDirs = Arrays.asList(
            new File(pp, "tasker").getPath(), new File(xdg, "tasker").getPath(), new File(legacy, "tasker").getPath());
        write(xdg, "tasker/only-xdg.sh", "x");
        write(legacy, "tasker/only-legacy.sh", "x");
        write(pp, "tasker/both.sh", "x");
        write(legacy, "tasker/both.sh", "x");

        assertEquals(new File(pp, "tasker/both.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath("both.sh", taskerDirs));
        assertEquals(new File(xdg, "tasker/only-xdg.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath("only-xdg.sh", taskerDirs));
        assertEquals(new File(legacy, "tasker/only-legacy.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath("only-legacy.sh", taskerDirs));
        // Missing scripts resolve against the canonical directory
        assertEquals(new File(pp, "tasker/missing.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath("missing.sh", taskerDirs));
    }

    @Test
    public void absoluteScriptPathsAreOnlyCanonicalized() throws IOException {
        List<String> taskerDirs = Arrays.asList(new File(pp, "tasker").getPath());
        File outside = write(tmp.newFolder("elsewhere"), "run.sh", "x");
        assertEquals(outside.getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(outside.getPath(), taskerDirs));
        assertEquals(new File("/x").getCanonicalPath(), TermuxConfigDirs.resolveScriptCanonicalPath("/x/y/..", taskerDirs));
    }

    @Test
    public void absoluteScriptPathInALegacyDirFollowsTheMovedScript() throws IOException {
        List<String> taskerDirs = Arrays.asList(
            new File(pp, "tasker").getPath(), new File(xdg, "tasker").getPath(), new File(legacy, "tasker").getPath());
        write(pp, "tasker/moved.sh", "x");
        write(pp, "tasker/sub/nested.sh", "x");
        write(legacy, "tasker/only-legacy.sh", "x");

        // A profile still pointing at the old location finds the script in the canonical directory
        assertEquals(new File(pp, "tasker/moved.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(new File(legacy, "tasker/moved.sh").getPath(), taskerDirs));
        assertEquals(new File(pp, "tasker/moved.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(new File(xdg, "tasker/moved.sh").getPath(), taskerDirs));
        assertEquals(new File(pp, "tasker/sub/nested.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(new File(xdg, "tasker/sub/nested.sh").getPath(), taskerDirs));
        // The other way round works as well
        assertEquals(new File(legacy, "tasker/only-legacy.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(new File(pp, "tasker/only-legacy.sh").getPath(), taskerDirs));
        // Existing absolute paths are never redirected
        write(xdg, "tasker/moved.sh", "y");
        assertEquals(new File(xdg, "tasker/moved.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(new File(xdg, "tasker/moved.sh").getPath(), taskerDirs));
        // Nothing found anywhere: unchanged, so the caller reports the missing file
        assertEquals(new File(legacy, "tasker/nope.sh").getCanonicalPath(),
            TermuxConfigDirs.resolveScriptCanonicalPath(new File(legacy, "tasker/nope.sh").getPath(), taskerDirs));
    }

    @Test
    public void scriptEscapingTheScriptsDirsIsNotMatched() throws IOException {
        List<String> taskerDirs = Arrays.asList(new File(pp, "tasker").getPath(), new File(legacy, "tasker").getPath());
        write(pp, "secret.sh", "x");
        String escaped = TermuxConfigDirs.resolveScriptCanonicalPath("../secret.sh", taskerDirs);
        assertEquals(new File(pp, "secret.sh").getCanonicalPath(), escaped);
        assertEquals(taskerDirs.get(0), TermuxConfigDirs.getMatchedDirPath(escaped, taskerDirs));
        assertTrue(!dev.pschmitt.termux.shared.file.FileUtils.isPathInDirPaths(escaped, taskerDirs, true));
    }

    @Test
    public void matchedDirIsTheContainingScriptsDir() throws IOException {
        List<String> taskerDirs = Arrays.asList(new File(pp, "tasker").getPath(), new File(legacy, "tasker").getPath());
        File script = write(legacy, "tasker/a.sh", "x");
        assertEquals(taskerDirs.get(1), TermuxConfigDirs.getMatchedDirPath(script.getCanonicalPath(), taskerDirs));
        assertEquals(taskerDirs.get(0), TermuxConfigDirs.getMatchedDirPath("/nowhere/a.sh", taskerDirs));
    }

    @Test
    public void symlinkedLegacyDirResolvesIntoACandidate() throws IOException {
        // Mirrors the real layout: ~/.termux is a symlink to ~/.config/termux
        Path link = new File(tmp.getRoot(), "home/.termux-link").toPath();
        Files.createSymbolicLink(link, xdg.toPath());
        write(xdg, "tasker/job.sh", "x");
        List<String> taskerDirs = Arrays.asList(
            new File(pp, "tasker").getPath(), new File(xdg, "tasker").getPath(), link.resolve("tasker").toString());
        String resolved = TermuxConfigDirs.resolveScriptCanonicalPath(link.resolve("tasker/job.sh").toString(), taskerDirs);
        assertTrue(dev.pschmitt.termux.shared.file.FileUtils.isPathInDirPaths(resolved, taskerDirs, true));
    }
}
