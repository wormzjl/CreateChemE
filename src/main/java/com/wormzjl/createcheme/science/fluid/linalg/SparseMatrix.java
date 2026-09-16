package com.wormzjl.createcheme.science.fluid.linalg;

import java.util.Objects;

/** Deeply immutable square matrix in compressed sparse column order; rows in a column are unique and sorted. */
public final class SparseMatrix {
    private final int size;
    private final int[] columnOffsets;
    private final int[] rows;
    private final double[] values;

    public SparseMatrix(int size, int[] columnOffsets, int[] rows, double[] values) {
        this(size, columnOffsets, rows, values, true);
    }

    /**
     * Adopts arrays the caller has just built and never touches again: the defensive copy is
     * skipped, every structural and finiteness check is unchanged, and the result stays deeply
     * immutable because the caller gives up its references. For solver-internal assembly, where a
     * colored Jacobian is three freshly allocated arrays per build.
     */
    public static SparseMatrix adopting(int size, int[] columnOffsets, int[] rows, double[] values) {
        return new SparseMatrix(size, columnOffsets, rows, values, false);
    }

    private SparseMatrix(int size, int[] columnOffsets, int[] rows, double[] values, boolean copy) {
        if (size < 0 || size == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid matrix size");
        }
        this.size = size;
        Objects.requireNonNull(columnOffsets, "columnOffsets");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(values, "values");
        this.columnOffsets = copy ? columnOffsets.clone() : columnOffsets;
        this.rows = copy ? rows.clone() : rows;
        this.values = copy ? values.clone() : values;
        if (this.columnOffsets.length != size + 1 || this.rows.length != this.values.length
                || this.columnOffsets[0] != 0 || this.columnOffsets[size] != this.values.length) {
            throw new IllegalArgumentException("Inconsistent CSC dimensions");
        }
        for (int column = 0; column < size; column++) {
            int start = this.columnOffsets[column];
            int end = this.columnOffsets[column + 1];
            if (start < 0 || end < start || end > this.values.length) {
                throw new IllegalArgumentException("Invalid column offsets");
            }
            int previousRow = -1;
            for (int entry = start; entry < end; entry++) {
                if (this.rows[entry] <= previousRow || this.rows[entry] >= size) {
                    throw new IllegalArgumentException("Rows must be in range, sorted, and unique");
                }
                if (!Double.isFinite(this.values[entry])) {
                    throw new IllegalArgumentException("Matrix values must be finite");
                }
                previousRow = this.rows[entry];
            }
        }
    }

    public int size() { return size; }
    public int nonzeroCount() { return values.length; }
    public int columnStart(int column) { return columnOffsets[column]; }
    public int columnEnd(int column) { return columnOffsets[column + 1]; }
    public int rowAt(int entry) { return rows[entry]; }
    public double valueAt(int entry) { return values[entry]; }
}
