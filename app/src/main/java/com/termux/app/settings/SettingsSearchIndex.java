package com.termux.app.settings;

import android.content.Context;
import android.content.res.XmlResourceParser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.fragments.settings.termux.PropertiesPreferencesFragment;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.pschmitt.termux.shared.termux.settings.properties.TermuxConfigSchema;

/** Static index of preferences reachable from the Settings root. Values are never indexed. */
public final class SettingsSearchIndex {

    private static final String APP_NAMESPACE = "http://schemas.android.com/apk/res-auto";
    private static final String FRAGMENT_PREFIX = "com.termux.app.fragments.settings.";

    private SettingsSearchIndex() {}

    public static final class Result {
        public final String title;
        public final String breadcrumb;
        public final List<String> fragmentPath;
        @Nullable public final String preferenceKey;
        @Nullable private final String preferenceSummary;

        private Result(String title, String breadcrumb, List<String> fragmentPath, @Nullable String preferenceKey,
                       @Nullable String preferenceSummary) {
            this.title = title;
            this.breadcrumb = breadcrumb;
            this.fragmentPath = Collections.unmodifiableList(new ArrayList<>(fragmentPath));
            this.preferenceKey = preferenceKey;
            this.preferenceSummary = preferenceSummary;
        }

        public boolean matches(@NonNull String query) {
            String searchable = normalize(title + " " + breadcrumb + " "
                + (preferenceKey == null ? "" : preferenceKey) + " "
                + (preferenceSummary == null ? "" : preferenceSummary));
            for (String term : normalize(query).split(" ")) {
                if (!term.isEmpty() && !searchable.contains(term)) return false;
            }
            return true;
        }
    }

    public static List<Result> build(@NonNull Context context) {
        List<Result> results = new ArrayList<>();
        Map<String, Integer> screens = screenResources();
        Node root = readScreen(context, R.xml.root_preferences);
        if (root != null) {
            for (Node category : root.children) {
                for (Node page : category.children) {
                    if (!page.visible) continue;
                    if (page.fragment == null) {
                        if (page.key != null) addResult(results, page, category.title, Collections.emptyList());
                        continue;
                    }
                    List<String> path = Collections.singletonList(page.fragment);
                    String crumb = join(category.title, page.title);
                    if (page.title != null) results.add(new Result(page.title, crumb, path, null, page.summary));
                    Integer xml = screens.get(page.fragment);
                    if (xml != null && xml != 0) indexScreen(context, results, screens, xml, path, crumb,
                        new HashSet<>(path));
                    if (page.fragment.endsWith("PropertiesPreferencesFragment"))
                        indexProperties(context, results, path, crumb);
                }
            }
        }
        return results;
    }

    private static void indexScreen(Context context, List<Result> results, Map<String, Integer> screens,
                                    int resource, List<String> path, String breadcrumb, Set<String> seen) {
        Node screen = readScreen(context, resource);
        if (screen == null) return;
        indexChildren(context, results, screens, screen, path, breadcrumb, seen);
    }

    private static void indexChildren(Context context, List<Result> results, Map<String, Integer> screens,
                                      Node parent, List<String> path, String breadcrumb, Set<String> seen) {
        for (Node node : parent.children) {
            if (!node.visible) continue;
            String currentBreadcrumb = node.group ? join(breadcrumb, node.title) : breadcrumb;
            if (node.fragment != null) {
                List<String> targetPath = new ArrayList<>(path);
                targetPath.add(node.fragment);
                results.add(new Result(node.title, join(currentBreadcrumb, node.title), targetPath, null, node.summary));
                Integer nestedResource = screens.get(node.fragment);
                if (nestedResource != null && nestedResource != 0 && !seen.contains(node.fragment)) {
                    Set<String> nestedSeen = new HashSet<>(seen);
                    nestedSeen.add(node.fragment);
                    indexScreen(context, results, screens, nestedResource, targetPath,
                        join(currentBreadcrumb, node.title), nestedSeen);
                }
            } else if (node.group) {
                indexChildren(context, results, screens, node, path, currentBreadcrumb, seen);
            } else if (node.key != null && node.title != null) {
                results.add(new Result(node.title, join(currentBreadcrumb, node.title), path, node.key, node.summary));
            }
        }
    }

    private static void indexProperties(Context context, List<Result> results, List<String> path, String breadcrumb) {
        for (String key : TermuxConfigSchema.paths().values()) {
            if (TermuxConfigSchema.isDisplayKey(key)) continue;
            String parent = "extra-keys".equals(key) ? breadcrumb
                : join(breadcrumb, context.getString(PropertiesPreferencesFragment.categoryFor(key)));
            // The path in config.yaml is searchable too, besides the title and the former key.
            results.add(new Result(propertyTitle(key), parent, path, key,
                TermuxConfigSchema.pathFor(key) + " " + PropertyInputSpec.help(key)));
        }
    }

