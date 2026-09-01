package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class V3Sotelo2019SideDrawCaseTest {
    @Test
    void sourceAnalogPreservesPublishedGeometryAndFlowFractions() {
        V3ColumnInput input = V3Sotelo2019SideDrawCase.sourceGeometryAnalog(1.0);
        assertEquals(29, input.stageCount());
        assertEquals(104_000.0, input.topPressurePascal());
        assertEquals((198_540.0 - 104_000.0) / 29.0, input.stagePressureDropPascal());
        assertEquals(List.of(13, 17, 22), input.sideDraws().stream().map(V3SideDrawSpec::trayNumber).toList());
        double feed = java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        double[] expectedFractions = {14.0 / 99.0, 20.0 / 99.0, 5.0 / 99.0};
        for (int draw = 0; draw < expectedFractions.length; draw++) {
            assertEquals(expectedFractions[draw], input.sideDraws().get(draw).molarFlowMolPerSecond() / feed, 1.0e-12);
        }
        assertEquals(39.0 / 99.0, input.sideDraws().stream()
                .mapToDouble(V3SideDrawSpec::molarFlowMolPerSecond).sum() / feed, 1.0e-12);
    }

    @Test
    void quarterLoadDryQualificationConvergesAndPublishesAllThreeDraws() {
        V3ColumnInput input = V3Sotelo2019SideDrawCase.dryQualificationInput();
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started > 45_000_000_000L) {
                throw new AssertionError("Sotelo dry qualification exceeded 45 seconds");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertEquals(6, success.result().streams().size());
        for (V3SideDrawSpec draw : input.sideDraws()) {
            V3ColumnStreamProperties stream = success.result().streams().stream()
                    .filter(candidate -> candidate.displayName().equals("Side draw (tray " + draw.trayNumber() + ")"))
                    .findFirst().orElseThrow();
            assertEquals(draw.molarFlowMolPerSecond(), stream.molarFlowMolPerSecond(), 1.0e-8);
        }
    }
}
