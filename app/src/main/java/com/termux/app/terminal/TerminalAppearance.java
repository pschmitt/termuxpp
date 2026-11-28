package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Typeface;

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
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import com.termux.app.settings.ConfigSettings;

public final class TerminalAppearance {

    public static final String CONFIG = "config";
    public static final String DEFAULT = "default";

    public enum Setting {
        COLORS("terminal_color_scheme", TermuxPropertyConstants.KEY_TERMINAL_COLOR_SCHEME, "colors", ".properties", "colors.properties"),
        FONT("terminal_font", TermuxPropertyConstants.KEY_TERMINAL_FONT, "fonts", ".ttf", "font.ttf");

        /** The key of the preference on the Display screen. */
        public final String key;
        /** The setting in config.yaml. */
        public final String propertyKey;
        public final String assetDirectory;
        public final String extension;
        public final String configFile;

        Setting(String key, String propertyKey, String assetDirectory, String extension, String configFile) {
            this.key = key;
            this.propertyKey = propertyKey;
            this.assetDirectory = assetDirectory;
            this.extension = extension;
            this.configFile = configFile;
        }

        /** The choice in config.yaml ({@code appearance.color-scheme} or {@code appearance.font}). */
        public String selection(Context context) {
            return ConfigSettings.get(context, propertyKey, CONFIG);
        }

        public void select(Context context, String choice) {
            ConfigSettings.set(context, propertyKey, CONFIG.equals(choice) ? null : choice);
        }

        private String assetPath(String choice) {
            return assetDirectory + "/" + choice;
        }
    }

    private static final String LOG_TAG = "TerminalAppearance";

    private TerminalAppearance() {}

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
        if ("google-sans".equalsIgnoreCase(choice)) return googleSans(context);
        return null;
    }

    /** Where Pixels keep Google Sans, which they expose as the {@code google-sans-text} family. */
    private static final String[] SYSTEM_GOOGLE_SANS = {
        "/product/fonts/GoogleSans-Regular.ttf", "/system/fonts/GoogleSans-Regular.ttf"};
    /** Google Sans Flex (SIL OFL 1.1), built by scripts/build-extra-keys-font.sh. */
    private static final String BUNDLED_GOOGLE_SANS_REGULAR = "extra-keys-fonts/GoogleSansFlex-Regular.ttf";
    private static final String BUNDLED_GOOGLE_SANS_MEDIUM = "extra-keys-fonts/GoogleSansFlex-Medium.ttf";
    private static Typeface sBundledGoogleSans;

    /**
     * Gboard's font: the system's Google Sans on a Pixel, otherwise the bundled Google Sans Flex,
     * regular and medium in one family so that a medium weight (long-press menus) is the real cut.
     */
    static Typeface googleSans(Context context) {
        for (String path : SYSTEM_GOOGLE_SANS) {
            if (new File(path).isFile()) return Typeface.create("google-sans-text", Typeface.NORMAL);
        }
        if (sBundledGoogleSans != null) return sBundledGoogleSans;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.res.AssetManager assets = context.getAssets();
                android.graphics.fonts.FontFamily family = new android.graphics.fonts.FontFamily.Builder(
                    new android.graphics.fonts.Font.Builder(assets, BUNDLED_GOOGLE_SANS_REGULAR).setWeight(400).build())
                    .addFont(new android.graphics.fonts.Font.Builder(assets, BUNDLED_GOOGLE_SANS_MEDIUM).setWeight(500).build())
                    .build();
                sBundledGoogleSans = new Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build();
            } else {
                sBundledGoogleSans = Typeface.createFromAsset(context.getAssets(), BUNDLED_GOOGLE_SANS_REGULAR);
            }
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load the bundled Google Sans Flex", e);
            return null;
        }
        return sBundledGoogleSans;
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