    private static void addResult(List<Result> results, Node node, String breadcrumb, List<String> path) {
        if (node.title != null) results.add(new Result(node.title, join(breadcrumb, node.title), path, node.key, node.summary));
    }

    @Nullable
    private static Node readScreen(Context context, int resource) {
        XmlResourceParser parser = context.getResources().getXml(resource);
        ArrayDeque<Node> stack = new ArrayDeque<>();
        Node root = null;
        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    Node node = new Node(isGroup(name),
                        attribute(context, parser, "title"),
                        attribute(context, parser, "key"),
                        attribute(context, parser, "summary"),
                        attribute(context, parser, "fragment"),
                        !"false".equalsIgnoreCase(attribute(context, parser, "isPreferenceVisible")));
                    if (root == null) root = node;
                    if (!stack.isEmpty()) stack.peek().children.add(node);
                    if (node.group) stack.push(node);
                } else if (event == XmlPullParser.END_TAG && isGroup(parser.getName()) && !stack.isEmpty()) {
                    stack.pop();
                }
                event = parser.next();
            }
        } catch (XmlPullParserException | IOException ignored) {
            return null;
        } finally {
            parser.close();
        }
        return root;
    }

    @Nullable
    private static String attribute(Context context, XmlResourceParser parser, String name) {
        int resource = parser.getAttributeResourceValue(APP_NAMESPACE, name, 0);
        if (resource != 0) {
            try {
                return context.getText(resource).toString();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return parser.getAttributeValue(APP_NAMESPACE, name);
    }

    private static boolean isGroup(String name) {
        return "PreferenceScreen".equals(name) || "PreferenceCategory".equals(name);
    }

    private static Map<String, Integer> screenResources() {
        Map<String, Integer> screens = new HashMap<>();
        screens.put(FRAGMENT_PREFIX + "termux.TerminalViewPreferencesFragment", R.xml.termux_terminal_view_preferences);
        screens.put(FRAGMENT_PREFIX + "termux.TerminalIOPreferencesFragment", R.xml.termux_terminal_io_preferences);
        screens.put(FRAGMENT_PREFIX + "termux.PropertiesPreferencesFragment", 0);
        screens.put(FRAGMENT_PREFIX + "termux.ServicesPreferencesFragment", R.xml.termux_services_preferences);
        screens.put(FRAGMENT_PREFIX + "UpdatesPreferencesFragment", R.xml.updates_preferences);
        screens.put(FRAGMENT_PREFIX + "termux.DebuggingPreferencesFragment", R.xml.termux_debugging_preferences);
        screens.put(FRAGMENT_PREFIX + "AboutPreferencesFragment", R.xml.about_preferences);
        screens.put(FRAGMENT_PREFIX + "termux.AdbShellAuthorizationsPreferencesFragment", R.xml.adb_shell_authorizations_preferences);
        return screens;
    }

    /**
     * The title of a setting, like "Triple tap: action" for {@code gestures.tap.triple.action}. A setting
     * without a written title is named by its path without the group; a key without a path is spelled out.
     */
    public static String propertyTitle(String key) {
        String path = TermuxConfigSchema.pathFor(key);
        if (path == null) return capitalize(key.replace('-', ' ').replace('_', ' '));
        String written = SettingTitles.forPath(path);
        if (written != null) return written;
        String[] parts = path.split("\\.");
        StringBuilder title = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (title.length() > 0) title.append(" › ");
            title.append(capitalize(parts[i].replace('-', ' ')));
        }
        return title.toString();
    }

    private static String capitalize(String words) {
        if (words.isEmpty()) return words;
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String join(@Nullable String left, @Nullable String right) {
        if (left == null || left.trim().isEmpty()) return right == null ? "" : right;
        if (right == null || right.trim().isEmpty()) return left;
        return left + " › " + right;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }

    private static final class Node {
        final boolean group;
        @Nullable final String title;
        @Nullable final String key;
        @Nullable final String summary;
        @Nullable final String fragment;
        final boolean visible;
        final List<Node> children = new ArrayList<>();

        Node(boolean group, @Nullable String title, @Nullable String key,
             @Nullable String summary, @Nullable String fragment, boolean visible) {
            this.group = group;
            this.title = title;
            this.key = key;
            this.summary = summary;
            this.fragment = fragment;
            this.visible = visible;
        }
    }
}
