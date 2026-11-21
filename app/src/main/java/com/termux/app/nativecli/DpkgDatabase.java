package com.termux.app.nativecli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A read-only view of the dpkg database of the prefix. Nothing here writes to it, and the installer
 * never runs {@code dpkg} or {@code apt}: older TermuxPP builds shipped the {@code termux-api} and
 * {@code termux-am} helpers as Debian packages, and devices keep their entries (and held state) when
 * the packages are no longer built.
 */
public final class DpkgDatabase {

    /** An installed package whose recorded files are (partly) gone. */
    public static final class Package {
        public final String name;
        public final String version;
        public final int listed;
        public final int missing;

        Package(String name, String version, int listed, int missing) {
            this.name = name;
            this.version = version;
            this.listed = listed;
            this.missing = missing;
        }
    }

    private final File prefix;

    public DpkgDatabase(File prefix) {
        this.prefix = prefix;
    }

    private File infoDir() {
        return new File(prefix, "var/lib/dpkg/info");
    }

    /**
     * For each path of {@code wanted} (absolute) that a package lists, the owning package. The
     * {@code .list} files are scanned line by line instead of indexed, which keeps memory flat.
     */
    public Map<String, String> owners(Set<String> wanted) throws IOException {
        Map<String, String> result = new HashMap<>();
        File[] lists = infoDir().listFiles();
        if (lists == null) return result;
        for (File list : lists) {
            String name = list.getName();
            if (!name.endsWith(".list")) continue;
            String pkg = name.substring(0, name.length() - ".list".length());
            int colon = pkg.indexOf(':');
            if (colon > 0) pkg = pkg.substring(0, colon);
            try (BufferedReader reader = open(list)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (wanted.contains(line)) result.putIfAbsent(line, pkg);
                }
            }
        }
        return result;
    }

    /**
     * The named packages that dpkg considers installed although some of their files no longer exist.
     * Only reported, never repaired.
     */
    public List<Package> stale(Set<String> names) throws IOException {
        File status = new File(prefix, "var/lib/dpkg/status");
        if (!status.isFile()) return Collections.emptyList();
        Map<String, String> installed = new TreeMap<>();
        try (BufferedReader reader = open(status)) {
            String name = null;
            String version = "";
            boolean ok = false;
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null || line.isEmpty()) {
                    if (name != null && ok && names.contains(name)) installed.put(name, version);
                    name = null;
                    version = "";
                    ok = false;
                    if (line == null) break;
                    continue;
                }
                if (line.startsWith("Package: ")) name = line.substring(9).trim();
                else if (line.startsWith("Version: ")) version = line.substring(9).trim();
                else if (line.startsWith("Status: ")) ok = line.endsWith(" installed");
            }
        }
        List<Package> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : installed.entrySet()) {
            File list = new File(infoDir(), entry.getKey() + ".list");
            int listed = 0;
            int missing = 0;
            if (list.isFile()) {
                try (BufferedReader reader = open(list)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        File file = new File(line);
                        if (!line.startsWith("/") || file.isDirectory()) continue;
                        listed++;
                        if (!exists(file)) missing++;
                    }
                }
            }
            if (missing > 0) result.add(new Package(entry.getKey(), entry.getValue(), listed, missing));
        }
        return result;
    }

    private static boolean exists(File file) {
        // A dangling symlink still counts as present: dpkg recorded the link, not its target.
        return file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath());
    }

    private static BufferedReader open(File file) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
    }
}
