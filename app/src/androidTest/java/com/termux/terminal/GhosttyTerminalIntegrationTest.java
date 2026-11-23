package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.view.TerminalRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class GhosttyTerminalIntegrationTest {
    @Test
    public void bothEnginesRenderKittyGraphics() {
        assumeTrue(Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            verifyEngine(false);
            verifyEngine(true);
        });
    }

    private void verifyEngine(boolean useLibghostty) {
        TerminalOutputRecorder output = new TerminalOutputRecorder();
        TerminalEmulator emulator = new TerminalEmulator(output, 30, 12, 12, 24, 2000, null);
        try {
            if (useLibghostty) assertTrue("libghostty must load", emulator.enableLibghostty(2000));
            append(emulator, "first row\r\nsecond row");
            assertEquals("first row\nsecond row", emulator.getSelectedText(0, 0, 29, 1));

            // An opaque four-by-four red image, scaled across two columns and rows.
            byte[] rgba = new byte[4 * 4 * 4];
            for (int i = 0; i < rgba.length; i += 4) {
                rgba[i] = (byte) 255;
                rgba[i + 3] = (byte) 255;
            }
            append(emulator, "\r\n\033_Ga=T,f=32,s=4,v=4,c=2,r=2,i=42;"
                + Base64.encodeToString(rgba, Base64.NO_WRAP) + "\033\\");
            assertTrue("Kitty acknowledgement", output.text().contains("i=42;OK"));

            Bitmap frame = Bitmap.createBitmap(360, 288, Bitmap.Config.ARGB_8888);
            new TerminalRenderer(20, Typeface.MONOSPACE)
                .render(emulator, new Canvas(frame), 0, -1, -1, -1, -1);
            boolean foundRed = false;
            for (int y = 0; y < frame.getHeight() && !foundRed; y++) {
                for (int x = 0; x < frame.getWidth() && !foundRed; x++) {
                    foundRed = frame.getPixel(x, y) == 0xffff0000;
                }
            }
            assertTrue("Kitty image pixels on the terminal canvas", foundRed);
        } finally {
            emulator.close();
        }
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class TerminalOutputRecorder extends TerminalOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override public void write(byte[] data, int offset, int count) { bytes.write(data, offset, count); }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
}
