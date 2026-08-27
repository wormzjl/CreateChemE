package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
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

    /** Materializes only the declared tri-block coupling as the scalar band matrix used by the current LU solver. */
    V3BandedMatrix toBandedMatrix() {
        int lowerBandwidth = 0;
        int upperBandwidth = 0;
        for (int rowNode = 0; rowNode < layout.nodeCount(); rowNode++) {
            int rowStart = layout.start(rowNode);
            lowerBandwidth = Math.max(lowerBandwidth, maximumLowerBandwidth(
                    lower[rowNode], rowStart, rowNode == 0 ? 0 : layout.start(rowNode - 1)));
            int diagonalLower = maximumLowerBandwidth(diagonal[rowNode], rowStart, rowStart);
            int diagonalUpper = maximumUpperBandwidth(diagonal[rowNode], rowStart, rowStart);
            lowerBandwidth = Math.max(lowerBandwidth, diagonalLower);
            upperBandwidth = Math.max(upperBandwidth, diagonalUpper);
            if (rowNode + 1 < layout.nodeCount()) {
                upperBandwidth = Math.max(upperBandwidth,
                        maximumUpperBandwidth(upper[rowNode], rowStart, layout.start(rowNode + 1)));
            }
        }
        V3BandedMatrix matrix = new V3BandedMatrix(totalSize(), lowerBandwidth, upperBandwidth);
        for (int rowNode = 0; rowNode < layout.nodeCount(); rowNode++) {
            int rowStart = layout.start(rowNode);
            if (rowNode > 0) putBlock(matrix, lower[rowNode], rowStart, layout.start(rowNode - 1));
            putBlock(matrix, diagonal[rowNode], rowStart, rowStart);
            if (rowNode + 1 < layout.nodeCount()) putBlock(matrix, upper[rowNode], rowStart, layout.start(rowNode + 1));
        }
        return matrix;
    }

    private int totalSize() {
        int finalNode = layout.nodeCount() - 1;
        return layout.start(finalNode) + layout.size(finalNode);
    }

    private static int maximumLowerBandwidth(double[][] block, int rowStart, int columnStart) {
        int maximum = 0;
        for (int row = 0; row < block.length; row++) {
            for (int column = 0; column < block[row].length; column++) {
                if (Math.abs(block[row][column]) > 0.0) {
                    maximum = Math.max(maximum, rowStart + row - columnStart - column);
                }
            }
        }
        return Math.max(0, maximum);
    }

    private static int maximumUpperBandwidth(double[][] block, int rowStart, int columnStart) {
        int maximum = 0;
        for (int row = 0; row < block.length; row++) {
            for (int column = 0; column < block[row].length; column++) {
                if (Math.abs(block[row][column]) > 0.0) {
                    maximum = Math.max(maximum, columnStart + column - rowStart - row);
                }
            }
        }
        return Math.max(0, maximum);
    }

    private static void putBlock(V3BandedMatrix matrix, double[][] block, int rowStart, int columnStart) {
        for (int row = 0; row < block.length; row++) {
            for (int column = 0; column < block[row].length; column++) {
                if (block[row][column] != 0.0) {
                    matrix.set(rowStart + row, columnStart + column, block[row][column]);
                }
            }
        }
    }

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
