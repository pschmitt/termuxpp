package com.termux.app;

import dev.pschmitt.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Robolectric integration test of the installer's inputs: the real {@link TermuxConstants} identity and
 * the real packaged resources. It does NOT run the installer, and a fresh device install is not tested
 * here; the installer thread itself needs an Activity and a real prefix.
 */
@RunWith(RobolectricTestRunner.class)
public class TermuxInstallerBootstrapIntegrationTest {

    private static String installerSource() throws IOException {
        for (String path : new String[] {"src/main/java/com/termux/app/TermuxInstaller.java",
            "app/src/main/java/com/termux/app/TermuxInstaller.java"}) {
            File file = new File(path);
            if (file.isFile()) return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        Assume.assumeTrue("TermuxInstaller.java not reachable from " + new File(".").getAbsolutePath(), false);
        return null;
    }

    @Test
    public void theInstallersOwnValuesLoadTheRealTableCompletely() throws IOException {
        Map<String, String> values = TermuxInstaller.helperIdentityValues();
        Assert.assertEquals(TermuxConstants.TERMUX_PACKAGE_NAME, values.get("PACKAGE"));
        Assert.assertEquals(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH, values.get("DATA_DIR"));

        TermuxBootstrapAdapter adapter = TermuxBootstrapAdapter.load(TermuxConstants.TERMUX_PACKAGE_NAME, values);
        if ("com.termux".equals(TermuxConstants.TERMUX_PACKAGE_NAME)) {
            Assert.assertTrue(adapter.isStock());
            return;
        }
        Assert.assertFalse(adapter.isStock());
        Assert.assertEquals(TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH, adapter.dataDir());
        // Every row of the packaged table resolved: nothing was dropped for the app's real constants.
        Assert.assertEquals(51, adapter.rules().size());
    }

    @Test
    public void theRealIdentityAdaptsTheRealStockFixtures() throws IOException {
        TermuxBootstrapAdapter adapter = TermuxBootstrapAdapter.load(TermuxConstants.TERMUX_PACKAGE_NAME,
            TermuxInstaller.helperIdentityValues());
        Assume.assumeFalse(adapter.isStock());
        String login = new String(adapter.adaptEntry("bin/login",
            TermuxHelperRewritesTest.stockFile("bin/login").getBytes(StandardCharsets.ISO_8859_1)), StandardCharsets.ISO_8859_1);
        Assert.assertTrue(login.startsWith("#!" + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH));
        Assert.assertTrue(login.contains("${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"));
    }

    @Test
    public void theTableAndTheHookScriptAreOnTheClasspathOfTheApp() throws IOException {
        Assert.assertFalse(TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE).isEmpty());
        Assert.assertFalse(TermuxHelperRewrites.readResource(TermuxHelperRewrites.HOOK_SCRIPT_RESOURCE).isEmpty());
        // They live in src/main/resources, which Gradle packages into the APK.
        Assert.assertTrue(new File("src/main/resources/termuxpp/helper-rewrites.tsv").isFile()
            || new File("app/src/main/resources/termuxpp/helper-rewrites.tsv").isFile());
    }

    @Test
    public void theInstallerHasNoSilentFallbackAndLoadsBeforeItDeletesAnything() throws IOException {
        String source = installerSource();
        Assert.assertFalse("the empty-table fallback must be gone", source.contains("loadHelperRewrites"));
        Assert.assertFalse("no emptyList fallback", source.contains("Collections.emptyList"));
        Assert.assertFalse("no inline extraction loop", source.contains("ZipInputStream"));
        int load = source.indexOf("TermuxBootstrapAdapter.load(");
        int firstDelete = source.indexOf("FileUtils.deleteFile(\"termux prefix staging directory\"");
        int extract = source.indexOf("TermuxBootstrapExtractor.extract(");
        int move = source.indexOf("TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)");
        int hook = source.indexOf("TermuxHelperRewrites.deployHook(");
        Assert.assertTrue("adapter is loaded", load > 0);
        Assert.assertTrue("adapter loads before the first delete", load < firstDelete);
        Assert.assertTrue("extraction before the staging directory is activated", extract > 0 && extract < move);
        Assert.assertTrue("hook deployed before the staging directory is activated", hook > 0 && hook < move);
        // A hook failure is not swallowed any more.
        Assert.assertFalse(source.contains("Not fatal"));
    }
}
