package core.amazake.stress.redstone;

import core.amazake.stress.Region;

/**
 * Fixed grid of equal cells inside one region: {@code cols} cells along X, rows along Z, cell {@code i} at
 * column {@code i % cols}, row {@code i / cols}. A {@link #MARGIN}-block border keeps builds off the region edge.
 */
record Layout(Region region, int cellW, int cellD, int cols) {

    static final int MARGIN = 2;
    /** Target region width in blocks; wider cells get one column. */
    static final int TARGET_WIDTH = 128;

    static int columns(int count, int cellW) {
        return Math.max(1, Math.min(Math.max(1, count), TARGET_WIDTH / Math.max(1, cellW)));
    }

    static int chunksX(int count, int cellW) {
        return ceilDiv(2 * MARGIN + columns(count, cellW) * cellW, 16);
    }

    static int chunksZ(int count, int cellW, int cellD) {
        final int cols = columns(count, cellW);
        final int rows = ceilDiv(Math.max(1, count), cols);
        return ceilDiv(2 * MARGIN + rows * cellD, 16);
    }

    static Layout of(Region region, int count, int cellW, int cellD) {
        return new Layout(region, cellW, cellD, columns(count, cellW));
    }

    /** Minimum X of cell {@code i}. */
    int cellX(int i) {
        return this.region.minX() + MARGIN + (i % this.cols) * this.cellW;
    }

    /** Minimum Z of cell {@code i}. */
    int cellZ(int i) {
        return this.region.minZ() + MARGIN + (i / this.cols) * this.cellD;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
