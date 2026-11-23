package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.termux.terminal.TerminalBitmap;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    public enum TypefaceStyle {
        NORMAL,
        ITALIC,
        BOLD,
        BOLD_ITALIC
    }

    final int mTextSize;
    final Typeface mTypeface;
    private final Typeface[] mTypefaceByStyle = new Typeface[4];
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, null, null, null);
    }

    public TerminalRenderer(int textSize, Typeface normalTypeface, Typeface italicTypeface,
                            Typeface boldTypeface, Typeface boldItalicTypeface) {
        mTextSize = textSize;
        mTypeface = normalTypeface != null ? normalTypeface : Typeface.MONOSPACE;
        mTypefaceByStyle[TypefaceStyle.NORMAL.ordinal()] = mTypeface;
        mTypefaceByStyle[TypefaceStyle.ITALIC.ordinal()] = italicTypeface;
        mTypefaceByStyle[TypefaceStyle.BOLD.ordinal()] = boldTypeface;
        mTypefaceByStyle[TypefaceStyle.BOLD_ITALIC.ordinal()] = boldItalicTypeface;

        mTextPaint.setTypeface(mTypeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    public Typeface getTypeface(TypefaceStyle style) {
        return mTypefaceByStyle[style.ordinal()];
    }

    private static final int KITTY_UNICODE_PLACEHOLDER = 0x10EEEE;

    private static final int[] KITTY_DIACRITICS = {
        0x0305, 0x030D, 0x030E, 0x0310, 0x0312, 0x033D, 0x033E, 0x033F, 0x0346, 0x034A,
        0x034B, 0x034C, 0x0350, 0x0351, 0x0352, 0x0357, 0x035B, 0x0363, 0x0364, 0x0365,
        0x0366, 0x0367, 0x0368, 0x0369, 0x036A, 0x036B, 0x036C, 0x036D, 0x036E, 0x036F,
        0x0483, 0x0484, 0x0485, 0x0486, 0x0487, 0x0592, 0x0593, 0x0594, 0x0595, 0x0597,
        0x0598, 0x0599, 0x059C, 0x059D, 0x059E, 0x059F, 0x05A0, 0x05A1, 0x05A8, 0x05A9,
        0x05AB, 0x05AC, 0x05AF, 0x05C4, 0x0610, 0x0611, 0x0612, 0x0613, 0x0614, 0x0615,
        0x0616, 0x0617, 0x0657, 0x0658, 0x0659, 0x065A, 0x065B, 0x065D, 0x065E, 0x06D6,
        0x06D7, 0x06D8, 0x06D9, 0x06DA, 0x06DB, 0x06DC, 0x06DF, 0x06E0, 0x06E1, 0x06E2,
        0x06E4, 0x06E7, 0x06E8, 0x06EB, 0x06EC, 0x0730, 0x0732, 0x0733, 0x0735, 0x0736,
        0x073A, 0x073D, 0x073F, 0x0740, 0x0741, 0x0743, 0x0745, 0x0747, 0x0749, 0x074A,
        0x07EB, 0x07EC, 0x07ED, 0x07EE, 0x07EF, 0x07F0, 0x07F1, 0x07F3, 0x0816, 0x0817,
        0x0818, 0x0819, 0x081B, 0x081C, 0x081D, 0x081E, 0x081F, 0x0820, 0x0821, 0x0822,
        0x0823, 0x0825, 0x0826, 0x0827, 0x0829, 0x082A, 0x082B, 0x082C, 0x082D, 0x0951,
        0x0953, 0x0954, 0x0F82, 0x0F83, 0x0F86, 0x0F87, 0x135D, 0x135E, 0x135F, 0x17DD,
        0x193A, 0x1A17, 0x1A75, 0x1A76, 0x1A77, 0x1A78, 0x1A79, 0x1A7A, 0x1A7B, 0x1A7C,
        0x1B6B, 0x1B6D, 0x1B6E, 0x1B6F, 0x1B70, 0x1B71, 0x1B72, 0x1B73, 0x1CD0, 0x1CD1,
        0x1CD2, 0x1CDA, 0x1CDB, 0x1CE0, 0x1DC0, 0x1DC1, 0x1DC3, 0x1DC4, 0x1DC5, 0x1DC6,
        0x1DC7, 0x1DC8, 0x1DC9, 0x1DCB, 0x1DCC, 0x1DD1, 0x1DD2, 0x1DD3, 0x1DD4, 0x1DD5,
        0x1DD6, 0x1DD7, 0x1DD8, 0x1DD9, 0x1DDA, 0x1DDB, 0x1DDC, 0x1DDD, 0x1DDE, 0x1DDF,
        0x1DE0, 0x1DE1, 0x1DE2, 0x1DE3, 0x1DE4, 0x1DE5, 0x1DE6, 0x1DFE, 0x20D0, 0x20D1,
        0x20D4, 0x20D5, 0x20D6, 0x20D7, 0x20DB, 0x20DC, 0x20E1, 0x20E7, 0x20E9, 0x20F0,
        0x2CEF, 0x2CF0, 0x2CF1, 0x2DE0, 0x2DE1, 0x2DE2, 0x2DE3, 0x2DE4, 0x2DE5, 0x2DE6,
        0x2DE7, 0x2DE8, 0x2DE9, 0x2DEA, 0x2DEB, 0x2DEC, 0x2DED, 0x2DEE, 0x2DEF, 0x2DF0,
        0x2DF1, 0x2DF2, 0x2DF3, 0x2DF4, 0x2DF5, 0x2DF6, 0x2DF7, 0x2DF8, 0x2DF9, 0x2DFA,
        0x2DFB, 0x2DFC, 0x2DFD, 0x2DFE, 0x2DFF, 0xA66F, 0xA67C, 0xA67D, 0xA6F0, 0xA6F1,
        0xA8E0, 0xA8E1, 0xA8E2, 0xA8E3, 0xA8E4, 0xA8E5, 0xA8E6, 0xA8E7, 0xA8E8, 0xA8E9,
        0xA8EA, 0xA8EB, 0xA8EC, 0xA8ED, 0xA8EE, 0xA8EF, 0xA8F0, 0xA8F1, 0xAAB0, 0xAAB2,
        0xAAB3, 0xAAB7, 0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xFE20, 0xFE21, 0xFE22, 0xFE23,
        0xFE24, 0xFE25, 0xFE26, 0x10A0F, 0x10A38, 0x1D185, 0x1D186, 0x1D187, 0x1D188,
        0x1D189, 0x1D1AA, 0x1D1AB, 0x1D1AC, 0x1D1AD, 0x1D242, 0x1D243, 0x1D244
    };

    private static final Map<Integer, Integer> KITTY_DIACRITIC_TO_INDEX = new HashMap<>();
    static {
        for (int i = 0; i < KITTY_DIACRITICS.length; i++) {
            KITTY_DIACRITIC_TO_INDEX.put(KITTY_DIACRITICS[i], i);
        }
    }

    private static final class BitmapDrawOp {
        final Bitmap mBitmap;
        final Rect mSrcRect;
        final RectF mDestRect;
        final int mZIndex;

        BitmapDrawOp(Bitmap bitmap, Rect srcRect, RectF destRect, int zIndex) {
            mBitmap = bitmap;
            mSrcRect = srcRect;
            mDestRect = destRect;
            mZIndex = zIndex;
        }
    }

    private static final Comparator<BitmapDrawOp> BITMAP_Z_ORDER = (a, b) -> Integer.compare(a.mZIndex, b.mZIndex);

    private BitmapDrawOp buildVirtualPlaceholderDrawOp(TerminalEmulator emulator, long style,
                                                       char[] line, int startCharIndex, int charsUsedInLine,
                                                       float left, float top) {
        int idx = startCharIndex + 2; // Skip U+10EEEE (2 UTF-16 chars).
        int d1 = -1, d2 = -1, d3 = -1;
        for (int n = 0; n < 3 && idx < charsUsedInLine; n++) {
            int cp = Character.codePointAt(line, idx);
            if (WcWidth.width(cp) > 0) break;
            Integer dIdx = KITTY_DIACRITIC_TO_INDEX.get(cp);
            if (dIdx != null) {
                if (n == 0) d1 = dIdx;
                else if (n == 1) d2 = dIdx;
                else d3 = dIdx;
            }
            idx += Character.charCount(cp);
        }
        int decodedFg = TextStyle.decodeForeColor(style);
        long low24 = ((decodedFg & 0xff000000) == 0xff000000) ? (decodedFg & 0x00ffffffL) : (decodedFg & 0xffL);
        long imageId = (d3 >= 0) ? (((long) d3 << 24) | low24) : low24;
        if (imageId <= 0) return null;

        TerminalEmulator.KittyVirtualPlacement vp = emulator.getKittyVirtualPlacement(imageId, 0);
        if (vp == null) return null;
        Bitmap bmp = emulator.getKittyImageBitmap(imageId);
        if (bmp == null) return null;

        int vpRow = (d1 >= 0) ? d1 : 0;
        int vpCol = (d2 >= 0) ? d2 : 0;
        int cols = Math.max(1, vp.mCols);
        int rows = Math.max(1, vp.mRows);
        if (vpRow >= rows || vpCol >= cols) return null;

        int imgSx = Math.max(0, vp.mSourceX);
        int imgSy = Math.max(0, vp.mSourceY);
        int imgSw = vp.mSourceWidth > 0 ? Math.min(vp.mSourceWidth, bmp.getWidth() - imgSx) : (bmp.getWidth() - imgSx);
        int imgSh = vp.mSourceHeight > 0 ? Math.min(vp.mSourceHeight, bmp.getHeight() - imgSy) : (bmp.getHeight() - imgSy);
        if (imgSw <= 0 || imgSh <= 0) return null;

        int cellSx = imgSx + (imgSw * vpCol) / cols;
        int cellSy = imgSy + (imgSh * vpRow) / rows;
        int cellEx = imgSx + (imgSw * (vpCol + 1)) / cols;
        int cellEy = imgSy + (imgSh * (vpRow + 1)) / rows;
        Rect srcRect = new Rect(cellSx, cellSy, Math.max(cellSx + 1, cellEx), Math.max(cellSy + 1, cellEy));
        RectF destRect = new RectF(left + vp.mOffsetX, top + vp.mOffsetY,
            left + vp.mOffsetX + mFontWidth, top + vp.mOffsetY + mFontLineSpacing);
        return new BitmapDrawOp(bmp, srcRect, destRect, vp.mZIndex);
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        List<TerminalEmulator.ImagePlacement> placements = mEmulator.getImagePlacements(topRow);
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        List<BitmapDrawOp> backgroundBitmaps = null;
        List<BitmapDrawOp> foregroundBitmaps = null;

        for (TerminalEmulator.ImagePlacement image : placements) {
            float left = image.column * mFontWidth + image.offsetX;
            float top = mFontLineSpacingAndAscent + image.row * mFontLineSpacing + image.offsetY;
            BitmapDrawOp op = new BitmapDrawOp(image.bitmap, image.source,
                new RectF(left, top, left + image.width, top + image.height), image.z);
            if (image.z < 0) {
                if (backgroundBitmaps == null) backgroundBitmaps = new ArrayList<>();
                backgroundBitmaps.add(op);
            } else {
                if (foregroundBitmaps == null) foregroundBitmaps = new ArrayList<>();
                foregroundBitmaps.add(op);
            }
        }

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;
            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();
            int currentCharIndex = 0;
            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final long style = lineObject.getStyle(column);
                if (TextStyle.isTerminalBitmap(style)) {
                    Bitmap bitmap = screen.getSixelBitmap(style);
                    if (bitmap != null) {
                        TerminalBitmap tb = screen.getTerminalBitmap(style);
                        int zIndex = tb != null ? tb.getKittyZIndex() : 0;
                        int offsetX = tb != null ? tb.getKittyOffsetX() : 0;
                        int offsetY = tb != null ? tb.getKittyOffsetY() : 0;
                        float left = column * mFontWidth + offsetX;
                        float top = heightOffset - mFontLineSpacing + offsetY;
                        Rect bitmapSrcRect = screen.getSixelRect(style);
                        RectF bitmapDestRect = new RectF(left, top, left + mFontWidth, top + mFontLineSpacing);
                        BitmapDrawOp op = new BitmapDrawOp(bitmap, bitmapSrcRect, bitmapDestRect, zIndex);
                        if (zIndex < 0) {
                            if (backgroundBitmaps == null) backgroundBitmaps = new ArrayList<>();
                            backgroundBitmaps.add(op);
                        } else {
                            if (foregroundBitmaps == null) foregroundBitmaps = new ArrayList<>();
                            foregroundBitmaps.add(op);
                        }
                    }
                    column += 1;
                    currentCharIndex += charsForCodePoint;
                    continue;
                } else if (codePoint == KITTY_UNICODE_PLACEHOLDER) {
                    float left = column * mFontWidth;
                    float top = heightOffset - mFontLineSpacing;
                    BitmapDrawOp op = buildVirtualPlaceholderDrawOp(mEmulator, style, line, currentCharIndex, charsUsedInLine, left, top);
                    if (op != null) {
                        if (op.mZIndex < 0) {
                            if (backgroundBitmaps == null) backgroundBitmaps = new ArrayList<>();
                            backgroundBitmaps.add(op);
                        } else {
                            if (foregroundBitmaps == null) foregroundBitmaps = new ArrayList<>();
                            foregroundBitmaps.add(op);
                        }
                    }
                    column += 1;
                    currentCharIndex += charsForCodePoint;
                    while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                        currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                    }
                    continue;
                }
                column += Math.max(1, WcWidth.width(codePoint));
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }
        }

        if (backgroundBitmaps != null) {
            if (backgroundBitmaps.size() > 1) Collections.sort(backgroundBitmaps, BITMAP_Z_ORDER);
            for (BitmapDrawOp op : backgroundBitmaps) {
                canvas.drawBitmap(op.mBitmap, op.mSrcRect, op.mDestRect, null);
            }
        }

        heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final long style = lineObject.getStyle(column);
                if (TextStyle.isTerminalBitmap(style) || codePoint == KITTY_UNICODE_PLACEHOLDER) {
                    // The text run that is pending must be drawn before skipping the bitmap/placeholder cell.
                    if (lastRunStartColumn >= 0 && column > lastRunStartColumn) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }

                    column += 1;
                    currentCharIndex += charsForCodePoint;
                    if (codePoint == KITTY_UNICODE_PLACEHOLDER) {
                        while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                            currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                        }
                    }
                    // The next text run starts after the cell of the bitmap, both in columns and in
                    // the characters of the line.
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = false;
                    continue;
                }
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0 || column == lastRunStartColumn) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            if (columnWidthSinceLastRun > 0) {
                drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                    measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
            }
        }

        if (foregroundBitmaps != null) {
            if (foregroundBitmaps.size() > 1) Collections.sort(foregroundBitmaps, BITMAP_Z_ORDER);
            for (BitmapDrawOp op : foregroundBitmaps) {
                canvas.drawBitmap(op.mBitmap, op.mSrcRect, op.mDestRect, null);
            }
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            prepareStyleOrFallback(bold, italic);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            }
            if (bold || italic) prepareStyleOrFallback(false, false);
        }

        if (savedMatrix) canvas.restore();
    }

    private void prepareStyleOrFallback(boolean bold, boolean italic) {
        Typeface italicTypeface = mTypefaceByStyle[TypefaceStyle.ITALIC.ordinal()];
        Typeface boldTypeface = mTypefaceByStyle[TypefaceStyle.BOLD.ordinal()];
        Typeface boldItalicTypeface = mTypefaceByStyle[TypefaceStyle.BOLD_ITALIC.ordinal()];

        if (bold && italic) {
            if (boldItalicTypeface != null) {
                mTextPaint.setTypeface(boldItalicTypeface);
                mTextPaint.setFakeBoldText(false);
                mTextPaint.setTextSkewX(0.f);
            } else if (italicTypeface != null) {
                mTextPaint.setTypeface(italicTypeface);
                mTextPaint.setFakeBoldText(true);
                mTextPaint.setTextSkewX(0.f);
            } else if (boldTypeface != null) {
                mTextPaint.setTypeface(boldTypeface);
                mTextPaint.setFakeBoldText(false);
                mTextPaint.setTextSkewX(-0.35f);
            } else {
                mTextPaint.setTypeface(mTypeface);
                mTextPaint.setFakeBoldText(true);
                mTextPaint.setTextSkewX(-0.35f);
            }
        } else if (italic) {
            if (italicTypeface != null) {
                mTextPaint.setTypeface(italicTypeface);
                mTextPaint.setTextSkewX(0.f);
            } else {
                mTextPaint.setTypeface(mTypeface);
                mTextPaint.setTextSkewX(-0.35f);
            }
            mTextPaint.setFakeBoldText(false);
        } else if (bold) {
            if (boldTypeface != null) {
                mTextPaint.setTypeface(boldTypeface);
                mTextPaint.setFakeBoldText(false);
            } else {
                mTextPaint.setTypeface(mTypeface);
                mTextPaint.setFakeBoldText(true);
            }
            mTextPaint.setTextSkewX(0.f);
        } else {
            mTextPaint.setTypeface(mTypeface);
            mTextPaint.setFakeBoldText(false);
            mTextPaint.setTextSkewX(0.f);
        }
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
