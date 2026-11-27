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
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Present;
import org.snakeyaml.engine.v2.api.lowlevel.Serialize;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads and writes {@code config.yaml}, the grouped settings file laid out by {@link TermuxConfigSchema}.
 * The app works with the flat keys internally, so reading flattens the YAML into a {@link Properties} object
 * and writing groups such an object again. {@link #update} changes one setting in an existing document and
 * keeps its comments.
 */
public final class TermuxYamlConfig {

    /** A number YAML reads back as the same text: no leading zeros, no trailing zeros after a point. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?");
    public static final String HEADER = "# TermuxPP settings. Reference: docs/en/config-yaml.md in the TermuxPP\n"
        + "# repository. Comments are kept when you change a setting in the app.\n";

    private TermuxYamlConfig() {}

    /**
     * Read a YAML document into flat settings.
     *
     * @param unknown Collects the paths that the schema does not know, if not null.
     * @throws IllegalArgumentException When the document is not YAML or not a mapping at the top.
     */
    @NonNull
    public static Properties read(@NonNull String text, @Nullable List<String> unknown) {
        return read(text, unknown, null);
    }

    /**
     * Read a YAML document into flat settings.
     *
     * @param unknown Collects the paths that the schema does not know, if not null.
     * @param former Collects the former paths that were read, if not null.
     * @throws IllegalArgumentException When the document is not YAML or not a mapping at the top.
     */
    @NonNull
    public static Properties read(@NonNull String text, @Nullable List<String> unknown, @Nullable List<String> former) {
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
        Properties aliased = new Properties();
        flatten("", (Map<?, ?>) document, properties, aliased, unknown, former);
        // A canonical path wins over a former path of the same setting.
        for (String key : aliased.stringPropertyNames())
            if (properties.getProperty(key) == null) properties.setProperty(key, aliased.getProperty(key));
        return properties;
    }

    private static void flatten(String prefix, Map<?, ?> map, Properties properties, Properties aliased,
                                @Nullable List<String> unknown, @Nullable List<String> former) {
        for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(mapEntry.getKey()) : prefix + "." + mapEntry.getKey();
            Object value = mapEntry.getValue();
            TermuxConfigSchema.Entry entry = TermuxConfigSchema.canonical(path);
            Properties into = properties;
            if (entry == null) {
                entry = TermuxConfigSchema.alias(path);
                into = aliased;
                if (entry != null && former != null) former.add(path);
            }
            if (entry != null) {
                String text = text(value);
                if (text != null) into.setProperty(entry.key, entry.inverted ? TermuxConfigSchema.invert(text) : text);
            } else if (value instanceof Map) {
                flatten(path, (Map<?, ?>) value, properties, aliased, unknown, former);
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
        for (TermuxConfigSchema.Entry entry : TermuxConfigSchema.entries()) {
            String value = properties.getProperty(entry.key);
            if (value == null) continue;
            insert(root, entry.path.split("\\."), typed(entry, value));
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

    /**
     * Change one setting in a YAML document and keep everything else, comments included. Former paths of the
     * setting are removed, and a missing group is created in the order of {@link TermuxConfigSchema}.
     *
     * @param text The current document, possibly empty.
     * @param key The internal key of the setting.
     * @param value The internal value, or null to remove the setting.
     * @throws IllegalArgumentException When the document is not YAML or not a mapping at the top, or when the
     * key is not part of the schema.
     */
    @NonNull
    public static String update(@NonNull String text, @NonNull String key, @Nullable String value) {
        TermuxConfigSchema.Entry entry = TermuxConfigSchema.entryFor(key);
        if (entry == null) throw new IllegalArgumentException("Unknown setting: " + key);
        // Validate first: duplicate keys and other errors must not be written back.
        read(text, null);

        boolean empty = text.trim().isEmpty();
        String source = empty ? HEADER : text;
        Optional<Node> composed = compose(source);
        // A document of comments only composes to an empty mapping tagged as comment, which cannot be changed.
        boolean commentsOnly = !composed.isPresent() || Tag.COMMENT.equals(composed.get().getTag());
        MappingNode root;
        if (!commentsOnly && composed.get() instanceof MappingNode) {
            root = (MappingNode) composed.get();
        } else {
            root = new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
        }

        for (String path : TermuxConfigSchema.allPathsFor(key)) remove(root, path.split("\\."));
        if (value != null) {
            String[] parts = entry.path.split("\\.");
            put(root, parts, 0, valueNode(entry, value));
        }

        String out = present(root);
        // Keep the text of a document of comments only above the settings.
        if (commentsOnly) out = source.replaceAll("\\s+$", "") + "\n\n" + out;
        return out;
    }

    private static Optional<Node> compose(String text) {
        try {
            return new Compose(LoadSettings.builder().setParseComments(true).build()).composeString(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage(), e);
        }
    }

    private static String present(Node root) {
        DumpSettings settings = DumpSettings.builder()
            .setDumpComments(true)
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setIndicatorIndent(2)
            .setIndentWithIndicator(true)
            .setWidth(Integer.MAX_VALUE)
            .setSplitLines(false)
            .build();
        List<org.snakeyaml.engine.v2.events.Event> events = new Serialize(settings).serializeOne(root);
        return new Present(settings).emitToString(events.iterator());
    }

    /** The node of a setting value, written the same way as {@link #write} writes it. */
    private static Node valueNode(TermuxConfigSchema.Entry entry, String value) {
        StringBuilder snippet = new StringBuilder();
        emit(snippet, "v", typed(entry, value), 0);
        Node node = compose(snippet.toString()).orElseThrow(IllegalStateException::new);
        Node child = ((MappingNode) node).getValue().get(0).getValueNode();
        child.setBlockComments(null);
        return child;
    }

    @Nullable
    private static String scalarKey(NodeTuple tuple) {
        Node key = tuple.getKeyNode();
        return key instanceof ScalarNode ? ((ScalarNode) key).getValue() : null;
    }

    /** Remove a path; parents that become empty are removed too. */
    private static void remove(MappingNode map, String[] path) {
        removeAt(map, path, 0);
    }

    /** @return whether the mapping is empty afterwards. */
    private static boolean removeAt(MappingNode map, String[] path, int depth) {
        List<NodeTuple> tuples = map.getValue();
        for (int i = 0; i < tuples.size(); i++) {
            NodeTuple tuple = tuples.get(i);
            if (!path[depth].equals(scalarKey(tuple))) continue;
            boolean drop;
            if (depth == path.length - 1) {
                drop = true;
            } else if (tuple.getValueNode() instanceof MappingNode) {
                drop = removeAt((MappingNode) tuple.getValueNode(), path, depth + 1);
            } else {
                drop = false;
            }
            if (drop) {
                tuples.remove(i);
                // Comments above the removed line stay with the next line of the same mapping.
                List<org.snakeyaml.engine.v2.comments.CommentLine> comments = tuple.getKeyNode().getBlockComments();
                if (comments != null && !comments.isEmpty() && i < tuples.size()) {
                    Node next = tuples.get(i).getKeyNode();
                    List<org.snakeyaml.engine.v2.comments.CommentLine> merged = new ArrayList<>(comments);
                    if (next.getBlockComments() != null) merged.addAll(next.getBlockComments());
                    next.setBlockComments(merged);
                }
            }
            break;
        }
        return tuples.isEmpty();
    }

    private static void put(MappingNode map, String[] path, int depth, Node value) {
        List<NodeTuple> tuples = map.getValue();
        String name = path[depth];
        for (int i = 0; i < tuples.size(); i++) {
            NodeTuple tuple = tuples.get(i);
            if (!name.equals(scalarKey(tuple))) continue;
            if (depth == path.length - 1) {
                Node old = tuple.getValueNode();
                value.setInLineComments(old.getInLineComments());
                tuples.set(i, new NodeTuple(tuple.getKeyNode(), value));
                return;
            }
            if (tuple.getValueNode() instanceof MappingNode) {
                put((MappingNode) tuple.getValueNode(), path, depth + 1, value);
                return;
            }
            // A scalar where a group belongs: replace it.
            tuples.remove(i);
            break;
        }

        Node child = value;
        if (depth < path.length - 1) {
            MappingNode group = new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
            put(group, path, depth + 1, value);
            child = group;
        }
        NodeTuple tuple = new NodeTuple(new ScalarNode(Tag.STR, name, org.snakeyaml.engine.v2.common.ScalarStyle.PLAIN), child);

        // Insert before the first sibling that comes later in the schema order.
        String prefix = String.join(".", java.util.Arrays.copyOfRange(path, 0, depth));
        int order = TermuxConfigSchema.order(prefix.isEmpty() ? name : prefix + "." + name);
        int index = tuples.size();
        for (int i = 0; i < tuples.size(); i++) {
            String sibling = scalarKey(tuples.get(i));
            if (sibling == null) continue;
            if (TermuxConfigSchema.order(prefix.isEmpty() ? sibling : prefix + "." + sibling) > order) {
                index = i;
                break;
            }
        }
        tuples.add(index, tuple);
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
    private static Object typed(TermuxConfigSchema.Entry entry, String value) {
        if (TermuxPropertyConstants.KEY_EXTRA_KEYS.equals(entry.key)) {
            try {
                return plain(new JSONArray(value));
            } catch (JSONException e) {
                return value;
            }
        }
        if (entry.inverted) value = TermuxConfigSchema.invert(value);
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
