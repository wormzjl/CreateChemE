package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3BlockJacobianAssemblerTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 4})
    void localStageBlocksMatchTheWholeSystemFiniteDifferenceOracleAndHaveNoOffBandCoupling(int drawTray) {
        V3ColumnProblem problem = problem();
        if (drawTray > 0) {
            V3ColumnInput input = problem.input();
            problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(),
                    input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                    input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                    input.specifications(), List.of(new V3SideDrawSpec(drawTray, 3.0))), V3CondenserPhaseBranch.TWO_PHASE);
        }
        assertLocalBlocksMatchFiniteDifference(problem);
    }

    @Test
    void localStageBlocksMatchTheWholeSystemFiniteDifferenceOracleWithSumpSteam() {
        V3ColumnProblem problem = problem(List.of(new V3SteamFeedSpec(5, 1.0, 450.0)));

        assertLocalBlocksMatchFiniteDifference(problem);
    }

    private static void assertLocalBlocksMatchFiniteDifference(V3ColumnProblem problem) {
        SmoothThermo thermo = new SmoothThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState state = state(problem.topology());

        V3FiniteDifferenceJacobian.Jacobian full = V3FiniteDifferenceJacobian.evaluate(
                evaluator, coordinates, state, thermo::newWorkspace);
        V3BlockJacobian referenceBlocks = V3BlockJacobianAssembler.assemble(
                problem, evaluator, coordinates, state, thermo::newWorkspace);
        V3BlockJacobian localBlocks = V3BlockJacobianAssembler.assembleLocal(
                problem, evaluator, coordinates, state, thermo::newWorkspace,
                V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);

        assertEquals(6, referenceBlocks.layout().nodeCount());
        assertEquals(4, referenceBlocks.layout().size(0));
        assertEquals(5, referenceBlocks.layout().size(1));
        assertTrue(referenceBlocks.maximumOffBandMagnitude() <= 1.0e-10);
        assertTrue(localBlocks.maximumOffBandMagnitude() <= 1.0e-10);
        for (int node = 0; node < referenceBlocks.layout().nodeCount(); node++) {
            assertBlockEquals(full.values(), referenceBlocks.layout(), node, node, referenceBlocks.diagonal(node), 0.0);
            assertBlockEquals(full.values(), localBlocks.layout(), node, node, localBlocks.diagonal(node), 1.0e-5);
            if (node > 0) {
                assertBlockEquals(full.values(), referenceBlocks.layout(), node, node - 1, referenceBlocks.lower(node), 0.0);
                assertBlockEquals(full.values(), localBlocks.layout(), node, node - 1, localBlocks.lower(node), 1.0e-5);
            } else {
                assertEquals(0, referenceBlocks.lower(node)[0].length);
                assertEquals(0, localBlocks.lower(node)[0].length);
            }
            if (node + 1 < referenceBlocks.layout().nodeCount()) {
                assertBlockEquals(full.values(), referenceBlocks.layout(), node, node + 1, referenceBlocks.upper(node), 0.0);
                assertBlockEquals(full.values(), localBlocks.layout(), node, node + 1, localBlocks.upper(node), 1.0e-5);
            } else {
                assertEquals(0, referenceBlocks.upper(node)[0].length);
                assertEquals(0, localBlocks.upper(node)[0].length);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = V3CondenserPhaseBranch.class, names = {"TWO_PHASE", "LIQUID_ONLY"})
    void localStageBlocksMatchTheColoredReferenceForRealCrudeCondenserBranches(V3CondenserPhaseBranch branch) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnInput input = realCrudeInput(crude, 100_000.0);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, branch);
        V3DryMeshState state = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        V3FlashResult feed = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feed.molarEnthalpyJoulesPerMol());
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);

        V3FiniteDifferenceJacobian.Jacobian reference = V3FiniteDifferenceJacobian.evaluateStageColored(
                evaluator, coordinates, state, thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.FINE);
        V3BlockJacobian local = V3BlockJacobianAssembler.assembleLocal(
                problem, evaluator, coordinates, state, thermo::newWorkspace,
                V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);
        V3BandedMatrix flattened = local.toBandedMatrix();
        double[][] referenceValues = reference.values();

        assertEquals(referenceValues.length, flattened.size());
        for (int row = 0; row < referenceValues.length; row++) {
            for (int column = 0; column < referenceValues[row].length; column++) {
                assertClose(referenceValues[row][column], flattened.get(row, column),
                        "row " + row + ", column " + column);
            }
        }
    }

    private static void assertBlockEquals(
            double[][] full,
            V3StageBlockLayout layout,
            int rowNode,
            int columnNode,
            double[][] block,
            double tolerance) {
        assertEquals(layout.size(rowNode), block.length);
        assertEquals(layout.size(columnNode), block[0].length);
        for (int row = 0; row < block.length; row++) {
            for (int column = 0; column < block[row].length; column++) {
                assertEquals(full[layout.start(rowNode) + row][layout.start(columnNode) + column], block[row][column], tolerance);
            }
        }
    }

    private static void assertClose(double expected, double actual, String location) {
        assertEquals(expected, actual, 5.0e-5 * Math.max(1.0, Math.abs(expected)), location);
    }

    private static V3ColumnInput realCrudeInput(V3CrudeFeed crude, double topPressurePascal) {
        double[] flows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_000.0 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlowMolPerSecond;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, topPressurePascal, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static V3ColumnProblem problem() {
        return problem(List.of());
    }

    private static V3ColumnProblem problem(List<V3SteamFeedSpec> steamFeeds) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                4, 2, steamFeeds.isEmpty() ? 250_000.0 : 150_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)), List.of(), steamFeeds);
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3DryMeshState state(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        }, new double[][] {
                {8.0, 2.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        }, new double[] {400.0, 410.0, 420.0, 430.0, 440.0, 450.0});
    }

    private static final class SmoothThermo implements V3ThermoModel {
        private static final double[][] K = {
                {1.6, 0.4}, {1.2, 0.8}, {12.0 / 7.0, 8.0 / 13.0},
                {12.0 / 7.0, 8.0 / 13.0}, {12.0 / 7.0, 8.0 / 13.0}, {42.0 / 17.0, 28.0 / 53.0}
        };
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            int node = (int) Math.round((temperatureKelvin - 400.0) / 10.0);
            if (node < 0 || node >= K.length) throw new IllegalArgumentException("Manufactured VLE temperature is outside its grid");
            double[] logPhi = new double[2];
            if (phase == V3Phase.LIQUID) {
                double referenceTemperature = 400.0 + 10.0 * node;
                for (int component = 0; component < 2; component++) logPhi[component] = Math.log(K[node][component])
                        + (component + 1) * 1.0e-3 * (temperatureKelvin - referenceTemperature);
            }
            return new V3FugacityResult(phase, logPhi, 1.0, 0.0, 1, 0.0);
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
            throw new UnsupportedOperationException("The smooth manufactured model does not implement a flash");
        }
    }
}
