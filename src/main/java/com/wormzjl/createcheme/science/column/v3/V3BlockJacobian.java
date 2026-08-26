package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Immutable lower/diagonal/upper stage-block Jacobian with no hidden dense production representation. */
final class V3BlockJacobian {
    private final V3StageBlockLayout layout;
    private final double[][][] lower;
    private final double[][][] diagonal;
    private final double[][][] upper;
    private final double maximumOffBandMagnitude;

    V3BlockJacobian(
            V3StageBlockLayout layout, double[][][] lower, double[][][] diagonal, double[][][] upper,
            double maximumOffBandMagnitude) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.lower = copyBlocks(lower, layout, -1);
        this.diagonal = copyBlocks(diagonal, layout, 0);
        this.upper = copyBlocks(upper, layout, 1);
        if (!Double.isFinite(maximumOffBandMagnitude) || maximumOffBandMagnitude < 0.0) {
            throw new IllegalArgumentException("V3 block Jacobian off-band magnitude must be finite and nonnegative");
        }
        this.maximumOffBandMagnitude = maximumOffBandMagnitude;
    }

    V3StageBlockLayout layout() { return layout; }
    double[][] lower(int node) { return copy(lower[node]); }
    double[][] diagonal(int node) { return copy(diagonal[node]); }
    double[][] upper(int node) { return copy(upper[node]); }
    double maximumOffBandMagnitude() { return maximumOffBandMagnitude; }

    private static double[][][] copyBlocks(double[][][] blocks, V3StageBlockLayout layout, int columnOffset) {
        blocks = Objects.requireNonNull(blocks, "blocks");
        if (blocks.length != layout.nodeCount()) throw new IllegalArgumentException("V3 block Jacobian node count is invalid");
        double[][][] copy = new double[blocks.length][][];
        for (int node = 0; node < blocks.length; node++) {
            int columns = node + columnOffset < 0 || node + columnOffset >= layout.nodeCount()
                    ? 0 : layout.size(node + columnOffset);
            copy[node] = copy(blocks[node]);
            if (copy[node].length != layout.size(node)) throw new IllegalArgumentException("V3 block Jacobian row size is invalid");
            for (double[] row : copy[node]) {
                if (row.length != columns) throw new IllegalArgumentException("V3 block Jacobian column size is invalid");
                for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 block Jacobian must be finite");
            }
        }
        return copy;
    }

    private static double[][] copy(double[][] values) {
        values = Objects.requireNonNull(values, "values");
        double[][] copy = new double[values.length][];
        for (int row = 0; row < values.length; row++) copy[row] = Objects.requireNonNull(values[row], "row").clone();
        return copy;
    }
}
