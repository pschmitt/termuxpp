package com.termux.app.terminal;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * The arithmetic of reordering the session list of the sidebar, kept free of Android classes so that
 * the drag and the actions dialog share one tested definition of where a session ends up.
 */
public final class SessionReorder {

    private SessionReorder() {}

    /**
     * Where a drag moves the dragged row to when the finger crosses a neighbour.
     *
     * @param dragIndex The 0-based index of the dragged row.
     * @param count The number of rows.
     * @param fingerY The vertical position of the finger, in the same coordinates as the other arguments.
     * @param previousTop The top of the row above the dragged one, or {@link Integer#MIN_VALUE} if there is none
     *                    or it is not laid out.
     * @param previousHeight The height of that row.
     * @param nextTop The top of the row below the dragged one, or {@link Integer#MIN_VALUE} if there is none
     *                or it is not laid out.
     * @param nextHeight The height of that row.
     * @return The index the dragged row should move to: {@code dragIndex - 1}, {@code dragIndex + 1} or
     *         {@code dragIndex}. The row only moves once the finger passes the middle of the neighbour, which
     *         keeps rows of different heights from swapping back and forth.
     */
    public static int dragTarget(int dragIndex, int count, int fingerY,
                                 int previousTop, int previousHeight, int nextTop, int nextHeight) {
        if (dragIndex < 0 || dragIndex >= count) return dragIndex;
        if (dragIndex > 0 && previousTop != Integer.MIN_VALUE && fingerY < previousTop + previousHeight / 2)
            return dragIndex - 1;
        if (dragIndex < count - 1 && nextTop != Integer.MIN_VALUE && fingerY > nextTop + nextHeight / 2)
            return dragIndex + 1;
        return dragIndex;
    }

    /**
     * Where the top of the dragged row is drawn: under the finger, at the offset the row was grabbed at, but
     * never outside the list.
     */
    public static int draggedTop(float fingerY, float grabOffset, int rowHeight, int listHeight) {
        int top = Math.round(fingerY - grabOffset);
        return Math.max(0, Math.min(Math.max(0, listHeight - rowHeight), top));
    }

    /**
     * How far to scroll the list in one frame while a row is dragged: negative up, positive down, zero when
     * the finger is not within {@code edge} of the top or the bottom. The step grows linearly up to
     * {@code maxStep} as the finger gets closer to (or goes beyond) the edge.
     */
    public static int autoScrollStep(float fingerY, int listHeight, float edge, float maxStep) {
        if (edge <= 0 || listHeight <= 0) return 0;
        // A list shorter than two edges must not scroll both ways at once: the nearer edge wins.
        float fromTop = fingerY;
        float fromBottom = listHeight - fingerY;
        if (fromTop < edge && fromTop <= fromBottom)
            return -Math.round(maxStep * Math.min(1f, (edge - fromTop) / edge));
        if (fromBottom < edge)
            return Math.round(maxStep * Math.min(1f, (edge - fromBottom) / edge));
        return 0;
    }

    /** The index that Move up, Move down, Move to top and Move to bottom lead to, clamped to the list. */
    public static int targetFor(@NonNull Move move, int index, int count) {
        if (count <= 0) return index;
        int target;
        switch (move) {
            case UP: target = index - 1; break;
            case DOWN: target = index + 1; break;
            case TOP: target = 0; break;
            case BOTTOM: target = count - 1; break;
            default: target = index;
        }
        return Math.max(0, Math.min(count - 1, target));
    }

    /** A move of one session, as offered by the actions dialog. */
    public enum Move { UP, DOWN, TOP, BOTTOM }

    /**
     * Move one element of the list to another index, shifting the elements in between. Does nothing if
     * either index is out of range.
     *
     * @return Whether the list changed.
     */
    public static <T> boolean move(@NonNull List<T> list, int from, int to) {
        int size = list.size();
        if (from < 0 || from >= size || to < 0 || to >= size || from == to) return false;
        list.add(to, list.remove(from));
        return true;
    }

}
