package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3TruncationNumericsTest {
    @Test
    void projectionZerosRemovedPointsFloorsOnlyNonpositiveRetainedFlowsAndPreservesTheSeed() {
        Fixture fixture = fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        double[][] liquid = copyFlows(fixture.exact(), true);
        double[][] vapor = copyFlows(fixture.exact(), false);
        liquid[0][2] = 123.0;
        vapor[0][2] = 456.0;
        liquid[2][2] = 0.0;
        vapor[2][2] = 0.0;
        liquid[4][0] = 1.0e-30;
        V3DryMeshState seed = new V3DryMeshState(fixture.problem().topology(), 3, liquid, vapor, temperatures());
        V3TruncationSupport support = fixture.problem().truncationSupport();
        V3DryMeshState projected = support.projectSeed(fixture.problem(), seed);

        assertEquals(0L, Double.doubleToLongBits(projected.liquidFlow(0, 2)));
        assertEquals(0L, Double.doubleToLongBits(projected.vaporFlow(0, 2)));
        assertEquals(1.0e-12, projected.liquidFlow(2, 2), 1.0e-27);
        assertEquals(1.0e-12, projected.vaporFlow(2, 2), 1.0e-27);
        assertEquals(1.0e-30, projected.liquidFlow(4, 0));
        assertEquals(123.0, seed.liquidFlow(0, 2));
        assertEquals(0.0, seed.liquidFlow(2, 2));
        for (int node = 0; node < 6; node++) assertEquals(seed.temperatureKelvin(node), projected.temperatureKelvin(node));
        assertDoesNotThrow(() -> new V3DryMeshCoordinateMap(fixture.problem()).encode(projected));
        assertSame(seed, fixture.original().truncationSupport().projectSeed(fixture.original(), seed));
    }

    @Test
    void sinkEdgesAreImmutableInternalExitsAndSumToTheSolvedFeedDefect() {
        Fixture fixture = fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3TruncationSupport support = fixture.problem().truncationSupport();
        assertEquals(List.of(
                new V3TruncationSupport.SinkEdge(V3TruncationSupport.SinkKind.VAPOR_TO_ABOVE, 2, 1, 2),
                new V3TruncationSupport.SinkEdge(V3TruncationSupport.SinkKind.LIQUID_TO_BELOW, 2, 3, 2)),
                support.sinkEdges());
        assertThrows(UnsupportedOperationException.class, () -> support.sinkEdges().clear());
        assertEquals(0.01, support.massDefectMolPerSecond(fixture.exact()), 1.0e-16);
        double products = 0.0;
        for (int component = 0; component < 3; component++) {
            products += fixture.exact().vaporFlow(0, component)
                    + fixture.exact().liquidFlow(0, component) / 2.0
                    + fixture.exact().liquidFlow(5, component);
        }
        assertEquals(support.massDefectMolPerSecond(fixture.exact()),
                fixture.problem().activeComponentBasis().totalFeedFlowMolPerSecond() - products, 1.0e-13);
        assertTrue(fixture.original().truncationSupport().sinkEdges().isEmpty());
        assertEquals(0.0, fixture.original().truncationSupport().massDefectMolPerSecond(fixture.exact()));
    }

    @Test
    void boundarySinkTaxonomyIncludesCondenserAndReboilerButNotProductsOrZeroReflux() {
        V3ColumnProblem original = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 2);
        double[][] flows = V3TruncationSupportTest.uniformFlows(original);
        flows[0] = new double[] {0.1, 99.9};
        flows[5] = new double[] {0.1, 99.9};
        V3DryMeshState seed = V3TruncationSupportTest.state(original, flows, flows);
        V3TruncationSupport support = V3TruncationSupport.derive(original, 0.01, seed);
        assertEquals(List.of(
                new V3TruncationSupport.SinkEdge(V3TruncationSupport.SinkKind.VAPOR_TO_ABOVE, 1, 0, 0),
                new V3TruncationSupport.SinkEdge(V3TruncationSupport.SinkKind.LIQUID_TO_BELOW, 4, 5, 0)),
                support.sinkEdges());
        assertEquals(100.0, support.massDefectMolPerSecond(support.projectSeed(original, seed)));
        // Whole-point inflow closure removes the condenser if tray one is removed, so a reflux sink
        // cannot survive the current T2 invariant (irrespective of whether reflux is zero or positive).
        assertTrue(support.sinkEdges().stream().noneMatch(edge -> edge.kind()
                == V3TruncationSupport.SinkKind.REFLUX_TO_TRAY_ONE));
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void retainedResidualsAndLocalTermsMatchHandWrittenTransmittedTerms(V3CondenserPhaseBranch branch) {
        Fixture fixture = fixture(branch, 0.01);
        V3DryMeshState state = perturb(fixture);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(fixture.problem(), fixture.thermo(), 0.0);
        V3MeshResidual residual = evaluator.evaluate(state, fixture.thermo().newWorkspace());
        double refluxFraction = branch == V3CondenserPhaseBranch.VAPOR_ONLY ? 0.0 : 0.5;
        for (V3MeshResidual.Row row : residual.rows()) {
            int node = row.equation().node();
            int component = row.equation().component();
            V3MeshResidualEvaluator.LocalNodeTerms local = evaluator.localTerms(state, node, fixture.thermo().newWorkspace());
            switch (row.equation().family()) {
                case COMPONENT_MATERIAL_BALANCE -> {
                    double expected = (node == 0 ? state.vaporFlow(1, component)
                            : node == 5 ? state.liquidFlow(4, component)
                            : (node == 1 ? refluxFraction * state.liquidFlow(0, component)
                            : state.liquidFlow(node - 1, component)) + state.vaporFlow(node + 1, component)
                            + (node == 2 ? fixture.problem().activeComponentBasis().feedFlowMolPerSecond(component) : 0.0))
                            - state.liquidFlow(node, component) - state.vaporFlow(node, component);
                    assertEquals(expected, row.physicalValue(), 1.0e-12);
                }
                case VAPOR_LIQUID_EQUILIBRIUM -> assertEquals(row.physicalValue(), local.equilibriumResidual(component));
                case ENERGY_BALANCE -> {
                    double expected = (node == 1 ? refluxFraction * phaseEnergy(state, 0, true)
                            : phaseEnergy(state, node - 1, true))
                            + (node == 5 ? 0.0 : phaseEnergy(state, node + 1, false))
                            - phaseEnergy(state, node, true) - phaseEnergy(state, node, false);
                    assertEquals(expected, row.physicalValue(), 1.0e-10);
                }
            }
            assertEquals(phaseEnergy(state, node, true), local.liquidPhaseEnergy(), 1.0e-10);
            assertEquals(phaseEnergy(state, node, false), local.vaporPhaseEnergy(), 1.0e-10);
            if (node != 2) assertTrue(Double.isNaN(local.equilibriumResidual(2)));
        }
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void localAndColoredJacobiansMatchTheUncoloredOracleForBothStepSizes(V3CondenserPhaseBranch branch) {
        Fixture fixture = fixture(branch, 0.01);
        V3ColumnProblem problem = fixture.problem();
        V3DryMeshState state = perturb(fixture);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, fixture.thermo(), 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        assertTrue(coordinates.coordinateCount() < 96, "the reference must select the uncolored central path");
        for (V3FiniteDifferenceJacobian.DifferenceScale scale : V3FiniteDifferenceJacobian.DifferenceScale.values()) {
            double[][] full = V3FiniteDifferenceJacobian.evaluate(evaluator, coordinates, state,
                    fixture.thermo()::newWorkspace, scale).values();
            double[][] colored = V3FiniteDifferenceJacobian.evaluateStageColored(evaluator, coordinates, state,
                    fixture.thermo()::newWorkspace, scale).values();
            V3BlockJacobian local = V3BlockJacobianAssembler.assembleLocal(problem, evaluator, coordinates, state,
                    fixture.thermo()::newWorkspace, scale, V3SolveControl.UNBOUNDED);
            V3BandedMatrix banded = local.toBandedMatrix();
            for (int row = 0; row < full.length; row++) {
                for (int column = 0; column < full.length; column++) {
                    double tolerance = 1.0e-6 * Math.max(1.0, Math.abs(full[row][column]));
                    assertEquals(full[row][column], colored[row][column], tolerance, "colored row/column " + row + "/" + column);
                    assertEquals(full[row][column], banded.get(row, column), tolerance, "local row/column " + row + "/" + column);
                }
            }
            assertTrue(local.maximumOffBandMagnitude() <= 1.0e-10);
        }
        assertTrue(V3BlockJacobianAssembler.assemble(problem, evaluator, coordinates, state,
                fixture.thermo()::newWorkspace).maximumOffBandMagnitude() <= 1.0e-10);
    }

    @Test
    void maskedSmallProblemConvergesFromAPerturbedSeedWithExactZerosAndTheExpectedDefect() {
        Fixture fixture = fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(fixture.problem(), fixture.thermo(), 0.0);
        assertTrue(evaluator.evaluate(fixture.exact(), fixture.thermo().newWorkspace()).maximumAbsoluteScaledResidual() < 1.0e-12);
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(fixture.problem(), evaluator,
                new V3DryMeshCoordinateMap(fixture.problem()), perturb(fixture), fixture.thermo()::newWorkspace, 32, 1.0e-9);
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, attempt, attempt::toString);
        assertTrue(converged.evidence().iterations() > 0);
        assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
        assertEquals(0.01, fixture.problem().truncationSupport().massDefectMolPerSecond(converged.state()), 1.0e-10);
        for (int node = 0; node < 6; node++) {
            if (node == 2) continue;
            assertEquals(0L, Double.doubleToLongBits(converged.state().liquidFlow(node, 2)));
            assertEquals(0L, Double.doubleToLongBits(converged.state().vaporFlow(node, 2)));
        }
    }

    @Test
    void evaluatorRejectsNonzeroRemovedSlotsRatherThanSilentlyRenormalizingThemAway() {
        Fixture fixture = fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        double[][] liquid = copyFlows(fixture.exact(), true);
        liquid[0][2] = 1.0e-50;
        V3DryMeshState invalid = new V3DryMeshState(fixture.problem().topology(), 3, liquid,
                copyFlows(fixture.exact(), false), temperatures());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(fixture.problem(), fixture.thermo(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(invalid, fixture.thermo().newWorkspace()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.localTerms(invalid, 0, fixture.thermo().newWorkspace()));
    }

    static Fixture fixture(V3CondenserPhaseBranch branch, double traceFeed) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:truncation", "test:ternary",
                new V3ComponentBasis(List.of("component-a", "dormant", "component-b", "trace")),
                new double[] {30.0, 0.0, 60.0, traceFeed}, 400.0, 4, 2, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                new V3ColumnSpecification.OrganicRefluxRatio(branch == V3CondenserPhaseBranch.VAPOR_ONLY ? 0.0 : 1.0),
                new V3ColumnSpecification.ReboilerDuty(0.0)));
        V3ColumnProblem original = V3ColumnProblemResolver.resolve(input, branch);
        double[][] liquid = {{10, 10, 0}, {5, 5, 0}, {35, 65, traceFeed * 0.6},
                {35, 65, 0}, {35, 65, 0}, {17, 53, 0}};
        double[][] vapor = {{8, 2, 0}, {18, 12, 0}, {18, 12, traceFeed * 0.4},
                {18, 12, 0}, {18, 12, 0}, {18, 12, 0}};
        if (!original.topology().hasLiquidPhase(0)) liquid[0] = new double[3];
        if (!original.topology().hasVaporPhase(0)) vapor[0] = new double[3];
        V3DryMeshState exact = new V3DryMeshState(original.topology(), 3, liquid, vapor, temperatures());
        V3TruncationSupport support = V3TruncationSupport.derive(original, 0.01, exact);
        V3ColumnProblem masked = V3ColumnProblemResolver.withTruncation(original, support);
        return new Fixture(original, masked, exact, new ManufacturedThermo(input.componentBasis(), exact));
    }

    private static V3DryMeshState perturb(Fixture fixture) {
        V3DryMeshCoordinateMap map = new V3DryMeshCoordinateMap(fixture.problem());
        double[] values = map.encode(fixture.exact());
        for (int index = 0; index < values.length; index++) {
            boolean temperature = map.unknowns().get(index).id().family() == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE;
            values[index] += ((index % 3) - 1) * (temperature ? 0.1 : 0.01);
        }
        return map.decode(values);
    }

    static double[][] copyFlows(V3DryMeshState state, boolean liquid) {
        double[][] result = new double[state.nodeCount()][state.componentCount()];
        for (int node = 0; node < state.nodeCount(); node++) {
            for (int component = 0; component < state.componentCount(); component++) {
                result[node][component] = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            }
        }
        return result;
    }

    static double[] temperatures() { return new double[] {400, 410, 420, 430, 440, 450}; }

    private static double phaseEnergy(V3DryMeshState state, int node, boolean liquid) {
        double weighted = 0.0;
        for (int component = 0; component < 3; component++) {
            weighted += (30.0 + 10.0 * component) * (liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component));
        }
        return weighted * (state.temperatureKelvin(node) - (400.0 + 10.0 * node));
    }

    record Fixture(V3ColumnProblem original, V3ColumnProblem problem, V3DryMeshState exact, ManufacturedThermo thermo) {}

    static final class ManufacturedThermo implements V3ThermoModel {
        private final V3ComponentBasis basis;
        private final double[][] logK = new double[6][4];

        ManufacturedThermo(V3ComponentBasis basis, V3DryMeshState exact) {
            this.basis = basis;
            for (int node = 0; node < 6; node++) {
                double liquidTotal = Arrays.stream(copyFlows(exact, true)[node]).sum();
                double vaporTotal = Arrays.stream(copyFlows(exact, false)[node]).sum();
                for (int component = 0; component < 3; component++) {
                    double liquid = exact.liquidFlow(node, component);
                    double vapor = exact.vaporFlow(node, component);
                    if (liquid > 0.0 && vapor > 0.0) {
                        logK[node][component == 0 ? 0 : component + 1] = Math.log((vapor / vaporTotal) / (liquid / liquidTotal));
                    }
                }
            }
        }

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(4); }

        @Override public V3FugacityResult fugacity(double temperature, double pressure, double[] composition,
                                                 V3Phase phase, V3ThermoWorkspace workspace) {
            int node = (int) Math.round((temperature - 400.0) / 10.0);
            assertEquals(0.0, composition[1]);
            if (node != 2) assertEquals(0.0, composition[3]);
            assertEquals(1.0, Arrays.stream(composition).sum(), 1.0e-12);
            double[] logPhi = new double[4];
            if (phase == V3Phase.LIQUID) {
                for (int component = 0; component < 4; component++) {
                    logPhi[component] = logK[node][component] + (component + 1) * 1.0e-3 * (temperature - (400 + 10 * node));
                }
            }
            return new V3FugacityResult(phase, logPhi, 1.0,
                    molarEnthalpy(temperature, pressure, composition, phase, workspace), 1, 0.0);
        }

        @Override public double molarEnthalpy(double temperature, double pressure, double[] composition,
                                              V3Phase phase, V3ThermoWorkspace workspace) {
            int node = (int) Math.round((temperature - 400.0) / 10.0);
            return (temperature - (400 + 10 * node)) * (30 * composition[0] + 40 * composition[2] + 50 * composition[3]);
        }

        @Override public V3FlashResult flashTP(double temperature, double pressure, double[] composition,
                                              V3ThermoWorkspace workspace) {
            int node = (int) Math.round((temperature - 400.0) / 10.0);
            double[] k = new double[basis.componentCount()];
            for (int component = 0; component < k.length; component++) k[component] = Math.exp(logK[node][component]);
            return V3ManufacturedFlash.flash(composition, k);
        }
    }
}
