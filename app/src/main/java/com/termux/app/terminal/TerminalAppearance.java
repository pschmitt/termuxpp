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

    /** The extra keys font ({@code extra-keys.font}) at a weight ({@code extra-keys.font-weight}, 0 to keep it). */
    public static Typeface loadExtraKeysFont(Context context, String choice, int weight) {
        return withWeight(loadExtraKeysFont(context, choice), weight);
    }

    /** The typeface at a weight from 100 to 900, or as it is for 0 (or before Android 9). */
    public static Typeface withWeight(Typeface typeface, int weight) {
        if (weight <= 0 || android.os.Build.VERSION.SDK_INT < 28) return typeface;
        return Typeface.create(typeface != null ? typeface : Typeface.DEFAULT, weight, false);
    }

    /** Where Pixels keep Google Sans, which they expose as the {@code google-sans-text} family. */
    private static final String[] SYSTEM_GOOGLE_SANS = {
        "/product/fonts/GoogleSans-Regular.ttf", "/system/fonts/GoogleSans-Regular.ttf"};
    /** Google Sans Flex (SIL OFL 1.1) with a variable weight, built by scripts/build-extra-keys-font.sh. */
    private static final String BUNDLED_GOOGLE_SANS = "extra-keys-fonts/GoogleSansFlex.ttf";
    private static Typeface sBundledGoogleSans;

    /**
     * Gboard's font: the system's Google Sans on a Pixel, otherwise the bundled Google Sans Flex, with
     * a real cut for every weight from 100 to 900 (extra-keys.font-weight, the long-press menus).
     */
    static Typeface googleSans(Context context) {
        for (String path : SYSTEM_GOOGLE_SANS) {
            if (new File(path).isFile()) return Typeface.create("google-sans-text", Typeface.NORMAL);
        }
        if (sBundledGoogleSans != null) return sBundledGoogleSans;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                java.nio.ByteBuffer data = readAsset(context, BUNDLED_GOOGLE_SANS);
                android.graphics.fonts.FontFamily.Builder family = null;
                for (int weight = 100; weight <= 900; weight += 100) {
                    android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(data.duplicate())
                        .setWeight(weight).setFontVariationSettings("'wght' " + weight).build();
                    if (family == null) family = new android.graphics.fonts.FontFamily.Builder(font);
                    else family.addFont(font);
                }
                sBundledGoogleSans = new Typeface.CustomFallbackBuilder(family.build())
                    .setStyle(new android.graphics.fonts.FontStyle(400, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
                    .setSystemFallback("sans-serif").build();
            } else {
                sBundledGoogleSans = Typeface.createFromAsset(context.getAssets(), BUNDLED_GOOGLE_SANS);
            }
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load the bundled Google Sans Flex", e);
            return null;
        }
        return sBundledGoogleSans;
    }

    /** An asset in a direct buffer, as {@link android.graphics.fonts.Font.Builder} needs, read once for all weights. */
    private static java.nio.ByteBuffer readAsset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) > 0; ) bytes.write(chunk, 0, n);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(bytes.size());
            buffer.put(bytes.toByteArray()).flip();
            return buffer;
        }
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
