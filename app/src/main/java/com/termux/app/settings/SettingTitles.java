package com.termux.app.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The human-readable titles of the settings in {@code config.yaml}, keyed by their path. The Properties
 * screen shows them under the category of their group, so a title does not repeat the group's name.
 */
public final class SettingTitles {

    private static final Map<String, String> TITLES = new HashMap<>();

    private static void title(String path, String title) {
        TITLES.put(path, title);
    }

    /** {@code action}, {@code keys} and {@code command} of one gesture binding. */
    private static void binding(String path, String gesture) {
        title(path + ".action", gesture + ": action");
        title(path + ".keys", gesture + ": keys to send");
        title(path + ".command", gesture + ": command");
    }

    static {
        title("appearance.night-mode", "Dark theme");
        title("appearance.accent", "Accent color");
        title("appearance.color-scheme", "Color scheme");
        title("appearance.font", "Terminal font");
        title("appearance.fullscreen", "Fullscreen");
        title("appearance.fullscreen-workaround", "Keyboard fix in fullscreen");
        title("appearance.toast-position", "Message position");
        title("appearance.session-change-toast", "Show the session name when switching");

        title("terminal.engine", "Terminal engine");
        title("terminal.margin.horizontal", "Side margin");
        title("terminal.margin.vertical", "Top and bottom margin");
        title("terminal.cursor.style", "Cursor shape");
        title("terminal.cursor.blink-rate", "Cursor blink rate");
        title("terminal.transcript-rows", "Scrollback lines");
        title("terminal.bell", "Bell");
        title("terminal.open-url-on-click", "Open links with a tap");
        title("terminal.default-working-directory", "Start directory");

        title("sessions.picker-position", "Picker position");
        title("sessions.create-on-single-session-switch", "Switching from the only session creates a new one");
        title("sessions.command-context", "Where commands run");
        title("sessions.tmpdir-cleanup-days", "Delete unused temporary files after (days)");
        title("sessions.nix.enabled", "Start sessions in the Nix guest");
        title("sessions.nix.user", "Nix guest user name");

        title("sidebar.layout", "tmux window layout");
        title("sidebar.style", "Highlight style");
        title("sidebar.right.enabled", "Right sidebar");
        for (String side : new String[] {"left", "right"}) {
            String name = side.equals("left") ? "Left sidebar" : "Right sidebar";
            title("sidebar." + side + ".show-sessions", name + ": sessions");
            title("sidebar." + side + ".show-tmux-windows", name + ": tmux windows");
            title("sidebar." + side + ".show-new-session", name + ": New session button");
            title("sidebar." + side + ".show-keyboard-button", name + ": Keyboard button");
        }

        title("keyboard.back-key", "Back button");
        title("keyboard.hide-on-startup", "Hide the keyboard at start");
        title("keyboard.hide-on-sidebar", "Hide the keyboard while a sidebar is open");
        title("keyboard.toggle-behaviour", "Keyboard toggle");
        title("keyboard.char-based-input", "Send each character as typed");
        title("keyboard.ctrl-space-workaround", "Ctrl+Space fix");
        title("keyboard.hardware-shortcuts", "Hardware keyboard shortcuts");
        title("keyboard.shortcuts.create-session", "Shortcut: new session");
        title("keyboard.shortcuts.next-session", "Shortcut: next session");
        title("keyboard.shortcuts.previous-session", "Shortcut: previous session");
        title("keyboard.shortcuts.rename-session", "Shortcut: rename session");
        title("keyboard.volume.mode", "Volume buttons");
        title("keyboard.volume.up.action", "Volume up: action");
        title("keyboard.volume.up.keys", "Volume up: keys to send");
        title("keyboard.volume.down.action", "Volume down: action");
        title("keyboard.volume.down.keys", "Volume down: keys to send");

        title("extra-keys.keys", "Layout");
        title("extra-keys.theme", "Theme");
        title("extra-keys.labels", "Key labels");
        title("extra-keys.height", "Height");
        title("extra-keys.compact", "Compact rows");
        title("extra-keys.background-color", "Background color");
        title("extra-keys.font", "Font");
        title("extra-keys.animation", "Press animation");
        title("extra-keys.all-caps", "Capital letters");
        title("extra-keys.corner-radius", "Corner radius");
        title("extra-keys.active.text-color", "Active modifier text color");
        title("extra-keys.active.background-color", "Pressed key color");

        title("gestures.animations", "Gesture feedback");
        title("gestures.feedback.style", "Feedback effect");
        title("gestures.feedback.position", "Feedback position");
        binding("gestures.tap.single", "Single tap");
        binding("gestures.tap.double", "Double tap");
        binding("gestures.tap.triple", "Triple tap");
        title("gestures.tap.triple.focus-title", "Triple tap: switch to a session by title");
        for (String fingers : new String[] {"two", "three"}) {
            String name = fingers.equals("two") ? "Two-finger swipe " : "Three-finger swipe ";
            for (String direction : new String[] {"up", "down", "left", "right"})
                binding("gestures.swipe." + fingers + "-finger." + direction, name + direction);
        }
        binding("gestures.extra-keys.swipe.right", "Extra keys swipe right");
        binding("gestures.extra-keys.swipe.left", "Extra keys swipe left");
        binding("gestures.extra-keys.long-swipe.right", "Extra keys long swipe right");
        binding("gestures.extra-keys.long-swipe.left", "Extra keys long swipe left");

        title("integrations.allow-external-apps", "Allow other apps to run commands");
        title("integrations.am-socket-server", "Fast am command");
        title("integrations.share-target", "Show in the Share menu");
        title("integrations.open-with-target", "Show in Open with");
        title("integrations.adb-shell.allowed-ssids", "ADB shell Wi-Fi networks");

        title("power.wake-lock.timeout", "Wake lock timeout (minutes)");
        title("power.wake-lock.wifi", "Keep Wi-Fi awake with the wake lock");
    }

    private SettingTitles() {}

    /** The title of a setting by its path, or null if it has none. */
    @Nullable
    public static String forPath(@NonNull String path) {
        return TITLES.get(path);
    }
}
