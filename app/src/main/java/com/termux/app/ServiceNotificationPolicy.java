package com.termux.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

/** The shared settings used by app-managed and guest-managed services. */
public final class ServiceNotificationPolicy {
    public static final String DEFAULTS = "_defaults";
    public static final String ADB_SHELL = "adb-shell";

    public static File stateDirectory() {
        return new File(TermuxNix.RUNTIME_DIR_PATH, "service-state");
    }

    public static JSONObject readSettings() {
        try {
            String value = readFile(new File(stateDirectory(), "settings.json"));
            return new JSONObject(value);
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    public static boolean configuredDefault() {
        try {
            return "true".equals(readFile(new File(stateDirectory(), "notification-default")).trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean enabled(JSONObject settings, String service, boolean configuredDefault) {
        return enabled(settings, service, configuredDefault, null);
    }

    public static boolean enabled(JSONObject settings, String service, boolean configuredDefault,
                                  Boolean configuredService) {
        Boolean override = override(settings, service);
        if (override != null) return override;
        if (configuredService != null) return configuredService;
        Boolean global = override(settings, DEFAULTS);
        return global != null ? global : configuredDefault;
    }

    public static Boolean configuredAdbShell() {
        try {
            String value = readFile(new File(stateDirectory(), "notification-adb-shell")).trim();
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
        } catch (IOException ignored) {}
        return null;
    }

    public static Boolean override(JSONObject settings, String service) {
        JSONObject item = settings.optJSONObject(service);
        Object value = item == null ? null : item.opt("notification");
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static String readFile(File file) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > 65536) throw new IOException("settings too large");
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    private ServiceNotificationPolicy() {}
}
