package dev.pschmitt.termux.shared.termux.extrakeys;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * A long-press menu of alternates above an extra key, like the accent popup of a phone keyboard.
 * The finger that long-pressed the key keeps sliding: the item nearest to it is highlighted and
 * lifting the finger picks it. All positions are screen coordinates, so tracking needs no layout pass.
 */
final class ExtraKeyMenu {
    /** Items per row before the menu wraps; a few wide rows read faster than a tall column. */
    static final int MAX_COLUMNS = 6;

    private final List<ExtraKeyButton> items;
    private final PopupWindow window;
    private final TextView[] cells;
    private final int columns;
    private final int cellWidth;
    private final int cellHeight;
    private final int padding;
    private final int left;
    private final int top;
    private final int textColor;
    private final int activeTextColor;
    private final int activeBackgroundColor;
    private final float radius;
    private int selected = -1;
    /** The item marked {@code default: true}, or -1: picked while the finger has not moved away. */
    private final int defaultIndex;
    private final float startX;
    private final float startY;
    /** How far the finger must move before the nearest item replaces the default. */
    private final float slop;
    private boolean moved;

    /** The key font at medium weight: Gboard draws its popup glyphs heavier than regular text. */
    @NonNull
    static Typeface popupTypeface(@Nullable Typeface typeface) {
        Typeface base = typeface != null ? typeface : Typeface.DEFAULT;
        if (android.os.Build.VERSION.SDK_INT >= 28) return Typeface.create(base, 500, false);
        return Typeface.create(base, Typeface.BOLD);
    }

    /** The column count for {@code count} items: one row up to {@link #MAX_COLUMNS}, then balanced rows. */
    static int columnsFor(int count) {
        if (count <= MAX_COLUMNS) return Math.max(1, count);
        int rows = (count + MAX_COLUMNS - 1) / MAX_COLUMNS;
        return (count + rows - 1) / rows;
    }

    /**
     * The row on screen of item {@code index}, counted from the top: like Gboard, the first items
     * fill the bottom row, next to the finger, and a shorter row (if any) sits on top.
     */
    static int rowOf(int index, int count, int columns) {
        int rows = (count + columns - 1) / columns;
        return rows - 1 - index / columns;
    }

    /**
     * Index of the item nearest to a point given relative to the menu's content origin, for a grid
     * of {@code count} cells in {@code columns} columns of {@code width} x {@code height}, filled
     * from the bottom row up (see {@link #rowOf}).
     */
    static int nearest(float x, float y, int count, int columns, int width, int height) {
        int rows = (count + columns - 1) / columns;
        int row = Math.max(0, Math.min(rows - 1, (int) Math.floor(y / height)));
        int itemRow = rows - 1 - row;
        int inRow = itemRow == rows - 1 ? count - itemRow * columns : columns;
        int column = Math.max(0, Math.min(inRow - 1, (int) Math.floor(x / width)));
        return itemRow * columns + column;
    }

    ExtraKeyMenu(@NonNull View anchor, @NonNull List<ExtraKeyButton> items, float touchRawX, float touchRawY,
                 int panelColor, int textColor, int activeTextColor, int activeBackgroundColor,
                 float cornerRadiusPx, @Nullable Typeface typeface, boolean allCaps) {
        this.items = items;
        this.textColor = textColor;
        this.activeTextColor = activeTextColor;
        this.activeBackgroundColor = activeBackgroundColor;
        Context context = anchor.getContext();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float density = metrics.density;
        padding = Math.round(6 * density);
        // Square cells one key high, like Gboard's: the highlight is a circle in them.
        cellHeight = Math.max(Math.round(40 * density), anchor.getHeight());
        cellWidth = cellHeight;
        radius = Math.max(cornerRadiusPx, 8 * density);
        columns = columnsFor(items.size());
        int rows = (items.size() + columns - 1) / columns;
        int width = columns * cellWidth + 2 * padding;
        int height = rows * cellHeight + 2 * padding;

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(columns);
        grid.setRowCount(rows);
        grid.setPadding(padding, padding, padding, padding);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(panelColor);
        // Rounded like the circles inside it, as Gboard's panel is.
        panel.setCornerRadius(Math.max(radius, cellHeight / 2f) + padding);
        grid.setBackground(panel);
        cells = new TextView[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ExtraKeyButton item = items.get(i);
            TextView cell = new TextView(context);
            cell.setText(item.getDisplay());
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(22);
            cell.setSingleLine(true);
            cell.setAllCaps(allCaps && !item.hasCustomDisplay());
            cell.setTypeface(popupTypeface(typeface));
            cell.setTextColor(item.textColor != null ? item.textColor : textColor);
            cell.setContentDescription(item.getDisplay());
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(rowOf(i, items.size(), columns)), GridLayout.spec(i % columns));
            params.width = cellWidth;
            params.height = cellHeight;
            grid.addView(cell, params);
            cells[i] = cell;
        }
        for (int i = 0; i < cells.length; i++) paint(i, false);

        int defaultItem = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isMenuDefault()) {
                defaultItem = i;
                break;
            }
        }
        defaultIndex = defaultItem;
        startX = touchRawX;
        startY = touchRawY;
        slop = cellWidth / 2f;

        // Center over the key (or put the default item over it) and sit just above it; keep the
        // whole menu on screen.
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int x = defaultIndex < 0 ? location[0] + anchor.getWidth() / 2 - width / 2
            : location[0] + anchor.getWidth() / 2 - padding - (defaultIndex % columns) * cellWidth - cellWidth / 2;
        x = Math.max(0, Math.min(x, metrics.widthPixels - width));
        int y = location[1] - height - Math.round(4 * density);
        if (y < 0) y = location[1] + anchor.getHeight() + Math.round(4 * density);
        left = x;
        top = y;

        window = new PopupWindow(grid, width, height);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setClippingEnabled(false);
        window.setTouchable(false);
        window.setFocusable(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) window.setElevation(6 * density);
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, left, top);
        if (defaultIndex >= 0) {
            selected = defaultIndex;
            paint(selected, true);
        } else {
            track(touchRawX, touchRawY);
        }
    }

    /**
     * Highlights the item nearest to the finger; with a default item, only once the finger has moved
     * away from where the menu opened. Returns whether the highlight moved.
     */
    boolean track(float rawX, float rawY) {
        if (defaultIndex >= 0 && !moved) {
            if (Math.hypot(rawX - startX, rawY - startY) < slop) return false;
            moved = true;
        }
        int index = nearest(rawX - left - padding, rawY - top - padding, items.size(), columns, cellWidth, cellHeight);
        if (index == selected) return false;
        if (selected >= 0) paint(selected, false);
        selected = index;
        paint(selected, true);
        return true;
    }

    @Nullable
    ExtraKeyButton selected() {
        return selected < 0 ? null : items.get(selected);
    }

    void dismiss() {
        window.dismiss();
    }

    private void paint(int index, boolean active) {
        TextView cell = cells[index];
        ExtraKeyButton item = items.get(index);
        if (active) {
            GradientDrawable highlight = new GradientDrawable();
            highlight.setShape(GradientDrawable.OVAL);
            highlight.setColor(activeBackgroundColor);
            cell.setBackground(highlight);
            cell.setTextColor(activeTextColor);
        } else {
            if (item.backgroundColor != null) {
                GradientDrawable background = new GradientDrawable();
                background.setShape(GradientDrawable.OVAL);
                background.setColor(item.backgroundColor);
                cell.setBackground(background);
            } else {
                cell.setBackground(null);
            }
            cell.setTextColor(item.textColor != null ? item.textColor : textColor);
        }
    }
}
