package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DryInsideOutColumnSolverTest {
    @Test
    void globalTrustRegionScalingPreservesTheCoupledCorrectionDirection() {
        double[] correction = {20.0, -10.0, 5.0};

        double scale = DryInsideOutColumnSolver.globallyLimitTemperatureCorrection(correction, 0.60, 2.0);

        assertEquals(0.10, scale, 1.0e-12);
        assertEquals(2.0, correction[0], 1.0e-12);
        assertEquals(-1.0, correction[1], 1.0e-12);
        assertEquals(0.5, correction[2], 1.0e-12);
    }

    @Test
    void defaultPathDoesNotRejectEveryEnergyBacktrack() {
        DryColumnOutcome outcome = new DryInsideOutColumnSolver().solve(ColumnProblem.resolve(ColumnNextInput.defaults()));
        if (outcome instanceof DryColumnOutcome.Failure failure) {
            assertFalse(failure.diagnostics().events().stream()
                    .anyMatch(event -> event.startsWith("ENERGY_BACKTRACK_REJECTED")), failure::summary);
        }
    }

    @Test
    void cancellationProducesTypedOutcomeBeforeAnyPartialResultCanEscape() {
        DryColumnOutcome outcome = new DryInsideOutColumnSolver().solve(
                ColumnProblem.resolve(ColumnNextInput.defaults()),
                new DrySolveControl(() -> true, Long.MAX_VALUE));

        DryColumnOutcome.Failure failure = assertInstanceOf(DryColumnOutcome.Failure.class, outcome);
        assertEquals(DrySolverFailureCode.CANCELLED, failure.code());
        assertFalse(failure.diagnostics().acceptanceAudit().accepted());
        assertEquals(DryResidualFamily.CANCELLATION, failure.diagnostics().acceptanceAudit().checks().getFirst().family());
    }

    @Test
    void unifiedPathAttemptsWaterSeparatelyRatherThanAddingItToTheHydrocarbonBasis() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput wet = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal(),
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(), defaults.sideDraws(),
                List.of(new ColumnNextInput.WaterSteamFeedInput(
                        ColumnNextInput.UtilityFeedMode.STEAM, 10, 1.0, 450.0, 300_000.0)));

        DryColumnOutcome outcome = new DryInsideOutColumnSolver().solve(ColumnProblem.resolve(wet));

        DryColumnOutcome.Failure failure = assertInstanceOf(DryColumnOutcome.Failure.class, outcome);
        assertNotEquals(DrySolverFailureCode.DOF_MISMATCH, failure.code());
        assertFalse(failure.diagnostics().acceptanceAudit().accepted());
    }

    @Test
    void boundedIncompleteAttemptReturnsFailureRatherThanNominalSuccess() {
        DrySolverLimits onePass = new DrySolverLimits(1, 1, 1, 0.60, 0.25, 10.0, 2.0);
        DryColumnOutcome outcome = new DryInsideOutColumnSolver(onePass).solve(ColumnProblem.resolve(ColumnNextInput.defaults()));

        DryColumnOutcome.Failure failure = assertInstanceOf(DryColumnOutcome.Failure.class, outcome);
        assertFalse(failure.diagnostics().acceptanceAudit().accepted());
        assertTrue(failure.code() == DrySolverFailureCode.INNER_NONCONVERGENCE
                || failure.code() == DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN
                || failure.code() == DrySolverFailureCode.EOS_ROOT_FAILURE
                || failure.code() == DrySolverFailureCode.NEGATIVE_PHASE_FLOW
                || failure.code() == DrySolverFailureCode.CONTINUATION_FAILURE);
    }

    @Test
    void continuationScalesOnlyTheSpecifiedRecoveryInputs() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput input = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal(),
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(), defaults.sideDraws(),
                List.of(new ColumnNextInput.WaterSteamFeedInput(
                        ColumnNextInput.UtilityFeedMode.STEAM, 10, 4.0, 450.0, 300_000.0)));

        ColumnNextInput scaled = DryInsideOutColumnSolver.continuationInput(input, 0.25);

        assertEquals(input.stagePressureDropPascal() * 0.25, scaled.stagePressureDropPascal(), 1.0e-12);
        assertEquals(input.sideDraws().size(), scaled.sideDraws().size());
        assertEquals(input.sideDraws().getFirst().basis(), scaled.sideDraws().getFirst().basis());
        assertEquals(input.sideDraws().getFirst().authoredRate() * 0.25,
                scaled.sideDraws().getFirst().authoredRate(), 1.0e-12);
        assertEquals(input.utilityFeeds().getFirst().mode(), scaled.utilityFeeds().getFirst().mode());
        assertEquals(input.utilityFeeds().getFirst().stageNumber(), scaled.utilityFeeds().getFirst().stageNumber());
        assertEquals(1.0, scaled.utilityFeeds().getFirst().molarFlowMolPerSecond(), 1.0e-12);
        assertEquals(input.utilityFeeds().getFirst().temperatureKelvin(),
                scaled.utilityFeeds().getFirst().temperatureKelvin(), 0.0);
        assertEquals(input.utilityFeeds().getFirst().upstreamPressurePascal(),
                scaled.utilityFeeds().getFirst().upstreamPressurePascal(), 0.0);
    }

    @Test
    void vaporOnlyTopologySharesThePhysicalResidualConventionWithItsThomasRows() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput input = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                4, 3, defaults.topPressurePascal(), 0.0, defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), 0.0, List.of(), List.of());
        ColumnTopology topology = ColumnProblem.resolve(input).topology();
        int nodes = topology.nodeCount();
        double[] k = new double[nodes];
        double[] liquidTotals = new double[nodes];
        double[] vaporTotals = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            k[node] = 0.45 + 0.04 * node;
            liquidTotals[node] = node == 0 ? 0.0 : 12.0 + node;
            vaporTotals[node] = 8.0 + node;
        }
        int reduced = nodes - 1;
        double[] lower = new double[reduced];
        double[] diagonal = new double[reduced];
        double[] upper = new double[reduced];
        double[] rhs = new double[reduced];
        double[] solution = new double[reduced];
        topology.assembleVaporOnlyHydrocarbonRows(k, liquidTotals, vaporTotals, liquidTotals[nodes - 1],
                new double[topology.stageCount() + 1], 5.0, lower, diagonal, upper, rhs);
        assertTrue(ThomasTridiagonalSolver.solve(lower, diagonal, upper, rhs, solution,
                new double[reduced], new double[reduced]) <= 1.0e-12);

        double[] liquid = new double[nodes];
        double[] vapor = new double[nodes];
        for (int stage = 1; stage <= topology.stageCount(); stage++) {
            liquid[stage] = solution[stage - 1];
            vapor[stage] = k[stage] * vaporTotals[stage] / liquidTotals[stage] * liquid[stage];
        }
        liquid[nodes - 1] = solution[reduced - 1];
        vapor[nodes - 1] = k[nodes - 1] * vaporTotals[nodes - 1] / liquidTotals[nodes - 1]
                * liquid[nodes - 1];
        vapor[0] = vapor[1];
        double[] residual = new double[nodes];
        topology.evaluateVaporOnlyHydrocarbonBalanceResiduals(liquid, vapor,
                new double[topology.stageCount() + 1], 5.0, residual);
        for (double value : residual) assertEquals(0.0, value, 1.0e-11);
    }
}
