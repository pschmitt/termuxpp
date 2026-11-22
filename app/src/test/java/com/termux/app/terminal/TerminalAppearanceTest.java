package com.termux.app.terminal;

import android.content.Context;

import com.termux.terminal.TextStyle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class TerminalAppearanceTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void presetsAndDefaultOverrideConfigWithoutEditingIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File guest = temporary.newFolder("guest");
        File nativeHome = temporary.newFolder("native");
        File guestColors = new File(guest, "colors.properties");
        Files.write(guestColors.toPath(), "background=rgb:11/22/33\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(nativeHome, "colors.properties").toPath(),
            "background=#445566\n".getBytes(StandardCharsets.UTF_8));
        List<String> paths = Arrays.asList(guest.getPath(), nativeHome.getPath());

        assertEquals(0xff112233, background(context, TerminalAppearance.CONFIG, paths));
        assertEquals(0xff0e1019, background(context, "argonaut.properties", paths));
        assertEquals(0xff000000, background(context, TerminalAppearance.DEFAULT, paths));
        assertEquals(0xff112233, background(context, TerminalAppearance.CONFIG, paths));
        assertTrue(guestColors.delete());
        assertEquals(0xff445566, background(context, TerminalAppearance.CONFIG, paths));
    }

    @Test
    public void invalidPaletteFallsBackAsAWhole() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File config = temporary.newFolder("config");
        Files.write(new File(config, "colors.properties").toPath(),
            "background=#123456\ncolor9999=#000000\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(0xff000000, background(context, TerminalAppearance.CONFIG,
            Arrays.asList(config.getPath())));
    }

    private int background(Context context, String choice, List<String> paths) {
        return TerminalAppearance.loadColors(context, choice, paths)
            .mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }
}
