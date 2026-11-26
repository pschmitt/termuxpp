package dev.pschmitt.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads and writes {@code config.yaml}, the grouped settings file laid out by {@link TermuxConfigSchema}.
 * The app works with the flat keys internally, so reading flattens the YAML into a {@link Properties} object
 * and writing groups such an object again.
 */
public final class TermuxYamlConfig {

    /** A number YAML reads back as the same text: no leading zeros, no trailing zeros after a point. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?");
    private static final String HEADER = "# TermuxPP settings. Reference: docs/en/terminal-settings.md in the TermuxPP\n"
        + "# repository. The app rewrites this file when you change a setting in it, without comments.\n";

    private TermuxYamlConfig() {}

    /**
     * Read a YAML document into flat settings.
     *
     * @param unknown Collects the paths that the schema does not know, if not null.
     * @throws IllegalArgumentException When the document is not YAML or not a mapping at the top.
     */
    @NonNull
    public static Properties read(@NonNull String text, @Nullable List<String> unknown) {
        Object document;
        try {
            document = new Load(LoadSettings.builder().setAllowDuplicateKeys(false).build()).loadFromString(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage(), e);
        }
        Properties properties = new Properties();
        if (document == null) return properties;
        if (!(document instanceof Map))
            throw new IllegalArgumentException("The settings file must be a mapping of groups at the top level");
        flatten("", (Map<?, ?>) document, properties, unknown);
        return properties;
    }

    private static void flatten(String prefix, Map<?, ?> map, Properties properties, @Nullable List<String> unknown) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            String key = TermuxConfigSchema.keyFor(path);
            if (key != null) {
                String text = text(value);
                if (text != null) properties.setProperty(key, text);
            } else if (value instanceof Map) {
                flatten(path, (Map<?, ?>) value, properties, unknown);
            } else if (unknown != null) {
                unknown.add(path);
            }
        }
    }

    /** The flat text of a YAML value: scalars as they read, lists and mappings (extra keys) as JSON. */
    @Nullable
    private static String text(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof Collection) return json(value).toString();
        if (value instanceof Map) return json(value).toString();
        return String.valueOf(value);
    }

    private static Object json(Object value) {
        if (value instanceof Collection) {
            JSONArray array = new JSONArray();
            for (Object item : (Collection<?>) value) array.put(json(item));
            return array;
        }
        if (value instanceof Map) {
            JSONObject object = new JSONObject();
            try {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                    object.put(String.valueOf(entry.getKey()), json(entry.getValue()));
            } catch (JSONException e) {
                throw new IllegalArgumentException(e);
            }
            return object;
        }
        return value == null ? JSONObject.NULL : value;
    }

    /**
     * Write flat settings as a grouped YAML document. Keys the schema does not know are left out.
     *
     * @param unknown Collects the keys that were left out, if not null.
     */
    @NonNull
    public static String write(@NonNull Properties properties, @Nullable List<String> unknown) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : TermuxConfigSchema.paths().entrySet()) {
            String value = properties.getProperty(entry.getValue());
            if (value == null) continue;
            insert(root, entry.getKey().split("\\."), typed(entry.getValue(), value));
        }
        if (unknown != null) {
            for (String key : properties.stringPropertyNames())
                if (TermuxConfigSchema.pathFor(key) == null) unknown.add(key);
        }

        StringBuilder out = new StringBuilder(HEADER);
        for (Map.Entry<String, Object> group : root.entrySet()) {
            out.append('\n');
            emit(out, group.getKey(), group.getValue(), 0);
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> root, String[] path, Object value) {
        Map<String, Object> map = root;
        for (int i = 0; i < path.length - 1; i++) {
            Object child = map.get(path[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                map.put(path[i], child);
            }
            map = (Map<String, Object>) child;
        }
        map.put(path[path.length - 1], value);
    }

    /** The YAML value for a flat one: booleans and numbers typed, the extra keys as a list of rows. */
    private static Object typed(String key, String value) {
        if (TermuxPropertyConstants.KEY_EXTRA_KEYS.equals(key)) {
            try {
                return plain(new JSONArray(value));
            } catch (JSONException e) {
                return value;
            }
        }
        if (value.equals("true")) return Boolean.TRUE;
        if (value.equals("false")) return Boolean.FALSE;
        if (NUMBER.matcher(value).matches()) {
            try {
                return value.contains(".") ? (Object) Double.valueOf(value) : (Object) Long.valueOf(value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }

    private static Object plain(Object json) throws JSONException {
        if (json instanceof JSONArray) {
            JSONArray array = (JSONArray) json;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) list.add(plain(array.get(i)));
            return list;
        }
        if (json instanceof JSONObject) {
            JSONObject object = (JSONObject) json;
            Map<String, Object> map = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String name = keys.next();
                map.put(name, plain(object.get(name)));
            }
            return map;
        }
        return json;
    }

    private static void emit(StringBuilder out, String name, Object value, int indent) {
        indent(out, indent).append(scalar(name)).append(':');
        if (value instanceof Map) {
            out.append('\n');
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                emit(out, String.valueOf(entry.getKey()), entry.getValue(), indent + 2);
        } else if (value instanceof List) {
            // A list (the extra keys) has one row per line, each row in flow style.
            out.append('\n');
            for (Object row : (List<?>) value) indent(out, indent + 2).append("- ").append(flow(row)).append('\n');
        } else {
            out.append(' ').append(scalar(value)).append('\n');
        }
    }

    private static StringBuilder indent(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) out.append(' ');
        return out;
    }

    private static String scalar(Object value) {
        return new Dump(DumpSettings.builder().build()).dumpToString(value).trim();
    }

    private static String flow(Object value) {
        return new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.FLOW).setWidth(Integer.MAX_VALUE).build())
            .dumpToString(value).trim();
    }
}
