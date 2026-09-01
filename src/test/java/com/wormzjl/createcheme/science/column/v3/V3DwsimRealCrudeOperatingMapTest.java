package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Cold real-crude operating map for the DWSIM-style V3 initializer and rigorous correction path. */
class V3DwsimRealCrudeOperatingMapTest {
    private static final long CASE_BUDGET_NANOS = 5_000_000_000L;

    @Test
    void twoStageRealCrudeMapSeparatesSpecSensitivityFromInternalFailures() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        List<Case> cases = List.of(
                new Case("base", 638.15, 332.15, 4.17, 8.0),
                new Case("reflux_1", 638.15, 332.15, 1.0, 8.0),
                new Case("reflux_2", 638.15, 332.15, 2.0, 8.0),
                new Case("duty_0", 638.15, 332.15, 2.0, 0.0),
                new Case("duty_2", 638.15, 332.15, 2.0, 2.0),
                new Case("duty_4", 638.15, 332.15, 2.0, 4.0),
                new Case("condenser_400", 638.15, 400.0, 2.0, 8.0),
                new Case("condenser_450", 638.15, 450.0, 2.0, 8.0),
                new Case("feed_600", 600.0, 400.0, 2.0, 4.0));
        StringBuilder report = new StringBuilder("V3 DWSIM-path real-crude operating map\n");

        for (Case sample : cases) {
            Probe probe = solve(crude, sample);
            report.append(sample.name()).append(" | ").append(probe.summary()).append('\n');
            if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR,
                        () -> sample.name() + ": " + failure);
                assertFalse(failure.code() == V3SolverFailureCode.INVALID_INPUT,
                        () -> sample.name() + ": " + failure);
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
                    throw new CancellationException("cold operating-map budget exceeded");
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
                flows, sample.feedTemperatureKelvin(), 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(sample.condenserTemperatureKelvin()),
                        new V3ColumnSpecification.OrganicRefluxRatio(sample.refluxRatio()),
                        new V3ColumnSpecification.ReboilerDuty(sample.reboilerDutyMegawatt() == 0.0
                                ? Double.MIN_NORMAL : sample.reboilerDutyMegawatt() * 1_000_000.0)));
    }

    private record Case(
            String name,
            double feedTemperatureKelvin,
            double condenserTemperatureKelvin,
            double refluxRatio,
            double reboilerDutyMegawatt) {}

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
