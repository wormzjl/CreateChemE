package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3NormalEquationsTest {
    private static final double OFF_BAND_TOLERANCE = 1.0e-10;
    private static final String OFF_BAND_MESSAGE =
            "V3 damped normal matrix contains an unexpected off-band coupling";

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void bandAwareProductsMatchTheDenseScalarOracleForNonuniformAndTruncatedLayouts(
            V3CondenserPhaseBranch branch) {
        V3ColumnProblem original = V3TruncationSupportTest.problem(branch, 0.0, 2);
        V3ColumnProblem truncated = V3ColumnProblemResolver.withTruncation(
                original, V3TruncationSupportTest.topTailSupport(original));
        for (V3ColumnProblem problem : List.of(original, truncated)) {
            V3StageBlockLayout layout = new V3StageBlockLayout(problem);
            double[][] values = stagePattern(layout);
            V3MeshResidual residual = residual(problem, false);
            V3NormalEquations prepared = V3NormalEquations.prepare(
                    jacobian(problem, values), residual, layout, V3SolveControl.UNBOUNDED);

            double damping = 1.0e-8;
            for (int attempt = 0; attempt < 8; attempt++) {
                assertMatchesOracle(values, residual, layout, damping, prepared);
                damping *= 10.0;
            }
        }
    }

    @Test
    void productsAndGradientKeepTheLegacyAscendingRowSummationOrder() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        double[][] values = zeros(layout);
        values[0][0] = 1.0e16;
        values[1][0] = 1.0;
        values[2][0] = -1.0e16;
        values[0][1] = values[1][1] = values[2][1] = 1.0;
        V3MeshResidual residual = residual(problem, true);
        V3NormalEquations prepared = V3NormalEquations.prepare(
                jacobian(problem, values), residual, layout, V3SolveControl.UNBOUNDED);

        Oracle expected = denseOracle(values, residual, layout, 1.0e-8);
        assertBits(0.0, expected.matrix().get(0, 1), "cancellation-sensitive product");
        assertBits(0.0, expected.gradient()[0], "cancellation-sensitive gradient");
        assertMatchesOracle(values, residual, layout, 1.0e-8, prepared);
    }

    @Test
    void subthresholdOffSpanNoiseIsRetainedInsideTheDiscoveredScalarBand() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        int first = layout.start(0) + layout.size(0) - 1;
        int distant = layout.start(3);
        V3MeshResidual residual = residual(problem, false);
        for (double noise : new double[] {
                Math.nextDown(OFF_BAND_TOLERANCE), OFF_BAND_TOLERANCE,
                -OFF_BAND_TOLERANCE, Double.MIN_VALUE}) {
            double[][] values = noisyWideBandPattern(layout, noise);
            V3NormalEquations prepared = V3NormalEquations.prepare(
                    jacobian(problem, values), residual, layout, V3SolveControl.UNBOUNDED);
            V3BandedMatrix actual = prepared.dampedMatrix(1.0e-8, V3SolveControl.UNBOUNDED);

            assertTrue(actual.upperBandwidth() >= distant - first);
            assertBits(noise, actual.get(first, distant), "retained off-span noise");
            assertMatchesOracle(values, residual, layout, 1.0e-8, prepared);
        }
    }

    @Test
    void offSpanNoiseAboveTheGuardOrAmplifiedByMultiplicationIsStillRejected() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        V3MeshResidual residual = residual(problem, false);
        double[][] aboveThreshold = noisyWideBandPattern(layout, Math.nextUp(OFF_BAND_TOLERANCE));
        double[][] amplified = noisyWideBandPattern(layout, 1.0e-11);
        amplified[0][layout.start(0) + layout.size(0) - 1] = 100.0;
        for (double[][] values : List.of(aboveThreshold, amplified)) {
            IllegalStateException expected = assertThrows(IllegalStateException.class,
                    () -> denseOracle(values, residual, layout, 1.0e-8));
            IllegalStateException actual = assertThrows(IllegalStateException.class,
                    () -> V3NormalEquations.prepare(jacobian(problem, values), residual, layout,
                            V3SolveControl.UNBOUNDED).dampedMatrix(1.0e-8, V3SolveControl.UNBOUNDED));
            assertEquals(OFF_BAND_MESSAGE, expected.getMessage());
            assertEquals(expected.getMessage(), actual.getMessage());
        }
    }

    @Test
    void repeatedDampingAndReturnedArrayMutationDoNotChangeThePreparedBase() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        double[][] values = stagePattern(layout);
        V3FiniteDifferenceJacobian.Jacobian jacobian = jacobian(problem, values);
        double[][] original = jacobian.values();
        V3MeshResidual residual = residual(problem, false);
        V3NormalEquations prepared = V3NormalEquations.prepare(
                jacobian, residual, layout, V3SolveControl.UNBOUNDED);
        V3BandedMatrix first = prepared.dampedMatrix(1.0e-8, V3SolveControl.UNBOUNDED);
        first.set(0, 0, 12345.0);
        prepared.negativeGradient()[0] = 12345.0;
        values[0][0] = -12345.0;

        assertMatchesOracle(original, residual, layout, 1.0e-3, prepared);
        assertMatchesOracle(original, residual, layout, 1.0e-8, prepared);
        for (int row = 0; row < original.length; row++) {
            for (int column = 0; column < original.length; column++) {
                assertBits(original[row][column], jacobian.value(row, column), "unchanged Jacobian");
            }
        }
    }

    @Test
    void aZeroJacobianProducesOnlyTheDampingDiagonalAndZeroGradient() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        double[][] values = zeros(layout);
        V3MeshResidual residual = residual(problem, false);
        V3NormalEquations prepared = V3NormalEquations.prepare(
                jacobian(problem, values), residual, layout, V3SolveControl.UNBOUNDED);
        V3BandedMatrix actual = prepared.dampedMatrix(1.0e-8, V3SolveControl.UNBOUNDED);

        assertEquals(0, actual.lowerBandwidth());
        assertEquals(0, actual.upperBandwidth());
        double[] gradient = prepared.negativeGradient();
        for (int row = 0; row < actual.size(); row++) {
            assertBits(1.0e-8, actual.get(row, row), "zero-Jacobian damping");
            assertBits(0.0, gradient[row], "zero-Jacobian gradient");
        }
        assertMatchesOracle(values, residual, layout, 1.0e-8, prepared);
    }

    @Test
    void cancellationEscapesBothPreparationPathsAndLeavesPreparedMaterializationReusable() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        V3MeshResidual residual = residual(problem, false);
        for (boolean noisy : new boolean[] {false, true}) {
            double[][] values = stagePattern(layout);
            if (noisy) values[0][values.length - 1] = 1.0e-20;
            V3FiniteDifferenceJacobian.Jacobian jacobian = jacobian(problem, values);
            AtomicInteger preparationCheckpoints = new AtomicInteger();
            V3NormalEquations prepared = V3NormalEquations.prepare(
                    jacobian, residual, layout, () -> preparationCheckpoints.incrementAndGet());
            assertTrue(preparationCheckpoints.get() > 2);
            // Probe early, partway through, and at the last boundary without freezing incidental call counts.
            for (int stopAt : new int[] {
                    1, Math.max(2, preparationCheckpoints.get() / 3), preparationCheckpoints.get()}) {
                CancellationException cancelled = new CancellationException("normal preparation cancelled");
                assertSame(cancelled, assertThrows(CancellationException.class,
                        () -> V3NormalEquations.prepare(jacobian, residual, layout,
                                cancellingControl(stopAt, cancelled))));
            }
            for (int stopAt : new int[] {1, 2}) {
                CancellationException cancelled = new CancellationException("normal materialization cancelled");
                assertSame(cancelled, assertThrows(CancellationException.class,
                        () -> prepared.dampedMatrix(1.0e-8, cancellingControl(stopAt, cancelled))));
                assertMatchesOracle(values, residual, layout, 1.0e-8, prepared);
            }
        }
    }

    @Test
    void finiteInputsWhoseNormalDiagonalOverflowsAreNotSilentlyDropped() {
        V3ColumnProblem problem = problem();
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        double[][] values = zeros(layout);
        values[0][0] = Double.MAX_VALUE;
        V3MeshResidual residual = residual(problem, false);

        assertThrows(IllegalArgumentException.class, () -> denseOracle(values, residual, layout, 1.0e-8));
        assertThrows(IllegalArgumentException.class,
                () -> V3NormalEquations.prepare(jacobian(problem, values), residual, layout,
                        V3SolveControl.UNBOUNDED).dampedMatrix(1.0e-8, V3SolveControl.UNBOUNDED));
    }

    private static V3ColumnProblem problem() {
        return V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 2);
    }

    private static V3FiniteDifferenceJacobian.Jacobian jacobian(V3ColumnProblem problem, double[][] values) {
        V3DegreeOfFreedomLedger ledger = problem.degreeOfFreedomLedger();
        return new V3FiniteDifferenceJacobian.Jacobian(
                ledger.equations().stream().map(V3DegreeOfFreedomLedger.Equation::id).toList(),
                ledger.unknowns().stream().map(V3DegreeOfFreedomLedger.Unknown::id).toList(), values);
    }

    private static V3MeshResidual residual(V3ColumnProblem problem, boolean unitResiduals) {
        List<V3MeshResidual.Row> rows = new ArrayList<>();
        List<V3DegreeOfFreedomLedger.Equation> equations = problem.degreeOfFreedomLedger().equations();
        for (int row = 0; row < equations.size(); row++) {
            rows.add(new V3MeshResidual.Row(equations.get(row).id(),
                    unitResiduals ? 1.0 : (row % 7 - 3) * 0.75,
                    unitResiduals ? 1.0 : row % 3 + 1.0));
        }
        return new V3MeshResidual(rows);
    }

    private static double[][] zeros(V3StageBlockLayout layout) {
        int last = layout.nodeCount() - 1;
        int size = layout.start(last) + layout.size(last);
        return new double[size][size];
    }

    private static double[][] stagePattern(V3StageBlockLayout layout) {
        double[][] values = zeros(layout);
        for (int row = 0; row < values.length; row++) {
            if (row == values.length / 2) continue;
            for (int column = 0; column < values.length; column++) {
                if (Math.abs(nodeFor(row, layout) - nodeFor(column, layout)) > 1
                        || column == values.length - 1 || (row * 7 + column * 11) % 5 == 0) continue;
                values[row][column] = ((row * 17 + column * 13) % 19 - 9) * 0.125;
            }
        }
        values[0][0] = Double.MIN_VALUE;
        values[0][1] = -0.0;
        return values;
    }

    private static double[][] noisyWideBandPattern(V3StageBlockLayout layout, double noise) {
        double[][] values = zeros(layout);
        // A valid two-node-span normal coupling makes the scalar band wide enough to retain the noise.
        values[layout.start(4)][layout.start(3)] = 2.0;
        values[layout.start(4)][layout.start(5) + layout.size(5) - 1] = 3.0;
        values[0][layout.start(0) + layout.size(0) - 1] = 1.0;
        values[0][layout.start(3)] = noise;
        return values;
    }

    private static V3SolveControl cancellingControl(int stopAt, CancellationException cancelled) {
        AtomicInteger checkpoints = new AtomicInteger();
        return () -> {
            if (checkpoints.incrementAndGet() == stopAt) throw cancelled;
        };
    }

    private static void assertMatchesOracle(double[][] values, V3MeshResidual residual,
            V3StageBlockLayout layout, double damping, V3NormalEquations prepared) {
        Oracle expected = denseOracle(values, residual, layout, damping);
        V3BandedMatrix actual = prepared.dampedMatrix(damping, V3SolveControl.UNBOUNDED);
        assertEquals(expected.matrix().size(), actual.size());
        assertEquals(expected.matrix().lowerBandwidth(), actual.lowerBandwidth());
        assertEquals(expected.matrix().upperBandwidth(), actual.upperBandwidth());
        double[] gradient = prepared.negativeGradient();
        assertEquals(expected.gradient().length, gradient.length);
        for (int row = 0; row < actual.size(); row++) {
            assertBits(expected.gradient()[row], gradient[row], "gradient " + row);
            for (int column = 0; column < actual.size(); column++) {
                assertBits(expected.matrix().get(row, column), actual.get(row, column),
                        "normal " + row + "/" + column + " damping " + damping);
            }
        }
    }

    /** Literal pre-optimization scalar loops; intentionally independent of the production helper. */
    private static Oracle denseOracle(double[][] values, V3MeshResidual residual,
            V3StageBlockLayout layout, double damping) {
        double[][] normal = new double[values.length][values.length];
        for (int row = 0; row < normal.length; row++) {
            for (int column = row; column < normal.length; column++) {
                double value = 0.0;
                for (double[] jacobianRow : values) value += jacobianRow[row] * jacobianRow[column];
                normal[row][column] = value;
                normal[column][row] = value;
            }
            normal[row][row] += damping * Math.max(1.0, normal[row][row]);
        }
        double[] gradient = new double[values.length];
        for (int row = 0; row < values.length; row++) {
            double scaledResidual = residual.rows().get(row).scaledValue();
            for (int column = 0; column < gradient.length; column++) {
                gradient[column] -= values[row][column] * scaledResidual;
            }
        }
        int lowerBandwidth = 0;
        int upperBandwidth = 0;
        for (int row = 0; row < normal.length; row++) {
            for (int column = 0; column < normal.length; column++) {
                double value = normal[row][column];
                if (Math.abs(nodeFor(column, layout) - nodeFor(row, layout)) > 2
                        && Math.abs(value) > OFF_BAND_TOLERANCE) throw new IllegalStateException(OFF_BAND_MESSAGE);
                if (Math.abs(value) <= OFF_BAND_TOLERANCE) continue;
                if (row >= column) lowerBandwidth = Math.max(lowerBandwidth, row - column);
                else upperBandwidth = Math.max(upperBandwidth, column - row);
            }
        }
        V3BandedMatrix matrix = new V3BandedMatrix(normal.length, lowerBandwidth, upperBandwidth);
        for (int row = 0; row < normal.length; row++) {
            for (int column = Math.max(0, row - lowerBandwidth);
                    column <= Math.min(normal.length - 1, row + upperBandwidth); column++) {
                matrix.set(row, column, normal[row][column]);
            }
        }
        return new Oracle(matrix, gradient);
    }

    private static int nodeFor(int index, V3StageBlockLayout layout) {
        for (int node = 0; node < layout.nodeCount(); node++) {
            if (index >= layout.start(node) && index < layout.start(node) + layout.size(node)) return node;
        }
        throw new AssertionError("test index outside layout: " + index);
    }

    private static void assertBits(double expected, double actual, String description) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), description);
    }

    private record Oracle(V3BandedMatrix matrix, double[] gradient) {}
}
