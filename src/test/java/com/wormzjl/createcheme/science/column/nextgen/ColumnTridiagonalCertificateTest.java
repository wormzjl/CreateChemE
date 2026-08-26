package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnTridiagonalCertificateTest {
    @Test
    void acceptsAnMMatrixWithPositiveEliminationPivots() {
        ColumnTridiagonalCertificate.Result result = ColumnTridiagonalCertificate.certify(
                new double[] {0.0, -0.25, -0.25}, new double[] {1.0, 1.0, 1.0},
                new double[] {-0.25, -0.25, 0.0}, new double[] {1.0, 1.0, 1.0});

        assertTrue(result.accepted());
        assertTrue(result.minimumPositivePivotRow() >= 0);
        assertTrue(result.minimumPositivePivot() > 0.0);
    }

    @Test
    void rejectsAColumnMatrixWhenGenericThomasWouldStillAlgebraicallySolveIt() {
        double[] lower = {0.0, -2.0};
        double[] diagonal = {1.0, 1.0};
        double[] upper = {-2.0, 0.0};
        double[] rhs = {1.0, 1.0};

        ColumnTridiagonalCertificate.Result result = ColumnTridiagonalCertificate.certify(lower, diagonal, upper, rhs);
        assertFalse(result.accepted());
        assertEquals(1, result.row());
        assertTrue(Double.isFinite(ThomasTridiagonalSolver.solve(
                lower, diagonal, upper, rhs, new double[2], new double[2], new double[2])));
    }
}
