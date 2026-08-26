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
