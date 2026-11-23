package com.termux.terminal;

/** JNI bindings for one libghostty-vt instance. All calls run on the session thread. */
final class GhosttyTerminalNative {
    static {
        System.loadLibrary("termux-ghostty");
    }

    private GhosttyTerminalNative() {}

    static native long terminalNew(int columns, int rows, int scrollbackRows);
    static native void terminalFree(long handle);
    static native void terminalReset(long handle);
    static native byte[] terminalFeed(long handle, byte[] data, int length);
    static native int terminalEvents(long handle);
    static native String terminalTitle(long handle);
    static native void terminalResize(long handle, int columns, int rows, int cellWidth, int cellHeight);
    static native void terminalSetColors(long handle, int foreground, int background, int cursor, int[] palette);
    static native void terminalScroll(long handle, int mode, int delta);
    static native void terminalScrollbar(long handle, int[] result);
    static native int terminalSnapshot(long handle, int[] codepoints, int[] foreground, int[] background,
                                       int[] attributes, int[] metadata, int[] graphemes);
    static native int terminalGraphics(long handle, int[] placements);
    static native byte[] terminalImage(long handle, int imageId, int[] dimensions);
    static native byte[] terminalEncodePaste(long handle, byte[] text);
    static native byte[] terminalEncodeMouse(long handle, int action, int button, float x, float y,
                                             boolean buttonHeld);
}
