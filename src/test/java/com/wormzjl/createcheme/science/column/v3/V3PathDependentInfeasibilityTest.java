package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * {@code INFEASIBLE_SPECIFICATION} is a claim about the request, never about the path the initializer took.
 *
 * <p>V3 has two kinds of gate. A <em>request-only</em> gate is computable from the authored specification
 * alone — {@code totalDraw >= totalFeed} and the static cooling admission — and a rejection there is a real
 * infeasibility that no seed can lift. A <em>state-dependent</em> gate reads the last accepted continuation
 * state, so its verdict changes with the initializer: measured on the 405-case neural validation population,
 * 42 of the 108 requests the classical path typed {@code INFEASIBLE_SPECIFICATION} are strictly solved by a
 * neural-seeded pipeline on the identical input. This pins the split: state-dependent gates publish a hinted
 * {@code NONCONVERGENCE}, request-only gates keep the typed infeasibility.</p>
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.createcheme.science.material.Cdu17FixtureExtension.class)
class V3PathDependentInfeasibilityTest {
    private static final long BUDGET_NANOS = 180_000_000_000L;

    /**
     * The base-condenser-duty bound is recomputed from the accepted heat-free continuation state, so it is
     * path-dependent: it must explain itself without claiming the specification cannot be solved at all.
     */
    @Test
    void aStateDependentHeatBoundIsTypedNonconvergenceCarryingItsDiagnosticAsAHint() {
        V3ColumnOutcome heatFree = calculate(v1ScaleInput(List.of()));
        double baseCondenser = Math.abs(assertInstanceOf(V3ColumnOutcome.Success.class, heatFree, heatFree::toString)
                .result().dutyLedger().orElseThrow().condenserWatts());
        long started = System.nanoTime();

        V3ColumnOutcome outcome = calculate(v1ScaleInput(
                List.of(new V3PumparoundSpec(8, 12, -1.02 * baseCondenser, V3PumparoundSpec.Split.UNIFORM))));
        System.out.printf(Locale.ROOT, "path-dependent condenser bound: %.3f s; Q_cond0=%.4g MW; %s%n",
                (System.nanoTime() - started) / 1.0e9, baseCondenser / 1.0e6, outcome);

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.NONCONVERGENCE, failure.code());
        // The bound still explains itself: the GUI shows the same duties it always did.
        assertTrue(failure.summary().contains("Q_cond0"), failure::summary);
        assertTrue(failure.summary().contains(String.format(Locale.ROOT, "%.4g", baseCondenser / 1.0e6)),
                failure::summary);
        // ...and now names the path it belongs to, so the text cannot be read as an infeasible specification.
        assertTrue(failure.summary().contains("continuation path"), failure::summary);
        assertTrue(failure.summary().contains(V3ColumnCalculator.PATH_DEPENDENT_BOUND_SUFFIX), failure::summary);
        assertTrue(failure.diagnostics().solvePath().contains("heat-condenser-bound"),
                failure.diagnostics()::solvePath);
    }

    /** The static cooling admission reads one feed flash of the authored input; it is a real infeasibility. */
    @Test
    void theRequestOnlyStaticCoolingGateStillTypesAnInfeasibleSpecification() {
        V3ColumnOutcome outcome = calculate(v1ScaleInput(
                List.of(new V3PumparoundSpec(8, 12, -200.0e6, V3PumparoundSpec.Split.UNIFORM))));

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertEquals("input/heat-1", failure.diagnostics().solvePath());
        assertEquals(0, failure.diagnostics().newtonIterations(), "no solve may precede a request-only verdict");
        assertTrue(failure.summary().contains("exceeds"), failure::summary);
        assertFalse(failure.summary().contains(V3ColumnCalculator.PATH_DEPENDENT_BOUND_SUFFIX), failure::summary);
    }

    /** Draws that remove the whole feed close no material balance on any path. */
    @Test
    void theRequestOnlyDrawGateStillTypesAnInfeasibleSpecification() {
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(
                V3SideDrawContractTest.input(List.of(new V3SideDrawSpec(2, 100))));

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertEquals(0, failure.diagnostics().newtonIterations(), "no solve may precede a request-only verdict");
        assertFalse(failure.summary().contains(V3ColumnCalculator.PATH_DEPENDENT_BOUND_SUFFIX), failure::summary);
    }

    private static V3ColumnOutcome calculate(V3ColumnInput input) {
        long started = System.nanoTime();
        return V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= BUDGET_NANOS) {
                throw new AssertionError("path-dependent gate case exceeded its cold budget");
            }
        });
    }

    /** The same v1-scale TJL CDU case the pumparound suite uses, so the bound is exercised where it is tuned. */
    private static V3ColumnInput v1ScaleInput(List<V3PumparoundSpec> pumparounds) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 365.0 + 273.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(), List.of(), pumparounds);
    }
}
