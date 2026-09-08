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
     * The published 12.84 MW top cooler admits a free-water tray, and its water balance closes on the
     * candidate, but the wet column does not yet converge.
     *
     * <p>This is the one gate of the free-water work that is not met, and the assertion states exactly that
     * rather than hiding it. Measured: the dry stage of the rung converges to 5.0e-14 with tray one at
     * 83.1 C and a saturation ratio of 1.162; the refresh admits tray one with 213 kmol/h of free water; the
     * wet solve drives the residual from 1.50e-1 to 7.81e-4 in five iterations and then finds no
     * Armijo-reducing step, with every tray energy row short by the same 57.6 kW. The Jacobian is not the
     * cause — it agrees with the coloured finite-difference oracle to 1.5e-9 on that very state — the
     * remaining direction is near-null. See {@code documentation/V3_FREE_WATER_TRAYS_REVIEW.md} section 5.
     * The preset itself keeps the halved top cooler and stays above the dew point, so nothing shipped
     * depends on this case.</p>
     */
    @Test
    void thePublishedTopCoolerDutyAdmitsAFreeWaterTrayAndClosesItsWaterBalanceWithoutConvergingYet() {
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
        assertEquals(V3SolverFailureCode.NONCONVERGENCE, failure.code(), failure.summary());
        assertNotNull(outcome.diagnostics().acceptanceAudit(), outcome::toString);
        assertTrue(outcome.diagnostics().acceptanceAudit().advisoryEvidence().stream()
                .anyMatch(advisory -> advisory.startsWith("wet trays: [1")), "the top tray carries free water");
        // The water contract holds even on a candidate the solver could not finish: every mole of steam fed
        // is accounted for as overhead vapour, decanted free water or bottoms free water.
        assertTrue(outcome.diagnostics().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("WATER_BALANCE") && check.passed()));
    }
}
