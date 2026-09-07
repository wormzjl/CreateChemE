package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Immutable physical and scaled residual vector in the exact semantic order of the V3 DOF ledger. */
final class V3MeshResidual {
    private final List<Row> rows;

    V3MeshResidual(List<Row> rows) {
        this.rows = List.copyOf(rows);
        if (this.rows.isEmpty() || this.rows.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 MESH residual must contain non-null rows");
        }
    }

    List<Row> rows() { return rows; }

    double maximumAbsoluteScaledResidual() {
        double maximum = 0.0;
        for (Row row : rows) maximum = Math.max(maximum, Math.abs(row.scaledValue()));
        return maximum;
    }

    /**
     * Row scales of this evaluation, in ledger order.
     *
     * <p>A Jacobian or a line search that must compare two states differentiates or compares the physical
     * rows against one frozen copy of this vector rather than against each candidate's own scales, so that a
     * row scale which is itself a function of the state cannot move underneath the comparison.</p>
     */
    double[] scales() {
        double[] scales = new double[rows.size()];
        for (int row = 0; row < scales.length; row++) scales[row] = rows.get(row).scale();
        return scales;
    }

    record Row(V3DegreeOfFreedomLedger.EquationId equation, double physicalValue, double scale) {
        Row {
            equation = Objects.requireNonNull(equation, "equation");
            if (!Double.isFinite(physicalValue) || !Double.isFinite(scale) || scale <= 0.0) {
                throw new IllegalArgumentException("V3 MESH residual rows must be finite with a positive scale");
            }
        }

        double scaledValue() { return physicalValue / scale; }
    }
}
