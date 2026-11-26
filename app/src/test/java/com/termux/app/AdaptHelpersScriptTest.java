package com.termux.app;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the real APT hook script against a fake prefix. */
public class AdaptHelpersScriptTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File prefix;
    private File hookDir;
    private File script;

    private static final class Result {
        int exit;
        String stderr;
    }

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("bash and coreutils needed", new File("/bin/bash").exists() || new File("/usr/bin/bash").exists()
            || new File("/run/current-system/sw/bin/bash").exists() || onPath("bash"));
        prefix = temp.newFolder("files", "usr");
        new File(prefix, "bin").mkdirs();
        hookDir = new File(prefix, "libexec/termuxpp");
        hookDir.mkdirs();
        script = new File(hookDir, "adapt-helpers");
        write(script, TermuxHelperRewrites.readResource(TermuxHelperRewrites.HOOK_SCRIPT_RESOURCE));
        writeTable(prefix.getCanonicalPath());
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, program).canExecute()) return true;
        }
        return false;
    }

    private void writeTable(String prefixPath) throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), TermuxHelperRewritesTest.forkValues());
        write(new File(hookDir, "helper-rewrites.tsv"), TermuxHelperRewrites.hookTable(prefixPath, rendered));
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private File helper(String name) {
        return new File(prefix, "bin/" + name);
    }

    private void installStock(String name, String mode) throws IOException {
        write(helper(name), TermuxHelperRewritesTest.stockHelper(name));
        Files.setPosixFilePermissions(helper(name).toPath(), PosixFilePermissions.fromString(mode));
    }

    private void installStockFile(String relativePath, String mode) throws IOException {
        File file = new File(prefix, relativePath);
        file.getParentFile().mkdirs();
        write(file, TermuxHelperRewritesTest.stockFile(relativePath));
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode));
    }

    /** The dpkg file list of termux-tools; entries are absolute, like the ones dpkg records. */
    private void writeToolsList(String... entries) throws IOException {
        File list = new File(prefix, "var/lib/dpkg/info/termux-tools.list");
        list.getParentFile().mkdirs();
        StringBuilder content = new StringBuilder("/.\n");
        for (String entry : entries) {
            content.append(entry.startsWith("/") ? entry : prefix.getCanonicalPath() + "/" + entry).append('\n');
        }
        write(list, content.toString());
    }

    private Result run(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("bash");
        command.add(script.getPath());
        command.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        process.getOutputStream().close();
        byte[] stderr = readAll(process.getErrorStream());
        readAll(process.getInputStream());
        Assert.assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        Result result = new Result();
        result.exit = process.exitValue();
        result.stderr = new String(stderr, StandardCharsets.UTF_8);
        return result;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private List<TermuxHelperRewrites.Rule> rules() throws IOException {
        return TermuxHelperRewritesTest.forkRules();
    }

    @Test
    public void hookProducesExactlyWhatTheInstallerProduces() throws Exception {
        for (String name : TermuxHelperRewritesTest.HELPERS) installStock(name, "rwx------");
        installStockFile("bin/login", "rwx------");
        installStockFile("etc/termux-login.sh", "rw-------");
        installStockFile("bin/pkg", "rwx------");
        installStockFile("etc/motd", "rw-------");
        installStockFile("etc/profile.d/init-termux-properties.sh", "rw-------");
        writeToolsList("bin", "bin/login", "bin/pkg", "etc/termux-login.sh", "etc/motd",
            "etc/profile.d/init-termux-properties.sh");
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        for (String name : TermuxHelperRewritesTest.HELPERS) {
            Assert.assertEquals(name,
                TermuxHelperRewrites.apply(rules(), "bin/" + name, TermuxHelperRewritesTest.stockHelper(name)),
                read(helper(name)));
            Assert.assertTrue(result.stderr.contains("adapted bin/" + name));
        }
        for (String file : TermuxHelperRewritesTest.STOCK_FILES) {
            Assert.assertEquals(file,
                TermuxHelperRewrites.apply(rules(), file, TermuxHelperRewritesTest.stockFile(file)),
                read(new File(prefix, file)));
            Assert.assertTrue(result.stderr.contains("adapted " + file));
        }
        Assert.assertEquals("rw-------", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(new File(prefix, "etc/termux-login.sh").toPath())));
    }

    private static final String ADAPTED_MOTD = "Welcome to TermuxPP!\n"
        + "\n"
        + "Docs:       https://github.com/pschmitt/termuxpp/tree/main/docs/en\n"
        + "Source:     https://github.com/pschmitt/termuxpp\n"
        + "\n"
        + "Working with packages:\n"
        + "\n"
        + " - Search:  pkg search <query>\n"
        + " - Install: pkg install <package>\n"
        + " - Upgrade: pkg upgrade\n"
        + "\n"
        + "Subscribing to additional repositories:\n"
        + "\n"
        + " - Root:    pkg install root-repo\n"
        + " - X11:     pkg install x11-repo\n"
        + "\n"
        + "For fixing any repository issues,\n"
        + "try 'termux-change-repo' command.\n"
        + "\n"
        + "Package issues (upstream Termux): https://github.com/termux/termux-packages/issues\n";

    @Test
    public void theMotdPointsToTheForkAndSurvivesAnUpstreamReinstall() throws Exception {
        installStockFile("etc/motd", "rw-r--r--");
        writeToolsList("etc/motd");
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        Assert.assertEquals(ADAPTED_MOTD, read(new File(prefix, "etc/motd")));
        Assert.assertTrue(result.stderr.contains("adapted etc/motd"));
        Assert.assertFalse(read(new File(prefix, "etc/motd")).contains("termux.dev"));
        Assert.assertFalse(read(new File(prefix, "etc/motd")).contains("Donate"));

        // A second run changes nothing and says nothing.
        Result again = run();
        Assert.assertEquals(0, again.exit);
        Assert.assertFalse(again.stderr, again.stderr.contains("adapted"));

        // termux-tools is upgraded or reinstalled: dpkg writes the stock conffile again.
        Files.delete(new File(prefix, "etc/motd").toPath());
        installStockFile("etc/motd", "rw-r--r--");
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals(ADAPTED_MOTD, read(new File(prefix, "etc/motd")));
        Assert.assertEquals("rw-r--r--", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(new File(prefix, "etc/motd").toPath())));
    }

    private static final String INIT_SCRIPT = "etc/profile.d/init-termux-properties.sh";
    private static final String ADAPTED_INIT = "if [ ! -f /data/data/dev.pschmitt.termux/files/home/.config/termux/termux.properties ]"
        + " && [ ! -e /data/data/dev.pschmitt.termux/files/home/.termux/termux.properties ]"
        + " && [ ! -e /data/data/dev.pschmitt.termux/files/home/.config/termuxpp/termuxpp.properties ]"
        + " && [ ! -e /data/data/dev.pschmitt.termux/files/home/.config/termuxpp/termux.properties ]"
        + " && [ ! -e /data/data/dev.pschmitt.termux/files/home/.config/termuxpp/config.yaml ]; then\n"
        + "\tmkdir -p /data/data/dev.pschmitt.termux/files/home/.config/termuxpp\n"
        + "\tcp /data/data/dev.pschmitt.termux/files/usr/share/examples/termux/termux.properties"
        + " /data/data/dev.pschmitt.termux/files/home/.config/termuxpp/termuxpp.properties\n"
        + "fi\n";

    @Test
    public void theExampleSettingsGoToTheCanonicalDirAndSurviveAnUpstreamReinstall() throws Exception {
        installStockFile(INIT_SCRIPT, "rw-r--r--");
        writeToolsList(INIT_SCRIPT);
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        Assert.assertEquals(ADAPTED_INIT, read(new File(prefix, INIT_SCRIPT)));
        Assert.assertTrue(result.stderr.contains("adapted " + INIT_SCRIPT));

        // A second run changes nothing and says nothing.
        Result again = run();
        Assert.assertEquals(0, again.exit);
        Assert.assertFalse(again.stderr, again.stderr.contains("adapted"));

        // termux-tools is upgraded or reinstalled: dpkg writes the stock conffile again.
        Files.delete(new File(prefix, INIT_SCRIPT).toPath());
        installStockFile(INIT_SCRIPT, "rw-r--r--");
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals(ADAPTED_INIT, read(new File(prefix, INIT_SCRIPT)));
        Assert.assertEquals("rw-r--r--", PosixFilePermissions.toString(
            Files.getPosixFilePermissions(new File(prefix, INIT_SCRIPT).toPath())));
    }

    @Test
    public void aCustomisedInitScriptIsLeftAloneAndDoesNotFailTheHook() throws Exception {
        String custom = "# my own init\n[ -f ~/.config/termuxpp/termux.properties ] || touch ~/.config/termuxpp/termux.properties\n";
        write(new File(prefix, INIT_SCRIPT), custom);
        writeToolsList(INIT_SCRIPT);
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        Assert.assertEquals(custom, read(new File(prefix, INIT_SCRIPT)));
        Assert.assertFalse(result.stderr, result.stderr.contains("adapted"));
    }

    @Test
    public void aCustomMotdKeepsItsOwnText() throws Exception {
        String custom = "Hello Philipp, today is a good day.\nDocs live at https://example.org/docs\n";
        write(new File(prefix, "etc/motd"), custom);
        writeToolsList("etc/motd");
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        Assert.assertEquals(custom, read(new File(prefix, "etc/motd")));
        Assert.assertFalse(result.stderr, result.stderr.contains("adapted"));

        // An edited stock motd only loses the stock lines it still has, everything else stays.
        String edited = TermuxHelperRewritesTest.stockFile("etc/motd")
            .replace("Welcome to Termux!", "Hi there!") + "\nMy own line\n";
        write(new File(prefix, "etc/motd"), edited);
        Assert.assertEquals(0, run().exit);
        String result2 = read(new File(prefix, "etc/motd"));
        Assert.assertTrue(result2.startsWith("Hi there!\n"));
        Assert.assertTrue(result2.endsWith("\nMy own line\n"));
        Assert.assertTrue(result2.contains("Source:     https://github.com/pschmitt/termuxpp\n"));
        Assert.assertFalse(result2.contains("termux.dev"));
    }

    @Test
    public void loginAndTheConffileAreRestoredAfterAnUpstreamReinstall() throws Exception {
        installStockFile("bin/login", "rwx------");
        installStockFile("etc/termux-login.sh", "rw-------");
        Assert.assertEquals(0, run().exit);
        String login = read(new File(prefix, "bin/login"));
        String conffile = read(new File(prefix, "etc/termux-login.sh"));
        Assert.assertTrue(login.contains("${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"));
        Assert.assertTrue(conffile.contains("TERMUX_PKG_REDIRECT_SO"));

        // dpkg unpacks both again; for the conffile the user chose "install the new version".
        Files.delete(new File(prefix, "bin/login").toPath());
        Files.delete(new File(prefix, "etc/termux-login.sh").toPath());
        installStockFile("bin/login", "rwx------");
        installStockFile("etc/termux-login.sh", "rw-------");
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals(login, read(new File(prefix, "bin/login")));
        Assert.assertEquals(conffile, read(new File(prefix, "etc/termux-login.sh")));
    }

    @Test
    public void aChangedUpstreamLoginFailsLoudlyButKeepsTheShebangUsable() throws Exception {
        String changed = TermuxHelperRewritesTest.stockFile("bin/login")
            .replace("export LD_PRELOAD=\"/", "export LD_PRELOAD=$LIB/");
        write(new File(prefix, "bin/login"), changed);
        Result result = run();
        Assert.assertEquals(1, result.exit);
        Assert.assertTrue(result.stderr, result.stderr.contains("ERROR: bin/login: missing expected text"));
        Assert.assertTrue(read(new File(prefix, "bin/login")).startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/sh\n"));
    }

    @Test
    public void aNewStockReferenceInAHelperFailsLoudly() throws Exception {
        write(helper("termux-open"), TermuxHelperRewritesTest.stockHelper("termux-open")
            + "am broadcast -n \"com.termux/com.termux.app.New\"\n");
        Result result = run();
        Assert.assertEquals(1, result.exit);
        Assert.assertTrue(result.stderr, result.stderr.contains("ERROR: bin/termux-open: still contains stock text"));
        Assert.assertTrue(result.stderr.contains("could not adapt bin/termux-open"));
    }

    @Test
    public void identityOnlyAppliesToTheTextFilesOfTermuxTools() throws Exception {
        String stockPath = "/data/data/com.termux/files/usr";
        new File(prefix, "etc").mkdirs();
        write(helper("listed"), "#!/data/data/com.termux/files/usr/bin/sh\n");
        write(helper("unlisted"), "#!/data/data/com.termux/files/usr/bin/sh\n");
        write(new File(prefix, "etc/motd"), "see /data/data/com.termux/files/usr/etc\n");
        Files.write(new File(prefix, "etc/blob").toPath(), new byte[] {'#', '!', 0, '/', 'd', 'a', 't', 'a', '/', 'c'});
        File outside = temp.newFile("outside");
        write(outside, "/data/data/com.termux/x\n");
        Files.createSymbolicLink(new File(prefix, "etc/link").toPath(), outside.toPath());
        writeToolsList("bin/listed", "etc/motd", "etc/blob", "etc/link", "bin/missing", "etc/../../outside-escape",
            stockPath + "/bin/termux-open", "/elsewhere/bin/unlisted");
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        Assert.assertEquals("#!/data/data/dev.pschmitt.termux/files/usr/bin/sh\n", read(helper("listed")));
        Assert.assertEquals("#!/data/data/com.termux/files/usr/bin/sh\n", read(helper("unlisted")));
        Assert.assertEquals("see /data/data/dev.pschmitt.termux/files/usr/etc\n", read(new File(prefix, "etc/motd")));
        Assert.assertEquals("/data/data/com.termux/x\n", read(outside));
        Assert.assertTrue(Files.isSymbolicLink(new File(prefix, "etc/link").toPath()));
        Assert.assertEquals(10, Files.readAllBytes(new File(prefix, "etc/blob").toPath()).length);
    }

    @Test
    public void textWithMultibyteCharactersIsRewrittenWithoutLosingBytes() throws Exception {
        new File(prefix, "share/examples").mkdirs();
        String content = "# äöü — „quoted“\n# dir = /data/data/com.termux/files/home\n✓ done";
        write(new File(prefix, "share/examples/termux.properties"), content);
        writeToolsList("share/examples/termux.properties");
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        Assert.assertEquals(content.replace("/data/data/com.termux", "/data/data/dev.pschmitt.termux"),
            read(new File(prefix, "share/examples/termux.properties")));
    }

    @Test
    public void theStockIdentityLeavesEverythingByteIdenticalAndSilent() throws Exception {
        for (String name : TermuxHelperRewritesTest.HELPERS) installStock(name, "rwx------");
        installStockFile("bin/login", "rwx------");
        installStockFile("etc/termux-login.sh", "rw-------");
        writeToolsList("bin/login", "etc/termux-login.sh");
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), TermuxHelperRewritesTest.stockValues());
        write(new File(hookDir, "helper-rewrites.tsv"), TermuxHelperRewrites.hookTable(prefix.getCanonicalPath(), rendered));
        Result result = run();
        Assert.assertEquals(result.stderr, 0, result.exit);
        // Unusable rows (stock equals fork, unresolved guard) are only warnings; nothing is rewritten.
        Assert.assertFalse(result.stderr, result.stderr.contains("adapted"));
        Assert.assertFalse(result.stderr, result.stderr.contains("ERROR"));
        for (String name : TermuxHelperRewritesTest.HELPERS) {
            Assert.assertEquals(TermuxHelperRewritesTest.stockHelper(name), read(helper(name)));
        }
        for (String file : TermuxHelperRewritesTest.STOCK_FILES) {
            File target = new File(prefix, file);
            if (target.exists()) Assert.assertEquals(TermuxHelperRewritesTest.stockFile(file), read(target));
        }
    }

    @Test
    public void modeIsPreservedAndNoTemporaryFilesRemain() throws Exception {
        installStock("termux-open", "rwxr-x---");
        installStock("termux-info", "rw-------");
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(helper("termux-open").toPath())));
        Assert.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(helper("termux-info").toPath())));
        for (String entry : new File(prefix, "bin").list()) {
            Assert.assertFalse(entry, entry.contains(".termuxpp."));
        }
    }

    @Test
    public void secondRunIsAByteIdenticalNoOp() throws Exception {
        for (String name : TermuxHelperRewritesTest.HELPERS) installStock(name, "rwx------");
        run();
        String before = read(helper("termux-wake-lock"));
        long modified = helper("termux-wake-lock").lastModified();
        Thread.sleep(1100);
        Result again = run();
        Assert.assertEquals(0, again.exit);
        Assert.assertEquals("", again.stderr);
        Assert.assertEquals(before, read(helper("termux-wake-lock")));
        Assert.assertEquals(modified, helper("termux-wake-lock").lastModified());
    }

    @Test
    public void hookRestoresAHelperAfterAnUpstreamReplacement() throws Exception {
        installStock("termux-open", "rwx------");
        run();
        String adapted = read(helper("termux-open"));
        // APT replaces the helper with the stock version again (new inode, stock content).
        Files.delete(helper("termux-open").toPath());
        installStock("termux-open", "rwx------");
        Assert.assertNotEquals(adapted, read(helper("termux-open")));
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals(adapted, read(helper("termux-open")));
    }

    @Test
    public void dryRunChangesNothing() throws Exception {
        installStock("termux-open", "rwx------");
        String before = read(helper("termux-open"));
        Assert.assertEquals(0, run("--dry-run").exit);
        Assert.assertEquals(before, read(helper("termux-open")));
    }

    @Test
    public void missingHelpersAreSkipped() throws Exception {
        installStock("termux-open", "rwx------");
        Result result = run();
        Assert.assertEquals(0, result.exit);
        Assert.assertFalse(result.stderr.contains("could not adapt"));
        Assert.assertFalse(helper("termux-reset").exists());
    }

    @Test
    public void symlinkedHelpersAreNotFollowedOrReplaced() throws Exception {
        File outside = temp.newFile("outside-script");
        write(outside, TermuxHelperRewritesTest.stockHelper("termux-open"));
        Files.createSymbolicLink(helper("termux-open").toPath(), outside.toPath());
        Assert.assertEquals(0, run().exit);
        Assert.assertTrue(Files.isSymbolicLink(helper("termux-open").toPath()));
        Assert.assertEquals(TermuxHelperRewritesTest.stockHelper("termux-open"), read(outside));
    }

    @Test
    public void unknownButHarmlessContentIsUntouched() throws Exception {
        write(helper("termux-open"), "#!/bin/sh\necho custom\n");
        write(helper("termux-something-else"), "#!/bin/sh\n");
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals("#!/bin/sh\necho custom\n", read(helper("termux-open")));
    }

    @Test
    public void filesThatCannotBeAdaptedAreLeftAloneAndReported() throws Exception {
        write(helper("termux-reset"), "not a script \"com.termux/com.termux.app.TermuxService\"\n");
        Files.write(helper("termux-info").toPath(), new byte[] {0x7f, 'E', 'L', 'F', 0, 1, 2});
        // A shebang file with a NUL byte is not a shell script either.
        byte[] withNul = ("#!/bin/sh\n-n \"com.termux/com.termux.app.TermuxOpenReceiver\"\n\0\n").getBytes(StandardCharsets.UTF_8);
        Files.write(helper("termux-wake-lock").toPath(), withNul);
        Result result = run();
        Assert.assertEquals(1, result.exit);
        Assert.assertTrue(result.stderr, result.stderr.contains("ERROR: bin/termux-reset: still contains stock text"));
        Assert.assertTrue(result.stderr.contains("ERROR: bin/termux-info: not a text file"));
        Assert.assertTrue(result.stderr.contains("ERROR: bin/termux-wake-lock: not a text file"));
        Assert.assertEquals("not a script \"com.termux/com.termux.app.TermuxService\"\n", read(helper("termux-reset")));
        Assert.assertArrayEquals(new byte[] {0x7f, 'E', 'L', 'F', 0, 1, 2}, Files.readAllBytes(helper("termux-info").toPath()));
        Assert.assertArrayEquals(withNul, Files.readAllBytes(helper("termux-wake-lock").toPath()));
    }

    @Test
    public void filesWithoutATrailingNewlineKeepTheirBytes() throws Exception {
        String content = "#!/bin/sh\nam startservice -a \"com.termux.service_wake_lock\" \"com.termux/com.termux.app.TermuxService\"";
        write(helper("termux-wake-lock"), content);
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals("#!/bin/sh\nam startservice -a \"dev.pschmitt.termux.service_wake_lock\" "
            + "\"dev.pschmitt.termux/com.termux.app.TermuxService\"", read(helper("termux-wake-lock")));
    }

    @Test
    public void regexAndGlobCharactersInRulesAreLiteral() throws Exception {
        write(helper("termux-open"), "#!/bin/sh\necho a.b*c[d]&e\n");
        Path table = new File(hookDir, "helper-rewrites.tsv").toPath();
        Files.write(table, ("prefix\t" + prefix.getCanonicalPath() + "\n"
            + "rule\tbin/termux-open\ta.b*c[d]&e\tX&Y*Z\n").getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(0, run().exit);
        Assert.assertEquals("#!/bin/sh\necho X&Y*Z\n", read(helper("termux-open")));
    }

    @Test
    public void invalidTablesFailLoudlyAndChangeNothing() throws Exception {
        installStock("termux-open", "rwx------");
        String before = read(helper("termux-open"));
        Path table = new File(hookDir, "helper-rewrites.tsv").toPath();
        String rule = "rule\tbin/termux-open\t-n \"com.termux/com.termux.app.TermuxOpenReceiver\"\t-n \"x/y\"\n";
        String[] tables = {
            rule,
            "prefix\trelative\n" + rule,
            "prefix\t/data/../etc\n" + rule,
            "prefix\t/does/not/exist\n" + rule,
            "prefix\t" + temp.getRoot().getCanonicalPath() + "\n" + rule, // script is not under that prefix
            "",
        };
        for (String content : tables) {
            Files.write(table, content.getBytes(StandardCharsets.UTF_8));
            Result result = run();
            Assert.assertEquals(content, 1, result.exit);
            Assert.assertTrue(content + result.stderr, result.stderr.contains("ERROR"));
            Assert.assertEquals(content, before, read(helper("termux-open")));
        }
    }

    @Test
    public void rulesWithAnUnresolvedPlaceholderAreIgnored() throws Exception {
        installStock("termux-open", "rwx------");
        String before = read(helper("termux-open"));
        write(new File(hookDir, "helper-rewrites.tsv"), "prefix\t" + prefix.getCanonicalPath() + "\n"
            + "rule\tbin/termux-open\t-n \"com.termux/com.termux.app.TermuxOpenReceiver\"\t-n \"@PACKAGE@/x\"\n");
        Result result = run();
        Assert.assertEquals(0, result.exit);
        Assert.assertEquals(before, read(helper("termux-open")));
    }

    @Test
    public void unexpectedHelperNamesInTheTableAreRefused() throws Exception {
        File victim = new File(prefix, "etc");
        victim.mkdirs();
        write(new File(victim, "profile"), "#!/bin/sh\nreplaceme\n");
        write(helper("termux-open"), "#!/bin/sh\nreplaceme\n");
        Path table = new File(hookDir, "helper-rewrites.tsv").toPath();
        Files.write(table, ("prefix\t" + prefix.getCanonicalPath() + "\n"
            + "rule\tetc/profile\treplaceme\tpwned\n"
            + "rule\tbin/../etc/profile\treplaceme\tpwned\n"
            + "rule\tbin/termux-open\treplaceme\tfine\n").getBytes(StandardCharsets.UTF_8));
        Result result = run();
        Assert.assertEquals(1, result.exit);
        Assert.assertEquals("#!/bin/sh\nreplaceme\n", read(new File(victim, "profile")));
        Assert.assertEquals("#!/bin/sh\nfine\n", read(helper("termux-open")));
        Assert.assertTrue(result.stderr.contains("refusing unexpected helper name"));
    }

    @Test
    public void missingTableFailsLoudly() throws Exception {
        installStock("termux-open", "rwx------");
        Files.delete(new File(hookDir, "helper-rewrites.tsv").toPath());
        String before = read(helper("termux-open"));
        Result result = run();
        Assert.assertEquals(1, result.exit);
        Assert.assertTrue(result.stderr.contains("ERROR: missing rewrite table"));
        Assert.assertEquals(before, read(helper("termux-open")));
    }

    @Test
    public void readOnlyBinDirectoryIsReportedAndFails() throws Exception {
        Assume.assumeFalse("root ignores directory permissions", "root".equals(System.getProperty("user.name")));
        installStock("termux-open", "rwx------");
        String before = read(helper("termux-open"));
        File bin = new File(prefix, "bin");
        Assert.assertTrue(bin.setWritable(false));
        try {
            Result result = run();
            Assert.assertEquals(1, result.exit);
            Assert.assertTrue(result.stderr.contains("could not adapt bin/termux-open"));
            Assert.assertEquals(before, read(helper("termux-open")));
        } finally {
            bin.setWritable(true);
        }
    }
}
