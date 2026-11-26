package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The adapter that the installer uses for every bootstrap file. These tests load the real packaged
 * resource {@code /termuxpp/helper-rewrites.tsv} from the classpath (the same lookup the installer does).
 * They do not run the installer on a device: a fresh install on a device is not tested here.
 */
public class TermuxBootstrapAdapterTest {

    private static final String STOCK_DIR = "/data/data/com.termux";
    private static final String FORK_DIR = "/data/data/dev.pschmitt.termux";

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static TermuxBootstrapAdapter fork() throws IOException {
        return TermuxBootstrapAdapter.load("dev.pschmitt.termux", TermuxHelperRewritesTest.forkValues());
    }

    private static String rawTable() throws IOException {
        return TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE);
    }

    private static void assertRejected(String why, Map<String, String> values, String template) {
        try {
            TermuxBootstrapAdapter.fromTemplate(values, template);
            Assert.fail("should have been rejected: " + why);
        } catch (IOException expected) {
            Assert.assertNotNull(why, expected.getMessage());
        }
    }

    // ---- the real resource ----

    @Test
    public void theRealResourceLoadsWithAllThirtyEightForkRows() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        Assert.assertFalse(adapter.isStock());
        Assert.assertEquals(FORK_DIR, adapter.dataDir());
        int[] counts = new int[TermuxHelperRewrites.Kind.values().length];
        for (TermuxHelperRewrites.Rule rule : adapter.rules()) counts[rule.kind.ordinal()]++;
        Assert.assertEquals(1, counts[TermuxHelperRewrites.Kind.IDENTITY.ordinal()]);
        Assert.assertEquals(32, counts[TermuxHelperRewrites.Kind.RULE.ordinal()]);
        Assert.assertEquals(1, counts[TermuxHelperRewrites.Kind.APPEND.ordinal()]);
        Assert.assertEquals(5, counts[TermuxHelperRewrites.Kind.REQUIRE.ordinal()]);
        Assert.assertEquals(12, counts[TermuxHelperRewrites.Kind.FORBID.ordinal()]);
        Assert.assertEquals(51, adapter.rules().size());
    }

    @Test
    public void theHookScriptResourceIsPackagedToo() throws IOException {
        Assert.assertTrue(TermuxHelperRewrites.readResource(TermuxHelperRewrites.HOOK_SCRIPT_RESOURCE).startsWith("#!"));
    }

    @Test
    public void stockLoadsWithoutATableAndRewritesNothing() throws IOException {
        // A resource name that does not exist: the stock identity must not even look at it.
        TermuxBootstrapAdapter adapter = TermuxBootstrapAdapter.loadResource("com.termux",
            TermuxHelperRewritesTest.stockValues(), "/termuxpp/does-not-exist.tsv");
        Assert.assertTrue(adapter.isStock());
        Assert.assertTrue(adapter.rules().isEmpty());
        Assert.assertEquals(STOCK_DIR, adapter.dataDir());
    }

    // ---- missing and malformed table: the fork fails, it never degrades ----

    @Test
    public void aMissingTableResourceFailsTheForkLoad() {
        try {
            TermuxBootstrapAdapter.loadResource("dev.pschmitt.termux", TermuxHelperRewritesTest.forkValues(),
                "/termuxpp/does-not-exist.tsv");
            Assert.fail("a missing table must not yield an adapter");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Missing resource"));
        }
    }

    @Test
    public void anEmptyOrCommentOnlyTableIsRejected() throws IOException {
        Map<String, String> values = TermuxHelperRewritesTest.forkValues();
        assertRejected("empty", values, "");
        assertRejected("comments only", values, "# nothing\n\n# here\n");
    }

    @Test
    public void aMalformedRowIsRejectedNotSkipped() throws IOException {
        Map<String, String> values = TermuxHelperRewritesTest.forkValues();
        assertRejected("unknown kind", values, rawTable() + "frobnicate\ta\tb\n");
        assertRejected("too few fields", values, rawTable() + "rule\tbin/login\tonly-two\n");
        assertRejected("file outside the whitelist", values, rawTable() + "rule\tetc/passwd\ta\tb\n");
        assertRejected("space instead of tabs", values, rawTable() + "rule bin/login a b\n");
        assertRejected("not idempotent", values, rawTable() + "rule\tbin/login\tx\txx\n");
        assertRejected("no change", values, rawTable() + "rule\tbin/login\tx\tx\n");
    }

    @Test
    public void anUnresolvedPlaceholderIsRejected() throws IOException {
        Map<String, String> values = TermuxHelperRewritesTest.forkValues();
        values.remove("REDIRECT_SO_VAR");
        assertRejected("unresolved @REDIRECT_SO_VAR@", values, rawTable());
        values = TermuxHelperRewritesTest.forkValues();
        values.put("PACKAGE", "");
        assertRejected("empty PACKAGE", values, rawTable());
    }

    @Test
    public void aTableWithoutTheIdentityOrLoginRowIsRejected() throws IOException {
        Map<String, String> values = TermuxHelperRewritesTest.forkValues();
        StringBuilder noIdentity = new StringBuilder();
        StringBuilder noLogin = new StringBuilder();
        for (String line : rawTable().split("\n")) {
            if (!line.startsWith("identity\t")) noIdentity.append(line).append('\n');
            if (!line.contains("\tbin/login\t")) noLogin.append(line).append('\n');
        }
        assertRejected("no identity row", values, noIdentity.toString());
        assertRejected("no login row", values, noLogin.toString());
    }

    @Test
    public void aTableThatDisagreesWithTheDataDirIsRejected() throws IOException {
        Map<String, String> values = TermuxHelperRewritesTest.forkValues();
        String other = rawTable().replace("identity\t/data/data/com.termux\t@DATA_DIR@",
            "identity\t/data/data/com.termux\t/data/data/other.app");
        Assert.assertNotEquals(rawTable(), other);
        assertRejected("identity maps somewhere else", values, other);
    }

    @Test
    public void aNonAsciiRowIsRejected() throws IOException {
        assertRejected("non-ascii", TermuxHelperRewritesTest.forkValues(),
            rawTable() + "rule\tbin/termux-open\tcafé\tcafe\n");
    }

    @Test
    public void anInvalidDataDirIsRejectedForTheFork() {
        String[] bad = {null, "", "relative/path", "/data/data/../x", STOCK_DIR, STOCK_DIR + ".x",
            "/data/data/dév", "/data/data/with space"};
        for (String dir : bad) {
            Map<String, String> values = TermuxHelperRewritesTest.forkValues();
            if (dir == null) values.remove("DATA_DIR"); else values.put("DATA_DIR", dir);
            try {
                TermuxBootstrapAdapter.load("dev.pschmitt.termux", values);
                Assert.fail("data dir should be rejected: " + dir);
            } catch (IOException expected) {
                // fine
            }
        }
    }

    @Test
    public void zipArchivesAreNeverRewritten() throws IOException {
        TermuxBootstrapAdapter adapter = TermuxBootstrapAdapter.withRules(FORK_DIR,
            Collections.<TermuxHelperRewrites.Rule>emptyList());
        byte[] zip = bytes("PK\u0003\u0004 stored " + STOCK_DIR + "/files/usr\n");
        Assert.assertSame(zip, adapter.adaptEntry("share/nixpp/bootstrap-aarch64.zip", zip));
    }

    // ---- the mandatory rewrite stands on its own ----

    @Test
    public void theGenericPathRewriteDoesNotDependOnTheTable() throws IOException {
        // No rows at all: the very case an empty or lost table used to produce.
        TermuxBootstrapAdapter adapter = TermuxBootstrapAdapter.withRules(FORK_DIR,
            Collections.<TermuxHelperRewrites.Rule>emptyList());
        byte[] out = adapter.adaptEntry("etc/profile", bytes("export PREFIX=" + STOCK_DIR + "/files/usr\n"));
        Assert.assertEquals("export PREFIX=" + FORK_DIR + "/files/usr\n", new String(out, StandardCharsets.ISO_8859_1));
        // and for a file name no table row has ever heard of
        out = adapter.adaptEntry("share/doc/anything/README", bytes("see " + STOCK_DIR + "/files\n"));
        Assert.assertEquals("see " + FORK_DIR + "/files\n", new String(out, StandardCharsets.ISO_8859_1));
    }

    @Test
    public void everyOccurrenceInEveryNonElfFileIsRewritten() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        String[] names = {"etc/profile", "etc/apt/sources.list", "var/lib/dpkg/info/bash.list", "bin/termux-open",
            "etc/termux-login.sh", "share/x/y.conf", "lib/pkgconfig/foo.pc"};
        for (String name : names) {
            String text = "a " + STOCK_DIR + "/files/usr\nb " + STOCK_DIR + "\nc " + STOCK_DIR + "/x " + STOCK_DIR + "/y\n";
            String out = new String(adapter.adaptEntry(name, bytes(text)), StandardCharsets.ISO_8859_1);
            Assert.assertFalse(name, out.contains(STOCK_DIR));
            Assert.assertEquals(name, 4, out.split(java.util.regex.Pattern.quote(FORK_DIR), -1).length - 1);
        }
    }

    // ---- representative bootstrap files ----

    @Test
    public void loginGetsThePreloadAndPassesTheCheck() throws IOException {
        byte[] out = fork().adaptEntry("bin/login", bytes(TermuxHelperRewritesTest.stockFile("bin/login")));
        String login = new String(out, StandardCharsets.ISO_8859_1);
        Assert.assertTrue(login, login.contains("export LD_PRELOAD=\"${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}/"));
        Assert.assertFalse(login.contains(STOCK_DIR));
    }

    @Test
    public void loginWhosePreloadLineChangedShapeFailsInsteadOfShippingHalfAdapted() throws IOException {
        String changed = TermuxHelperRewritesTest.stockFile("bin/login").replace("export LD_PRELOAD=\"/", "export LD_PRELOAD=$X/");
        try {
            fork().adaptEntry("bin/login", bytes(changed));
            Assert.fail("a login that cannot be adapted must fail the extraction");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Fork identity check failed"));
        }
    }

    @Test
    public void conffileGetsTheHookBlockAppendedOnce() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        byte[] once = adapter.adaptEntry("etc/termux-login.sh", bytes(TermuxHelperRewritesTest.stockFile("etc/termux-login.sh")));
        Assert.assertTrue(new String(once, StandardCharsets.ISO_8859_1).contains("TERMUX_PKG_REDIRECT_SO"));
        Assert.assertArrayEquals(once, adapter.adaptEntry("etc/termux-login.sh", once));
    }

    @Test
    public void sharedJavaIdentitiesStayUnderTheStockNamespace() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        String reset = new String(adapter.adaptEntry("bin/termux-reset",
            bytes(TermuxHelperRewritesTest.stockHelper("termux-reset"))), StandardCharsets.ISO_8859_1);
        Assert.assertTrue(reset.contains("\"com.termux.termuxam.Am\" stopservice \"dev.pschmitt.termux/com.termux.app.TermuxService\""));
        String open = new String(adapter.adaptEntry("bin/termux-open",
            bytes(TermuxHelperRewritesTest.stockHelper("termux-open"))), StandardCharsets.ISO_8859_1);
        Assert.assertTrue(open.contains("-n \"dev.pschmitt.termux/com.termux.app.TermuxOpenReceiver\""));
        String lock = new String(adapter.adaptEntry("bin/termux-wake-lock",
            bytes(TermuxHelperRewritesTest.stockHelper("termux-wake-lock"))), StandardCharsets.ISO_8859_1);
        Assert.assertTrue(lock.contains("-a \"dev.pschmitt.termux.service_wake_lock\""));
    }

    @Test
    public void everyStockFixtureAdaptsAndTheAdaptedFilesAreStable() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        String[] files = {"bin/login", "etc/termux-login.sh", "bin/pkg", "bin/termux-info", "bin/termux-open",
            "bin/termux-reload-settings", "bin/termux-reset", "bin/termux-setup-storage", "bin/termux-wake-lock",
            "bin/termux-wake-unlock"};
        for (String file : files) {
            byte[] stock = bytes(TermuxHelperRewritesTest.stockFile(file));
            byte[] once = adapter.adaptEntry(file, stock);
            Assert.assertArrayEquals("second pass changes nothing: " + file, once, adapter.adaptEntry(file, once));
        }
    }

    // ---- stock compatibility ----

    @Test
    public void stockReturnsTheVeryArrayForEveryKindOfFile() throws IOException {
        TermuxBootstrapAdapter stock = TermuxBootstrapAdapter.stock();
        String[] names = {"bin/login", "etc/profile", "bin/termux-open", "etc/termux-login.sh", "lib/x.so", "share/a"};
        for (String name : names) {
            byte[] withNeedle = bytes("x " + STOCK_DIR + "/files y\n");
            Assert.assertSame(name, withNeedle, stock.adaptEntry(name, withNeedle));
            byte[] elf = {0x7F, 'E', 'L', 'F', 1, 2, 3};
            Assert.assertSame(name, elf, stock.adaptEntry(name, elf));
        }
        for (String file : new String[] {"bin/login", "etc/termux-login.sh", "bin/pkg", "bin/termux-open"}) {
            byte[] fixture = bytes(TermuxHelperRewritesTest.stockFile(file));
            Assert.assertSame(file, fixture, stock.adaptEntry(file, fixture));
        }
    }

    @Test
    public void theStockPackageThroughTheRealLoaderIsAlsoANoOp() throws IOException {
        TermuxBootstrapAdapter stock = TermuxBootstrapAdapter.load("com.termux", TermuxHelperRewritesTest.stockValues());
        Assert.assertTrue(stock.isStock());
        byte[] data = bytes("keep " + STOCK_DIR + "\n");
        Assert.assertSame(data, stock.adaptEntry("etc/profile", data));
    }

    // ---- byte identity and unknown text ----

    @Test
    public void unicodeAndInvalidUtf8SurviveByteForByte() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        byte[] unicode = ("héllo ✓ 日本 😀 " + STOCK_DIR + "/files ü\n")
            .getBytes(StandardCharsets.UTF_8);
        byte[] out = adapter.adaptEntry("share/unicode.txt", unicode);
        byte[] expected = new String(unicode, StandardCharsets.ISO_8859_1).replace(STOCK_DIR, FORK_DIR)
            .getBytes(StandardCharsets.ISO_8859_1);
        Assert.assertArrayEquals(expected, out);
        Assert.assertEquals("héllo ✓ 日本 😀 " + FORK_DIR + "/files ü\n",
            new String(out, StandardCharsets.UTF_8));

        byte[] invalid = {'a', (byte) 0xff, (byte) 0xfe, (byte) 0x80, (byte) 0xc3, '\n'};
        Assert.assertSame("no needle: the very same array", invalid, adapter.adaptEntry("share/latin1.txt", invalid));
        byte[] mixed = new byte[invalid.length + STOCK_DIR.length()];
        System.arraycopy(invalid, 0, mixed, 0, invalid.length);
        System.arraycopy(STOCK_DIR.getBytes(StandardCharsets.US_ASCII), 0, mixed, invalid.length, STOCK_DIR.length());
        byte[] mixedOut = adapter.adaptEntry("share/mixed", mixed);
        for (int i = 0; i < invalid.length; i++) Assert.assertEquals(invalid[i], mixedOut[i]);
        Assert.assertEquals(FORK_DIR, new String(mixedOut, invalid.length, mixedOut.length - invalid.length,
            StandardCharsets.US_ASCII));
    }

    @Test
    public void everyByteValueSurvivesAFileWithoutNeedles() throws IOException {
        byte[] all = new byte[256 * 4];
        for (int i = 0; i < all.length; i++) all[i] = (byte) i;
        all[0] = '#'; // not an ELF header
        Assert.assertSame(all, fork().adaptEntry("share/all-bytes", all));
        // and with a needle in the middle the rest is untouched
        byte[] needle = STOCK_DIR.getBytes(StandardCharsets.US_ASCII);
        byte[] withNeedle = new byte[all.length + needle.length];
        System.arraycopy(all, 0, withNeedle, 0, 100);
        System.arraycopy(needle, 0, withNeedle, 100, needle.length);
        System.arraycopy(all, 100, withNeedle, 100 + needle.length, all.length - 100);
        byte[] out = fork().adaptEntry("share/all-bytes2", withNeedle);
        Assert.assertEquals(all.length + FORK_DIR.length(), out.length);
        for (int i = 0; i < 100; i++) Assert.assertEquals(all[i], out[i]);
        for (int i = 100; i < all.length; i++) Assert.assertEquals(all[i], out[i + FORK_DIR.length()]);
    }

    @Test
    public void unknownTextWithoutNeedleIsTheSameArray() throws IOException {
        TermuxBootstrapAdapter adapter = fork();
        byte[] text = bytes("echo hello\nexport FOO=bar\n/data/data/some.other.app/files\n");
        Assert.assertSame(text, adapter.adaptEntry("etc/something", text));
        Assert.assertSame(text, adapter.adaptEntry("bin/termux-unknown-helper", text));
        byte[] empty = new byte[0];
        Assert.assertSame(empty, adapter.adaptEntry("etc/empty", empty));
        // a prefix of the needle or the stock package in another path is not a needle
        byte[] near = bytes("/data/data/com.termu /data/user/0/com.termux /data/data/com.termu\n");
        Assert.assertSame(near, adapter.adaptEntry("etc/near", near));
    }

    @Test
    public void elfFilesAreNeverRewrittenEvenWithTheNeedleInside() throws IOException {
        byte[] elf = ("\u007fELF....." + STOCK_DIR + "/files/usr/lib\0").getBytes(StandardCharsets.ISO_8859_1);
        Assert.assertSame(elf, fork().adaptEntry("bin/bash", elf));
        Assert.assertSame(elf, fork().adaptEntry("lib/libfoo.so", elf));
    }

    @Test
    public void aNamedHelperThatIsAnElfIsLeftAlone() throws IOException {
        // termux-open as a compiled binary: no text rule may touch it and the check must not fire.
        byte[] elf = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 'c', 'o', 'm', '.', 't', 'e', 'r', 'm', 'u', 'x'};
        Assert.assertSame(elf, fork().adaptEntry("bin/termux-open", elf));
    }

    @Test
    public void adaptEntryReturnsAnUnmodifiedListOfRules() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = fork().rules();
        try {
            rules.add(rules.get(0));
            Assert.fail("rules must be immutable");
        } catch (UnsupportedOperationException expected) {
            // fine
        }
    }

    @Test
    public void rulesAreIndependentOfTheMapIterationOrder() throws IOException {
        Map<String, String> values = new HashMap<>(TermuxHelperRewritesTest.forkValues());
        Assert.assertEquals(fork().rules().size(), TermuxBootstrapAdapter.fromTemplate(values, rawTable()).rules().size());
    }
}
