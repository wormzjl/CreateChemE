package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
