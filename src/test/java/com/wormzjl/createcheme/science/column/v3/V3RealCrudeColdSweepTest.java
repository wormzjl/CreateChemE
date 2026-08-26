package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * Cold-start QA baseline for the V1-scale Tia Juana crude case exposed by the V3 block.
 *
 * <p>It intentionally has no warm state, prior accepted result, or cached iterate. A per-case cooperative budget
 * makes the current high-dimensional finite-difference cost explicit while ensuring the test remains a safe cold
 * diagnostic rather than an unbounded background calculation.</p>
 */
class V3RealCrudeColdSweepTest {
    private static final long CASE_BUDGET_NANOS = 5_000_000_000L;

    @Test
    void v1ScaleRealCrudeColdMatrixReportsInitialResidualsAndBoundedOutcomes() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        List<Case> cases = List.of(
                new Case("two_stage", 2, 1),
                new Case("four_stage", 4, 3),
                new Case("eight_stage", 8, 6),
                new Case("fifteen_stage", 15, 12),
                new Case("v1_default_thirty_stage", 30, 24));
        List<String> report = new ArrayList<>();

        for (Case sample : cases) {
            V3ColumnInput input = v1ScaleInput(crude, sample.stageCount(), sample.feedStage());
            InitialResidual initial = initialResidual(thermo, input);
            Probe probe = runColdWithBudget(input);
            report.add(sample.name() + " stages=" + sample.stageCount()
                    + " initial(material=" + initial.material()
                    + ",equilibrium=" + initial.equilibrium()
                    + ",energy=" + initial.energy() + ") " + probe.summary());
            if (probe.outcome() instanceof V3ColumnOutcome.Failure failure) {
                assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, report::toString);
            }
        }

        System.out.println("V3 real-crude cold matrix\n" + String.join("\n", report));
        assertTrue(report.size() == cases.size());
    }

    private static Probe runColdWithBudget(V3ColumnInput input) {
        long started = System.nanoTime();
        try {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
                if (System.nanoTime() - started >= CASE_BUDGET_NANOS) {
                    throw new CancellationException("cold diagnostic budget exceeded");
                }
            });
            return new Probe(outcome, System.nanoTime() - started, false);
        } catch (CancellationException expectedBudgetExpiry) {
            return new Probe(null, System.nanoTime() - started, true);
        }
    }

    private static InitialResidual initialResidual(V3PengRobinsonThermo thermo, V3ColumnInput input) {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3DryMeshState state = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state();
        V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .evaluate(state, thermo.newWorkspace());
        return new InitialResidual(
                maximum(residual, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE),
                maximum(residual, V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM),
                maximum(residual, V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE));
    }

    private static double maximum(V3MeshResidual residual, V3DegreeOfFreedomLedger.EquationFamily family) {
        return residual.rows().stream()
                .filter(row -> row.equation().family() == family)
                .mapToDouble(row -> Math.abs(row.scaledValue()))
                .max().orElseThrow();
    }

    private static V3ColumnInput v1ScaleInput(V3CrudeFeed crude, int stages, int feedStage) {
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 365.0 + 273.15, stages, feedStage, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private record Case(String name, int stageCount, int feedStage) {}

    private record InitialResidual(double material, double equilibrium, double energy) {}

    private record Probe(V3ColumnOutcome outcome, long elapsedNanos, boolean budgetExceeded) {
        private String summary() {
            if (budgetExceeded) return "BUDGET_EXCEEDED elapsed_ms=" + elapsedNanos / 1_000_000L;
            return switch (outcome) {
                case V3ColumnOutcome.Success success -> "SUCCESS iter=" + success.diagnostics().newtonIterations()
                        + " residual=" + success.diagnostics().maximumScaledResidual()
                        + " elapsed_ms=" + elapsedNanos / 1_000_000L;
                case V3ColumnOutcome.Failure failure -> failure.code() + " iter="
                        + failure.diagnostics().newtonIterations() + " residual="
                        + failure.diagnostics().maximumScaledResidual() + " elapsed_ms=" + elapsedNanos / 1_000_000L;
            };
        }
    }
}
