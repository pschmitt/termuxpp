package dev.pschmitt.termux.shared.termux.settings.properties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.termux.TermuxConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The grouped layout of {@code config.yaml}: the YAML path of every setting, mapped to the flat key the app
 * uses internally (and that the legacy {@code termux.properties} format used). The order here is the order in
 * which the app writes the file.
 */
public final class TermuxConfigSchema {

    /** YAML path (dot-separated) to internal key, in file order. */
    private static final Map<String, String> PATH_TO_KEY = new LinkedHashMap<>();
    /** Internal key to YAML path. */
    private static final Map<String, String> KEY_TO_PATH = new HashMap<>();

    private static void add(String path, String key) {
        PATH_TO_KEY.put(path, key);
        KEY_TO_PATH.put(key, path);
    }

    /** {@code action}, {@code keys} and {@code command} of one gesture binding. */
    private static void binding(String path, String prefix) {
        add(path + ".action", prefix + "-action");
        add(path + ".keys", prefix + "-keys");
        add(path + ".command", prefix + "-command");
    }

    static {
        add("appearance.fullscreen", "fullscreen");
        add("appearance.fullscreen-workaround", "use-fullscreen-workaround");
        add("appearance.night-mode", "night-mode");
        add("appearance.toast-position", "terminal-toast-position");
        add("appearance.disable-session-change-toast", "disable-terminal-session-change-toast");

        add("terminal.engine", "terminal-engine");
        add("terminal.margin.horizontal", "terminal-margin-horizontal");
        add("terminal.margin.vertical", "terminal-margin-vertical");
        add("terminal.cursor.style", "terminal-cursor-style");
        add("terminal.cursor.blink-rate", "terminal-cursor-blink-rate");
        add("terminal.transcript-rows", "terminal-transcript-rows");
        add("terminal.toolbar-height", "terminal-toolbar-height");
        add("terminal.bell", "bell-character");
        add("terminal.open-url-on-click", "terminal-onclick-url-open");
        add("terminal.default-working-directory", "default-working-directory");

        add("sessions.picker-position", "terminal-picker-position");
        add("sessions.create-on-single-session-switch", "create-session-on-single-session-switch");
        add("sessions.tmpdir-max-age-days", "delete-tmpdir-files-older-than-x-days-on-exit");
        add("sessions.nix.session", "nix-session");
        add("sessions.nix.user", "nix-user");

        add("tmux.sidebar.layout", "terminal-tmux-sidebar-layout");
        add("tmux.sidebar.style", "terminal-tmux-sidebar-style");
        add("tmux.sidebar.right", "tmux-right-sidebar");

        add("sidebar.left.show-sessions", "sidebar-left-show-sessions");
        add("sidebar.left.show-tmux-windows", "sidebar-left-show-tmux-windows");
        add("sidebar.left.show-new-session", "sidebar-left-show-new-session");
        add("sidebar.left.show-keyboard-button", "sidebar-left-show-keyboard-button");
        add("sidebar.right.show-sessions", "sidebar-right-show-sessions");
        add("sidebar.right.show-tmux-windows", "sidebar-right-show-tmux-windows");
        add("sidebar.right.show-new-session", "sidebar-right-show-new-session");
        add("sidebar.right.show-keyboard-button", "sidebar-right-show-keyboard-button");

        add("keyboard.back-key", "back-key");
        add("keyboard.hide-on-startup", "hide-soft-keyboard-on-startup");
        add("keyboard.hide-on-sidebar", "hide-soft-keyboard-on-sidebar");
        add("keyboard.toggle-behaviour", "soft-keyboard-toggle-behaviour");
        add("keyboard.char-based-input", "enforce-char-based-input");
        add("keyboard.ctrl-space-workaround", "ctrl-space-workaround");
        add("keyboard.disable-hardware-shortcuts", "disable-hardware-keyboard-shortcuts");
        add("keyboard.shortcuts.create-session", "shortcut.create-session");
        add("keyboard.shortcuts.next-session", "shortcut.next-session");
        add("keyboard.shortcuts.previous-session", "shortcut.previous-session");
        add("keyboard.shortcuts.rename-session", "shortcut.rename-session");
        add("keyboard.volume-keys", "volume-keys");
        add("keyboard.volume.up.action", "volume-up-action");
        add("keyboard.volume.up.keys", "volume-up-keys");
        add("keyboard.volume.down.action", "volume-down-action");
        add("keyboard.volume.down.keys", "volume-down-keys");

        add("extra-keys.keys", "extra-keys");
        add("extra-keys.style", "extra-keys-style");
        add("extra-keys.all-caps", "extra-keys-text-all-caps");
        add("extra-keys.corner-radius", "extra-keys-button-corner-radius");
        add("extra-keys.active.text-color", "extra-keys-button-active-text-color");
        add("extra-keys.active.background-color", "extra-keys-button-active-background-color");
        binding("extra-keys.swipe.left", "extra-keys-swipe-left");
        binding("extra-keys.swipe.right", "extra-keys-swipe-right");
        binding("extra-keys.swipe.long-left", "extra-keys-long-swipe-left");
        binding("extra-keys.swipe.long-right", "extra-keys-long-swipe-right");

        add("gestures.animations", "gesture-animations");
        add("gestures.command-context", "gesture-command-context");
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

        add("integrations.allow-external-apps", TermuxConstants.PROP_ALLOW_EXTERNAL_APPS);
        add("integrations.am-socket-server", "run-termux-am-socket-server");
        add("integrations.disable-file-share-receiver", "disable-file-share-receiver");
        add("integrations.disable-file-view-receiver", "disable-file-view-receiver");
        add("integrations.adb-shell.allowed-ssids", "adb-shell-allowed-ssids");

        add("power.wake-lock.timeout", "wake-lock-timeout");
        add("power.wake-lock.wifi", "wake-lock-wifi");
    }

    private TermuxConfigSchema() {}

    /** Every YAML path and its internal key, in file order. */
    @NonNull
    public static Map<String, String> paths() {
        return Collections.unmodifiableMap(PATH_TO_KEY);
    }

    /** The internal key of a YAML path, or null for a path the schema does not know. */
    @Nullable
    public static String keyFor(@NonNull String path) {
        return PATH_TO_KEY.get(path);
    }

    /** The YAML path of an internal key, or null for a key the schema does not know. */
    @Nullable
    public static String pathFor(@NonNull String key) {
        return KEY_TO_PATH.get(key);
    }
}
