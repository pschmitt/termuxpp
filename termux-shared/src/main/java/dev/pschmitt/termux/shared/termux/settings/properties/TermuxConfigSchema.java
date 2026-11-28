package dev.pschmitt.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.termux.TermuxConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The grouped layout of {@code config.yaml}: the YAML path of every setting, mapped to the flat key the app
 * uses internally (and that the legacy {@code termux.properties} format used). The order here is the order in
 * which the app writes the file.
 *
 * <p>Some paths are <em>inverted</em>: the YAML setting is the positive form of an internal key that is
 * negative (for example {@code appearance.session-change-toast} is the opposite of
 * {@code disable-terminal-session-change-toast}). Their boolean value is negated when it is read and written.
 *
 * <p>Paths of former layouts are still read as <em>aliases</em>, so that existing files keep working. A
 * canonical path wins over an alias of the same setting, and the app replaces an alias with the canonical
 * path when it changes that setting.
 */
public final class TermuxConfigSchema {

    /** A YAML path and how its value maps to an internal key. */
    public static final class Entry {
        @NonNull public final String path;
        @NonNull public final String key;
        /** Whether the YAML boolean is the negation of the internal value. */
        public final boolean inverted;

        Entry(@NonNull String path, @NonNull String key, boolean inverted) {
            this.path = path;
            this.key = key;
            this.inverted = inverted;
        }
    }

    /** Canonical YAML path (dot-separated) to its entry, in file order. */
    private static final Map<String, Entry> CANONICAL = new LinkedHashMap<>();
    /** Former YAML path to its entry. */
    private static final Map<String, Entry> ALIASES = new LinkedHashMap<>();
    /** Internal key to its canonical entry. */
    private static final Map<String, Entry> BY_KEY = new HashMap<>();
    /** Internal key to every path, canonical first. */
    private static final Map<String, List<String>> PATHS_BY_KEY = new HashMap<>();
    /** Internal keys that are edited outside the Properties screen. */
    private static final Set<String> DISPLAY_KEYS = new HashSet<>();

    private static void add(String path, String key) {
        add(path, key, false);
    }

    private static void add(String path, String key, boolean inverted) {
        Entry entry = new Entry(path, key, inverted);
        CANONICAL.put(path, entry);
        BY_KEY.put(key, entry);
        PATHS_BY_KEY.computeIfAbsent(key, k -> new ArrayList<>()).add(0, path);
    }

    private static void alias(String path, String key) {
        alias(path, key, false);
    }

    private static void alias(String path, String key, boolean inverted) {
        ALIASES.put(path, new Entry(path, key, inverted));
        PATHS_BY_KEY.computeIfAbsent(key, k -> new ArrayList<>()).add(path);
    }

    /** {@code action}, {@code keys} and {@code command} of one gesture binding. */
    private static void binding(String path, String prefix) {
        for (String type : new String[] {"action", "keys", "command"})
            add(path + "." + type, prefix + "-" + type);
    }

    private static void bindingAlias(String path, String prefix) {
        for (String type : new String[] {"action", "keys", "command"})
            alias(path + "." + type, prefix + "-" + type);
    }

