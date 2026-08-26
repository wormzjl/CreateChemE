package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Cold stage-continuation map at a separately proven real-crude operating condition; no warm state is supplied. */
class V3DwsimRealCrudeStageMapTest {
    private static final long CASE_BUDGET_NANOS = 5_000_000_000L;

    @Test
    void realCrudeStageMapAtProvenCondenserConditionHasOnlyTypedColdOutcomes() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        List<Case> cases = List.of(
                new Case(2, 1), new Case(4, 3), new Case(8, 6), new Case(15, 12), new Case(30, 24));
        StringBuilder report = new StringBuilder("V3 DWSIM-path real-crude cold stage map\n");

        for (Case sample : cases) {
            Probe probe = solve(crude, sample);
            report.append("stages=").append(sample.stages()).append(" feed_stage=").append(sample.feedStage())
                    .append(" | ").append(probe.summary()).append('\n');
            if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, failure::toString);
                assertFalse(failure.code() == V3SolverFailureCode.INVALID_INPUT, failure::toString);
            }
        }
        System.out.println(report);
        assertTrue(report.length() > 100);
    }

    private static Probe solve(V3CrudeFeed crude, Case sample) {
        long started = System.nanoTime();
        try {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input(crude, sample), () -> {
                if (System.nanoTime() - started >= CASE_BUDGET_NANOS) {
                    throw new CancellationException("cold stage-map budget exceeded");
                }
            }, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE);
            return new Probe(outcome, false, System.nanoTime() - started);
        } catch (CancellationException expected) {
            return new Probe(null, true, System.nanoTime() - started);
        }
    }

    private static V3ColumnInput input(V3CrudeFeed crude, Case sample) {
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 638.15, sample.stages(), sample.feedStage(), 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private record Case(int stages, int feedStage) {}

    private record Probe(V3ColumnOutcome outcome, boolean budgetExceeded, long elapsedNanos) {
        private String summary() {
            if (budgetExceeded) return "BUDGET_EXCEEDED elapsed_ms=" + elapsedNanos / 1_000_000L;
            return switch (outcome) {
                case V3ColumnOutcome.Success success -> "SUCCESS iterations=" + success.diagnostics().newtonIterations()
                        + " residual=" + success.diagnostics().maximumScaledResidual()
                        + " elapsed_ms=" + elapsedNanos / 1_000_000L;
                case V3ColumnOutcome.Failure failure -> failure.code() + " iterations="
                        + failure.diagnostics().newtonIterations() + " residual="
                        + failure.diagnostics().maximumScaledResidual() + " elapsed_ms=" + elapsedNanos / 1_000_000L;
            };
        }
    }
}
