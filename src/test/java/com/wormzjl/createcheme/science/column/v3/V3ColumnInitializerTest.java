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

class V3ColumnInitializerTest {
    @Test
    void positiveRefluxSeedIsFinitePositiveAndClosesEveryComponentMaterialRowWithoutPublishingSuccess() {
        V3ColumnProblem problem = problem(1.0, V3CondenserPhaseBranch.TWO_PHASE, new double[] {30.0, 60.0});
        MaterialOnlyThermo thermo = new MaterialOnlyThermo();

        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
        V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, 0.0)
                .evaluate(seed.state(), thermo.newWorkspace());

        assertEquals(V3FeedPhase.LIQUID.name(), seed.evidence().feedPhase());
        assertEquals("organic-reflux material-closed traffic seed", seed.evidence().trafficPolicy());
        assertTrue(residual.rows().stream().filter(row -> row.equation().family()
                        == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE)
                .allMatch(row -> Math.abs(row.physicalValue()) <= 1.0e-12));
        assertTrue(seed.state().liquidFlow(0, 0) > 0.0);
        assertTrue(seed.state().vaporFlow(problem.topology().reboilerNode(), 1) > 0.0);
    }

    @Test
    void zeroRefluxTwoPhaseAndVaporOnlySeedBranchesRemainMaterialClosed() {
        MaterialOnlyThermo thermo = new MaterialOnlyThermo();
        for (V3CondenserPhaseBranch branch : V3CondenserPhaseBranch.values()) {
            V3ColumnProblem problem = problem(0.0, branch, new double[] {30.0, 60.0});
            V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
            V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, 0.0)
                    .evaluate(seed.state(), thermo.newWorkspace());

            assertEquals("zero-reflux finite traffic seed", seed.evidence().trafficPolicy());
            assertTrue(residual.rows().stream().filter(row -> row.equation().family()
                            == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE)
                    .allMatch(row -> Math.abs(row.physicalValue()) <= 1.0e-12));
        }
    }

    @Test
    void exactZeroFeedComponentIsEliminatedFromTheSeedWithoutAFloor() {
        V3ColumnProblem problem = problem(1.0, V3CondenserPhaseBranch.TWO_PHASE, new double[] {30.0, 0.0});

        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, new MaterialOnlyThermo(), new V3ThermoWorkspace(2));

        assertEquals(1, problem.activeComponentBasis().componentCount());
        assertEquals(1, seed.state().componentCount());
        assertEquals(0, problem.activeComponentBasis().publicIndex(0));
    }

    private static V3ColumnProblem problem(double refluxRatio, V3CondenserPhaseBranch branch, double[] feed) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:initializer", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), feed, 460.0, 4, 2, 250_000.0, 750.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(refluxRatio),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, branch);
    }

    private static final class MaterialOnlyThermo implements V3ThermoModel {
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return new V3FugacityResult(phase, new double[] {0.0, 0.0}, 1.0, 0.0, 1, 0.0);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return 0.0;
        }

        @Override
        public V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
            return new V3FlashResult(V3FeedPhase.LIQUID, 0, 0.0, overallComposition, new double[0], 0.0,
                    "manufactured all-liquid feed flash");
        }
    }
}