    static {
        add("appearance.night-mode", TermuxPropertyConstants.KEY_NIGHT_MODE);
        add("appearance.accent", TermuxPropertyConstants.KEY_APP_ACCENT);
        add("appearance.color-scheme", TermuxPropertyConstants.KEY_TERMINAL_COLOR_SCHEME);
        add("appearance.font", TermuxPropertyConstants.KEY_TERMINAL_FONT);
        add("appearance.fullscreen", "fullscreen");
        add("appearance.fullscreen-workaround", "use-fullscreen-workaround");
        add("appearance.toast-position", "terminal-toast-position");
        add("appearance.session-change-toast", "disable-terminal-session-change-toast", true);
        DISPLAY_KEYS.add(TermuxPropertyConstants.KEY_APP_ACCENT);
        DISPLAY_KEYS.add(TermuxPropertyConstants.KEY_TERMINAL_COLOR_SCHEME);
        DISPLAY_KEYS.add(TermuxPropertyConstants.KEY_TERMINAL_FONT);

        add("terminal.engine", "terminal-engine");
        add("terminal.margin.horizontal", "terminal-margin-horizontal");
        add("terminal.margin.vertical", "terminal-margin-vertical");
        add("terminal.cursor.style", "terminal-cursor-style");
        add("terminal.cursor.blink-rate", "terminal-cursor-blink-rate");
        add("terminal.transcript-rows", "terminal-transcript-rows");
        add("terminal.bell", "bell-character");
        add("terminal.open-url-on-click", "terminal-onclick-url-open");
        add("terminal.default-working-directory", "default-working-directory");

        add("sessions.picker-position", "terminal-picker-position");
        add("sessions.create-on-single-session-switch", "create-session-on-single-session-switch");
        add("sessions.command-context", "gesture-command-context");
        add("sessions.tmpdir-cleanup-days", "delete-tmpdir-files-older-than-x-days-on-exit");
        add("sessions.nix.enabled", "nix-session");
        add("sessions.nix.user", "nix-user");

        add("sidebar.layout", "terminal-tmux-sidebar-layout");
        add("sidebar.style", "terminal-tmux-sidebar-style");
        for (String side : new String[] {"left", "right"}) {
            if (side.equals("right")) add("sidebar.right.enabled", "tmux-right-sidebar");
            for (String item : new String[] {"sessions", "tmux-windows", "new-session", "keyboard-button"})
                add("sidebar." + side + ".show-" + item, "sidebar-" + side + "-show-" + item);
        }

        add("keyboard.back-key", "back-key");
        add("keyboard.hide-on-startup", "hide-soft-keyboard-on-startup");
        add("keyboard.hide-on-sidebar", "hide-soft-keyboard-on-sidebar");
        add("keyboard.toggle-behaviour", "soft-keyboard-toggle-behaviour");
        add("keyboard.char-based-input", "enforce-char-based-input");
        add("keyboard.ctrl-space-workaround", "ctrl-space-workaround");
        add("keyboard.hardware-shortcuts", "disable-hardware-keyboard-shortcuts", true);
        for (String shortcut : new String[] {"create-session", "next-session", "previous-session", "rename-session", "palette"})
            add("keyboard.shortcuts." + shortcut, "shortcut." + shortcut);
        add("keyboard.volume.mode", "volume-keys");
        add("keyboard.volume.up.action", "volume-up-action");
        add("keyboard.volume.up.keys", "volume-up-keys");
        add("keyboard.volume.down.action", "volume-down-action");
        add("keyboard.volume.down.keys", "volume-down-keys");

        add("extra-keys.keys", "extra-keys");
        add("extra-keys.theme", "extra-keys-theme");
        add("extra-keys.labels", "extra-keys-style");
        add("extra-keys.height", "terminal-toolbar-height");
        add("extra-keys.compact", "extra-keys-compact");
        add("extra-keys.row-height", "extra-keys-row-height");
        add("extra-keys.auto-hide", "extra-keys-auto-hide");
        add("extra-keys.background-color", "extra-keys-row-background-color");
        add("extra-keys.font", "extra-keys-font");
        add("extra-keys.font-weight", "extra-keys-font-weight");
        add("extra-keys.animation", "extra-keys-animation");
        add("extra-keys.all-caps", "extra-keys-text-all-caps");
        add("extra-keys.corner-radius", "extra-keys-button-corner-radius");
        add("extra-keys.active.text-color", "extra-keys-button-active-text-color");
        add("extra-keys.active.background-color", "extra-keys-button-active-background-color");

        add("gestures.animations", "gesture-animations");
        add("gestures.feedback.style", "gesture-feedback-style");
        add("gestures.feedback.position", "gesture-feedback-position");
        for (String taps : new String[] {"single", "double", "triple"})
            binding("gestures.tap." + taps, "terminal-" + taps + "-tap");
        add("gestures.tap.triple.focus-title", "terminal-triple-tap-focus-title");
        String[] fingerNames = {"two-finger", "three-finger"};
        for (int fingers = 2; fingers <= 3; fingers++) {
            for (String direction : new String[] {"up", "down", "left", "right"}) {
                for (String type : new String[] {"action", "keys", "command"}) {
                    add("gestures.swipe." + fingerNames[fingers - 2] + "." + direction + "." + type,
                        TermuxPropertyConstants.terminalMultiFingerSwipeProperty(fingers, direction, type));
                }
            }
        }
        // Named by the direction the finger moves, like the terminal swipes. The internal keys are named
        // after the toolbar page the swipe goes to, which is the opposite direction.
        binding("gestures.extra-keys.swipe.right", "extra-keys-swipe-left");
        binding("gestures.extra-keys.swipe.left", "extra-keys-swipe-right");
        binding("gestures.extra-keys.long-swipe.right", "extra-keys-long-swipe-left");
        binding("gestures.extra-keys.long-swipe.left", "extra-keys-long-swipe-right");

        add("integrations.allow-external-apps", TermuxConstants.PROP_ALLOW_EXTERNAL_APPS);
        add("integrations.am-socket-server", "run-termux-am-socket-server");
        add("integrations.share-target", "disable-file-share-receiver", true);
        add("integrations.open-with-target", "disable-file-view-receiver", true);
        add("integrations.adb-shell.allowed-ssids", "adb-shell-allowed-ssids");

        add("power.wake-lock.timeout", "wake-lock-timeout");
        add("power.wake-lock.wifi", "wake-lock-wifi");

        // The layout before the review of the setting names (2026-11).
        alias("appearance.disable-session-change-toast", "disable-terminal-session-change-toast");
        alias("terminal.toolbar-height", "terminal-toolbar-height");
        alias("sessions.tmpdir-max-age-days", "delete-tmpdir-files-older-than-x-days-on-exit");
        alias("sessions.nix.session", "nix-session");
        alias("tmux.sidebar.layout", "terminal-tmux-sidebar-layout");
        alias("tmux.sidebar.style", "terminal-tmux-sidebar-style");
        alias("tmux.sidebar.right", "tmux-right-sidebar");
        alias("keyboard.disable-hardware-shortcuts", "disable-hardware-keyboard-shortcuts");
        alias("keyboard.volume-keys", "volume-keys");
        alias("extra-keys.style", "extra-keys-style");
        bindingAlias("extra-keys.swipe.left", "extra-keys-swipe-left");
        bindingAlias("extra-keys.swipe.right", "extra-keys-swipe-right");
        bindingAlias("extra-keys.swipe.long-left", "extra-keys-long-swipe-left");
        bindingAlias("extra-keys.swipe.long-right", "extra-keys-long-swipe-right");
        alias("gestures.command-context", "gesture-command-context");
        alias("integrations.disable-file-share-receiver", "disable-file-share-receiver");
        alias("integrations.disable-file-view-receiver", "disable-file-view-receiver");
    }

