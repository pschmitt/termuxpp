package com.termux.app;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the real bootstrap archives (the ones the Gradle {@code downloadBootstraps} task fetches into
 * {@code app/src/main/cpp}) into a temporary directory with the real adapter and the real packaged table.
 * Nothing touches a device. A fresh device install is not tested here.
 *
 * <p>With {@code TERMUXPP_REQUIRE_BOOTSTRAPS=1} (set on the CI and remote runs) a missing archive fails the
 * test instead of skipping it.
 */
public class RealBootstrapExtractionTest {

    private static final String[] ARCHS = {"aarch64", "arm", "i686", "x86_64"};
    private static final String STOCK_DIR = "/data/data/com.termux";
    private static final String FORK_DIR = "/data/data/dev.pschmitt.termux";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static File archive(String arch) {
        for (String dir : new String[] {"src/main/cpp", "app/src/main/cpp"}) {
            File file = new File(dir, "bootstrap-" + arch + ".zip");
            if (file.isFile()) return file;
        }
        if ("1".equals(System.getenv("TERMUXPP_REQUIRE_BOOTSTRAPS"))) {
            Assert.fail("bootstrap-" + arch + ".zip is required but missing (run from the app module after downloadBootstraps)");
        }
        Assume.assumeTrue("bootstrap-" + arch + ".zip not downloaded", false);
        return null;
    }

    /** Files whose adaptation is defined by the helper rewrite table rather than by the plain path rewrite. */
    private static boolean isTableFile(String key) {
        return key.equals("bin/login") || key.equals("etc/termux-login.sh") || key.equals("etc/motd")
            || key.equals("etc/profile.d/init-termux-properties.sh") || key.startsWith("bin/termux-");
    }

    private static boolean isElf(byte[] bytes) {
        return TermuxBootstrapAdapter.isElf(bytes);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static Map<String, byte[]> entries(byte[] zip) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                files.put(entry.getName(), out.toByteArray());
            }
        }
        return files;
    }

    private void checkArch(String arch) throws IOException {
        byte[] zip = Files.readAllBytes(archive(arch).toPath());
        Map<String, byte[]> original = entries(zip);
        original.remove("SYMLINKS.txt");
        byte[] stockNeedle = STOCK_DIR.getBytes(StandardCharsets.US_ASCII);

        // --- fork ---
        File staging = temp.newFolder("fork-" + arch);
        TermuxBootstrapAdapter adapter = TermuxBootstrapAdapter.load("dev.pschmitt.termux",
            TermuxHelperRewritesTest.forkValues());
        TermuxBootstrapExtractor.Result result = TermuxBootstrapExtractor.extract(zip, staging, adapter, (f, m) -> {
            Assert.assertTrue(f.setExecutable(true, true));
        });
        int textWithNeedle = 0;
        for (Map.Entry<String, byte[]> file : original.entrySet()) {
            byte[] extracted = Files.readAllBytes(new File(staging, file.getKey()).toPath());
            if (isElf(file.getValue())) {
                Assert.assertArrayEquals(arch + " " + file.getKey() + " (ELF) must be byte identical",
                    file.getValue(), extracted);
            } else if (contains(file.getValue(), stockNeedle)) {
                textWithNeedle++;
                Assert.assertFalse(arch + " " + file.getKey() + " still has the stock path", contains(extracted, stockNeedle));
                if (!isTableFile(file.getKey())) {
                    // exactly the needle changed and nothing else
                    Assert.assertArrayEquals(arch + " " + file.getKey(), new String(file.getValue(), StandardCharsets.ISO_8859_1)
                        .replace(STOCK_DIR, FORK_DIR).getBytes(StandardCharsets.ISO_8859_1), extracted);
                }
            } else if (!isTableFile(file.getKey())) {
                Assert.assertArrayEquals(arch + " " + file.getKey() + " has nothing to adapt: byte identical",
                    file.getValue(), extracted);
            }
        }
        // An upstream bootstrap (with its dpkg database) must have had something to adapt; the slim
        // TermuxPP outer bootstrap has no stock files at all.
        if (original.containsKey("var/lib/dpkg/status"))
            Assert.assertTrue(arch + ": the bootstrap has text files with the stock path", textWithNeedle > 0);

        if (original.containsKey("etc/profile.d/init-termux-properties.sh")) {
            String init = new String(Files.readAllBytes(new File(staging,
                "etc/profile.d/init-termux-properties.sh").toPath()), StandardCharsets.ISO_8859_1);
            Assert.assertTrue(arch, init.contains("/files/home/.config/termuxpp\n"));
            Assert.assertFalse(arch, init.contains("/files/home/.termux\n"));
            Assert.assertTrue(arch, init.contains("/home/.config/termuxpp/termuxpp.properties\n"));
        }

        if (original.containsKey("etc/motd")) {
            String motd = new String(Files.readAllBytes(new File(staging, "etc/motd").toPath()), StandardCharsets.ISO_8859_1);
            Assert.assertTrue(arch, motd.startsWith("Welcome to TermuxPP!\n"));
            Assert.assertFalse(arch, motd.contains("termux.dev"));
            Assert.assertTrue(arch, motd.contains(" - Install: pkg install <package>\n"));
        }

        if (original.containsKey("bin/login")) {
            String login = new String(Files.readAllBytes(new File(staging, "bin/login").toPath()), StandardCharsets.ISO_8859_1);
            Assert.assertTrue(arch, login.contains("${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"));
            Assert.assertFalse(arch, login.contains(STOCK_DIR));
        }
        Assert.assertTrue(arch, result.adaptedFiles.size() >= textWithNeedle);
        for (TermuxBootstrapExtractor.Symlink link : result.symlinks) {
            Assert.assertFalse(arch + " " + link.target, link.target.contains(STOCK_DIR));
        }
        Assert.assertFalse(result.symlinks.isEmpty());

        // no text file anywhere under staging mentions the stock data dir
        List<String> leftovers = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(staging.toPath())) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                byte[] data = Files.readAllBytes(p);
                if (!isElf(data) && contains(data, stockNeedle)) leftovers.add(staging.toPath().relativize(p).toString());
            }
        }
        Assert.assertTrue(arch + " files still containing the stock path: " + leftovers, leftovers.isEmpty());

        // --- stock: everything byte identical ---
        File stockStaging = temp.newFolder("stock-" + arch);
        TermuxBootstrapExtractor.Result stockResult = TermuxBootstrapExtractor.extract(zip, stockStaging,
            TermuxBootstrapAdapter.stock(), (f, m) -> { });
        for (Map.Entry<String, byte[]> file : original.entrySet()) {
            Assert.assertArrayEquals(arch + " stock " + file.getKey(), file.getValue(),
                Files.readAllBytes(new File(stockStaging, file.getKey()).toPath()));
        }
        Assert.assertTrue(stockResult.adaptedFiles.isEmpty());
        Assert.assertEquals(result.symlinks.size(), stockResult.symlinks.size());
    }

    @Test
    public void aarch64() throws IOException {
        checkArch("aarch64");
    }

    @Test
    public void arm() throws IOException {
        checkArch("arm");
    }

    @Test
    public void i686() throws IOException {
        checkArch("i686");
    }

    @Test
    public void x86_64() throws IOException {
        checkArch("x86_64");
    }
}
