package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Small cold operating envelope for the all-liquid condenser regime of the V3 real-crude default. */
class V3TotalCondenserColdSweepTest {
    private static final long CASE_BUDGET_NANOS = 45_000_000_000L;

    @Test
    void realCrudeTotalCondenserTemperatureEnvelopeSeparatesAcceptedAndBudgetBoundedCases() {
        List<String> report = new ArrayList<>();
        for (double temperatureKelvin : List.of(323.15, 328.15)) {
            Probe probe = runCold(input(temperatureKelvin));
            report.add(temperatureKelvin + " K | " + probe.summary());
            if (probe.outcome() instanceof V3ColumnOutcome.Success success) {
                assertTrue(success.result().acceptanceAudit().accepted());
                assertTrue(success.result().convergenceEvidence().satisfiesGates());
            } else if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, report::toString);
                assertFalse(failure.code() == V3SolverFailureCode.INVALID_INPUT, report::toString);
            }
            if (temperatureKelvin == 323.15) {
                assertInstanceOf(V3ColumnOutcome.Success.class, probe.outcome(), report::toString);
            }
        }
        System.out.println("V3 total-condenser cold envelope\n" + String.join("\n", report));
    }

    private static Probe runCold(V3ColumnInput input) {
        long started = System.nanoTime();
        try {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
                if (System.nanoTime() - started >= CASE_BUDGET_NANOS) {
                    throw new CancellationException("total-condenser cold envelope per-case budget exceeded");
                }
            });
            return new Probe(outcome, false, (System.nanoTime() - started) / 1_000_000L);
        } catch (CancellationException bounded) {
            return new Probe(null, true, (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private static V3ColumnInput input(double condenserTemperatureKelvin) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperatureKelvin),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private record Probe(V3ColumnOutcome outcome, boolean budgetExceeded, long elapsedMilliseconds) {
        private String summary() {
            if (budgetExceeded) return "BUDGET_EXCEEDED elapsed_ms=" + elapsedMilliseconds;
            return switch (outcome) {
                case V3ColumnOutcome.Success success -> "SUCCESS streams=" + success.result().streams().size()
                        + " path=" + success.diagnostics().solvePath() + " elapsed_ms=" + elapsedMilliseconds;
                case V3ColumnOutcome.Failure failure -> failure.code() + " residual="
                        + failure.diagnostics().maximumScaledResidual() + " elapsed_ms=" + elapsedMilliseconds;
            };
        }
    }
}
