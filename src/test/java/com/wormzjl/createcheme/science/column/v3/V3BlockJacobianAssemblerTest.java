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
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * The free-water columns are the one place the local assembler writes derivatives in closed form rather
     * than probing for them, because they cross a node boundary: {@code F_m} reaches the equilibrium rows,
     * the saturation row and both energy rows around tray {@code m + 1} through that tray's water vapour.
     * This holds the whole assembled band, wet columns included, against the uncoloured oracle.
     */
    @Test
    void localStageBlocksMatchTheWholeSystemFiniteDifferenceOracleOnWetTrays() {
        V3ColumnProblem wet = wetProblem();
        assertTrue(wet.hasWetTrays(), "fixture must carry a free-water tray");
        assertLocalBlocksMatchFiniteDifference(wet, wetState(wet));
    }

    @Test
    void localStageBlocksMatchTheWholeSystemFiniteDifferenceOracleWithPrescribedStageHeat() {
        V3ColumnInput plain = problem().input();
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(plain.schemaVersion(),
                plain.packageId(), plain.assayId(), plain.componentBasis(), plain.feedComponentMolarFlowsMolPerSecond(),
                plain.feedTemperatureKelvin(), plain.stageCount(), plain.feedStageNumber(), plain.topPressurePascal(),
                plain.stagePressureDropPascal(), plain.specifications(), List.of(), List.of(),
                List.of(new V3PumparoundSpec(2, 4, -750_000.0, V3PumparoundSpec.Split.UNIFORM))),
                V3CondenserPhaseBranch.TWO_PHASE);

        assertLocalBlocksMatchFiniteDifference(problem);
    }

    private static void assertLocalBlocksMatchFiniteDifference(V3ColumnProblem problem) {
        assertLocalBlocksMatchFiniteDifference(problem, state(problem.topology()));
    }

    private static void assertLocalBlocksMatchFiniteDifference(V3ColumnProblem problem, V3DryMeshState state) {
        SmoothThermo thermo = new SmoothThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);

        V3FiniteDifferenceJacobian.Jacobian full = V3FiniteDifferenceJacobian.evaluate(
                evaluator, coordinates, state, thermo::newWorkspace);
        V3BlockJacobian referenceBlocks = V3BlockJacobianAssembler.assemble(
                problem, evaluator, coordinates, state, thermo::newWorkspace);
        V3BlockJacobianAssembler.AnalyticTally tally = new V3BlockJacobianAssembler.AnalyticTally();
        V3BlockJacobian localBlocks = V3BlockJacobianAssembler.assembleLocal(
                problem, evaluator, coordinates, state, thermo::newWorkspace,
                V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED, tally);

        // A model that does not differentiate itself is probed, and is not a fallback: nothing was refused.
        assertEquals(0, tally.analyticNodes());
        assertEquals(0, tally.fallbackNodes());
        assertEquals(6, referenceBlocks.layout().nodeCount());
        assertEquals(4, referenceBlocks.layout().size(0));
        // Two component flows per phase plus the temperature, and on a wet tray the free-water unknown.
        assertEquals(problem.isWetTray(1) ? 6 : 5, referenceBlocks.layout().size(1));
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

    /**
     * The real-crude blocks against the coloured whole-system oracle, on the branches the calculator uses.
     *
     * <p>The reference is differenced at the coarse resolution rather than the fine one, because that is the
     * more accurate oracle for exactly the entries this fixture stresses. A tray's phase enthalpy rate here is
     * of order {@code 1e12} W and a trace component's contribution to its derivative is of order one, so a
     * log-flow step of {@code 1e-6} moves the sum by a few units in its last place and the difference of two
     * such sums is quantisation, not slope. Ten times the step is still far inside the quadratic truncation
     * budget and recovers a decimal order of resolution. The blocks themselves do not change with the
     * resolution at all when the property model differentiates itself, which is the point of asserting the
     * fallback count is zero: nothing here was differenced.</p>
     *
     * <p>Two agreements are asserted. The first is the entrywise one this test has always made — observed
     * 8.7e-7 (TWO_PHASE) and 8.6e-6 (LIQUID_ONLY), against 3.9e-6 and 7.2e-5 at the fine resolution, which is
     * the quantisation described above and not a disagreement about any slope. The second is the one that
     * decides what the linear algebra sees: every row of the band is equilibrated by its own maximum before
     * pivoting, so a difference is only meaningful against that maximum, and against it the analytic blocks
     * and the oracle agree to {@code 1e-6} — observed 9.0e-9 and 1.1e-8 at the coarse resolution, 1.4e-7 and
     * 1.1e-7 at the fine one.</p>
     */
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
                evaluator, coordinates, state, thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.COARSE);
        V3BlockJacobianAssembler.AnalyticTally tally = new V3BlockJacobianAssembler.AnalyticTally();
        V3BlockJacobian local = V3BlockJacobianAssembler.assembleLocal(
                problem, evaluator, coordinates, state, thermo::newWorkspace,
                V3FiniteDifferenceJacobian.DifferenceScale.COARSE, V3SolveControl.UNBOUNDED, tally);
        V3BandedMatrix flattened = local.toBandedMatrix();
        double[][] referenceValues = reference.values();

        assertEquals(problem.topology().nodeCount(), tally.analyticNodes());
        assertEquals(0, tally.fallbackNodes());
        assertEquals(referenceValues.length, flattened.size());
        for (int row = 0; row < referenceValues.length; row++) {
            for (int column = 0; column < referenceValues[row].length; column++) {
                assertClose(referenceValues[row][column], flattened.get(row, column),
                        "row " + row + ", column " + column);
            }
        }
        assertEquilibratedRowsAgree(referenceValues, flattened, branch.toString());
    }

    /**
     * The analytic blocks against the coloured oracle on every feature the rows can carry.
     *
     * <p>Each fixture below adds one thing to the same converging real-crude state: a side draw, whose
     * withdrawal fraction makes the liquid leaving a tray a function of that tray's own composition; a
     * prescribed stage heat, which is a constant and must therefore change nothing; a steam feed, which puts
     * water in every vapour at and below it and so adds the dilution term to the equilibrium rows and a water
     * term to the vapour enthalpy; a steam feed large enough to condense on tray one, which adds the free
     * water unknown and its saturation row; and a small steam feed, which caps the condenser's water split at
     * the water that arrived and so switches off the one place a node's own vapour changes its own water.</p>
     *
     * <p>The condenser's uncapped split is the subtlest of these: on the two-phase branch of the free-water
     * regime the overhead carries a fixed number of moles of water per mole of hydrocarbon vapour, so the
     * node's water is a function of the node's own unknowns, which is true nowhere else in the column.</p>
     *
     * <p>Every fixture agrees with the oracle to 4.9e-9 of its row maximum at the coarse resolution and to
     * 4.8e-8 at the fine one.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"plain", "draw", "heat", "steam", "wet", "capped-condenser"})
    void analyticStageBlocksMatchTheColoredReferenceOnEveryRowFeature(String feature) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3ColumnProblem dry = V3ColumnProblemResolver.resolve(
                featuredInput("plain"), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState state = V3ColumnInitializer.initialize(
                dry, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        V3ColumnInput input = featuredInput(feature);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        if (feature.equals("wet")) {
            boolean[] wet = new boolean[problem.topology().nodeCount()];
            wet[1] = true;
            problem = V3ColumnProblemResolver.withTruncation(problem, problem.truncationSupport(),
                    V3WetTraySet.of(problem.topology(), wet));
            double[] freeWater = new double[problem.topology().nodeCount()];
            freeWater[1] = 3.0;
            state = V3ColumnInitializer.withFreeWater(state, problem.topology(), freeWater);
            assertTrue(problem.hasFreeWaterUnknown(1), "the wet fixture must carry a free-water unknown");
        }
        V3FlashResult feed = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator =
                new V3MeshResidualEvaluator(problem, thermo, feed.molarEnthalpyJoulesPerMol());
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        assertEquals(!input.steamFeeds().isEmpty(), problem.hasSteamFeeds());
        if (!input.steamFeeds().isEmpty()) {
            double arriving = problem.waterVaporFlowMolPerSecond(1);
            boolean capped = problem.waterVaporFlow(state, 0) >= arriving;
            assertEquals(feature.equals("capped-condenser"), capped,
                    "the condenser split of " + feature + " must exercise the intended side of its cap");
        }

        V3FiniteDifferenceJacobian.Jacobian reference = V3FiniteDifferenceJacobian.evaluateStageColored(
                evaluator, coordinates, state, thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.COARSE);
        V3BlockJacobianAssembler.AnalyticTally tally = new V3BlockJacobianAssembler.AnalyticTally();
        V3BandedMatrix flattened = V3BlockJacobianAssembler.assembleLocal(problem, evaluator, coordinates, state,
                thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.COARSE, V3SolveControl.UNBOUNDED,
                tally).toBandedMatrix();

        assertEquals(problem.topology().nodeCount(), tally.analyticNodes(), feature + " analytic nodes");
        assertEquals(0, tally.fallbackNodes(), feature + " probe fallbacks");
        assertEquilibratedRowsAgree(reference.values(), flattened, feature);
    }

    /**
     * The one condenser branch the calculator reaches only without reflux, on the same analytic path.
     *
     * <p>A vapour-only condenser has no liquid at all, so the node contributes no equilibrium rows and no
     * liquid enthalpy; the analytic derivatives have to produce exact zeros there rather than evaluate a
     * phase that is not present. Observed agreement 3.3e-8 of the row maximum.</p>
     */
    @Test
    void analyticStageBlocksMatchTheColoredReferenceOnAVaporOnlyCondenser() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_000.0 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlowMolPerSecond;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(0.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.VAPOR_ONLY);
        V3DryMeshState state = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        V3FlashResult feed = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator =
                new V3MeshResidualEvaluator(problem, thermo, feed.molarEnthalpyJoulesPerMol());
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);

        V3FiniteDifferenceJacobian.Jacobian reference = V3FiniteDifferenceJacobian.evaluateStageColored(
                evaluator, coordinates, state, thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.COARSE);
        V3BlockJacobianAssembler.AnalyticTally tally = new V3BlockJacobianAssembler.AnalyticTally();
        V3BandedMatrix flattened = V3BlockJacobianAssembler.assembleLocal(problem, evaluator, coordinates, state,
                thermo::newWorkspace, V3FiniteDifferenceJacobian.DifferenceScale.COARSE, V3SolveControl.UNBOUNDED,
                tally).toBandedMatrix();

        assertEquals(problem.topology().nodeCount(), tally.analyticNodes());
        assertEquals(0, tally.fallbackNodes());
        assertEquilibratedRowsAgree(reference.values(), flattened, "VAPOR_ONLY");
    }

    /**
     * Compares every entry against its own row's largest magnitude.
     *
     * <p>That is the scale the band solver equilibrates each row by before it pivots, so it is the scale at
     * which a difference between two Jacobians can change what the solver does. Measuring instead against the
     * entry itself asks for relative accuracy in entries a hundred million times smaller than their
     * neighbours, which no finite difference of a phase enthalpy rate can deliver.</p>
     */
    private static void assertEquilibratedRowsAgree(double[][] reference, V3BandedMatrix actual, String location) {
        assertEquals(reference.length, actual.size(), location);
        for (int row = 0; row < reference.length; row++) {
            double rowMaximum = 0.0;
            for (double value : reference[row]) rowMaximum = Math.max(rowMaximum, Math.abs(value));
            assertTrue(rowMaximum > 0.0, location + " row " + row + " is empty");
            for (int column = 0; column < reference[row].length; column++) {
                assertEquals(reference[row][column] / rowMaximum, actual.get(row, column) / rowMaximum, 1.0e-6,
                        location + " row " + row + ", column " + column);
            }
        }
    }

    private static V3ColumnInput featuredInput(String feature) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_000.0 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlowMolPerSecond;
        List<V3SideDrawSpec> draws = feature.equals("draw") ? List.of(new V3SideDrawSpec(10, 40.0)) : List.of();
        List<V3SteamFeedSpec> steam = switch (feature) {
            case "steam" -> List.of(new V3SteamFeedSpec(31, 20.0, 533.15));
            case "wet" -> List.of(new V3SteamFeedSpec(31, 60.0, 533.15));
            case "capped-condenser" -> List.of(new V3SteamFeedSpec(31, 2.0, 533.15));
            default -> List.of();
        };
        List<V3PumparoundSpec> pumparounds = feature.equals("heat")
                ? List.of(new V3PumparoundSpec(8, 10, -3.0e6, V3PumparoundSpec.Split.UNIFORM)) : List.of();
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), draws, steam, pumparounds);
    }

    /**
     * Holds the single-entry probe decode and the shared thermodynamic workspace to the bit against the
     * whole-vector decode and the per-probe workspace they replaced.
     *
     * <p>The local assembler is a pure function of the base local terms and, per column, of the two probe
     * terms; nothing else about it changed. So a bitwise agreement on the probe state and on every number of
     * the terms that state produces is a bitwise agreement on the assembled blocks, which is the property the
     * solver depends on: the same Newton direction, the same line search, the same iterate. This asserts both
     * halves — the decoded state entry by entry, and the terms field by field — for every coordinate of a
     * registered CDU17 fixture and for both signs of its finite-difference step.</p>
     */
    @Test
    void everyProbeOfTheRealCrudeFixtureDecodesAndEvaluatesBitwiseAsTheWholeVectorProbeDid() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnInput input = realCrudeInput(crude, 100_000.0);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState state = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        V3FlashResult feed = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feed.molarEnthalpyJoulesPerMol());
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);

        assertEveryProbeIsBitwiseIdentical(evaluator, coordinates, state, thermo::newWorkspace);
    }

    /**
     * The same bitwise agreement on the one coordinate family the crude fixture has none of.
     *
     * <p>A free-water coordinate is the only one that moves a vector outside the two flow matrices, so it is
     * the only one whose single-entry decode touches a different array than the others.</p>
     */
    @Test
    void everyProbeOfAWetFixtureDecodesAndEvaluatesBitwiseAsTheWholeVectorProbeDid() {
        V3ColumnProblem wet = wetProblem();
        SmoothThermo thermo = new SmoothThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(wet, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(wet);
        assertTrue(coordinates.unknowns().stream().anyMatch(unknown ->
                        unknown.id().family() == V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW),
                "fixture must carry a free-water coordinate");

        assertEveryProbeIsBitwiseIdentical(evaluator, coordinates, wetState(wet), thermo::newWorkspace);
    }

    /**
     * A step no coordinate survives has to be refused by the single-entry decode exactly where the whole-vector
     * decode refused it, because that refusal is what tells the assembler a probe is inadmissible.
     */
    @Test
    void anInadmissibleProbeStepIsRefusedIdenticallyByBothDecodes() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(
                realCrudeInput(crude, 100_000.0), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState state = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        double[] base = coordinates.encode(state);
        V3DryMeshState decodedBase = coordinates.decode(base);
        int refusedFlows = 0;

        for (int index = 0; index < base.length; index++) {
            final int column = index;
            for (double signedStep : new double[] {
                    1.0e6, -1.0e6, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
                String location = "column " + column + " step " + signedStep;
                String whole = rejection(() -> coordinates.decode(perturbed(base, column, signedStep)));
                String single = rejection(() -> coordinates.decodePerturbed(decodedBase, base, column, signedStep));
                assertEquals(whole, single, location);
                if (whole != null && coordinates.unknowns().get(column).id().family()
                        != V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE) {
                    refusedFlows++;
                }
            }
        }
        // Every flow coordinate overflows one way and underflows the other, and no coordinate survives a
        // non-finite step, so a silently admissible fixture would mean the loop above proved nothing.
        assertTrue(refusedFlows > 0, "the fixture must actually reach the refusing branch");
    }

    private static void assertEveryProbeIsBitwiseIdentical(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory) {
        double[] base = coordinates.encode(state);
        V3DryMeshState decodedBase = coordinates.decode(base);
        // The assembler's shared workspace has already carried a whole-column residual before the first probe
        // reaches it, so the reused one under test starts out exactly as loaded as the production one.
        V3ThermoWorkspace shared = workspaceFactory.newWorkspace();
        evaluator.evaluate(state, shared);
        int compared = 0;
        int refused = 0;

        for (int index = 0; index < base.length; index++) {
            final int column = index;
            V3DegreeOfFreedomLedger.UnknownId unknown = coordinates.unknowns().get(column).id();
            double step = V3FiniteDifferenceJacobian.step(
                    base[column], unknown.family(), V3FiniteDifferenceJacobian.DifferenceScale.FINE);
            for (double signedStep : new double[] {step, -step}) {
                String location = "column " + column + " (" + unknown + ") step " + signedStep;
                assertEquals(rejection(() -> coordinates.decode(perturbed(base, column, signedStep))),
                        rejection(() -> coordinates.decodePerturbed(decodedBase, base, column, signedStep)),
                        location + " decode rejection");
                V3DryMeshState whole = coordinates.decode(perturbed(base, column, signedStep));
                V3DryMeshState single = coordinates.decodePerturbed(decodedBase, base, column, signedStep);
                assertStateBitsEqual(whole, single, location);

                // A probe the property package refuses is admissible input to the assembler, which reads the
                // refusal as a missing side of the difference; both paths have to refuse it the same way.
                String wholeRefusal = rejection(() ->
                        evaluator.localTerms(whole, unknown.node(), workspaceFactory.newWorkspace()));
                String singleRefusal = rejection(() -> evaluator.localTerms(single, unknown.node(), shared));
                assertEquals(wholeRefusal, singleRefusal, location + " evaluation rejection");
                if (wholeRefusal != null) {
                    refused++;
                    continue;
                }
                assertTermsBitsEqual(evaluator.localTerms(whole, unknown.node(), workspaceFactory.newWorkspace()),
                        evaluator.localTerms(single, unknown.node(), shared), location);
                compared++;
            }
        }
        assertEquals(2 * base.length, compared + refused, "every coordinate must be probed on both sides");
        assertTrue(compared > 0, "the fixture must produce admissible probes to compare");
    }

    private static double[] perturbed(double[] base, int column, double signedStep) {
        double[] candidate = base.clone();
        candidate[column] += signedStep;
        return candidate;
    }

    /** The class and message a decode refused a candidate with, or {@code null} when it accepted it. */
    private static String rejection(Runnable decode) {
        try {
            decode.run();
            return null;
        } catch (RuntimeException refused) {
            return refused.getClass().getName() + ": " + refused.getMessage();
        }
    }

    private static void assertStateBitsEqual(V3DryMeshState expected, V3DryMeshState actual, String location) {
        assertEquals(expected.nodeCount(), actual.nodeCount(), location + " node count");
        assertEquals(expected.componentCount(), actual.componentCount(), location + " component count");
        for (int node = 0; node < expected.nodeCount(); node++) {
            assertBitsEqual(expected.temperatureKelvin(node), actual.temperatureKelvin(node),
                    location + " temperature " + node);
            assertBitsEqual(expected.freeWaterFlow(node), actual.freeWaterFlow(node),
                    location + " free water " + node);
            for (int component = 0; component < expected.componentCount(); component++) {
                assertBitsEqual(expected.liquidFlow(node, component), actual.liquidFlow(node, component),
                        location + " liquid " + node + "/" + component);
                assertBitsEqual(expected.vaporFlow(node, component), actual.vaporFlow(node, component),
                        location + " vapor " + node + "/" + component);
            }
        }
    }

    private static void assertTermsBitsEqual(
            V3MeshResidualEvaluator.LocalNodeTerms expected,
            V3MeshResidualEvaluator.LocalNodeTerms actual,
            String location) {
        double[] expectedEquilibrium = expected.equilibriumResiduals();
        double[] actualEquilibrium = actual.equilibriumResiduals();
        assertEquals(expectedEquilibrium.length, actualEquilibrium.length, location + " equilibrium length");
        for (int component = 0; component < expectedEquilibrium.length; component++) {
            assertBitsEqual(expectedEquilibrium[component], actualEquilibrium[component],
                    location + " equilibrium " + component);
        }
        assertBitsEqual(expected.liquidPhaseEnergy(), actual.liquidPhaseEnergy(), location + " liquid energy");
        assertBitsEqual(expected.vaporPhaseEnergy(), actual.vaporPhaseEnergy(), location + " vapor energy");
        assertBitsEqual(expected.freeWaterPhaseEnergy(), actual.freeWaterPhaseEnergy(), location + " free-water energy");
        assertBitsEqual(expected.waterSaturationResidual(), actual.waterSaturationResidual(),
                location + " water saturation");
    }

    private static void assertBitsEqual(double expected, double actual, String location) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), location);
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

    /**
     * The manufactured binary at 1.5 MPa with 10 mol/s of sump steam: at 410 K the water saturation pressure
     * is 349 kPa and the tray-one vapour would carry 10 of 40 mol/s of water at 1.5 MPa, so tray one is
     * supersaturated and takes a free-water phase.
     */
    private static V3ColumnProblem wetProblem() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                4, 2, 1_500_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)), List.of(),
                List.of(new V3SteamFeedSpec(5, 10.0, 520.0)));
        V3ColumnProblem dry = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        boolean[] wet = new boolean[dry.topology().nodeCount()];
        wet[1] = true;
        return V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(),
                V3WetTraySet.of(dry.topology(), wet));
    }

    private static V3DryMeshState wetState(V3ColumnProblem problem) {
        double[] freeWater = new double[problem.topology().nodeCount()];
        freeWater[1] = 2.5;
        return V3ColumnInitializer.withFreeWater(state(problem.topology()), problem.topology(), freeWater);
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
