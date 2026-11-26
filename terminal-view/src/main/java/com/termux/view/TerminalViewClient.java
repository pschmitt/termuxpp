package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    /**
     * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
     */
    float onScale(float scale);



    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    void onSingleTapUp(MotionEvent e);

    /**
     * The highest tap count that the client has an action for: {@code 2} if there is a double tap
     * action but no triple tap action, and so on. {@code 1} or less if only single taps have an
     * action, or none do, in which case {@link #onSingleTapUp(MotionEvent)} alone acts on taps,
     * as soon as the platform has confirmed a single tap. For {@code 2} or more
     * {@link #onTapCount(int)} acts on taps, and {@link #onSingleTapUp(MotionEvent)} must not.
     *
     * The default is {@code 1}.
     */
    /**
     * Whether keyboards may commit images into the terminal, such as a screenshot from the keyboard's
     * clipboard, a GIF or a sticker. The terminal then offers {@link TerminalView#IMAGE_MIME_TYPES}.
     */
    default boolean acceptsImageInput() {
        return false;
    }

    /**
     * An image a keyboard committed into the terminal.
     *
     * @param release Releases the temporary read permission of the image; run it once the image
     *                was read, also when that fails.
     * @return Whether the image was accepted. If not, the permission is released for the client.
     */
    default boolean onImageInput(@androidx.annotation.NonNull android.net.Uri uri,
                                 @androidx.annotation.NonNull String mimeType,
                                 @androidx.annotation.NonNull Runnable release) {
        return false;
    }

    default int getMaxTapActionCount() {
        return 1;
    }

    /**
     * Called for a finger tap sequence that is over, if {@link #getMaxTapActionCount()} is {@code 2}
     * or more: {@code 1} for a lone tap, {@code 2} for two taps that followed each other fast and
     * close enough, and so on. Taps that wait for a possible next tap are delivered when it is
     * clear that none follows, after the platform's double tap timeout. Not called for external
     * mouse events, long presses, drags, multi touch gestures or while text is selected.
     *
     * The taps are also forwarded to the terminal application as mouse clicks, if it has enabled
     * mouse tracking.
     */
    default void onTapCount(int tapCount) {
    }

    /** Tap sequence with the terminal-relative location of the final tap. */
    default void onTapCount(int tapCount, float x, float y) {
        onTapCount(tapCount);
    }

    default boolean hasMultiFingerSwipeAction(int fingers, MultiFingerSwipe.Direction direction) {
        return false;
    }

    default void onMultiFingerSwipe(int fingers, MultiFingerSwipe.Direction direction, float x, float y) {
    }

    boolean shouldBackButtonBeMappedToEscape();

    boolean shouldEnforceCharBasedInput();

    boolean shouldUseCtrlSpaceWorkaround();

    boolean isTerminalViewSelected();



    void copyModeChanged(boolean copyMode);



    boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

    boolean onKeyUp(int keyCode, KeyEvent e);

    boolean onLongPress(MotionEvent event);



    boolean readControlKey();

    boolean readAltKey();

    boolean readShiftKey();

    boolean readFnKey();



    boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session);


    void onEmulatorSet();


    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
