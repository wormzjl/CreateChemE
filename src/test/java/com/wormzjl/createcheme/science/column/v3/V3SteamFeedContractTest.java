package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SteamFeedContractTest {
    @Test
    void canonicalizesSteamAndResolvesItsKnownUpwardProfileWithoutChangingDegreesOfFreedom() {
        V3ColumnInput dry = input(8_000_000.0, List.of());
        V3ColumnInput wet = input(0.0, List.of(new V3SteamFeedSpec(5, 4.0, 450.0), new V3SteamFeedSpec(2, 2.0, 450.0)));
        V3ColumnProblem plain = V3ColumnProblemResolver.resolve(dry, V3CondenserPhaseBranch.TWO_PHASE);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(wet, V3CondenserPhaseBranch.TWO_PHASE);

        assertEquals(List.of(new V3SteamFeedSpec(2, 2.0, 450.0), new V3SteamFeedSpec(5, 4.0, 450.0)), wet.steamFeeds());
        assertEquals(6.0, problem.waterVaporFlowMolPerSecond(1));
        assertEquals(6.0, problem.waterVaporFlowMolPerSecond(2));
        assertEquals(4.0, problem.waterVaporFlowMolPerSecond(3));
        assertEquals(4.0, problem.waterVaporFlowMolPerSecond(5));
        assertEquals(plain.degreeOfFreedomLedger().unknownCount(), problem.degreeOfFreedomLedger().unknownCount());
        assertEquals(plain.degreeOfFreedomLedger().equationCount(), problem.degreeOfFreedomLedger().equationCount());
    }

    @Test
    void acceptsDryZeroDutyButRejectsSteamWithoutAZeroDutySumpSource() {
        assertThrows(IllegalArgumentException.class, () -> new V3SteamFeedSpec(0, 1.0, 450.0));
        assertDoesNotThrow(() -> V3ColumnProblemResolver.validateInput(input(0.0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> V3ColumnProblemResolver.validateInput(
                input(0.0, List.of(new V3SteamFeedSpec(2, 1.0, 450.0)))));
        assertThrows(IllegalArgumentException.class, () -> V3ColumnProblemResolver.validateInput(
                input(0.0, List.of(new V3SteamFeedSpec(6, 1.0, 450.0)))));
        assertThrows(IllegalArgumentException.class, () -> V3ColumnProblemResolver.validateInput(
                input(8_000_000.0, List.of(new V3SteamFeedSpec(5, 101.0, 450.0)))));
        assertThrows(IllegalArgumentException.class, () -> V3ColumnProblemResolver.validateInput(
                input(8_000_000.0, List.of(new V3SteamFeedSpec(5, 1.0, 395.0)))));
    }

    @Test
    void waterCorrelationsMatchPinnedSteamTableSanityValues() {
        assertEquals(101_418.0, V3WaterProperties.saturationPressurePascal(373.15), 400.0);
        assertEquals(7_384.0, V3WaterProperties.saturationPressurePascal(313.15), 35.0);
        assertEquals(373.15, V3WaterProperties.saturationTemperatureKelvin(101_418.0), 0.15);
        assertEquals(40_660.0, V3WaterProperties.vaporizationEnthalpy(373.15), 100.0);
        double rise = V3WaterProperties.vaporMolarEnthalpy(500.0) - V3WaterProperties.vaporMolarEnthalpy(300.0);
        assertEquals(6_900.0, rise, 150.0);
    }

    @Test
    void steamChangesDigestButEmptySteamRetainsDryDigest() {
        V3ColumnProblem dry = V3ColumnProblemResolver.resolve(input(8_000_000.0, List.of()), V3CondenserPhaseBranch.TWO_PHASE);
        V3ColumnProblem wet = V3ColumnProblemResolver.resolve(
                input(8_000_000.0, List.of(new V3SteamFeedSpec(5, 4.0, 450.0))), V3CondenserPhaseBranch.TWO_PHASE);
        assertNotEquals(V3InputDigest.of(dry, "mesh", "data", "assumptions"),
                V3InputDigest.of(wet, "mesh", "data", "assumptions"));
    }

    @Test
    void hotCondenserKeepsSteamAsVaporInsteadOfRejectingTheHydrocarbonCondenserBranch() {
        V3ColumnInput input = new V3ColumnInput(1, "test:ideal_binary", "test:hot-steam",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0,
                4, 2, 150_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(8_000_000.0)),
                List.of(), List.of(new V3SteamFeedSpec(5, 4.0, 450.0)));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);

        assertEquals(V3WaterCondenserRegime.ALL_VAPOR, problem.waterCondenserRegime());
        assertTrue(problem.hasAllVaporWaterCondenser());
        assertEquals(0.0, problem.waterVaporSlipCoefficient());
    }

    @Test
    void allVaporWaterSharesTheHydrocarbonGasStreamAndNeverPublishesAFreeWaterLiquid() {
        V3ColumnInput input = new V3ColumnInput(1, "test:ideal_binary", "test:mixed-gas-steam",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0,
                4, 2, 150_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(8_000_000.0)),
                List.of(), List.of(new V3SteamFeedSpec(5, 4.0, 450.0)));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState state = manufacturedWetState(problem, 400.0);
        List<V3ColumnStreamProperties> streams = V3ColumnStreamProperties.fromAccepted(problem, state,
                new double[] {0.016, 0.072});

        V3ColumnStreamProperties overhead = streams.stream().filter(stream -> stream.streamId().equals("overhead_vapor"))
                .findFirst().orElseThrow();
        assertEquals(4.0 / 14.0, fraction(overhead, "h2o").moleFraction(), 1.0e-12);
        assertTrue(fraction(overhead, "methane").moleFraction() > 0.0);
        assertTrue(fraction(overhead, "n-pentane").moleFraction() > 0.0);
        assertFalse(streams.stream().anyMatch(stream -> stream.streamId().equals("free_water")));
    }

    @Test
    void freeWaterProductAndOverheadSlipArePublishedFromAnAcceptedWetState() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(
                input(8_000_000.0, List.of(new V3SteamFeedSpec(5, 4.0, 450.0))), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState state = manufacturedWetState(problem, 332.15);
        List<V3ColumnStreamProperties> streams = V3ColumnStreamProperties.fromAccepted(problem, state,
                new double[] {0.016, 0.072});
        V3ColumnStreamProperties overhead = streams.stream().filter(stream -> stream.streamId().equals("overhead_vapor"))
                .findFirst().orElseThrow();
        assertTrue(overhead.moleFractions().stream().anyMatch(fraction -> fraction.componentId().equals("h2o")
                && fraction.moleFraction() > 0.0));
        V3ColumnStreamProperties freeWater = streams.stream()
                .filter(stream -> stream.streamId().equals("free_water")).findFirst().orElseThrow(
                        () -> new AssertionError("streams=" + streams));
        assertTrue(freeWater.molarFlowMolPerSecond() > 0.0);
        assertEquals(List.of(new V3ColumnStreamProperties.ComponentFraction("h2o", 1.0, 1.0)), freeWater.moleFractions());
        V3ColumnStreamProperties distillate = streams.stream().filter(stream -> stream.streamId().equals("distillate_liquid"))
                .findFirst().orElseThrow();
        assertFalse(distillate.moleFractions().stream().anyMatch(fraction -> fraction.componentId().equals("h2o")));
    }

    @Test
    void lowPressureAllVaporWaterOnALiquidOnlyCondenserLeavesAsSteamProductEnthalpy() {
        // Psat(360 K) ~ 62 kPa exceeds a 50 kPa overhead, so the water regime is ALL_VAPOR even though the
        // hydrocarbon condenser branch is LIQUID_ONLY: the arriving steam is published as a pure-water
        // "overhead_vapor" product and its enthalpy must leave the condenser node with it.
        double topPressure = 50_000.0;
        double condenserTemperature = 360.0;
        assertTrue(V3WaterProperties.saturationPressurePascal(condenserTemperature) >= topPressure);
        V3ColumnInput input = new V3ColumnInput(1, "test:ideal_binary", "test:low-pressure-steam",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0,
                4, 2, topPressure, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(0.0)),
                List.of(), List.of(new V3SteamFeedSpec(5, 4.0, 450.0)));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.LIQUID_ONLY);
        assertEquals(V3WaterCondenserRegime.ALL_VAPOR, problem.waterCondenserRegime());
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, problem.topology().condenserPhaseBranch());
        assertFalse(problem.topology().hasVaporPhase(problem.topology().condenserNode()));
        V3DryMeshState state = manufacturedWetState(problem, condenserTemperature);
        assertEquals(4.0, problem.waterCondenserSplit(state).vaporFlowMolPerSecond());
        assertEquals(0.0, problem.waterCondenserSplit(state).freeWaterFlowMolPerSecond());

        List<V3ColumnStreamProperties> streams = V3ColumnStreamProperties.fromAccepted(problem, state,
                new double[] {0.016, 0.072});
        V3ColumnStreamProperties overhead = streams.stream().filter(stream -> stream.streamId().equals("overhead_vapor"))
                .findFirst().orElseThrow(() -> new AssertionError("streams=" + streams));
        assertEquals(4.0, overhead.molarFlowMolPerSecond());
        assertEquals(List.of(new V3ColumnStreamProperties.ComponentFraction("h2o", 1.0, 1.0)), overhead.moleFractions());
        assertFalse(streams.stream().anyMatch(stream -> stream.streamId().equals("free_water")));

        ZeroEnthalpyThermo thermo = new ZeroEnthalpyThermo(input.componentBasis());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        double steamProductEnergy = 4.0 * V3WaterProperties.vaporMolarEnthalpy(condenserTemperature);
        double arrivingWaterEnergy = 4.0 * V3WaterProperties.vaporMolarEnthalpy(450.0);
        V3MeshResidualEvaluator.LocalNodeTerms condenser = evaluator.localTerms(state, 0, workspace);
        assertEquals(0.0, condenser.liquidPhaseEnergy());
        assertEquals(steamProductEnergy, condenser.vaporPhaseEnergy(), 1.0e-9 * steamProductEnergy);
        assertEquals(steamProductEnergy - arrivingWaterEnergy,
                V3ColumnDutyLedger.condenserDutyWatts(problem, state, evaluator, workspace), 1.0e-9 * arrivingWaterEnergy);

        // The tray energy rows never see the condenser vapor term, so the wet MESH residual is unaffected.
        V3MeshResidual residual = evaluator.evaluate(state, workspace);
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) {
                assertEquals(0.0, row.physicalValue(), 1.0e-9 * arrivingWaterEnergy, row::toString);
            }
        }
        // The whole-column closure adds the condenser vapor outlet inside the condenser duty and subtracts it again
        // as a product, so it is blind to this term and passes with or without the fix; the condenser-duty
        // assertion above is the guard for the published number.
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, 0.0).audit(state, workspace);
        V3AcceptanceAudit.Check closure = audit.checks().stream()
                .filter(check -> check.family().equals("GLOBAL_ENERGY_BALANCE")).findFirst().orElseThrow();
        assertTrue(closure.passed(), closure::toString);
        assertEquals(0.0, closure.value(), 1.0e-9 * arrivingWaterEnergy);
    }

    /** Hydrocarbon phases carry no enthalpy, so every energy term in the column is water. */
    private static final class ZeroEnthalpyThermo implements V3ThermoModel {
        private final V3ComponentBasis basis;

        private ZeroEnthalpyThermo(V3ComponentBasis basis) {
            this.basis = basis;
        }

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(basis.componentCount()); }

        @Override
        public V3FugacityResult fugacity(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
            return new V3FugacityResult(phase, new double[basis.componentCount()], 1.0, 0.0, 1, 0.0);
        }

        @Override
        public double molarEnthalpy(double t, double p, double[] z, V3Phase phase, V3ThermoWorkspace workspace) {
            return 0.0;
        }

        @Override
        public V3FlashResult flashTP(double t, double p, double[] z, V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("The zero-enthalpy steam test model does not implement a flash");
        }
    }

    private static V3DryMeshState manufacturedWetState(V3ColumnProblem problem, double condenserTemperature) {
        int nodes = problem.topology().nodeCount();
        double[][] liquid = new double[nodes][2];
        double[][] vapor = new double[nodes][2];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            liquid[node] = new double[] {4.0, 6.0};
            vapor[node] = problem.topology().hasVaporPhase(node) ? new double[] {4.0, 6.0} : new double[2];
            temperatures[node] = node == 0 ? condenserTemperature : 450.0;
        }
        return new V3DryMeshState(problem.topology(), 2, liquid, vapor, temperatures);
    }

    private static V3ColumnStreamProperties.ComponentFraction fraction(
            V3ColumnStreamProperties stream, String componentId) {
        return stream.moleFractions().stream().filter(fraction -> fraction.componentId().equals(componentId))
                .findFirst().orElseThrow();
    }

    private static V3ColumnInput input(double duty, List<V3SteamFeedSpec> steam) {
        return new V3ColumnInput(1, "test:ideal_binary", "test:steam", new V3ComponentBasis(List.of("methane", "n-pentane")),
                new double[] {40.0, 60.0}, 450.0, 4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(duty)),
                List.of(), steam);
    }
}
