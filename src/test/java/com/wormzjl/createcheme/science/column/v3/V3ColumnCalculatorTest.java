package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class V3ColumnCalculatorTest {
    @Test
    void registeredPrBinaryPilotPublishesOnlyAFreshlyAuditedSuccess() {
        V3ColumnOutcome.Success success = assertInstanceOf(
                V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(registeredBinaryPilot()));

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertEquals(success.result().acceptanceAudit(), success.diagnostics().acceptanceAudit());
        assertEquals(success.result().convergenceEvidence(), success.diagnostics().convergenceEvidence());
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, success.result().problem().topology().condenserPhaseBranch());
        assertEquals(2, success.result().streams().size());
        assertTrue(success.result().streams().stream().allMatch(stream -> stream.molarFlowMolPerSecond() > 0.0));
    }

    @Test
    void unknownPropertyPackageIsAStableTypedFailure() {
        V3ColumnInput invalid = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:missing", "test:missing",
                new V3ComponentBasis(List.of("component-a")), new double[] {1.0}, 400.0, 2, 1, 250_000.0, 0.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class,
                V3ColumnCalculator.calculate(invalid));

        assertEquals(V3SolverFailureCode.INVALID_INPUT, failure.code());
        assertTrue(failure.diagnostics().acceptanceAudit().checks().stream().noneMatch(V3AcceptanceAudit.Check::passed));
    }

    @Test
    void cooperativeCancellationEscapesInsteadOfBecomingAScientificFailure() {
        AtomicInteger checkpoints = new AtomicInteger();

        assertThrows(CancellationException.class, () -> V3ColumnCalculator.calculate(registeredBinaryPilot(), () -> {
            if (checkpoints.incrementAndGet() >= 6) {
                throw new CancellationException("test cancellation");
            }
        }));

        assertTrue(checkpoints.get() >= 6);
    }

    @Test
    void productionCalculatorUsesTheCertifiedDwsimStageContinuationForThirtyStageRealCrude() {
        long started = System.nanoTime();
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(registeredRealCrudeThirtyStagePilot(), () -> {
                    if (System.nanoTime() - started >= 60_000_000_000L) {
                        throw new AssertionError("production 30-stage DWSIM continuation exceeded its cold-test budget");
                    }
                }));

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().solvePath().contains("dwsim-sequential/4-8-15-30"));
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")));
    }

    @Test
    void registeredPilotWithSumpSteamPublishesAuditedWetVapor() {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(registeredSteamStrippingPilot(), () -> {
                    if (System.nanoTime() - started >= 60_000_000_000L) {
                        throw new AssertionError("registered steam-stripping pilot exceeded its cold-test budget");
                    }
                });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.result().problem().hasSteamFeeds());
        assertTrue(success.result().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("WATER_PROFILE") && check.passed()));
        V3ColumnStreamProperties overhead = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("overhead_vapor")).findFirst().orElseThrow();
        assertTrue(overhead.moleFractions().stream()
                .anyMatch(fraction -> fraction.componentId().equals("h2o") && fraction.moleFraction() > 0.0));
    }

    @Test
    void registeredPilotWithSumpSteamAndColdCondenserPublishesFreeWater() {
        long started = System.nanoTime();
        V3ColumnInput preset = ColumnCalculatorV3BlockEntity.pilotPresetInput();
        V3ColumnInput cold = new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(),
                preset.componentBasis(), preset.feedComponentMolarFlowsMolPerSecond(), preset.feedTemperatureKelvin(),
                preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(), preset.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), preset.sideDraws(),
                List.of(new V3SteamFeedSpec(preset.stageCount() + 1, 8.0, 450.0)));
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(cold, () -> {
            if (System.nanoTime() - started >= 60_000_000_000L) {
                throw new AssertionError("registered cold steam-stripping pilot exceeded its cold-test budget");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, success.result().problem().topology().condenserPhaseBranch());
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("free_water")
                && stream.molarFlowMolPerSecond() > 0.0));
    }

    @Test
    void registeredPilotWithIntermediateSumpSteamTemperaturePublishesMixedWaterVapor() {
        long started = System.nanoTime();
        V3ColumnInput base = registeredSteamStrippingPilot();
        V3ColumnInput waterLimited = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(),
                base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(),
                base.stageCount(), base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(343.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), base.sideDraws(), base.steamFeeds());
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(waterLimited, () -> {
            if (System.nanoTime() - started >= 60_000_000_000L) {
                throw new AssertionError("registered water-limited steam pilot exceeded its cold-test budget");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")
                && stream.moleFractions().stream().anyMatch(fraction -> fraction.componentId().equals("h2o")
                && fraction.moleFraction() > 0.0)));
        assertFalse(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("free_water")));
    }

    @Test
    void registeredPilotWithWaterLimitedSumpSteamPublishesMixedOverheadVapor() {
        long started = System.nanoTime();
        V3ColumnInput base = registeredSteamStrippingPilot();
        V3ColumnInput hotter = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(),
                base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(),
                base.stageCount(), base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(353.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), base.sideDraws(), base.steamFeeds());
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(hotter, () -> {
            if (System.nanoTime() - started >= 60_000_000_000L) {
                throw new AssertionError("registered water-limited steam pilot exceeded its cold-test budget");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")
                && stream.moleFractions().stream().anyMatch(fraction -> fraction.componentId().equals("h2o")
                && fraction.moleFraction() > 0.0)));
        assertFalse(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("free_water")));
    }

    @Test
    void registeredPilotWithEightyKmolPerHourSteamAtSeventyCelsiusPublishesWaterInBothPhases() {
        long started = System.nanoTime();
        V3ColumnInput base = registeredSteamStrippingPilot();
        V3ColumnInput highSteam = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(),
                base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(),
                base.stageCount(), base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(343.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), base.sideDraws(),
                List.of(new V3SteamFeedSpec(base.stageCount() + 1, 80.0 / 3.6, 450.0)));
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(highSteam, () -> {
            if (System.nanoTime() - started >= 60_000_000_000L) {
                throw new AssertionError("80 kmol/h steam pilot exceeded its cold-test budget");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        V3ColumnStreamProperties overhead = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("overhead_vapor")).findFirst().orElseThrow();
        V3ColumnStreamProperties freeWater = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("free_water")).findFirst().orElseThrow();
        double vaporWater = overhead.molarFlowMolPerSecond() * overhead.moleFractions().stream()
                .filter(fraction -> fraction.componentId().equals("h2o"))
                .findFirst().orElseThrow().moleFraction();

        assertTrue(vaporWater > 0.0, "the overhead vapor must carry molecular water");
        assertTrue(freeWater.molarFlowMolPerSecond() > 0.0, "the condenser drum must decant free water");
        assertEquals(80.0 / 3.6, vaporWater + freeWater.molarFlowMolPerSecond(), 1.0e-8,
                "all authored steam must be allocated between vapor water and free water");
    }

    @ParameterizedTest
    @CsvSource({"323.15, 0.0", "328.15, 0.0", "323.15, 0.1"})
    void coldCondenserProducesOnlyLiquidProducts(double condenserTemperatureKelvin, double methaneFeedFlow) {
        long started = System.nanoTime();
        V3ColumnInput input = realCrudePilot(condenserTemperatureKelvin, methaneFeedFlow);
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= 45_000_000_000L) {
                throw new AssertionError("cold total-condenser solve exceeded its cold-test budget");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, success.result().problem().topology().condenserPhaseBranch());
        assertEquals(2, success.result().streams().size());
        assertTrue(success.result().streams().stream().allMatch(stream -> stream.vaporMoleFraction() == 0.0));
        assertFalse(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")));
        V3ColumnStreamProperties distillate = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("distillate_liquid")).findFirst().orElseThrow();
        assertTrue(distillate.moleFractions().get(1).moleFraction() > 0.0);
        if (methaneFeedFlow > 0.0) assertTrue(distillate.moleFractions().get(0).moleFraction() > 0.0);
        double[] feedFlows = input.feedComponentMolarFlowsMolPerSecond();
        for (int component = 0; component < feedFlows.length; component++) {
            int publicComponent = component;
            double productFlow = success.result().streams().stream().mapToDouble(stream -> stream.molarFlowMolPerSecond()
                    * stream.moleFractions().get(publicComponent).moleFraction()).sum();
            assertEquals(feedFlows[component], productFlow, 1.0e-7 * Math.max(1.0, feedFlows[component]));
        }
    }

    @Test
    void fiftyCelsiusPartialCondenserSeedIncludesEthaneInLiquidAndEquilibriumBalances() {
        V3ColumnInput input = realCrudePilot(323.15, 0.0);
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState seed = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidual initialResidual = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol()).evaluate(seed, thermo.newWorkspace());
        assertEquals("ethane", input.componentBasis().componentId(problem.activeComponentBasis().publicIndex(0)));
        assertTrue(seed.vaporFlow(problem.topology().condenserNode(), 0) > 0.0);
        assertTrue(seed.liquidFlow(problem.topology().condenserNode(), 0) > 0.0);
        assertTrue(initialResidual.rows().stream().anyMatch(row -> row.equation().family()
                == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM
                && row.equation().node() == problem.topology().condenserNode() && row.equation().component() == 0));
        assertTrue(initialResidual.rows().stream().filter(row -> row.equation().family()
                        == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE)
                .allMatch(row -> Math.abs(row.physicalValue()) <= 1.0e-9));
    }

    private static V3ColumnInput registeredBinaryPilot() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = 50.0;
        feedFlows[13] = 50.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }

    private static V3ColumnInput registeredRealCrudeThirtyStagePilot() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feedFlows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                feedFlows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static V3ColumnInput registeredSteamStrippingPilot() {
        V3ColumnInput dryPilot = ColumnCalculatorV3BlockEntity.pilotPresetInput();
        return new V3ColumnInput(dryPilot.schemaVersion(), dryPilot.packageId(), dryPilot.assayId(),
                dryPilot.componentBasis(), dryPilot.feedComponentMolarFlowsMolPerSecond(), dryPilot.feedTemperatureKelvin(),
                dryPilot.stageCount(), dryPilot.feedStageNumber(), dryPilot.topPressurePascal(), dryPilot.stagePressureDropPascal(),
                dryPilot.specifications(), dryPilot.sideDraws(), List.of(new V3SteamFeedSpec(dryPilot.stageCount() + 1, 8.0, 450.0)));
    }

    private static V3ColumnInput realCrudePilot(double condenserTemperatureKelvin, double methaneFeedFlow) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feedFlows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= totalFlow;
        feedFlows[0] = methaneFeedFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                feedFlows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperatureKelvin),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }
}
