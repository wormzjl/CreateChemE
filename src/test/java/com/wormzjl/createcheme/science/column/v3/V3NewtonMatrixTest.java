package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3NewtonMatrixTest {
    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void smallEssentialCouplingsSurviveBeforeLinearScaling(V3CondenserPhaseBranch branch) {
        V3ColumnProblem original = V3TruncationSupportTest.problem(branch, 0.0, 2);
        V3ColumnProblem reduced = V3ColumnProblemResolver.withTruncation(
                original, V3TruncationSupportTest.topTailSupport(original));
        for (V3ColumnProblem problem : List.of(original, reduced)) {
            V3StageBlockLayout layout = new V3StageBlockLayout(problem);
            int size = problem.degreeOfFreedomLedger().unknownCount();
            int adjacent = layout.start(1);
            double[][] values = new double[size][size];
            double[] rhs = new double[size];
            Arrays.fill(rhs, 1.0);
            for (int index = 0; index < size; index++) values[index][index] = 1.0;
            // An invertible two-variable subsystem whose upper coupling used to be dropped at 1e-10.
            values[0][0] = values[adjacent][adjacent] = 0.0;
            values[0][adjacent] = rhs[0] = 1.0e-12;
            values[adjacent][0] = 1.0;

            var matrix = V3SimultaneousColumnSolver.toBandedMatrix(jacobian(problem, values), layout);
            var solved = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                    V3BandedPivotedSolver.solve(matrix, rhs));

            double[] expected = new double[size];
            Arrays.fill(expected, 1.0);
            assertArrayEquals(expected, solved.solution(), 1.0e-14);
            assertEquals(0.0, solved.backwardError());
        }
    }

    @Test
    void nonadjacentStageCouplingStillFailsTheStructuralGuard() {
        V3ColumnProblem problem = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        int size = problem.degreeOfFreedomLedger().unknownCount();
        double[][] values = new double[size][size];
        values[0][layout.start(2)] = 1.0;

        assertThrows(IllegalStateException.class,
                () -> V3SimultaneousColumnSolver.toBandedMatrix(jacobian(problem, values), layout));
    }

    private static V3FiniteDifferenceJacobian.Jacobian jacobian(V3ColumnProblem problem, double[][] values) {
        return new V3FiniteDifferenceJacobian.Jacobian(
                problem.degreeOfFreedomLedger().equations().stream().map(V3DegreeOfFreedomLedger.Equation::id).toList(),
                problem.degreeOfFreedomLedger().unknowns().stream().map(V3DegreeOfFreedomLedger.Unknown::id).toList(), values);
    }
}
