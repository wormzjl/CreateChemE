package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3FiniteDifferenceJacobianTest {
    @Test
    void compactStorageMatchesEveryDenseBitForCentralAndColoredPaths() {
        for (int trays : new int[] {4, 20}) {
            for (var branch : V3CondenserPhaseBranch.values()) {
                V3ColumnProblem problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(
                        V3ColumnInput.SCHEMA_VERSION, "test:compact", "test:compact",
                        new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30, 60},
                        450, trays, 2, 250_000, 750, List.of(
                                new V3ColumnSpecification.CondenserOutletTemperature(400),
                                new V3ColumnSpecification.OrganicRefluxRatio(branch == V3CondenserPhaseBranch.VAPOR_ONLY ? 0 : 1),
                                new V3ColumnSpecification.ReboilerDuty(0))), branch);
                LinearThermo thermo = new LinearThermo();
                var state = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state();
                var evaluator = new V3MeshResidualEvaluator(problem, thermo, 0);
                var coordinates = new V3DryMeshCoordinateMap(problem);
                for (var scale : V3FiniteDifferenceJacobian.DifferenceScale.values()) {
                    var dense = V3FiniteDifferenceJacobian.evaluate(evaluator, coordinates, state, thermo::newWorkspace, scale);
                    var compact = V3FiniteDifferenceJacobian.evaluateCompact(evaluator, coordinates, state,
                            thermo::newWorkspace, scale, V3SolveControl.UNBOUNDED);
                    assertTrue(compact.storedValueCount() < dense.storedValueCount() * 0.6);
                    double[][] snapshot = compact.values();
                    for (int row = 0; row < snapshot.length; row++) {
                        for (int column = 0; column < snapshot.length; column++) {
                            long bits = Double.doubleToRawLongBits(dense.value(row, column));
                            assertEquals(bits, Double.doubleToRawLongBits(compact.value(row, column)));
                            assertEquals(bits, Double.doubleToRawLongBits(snapshot[row][column]));
                            snapshot[row][column] = 999;
                            assertEquals(bits, Double.doubleToRawLongBits(compact.value(row, column)));
                        }
                    }
                }
            }
        }
    }

    @Test
    void compactStorageRetainsOffStageNoiseAndSignedZeroForExistingBandGuards() throws Exception {
        var problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, "test:compact-noise", "test:compact-noise",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30, 60},
                450, 4, 2, 250_000, 750, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400),
                        new V3ColumnSpecification.OrganicRefluxRatio(1),
                        new V3ColumnSpecification.ReboilerDuty(0))), V3CondenserPhaseBranch.TWO_PHASE);
        var thermo = new LinearThermo();
        var state = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state();
        var evaluator = new V3MeshResidualEvaluator(problem, thermo, 0);
        var coordinates = new V3DryMeshCoordinateMap(problem);
        for (double noise : new double[] {-0.0, 1e-14, 1e-6}) {
            var compact = V3FiniteDifferenceJacobian.evaluateCompact(evaluator, coordinates, state,
                    thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);
            // Inject a nonlocal derivative into the private builder to exercise its rare expansion path.
            // The physical fixture itself has no off-stage coupling.
            var field = compact.getClass().getDeclaredField("values");
            field.setAccessible(true);
            Object storage = field.get(compact);
            var setter = storage.getClass().getDeclaredMethod("set", int.class, int.class, double.class);
            setter.setAccessible(true);
            int column = coordinates.coordinateCount() - 1;
            setter.invoke(storage, 0, column, noise);
            assertEquals(Double.doubleToRawLongBits(noise), Double.doubleToRawLongBits(compact.value(0, column)));
            var dense = jacobian(problem, compact.values());
            var layout = new V3StageBlockLayout(problem);
            if (noise > 1e-10) {
                assertEquals(assertThrows(IllegalStateException.class,
                        () -> V3SimultaneousColumnSolver.toBandedMatrix(dense, layout)).getMessage(),
                        assertThrows(IllegalStateException.class,
                                () -> V3SimultaneousColumnSolver.toBandedMatrix(compact, layout)).getMessage());
            } else {
                var residual = evaluator.evaluate(state, thermo.newWorkspace());
                var expected = V3NormalEquations.prepare(dense, residual, layout, V3SolveControl.UNBOUNDED);
                var actual = V3NormalEquations.prepare(compact, residual, layout, V3SolveControl.UNBOUNDED);
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected.negativeGradient(), actual.negativeGradient());
                var gradient = V3SimultaneousColumnSolver.class.getDeclaredMethod("normalizedNegativeGradient",
                        V3FiniteDifferenceJacobian.Jacobian.class, V3MeshResidual.class, V3DryMeshCoordinateMap.class);
                gradient.setAccessible(true);
                for (double physical : new double[] {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                        1.0, -1.0, Double.MAX_VALUE, -Double.MAX_VALUE}) {
                    var rows = new java.util.ArrayList<V3MeshResidual.Row>();
                    for (int row = 0; row < residual.rows().size(); row++) {
                        rows.add(new V3MeshResidual.Row(residual.rows().get(row).equation(),
                                row % 2 == 0 ? physical : -physical, row % 3 == 0 ? Double.MIN_NORMAL : 1.0));
                    }
                    var extremes = new V3MeshResidual(rows);
                    double[] denseGradient = V3NormalEquations.prepare(dense, extremes, layout,
                            V3SolveControl.UNBOUNDED).negativeGradient();
                    double[] compactGradient = V3NormalEquations.prepare(compact, extremes, layout,
                            V3SolveControl.UNBOUNDED).negativeGradient();
                    double[] denseDirection = (double[]) gradient.invoke(null, dense, extremes, coordinates);
                    double[] compactDirection = (double[]) gradient.invoke(null, compact, extremes, coordinates);
                    for (int index = 0; index < denseGradient.length; index++) {
                        assertEquals(Double.doubleToRawLongBits(denseGradient[index]),
                                Double.doubleToRawLongBits(compactGradient[index]));
                        assertEquals(Double.doubleToRawLongBits(denseDirection[index]),
                                Double.doubleToRawLongBits(compactDirection[index]));
                    }
                }
                var expectedMatrix = expected.dampedMatrix(1e-3, V3SolveControl.UNBOUNDED);
                var actualMatrix = actual.dampedMatrix(1e-3, V3SolveControl.UNBOUNDED);
                for (int row = 0; row <= column; row++) for (int col = 0; col <= column; col++) {
                    assertEquals(Double.doubleToRawLongBits(expectedMatrix.get(row, col)),
                            Double.doubleToRawLongBits(actualMatrix.get(row, col)));
                }
            }
        }
    }

    @Test
    void stageColoredFiniteDifferenceMatchesIndependentCentralColumns() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, "test:colored", "test:colored",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0},
                450.0, 4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0))), V3CondenserPhaseBranch.TWO_PHASE);
        LinearThermo thermo = new LinearThermo();
        V3DryMeshState state = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);

        V3FiniteDifferenceJacobian.Jacobian central = V3FiniteDifferenceJacobian.evaluate(
                evaluator, coordinates, state, thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.FINE);
        V3FiniteDifferenceJacobian.Jacobian colored = V3FiniteDifferenceJacobian.evaluateStageColored(
                evaluator, coordinates, state, thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.FINE);

        double[][] expected = central.values();
        double[][] actual = colored.values();
        for (int row = 0; row < expected.length; row++) {
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals(expected[row][column], actual[row][column], 1.0e-8,
                        "row=" + row + " column=" + column);
            }
        }
    }

    @Test
    void scalarReadsAndArraySnapshotsDoNotExposeTheJacobianStorage() {
        V3ColumnProblem problem = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 2);
        int size = problem.degreeOfFreedomLedger().unknownCount();
        double[][] values = new double[size][size];
        values[0][0] = 3.5;
        values[1][0] = -0.0;
        V3FiniteDifferenceJacobian.Jacobian jacobian = jacobian(problem, values);
        values[0][0] = 99.0;
        values[1] = new double[size];
        double[][] snapshot = jacobian.values();
        snapshot[0][0] = -99.0;
        snapshot[1] = new double[size];

        assertEquals(3.5, jacobian.value(0, 0));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(jacobian.value(1, 0)));
        assertEquals(3.5, jacobian.values()[0][0]);
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(jacobian.values()[1][0]));
    }

    @Test
    void malformedAndNonfiniteJacobianMatricesAreRejectedBeforeScalarReads() {
        V3ColumnProblem problem = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 2);
        int size = problem.degreeOfFreedomLedger().unknownCount();
        assertThrows(NullPointerException.class, () -> jacobian(problem, null));
        assertThrows(IllegalArgumentException.class, () -> jacobian(problem, new double[size - 1][size]));
        double[][] jagged = new double[size][size];
        jagged[size - 1] = new double[size - 1];
        assertThrows(IllegalArgumentException.class, () -> jacobian(problem, jagged));
        double[][] nullRow = new double[size][size];
        nullRow[size - 1] = null;
        assertThrows(NullPointerException.class, () -> jacobian(problem, nullRow));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double[][] values = new double[size][size];
            values[size - 1][size - 1] = invalid;
            assertThrows(IllegalArgumentException.class, () -> jacobian(problem, values));
        }
    }

    private static V3FiniteDifferenceJacobian.Jacobian jacobian(V3ColumnProblem problem, double[][] values) {
        V3DegreeOfFreedomLedger ledger = problem.degreeOfFreedomLedger();
        return new V3FiniteDifferenceJacobian.Jacobian(
                ledger.equations().stream().map(V3DegreeOfFreedomLedger.Equation::id).toList(),
                ledger.unknowns().stream().map(V3DegreeOfFreedomLedger.Unknown::id).toList(), values);
    }

    private static final class LinearThermo implements V3ThermoModel {
        private static final V3ComponentBasis BASIS = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return BASIS; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return new V3FugacityResult(phase, new double[] {0.0, 0.0}, 1.0,
                    temperatureKelvin * (phase == V3Phase.LIQUID ? 100.0 : 120.0), 1, 0.1);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return temperatureKelvin * (phase == V3Phase.LIQUID ? 100.0 : 120.0);
        }

        @Override
        public V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
            return new V3FlashResult(V3FeedPhase.TWO_PHASE, 0, 0.5, overallComposition, overallComposition,
                    temperatureKelvin * 110.0, "manufactured two-phase flash");
        }
    }
}
