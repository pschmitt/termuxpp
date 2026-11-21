package com.termux.app.nativecli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class NativeCliInstallerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File prefix;
    private File libraries;
    private final Map<String, String> assets = new HashMap<>();

    private final NativeCliInstaller.Source source = new NativeCliInstaller.Source() {
        @Override
        public InputStream openAsset(String path) throws IOException {
            String content = assets.get(path);
            if (content == null) throw new IOException("no asset " + path);
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public File libraryDirectory() {
            return libraries;
        }
    };

    @Before
    public void setUp() throws IOException {
        prefix = temp.newFolder("usr");
        libraries = temp.newFolder("lib");
        assets.put("a/tpp", "#!@TERMUX_PREFIX@/bin/bash\necho tpp\n");
        assets.put("a/table", "table 1\n");
    }

    private NativeManifest manifest(String... entries) throws Exception {
        return NativeManifest.parse("{\"version\":1,\"entries\":[" + String.join(",", entries) + "]}");
    }

    private static final String TPP =
        "{\"path\":\"bin/tpp\",\"type\":\"asset\",\"source\":\"a/tpp\",\"executable\":true,\"subst\":true}";
    private static final String TABLE =
        "{\"path\":\"libexec/tpp/commands.tsv\",\"type\":\"asset\",\"source\":\"a/table\"}";
    private static final String BRIDGE =
        "{\"path\":\"libexec/termux-api-broadcast\",\"type\":\"lib\",\"source\":\"libbridge.so\"}";
    private static final String LINK =
        "{\"path\":\"libexec/termux-api\",\"type\":\"symlink\",\"target\":\"termux-api-broadcast\"}";

    private String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(prefix, path).toPath()), StandardCharsets.UTF_8);
    }

    private void write(String path, String content) throws IOException {
        File file = new File(prefix, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private File library(String name) throws IOException {
        File file = new File(libraries, name);
        Files.write(file.toPath(), new byte[] {0x7f, 'E', 'L', 'F'});
        return file;
    }

    private NativeCliInstaller.Report install(NativeManifest manifest) throws IOException {
        return NativeCliInstaller.install(prefix, manifest, source);
    }

    private boolean mentions(NativeCliInstaller.Report report, String text) {
        for (String issue : report.issues) if (issue.contains(text)) return true;
        return false;
    }

    @Test
    public void installsEverythingAndIsIdempotent() throws Exception {
        File bridge = library("libbridge.so");
        NativeManifest manifest = manifest(TPP, TABLE, BRIDGE, LINK);

        NativeCliInstaller.Report first = install(manifest);
        assertEquals(4, first.installed);
        assertTrue(first.issues.toString(), first.issues.isEmpty());
        assertEquals("#!" + prefix.getPath() + "/bin/bash\necho tpp\n", read("bin/tpp"));
        assertTrue(new File(prefix, "bin/tpp").canExecute());
        assertFalse(new File(prefix, "libexec/tpp/commands.tsv").canExecute());
        assertEquals(bridge.getAbsolutePath(),
            Files.readSymbolicLink(new File(prefix, "libexec/termux-api-broadcast").toPath()).toString());
        assertEquals("termux-api-broadcast",
            Files.readSymbolicLink(new File(prefix, "libexec/termux-api").toPath()).toString());

        NativeCliInstaller.Report second = install(manifest);
        assertEquals(0, second.installed + second.updated + second.removed);
        assertEquals(4, second.unchanged);
        assertTrue(second.issues.isEmpty());
    }

    @Test
    public void updatesFilesItInstalled() throws Exception {
        library("libbridge.so");
        install(manifest(TPP, TABLE, BRIDGE));
        assets.put("a/table", "table 2\n");
        File moved = temp.newFolder("lib2");
        libraries = moved;
        library("libbridge.so");

        NativeCliInstaller.Report report = install(manifest(TPP, TABLE, BRIDGE));

        assertEquals(2, report.updated);
        assertEquals("table 2\n", read("libexec/tpp/commands.tsv"));
        assertEquals(new File(moved, "libbridge.so").getAbsolutePath(),
            Files.readSymbolicLink(new File(prefix, "libexec/termux-api-broadcast").toPath()).toString());
    }

    @Test
    public void reinstallsAfterThePrefixWasReplaced() throws Exception {
        install(manifest(TPP, TABLE));
        assertTrue(new File(prefix, "bin/tpp").delete());

        NativeCliInstaller.Report report = install(manifest(TPP, TABLE));

        assertEquals(1, report.updated);
        assertTrue(new File(prefix, "bin/tpp").isFile());
    }

    @Test
    public void keepsFilesTheUserChanged() throws Exception {
        install(manifest(TPP, TABLE));
        write("libexec/tpp/commands.tsv", "mine\n");
        assets.put("a/table", "table 2\n");

        NativeCliInstaller.Report report = install(manifest(TPP, TABLE));

        assertEquals("mine\n", read("libexec/tpp/commands.tsv"));
        assertTrue(mentions(report, "modified after it was installed"));
        // Still recorded: it is reported again, never silently adopted or replaced.
        assertTrue(mentions(install(manifest(TPP, TABLE)), "modified after it was installed"));
    }

    @Test
    public void neverReplacesAForeignFile() throws Exception {
        write("bin/tpp", "#!/bin/sh\necho user script\n");

        NativeCliInstaller.Report report = install(manifest(TPP));

        assertEquals("#!/bin/sh\necho user script\n", read("bin/tpp"));
        assertTrue(mentions(report, "not installed by TermuxPP"));
        assertEquals(0, report.installed);
    }

    @Test
    public void adoptsAFileWithTheExactContent() throws Exception {
        write("libexec/tpp/commands.tsv", "table 1\n");

        NativeCliInstaller.Report report = install(manifest(TABLE));

        assertEquals(1, report.unchanged);
        assertTrue(report.issues.isEmpty());
        assets.put("a/table", "table 2\n");
        assertEquals(1, install(manifest(TABLE)).updated);
    }

    @Test
    public void replacesNoDirectory() throws Exception {
        assertTrue(new File(prefix, "bin/tpp").mkdirs());

        NativeCliInstaller.Report report = install(manifest(TPP));

        assertTrue(new File(prefix, "bin/tpp").isDirectory());
        assertEquals(1, report.issues.size());
    }

    @Test
    public void leavesFilesOfDebianPackagesAlone() throws Exception {
        library("libbridge.so");
        write("libexec/termux-api-broadcast", "old binary");
        write("var/lib/dpkg/info/termux-api.list", prefix + "/libexec/termux-api-broadcast\n");

        NativeCliInstaller.Report report = install(manifest(TPP, BRIDGE));

        assertEquals("old binary", read("libexec/termux-api-broadcast"));
        assertFalse(Files.isSymbolicLink(new File(prefix, "libexec/termux-api-broadcast").toPath()));
        assertTrue(mentions(report, "owned by package termux-api"));
        assertTrue(new File(prefix, "bin/tpp").isFile());
    }

    @Test
    public void dpkgListedButMissingFileIsInstalledAndReported() throws Exception {
        write("var/lib/dpkg/info/termux-api.list", prefix + "/bin/tpp\n");

        NativeCliInstaller.Report report = install(manifest(TPP));

        assertTrue(new File(prefix, "bin/tpp").isFile());
        assertTrue(mentions(report, "package termux-api"));
    }

    @Test
    public void reportsStaleDpkgEntriesWithoutTouchingTheDatabase() throws Exception {
        write("var/lib/dpkg/status", "Package: termux-api\nStatus: install ok installed\n"
            + "Version: 0.60.0+termuxpp1\n\nPackage: termux-am\nStatus: install ok installed\n"
            + "Version: 0.8.1+termuxpp1\n\nPackage: bash\nStatus: install ok installed\nVersion: 5\n");
        write("var/lib/dpkg/info/termux-api.list",
            prefix + "\n" + prefix + "/libexec/gone-one\n" + prefix + "/libexec/gone-two\n");
        write("var/lib/dpkg/info/termux-am.list", prefix + "/bin/am\n");
        write("bin/am", "#!/bin/sh\n");
        write("var/lib/dpkg/info/bash.list", prefix + "/bin/bash-missing\n");
        byte[] status = Files.readAllBytes(new File(prefix, "var/lib/dpkg/status").toPath());
        byte[] list = Files.readAllBytes(new File(prefix, "var/lib/dpkg/info/termux-api.list").toPath());

        NativeCliInstaller.Report report = install(manifest(TPP));

        assertTrue(report.issues.toString(), mentions(report, "Stale dpkg entry termux-api 0.60.0+termuxpp1: 2 of 2"));
        assertFalse("termux-am still has its files", mentions(report, "Stale dpkg entry termux-am"));
        assertFalse("only the retired helper packages are reported", mentions(report, "bash"));
        assertTrue(java.util.Arrays.equals(status,
            Files.readAllBytes(new File(prefix, "var/lib/dpkg/status").toPath())));
        assertTrue(java.util.Arrays.equals(list,
            Files.readAllBytes(new File(prefix, "var/lib/dpkg/info/termux-api.list").toPath())));
        assertTrue(new File(prefix, "bin/am").isFile());
    }

    @Test
    public void staleTermuxAmIsKeptBecauseTheBridgeRunsIt() throws Exception {
        write("var/lib/dpkg/status",
            "Package: termux-am\nStatus: install ok installed\nVersion: 0.8.1+termuxpp1\n");
        write("var/lib/dpkg/info/termux-am.list", prefix + "/bin/am\n");

        NativeCliInstaller.Report report = install(manifest(TPP));

        assertTrue(mentions(report, "keep it"));
    }

    @Test
    public void removesObsoleteFilesOnlyWhenUnchanged() throws Exception {
        install(manifest(TPP, TABLE));
        write("bin/tpp", "edited\n");

        NativeCliInstaller.Report report = install(manifest());

        assertEquals(1, report.removed);
        assertFalse(new File(prefix, "libexec/tpp/commands.tsv").exists());
        assertEquals("edited\n", read("bin/tpp"));
    }

    @Test
    public void optionalLibraryMayBeAbsent() throws Exception {
        String optional = "{\"path\":\"libexec/x\",\"type\":\"lib\",\"source\":\"libx.so\",\"optional\":true}";
        assertTrue(install(manifest(optional)).issues.isEmpty());
        assertTrue(mentions(install(manifest(BRIDGE)), "the APK has no libbridge.so"));
        assertFalse(new File(prefix, "libexec/termux-api-broadcast").exists());
    }

    @Test
    public void dropsOurLinkWhenTheLibraryIsGone() throws Exception {
        File bridge = library("libbridge.so");
        install(manifest(BRIDGE));
        assertTrue(bridge.delete());

        NativeCliInstaller.Report report = install(manifest(BRIDGE));

        assertEquals(1, report.removed);
        assertFalse(Files.isSymbolicLink(new File(prefix, "libexec/termux-api-broadcast").toPath()));
    }

    @Test
    public void doesNotWriteThroughASymlinkedDirectoryOutOfThePrefix() throws Exception {
        File outside = temp.newFolder("outside");
        Files.createSymbolicLink(new File(prefix, "bin").toPath(), outside.toPath());

        NativeCliInstaller.Report report = install(manifest(TPP));

        assertEquals(0, outside.list().length);
        assertTrue(mentions(report, "leads out of the prefix"));
    }

    @Test
    public void anUnreadableRecordMakesNothingOurs() throws Exception {
        install(manifest(TABLE));
        write(NativeCliInstaller.STATE_FILE, "not json");
        assets.put("a/table", "table 2\n");

        NativeCliInstaller.Report report = install(manifest(TABLE));

        assertEquals("table 1\n", read("libexec/tpp/commands.tsv"));
        assertTrue(mentions(report, "unreadable install record"));
        assertTrue(mentions(report, "not installed by TermuxPP"));
    }

    @Test
    public void recordsDigestsAndIssues() throws Exception {
        write("bin/tpp", "foreign\n");
        install(manifest(TPP, TABLE));

        JSONObject state = new JSONObject(read(NativeCliInstaller.STATE_FILE));
        JSONObject files = state.getJSONObject("files");
        assertTrue(files.has("libexec/tpp/commands.tsv"));
        assertFalse(files.has("bin/tpp"));
        JSONArray issues = state.getJSONArray("issues");
        assertEquals(1, issues.length());
    }

    @Test
    public void leavesNoTemporaryFilesBehind() throws Exception {
        library("libbridge.so");
        install(manifest(TPP, TABLE, BRIDGE, LINK));
        install(manifest(TPP, TABLE, BRIDGE, LINK));

        try (java.util.stream.Stream<Path> walk = Files.walk(prefix.toPath())) {
            walk.forEach(path -> {
                if (path.getFileName().toString().endsWith(".tmp")) fail("left behind: " + path);
            });
        }
    }

    @Test
    public void manifestRejectsUnsafePaths() throws Exception {
        for (String bad : new String[] {"../x", "/abs", "a//b", "a/./b", ""}) {
            try {
                manifest("{\"path\":\"" + bad + "\",\"type\":\"symlink\",\"target\":\"t\"}");
                fail("accepted " + bad);
            } catch (org.json.JSONException expected) {
                // fine
            }
        }
        try {
            manifest("{\"path\":\"a\",\"type\":\"lib\",\"source\":\"../libx.so\"}");
            fail("accepted a library path");
        } catch (org.json.JSONException expected) {
            // fine
        }
        try {
            manifest(TPP, TPP);
            fail("accepted a duplicate");
        } catch (org.json.JSONException expected) {
            // fine
        }
    }
}
