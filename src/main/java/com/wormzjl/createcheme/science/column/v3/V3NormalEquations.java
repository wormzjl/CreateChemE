package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import java.util.Objects;

/**
 * Immutable, attempt-local normal products reused by the regularized correction's damping trials.
 *
 * <p>Exactly stage-banded Jacobians use compact upper-triangle storage and row-ordered outer products. Any
 * nonzero off-stage entry selects the original dense dot products instead: even subthreshold Jacobian noise
 * can contribute a significant off-band normal entry. Neither path thresholds its operands or changes the
 * ascending residual-row summation order. Returned matrices and gradients never expose the cached products.</p>
 */
final class V3NormalEquations {
    static final String OFF_BAND_MESSAGE = "V3 damped normal matrix contains an unexpected off-band coupling";
    private static final double OFF_BAND_TOLERANCE = 1.0e-10;

    private final double[][] upperProduct;
    private final double[] negativeGradient;
    private final int bandwidth;

    private V3NormalEquations(double[][] upperProduct, double[] negativeGradient, int bandwidth) {
        this.upperProduct = upperProduct;
        this.negativeGradient = negativeGradient;
        this.bandwidth = bandwidth;
    }

    static V3NormalEquations prepare(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3MeshResidual residual,
            V3StageBlockLayout layout, V3SolveControl control) {
        Objects.requireNonNull(jacobian, "jacobian");
        Objects.requireNonNull(residual, "residual");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(control, "control");
        int size = jacobian.unknowns().size();
        int lastNode = layout.nodeCount() - 1;
        if (residual.rows().size() != size || layout.start(lastNode) + layout.size(lastNode) != size) {
            throw new IllegalArgumentException("V3 normal products do not match their residual and stage layout");
        }
        int[] nodes = new int[size];
        for (int node = 0; node < layout.nodeCount(); node++) {
            for (int index = layout.start(node); index < layout.start(node) + layout.size(node); index++) {
                nodes[index] = node;
            }
        }
        double[][] product = exactlyStageBanded(jacobian, nodes, control)
                ? stageBandedProduct(jacobian, layout, nodes, control)
                : denseProduct(jacobian, control);
        int bandwidth = bandwidthAndValidate(product, nodes, control);
        double[] gradient = new double[size];
        for (int row = 0; row < size; row++) {
            control.checkpoint();
            double scaledResidual = residual.rows().get(row).scaledValue();
            for (int column = 0; column < size; column++) {
                gradient[column] -= jacobian.value(row, column) * scaledResidual;
            }
        }
        return new V3NormalEquations(product, gradient, bandwidth);
    }

    /** Applies damping to a fresh matrix, always using the original undamped diagonal. */
    V3BandedMatrix dampedMatrix(double damping, V3SolveControl control) {
        Objects.requireNonNull(control, "control");
        int size = upperProduct.length;
        V3BandedMatrix matrix = new V3BandedMatrix(size, bandwidth, bandwidth);
        for (int row = 0; row < size; row++) {
            control.checkpoint();
            for (int column = Math.max(0, row - bandwidth); column <= Math.min(size - 1, row + bandwidth); column++) {
                double value = product(row, column);
                if (row == column) value += damping * Math.max(1.0, value);
                matrix.set(row, column, value);
            }
        }
        return matrix;
    }

    double[] negativeGradient() {
        return negativeGradient.clone();
    }

    private double product(int row, int column) {
        int first = Math.min(row, column);
        int offset = Math.abs(column - row);
        return offset < upperProduct[first].length ? upperProduct[first][offset] : 0.0;
    }

    private static boolean exactlyStageBanded(
            V3FiniteDifferenceJacobian.Jacobian jacobian, int[] nodes, V3SolveControl control) {
        for (int row = 0; row < nodes.length; row++) {
            control.checkpoint();
            for (int column = 0; column < nodes.length; column++) {
                if (Math.abs(nodes[row] - nodes[column]) > 1 && jacobian.value(row, column) != 0.0) return false;
            }
        }
        return true;
    }

    private static double[][] stageBandedProduct(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3StageBlockLayout layout,
            int[] nodes, V3SolveControl control) {
        double[][] product = new double[nodes.length][];
        int lastNode = layout.nodeCount() - 1;
        for (int row = 0; row < product.length; row++) {
            int lastCoupledNode = Math.min(lastNode, nodes[row] + 2);
            product[row] = new double[layout.start(lastCoupledNode) + layout.size(lastCoupledNode) - row];
        }
        // Every output entry receives its nonzero contributions in the same ascending row order as the
        // original dense dot product. Skipping exact zero products is safe because Jacobian entries are finite.
        for (int residualRow = 0; residualRow < nodes.length; residualRow++) {
            control.checkpoint();
            int firstColumn = layout.start(Math.max(0, nodes[residualRow] - 1));
            int lastColumnNode = Math.min(lastNode, nodes[residualRow] + 1);
            int columnEnd = layout.start(lastColumnNode) + layout.size(lastColumnNode);
            for (int row = firstColumn; row < columnEnd; row++) {
                double left = jacobian.value(residualRow, row);
                if (left == 0.0) continue;
                for (int column = row; column < columnEnd; column++) {
                    double right = jacobian.value(residualRow, column);
                    if (right == 0.0) continue;
                    product[row][column - row] += left * right;
                }
            }
        }
        return product;
    }

    /** Compatibility path: preserve every off-stage noise term before applying the existing normal guard. */
    private static double[][] denseProduct(V3FiniteDifferenceJacobian.Jacobian jacobian, V3SolveControl control) {
        int size = jacobian.unknowns().size();
        double[][] product = new double[size][];
        for (int row = 0; row < size; row++) {
            control.checkpoint();
            product[row] = new double[size - row];
            for (int column = row; column < size; column++) {
                double value = 0.0;
                for (int residualRow = 0; residualRow < size; residualRow++) {
                    value += jacobian.value(residualRow, row) * jacobian.value(residualRow, column);
                }
                product[row][column - row] = value;
            }
        }
        return product;
    }

    private static int bandwidthAndValidate(double[][] product, int[] nodes, V3SolveControl control) {
        int bandwidth = 0;
        for (int row = 0; row < product.length; row++) {
            control.checkpoint();
            for (int offset = 0; offset < product[row].length; offset++) {
                double value = product[row][offset];
                if (Math.abs(nodes[row + offset] - nodes[row]) > 2 && Math.abs(value) > OFF_BAND_TOLERANCE) {
                    throw new IllegalStateException(OFF_BAND_MESSAGE);
                }
                if (Math.abs(value) <= OFF_BAND_TOLERANCE) continue;
                bandwidth = Math.max(bandwidth, offset);
            }
        }
        return bandwidth;
    }
}
