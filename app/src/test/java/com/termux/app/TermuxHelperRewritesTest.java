package com.termux.app;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TermuxHelperRewritesTest {

    static final String[] HELPERS = {
        "termux-info", "termux-open", "termux-reload-settings", "termux-reset",
        "termux-setup-storage", "termux-wake-lock", "termux-wake-unlock",
    };

    /** Stock identity values: the rewrites must be no-ops. */
    static Map<String, String> stockValues() {
        Map<String, String> values = new HashMap<>();
        values.put("PACKAGE", "com.termux");
        values.put("RELOAD_STYLE_ACTION", "com.termux.app.reload_style");
        values.put("WAKE_LOCK_ACTION", "com.termux.service_wake_lock");
        values.put("WAKE_UNLOCK_ACTION", "com.termux.service_wake_unlock");
        values.put("DATA_DIR", "/data/data/com.termux");
        // No redirect library and no fork guard for the stock identity.
        return values;
    }

    static Map<String, String> forkValues() {
        Map<String, String> values = new HashMap<>();
        values.put("PACKAGE", "dev.pschmitt.termux");
        values.put("RELOAD_STYLE_ACTION", "dev.pschmitt.termux.app.reload_style");
        values.put("WAKE_LOCK_ACTION", "dev.pschmitt.termux.service_wake_lock");
        values.put("WAKE_UNLOCK_ACTION", "dev.pschmitt.termux.service_wake_unlock");
        values.put("DATA_DIR", "/data/data/dev.pschmitt.termux");
        values.put("REDIRECT_SO_VAR", "TERMUX_PKG_REDIRECT_SO");
        values.put("CONFIG_DIR_NAME", ".config/termuxpp");
        values.put("PRODUCT_NAME", "TermuxPP");
        values.put("REPO_URL", "https://github.com/pschmitt/termuxpp");
        values.put("DOCS_URL", "https://github.com/pschmitt/termuxpp/tree/main/docs/en");
        values.put("UPSTREAM_PACKAGES_ISSUES_URL", "https://github.com/termux/termux-packages/issues");
        values.put("FORK_ONLY", "fork");
        return values;
    }

    /** Other real stock termux-tools files used as fixtures, by path relative to the prefix. */
    static final String[] STOCK_FILES = {"bin/login", "etc/termux-login.sh", "bin/pkg", "etc/motd",
        "etc/profile.d/init-termux-properties.sh"};

    static String stockHelper(String name) throws IOException {
        return TermuxHelperRewrites.readResource("/termuxpp/stock/" + name);
    }

    /** A real stock file of termux-tools by its path relative to the prefix, such as bin/login. */
    static String stockFile(String relativePath) throws IOException {
        return stockHelper(relativePath.substring(relativePath.lastIndexOf('/') + 1));
    }

    static List<TermuxHelperRewrites.Rule> forkRules() throws IOException {
        return TermuxHelperRewrites.parse(TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues()));
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void everyStockHelperIsRewrittenAndNothingStockRemains() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String name : HELPERS) {
            String stock = stockHelper(name);
            String adapted = TermuxHelperRewrites.apply(rules, "bin/" + name, stock);
            Assert.assertNotEquals(name + " should change", stock, adapted);
            for (String line : adapted.split("\n")) {
                Assert.assertFalse(name + ": " + line, line.contains("\"com.termux/")
                    || line.contains("\"com.termux.service_")
                    || line.contains("\"com.termux.app.reload_style\"")
                    || line.contains("echo -n \"com.termux\"")
                    || line.contains("\"com.termux\" >"));
            }
        }
    }

    @Test
    public void javaClassNamesStayUnderTheStockNamespace() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String reset = TermuxHelperRewrites.apply(rules, "bin/termux-reset", stockHelper("termux-reset"));
        Assert.assertTrue(reset.contains("\"com.termux.termuxam.Am\" stopservice \"dev.pschmitt.termux/com.termux.app.TermuxService\""));
        String open = TermuxHelperRewrites.apply(rules, "bin/termux-open", stockHelper("termux-open"));
        Assert.assertTrue(open.contains("-n \"dev.pschmitt.termux/com.termux.app.TermuxOpenReceiver\""));
        String lock = TermuxHelperRewrites.apply(rules, "bin/termux-wake-lock", stockHelper("termux-wake-lock"));
        Assert.assertTrue(lock.contains("-a \"dev.pschmitt.termux.service_wake_lock\""));
        Assert.assertTrue(lock.contains("\"dev.pschmitt.termux/com.termux.app.TermuxService\""));
        String storage = TermuxHelperRewrites.apply(rules, "bin/termux-setup-storage", stockHelper("termux-setup-storage"));
        Assert.assertTrue(storage.contains("--es \"dev.pschmitt.termux.app.reload_style\" \"storage\""));
        Assert.assertTrue(storage.contains(" \"dev.pschmitt.termux\" > /dev/null"));
    }

    @Test
    public void rewritingIsIdempotent() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String name : HELPERS) {
            String once = TermuxHelperRewrites.apply(rules, "bin/" + name, stockHelper(name));
            Assert.assertEquals(name, once, TermuxHelperRewrites.apply(rules, "bin/" + name, once));
        }
    }

    @Test
    public void stockIdentityLeavesEveryHelperByteIdentical() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parse(TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), stockValues()));
        Assert.assertTrue(rules.isEmpty());
        for (String name : HELPERS) {
            String stock = stockHelper(name);
            Assert.assertEquals(stock, TermuxHelperRewrites.apply(rules, "bin/" + name, stock));
        }
        for (String file : STOCK_FILES) {
            String stock = stockFile(file);
            Assert.assertEquals(file, stock, TermuxHelperRewrites.apply(rules, file, stock));
        }
    }

    @Test
    public void unknownHelpersAndContentAreUntouched() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String other = "#!/bin/sh\nam start -n \"com.termux/com.termux.app.TermuxOpenReceiver\"\n";
        Assert.assertEquals(other, TermuxHelperRewrites.apply(rules, "bin/termux-something-else", other));
        Assert.assertEquals(other, TermuxHelperRewrites.apply(rules, null, other));
        String unrelated = "#!/bin/sh\necho hello\n";
        for (String name : HELPERS) {
            Assert.assertEquals(unrelated, TermuxHelperRewrites.apply(rules, "bin/" + name, unrelated));
        }
        // A rule only applies to its own helper (the identity rows apply to every file, the rules do not).
        String open = stockHelper("termux-open");
        String asReset = TermuxHelperRewrites.apply(rules, "bin/termux-reset", open);
        Assert.assertTrue(asReset.contains("-n \"com.termux/com.termux.app.TermuxOpenReceiver\""));
    }

    @Test
    public void everyRowMatchesTheRealStockScripts() throws IOException {
        // Guards the table against drifting from the upstream scripts: a row that matches nothing is dead.
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        int[] counts = new int[TermuxHelperRewrites.Kind.values().length];
        for (TermuxHelperRewrites.Rule rule : rules) {
            counts[rule.kind.ordinal()]++;
            switch (rule.kind) {
                case IDENTITY:
                    Assert.assertTrue(stockFile("bin/login").contains(rule.stock));
                    break;
                case RULE:
                    String intermediate = TermuxHelperRewrites.apply(rules.subList(0, rules.indexOf(rule)), rule.file, stockFile(rule.file));
                    String previous = previousPropertiesHelper(rule.file);
                    Assert.assertTrue(rule.file + ": " + rule.stock,
                        stockFile(rule.file).contains(rule.stock) || intermediate.contains(rule.stock) || previous.contains(rule.stock));
                    break;
                case APPEND:
                    Assert.assertFalse(rule.file, stockFile(rule.file).contains(rule.stock));
                    break;
                case REQUIRE:
                    Assert.assertTrue(rule.file + ": " + rule.stock,
                        TermuxHelperRewrites.apply(rules, rule.file, stockFile(rule.file)).contains(rule.stock));
                    break;
                case FORBID:
                    Assert.assertTrue(rule.file + ": " + rule.stock, stockFile(rule.file).contains(rule.stock));
                    break;
            }
        }
        Assert.assertEquals(1, counts[TermuxHelperRewrites.Kind.IDENTITY.ordinal()]);
        Assert.assertEquals(32, counts[TermuxHelperRewrites.Kind.RULE.ordinal()]);
        Assert.assertEquals(1, counts[TermuxHelperRewrites.Kind.APPEND.ordinal()]);
        Assert.assertEquals(5, counts[TermuxHelperRewrites.Kind.REQUIRE.ordinal()]);
        Assert.assertEquals(12, counts[TermuxHelperRewrites.Kind.FORBID.ordinal()]);
    }

    private String previousPropertiesHelper(String file) throws IOException {
        String home = "/data/data/dev.pschmitt.termux/files/home";
        return stockFile(file).replace("/data/data/com.termux", "/data/data/dev.pschmitt.termux")
            .replace("~/.termux/termux.properties", "~/.config/termuxpp/termux.properties")
            .replace("/home/.termux/termux.properties ]; then", "/home/.termux/termux.properties ] && [ ! -e " + home + "/.config/termuxpp/termux.properties ]; then")
            .replace("/home/.termux\n", "/home/.config/termuxpp\n")
            .replace("/home/.termux/\n", "/home/.config/termuxpp/\n");
    }

    @Test
    public void previousPropertiesHelpersUpgradeIdempotently() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String file : new String[]{"etc/profile.d/init-termux-properties.sh", "bin/termux-reload-settings"}) {
            String upgraded = TermuxHelperRewrites.apply(rules, file, previousPropertiesHelper(file));
            assertContainsNewFilename(file, upgraded);
            Assert.assertEquals(upgraded, TermuxHelperRewrites.apply(rules, file, upgraded));
        }
    }

    private void assertContainsNewFilename(String file, String text) {
        // The example settings are still copied as termuxpp.properties, which the app migrates to YAML; the
        // reload help names the settings file the app reads.
        Assert.assertTrue(text, text.contains(file.endsWith("termux-reload-settings")
            ? "/.config/termuxpp/config.yaml" : "/.config/termuxpp/termuxpp.properties"));
    }

    @Test
    public void everyAdaptedStockFileSatisfiesTheVerification() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String name : HELPERS) {
            String adapted = TermuxHelperRewrites.apply(rules, "bin/" + name, stockHelper(name));
            Assert.assertEquals(name, Collections.emptyList(), TermuxHelperRewrites.verify(rules, "bin/" + name, adapted));
        }
        for (String file : new String[] {"bin/login", "etc/termux-login.sh"}) {
            String adapted = TermuxHelperRewrites.apply(rules, file, stockFile(file));
            Assert.assertEquals(file, Collections.emptyList(), TermuxHelperRewrites.verify(rules, file, adapted));
            // The unadapted stock file must fail: that is what the check is for.
            Assert.assertFalse(file, TermuxHelperRewrites.verify(rules, file, stockFile(file)).isEmpty());
        }
    }

    @Test
    public void loginKeepsTheRedirectPreloadAndTheForkPaths() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String login = TermuxHelperRewrites.apply(rules, "bin/login", stockFile("bin/login"));
        Assert.assertTrue(login.startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/sh\n"));
        Assert.assertFalse(login.contains("/data/data/com.termux"));
        Assert.assertTrue(login.contains("export LD_PRELOAD=\"${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"
            + "/data/data/dev.pschmitt.termux/files/usr/lib/libtermux-exec-ld-preload.so\""));
        Assert.assertTrue(login.contains("export LD_PRELOAD=\"${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"
            + "/data/data/dev.pschmitt.termux/files/usr/lib/libtermux-exec.so\""));
        // The library path itself is never written down.
        Assert.assertFalse(login.contains("libtermux-pkg-redirect"));
        Assert.assertEquals(login, TermuxHelperRewrites.apply(rules, "bin/login", login));
    }

    @Test
    public void conffileGetsTheRedirectPreloadExactlyOnce() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String stock = stockFile("etc/termux-login.sh");
        String adapted = TermuxHelperRewrites.apply(rules, "etc/termux-login.sh", stock);
        Assert.assertTrue(adapted.startsWith("##\n## This script is sourced by "
            + "/data/data/dev.pschmitt.termux/files/usr/bin/login before executing shell.\n##\n"));
        Assert.assertTrue(adapted.endsWith("\nif [ -n \"${TERMUX_PKG_REDIRECT_SO:-}\" ] && [ -f \"$TERMUX_PKG_REDIRECT_SO\" ]; then\n"
            + "    case \":${LD_PRELOAD:-}:\" in\n"
            + "        *\":$TERMUX_PKG_REDIRECT_SO:\"*) ;;\n"
            + "        *) export LD_PRELOAD=\"$TERMUX_PKG_REDIRECT_SO${LD_PRELOAD:+:$LD_PRELOAD}\" ;;\n"
            + "    esac\nfi\n"));
        Assert.assertEquals(adapted, TermuxHelperRewrites.apply(rules, "etc/termux-login.sh", adapted));
    }

    @Test
    public void identityRewritesAnyFileOfTheBootstrap() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String pkg = TermuxHelperRewrites.apply(rules, "bin/pkg", stockFile("bin/pkg"));
        Assert.assertTrue(pkg.startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/bash\n"));
        Assert.assertFalse(pkg.contains("install-prefix:/data/data/com.termux"));
        Assert.assertEquals("/data/data/dev.pschmitt.termux/files/usr/etc/motd",
            TermuxHelperRewrites.apply(rules, "etc/x", "/data/data/com.termux/files/usr/etc/motd"));
    }

    @Test
    public void verificationReportsAnUpstreamChange() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        // A new stock reference in a helper, and a login whose preload line changed shape.
        String open = TermuxHelperRewrites.apply(rules, "bin/termux-open",
            stockHelper("termux-open") + "am broadcast -n \"com.termux/com.termux.app.New\"\n");
        Assert.assertEquals(1, TermuxHelperRewrites.verify(rules, "bin/termux-open", open).size());
        String login = TermuxHelperRewrites.apply(rules, "bin/login",
            stockFile("bin/login").replace("export LD_PRELOAD=\"/", "export LD_PRELOAD=$X/"));
        Assert.assertEquals(1, TermuxHelperRewrites.verify(rules, "bin/login", login).size());
    }

    @Test
    public void loginLooksUpMotdAndShellInTheConfigDirsInOrder() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String login = TermuxHelperRewrites.apply(rules, "bin/login", stockFile("bin/login"));
        Assert.assertFalse(login.contains("~/.termux/"));
        int order = login.indexOf("\"$HOME/.config/termuxpp/motd.sh\" \"$HOME/.config/termux/motd.sh\" \"$HOME/.termux/motd.sh\"");
        Assert.assertTrue(order >= 0);
        Assert.assertTrue(login.contains("\"$HOME/.config/termuxpp/shell\" \"$HOME/.config/termux/shell\" \"$HOME/.termux/shell\""));
        Assert.assertTrue(login.contains("export SHELL=\"$(realpath \"$termuxpp_shell\")\""));
        Assert.assertEquals(login, TermuxHelperRewrites.apply(rules, "bin/login", login));
        Assert.assertEquals(Collections.emptyList(), TermuxHelperRewrites.verify(rules, "bin/login", login));
    }

    @Test
    public void loginLookupFragmentsPickTheFirstExistingFile() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String login = TermuxHelperRewrites.apply(rules, "bin/login", stockFile("bin/login"));
        // The motd and shell lookup fragments of the adapted login, run for real against a temporary HOME.
        String motdFragment = fragment(login, "for termuxpp_motd in", "done;");
        File home = temp.newFolder("login-home");
        File pp = new File(home, ".config/termuxpp");
        File xdg = new File(home, ".config/termux");
        File old = new File(home, ".termux");
        Assert.assertTrue(pp.mkdirs() && xdg.mkdirs() && old.mkdirs());
        Assert.assertEquals("", runLookup(home, motdFragment, "termuxpp_motd"));
        Files.write(new File(old, "motd.sh").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(old.getPath() + "/motd.sh", runLookup(home, motdFragment, "termuxpp_motd"));
        Files.write(new File(xdg, "motd.sh").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(xdg.getPath() + "/motd.sh", runLookup(home, motdFragment, "termuxpp_motd"));
        Files.write(new File(pp, "motd.sh").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(pp.getPath() + "/motd.sh", runLookup(home, motdFragment, "termuxpp_motd"));
    }

    private static String fragment(String script, String start, String end) {
        int from = script.indexOf(start);
        Assert.assertTrue(start, from >= 0);
        int to = script.indexOf(end, from);
        Assert.assertTrue(end, to > from);
        return script.substring(from, to + end.length());
    }

    /** Runs the lookup fragment with sh and prints the variable it selected, or nothing if none matched. */
    private static String runLookup(File home, String fragment, String variable) throws IOException {
        String script = fragment + "\n[ -f \"$" + variable + "\" ] && printf '%s' \"$" + variable + "\"\n";
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script);
        builder.environment().put("HOME", home.getPath());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] out = readAll(process.getInputStream());
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }

    @Test
    public void reloadSettingsHelpNamesTheCanonicalConfigDir() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String reload = TermuxHelperRewrites.apply(rules, "bin/termux-reload-settings", stockHelper("termux-reload-settings"));
        Assert.assertTrue(reload.contains("~/.config/termuxpp/config.yaml"));
        Assert.assertTrue(reload.contains("~/.config/termuxpp/colors.properties"));
        Assert.assertTrue(reload.contains("~/.config/termuxpp/font.ttf"));
        Assert.assertFalse(reload.contains("~/.termux/"));
        Assert.assertEquals(reload, TermuxHelperRewrites.apply(rules, "bin/termux-reload-settings", reload));
    }

    @Test
    public void incompleteIdentityFailsSafe() {
        String template = "rule\tbin/termux-open\t-n \"com.termux/x\"\t-n \"@PACKAGE@/x\"\n";
        Assert.assertTrue(TermuxHelperRewrites.parse(TermuxHelperRewrites.render(template, new HashMap<>())).isEmpty());
        Map<String, String> empty = new HashMap<>();
        empty.put("PACKAGE", "");
        Assert.assertTrue(TermuxHelperRewrites.parse(TermuxHelperRewrites.render(template, empty)).isEmpty());
    }

    @Test
    public void malformedAndNonWhitelistedRulesAreDropped() {
        String table = "# comment\n"
            + "rule\tbin/termux-open\tstock\n"
            + "rule\tbin/../etc/passwd\tstock\tfork\n"
            + "rule\tbin/sh\tstock\tfork\n"
            + "append\tetc/profile\tmarker\ttext\n"
            + "rule\tbin/termux-open\tprefix\tprefix-x\n"
            + "identity\tsame\tsame\n"
            + "identity\tonly\n"
            + "require\tbin/login\ttext\n"
            + "forbid\tbin/login\ttext\t@FORK_ONLY@\n"
            + "rule\tbin/termux-open\t\tfork\n"
            + "rule\tbin/termux-open\tsame\tsame\n"
            + "junk\tbin/termux-open\tstock\tfork\n"
            + "rule\tbin/termux-open\tstock\tfork\n";
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parse(table);
        // Only the last rule survives; "prefix" -> "prefix-x" would match its own result and is dropped.
        Assert.assertEquals(1, rules.size());
        Assert.assertEquals("fork", rules.get(0).fork);
    }

    @Test
    public void theMotdAddressesOnlyRecognisableStockLines() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String stock = stockFile("etc/motd");
        String adapted = TermuxHelperRewrites.apply(rules, "etc/motd", stock);
        Assert.assertTrue(adapted.startsWith("Welcome to TermuxPP!\n\nDocs:       https://github.com/pschmitt/termuxpp/tree/main/docs/en\n"
            + "Source:     https://github.com/pschmitt/termuxpp\n\nWorking with packages:\n"));
        Assert.assertTrue(adapted.endsWith("try 'termux-change-repo' command.\n\n"
            + "Package issues (upstream Termux): https://github.com/termux/termux-packages/issues\n"));
        Assert.assertFalse(adapted.contains("termux.dev"));
        Assert.assertFalse(adapted.contains("Donate"));
        Assert.assertFalse(adapted.contains("Community"));
        // The package commands are upstream's and stay as they are.
        Assert.assertTrue(adapted.contains(" - Install: pkg install <package>\n"));
        Assert.assertTrue(adapted.contains(" - Root:    pkg install root-repo\n"));
        Assert.assertEquals(adapted, TermuxHelperRewrites.apply(rules, "etc/motd", adapted));

        // Text that is not a stock motd is never rewritten, and the motd rules do not touch other files.
        String custom = "Welcome to my phone\nDocs: https://example.org\n";
        Assert.assertEquals(custom, TermuxHelperRewrites.apply(rules, "etc/motd", custom));
        Assert.assertEquals(stock, TermuxHelperRewrites.apply(rules, "bin/termux-info", stock));
        // The motd has no checks: a custom motd must never make the hook fail.
        Assert.assertTrue(TermuxHelperRewrites.verify(rules, "etc/motd", custom).isEmpty());
        Assert.assertTrue(TermuxHelperRewrites.verify(rules, "etc/motd", adapted).isEmpty());
    }

    @Test
    public void theExampleSettingsGoToTheCanonicalDirAndNeverToTheLegacyOne() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String file = "etc/profile.d/init-termux-properties.sh";
        String stock = stockFile(file);
        String adapted = TermuxHelperRewrites.apply(rules, file, stock);
        String home = "/data/data/dev.pschmitt.termux/files/home";
        // Nothing is created while the canonical file (or either stock place) exists.
        Assert.assertTrue(adapted.contains("[ ! -e " + home + "/.config/termuxpp/termuxpp.properties ]"));
        Assert.assertTrue(adapted.contains("[ ! -e " + home + "/.config/termuxpp/termux.properties ]"));
        // Nor once the settings are in config.yaml, which the app migrates the example to.
        Assert.assertTrue(adapted.contains("[ ! -e " + home + "/.config/termuxpp/config.yaml ]; then\n"));
        Assert.assertTrue(adapted.contains("[ ! -f " + home + "/.config/termux/termux.properties ]"));
        Assert.assertTrue(adapted.contains("[ ! -e " + home + "/.termux/termux.properties ]"));
        // The example is copied to the canonical dir, never to ~/.termux.
        Assert.assertTrue(adapted.contains("\tmkdir -p " + home + "/.config/termuxpp\n"));
        Assert.assertTrue(adapted.contains(" " + home + "/.config/termuxpp/termuxpp.properties\n"));
        Assert.assertFalse(adapted.contains("/files/home/.termux\n"));
        Assert.assertFalse(adapted.contains("com.termux"));
        // The example itself still comes from the packaged examples.
        Assert.assertTrue(adapted.contains("/files/usr/share/examples/termux/termux.properties "));
        Assert.assertEquals(adapted, TermuxHelperRewrites.apply(rules, file, adapted));

        // A customised script is left alone, the rules do not touch other files and nothing is checked.
        String custom = "# mine\n[ -f ~/.config/termuxpp/termux.properties ] || touch ~/.config/termuxpp/termux.properties\n";
        Assert.assertEquals(custom, TermuxHelperRewrites.apply(rules, file, custom));
        // Only the identity rows (data directory) apply to other files, not the settings rules.
        Assert.assertTrue(TermuxHelperRewrites.apply(rules, "etc/termux-login.sh", stock)
            .contains("mkdir -p " + home + "/.termux\n"));
        Assert.assertTrue(TermuxHelperRewrites.verify(rules, file, custom).isEmpty());
    }

    @Test
    public void deployHookWritesTheHookTableAndAptConfig() throws IOException {
        File staging = temp.newFolder("usr.staging");
        TermuxHelperRewrites.deployHook(staging, "/data/data/dev.pschmitt.termux/files/usr", forkValues());

        String script = new String(Files.readAllBytes(new File(staging, "libexec/termuxpp/adapt-helpers").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(script.startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/bash\n"));
        Assert.assertTrue(new File(staging, "libexec/termuxpp/adapt-helpers").canExecute());

        String table = new String(Files.readAllBytes(new File(staging, "libexec/termuxpp/helper-rewrites.tsv").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(table.startsWith("prefix\t/data/data/dev.pschmitt.termux/files/usr\n"));
        Assert.assertEquals(forkRules().size(), TermuxHelperRewrites.parse(table).size());
        Assert.assertFalse(table.contains("@PACKAGE@"));
        Assert.assertFalse(table.contains("@DATA_DIR@"));

        String apt = new String(Files.readAllBytes(new File(staging, "etc/apt/apt.conf.d/99termuxpp-helpers").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(apt.contains("DPkg::Post-Invoke { \"/data/data/dev.pschmitt.termux/files/usr/bin/bash "
            + "/data/data/dev.pschmitt.termux/files/usr/libexec/termuxpp/adapt-helpers\"; };"));
        // A failing hook must make apt report an error.
        Assert.assertFalse(apt.contains("|| true"));
    }

    @Test
    public void refreshHookReplacesOnlyWhatChangedAndLeavesNoWorkDirectory() throws IOException {
        String prefixPath = "/data/data/dev.pschmitt.termux/files/usr";
        File prefix = temp.newFolder("usr.live");
        TermuxHelperRewrites.deployHook(prefix, prefixPath, forkValues());

        // Up to date: nothing changes.
        File work = new File(temp.getRoot(), "usr.hook-refresh");
        Assert.assertFalse(TermuxHelperRewrites.refreshHook(work, prefix, prefixPath, forkValues()));
        Assert.assertFalse(work.exists());

        // An older table from a previous app version is replaced, the others are left alone.
        File table = new File(prefix, "libexec/termuxpp/helper-rewrites.tsv");
        Files.write(table.toPath(), "prefix\t/old\n".getBytes(StandardCharsets.UTF_8));
        Assert.assertTrue(TermuxHelperRewrites.refreshHook(work, prefix, prefixPath, forkValues()));
        Assert.assertFalse(work.exists());
        String refreshed = new String(Files.readAllBytes(table.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(refreshed.startsWith("prefix\t" + prefixPath + "\n"));
        Assert.assertEquals(forkRules().size(), TermuxHelperRewrites.parse(refreshed).size());
        Assert.assertTrue(new File(prefix, "libexec/termuxpp/adapt-helpers").canExecute());

        // A prefix without a hook (never deployed) gets one.
        File bare = temp.newFolder("usr.bare");
        Assert.assertTrue(TermuxHelperRewrites.refreshHook(work, bare, prefixPath, forkValues()));
        Assert.assertTrue(new File(bare, "etc/apt/apt.conf.d/99termuxpp-helpers").isFile());
    }

    @Test
    public void unsafePrefixesAreRefused() throws IOException {
        File staging = temp.newFolder("usr.staging2");
        for (String prefix : Arrays.asList("relative/path", "/data/../etc", "/data/a b", "/data/a\"b", "/data/a\nb", "")) {
            try {
                TermuxHelperRewrites.deployHook(staging, prefix, forkValues());
                Assert.fail("accepted " + prefix);
            } catch (IOException expected) {
                // refused
            }
        }
        Assert.assertEquals(0, staging.list().length);
    }

    @Test
    public void hookTableRendersTheSameRulesAsTheInstaller() throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues());
        Assert.assertEquals(forkRules().size(),
            TermuxHelperRewrites.parse(TermuxHelperRewrites.hookTable("/p", rendered)).size());
    }

    @Test
    public void strictParseAcceptsTheRealForkTableWithThirtyEightRows() throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues());
        Assert.assertEquals(51, TermuxHelperRewrites.parseStrict(rendered).size());
        Assert.assertEquals(TermuxHelperRewrites.parse(rendered).size(), TermuxHelperRewrites.parseStrict(rendered).size());
    }

    @Test
    public void strictParseRejectsEveryRowThatLenientParseWouldDrop() throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues());
        String[] bad = {"bogus\tx\ty\n", "rule\tbin/login\tonly\n", "rule\tbin/evil\tx\ty\n",
            "rule\tbin/login\tx\tx\n", "rule\tbin/login\tx\txx\n", "rule\tbin/login\t@UNRESOLVED@\ty\n",
            "identity\t\tx\n", "append\tbin/login\t\tx\n"};
        for (String row : bad) {
            Assert.assertEquals(row, TermuxHelperRewrites.parse(rendered).size(),
                TermuxHelperRewrites.parse(rendered + row).size());
            try {
                TermuxHelperRewrites.parseStrict(rendered + row);
                Assert.fail("strict parse should reject: " + row);
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Unusable row"));
            }
        }
    }

    @Test
    public void strictParseOfTheStockRenderingRejectsTheForkRowsThatVanish() throws IOException {
        // The stock rendering leaves @FORK_ONLY@ and @REDIRECT_SO_VAR@ unresolved: parse() drops those rows by
        // design (stock stays untouched), parseStrict() is for the fork identity only and says so.
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), stockValues());
        try {
            TermuxHelperRewrites.parseStrict(rendered);
            Assert.fail("the stock rendering is not a complete fork table");
        } catch (IOException expected) {
            // fine
        }
    }

    @Test
    public void deployHookRefusesAnUnresolvablePlaceholderBeforeWritingAnything() throws IOException {
        File staging = temp.newFolder("staging-strict");
        Map<String, String> values = forkValues();
        values.remove("REDIRECT_SO_VAR");
        try {
            TermuxHelperRewrites.deployHook(staging, "/data/data/dev.pschmitt.termux/files/usr", values);
            Assert.fail("deployHook must refuse");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Unusable row"));
        }
        Assert.assertEquals(0, staging.list().length);
    }
}
