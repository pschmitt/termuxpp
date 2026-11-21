package com.termux.app.nativecli;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The list of files that the APK installs into the native prefix, read from
 * {@code assets/tpp-native/manifest.json}.
 *
 * <p>Every entry names a destination below the prefix and one of three sources:
 * <ul>
 *   <li>{@code asset}: a file of the APK assets, copied (with {@code @TERMUX_PREFIX@} replaced when
 *       {@code subst} is set);</li>
 *   <li>{@code lib}: a native executable of the APK, named {@code lib*.so} so that Android extracts it
 *       into the native library directory. The destination becomes a symlink to it, which keeps
 *       following the APK across updates. This is where C and Rust Android binaries plug in. An
 *       {@code optional} entry is skipped when the build has no such library (another ABI, a build
 *       without the Rust crate);</li>
 *   <li>{@code symlink}: a plain symlink with a relative or absolute {@code target}.</li>
 * </ul>
 */
public final class NativeManifest {

    public static final String PREFIX_TOKEN = "@TERMUX_PREFIX@";

    public enum Type { ASSET, LIB, SYMLINK }

    public static final class Entry {
        public final String path;
        public final Type type;
        /** Asset path, library file name or symlink target. */
        public final String source;
        public final boolean executable;
        public final boolean substitute;
        public final boolean optional;

        public Entry(String path, Type type, String source, boolean executable, boolean substitute,
                     boolean optional) {
            this.path = path;
            this.type = type;
            this.source = source;
            this.executable = executable;
            this.substitute = substitute;
            this.optional = optional;
        }
    }

    public final int version;
    public final List<Entry> entries;

    private NativeManifest(int version, List<Entry> entries) {
        this.version = version;
        this.entries = Collections.unmodifiableList(entries);
    }

    public static NativeManifest parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int version = root.getInt("version");
        if (version != 1) throw new JSONException("Unsupported manifest version " + version);
        JSONArray array = root.getJSONArray("entries");
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String path = item.getString("path");
            if (!isSafeRelativePath(path)) throw new JSONException("Unsafe path: " + path);
            if (!seen.add(path)) throw new JSONException("Duplicate path: " + path);
            Type type;
            String source;
            switch (item.getString("type")) {
                case "asset":
                    type = Type.ASSET;
                    source = item.getString("source");
                    if (!isSafeRelativePath(source)) throw new JSONException("Unsafe asset: " + source);
                    break;
                case "lib":
                    type = Type.LIB;
                    source = item.getString("source");
                    if (!source.matches("lib[A-Za-z0-9_+-]+\\.so"))
                        throw new JSONException("Not a native library name: " + source);
                    break;
                case "symlink":
                    type = Type.SYMLINK;
                    source = item.getString("target");
                    if (source.isEmpty() || source.indexOf('\0') >= 0)
                        throw new JSONException("Bad symlink target for " + path);
                    break;
                default:
                    throw new JSONException("Unknown entry type for " + path);
            }
            entries.add(new Entry(path, type, source, item.optBoolean("executable", false),
                item.optBoolean("subst", false), item.optBoolean("optional", false)));
        }
        return new NativeManifest(version, entries);
    }

    /** A path that stays below the directory it is resolved against. */
    static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0)
            return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }
}
