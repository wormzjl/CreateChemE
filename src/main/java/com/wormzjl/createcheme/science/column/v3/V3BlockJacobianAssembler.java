package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Extracts stage-local lower/diagonal/upper blocks from the whole-system finite-difference verification Jacobian. */
final class V3BlockJacobianAssembler {
    private static final double OFF_BAND_TOLERANCE = 1.0e-10;

    private V3BlockJacobianAssembler() {}

    static V3BlockJacobian assemble(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory) {
        V3StageBlockLayout layout = new V3StageBlockLayout(Objects.requireNonNull(problem, "problem"));
        V3FiniteDifferenceJacobian.Jacobian full = V3FiniteDifferenceJacobian.evaluate(
                Objects.requireNonNull(evaluator, "evaluator"), Objects.requireNonNull(coordinates, "coordinates"),
                Objects.requireNonNull(state, "state"), Objects.requireNonNull(workspaceFactory, "workspaceFactory"));
        double[][] values = full.values();
        double[][][] lower = new double[layout.nodeCount()][][];
        double[][][] diagonal = new double[layout.nodeCount()][][];
        double[][][] upper = new double[layout.nodeCount()][][];
        double maximumOffBandMagnitude = 0.0;
        for (int rowNode = 0; rowNode < layout.nodeCount(); rowNode++) {
            lower[rowNode] = block(values, layout, rowNode, rowNode - 1);
            diagonal[rowNode] = block(values, layout, rowNode, rowNode);
            upper[rowNode] = block(values, layout, rowNode, rowNode + 1);
            for (int columnNode = 0; columnNode < layout.nodeCount(); columnNode++) {
                if (Math.abs(columnNode - rowNode) <= 1) continue;
                maximumOffBandMagnitude = Math.max(maximumOffBandMagnitude, maximumAbsolute(block(values, layout, rowNode, columnNode)));
            }
        }
        if (maximumOffBandMagnitude > OFF_BAND_TOLERANCE) {
            throw new IllegalStateException("V3 MESH Jacobian contains an unexpected off-band coupling of "
                    + maximumOffBandMagnitude);
        }
        return new V3BlockJacobian(layout, lower, diagonal, upper, maximumOffBandMagnitude);
    }

    private static double[][] block(double[][] values, V3StageBlockLayout layout, int rowNode, int columnNode) {
        int rows = layout.size(rowNode);
        if (columnNode < 0 || columnNode >= layout.nodeCount()) return new double[rows][0];
        int columns = layout.size(columnNode);
        double[][] block = new double[rows][columns];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(values[layout.start(rowNode) + row], layout.start(columnNode), block[row], 0, columns);
        }
        return block;
    }

    private static double maximumAbsolute(double[][] values) {
        double maximum = 0.0;
        for (double[] row : values) for (double value : row) maximum = Math.max(maximum, Math.abs(value));
        return maximum;
    }
}
