package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import org.junit.jupiter.api.Test;

/** The fresh-calculator preset is the literature column; F3 restores its published top-cooler duty. */
class V3LiteraturePresetTest {
    @Test
    void theLiteraturePresetIsTheFortyTrayThesisColumnWithItsCoolersAtTheDraws() {
        V3ColumnInput input = ColumnCalculatorV3BlockEntity.literatureCduInput();
        V3ColumnProblemResolver.validateInput(input);
        assertEquals(ColumnCalculatorV3BlockEntity.LITERATURE_PACKAGE, input.packageId());
        assertEquals(40, input.stageCount());
        assertEquals(37, input.feedStageNumber());
        assertEquals(250_000.0, input.topPressurePascal());
        assertEquals(0.0, input.stagePressureDropPascal());
        assertEquals(3, input.sideDraws().size());
        assertEquals(3, input.pumparounds().size());
        for (int index = 0; index < 3; index++) {
            V3SideDrawSpec draw = input.sideDraws().get(index);
            V3PumparoundSpec cooler = input.pumparounds().get(index);
            assertEquals(draw.trayNumber(), cooler.drawTray(), "cooler drawn at the product draw stage");
            assertEquals(draw.trayNumber() - 2, cooler.returnTray(), "cooler returned two stages above");
            assertTrue(cooler.dutyWatts() < 0.0, "coolers remove heat");
        }
        assertEquals(-35.51e6, input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum(), 1.0e3);
        assertEquals(-6.42e6, input.pumparounds().get(0).dutyWatts(), 1.0, "top cooler halved: no stripper heat in this reconstruction");
        assertEquals(1, input.steamFeeds().size());
        assertEquals(41, input.steamFeeds().get(0).stageNumber());
        assertEquals(1_200.0 / 3.6, input.steamFeeds().get(0).molarFlowMolPerSecond(), 1.0e-9);
    }

    /**
     * The shipped preset stays dry in water: every stage is above the water dew point, so the free-water
     * contract adds no tray, no unknown and no row to it, and its water balance is the plain steam profile.
     *
     * <p>Measured stage temperatures against thesis Table 1.1 (source, then this reconstruction, C):
     * 1 = 93.7/91.5, 9 = 146.5/137.4, 10 = 147.4/149.5, 17 = 227.5/215.9, 18 = 238.6/228.6, 27 = 304.9/285.0,
     * 28 = 310.9/293.0, 36 = 341.3/313.0, 37 = 341.3/333.8, 41 = 335.1/305.3. The column is still colder than
     * the source below the top because the 18.1 MW of side-stripper reboiler heat is not modelled.</p>
     */
    @Test
    void theLiteraturePresetConvergesAboveTheWaterDewPointWithAClosedWaterBalance() {
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(
                ColumnCalculatorV3BlockEntity.literatureCduInput(), () -> {}, 0.0);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.diagnostics().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("WATER_DEW_POINT") && check.passed()));
        assertTrue(success.diagnostics().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("WATER_BALANCE") && check.passed()));
        assertTrue(success.result().dutyLedger().orElseThrow().stageHeatTotalWatts() < -35.0e6);
        assertTrue(success.diagnostics().acceptanceAudit().advisoryEvidence().stream()
                .noneMatch(advisory -> advisory.startsWith("wet trays:")), "no stage needs a free-water phase");
        // Every mole of the 1,200 kmol/h of stripping steam decants in the drum at 59 C.
        V3ColumnStreamProperties freeWater = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("free_water")).findFirst().orElseThrow();
        assertEquals(1_200.0 / 3.6, freeWater.molarFlowMolPerSecond(), 1.0e-9);
        // Light naphtha follows from the specified reflux and condenser temperature; the source fixes it at 833.
        V3ColumnStreamProperties distillate = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("distillate_liquid")).findFirst().orElseThrow();
        assertEquals(766.8, distillate.molarFlowMolPerSecond() * 3.6, 1.0);
    }

    /**
     * At the published 12.84 MW top cooler tray one is below the water dew point and no free-water phase can
     * lift it, so the column converges and the audit says so by name.
     *
     * <p>The tray water balance telescopes, so the water rising out of tray one is the whole authored steam
     * whatever that tray sheds; its saturation can therefore only move through its own temperature and
     * hydrocarbon vapour, and both are invariant along the free-water exchange cycle with tray two. Measured
     * by parametric continuation on this very column: holding tray one's free water at 0, 100, 200, 400, 800
     * and 1600 kmol/h and converging every other row each time leaves {@code ln(ratio)} at
     * 0.149743 -> 0.149685 and the tray at 83.073 C, a sensitivity of about 1.6e-9 per kmol/h against the
     * 0.1497 that would have to be closed. The continuation refuses the set after two parametric solves and
     * publishes what it measured; the solve keeps its converged dry candidate, which is a strictly better
     * answer than the 7.8e-4 stall this case used to end on. See
     * {@code documentation/V3_FREE_WATER_CONTINUATION_REVIEW.md}.</p>
     *
     * <p>The preset itself keeps the halved top cooler and stays above the dew point, so nothing shipped
     * depends on this case.</p>
     */
    @Test
    void thePublishedTopCoolerDutyConvergesWithTrayOneReportedBelowTheWaterDewPoint() {
        // The source duties as published: without the side-stripper reboiler heat the top lands below the dew point.
        V3ColumnInput preset = ColumnCalculatorV3BlockEntity.literatureCduInput();
        V3ColumnInput fullDuties = new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(),
                preset.componentBasis(), preset.feedComponentMolarFlowsMolPerSecond(), preset.feedTemperatureKelvin(),
                preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(), preset.stagePressureDropPascal(),
                preset.specifications(), preset.sideDraws(), preset.steamFeeds(), java.util.List.of(
                        new V3PumparoundSpec(8, 10, -12.84e6, V3PumparoundSpec.Split.UNIFORM),
                        preset.pumparounds().get(1), preset.pumparounds().get(2)));
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(fullDuties, () -> {}, 0.0);

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE, failure.code(), failure.summary());
        assertNotNull(outcome.diagnostics().acceptanceAudit(), outcome::toString);
        // The candidate is a solved column, not a stalled iterate: it carries a verified final Newton
        // correction and every check except the dew point passes on a fresh recomputation.
        assertTrue(outcome.diagnostics().maximumScaledResidual() < 1.0e-10, outcome::toString);
        assertTrue(outcome.diagnostics().acceptanceAudit().checks().stream()
                .filter(check -> !check.passed())
                .allMatch(check -> check.family().equals("WATER_DEW_POINT")), outcome::toString);
        V3AcceptanceAudit.Check dewPoint = outcome.diagnostics().acceptanceAudit().checks().stream()
                .filter(check -> check.family().equals("WATER_DEW_POINT")).findFirst().orElseThrow();
        assertEquals(1.162, dewPoint.value(), 0.02, dewPoint.detail());
        assertTrue(dewPoint.detail().startsWith("tray 1 "), dewPoint.detail());
        // The continuation states, in the units it measured, why no free-water phase was admitted.
        assertTrue(outcome.diagnostics().events().stream()
                .anyMatch(event -> event.contains("free-water continuation declined")
                        && event.contains("tray 1 cannot be saturated by any free-water flow")),
                () -> String.join(" | ", outcome.diagnostics().events()));
        // The water contract holds: every mole of steam fed is accounted for at the boundary.
        assertTrue(outcome.diagnostics().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("WATER_BALANCE") && check.passed()));
    }
}
