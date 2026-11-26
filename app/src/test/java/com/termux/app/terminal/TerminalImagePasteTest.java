package com.termux.app.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TerminalImagePasteTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void theHelpersLastLineChoosesTheKeyOrTheText() {
        assertNull(TerminalImagePaste.parse(0, "key\n").error);
        assertNull(TerminalImagePaste.parse(0, "key\n").text);
        assertEquals("/home/x/.cache/termuxpp-paste/a.png",
            TerminalImagePaste.parse(0, "copying...\ntext /home/x/.cache/termuxpp-paste/a.png\n").text);
        assertEquals("Could not paste the image: Could not reach fnuc (ssh exit status 255)",
            TerminalImagePaste.parse(1, "Could not reach fnuc (ssh exit status 255)\n").error);
        assertTrue(TerminalImagePaste.parse(127, "nixpp: termuxpp-image-paste: not found").error
            .contains("needs termuxpp-image-paste"));
        assertNotNull("a successful exit without the protocol is still an error", TerminalImagePaste.parse(0, "").error);
    }

    @Test public void storingKeepsTheImageAndItsTypeForTheGuestsWlPaste() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File source = folder.newFile("shot.png");
        Files.write(source.toPath(), "PNGDATA".getBytes(StandardCharsets.UTF_8));
        File dir = new File(folder.getRoot(), "handover");
        File image = TerminalImagePaste.store(context, Uri.fromFile(source), "image/png", dir);
        assertEquals("PNGDATA", new String(Files.readAllBytes(image.toPath()), StandardCharsets.UTF_8));
        assertEquals("image/png\n", new String(Files.readAllBytes(new File(dir, "type").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(dir, ".image.partial").exists());
    }

    @Test public void anOversizedImageIsRejectedWithoutReplacingThePreviousOne() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(folder.getRoot(), "handover");
        File small = folder.newFile("small.png");
        Files.write(small.toPath(), "OLD".getBytes(StandardCharsets.UTF_8));
        TerminalImagePaste.store(context, Uri.fromFile(small), "image/png", dir);
        File huge = folder.newFile("huge.png");
        try (RandomAccessFile file = new RandomAccessFile(huge, "rw")) {
            file.setLength(TerminalImagePaste.MAX_BYTES + 1);
        }
        assertThrows(IOException.class, () -> TerminalImagePaste.store(context, Uri.fromFile(huge), "image/png", dir));
        assertEquals("OLD", new String(Files.readAllBytes(new File(dir, "image").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(dir, ".image.partial").exists());
    }

    @Test public void onlyAnImageOnTheClipboardIsPastedAsAnImage() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String[] type = new String[1];
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "hello"));
        assertNull(TerminalImagePaste.clipboardImage(context, type));

        File shot = folder.newFile("shot.png");
        try (FileOutputStream out = new FileOutputStream(shot)) {
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        }
        ClipData image = new ClipData("Screenshot", new String[]{"image/png"}, new ClipData.Item(Uri.fromFile(shot)));
        clipboard.setPrimaryClip(image);
        assertEquals(Uri.fromFile(shot), TerminalImagePaste.clipboardImage(context, type));
        assertEquals("image/png", type[0]);
    }
}