    private TermuxConfigSchema() {}

    /** Every canonical YAML path and its internal key, in file order. */
    @NonNull
    public static Map<String, String> paths() {
        Map<String, String> paths = new LinkedHashMap<>();
        for (Entry entry : CANONICAL.values()) paths.put(entry.path, entry.key);
        return Collections.unmodifiableMap(paths);
    }

    /** Every canonical entry, in file order. */
    @NonNull
    public static Iterable<Entry> entries() {
        return Collections.unmodifiableCollection(CANONICAL.values());
    }

    /** The canonical entry of a path, or null. */
    @Nullable
    public static Entry canonical(@NonNull String path) {
        return CANONICAL.get(path);
    }

    /** The entry of a former path, or null. */
    @Nullable
    public static Entry alias(@NonNull String path) {
        return ALIASES.get(path);
    }

    /** Every former path and its entry. */
    @NonNull
    public static Map<String, Entry> aliases() {
        return Collections.unmodifiableMap(ALIASES);
    }

    /** The canonical entry of an internal key, or null for a key the schema does not know. */
    @Nullable
    public static Entry entryFor(@NonNull String key) {
        return BY_KEY.get(key);
    }

    /** Every path, canonical first and then former ones, that sets an internal key. */
    @NonNull
    public static List<String> allPathsFor(@NonNull String key) {
        List<String> paths = PATHS_BY_KEY.get(key);
        return paths == null ? Collections.emptyList() : Collections.unmodifiableList(paths);
    }

    /** The internal key of a canonical or former path, or null for a path the schema does not know. */
    @Nullable
    public static String keyFor(@NonNull String path) {
        Entry entry = CANONICAL.get(path);
        if (entry == null) entry = ALIASES.get(path);
        return entry == null ? null : entry.key;
    }

    /** The canonical YAML path of an internal key, or null for a key the schema does not know. */
    @Nullable
    public static String pathFor(@NonNull String key) {
        Entry entry = BY_KEY.get(key);
        return entry == null ? null : entry.path;
    }

    /** Whether the YAML value of an internal key is the negation of the internal value. */
    public static boolean isInverted(@NonNull String key) {
        Entry entry = BY_KEY.get(key);
        return entry != null && entry.inverted;
    }

    /** Whether a key is set on the Display screen rather than the Properties screen. */
    public static boolean isDisplayKey(@NonNull String key) {
        return DISPLAY_KEYS.contains(key);
    }

    /**
     * The negation of a boolean text, for inverted paths. Any other text is returned as it is, so that the
     * setting's parser reports it as invalid.
     */
    @Nullable
    public static String invert(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) return "false";
        if (trimmed.equalsIgnoreCase("false")) return "true";
        return value;
    }

    /** The order of a canonical path or of a group prefix in the file, or MAX_VALUE if it is unknown. */
    public static int order(@NonNull String pathOrPrefix) {
        int index = 0;
        for (String path : CANONICAL.keySet()) {
            if (path.equals(pathOrPrefix) || path.startsWith(pathOrPrefix + ".")) return index;
            index++;
        }
        return Integer.MAX_VALUE;
    }
}
