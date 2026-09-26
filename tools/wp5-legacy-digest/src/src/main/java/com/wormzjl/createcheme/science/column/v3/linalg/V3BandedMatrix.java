package com.wormzjl.createcheme.science.column.v3.linalg;

import java.util.Arrays;

/** Mutable scalar band matrix with fixed lower/upper bandwidth and no dense backing allocation. */
public final class V3BandedMatrix {
    private final int size;
    private final int lowerBandwidth;
    private final int upperBandwidth;
    private final double[] values;

    public V3BandedMatrix(int size, int lowerBandwidth, int upperBandwidth) {
        if (size < 1 || lowerBandwidth < 0 || upperBandwidth < 0) {
            throw new IllegalArgumentException("V3 band matrix dimensions must be positive with nonnegative bandwidths");
        }
        this.size = size;
        this.lowerBandwidth = lowerBandwidth;
        this.upperBandwidth = upperBandwidth;
        this.values = new double[Math.multiplyExact(size, lowerBandwidth + upperBandwidth + 1)];
    }

    public int size() {
        return size;
    }

    public int lowerBandwidth() {
        return lowerBandwidth;
    }

    public int upperBandwidth() {
        return upperBandwidth;
    }

    public void set(int row, int column, double value) {
        int index = index(row, column);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 band matrix entries must be finite");
        values[index] = value;
    }

    public double get(int row, int column) {
        requireIndex(row);
        requireIndex(column);
        if (!inBand(row, column)) return 0.0;
        return values[storageIndex(row, column)];
    }

    /** Clears every declared band entry without changing shape or allocating a dense matrix. */
    public void clear() {
        Arrays.fill(values, 0.0);
    }

    int firstStoredColumn(int row) {
        return Math.max(0, row - lowerBandwidth);
    }

    int lastStoredColumn(int row) {
        return Math.min(size - 1, row + upperBandwidth);
    }

    private int index(int row, int column) {
        requireIndex(row);
        requireIndex(column);
        if (!inBand(row, column)) {
            throw new IllegalArgumentException("V3 matrix entry is outside the declared scalar bandwidth");
        }
        return storageIndex(row, column);
    }

    private int storageIndex(int row, int column) {
        return row * (lowerBandwidth + upperBandwidth + 1) + column - row + lowerBandwidth;
    }

    private boolean inBand(int row, int column) {
        return column >= row - lowerBandwidth && column <= row + upperBandwidth;
    }

    private void requireIndex(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("V3 band matrix index " + index + " is outside 0.." + (size - 1));
    }
}
