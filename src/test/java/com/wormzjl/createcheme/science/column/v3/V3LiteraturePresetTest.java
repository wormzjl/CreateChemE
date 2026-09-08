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

    @Test
    void theLiteraturePresetConvergesAboveTheWaterDewPoint() {
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(
                ColumnCalculatorV3BlockEntity.literatureCduInput(), () -> {}, 0.0);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.diagnostics().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("WATER_DEW_POINT") && check.passed()));
        assertTrue(success.result().dutyLedger().orElseThrow().stageHeatTotalWatts() < -35.0e6);
    }

    /**
     * Placeholder for the F3 expectation, deliberately not asserting an outcome yet.
     *
     * <p>F1 removes the typed {@code WATER_DEW_POINT} verdict; F2 gives the trays a free-water phase; F3 restores
     * the published 12.84 MW top cooler in the preset and turns this into the real assertion — the published
     * duties converge with wet top trays and a closed water balance. Until F2 lands, the published duties still
     * produce a converged state whose top tray sits below the water dew point, so all this pins is that the
     * calculator returns a typed outcome rather than throwing, and that no removed failure code can come back.</p>
     */
    @Test
    void thePublishedTopCoolerDutyIsStillOnlyReachableThroughTheFreeWaterTrayContract() {
        // The source duties as published: without the side-stripper reboiler heat the top lands below the dew point.
        V3ColumnInput preset = ColumnCalculatorV3BlockEntity.literatureCduInput();
        V3ColumnInput fullDuties = new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(),
                preset.componentBasis(), preset.feedComponentMolarFlowsMolPerSecond(), preset.feedTemperatureKelvin(),
                preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(), preset.stagePressureDropPascal(),
                preset.specifications(), preset.sideDraws(), preset.steamFeeds(), java.util.List.of(
                        new V3PumparoundSpec(8, 10, -12.84e6, V3PumparoundSpec.Split.UNIFORM),
                        preset.pumparounds().get(1), preset.pumparounds().get(2)));
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(fullDuties, () -> {}, 0.0);
        assertNotNull(outcome.diagnostics().acceptanceAudit(), outcome::toString);
        if (outcome instanceof V3ColumnOutcome.Failure failure) {
            assertEquals(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE, failure.code(), failure.summary());
        }
    }
}
