package com.termux.app;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the files of the stock bootstrap into the files of this app while the bootstrap is extracted.
 *
 * <p>Two layers, deliberately independent of each other:
 * <ol>
 * <li>The mandatory path rewrite: every {@code /data/data/com.termux} in a non-ELF file becomes the
 * data directory of this app. It is part of this class and needs no resource, so no missing or broken
 * table can make the installer skip it.</li>
 * <li>The helper rewrite table ({@code termuxpp/helper-rewrites.tsv}): helpers, login and the login
 * conffile. For the fork identity it is required: {@link #load} throws when it is missing or has an
 * unusable row, so the installer fails before it touches the prefix instead of producing a half adapted
 * one.</li>
 * </ol>
 *
 * <p>Files are handled as bytes (ISO-8859-1 round trip), so text with multibyte characters and files
 * that are not valid UTF-8 pass through byte for byte; only the literal ASCII needles change. Files
 * that contain no needle and no applicable row are returned as the very same array.
 */
final class TermuxBootstrapAdapter {

    static final String STOCK_PACKAGE = "com.termux";
    static final String STOCK_DATA_DIR = "/data/data/com.termux";

    private static final Pattern ASCII_PATH = Pattern.compile("/[A-Za-z0-9._/-]+");

    private final String dataDir;
    private final List<TermuxHelperRewrites.Rule> rules;

    private TermuxBootstrapAdapter(String dataDir, List<TermuxHelperRewrites.Rule> rules) {
        this.dataDir = dataDir;
        this.rules = rules;
    }

    /** The adapter of the stock app: nothing is rewritten, every file stays byte-identical. */
    static TermuxBootstrapAdapter stock() {
        return new TermuxBootstrapAdapter(STOCK_DATA_DIR, Collections.<TermuxHelperRewrites.Rule>emptyList());
    }

    /**
     * The adapter for {@code packageName}. For the stock package this is {@link #stock()}. For any other
     * package the table resource must be readable and complete, and {@code values} must carry the
     * data directory of the app, or this throws an {@link IOException} with the reason.
     */
    @NonNull
    static TermuxBootstrapAdapter load(@NonNull String packageName, @NonNull Map<String, String> values)
        throws IOException {
        return loadResource(packageName, values, TermuxHelperRewrites.TABLE_RESOURCE);
    }

    /** {@link #load} with an explicit resource name; the table is only read for the fork identity. */
    @NonNull
    static TermuxBootstrapAdapter loadResource(@NonNull String packageName, @NonNull Map<String, String> values,
                                               @NonNull String resourceName) throws IOException {
        if (STOCK_PACKAGE.equals(packageName)) return stock();
        checkDataDir(values);
        return fromTemplate(values, TermuxHelperRewrites.readResource(resourceName));
    }

    private static String checkDataDir(Map<String, String> values) throws IOException {
        String dataDir = values.get("DATA_DIR");
        if (dataDir == null || !ASCII_PATH.matcher(dataDir).matches() || dataDir.contains("..")
            || dataDir.contains(STOCK_DATA_DIR)) {
            throw new IOException("Invalid data directory for the fork identity: " + dataDir);
        }
        return dataDir;
    }

    /** The fork adapter for an unrendered table; every problem of the table is an {@link IOException}. */
    @NonNull
    static TermuxBootstrapAdapter fromTemplate(@NonNull Map<String, String> values, @NonNull String tableTemplate)
        throws IOException {
        String dataDir = checkDataDir(values);
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parseStrict(
            TermuxHelperRewrites.render(tableTemplate, values));
        boolean hasIdentity = false;
        boolean hasLogin = false;
        for (TermuxHelperRewrites.Rule rule : rules) {
            // The table is never allowed to disagree with the mandatory rewrite.
            if (rule.kind == TermuxHelperRewrites.Kind.IDENTITY && STOCK_DATA_DIR.equals(rule.stock)) {
                if (!dataDir.equals(rule.fork)) {
                    throw new IOException("The helper rewrite table maps " + STOCK_DATA_DIR + " to " + rule.fork
                        + ", not to " + dataDir);
                }
                hasIdentity = true;
            }
            if ("bin/login".equals(rule.file)) hasLogin = true;
            if (!isAscii(rule.stock) || !isAscii(rule.fork)) {
                throw new IOException("The helper rewrite table has a non-ASCII row: " + rule.stock);
            }
        }
        if (!hasIdentity || !hasLogin || rules.size() < 3) {
            throw new IOException("The helper rewrite table is incomplete (" + rules.size() + " usable rows)");
        }
        return new TermuxBootstrapAdapter(dataDir, rules);
    }

    /** An adapter with explicit rules and no table checks, so tests can show the mandatory rewrite stands alone. */
    static TermuxBootstrapAdapter withRules(String dataDir, List<TermuxHelperRewrites.Rule> rules) {
        return new TermuxBootstrapAdapter(dataDir, rules);
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7e || (text.charAt(i) < 0x20 && text.charAt(i) != '\n')) return false;
        }
        return true;
    }

    boolean isStock() {
        return STOCK_DATA_DIR.equals(dataDir);
    }

    List<TermuxHelperRewrites.Rule> rules() {
        return rules;
    }

    String dataDir() {
        return dataDir;
    }

    /** ELF files are never rewritten. */
    static boolean isElf(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 0x7F && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F';
    }

    /**
     * Zip archives (such as the nixpp seed) are never rewritten either: a replaced path would corrupt
     * them, and turning a large archive into a string can exhaust the app heap.
     */
    static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    /**
     * The extracted content of the bootstrap entry {@code name} (relative to the prefix). Returns
     * {@code bytes} itself when nothing changes. Throws when the adapted file does not pass the checks
     * of the table, for example a login whose preload line changed shape upstream.
     */
    @NonNull
    byte[] adaptEntry(@NonNull String name, @NonNull byte[] bytes) throws IOException {
        if (isStock() || isElf(bytes) || isZip(bytes)) return bytes;

        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        String adapted = text;
        // Mandatory, table independent.
        if (adapted.contains(STOCK_DATA_DIR)) adapted = adapted.replace(STOCK_DATA_DIR, dataDir);
        adapted = TermuxHelperRewrites.apply(rules, name, adapted);

        List<String> problems = TermuxHelperRewrites.verify(rules, name, adapted);
        if (!problems.isEmpty()) {
            throw new IOException("Fork identity check failed: " + problems.get(0)
                + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more)" : ""));
        }
        return adapted.equals(text) ? bytes : adapted.getBytes(StandardCharsets.ISO_8859_1);
    }
}
