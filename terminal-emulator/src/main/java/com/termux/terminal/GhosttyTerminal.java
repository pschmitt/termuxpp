package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.termux.terminal.GhosttyTerminalNative.*;

/** Adapts libghostty's viewport to the terminal view's cell and bitmap model. */
final class GhosttyTerminal {
    private static final int PLACEMENT_SIZE = 14;
    private final TerminalOutput output;
    private long handle;
    private int columns;
    private int rows;
    private int cellWidth;
    private int cellHeight;
    private final int[] metadata = new int[16];
    private final int[] scrollbar = new int[3];
    private int[] codepoints = new int[0];
    private int[] foreground = new int[0];
    private int[] background = new int[0];
    private int[] attributes = new int[0];
    private int[] graphemes = new int[256];
    private int[] placements = new int[PLACEMENT_SIZE * 8];
    private final int[] configuredColors = new int[TextStyle.NUM_INDEXED_COLORS];
    private final int[] palette;
    String title;
    private List<TerminalEmulator.ImagePlacement> imagesForViewport;

    GhosttyTerminal(TerminalOutput output, int columns, int rows, int cellWidth,
                   int cellHeight, int transcriptRows, int[] palette) {
        this.output = output;
        this.palette = palette;
        handle = terminalNew(columns, rows, transcriptRows);
        if (handle == 0) throw new IllegalStateException("Could not initialize libghostty");
        Arrays.fill(configuredColors, -1);
        resize(columns, rows, cellWidth, cellHeight);
    }

    void close() {
        if (handle == 0) return;
        terminalFree(handle);
        handle = 0;
    }

    void resize(int columns, int rows, int cellWidth, int cellHeight) {
        this.columns = columns;
        this.rows = rows;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        terminalResize(handle, columns, rows, cellWidth, cellHeight);
        int cells = columns * rows;
        if (codepoints.length != cells) {
            codepoints = new int[cells];
            foreground = new int[cells];
            background = new int[cells];
            attributes = new int[cells];
        }
    }

    void reset() {
        terminalReset(handle);
    }

    void feed(byte[] data, int length) {
        applyColors();
        write(terminalFeed(handle, data, length));
        int events = terminalEvents(handle);
        if ((events & 1) != 0) output.onBell();
        if ((events & 2) != 0) {
            String old = title;
            title = terminalTitle(handle);
            output.titleChanged(old, title);
        }
    }

    void paste(String text) {
        write(terminalEncodePaste(handle, text.getBytes(StandardCharsets.UTF_8)));
    }

    void mouse(int button, int column, int row, boolean pressed) {
        int action = button == TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED ? 2 : pressed ? 0 : 1;
        int nativeButton = button == TerminalEmulator.MOUSE_WHEELUP_BUTTON ? 4
            : button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON ? 5 : 1;
        write(terminalEncodeMouse(handle, action, nativeButton,
            (column - 0.5f) * cellWidth, (row - 0.5f) * cellHeight, pressed || action == 2));
    }

    boolean mode(int mask) {
        return (metadata[14] & mask) != 0;
    }

    int cursorColumn() {
        return metadata[1];
    }

    int cursorRow() {
        return metadata[2] + scrollbar[1] + scrollbar[2] - scrollbar[0];
    }

    boolean cursorVisible() {
        return metadata[0] != 0 && metadata[4] != 0;
    }

    int cursorStyle() {
        switch (metadata[3]) {
            case 0: return TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR;
            case 2: return TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE;
            default: return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        }
    }

    int historyRows() {
        terminalScrollbar(handle, scrollbar);
        return Math.max(0, scrollbar[0] - scrollbar[2]);
    }

