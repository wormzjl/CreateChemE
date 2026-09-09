package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Qualifies deterministic internal stage continuation without using a persisted or cross-request warm state. */
class V3DwsimStageContinuationTest {
    private static final long TARGET_BUDGET_NANOS = 15_000_000_000L;
    private static final long FIFTEEN_STAGE_BUDGET_NANOS = 30_000_000_000L;
    private static final long THIRTY_STAGE_BUDGET_NANOS = 60_000_000_000L;
    private static final long FORTY_STAGE_BUDGET_NANOS = 60_000_000_000L;

    @Test
    void acceptedFourStageRealCrudeProfileCanSeedAFreshEightStageCorrection() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem fourStage = V3ColumnProblemResolver.resolve(input(crude, 4, 3), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged acceptedFourStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, fourStage,
                        V3ColumnInitializer.initialize(fourStage, thermo, thermo.newWorkspace(),
                                V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state(), V3SolveControl.UNBOUNDED));
        V3FlashResult fourFeedFlash = feedFlash(thermo, fourStage);
        assertTrue(new V3AcceptanceAuditor(fourStage, thermo, fourFeedFlash.molarEnthalpyJoulesPerMol())
                .audit(acceptedFourStage.state(), thermo.newWorkspace()).accepted());

        V3ColumnProblem eightStage = V3ColumnProblemResolver.resolve(input(crude, 8, 6), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState continuationSeed = interpolate(acceptedFourStage.state(), eightStage);
        long started = System.nanoTime();
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, eightStage, continuationSeed, () -> {
                    if (System.nanoTime() - started >= TARGET_BUDGET_NANOS) {
                        throw new AssertionError("eight-stage continuation exceeded its 15-second cold-test budget");
                    }
                }));
        V3FlashResult eightFeedFlash = feedFlash(thermo, eightStage);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(eightStage, thermo,
                eightFeedFlash.molarEnthalpyJoulesPerMol()).audit(converged.state(), thermo.newWorkspace());
        System.out.println("V3 DWSIM continuation 4->8: " + converged + "; audit=" + audit);
        assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(audit.accepted());
    }

    @Test
    void certifiedEightStageRealCrudeProfileCanSeedAFreshFifteenStageCorrection() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem fourStage = V3ColumnProblemResolver.resolve(input(crude, 4, 3), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFourStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, fourStage,
                        V3ColumnInitializer.initialize(fourStage, thermo, thermo.newWorkspace(),
                                V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state(), V3SolveControl.UNBOUNDED));

        V3ColumnProblem eightStage = V3ColumnProblemResolver.resolve(input(crude, 8, 6), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedEightStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, eightStage, interpolate(convergedFourStage.state(), eightStage), TARGET_BUDGET_NANOS));

        V3ColumnProblem fifteenStage = V3ColumnProblemResolver.resolve(input(crude, 15, 12), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFifteenStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, fifteenStage, interpolate(convergedEightStage.state(), fifteenStage),
                        FIFTEEN_STAGE_BUDGET_NANOS));
        V3FlashResult fifteenFeedFlash = feedFlash(thermo, fifteenStage);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fifteenStage, thermo,
                fifteenFeedFlash.molarEnthalpyJoulesPerMol()).audit(convergedFifteenStage.state(), thermo.newWorkspace());
        System.out.println("V3 DWSIM continuation 4->8->15: " + convergedFifteenStage + "; audit=" + audit);
        assertTrue(convergedFifteenStage.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(audit.accepted());
    }

    @Test
    void certifiedFifteenStageRealCrudeProfileCanSeedAFreshThirtyStageCorrection() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem fourStage = V3ColumnProblemResolver.resolve(input(crude, 4, 3), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFourStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, fourStage,
                        V3ColumnInitializer.initialize(fourStage, thermo, thermo.newWorkspace(),
                                V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state(), V3SolveControl.UNBOUNDED));
        V3ColumnProblem eightStage = V3ColumnProblemResolver.resolve(input(crude, 8, 6), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedEightStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, eightStage, interpolate(convergedFourStage.state(), eightStage), TARGET_BUDGET_NANOS));
        V3ColumnProblem fifteenStage = V3ColumnProblemResolver.resolve(input(crude, 15, 12), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFifteenStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, fifteenStage, interpolate(convergedEightStage.state(), fifteenStage),
                        FIFTEEN_STAGE_BUDGET_NANOS));

        V3ColumnProblem thirtyStage = V3ColumnProblemResolver.resolve(input(crude, 30, 24), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedThirtyStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, thirtyStage, interpolate(convergedFifteenStage.state(), thirtyStage),
                        THIRTY_STAGE_BUDGET_NANOS));
        V3FlashResult thirtyFeedFlash = feedFlash(thermo, thirtyStage);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(thirtyStage, thermo,
                thirtyFeedFlash.molarEnthalpyJoulesPerMol()).audit(convergedThirtyStage.state(), thermo.newWorkspace());
        System.out.println("V3 DWSIM continuation 4->8->15->30: " + convergedThirtyStage + "; audit=" + audit);
        assertTrue(convergedThirtyStage.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(audit.accepted());
    }

    @Test
    void theGridScheduleDoublesFromFifteenAndIsUnchangedAtOrBelowThirtyStages() {
        for (int request = V3ColumnInput.MIN_STAGE_COUNT; request <= 30; request++) {
            List<Integer> legacy = new java.util.ArrayList<>();
            for (int seed : new int[] {4, 8, 15}) if (seed < request) legacy.add(seed);
            legacy.add(request);
            assertEquals(legacy, V3ColumnCalculator.dwsimStageCounts(request),
                    () -> "the schedule of a request at or below 30 stages must not move");
        }

        assertEquals(List.of(4, 8, 15, 30, 31), V3ColumnCalculator.dwsimStageCounts(31));
        assertEquals(List.of(4, 8, 15, 30, 40), V3ColumnCalculator.dwsimStageCounts(40));
        assertEquals(List.of(4, 8, 15, 30, 60), V3ColumnCalculator.dwsimStageCounts(60));
        assertEquals(List.of(4, 8, 15, 30, 60, 61), V3ColumnCalculator.dwsimStageCounts(61));
        assertEquals(List.of(4, 8, 15, 30, 60, 64), V3ColumnCalculator.dwsimStageCounts(V3ColumnInput.MAX_STAGE_COUNT));

        for (int request = V3ColumnInput.MIN_STAGE_COUNT; request <= V3ColumnInput.MAX_STAGE_COUNT; request++) {
            List<Integer> schedule = V3ColumnCalculator.dwsimStageCounts(request);
            assertEquals(request, schedule.get(schedule.size() - 1), "the schedule must end on the request");
            for (int index = 1; index < schedule.size(); index++) {
                assertTrue(schedule.get(index) > schedule.get(index - 1), schedule::toString);
                assertTrue(schedule.get(index) <= 2 * schedule.get(index - 1) || schedule.get(index - 1) < 15,
                        () -> "no grid above 15 may more than double its predecessor: " + schedule);
            }
        }
    }

    /**
     * The steam ramp doubles at the resolution the equal schedule used to step by.
     *
     * <p>The first rung is still that smallest increment — the one the rung count was measured for — and every
     * later rung doubles the water already on the trays. On the literature preset's resolution of 24 that turns
     * ten rungs to 41.7 % of the authored steam into four to 62.5 %.</p>
     */
    @Test
    void theSteamRampDoublesFromItsSmallestIncrementAndAlwaysEndsOnTheAuthoredRate() {
        assertEquals(List.of(1.0), V3ColumnCalculator.steamRampFractions(1));
        assertEquals(List.of(0.25, 0.75, 1.0), V3ColumnCalculator.steamRampFractions(4));
        assertEquals(List.of(1.0 / 12.0, 3.0 / 12.0, 7.0 / 12.0, 1.0), V3ColumnCalculator.steamRampFractions(12));
        assertEquals(List.of(1.0 / 24.0, 3.0 / 24.0, 7.0 / 24.0, 15.0 / 24.0, 1.0),
                V3ColumnCalculator.steamRampFractions(24));

        for (int resolution = 1; resolution <= 24; resolution++) {
            List<Double> fractions = V3ColumnCalculator.steamRampFractions(resolution);
            assertEquals(1.0, fractions.get(fractions.size() - 1), 0.0,
                    () -> "the ramp must end on the authored rate exactly: " + fractions);
            assertEquals(1.0 / resolution, fractions.get(0), 0.0,
                    () -> "the first rung stays the measured smallest increment: " + fractions);
            for (int index = 1; index < fractions.size(); index++) {
                assertTrue(fractions.get(index) > fractions.get(index - 1), fractions::toString);
            }
            assertTrue(fractions.size() <= 1 + (int) (Math.log(resolution) / Math.log(2.0)) + 1, fractions::toString);
        }
        assertThrows(IllegalArgumentException.class, () -> V3ColumnCalculator.steamRampFractions(0));
    }

    /** Only an intermediate steam rung runs reduced; the rung that publishes keeps every fallback. */
    @Test
    void onlyAnIntermediateSteamRungGivesUpItsFallbacksAndItsCertificateCascade() {
        V3SimultaneousColumnSolver.RungBudget full = V3SimultaneousColumnSolver.RungBudget.DEFAULT;
        assertEquals(full, V3ColumnCalculator.rampRungBudget(true, true), "the requested steam rung publishes");
        assertEquals(full, V3ColumnCalculator.rampRungBudget(false, false), "a heat or draw rung is never reduced");
        assertEquals(full, V3ColumnCalculator.rampRungBudget(false, true));

        V3SimultaneousColumnSolver.RungBudget reduced = V3ColumnCalculator.rampRungBudget(true, false);
        assertEquals(2, reduced.maximumDampingSteps());
        assertFalse(reduced.gradientFallback());
        assertEquals(0, reduced.verificationDampingSteps(),
                "an intermediate rung produces a seed, not a published certificate");
        assertEquals(12, reduced.stallWindow());
        assertEquals(0.5, reduced.stallFactor(), 0.0);
        assertEquals(1.0e-3, reduced.stallResidualFloor(), 0.0);

        assertEquals(8, full.maximumDampingSteps());
        assertTrue(full.gradientFallback());
        assertEquals(8, full.verificationDampingSteps());
        assertEquals(0, full.stallWindow(), "no published attempt may be stopped by the stall detector");

        for (boolean steamRung : new boolean[] {true, false}) {
            for (boolean requested : new boolean[] {true, false}) {
                assertEquals(full, V3ColumnCalculator.rampRungBudget(steamRung, requested, true),
                        () -> "the resumed sweep exists to spend the fallbacks the first sweep declined, so"
                                + " every rung in it runs on the full budget: steamRung=" + steamRung
                                + " requested=" + requested);
            }
        }
    }

    /**
     * The resumed sweep's halving may only ever queue a fraction strictly inside the interval it splits.
     *
     * <p>That is what bounds it together with the halving count: a midpoint that landed on either endpoint
     * would re-queue a rung the schedule has already solved or already failed, and the loop would not make
     * progress. The narrow interval below is the arithmetic limit — its midpoint rounds onto the lower
     * endpoint — and is rejected rather than accepted as a degenerate rung.</p>
     */
    @Test
    void aResumedSteamRungHalvingOnlyEverQueuesAFractionStrictlyInsideItsInterval() {
        assertEquals(0.375, V3ColumnCalculator.steamRampMidpointFraction(0.25, 0.5), 0.0);
        // The literature preset's own halving: the doubling schedule's 7/24 and 15/24 rungs meet at 11/24,
        // which is the 0.4583333333333334 the recovered condenser +5 K case actually solves.
        assertEquals(11.0 / 24.0, V3ColumnCalculator.steamRampMidpointFraction(7.0 / 24.0, 15.0 / 24.0), 1.0e-15);
        assertEquals(0.5, V3ColumnCalculator.steamRampMidpointFraction(0.0, 1.0), 0.0);

        assertTrue(Double.isNaN(V3ColumnCalculator.steamRampMidpointFraction(0.5, 0.5)),
                "a zero-width interval has no interior rung");
        assertTrue(Double.isNaN(V3ColumnCalculator.steamRampMidpointFraction(0.75, 0.25)),
                "an inverted interval has no interior rung");
        assertTrue(Double.isNaN(V3ColumnCalculator.steamRampMidpointFraction(0.5, Math.nextUp(0.5))),
                "an interval one ulp wide has no interior rung in double arithmetic");

        double accepted = 1.0 / 24.0;
        double failed = 3.0 / 24.0;
        for (int halving = 0; halving < 8; halving++) {
            double upper = failed;
            double midpoint = V3ColumnCalculator.steamRampMidpointFraction(accepted, upper);
            if (Double.isNaN(midpoint)) break;
            assertTrue(midpoint > accepted && midpoint < upper,
                    () -> "halving " + accepted + " -> " + upper + " produced " + midpoint);
            failed = midpoint;
        }
    }

    /**
     * The wet literature preset at half the authored steam: the case the second sweep exists for.
     *
     * <p>Its first sweep stall-stops the last steam rung at a scaled residual of 0.153 and skips ahead, and the
     * requested rung then dies on a zero pivot after 31 iterations. Resuming that rung on the full budget and,
     * when it stops again, halving it once, puts the requested rung in the basin that publishes. The assertions
     * are on the contract rather than on the numbers: the sweep is entered at most once, it is announced in the
     * published events, and every rung it solves is on the {@code /full-budget/} path.</p>
     */
    @Test
    void theWetPresetAtHalfSteamIsRecoveredByOneFullBudgetResumeOfItsSteamSchedule() {
        V3ColumnInput input = wetLiteraturePreset(0.5);

        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= FORTY_STAGE_BUDGET_NANOS) {
                throw new AssertionError("the half-steam wet literature preset exceeded its 60-second cold budget");
            }
        });
        System.out.println("V3 wet literature preset at half steam: " + (System.nanoTime() - started) / 1.0e9 + " s; "
                + outcome.diagnostics().solvePath());
        outcome.diagnostics().events().forEach(event -> System.out.println("  event: " + event));

        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.diagnostics().solvePath().contains("/full-budget/"),
                success.diagnostics()::solvePath);

        long resumes = success.diagnostics().events().stream()
                .filter(event -> event.contains("resuming the steam schedule")).count();
        assertEquals(1, resumes, () -> "the resumed sweep is bought once and only once: "
                + success.diagnostics().events());
        assertTrue(success.diagnostics().events().stream()
                        .anyMatch(event -> event.contains("resuming the steam schedule at")
                                && event.contains("on the full rung budget")),
                () -> "the resume must say where it restarts and on what budget: "
                        + success.diagnostics().events());
    }

    /**
     * A ramp whose requested rung succeeds on the first sweep never buys the second one.
     *
     * <p>This is the speed contract of the resume: the flagship preset's own schedule stall-stops at 0.625 and
     * skips ahead to an accepted answer, so it must reach that answer without a single full-budget rung.
     * Recovering the perturbations eagerly instead — retrying every stopped rung as soon as it stops — was
     * measured at 1.74 s to 3.02 s on this case for the same input digest, which is what this asserts against.</p>
     */
    @Test
    void theWetLiteraturePresetItselfNeverEntersTheFullBudgetSweep() {
        V3ColumnInput input = wetLiteraturePreset(1.0);

        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= FORTY_STAGE_BUDGET_NANOS) {
                throw new AssertionError("the wet literature preset exceeded its 60-second cold budget");
            }
        });
        System.out.println("V3 wet literature preset: " + (System.nanoTime() - started) / 1.0e9 + " s; "
                + outcome.diagnostics().solvePath());

        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertFalse(success.diagnostics().solvePath().contains("full-budget"), success.diagnostics()::solvePath);
        assertTrue(success.diagnostics().events().stream()
                        .noneMatch(event -> event.contains("resuming the steam schedule")),
                () -> "a ramp that publishes on its first sweep must not pay for a second: "
                        + success.diagnostics().events());
    }

    /** The 40-tray Ledezma-Martinez column with its three draws, sump steam and three pumparounds. */
    private static V3ColumnInput wetLiteraturePreset(double steamScale) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 737.6996333000835;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 40, 37, 250_000.0, 0.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(0.0)),
                List.of(new V3SideDrawSpec(10, 491.0 / 3.6), new V3SideDrawSpec(18, 515.0 / 3.6),
                        new V3SideDrawSpec(28, 165.0 / 3.6)),
                List.of(new V3SteamFeedSpec(41, steamScale * 1_200.0 / 3.6, 533.15)),
                List.of(new V3PumparoundSpec(8, 10, -12.84e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(16, 18, -17.89e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(26, 28, -11.20e6, V3PumparoundSpec.Split.UNIFORM)));
    }

    /** A stopped stall is a nonconvergence, like every other bounded Newton stop. */
    @Test
    void aStoppedStallIsPublishedAsANonconvergence() {
        assertEquals(V3SolverFailureCode.NONCONVERGENCE, V3ColumnCalculator.failureCode("STALLED"));
        assertEquals(V3SolverFailureCode.NONCONVERGENCE, V3ColumnCalculator.failureCode("MAX_ITERATIONS"));
        assertEquals(V3SolverFailureCode.LINEAR_SOLVE_FAILURE, V3ColumnCalculator.failureCode("LINEAR_SINGULAR"));
    }

    /**
     * The plain 40-tray literature column: the geometry of Ledezma-Martinez (2019) without its draws, steam or
     * pumparounds. On the 4-8-15-40 schedule the 15 to 40 jump was rejected by the line search at iteration 0
     * and the request failed after 287 s; the 30-stage grid the doubling rule adds converges it in about 5 s.
     */
    @Test
    void thePlainFortyTrayLiteratureColumnConvergesOnTheDoubledSchedule() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 737.6996333000835;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 40, 37, 250_000.0, 0.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));

        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= FORTY_STAGE_BUDGET_NANOS) {
                throw new AssertionError("the plain 40-tray literature column exceeded its 60-second cold budget");
            }
        });
        System.out.println("V3 plain 40-tray literature column: " + (System.nanoTime() - started) / 1.0e9 + " s; " + outcome);

        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.diagnostics().solvePath().contains("4-8-15-30-40"), success.diagnostics()::solvePath);
    }

    private static V3SimultaneousColumnSolver.Attempt solve(
            V3PengRobinsonThermo thermo, V3ColumnProblem problem, V3DryMeshState seed, V3SolveControl control) {
        V3FlashResult feedFlash = feedFlash(thermo, problem);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        return V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, new V3DryMeshCoordinateMap(problem), seed,
                thermo::newWorkspace, V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS,
                V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE, control);
    }

    private static V3SimultaneousColumnSolver.Attempt solveWithin(
            V3PengRobinsonThermo thermo, V3ColumnProblem problem, V3DryMeshState seed, long budgetNanos) {
        long started = System.nanoTime();
        return solve(thermo, problem, seed, () -> {
            if (System.nanoTime() - started >= budgetNanos) {
                throw new AssertionError("DWSIM stage continuation exceeded its cold-test budget");
            }
        });
    }

    private static V3FlashResult feedFlash(V3PengRobinsonThermo thermo, V3ColumnProblem problem) {
        return thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
    }

    private static V3DryMeshState interpolate(V3DryMeshState source, V3ColumnProblem target) {
        int nodes = target.topology().nodeCount();
        int components = source.componentCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            double position = node * (source.nodeCount() - 1.0) / (nodes - 1.0);
            int lower = (int) Math.floor(position);
            int upper = Math.min(source.nodeCount() - 1, lower + 1);
            double fraction = position - lower;
            temperatures[node] = source.temperatureKelvin(lower)
                    + fraction * (source.temperatureKelvin(upper) - source.temperatureKelvin(lower));
            for (int component = 0; component < components; component++) {
                liquid[node][component] = source.liquidFlow(lower, component)
                        + fraction * (source.liquidFlow(upper, component) - source.liquidFlow(lower, component));
                vapor[node][component] = source.vaporFlow(lower, component)
                        + fraction * (source.vaporFlow(upper, component) - source.vaporFlow(lower, component));
            }
        }
        temperatures[target.topology().condenserNode()] = specification(
                target.input(), V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        V3DryMeshState interpolated = new V3DryMeshState(target.topology(), components, liquid, vapor, temperatures);
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(target.input().packageId());
        V3SequentialPreconditioner.Result prepared = V3BubblePointPreconditioner.INSTANCE.prepare(
                new V3SequentialPreconditioner.Request(target, interpolated, V3SolveControl.UNBOUNDED),
                thermo, thermo.newWorkspace());
        return prepared instanceof V3SequentialPreconditioner.Result.Prepared result ? result.state() : interpolated;
    }

    private static <S extends V3ColumnSpecification> S specification(V3ColumnInput input, Class<S> type) {
        return input.specifications().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static V3ColumnInput input(V3CrudeFeed crude, int stages, int feedStage) {
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 638.15, stages, feedStage, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }
}
