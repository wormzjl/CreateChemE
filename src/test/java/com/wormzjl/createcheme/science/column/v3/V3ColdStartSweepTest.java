package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold-start QA map for the registered two-stage PR pilot.
 *
 * <p>Every case gets a brand-new immutable input and invokes only {@link V3ColumnCalculator#calculate}; no accepted
 * state, cache, or prior attempt seeds a later point. The matrix holds topology and PC03/PC10 composition fixed while
 * covering practical setup edits exposed by the current V3 screen. It deliberately records numerical failure as a
 * typed outcome rather than pretending every in-range combination is feasible.</p>
 */
class V3ColdStartSweepTest {
    @Test
    void reasonableSetupMatrixHasBoundedTypedColdOutcomes() {
        List<Case> matrix = reasonableMatrix();
        Map<String, V3ColumnOutcome> outcomes = new LinkedHashMap<>();
        StringBuilder report = new StringBuilder("V3 cold-start sweep\n");
        for (Case sample : matrix) {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(sample.input());
            outcomes.put(sample.name(), outcome);
            report.append(sample.name()).append(" | ").append(summary(outcome)).append('\n');
            if (outcome instanceof V3ColumnOutcome.Success success) {
                assertTrue(success.result().acceptanceAudit().accepted(), sample.name());
                assertTrue(success.result().convergenceEvidence().satisfiesGates(), sample.name());
            } else if (outcome instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, () -> sample.name() + ": " + failure);
                assertFalse(failure.code() == V3SolverFailureCode.INVALID_INPUT, () -> sample.name() + ": " + failure);
            }
        }
        long accepted = outcomes.values().stream().filter(V3ColumnOutcome::isSuccess).count();
        report.append("accepted=").append(accepted).append(" / ").append(matrix.size()).append('\n');
        System.out.println(report);
        assertTrue(outcomes.get("nominal") instanceof V3ColumnOutcome.Success, report::toString);
        assertTrue(accepted >= 5L, report::toString);
        assertEquals(matrix.size(), outcomes.size(), "Every cold-sweep case must have a unique stable name");
    }

    private static List<Case> reasonableMatrix() {
        List<Case> cases = new ArrayList<>();
        add(cases, "nominal", 100.0, 550.0, 250.0, 0.75, 300.0, 2.0, 0.0);

        // One-factor cold edits centred on the accepted pilot. Values reflect the exposed UI units.
        for (double flow : List.of(75.0, 125.0)) add(cases, "flow_" + flow, flow, 550.0, 250.0, 0.75, 300.0, 2.0, 0.0);
        for (double temperature : List.of(545.0, 548.0, 551.0, 552.0, 555.0)) {
            add(cases, "feed_k_" + temperature, 100.0, temperature, 250.0, 0.75, 300.0, 2.0, 0.0);
        }
        for (double pressure : List.of(225.0, 275.0)) add(cases, "top_kpa_" + pressure, 100.0, 550.0, pressure, 0.75, 300.0, 2.0, 0.0);
        for (double drop : List.of(0.0, 1.5)) add(cases, "drop_kpa_" + drop, 100.0, 550.0, 250.0, drop, 300.0, 2.0, 0.0);
        for (double condenser : List.of(295.0, 305.0)) add(cases, "condenser_k_" + condenser, 100.0, 550.0, 250.0, 0.75, condenser, 2.0, 0.0);
        for (double reflux : List.of(1.5, 2.5)) add(cases, "reflux_" + reflux, 100.0, 550.0, 250.0, 0.75, 300.0, reflux, 0.0);
        for (double duty : List.of(0.02, 0.05)) add(cases, "reboiler_mw_" + duty, 100.0, 550.0, 250.0, 0.75, 300.0, 2.0, duty);

        // Combined corners distinguish coupled thermal/pressure sensitivity from individual one-factor effects.
        add(cases, "low_corner", 75.0, 545.0, 225.0, 0.0, 295.0, 1.5, 0.0);
        add(cases, "high_corner", 125.0, 555.0, 275.0, 1.5, 305.0, 2.5, 0.0);
        add(cases, "cool_high_reflux", 125.0, 545.0, 250.0, 0.75, 295.0, 2.5, 0.0);
        add(cases, "hot_low_reflux", 75.0, 555.0, 250.0, 0.75, 305.0, 1.5, 0.0);
        return List.copyOf(cases);
    }

    private static void add(
            List<Case> cases,
            String name,
            double feedFlow,
            double feedTemperature,
            double topPressureKpa,
            double dropKpaPerStage,
            double condenserTemperature,
            double reflux,
            double reboilerDutyMegawatt) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = feedFlow / 2.0;
        feedFlows[13] = feedFlow / 2.0;
        cases.add(new Case(name, new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION,
                thermo.packageId(),
                "test:registered-pr-binary",
                thermo.componentBasis(),
                feedFlows,
                feedTemperature,
                2,
                1,
                topPressureKpa * 1_000.0,
                dropKpaPerStage * 1_000.0,
                List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                        new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(reboilerDutyMegawatt == 0.0
                                ? Double.MIN_NORMAL : reboilerDutyMegawatt * 1_000_000.0)))));
    }

    private static String summary(V3ColumnOutcome outcome) {
        return switch (outcome) {
            case V3ColumnOutcome.Success success -> "SUCCESS residual="
                    + success.diagnostics().maximumScaledResidual() + " iterations=" + success.diagnostics().newtonIterations();
            case V3ColumnOutcome.Failure failure -> failure.code() + " residual="
                    + failure.diagnostics().maximumScaledResidual() + " iterations=" + failure.diagnostics().newtonIterations();
        };
    }

    private record Case(String name, V3ColumnInput input) {}
}