    void readViewport(int topRow, TerminalBuffer buffer) {
        applyColors();
        imagesForViewport = null;
        terminalScroll(handle, 1, 0);
        if (topRow < 0) terminalScroll(handle, 2, topRow);
        terminalScrollbar(handle, scrollbar);
        terminalSnapshot(handle, codepoints, foreground, background, attributes, metadata, graphemes);
        if (graphemes[0] >= graphemes.length) {
            graphemes = new int[graphemes[0] + 1];
            terminalSnapshot(handle, codepoints, foreground, background, attributes, metadata, graphemes);
        }

        int grapheme = 1;
        for (int y = 0; y < rows; y++) {
            TerminalRow row = buffer.allocateSnapshotLine(buffer.externalToInternalRow(topRow + y));
            row.clear(TextStyle.NORMAL);
            for (int x = 0; x < columns; x++) {
                int index = y * columns + x;
                int cp = codepoints[index];
                if (cp == 0 && x > 0 && (attributes[index - 1] & 16) != 0) continue;
                int attrs = attributes[index];
                int effects = attrs & 7;
                if ((attrs & 8) != 0) effects |= TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
                if ((attrs & 32) != 0) effects |= TextStyle.CHARACTER_ATTRIBUTE_BLINK;
                if ((attrs & 512) != 0) effects |= TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
                long style = TextStyle.encode(foreground[index] == metadata[8] ? TextStyle.COLOR_INDEX_FOREGROUND : foreground[index],
                    background[index] == metadata[7] ? TextStyle.COLOR_INDEX_BACKGROUND : background[index], effects);
                row.setChar(x, cp == 0 ? ' ' : cp, style);
                if (grapheme <= graphemes[0] && graphemes[grapheme] == index) {
                    int count = graphemes[grapheme + 1];
                    for (int i = 1; i < count; i++) {
                        int combining = graphemes[grapheme + 2 + i];
                        if (WcWidth.width(combining) <= 0) row.setChar(x, combining, style);
                    }
                    grapheme += 2 + count;
                }
            }
        }
    }

    List<TerminalEmulator.ImagePlacement> readImages() {
        if (imagesForViewport != null) return imagesForViewport;
        int count = terminalGraphics(handle, placements);
        if (count <= 0) return imagesForViewport = java.util.Collections.emptyList();
        if (count > placements.length / PLACEMENT_SIZE) {
            placements = new int[count * PLACEMENT_SIZE];
            count = terminalGraphics(handle, placements);
        }
        SparseArray<Bitmap> images = new SparseArray<>();
        List<TerminalEmulator.ImagePlacement> result = new ArrayList<>(count);
        int[] dimensions = new int[2];
        for (int i = 0; i < count * PLACEMENT_SIZE; i += PLACEMENT_SIZE) {
            int id = placements[i];
            Bitmap bitmap = images.get(id);
            if (bitmap == null) {
                byte[] rgba = terminalImage(handle, id, dimensions);
                if (rgba == null) continue;
                int[] pixels = new int[rgba.length / 4];
                for (int j = 0; j < pixels.length; j++) {
                    int p = j * 4;
                    pixels[j] = (rgba[p + 3] & 255) << 24 | (rgba[p] & 255) << 16
                        | (rgba[p + 1] & 255) << 8 | (rgba[p + 2] & 255);
                }
                bitmap = Bitmap.createBitmap(pixels, dimensions[0], dimensions[1], Bitmap.Config.ARGB_8888);
                images.put(id, bitmap);
            }
            Rect source = new Rect(placements[i + 7], placements[i + 8],
                placements[i + 7] + placements[i + 9], placements[i + 8] + placements[i + 10]);
            result.add(new TerminalEmulator.ImagePlacement(bitmap, source, placements[i + 3],
                placements[i + 4], placements[i + 5], placements[i + 6], placements[i + 11],
                placements[i + 12], placements[i + 13]));
        }
        imagesForViewport = result;
        return result;
    }

    private void applyColors() {
        if (Arrays.equals(configuredColors, palette)) return;
        System.arraycopy(palette, 0, configuredColors, 0, configuredColors.length);
        terminalSetColors(handle, palette[TextStyle.COLOR_INDEX_FOREGROUND],
            palette[TextStyle.COLOR_INDEX_BACKGROUND], palette[TextStyle.COLOR_INDEX_CURSOR], palette);
    }

    private void write(byte[] bytes) {
        if (bytes != null && bytes.length != 0) output.write(bytes, 0, bytes.length);
    }
}
