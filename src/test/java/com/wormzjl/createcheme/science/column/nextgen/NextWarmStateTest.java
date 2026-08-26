package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NextWarmStateTest {
    @Test
    void acceptsNearbyNumericEditsButRejectsStructuralTopologyChange() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnProblem base = ColumnProblem.resolve(defaults);
        NextWarmState warm = NextWarmState.fromCommitted(base, acceptedOutcome(defaults.stageCount()));

        ColumnNextInput nearbyPressure = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal() + 1_000.0,
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(), defaults.sideDraws(), defaults.utilityFeeds());
        ColumnNextInput topologyChanged = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                29, 23, defaults.topPressurePascal(), defaults.stagePressureDropPascal(),
                defaults.condenserOutletTemperatureKelvin(), defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(),
                defaults.sideDraws(), defaults.utilityFeeds());

        assertTrue(warm.isCompatibleWith(ColumnProblem.resolve(nearbyPressure)));
        assertFalse(warm.isCompatibleWith(ColumnProblem.resolve(topologyChanged)));
    }

    private static DryColumnOutcome.Success acceptedOutcome(int stages) {
        int nodes = stages + 2;
        DryAcceptanceAudit audit = acceptedAudit();
        double[][] profile = new double[nodes][16];
        double[][] sideDraws = new double[stages + 1][16];
        DryColumnResult result = new DryColumnResult(stages, new double[nodes], filled(nodes, 250_000.0), profile, profile,
                sideDraws, new double[16], new double[16], new double[16], new double[nodes], new double[nodes],
                new boolean[nodes], 0.0, audit);
        DrySolverDiagnostics diagnostics = new DrySolverDiagnostics(
                0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, "FIXTURE", List.of(), audit);
        return new DryColumnOutcome.Success(result, diagnostics);
    }

    private static double[] filled(int length, double value) {
        double[] values = new double[length];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static DryAcceptanceAudit acceptedAudit() {
        java.util.ArrayList<DryAcceptanceAudit.Check> checks = new java.util.ArrayList<>();
        for (DryResidualFamily family : DryResidualFamily.values()) {
            if (family == DryResidualFamily.INPUT_VALIDITY || family == DryResidualFamily.CANCELLATION) continue;
            checks.add(DryAcceptanceAudit.Check.pass(family, 0.0, 0.0, -1, -1, "fixture"));
        }
        return new DryAcceptanceAudit(checks);
    }
}
