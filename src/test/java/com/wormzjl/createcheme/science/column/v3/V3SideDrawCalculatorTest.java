package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SideDrawCalculatorTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"150000,0", "100000,0", "250000,0", "100000,0.000001"})
    void modestThirtyTrayCrudeDrawsCloseProductBalances(double pressure, double cutoff) {
        V3ColumnInput original = canonicalInput(pressure);
        V3ColumnInput input = new V3ColumnInput(original.schemaVersion(), original.packageId(), original.assayId(),
                original.componentBasis(), original.feedComponentMolarFlowsMolPerSecond(), original.feedTemperatureKelvin(),
                original.stageCount(), original.feedStageNumber(), pressure, original.stagePressureDropPascal(),
                original.specifications(), original.sideDraws().stream()
                .map(draw -> new V3SideDrawSpec(draw.trayNumber(), 0.25 * draw.molarFlowMolPerSecond())).toList());
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started > 45_000_000_000L) throw new AssertionError("side-draw solve exceeded 45 seconds");
        }, cutoff);
        System.out.println("Qualified side draws: pressure=" + pressure + ", cutoff=" + cutoff + "; "
                + (System.nanoTime() - started) / 1e9 + " s; " + outcome);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().solvePath().contains("draws-3"));
        if (cutoff > 0.0) {
            assertFalse(success.result().problem().truncationSupport().isIdentity(),
                    "the draw supply path must preserve a reduced solve instead of forcing a cold untruncated retry");
            assertTrue(success.diagnostics().events().stream().noneMatch(event -> event.startsWith("stage-trace fallback:")),
                    success.diagnostics().events()::toString);
            var defect = success.result().acceptanceAudit().checks().stream()
                    .filter(check -> check.family().equals("TRUNCATION_MASS_DEFECT")).findFirst().orElseThrow();
            assertTrue(defect.passed());
            assertTrue(defect.value() <= 8.0 * cutoff);
            double feed = java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
            double products = success.result().streams().stream().mapToDouble(V3ColumnStreamProperties::molarFlowMolPerSecond).sum();
            assertEquals(defect.value(), (feed - products) / feed, 1.0e-8,
                    "all six products must account for the independently audited truncation defect");
        }
        List<V3ColumnStreamProperties> streams = success.result().streams();
        assertEquals(6, streams.size());
        assertEquals(streams, V3ColumnDisplayResult.fromAccepted(success).streams());
        double[] products = new double[input.componentBasis().componentCount()];
        for (V3ColumnStreamProperties stream : streams) {
            for (int i = 0; i < products.length; i++) {
                products[i] += stream.molarFlowMolPerSecond() * stream.moleFractions().get(i).moleFraction();
            }
        }
        assertArrayEquals(input.feedComponentMolarFlowsMolPerSecond(), products, cutoff > 0 ? 0.01 : 1e-4);
        double lastTemperature = 0.0;
        for (V3SideDrawSpec draw : input.sideDraws()) {
            V3ColumnStreamProperties stream = streams.stream().filter(s -> s.displayName().equals(
                    "Side draw (tray " + draw.trayNumber() + ")")).findFirst().orElseThrow();
            assertEquals(draw.molarFlowMolPerSecond(), stream.molarFlowMolPerSecond(), 1e-8);
            assertTrue(stream.temperatureKelvin() > lastTemperature);
            lastTemperature = stream.temperatureKelvin();
        }
    }

    @Test
    void originalLargeDrawCaseAttemptsRequestedGeometryAndNamesAnAuthoredTray() {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(canonicalInput(150_000), () -> {
            if (System.nanoTime() - started > 45_000_000_000L) throw new AssertionError("large-draw failure exceeded 45 seconds");
        });
        if (outcome instanceof V3ColumnOutcome.Success success) {
            assertTrue(success.result().acceptanceAudit().accepted());
            assertEquals(30, success.result().problem().topology().trayCount());
        } else {
            V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome);
            assertTrue(failure.diagnostics().solvePath().contains("stage-30"), failure.diagnostics()::solvePath);
            assertTrue(failure.summary().contains("authored tray"), failure::summary);
            assertTrue(List.of(8, 15, 22).stream().anyMatch(
                    tray -> failure.summary().contains("authored tray " + tray)), failure::summary);
            assertFalse(failure.summary().contains("continuation grid"), failure::summary);
            assertTrue(failure.diagnostics().events().stream().anyMatch(
                    event -> event.contains("ramp reached 1.0")), failure.diagnostics().events()::toString);
        }
    }

    @Test
    void legalNearFeedDrawNeverBecomesInvalidInputWhenTheDrawBlindSeedIsAvailable() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[6] = 50;
        feed[13] = 50;
        V3ColumnInput input = new V3ColumnInput(1, thermo.packageId(), "test:near-feed-draw",
                thermo.componentBasis(), feed, 550, 2, 1, 250_000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(300),
                new V3ColumnSpecification.OrganicRefluxRatio(2),
                new V3ColumnSpecification.ReboilerDuty(Double.MIN_NORMAL)), List.of(new V3SideDrawSpec(1, 99)));

        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input);
        if (outcome instanceof V3ColumnOutcome.Failure failure) {
            assertNotEquals(V3SolverFailureCode.INVALID_INPUT, failure.code());
            assertTrue(failure.summary().contains("authored tray 1"), failure::summary);
        } else {
            assertTrue(((V3ColumnOutcome.Success) outcome).result().acceptanceAudit().accepted());
        }
    }

    static V3ColumnInput canonicalInput(double pressure) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feed = crude.moleFractions();
        for (int i = 0; i < feed.length; i++) feed[i] *= 2610.7 / 3.6;
        return new V3ColumnInput(1, crude.packageId(), crude.assayId(), crude.componentBasis(), feed,
                638.15, 30, 24, pressure, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(
                new V3SideDrawSpec(8, 496.0 / 3.6), new V3SideDrawSpec(15, 653.0 / 3.6),
                new V3SideDrawSpec(22, 149.0 / 3.6)));
    }
}
