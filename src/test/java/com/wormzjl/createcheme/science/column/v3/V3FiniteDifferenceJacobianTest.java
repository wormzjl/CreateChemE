package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
