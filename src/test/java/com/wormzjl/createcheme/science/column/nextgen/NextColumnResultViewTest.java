package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NextColumnResultViewTest {
    @Test
    void acceptedResultProjectsStableStreamColumnsWithoutRescalingFlows() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput input = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                2, 1, defaults.topPressurePascal(), defaults.stagePressureDropPascal(),
                defaults.condenserOutletTemperatureKelvin(), defaults.reboilerDutyWatts(), 1.0, List.of(), List.of());
        ColumnProblem problem = ColumnProblem.resolve(input);
        DryAcceptanceAudit audit = acceptedAudit();
        double[][] liquid = profile(4, 2.0);
        double[][] vapor = profile(4, 1.0);
        double[][] sideDraws = new double[3][16];
        double[] reflux = componentFlow(0.5);
        double[] overhead = componentFlow(1.0);
        double[] bottoms = componentFlow(0.5);
        DryColumnResult result = new DryColumnResult(2,
                new double[] {332.15, 450.0, 550.0, 560.0},
                new double[] {250_000.0, 250_000.0, 250_750.0, 250_750.0},
                liquid, vapor, sideDraws, reflux, overhead, bottoms,
                new double[] {0.25, 0.0, 0.0, 0.0}, new double[4], new boolean[4], 250_000.0, audit);
        DrySolverDiagnostics diagnostics = new DrySolverDiagnostics(2, 4, 64, 4, 16, 0.0, 0.0, 0.0, 0.0, 0.0,
                "COLD", List.of(), audit);

        NextColumnResultView view = NextColumnResultView.fromAccepted(
                problem, new DryColumnOutcome.Success(result, diagnostics), "COLD");

        assertEquals(17, view.componentAxis().size());
        assertEquals("crude_feed", view.streams().getFirst().id());
        assertTrue(view.streams().stream().anyMatch(stream -> stream.id().equals("overhead")));
        assertTrue(view.streams().stream().anyMatch(stream -> stream.id().equals("hydrocarbon_reflux")));
        assertTrue(view.streams().stream().anyMatch(stream -> stream.id().equals("bottom_aqueous")));
        assertFalse(view.resultDigest().isBlank());
        for (NextColumnResultView.Stream stream : view.streams()) {
            assertEquals(17, stream.componentMolarFlows().length);
            assertTrue(stream.molarFlowMolPerSecond() >= 0.0);
        }
    }

    private static double[][] profile(int nodes, double flow) {
        double[][] result = new double[nodes][16];
        for (int node = 0; node < nodes; node++) result[node][4] = flow;
        return result;
    }

    private static double[] componentFlow(double flow) {
        double[] result = new double[16];
        result[4] = flow;
        return result;
    }

    private static DryAcceptanceAudit acceptedAudit() {
        java.util.ArrayList<DryAcceptanceAudit.Check> checks = new java.util.ArrayList<>();
        for (DryResidualFamily family : DryResidualFamily.values()) {
            if (family == DryResidualFamily.INPUT_VALIDITY || family == DryResidualFamily.CANCELLATION) continue;
            checks.add(DryAcceptanceAudit.Check.pass(family, 0.0, 0.0, -1, -1, "fixture accepted"));
        }
        return new DryAcceptanceAudit(checks);
    }
}
