package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract of the authored convergence closure.
 *
 * <p>The five cases are the evaluation set of {@code documentation/V3_TRUNCATION_EVALUATION.md} and
 * {@code build/pkgcmp/RefreshProbe.java}: A a dry CDU17 base column, B dry preset draws with a 3 MW cooler,
 * C the wet TJL19 column with three pumparounds and three draws, D a 40 MW return-tray duty (a typed failure
 * from the phase-mask work package until the heat-rung energy-shift predictor recovered it) and E dry CDU17
 * with three draws.</p>
 */
class V3ConvergenceClosureTest {
    private static final String CDU = "createcheme:cdu17_tjl_acs2018";
    private static final String TJL = "createcheme:tjl19_dwsim";
    private static final double STEAM_MOL_PER_SECOND = 1_200.0 * 1_000.0 / 3_600.0;
    private static final double LOOSE_CLOSURE = 1.0e-3;

    /**
     * Every case at the default closure.
     *
     * <p>The counts were pinned from the run at {@code f245b39} that precedes this knob, and case B's
     * published iteration count was re-pinned from 4 to 2 by the product-path band work package, which
     * changes the accepted state of every case that has a side draw. Case D was re-pinned from
     * {@code NONCONVERGENCE, 16, .../failed-stage-30/liquid-only-condenser/heat-1} to
     * {@code SUCCESS, 7, .../fine-fd/heat-ramp-1.0/condenser-phase-correction/heat-1} by the heat-rung
     * energy-shift predictor, which puts the 40 MW rung's seed in the cold basin the solution actually sits
     * in (condenser -30.11 MW against the heat-free -59.37 MW). The digest is checked by recomputing
     * the pre-closure byte stream from the accepted problem rather than by a hex literal: the default must
     * produce exactly the digest of a build that has no closure field at all. The published hex values are
     * recorded in {@code documentation/V3_CLOSURE_AND_BAND_REVIEW.md}.</p>
     */
    @ParameterizedTest
    @CsvSource({
            "A, SUCCESS, 0, cold/stage-continuation/4-8-15-30/fine-fd/liquid-only-condenser",
            "B, SUCCESS, 2, cold/stage-continuation/4-8-15-30/fine-fd/draw-ramp-1.0/liquid-only-condenser/draws-3/heat-1",
            "C, SUCCESS, 3, cold/stage-continuation/4-8-15-30/fine-fd/draw-ramp-1.0/liquid-only-condenser/draws-3/steam-1/heat-3",
            "D, SUCCESS, 7, cold/stage-continuation/4-8-15-30/fine-fd/heat-ramp-1.0/condenser-phase-correction/heat-1",
            "E, SUCCESS, 3, cold/stage-continuation/4-8-15-30/fine-fd/draw-ramp-1.0/liquid-only-condenser/draws-3"})
    void theDefaultClosureLeavesEveryEvaluationCaseExactlyWhereItWas(
            String label, String kind, int newtonIterations, String solvePath) {
        V3ColumnInput input = evaluationCase(label);
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, boundedControl(), 0.0, 0.0);
        assertEquals(kind, outcomeKind(outcome), label);
        assertEquals(solvePath, outcome.diagnostics().solvePath(), label);
        assertEquals(newtonIterations, outcome.diagnostics().newtonIterations(), label);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, outcome.diagnostics().closureTolerance(), 0.0);
        if (outcome instanceof V3ColumnOutcome.Success success) {
            V3ColumnResult result = success.result();
            assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, result.closureTolerance(), 0.0);
            assertFalse(result.formulationRevision().contains("-closure"), result::formulationRevision);
            assertEquals(V3ColumnCalculator.formulationRevision(input, 0.0), result.formulationRevision());
            assertEquals(V3InputDigest.of(result.problem(), result.formulationRevision(),
                            V3PengRobinsonThermo.fromRegisteredPackage(input.packageId()).datasetRevision(),
                            V3ColumnCalculator.assumptionsRevision(input)),
                    result.inputDigest(), label);
        }
    }

    /**
     * The same cases at the loosest admitted closure, case D included since the energy-shift predictor
     * converges it. The closure never reached D while it plateaued: the plateau was three orders above any
     * admitted closure, exactly as the plan predicted, and what recovered the case was the rung seed rather
     * than the acceptance bar.
     */
    @ParameterizedTest
    @CsvSource({"A, 0", "B, 2", "C, 3", "D, 7", "E, 3"})
    void aLooseClosureAcceptsEveryCaseWithinItsOwnScaledLimits(String label, int defaultNewtonIterations) {
        V3ColumnInput input = evaluationCase(label);
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, boundedControl(), 0.0, LOOSE_CLOSURE);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3ColumnResult result = success.result();

        assertEquals(LOOSE_CLOSURE, result.closureTolerance(), 0.0);
        assertEquals(LOOSE_CLOSURE, success.diagnostics().closureTolerance(), 0.0);
        assertEquals(LOOSE_CLOSURE, V3ColumnDisplayResult.fromAccepted(success).closureTolerance(), 0.0);
        assertTrue(success.diagnostics().maximumScaledResidual() <= LOOSE_CLOSURE,
                () -> label + " residual " + success.diagnostics().maximumScaledResidual());
        assertTrue(result.convergenceEvidence().satisfiesGates());
        assertTrue(result.convergenceEvidence().maximumLogFlowChange() <= LOOSE_CLOSURE);
        assertTrue(success.diagnostics().newtonIterations() <= defaultNewtonIterations,
                () -> label + " published iterations " + success.diagnostics().newtonIterations()
                        + " exceed the default closure's " + defaultNewtonIterations);

        assertTrue(result.acceptanceAudit().accepted());
        for (V3AcceptanceAudit.Check check : result.acceptanceAudit().checks()) {
            assertTrue(check.passed(), check::toString);
            assertTrue(check.value() <= check.limit(), check::toString);
        }
        V3AcceptanceAudit.Check equilibrium = check(result, "EQUILIBRIUM");
        assertEquals(LOOSE_CLOSURE, equilibrium.limit(), 0.0, "the equilibrium family limit is the closure itself");
        assertEquals(1.0, check(result, "LOCAL_COMPONENT_BALANCE").limit(), 0.0);
        assertEquals(1.0, check(result, "ENERGY_BALANCE").limit(), 0.0);

        String revision = result.formulationRevision();
        assertTrue(revision.endsWith("-closure1e-3"), () -> label + " revision " + revision);
        assertEquals(V3ColumnCalculator.formulationRevision(input, 0.0) + "-closure1e-3", revision);
        assertNotEquals(V3InputDigest.of(result.problem(), V3ColumnCalculator.formulationRevision(input, 0.0),
                        V3PengRobinsonThermo.fromRegisteredPackage(input.packageId()).datasetRevision(),
                        V3ColumnCalculator.assumptionsRevision(input)),
                result.inputDigest(), "a state accepted at a looser closure must not share the default digest");
    }

    /** The whole-column energy closure admits one closure per tray, and no more. */
    @Test
    void theGlobalEnergyClosureLimitIsTheSumOfTheTrayClosures() {
        V3ColumnInput input = evaluationCase("B");
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, boundedControl(), 0.0, LOOSE_CLOSURE));
        V3ColumnResult result = success.result();
        double energyScale = Math.max(1.0,
                result.problem().activeComponentBasis().totalFeedFlowMolPerSecond() * 100_000.0);
        int nodeCount = result.problem().topology().nodeCount();
        V3AcceptanceAudit.Check global = check(result, "GLOBAL_ENERGY_BALANCE");
        assertEquals(nodeCount * LOOSE_CLOSURE * energyScale, global.limit(), 1.0e-6 * global.limit());
        assertTrue(global.passed(), global::toString);

        V3ColumnOutcome.Success tight = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, boundedControl(), 0.0, 0.0));
        V3AcceptanceAudit.Check frozen = check(tight.result(), "GLOBAL_ENERGY_BALANCE");
        assertTrue(frozen.limit() < global.limit(), "the default closure must not widen the boundary closure");
    }

    @Test
    void theClosureLabelIsCanonicalAndOnlyAppearsAboveTheDefault() {
        assertEquals("", V3ColumnCalculator.closureSuffix(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE));
        assertEquals("-closure1e-3", V3ColumnCalculator.closureSuffix(1.0e-3));
        assertEquals("-closure5e-4", V3ColumnCalculator.closureSuffix(5.0e-4));
        assertEquals("-closure1e-6", V3ColumnCalculator.closureSuffix(1.0e-6));
        assertEquals("-closure2.5e-5", V3ColumnCalculator.closureSuffix(2.5e-5));
        assertEquals("-closure1.5e-8", V3ColumnCalculator.closureSuffix(1.5e-8));
    }

    @Test
    void theAdmittedClosureRangeIsClosedAtBothEnds() {
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, V3ColumnCalculator.closureTolerance(0.0), 0.0);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, V3ColumnCalculator.closureTolerance(1.0e-9), 0.0);
        assertEquals(1.0e-3, V3ColumnCalculator.closureTolerance(1.0e-3), 0.0);
        for (double invalid : new double[] {-1.0e-9, 1.0000001e-3, 1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> V3ColumnCalculator.closureTolerance(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> V3ColumnCalculator.calculate(binaryInput(), V3SolveControl.UNBOUNDED, 0.0, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> V3ConvergenceEvidence.requireClosure(1.0e-9));
        assertThrows(IllegalArgumentException.class, () -> V3ConvergenceEvidence.requireClosure(1.0e-2));
        assertEquals(1.0e-8, V3ConvergenceEvidence.closureOf(1.0e-9), 0.0);
        assertEquals(1.0e-3, V3ConvergenceEvidence.closureOf(1.0e-2), 0.0);
    }

    /** The final-step gate moves with the closure and nothing else does. */
    @ParameterizedTest
    @ValueSource(doubles = {1.0e-8, 1.0e-6, 1.0e-3})
    void theFinalStepGateMovesWithTheClosureAndTheOtherTwoDoNot(double closure) {
        V3ConvergenceEvidence atClosure = new V3ConvergenceEvidence(true, 1.0e-13, 1.0e-7, 0.0, 0.0, closure);
        assertEquals(closure >= 1.0e-7, atClosure.satisfiesGates());
        assertEquals(closure >= 1.0e-7, atClosure.satisfiesGates(closure));
        assertFalse(atClosure.satisfiesGates(1.0e-8));
        assertTrue(atClosure.satisfiesGates(1.0e-3));

        assertFalse(new V3ConvergenceEvidence(true, 1.0e-11, 0.0, 0.0, 0.0, closure).satisfiesGates(),
                "the linear backward-error gate is a linear-solve quality and never moves");
        assertFalse(new V3ConvergenceEvidence(true, 0.0, 0.0, 0.0, 1.5, closure).satisfiesGates(),
                "the temperature step-ratio gate never moves");
        assertFalse(V3ConvergenceEvidence.unavailable(closure).satisfiesGates());
        assertEquals(closure, V3ConvergenceEvidence.unavailable(closure).closureTolerance(), 0.0);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE,
                new V3ConvergenceEvidence(true, 0.0, 0.0, 0.0, 0.0).closureTolerance(), 0.0,
                "the five-component form is the frozen default");
    }

    private static V3AcceptanceAudit.Check check(V3ColumnResult result, String family) {
        return result.acceptanceAudit().checks().stream().filter(c -> c.family().equals(family)).findFirst()
                .orElseThrow(() -> new AssertionError("audit has no " + family + " check"));
    }

    private static String outcomeKind(V3ColumnOutcome outcome) {
        return outcome instanceof V3ColumnOutcome.Success ? "SUCCESS"
                : ((V3ColumnOutcome.Failure) outcome).code().toString();
    }

    private static V3SolveControl boundedControl() {
        long started = System.nanoTime();
        return () -> {
            if (System.nanoTime() - started > 60_000_000_000L) {
                throw new AssertionError("closure case exceeded 60 seconds");
            }
        };
    }

    private static V3ColumnInput evaluationCase(String label) {
        List<V3SideDrawSpec> draws = List.of(draw(13, 14_000.0), draw(17, 20_000.0), draw(22, 5_000.0));
        List<V3PumparoundSpec> three = List.of(
                new V3PumparoundSpec(6, 9, -5.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(13, 16, -6.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(20, 23, -4.0e6, V3PumparoundSpec.Split.UNIFORM));
        return switch (label) {
            case "A" -> input(CDU, List.of(), List.of(), false);
            case "B" -> input(CDU,
                    List.of(new V3PumparoundSpec(4, 8, -3.0e6, V3PumparoundSpec.Split.UNIFORM)), draws, false);
            case "C" -> input(TJL, three, draws, true);
            case "D" -> input(CDU,
                    List.of(new V3PumparoundSpec(8, 12, -40.0e6, V3PumparoundSpec.Split.RETURN_TRAY)), List.of(), false);
            case "E" -> input(CDU, List.of(), draws, false);
            default -> throw new IllegalArgumentException("unknown evaluation case " + label);
        };
    }

    private static V3SideDrawSpec draw(int tray, double barrelsPerDay) {
        double kmolPerHour = Math.round(2_610.7 * barrelsPerDay / 99_000.0 * 0.25 * 100.0) / 100.0;
        return new V3SideDrawSpec(tray, kmolPerHour / 3.6);
    }

    private static V3ColumnInput input(
            String packageId, List<V3PumparoundSpec> pumparounds, List<V3SideDrawSpec> draws, boolean wet) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double total = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= total;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 250_000.0, 750.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)),
                draws,
                wet ? List.of(new V3SteamFeedSpec(31, STEAM_MOL_PER_SECOND, 533.15)) : List.of(),
                pumparounds);
    }

    /** Cheap admission-only fixture; the range checks reject before any property call. */
    private static V3ColumnInput binaryInput() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(CDU);
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[6] = 50.0;
        feed[13] = 50.0;
        return new V3ColumnInput(1, thermo.packageId(), "test:closure-range", thermo.componentBasis(), feed,
                550.0, 2, 1, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                new V3ColumnSpecification.ReboilerDuty(0.0)));
    }
}
