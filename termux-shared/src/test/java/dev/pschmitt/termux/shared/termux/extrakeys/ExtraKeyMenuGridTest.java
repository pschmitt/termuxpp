package dev.pschmitt.termux.shared.termux.extrakeys;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExtraKeyMenuGridTest {

    @Test
    public void itemsFillTheBottomRowFirst() {
        // Nine items: five columns, the first five in the bottom row, four on top (like Gboard).
        assertEquals(5, ExtraKeyMenu.columnsFor(9));
        assertEquals(1, ExtraKeyMenu.rowOf(0, 9, 5));
        assertEquals(1, ExtraKeyMenu.rowOf(4, 9, 5));
        assertEquals(0, ExtraKeyMenu.rowOf(5, 9, 5));
        assertEquals(0, ExtraKeyMenu.rowOf(8, 9, 5));
        // One row: everything is in it.
        assertEquals(0, ExtraKeyMenu.rowOf(2, 3, 3));
    }

    @Test
    public void nearestMapsScreenCellsToItems() {
        int size = 100;
        // Bottom row (y in the second row of cells) holds items 0..4.
        assertEquals(0, ExtraKeyMenu.nearest(10, 150, 9, 5, size, size));
        assertEquals(4, ExtraKeyMenu.nearest(450, 150, 9, 5, size, size));
        // Top row holds items 5..8; past its end the last one is nearest.
        assertEquals(5, ExtraKeyMenu.nearest(10, 50, 9, 5, size, size));
        assertEquals(8, ExtraKeyMenu.nearest(480, 50, 9, 5, size, size));
        // Outside the menu clamps to the edge cells.
        assertEquals(0, ExtraKeyMenu.nearest(-40, 900, 9, 5, size, size));
    }
}
