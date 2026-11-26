package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import androidx.preference.PreferenceManager;

import com.termux.terminal.TerminalColorScheme;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.TermuxConfigDirs;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

public final class TerminalAppearance {

    public static final String CONFIG = "config";
    public static final String DEFAULT = "default";

    public enum Setting {
        COLORS("terminal_color_scheme", "colors", ".properties", "colors.properties"),
        FONT("terminal_font", "fonts", ".ttf", "font.ttf");

        public final String key;
        public final String assetDirectory;
        public final String extension;
        public final String configFile;

        Setting(String key, String assetDirectory, String extension, String configFile) {
            this.key = key;
            this.assetDirectory = assetDirectory;
            this.extension = extension;
            this.configFile = configFile;
        }

        public String selection(Context context) {
            return preferences(context).getString(key, CONFIG);
        }

        public void select(Context context, String choice) {
            preferences(context).edit().putString(key, choice).apply();
        }

        private String assetPath(String choice) {
            return assetDirectory + "/" + choice;
        }
    }

    private static final String LOG_TAG = "TerminalAppearance";

    private TerminalAppearance() {}

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static List<String> configPaths() {
        return TermuxNix.configPaths(TermuxConstants.TERMUX_CONFIG_DIR_PATHS_LIST);
    }

    public static TerminalColorScheme loadColors(Context context, String choice, List<String> paths) {
        TerminalColorScheme scheme = new TerminalColorScheme();
        if (DEFAULT.equals(choice)) return scheme;

        Properties properties = new Properties();
        try (InputStream input = openColors(context, choice, paths)) {
            if (input != null) properties.load(input);
            scheme.updateWith(properties);
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load terminal colors", e);
            return new TerminalColorScheme();
        }
        return scheme;
    }

    private static InputStream openColors(Context context, String choice, List<String> paths) throws IOException {
        if (!CONFIG.equals(choice)) return context.getAssets().open(Setting.COLORS.assetPath(choice));
        File file = TermuxConfigDirs.resolveFile(paths, Setting.COLORS.configFile, false);
        return file.isFile() ? new FileInputStream(file) : null;
    }

    /** Resolve the extra keys independently, or use the font selected for the terminal. */
    public static Typeface loadExtraKeysFont(Context context, String choice) {
        if ("terminal".equalsIgnoreCase(choice))
            return loadFont(context, Setting.FONT.selection(context), configPaths());
        if ("monospace".equalsIgnoreCase(choice)) return Typeface.MONOSPACE;
        if ("serif".equalsIgnoreCase(choice)) return Typeface.SERIF;
        return null;
    }

    public static Typeface loadFont(Context context, String choice, List<String> paths) {
        if (CONFIG.equals(choice)) {
            Typeface typeface = loadConfigFont(paths, Setting.FONT.configFile);
            return typeface == null ? Typeface.MONOSPACE : typeface;
        }
        if (DEFAULT.equals(choice)) return Typeface.MONOSPACE;
        try {
            return Typeface.createFromAsset(context.getAssets(), Setting.FONT.assetPath(choice));
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load terminal font " + choice, e);
            return Typeface.MONOSPACE;
        }
    }

    static Typeface loadConfigFont(List<String> paths, String fileName) {
        File file = TermuxConfigDirs.resolveFile(paths, fileName, true);
        if (file.isFile() && file.length() > 0) {
            try {
                return Typeface.createFromFile(file);
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not load terminal font " + fileName, e);
            }
        }
        return null;
    }
}
