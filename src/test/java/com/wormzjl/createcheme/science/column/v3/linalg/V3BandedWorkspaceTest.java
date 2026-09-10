package com.wormzjl.createcheme.science.column.v3.linalg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class V3BandedWorkspaceTest {
    @Test
    void clearingShapeChangesAndFailedSolvesCannotLeakIntoLaterCorrectionsOrEarlierResults() {
        var workspace = new V3BandedPivotedSolver.Workspace();
        var matrix = workspace.clearedMatrix(3, 1, 1);
        matrix.set(0, 1, 2);
        matrix.set(1, 0, 3);
        matrix.set(1, 1, 4);
        matrix.set(1, 2, 5);
        matrix.set(2, 1, 6);
        matrix.set(2, 2, 7);
        var saved = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {-4, 10, 9}, workspace));
        double[] savedSolution = saved.solution();
        assertSame(matrix, workspace.clearedMatrix(3, 1, 1));
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) {
            assertEquals(0L, Double.doubleToRawLongBits(matrix.get(row, column)));
        }
        assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class,
                V3BandedPivotedSolver.solve(matrix, new double[3], workspace));
        matrix.set(0, 0, 2);
        matrix.set(1, 1, 3);
        matrix.set(2, 2, 4);
        var next = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {2, 6, 12}, workspace));
        assertArrayEquals(new double[] {1, 2, 3}, next.solution());
        assertArrayEquals(savedSolution, saved.solution());
        var diagonal = workspace.clearedMatrix(2, 0, 0);
        assertNotSame(matrix, diagonal);
        diagonal.set(0, 0, Double.MIN_VALUE);
        diagonal.set(1, 1, 2 * Double.MIN_VALUE);
        var subnormal = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(diagonal, new double[] {Double.MIN_VALUE, 2 * Double.MIN_VALUE}, workspace));
        assertArrayEquals(new double[] {1, 1}, subnormal.solution());
        assertArrayEquals(savedSolution, saved.solution());
    }
}
