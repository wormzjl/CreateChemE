package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import org.junit.jupiter.api.Test;

/** The fresh-calculator preset is the literature column, and its one physical verdict is reported by name. */
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
        assertEquals(-41.93e6, input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum(), 1.0e3);
        assertEquals(1, input.steamFeeds().size());
        assertEquals(41, input.steamFeeds().get(0).stageNumber());
        assertEquals(1_200.0 / 3.6, input.steamFeeds().get(0).molarFlowMolPerSecond(), 1.0e-9);
    }

    @Test
    void aConvergedColumnWithATrayBelowTheWaterDewPointIsReportedByName() {
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(
                ColumnCalculatorV3BlockEntity.literatureCduInput(), () -> {}, 0.0);
        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.WATER_DEW_POINT, failure.code(), failure.summary());
        assertTrue(failure.summary().startsWith("Converged, but tray "), failure.summary());
        assertTrue(failure.summary().contains("is below the water dew point"), failure.summary());
        assertTrue(failure.summary().contains("must not operate below the water dew point"), failure.summary());
        assertTrue(failure.diagnostics().acceptanceAudit().checks().stream()
                .filter(check -> !check.passed()).allMatch(check -> check.family().equals("WATER_DEW_POINT")));
    }
}
