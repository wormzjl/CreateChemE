package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fresh-calculator preset is the literature column at its published duties; a cold top is a warning, not a rejection. */
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
        assertEquals(-12.84e6, input.pumparounds().get(0).dutyWatts(), 1.0, "published top-cooler duty");
        assertEquals(-41.93e6, input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum(), 1.0e3);
        assertEquals(1, input.steamFeeds().size());
        assertEquals(41, input.steamFeeds().get(0).stageNumber());
        assertEquals(1_200.0 / 3.6, input.steamFeeds().get(0).molarFlowMolPerSecond(), 1.0e-9);
    }

    /**
     * At the published duties tray 1 is below the water dew point of its steam-laden overhead (83 C against
     * 87 C, ratio 1.16) because the reconstruction carries no side-stripper reboiler heat. The column solves,
     * publishes, closes its water balance, and says so as a warning that names the tray. No free-water phase can
     * lift that tray under these specifications, so no wet tray is admitted and the free-water continuation
     * records why (see V3_FREE_WATER_CONTINUATION_REVIEW).
     */
    @Test
    void theLiteraturePresetPublishesWithATrayOneWaterDewPointWarning() {
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(
                ColumnCalculatorV3BlockEntity.literatureCduInput(), () -> {}, 0.0);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(outcome.diagnostics().maximumScaledResidual() < 1.0e-10, outcome::toString);
        V3AcceptanceAudit audit = success.diagnostics().acceptanceAudit();
        assertTrue(audit.accepted(), () -> audit.checks().toString());
        V3AcceptanceAudit.Check dewPoint = audit.checks().stream()
                .filter(check -> check.family().equals("WATER_DEW_POINT")).findFirst().orElseThrow();
        assertTrue(dewPoint.passed(), dewPoint.detail());
        assertEquals(1.162, dewPoint.value(), 0.02, dewPoint.detail());
        assertTrue(dewPoint.detail().startsWith("warning: tray 1 "), dewPoint.detail());
        List<String> warnings = audit.advisoryEvidence().stream().filter(advisory -> advisory.startsWith("Warning: ")).toList();
        assertEquals(1, warnings.size(), () -> String.join(" | ", audit.advisoryEvidence()));
        assertTrue(warnings.getFirst().contains("tray 1") && warnings.getFirst().contains("below the water dew point"), warnings.getFirst());
        assertTrue(audit.advisoryEvidence().stream().noneMatch(advisory -> advisory.startsWith("wet trays:")),
                "no wet tray can be admitted for the top of the column");
        assertTrue(outcome.diagnostics().events().stream()
                .anyMatch(event -> event.contains("free-water continuation declined")), () -> String.join(" | ", outcome.diagnostics().events()));
        assertTrue(audit.checks().stream().anyMatch(check -> check.family().equals("WATER_BALANCE") && check.passed()));
        // Every mole of the 1,200 kmol/h of stripping steam decants in the drum at 59 C.
        V3ColumnStreamProperties freeWater = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("free_water")).findFirst().orElseThrow();
        assertEquals(1_200.0 / 3.6, freeWater.molarFlowMolPerSecond(), 1.0e-9);
        // Light naphtha follows from the specified reflux and condenser temperature; the source fixes it at 833.
        V3ColumnStreamProperties distillate = success.result().streams().stream()
                .filter(stream -> stream.streamId().equals("distillate_liquid")).findFirst().orElseThrow();
        assertEquals(699.4, distillate.molarFlowMolPerSecond() * 3.6, 1.0);
    }

    /** With the top cooler halved the same column clears the dew point and carries no warning. */
    @Test
    void aHalvedTopCoolerClearsTheDewPointWithoutAWarning() {
        V3ColumnInput preset = ColumnCalculatorV3BlockEntity.literatureCduInput();
        V3ColumnInput halved = new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(),
                preset.componentBasis(), preset.feedComponentMolarFlowsMolPerSecond(), preset.feedTemperatureKelvin(),
                preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(), preset.stagePressureDropPascal(),
                preset.specifications(), preset.sideDraws(), preset.steamFeeds(), List.of(
                        new V3PumparoundSpec(8, 10, -6.42e6, V3PumparoundSpec.Split.UNIFORM),
                        preset.pumparounds().get(1), preset.pumparounds().get(2)));
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(halved, () -> {}, 0.0);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3AcceptanceAudit.Check dewPoint = success.diagnostics().acceptanceAudit().checks().stream()
                .filter(check -> check.family().equals("WATER_DEW_POINT")).findFirst().orElseThrow();
        assertTrue(dewPoint.passed() && dewPoint.value() < 1.0, dewPoint.detail());
        assertTrue(success.diagnostics().acceptanceAudit().advisoryEvidence().stream()
                .noneMatch(advisory -> advisory.startsWith("Warning: ")));
    }
}
