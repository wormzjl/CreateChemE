package com.wormzjl.createcheme.science.fluid.diagnostics;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in instrumentation of the fluid solver. {@link #ENABLED} defaults to {@code false}; every
 * recording site then performs one volatile boolean read, allocates nothing and reads no clock, so
 * production numerics, tolerances and physics gates are unchanged whether or not it is enabled.
 *
 * <p>The counters are process-wide and additive, and each is a {@link LongAdder}, so any number of
 * workers may record into them at once: the facility is correct under the production solver pool,
 * not only under a single-threaded replay. The nesting markers ({@link #inJacobian()},
 * {@link #inReconstruct()}), which decide whether a factorization, solve or ordering is charged to
 * the Newton side or the conservative-transport side, are <b>per thread</b>, so twelve workers
 * cannot interleave each other's attribution. The timers are per-thread {@code nanoTime} deltas
 * summed into the same adders: under a pool they measure occupied thread time, so their sum over
 * threads exceeds the wall duration of the window by design.
 *
 * <p>Enabling and disabling is a plain volatile write, so a job already running when the switch
 * flips contributes only the part of itself that follows the flip, and one such job can charge its
 * transport linear algebra to the Newton buckets because its marker was never set. Over a window
 * of thousands of jobs that edge is negligible; it is why the server benchmark resets and enables
 * at the start of its measurement window rather than at warm-up.
 */
public final class SolverDiagnostics {
    private SolverDiagnostics() {}

    /** Master switch. Off in production; only the diagnostic harness turns it on. */
    public static volatile boolean ENABLED=false;

    // ---- interval and step control ----
    public static final LongAdder implicitSolves=new LongAdder();
    public static final LongAdder activeSetPasses=new LongAdder();
    /** TR-BDF2 companion defects answered by one linear filter, and by a complete nonlinear stage. */
    public static final LongAdder companionFilters=new LongAdder();
    public static final LongAdder companionSolves=new LongAdder();
    /** Filtered defects already inside the stage solve's own Newton tolerance, whose estimate is zero. */
    public static final LongAdder companionDefectsBelowTolerance=new LongAdder();
    /** Linear filters whose point failed to solve the perturbed equations, handed to the nonlinear stage. */
    public static final LongAdder companionFiltersRefused=new LongAdder();
    // ---- Newton ----
    public static final LongAdder newtonSolves=new LongAdder();
    public static final LongAdder newtonIterations=new LongAdder();
    public static final LongAdder newtonBacktracks=new LongAdder();
    public static final LongAdder residualEvaluations=new LongAdder();
    public static final LongAdder residualEvaluationsInJacobian=new LongAdder();
    public static final LongAdder jacobianBuilds=new LongAdder();
    public static final LongAdder jacobianColors=new LongAdder();
    public static final LongAdder jacobianNonzeros=new LongAdder();
    /**
     * Where a Newton iteration's search direction came from, so the cost of the modified-Newton
     * chord can be told from the cost of a fresh Jacobian. {@code newtonSolvesPreconditioned}
     * counts the solves that opened on an inherited factorization instead of building one, and
     * {@code newtonIterationsPreconditioned} the iterations those solves spent; the fresh/stale
     * pair splits every iteration by whether its own direction came from a Jacobian built in that
     * iteration, and the step-evaluation pair splits the line-search residuals the same way.
     */
    /** Jacobian builds assembled from per-node block perturbations, the columns they swept, and the
     * builds that had to fall back to the coloured whole-island sweep. */
    public static final LongAdder jacobianBlockBuilds=new LongAdder();
    public static final LongAdder jacobianBlockColumns=new LongAdder();
    public static final LongAdder jacobianBlockFallbacks=new LongAdder();
    public static final LongAdder newtonSolvesPreconditioned=new LongAdder();
    public static final LongAdder newtonIterationsPreconditioned=new LongAdder();
    public static final LongAdder newtonIterationsFresh=new LongAdder();
    public static final LongAdder newtonIterationsStale=new LongAdder();
    public static final LongAdder newtonStepEvaluationsFresh=new LongAdder();
    public static final LongAdder newtonStepEvaluationsStale=new LongAdder();
    /** Triangular solves spent on the affine-invariant merit probe, which is compared and discarded. */
    public static final LongAdder newtonMeritProbes=new LongAdder();
    /** Why the modified-Newton chord was given up: poor contraction, age, or a failed step. */
    public static final LongAdder newtonRefreshesStalled=new LongAdder();
    public static final LongAdder newtonRefreshesAged=new LongAdder();
    public static final LongAdder newtonRefreshesFailed=new LongAdder();
    // ---- sparse linear algebra, Newton side ----
    public static final LongAdder luFactorizations=new LongAdder();
    public static final LongAdder luFactorNanos=new LongAdder();
    public static final LongAdder luSolves=new LongAdder();
    public static final LongAdder luSolveNanos=new LongAdder();
    public static final LongAdder luChecks=new LongAdder();
    public static final LongAdder luRefinements=new LongAdder();
    public static final LongAdder luOrderings=new LongAdder();
    public static final LongAdder luOrderingNanos=new LongAdder();
    /** Reusable factorization workspaces allocated; every further factorization refills one. */
    public static final LongAdder luStorages=new LongAdder();
    /** Newton workspaces that found their preconditioner superseded by a sibling's refactorization. */
    public static final LongAdder luSupersededFactorizations=new LongAdder();
    // ---- sparse linear algebra, conservative transport side ----
    public static final LongAdder transportFactorizations=new LongAdder();
    public static final LongAdder transportFactorNanos=new LongAdder();
    public static final LongAdder transportSolves=new LongAdder();
    public static final LongAdder transportSolveNanos=new LongAdder();
    public static final LongAdder transportOrderings=new LongAdder();
    public static final LongAdder transportOrderingNanos=new LongAdder();
    // ---- residuals outside the Newton loop ----
    /**
     * Residual evaluations that do not go through {@link com.wormzjl.createcheme.science.fluid.solver.SparseNewton}
     * and are therefore absent from {@link #residualEvaluations}: the equation gate the conservative
     * reconstruction has to pass, and the check that the linearized companion point actually solves
     * the perturbed equations.
     */
    public static final LongAdder verificationResiduals=new LongAdder();
    public static final LongAdder companionFilterResiduals=new LongAdder();
    // ---- properties ----
    public static final LongAdder stateCalls=new LongAdder();
    /** The subset of {@link #stateCalls} performed while building a Jacobian: the node decodes a
     * block-structured or analytic Jacobian is trying to remove. */
    public static final LongAdder stateCallsInJacobian=new LongAdder();
    /** Peng-Robinson temperature preparations. The name is kept from the {@code TemperatureTerms} this
     * counted before WP6a replaced those three n x n matrices with the shared kernel's 3n vectors, so the
     * measurements across work packages stay comparable: it counts the same event either way. */
    public static final LongAdder temperatureTermsCalls=new LongAdder();
    public static final LongAdder flashCalls=new LongAdder();
    // ---- reconstruction ----
    public static final LongAdder reconstructCalls=new LongAdder();
    public static final LongAdder reconstructNanos=new LongAdder();

    // ---- phase-specific trace truncation ----
    /**
     * Unknowns the frozen per-component phase support removed from an implicit solve, summed over the
     * nodes of every active-set pass: one per component the seed put below the trace cutoff in one of
     * the two hydrocarbon phases. Each also removes one equilibrium row, so the system stays square.
     * {@code truncatedNodePasses} counts the node blocks that lost at least one, which is what the
     * per-node average is taken over, and {@code traceReactivations} counts the components the
     * converged fugacity coefficients put back into both phases.
     */
    public static final LongAdder traceOmittedUnknowns=new LongAdder();
    public static final LongAdder truncatedNodePasses=new LongAdder();
    public static final LongAdder traceReactivations=new LongAdder();
    // ---- solid phase ----
    /**
     * Trial points whose decoded solid moments had to be projected onto the nonnegative orthant,
     * and the subset of those projections that were active at an <em>accepted</em> solve point.
     * The first is expected to be nonzero - it is the line search exploring past a moment boundary,
     * which is what the projection exists to permit. The second must stay at zero: an accepted
     * point with a negative solid unknown would mean owned inventory was being clipped rather than
     * solved for, and the conservation audits would be reading a projected state.
     */
    public static final LongAdder solidMomentProjections=new LongAdder();
    public static final LongAdder solidMomentProjectionsAtAcceptedPoints=new LongAdder();
    /** Substeps rejected because a stage guard saw a solid transport transition inside the step. */
    public static final LongAdder solidTransitionRejections=new LongAdder();
    // ---- workspace reuse ----
    /** Newton workspaces served from the retained map, and those that had to be built or forked.
     * A cache key that accidentally varies per stage shows up here as zero reuse. */
    public static final LongAdder workspaceReuses=new LongAdder();
    public static final LongAdder workspaceBuilds=new LongAdder();
    /** TR-BDF2 steps that found the previous step's endpoint rate already computed, and those that
     * had to solve the algebraic port graph for it. A key that varies per step shows up here as
     * zero reuse and one extra implicit solve per step. */
    public static final LongAdder endpointRateReuses=new LongAdder();
    public static final LongAdder endpointRateBuilds=new LongAdder();
    // ---- adaptive step control ----
    /** Every interval attempt, and the accepted subset; exact even when the attempt log is full. */
    public static final LongAdder stepAttempts=new LongAdder();
    public static final LongAdder stepAttemptsAccepted=new LongAdder();

    /**
     * Per-thread nesting markers. Only ever touched inside an {@link #ENABLED} guard, so a
     * production run neither allocates the holder nor pays the thread-local lookup.
     */
    private static final class Markers {boolean jacobian,reconstruct;}
    private static final ThreadLocal<Markers> MARKERS=ThreadLocal.withInitial(Markers::new);

    public static boolean inJacobian(){return MARKERS.get().jacobian;}
    public static boolean inReconstruct(){return MARKERS.get().reconstruct;}
    /** Marks this thread as building a Jacobian and returns the previous value for the {@code finally}. */
    public static boolean enterJacobian(){var m=MARKERS.get();boolean previous=m.jacobian;m.jacobian=true;return previous;}
    public static void leaveJacobian(boolean previous){MARKERS.get().jacobian=previous;}
    /** Marks this thread as inside a conservative reconstruction; same contract as {@link #enterJacobian}. */
    public static boolean enterReconstruct(){var m=MARKERS.get();boolean previous=m.reconstruct;m.reconstruct=true;return previous;}
    public static void leaveReconstruct(boolean previous){MARKERS.get().reconstruct=previous;}

    /** One adaptive interval attempt: its step, whether it was accepted, and which term dominated. */
    public record Attempt(int index,double step,boolean accepted,String dominant,double error) {}
    /**
     * The log is bounded so a two-minute pool run cannot grow it without limit; the two attempt
     * counters above stay exact past the bound, and a replay fixture never approaches it.
     */
    public static final int MAXIMUM_ATTEMPTS=200_000;
    private static final List<Attempt> ATTEMPTS=Collections.synchronizedList(new ArrayList<>());

    public static long begin(){return ENABLED?System.nanoTime():0;}
    public static void end(LongAdder target,long started){if(ENABLED)target.add(System.nanoTime()-started);}
    public static void count(LongAdder target){if(ENABLED)target.increment();}
    public static void count(LongAdder target,long amount){if(ENABLED)target.add(amount);}
    public static void attempt(int index,double step,boolean accepted,String dominant,double error) {
        if(!ENABLED)return;
        stepAttempts.increment();if(accepted)stepAttemptsAccepted.increment();
        synchronized(ATTEMPTS){if(ATTEMPTS.size()<MAXIMUM_ATTEMPTS)ATTEMPTS.add(new Attempt(index,step,accepted,dominant,error));}
    }
    public static List<Attempt> attempts(){synchronized(ATTEMPTS){return List.copyOf(ATTEMPTS);}}

    private static final Map<String,LongAdder> COUNTERS=counters();
    private static Map<String,LongAdder> counters() {
        var map=new LinkedHashMap<String,LongAdder>();
        map.put("implicitSolves",implicitSolves);map.put("activeSetPasses",activeSetPasses);
        map.put("companionFilters",companionFilters);map.put("companionSolves",companionSolves);
        map.put("companionDefectsBelowTolerance",companionDefectsBelowTolerance);
        map.put("companionFiltersRefused",companionFiltersRefused);
        map.put("newtonSolves",newtonSolves);map.put("newtonIterations",newtonIterations);map.put("newtonBacktracks",newtonBacktracks);
        map.put("residualEvaluations",residualEvaluations);map.put("residualEvaluationsInJacobian",residualEvaluationsInJacobian);
        map.put("jacobianBuilds",jacobianBuilds);map.put("jacobianColors",jacobianColors);map.put("jacobianNonzeros",jacobianNonzeros);
        map.put("jacobianBlockBuilds",jacobianBlockBuilds);map.put("jacobianBlockColumns",jacobianBlockColumns);
        map.put("jacobianBlockFallbacks",jacobianBlockFallbacks);
        map.put("newtonSolvesPreconditioned",newtonSolvesPreconditioned);
        map.put("newtonIterationsPreconditioned",newtonIterationsPreconditioned);
        map.put("newtonIterationsFresh",newtonIterationsFresh);map.put("newtonIterationsStale",newtonIterationsStale);
        map.put("newtonStepEvaluationsFresh",newtonStepEvaluationsFresh);map.put("newtonStepEvaluationsStale",newtonStepEvaluationsStale);
        map.put("newtonMeritProbes",newtonMeritProbes);
        map.put("newtonRefreshesStalled",newtonRefreshesStalled);map.put("newtonRefreshesAged",newtonRefreshesAged);
        map.put("newtonRefreshesFailed",newtonRefreshesFailed);
        map.put("verificationResiduals",verificationResiduals);map.put("companionFilterResiduals",companionFilterResiduals);
        map.put("luFactorizations",luFactorizations);map.put("luFactorNanos",luFactorNanos);
        map.put("luSolves",luSolves);map.put("luSolveNanos",luSolveNanos);
        map.put("luChecks",luChecks);map.put("luRefinements",luRefinements);
        map.put("luOrderings",luOrderings);map.put("luOrderingNanos",luOrderingNanos);
        map.put("luStorages",luStorages);map.put("luSupersededFactorizations",luSupersededFactorizations);
        map.put("transportFactorizations",transportFactorizations);map.put("transportFactorNanos",transportFactorNanos);
        map.put("transportSolves",transportSolves);map.put("transportSolveNanos",transportSolveNanos);
        map.put("transportOrderings",transportOrderings);map.put("transportOrderingNanos",transportOrderingNanos);
        map.put("stateCalls",stateCalls);map.put("stateCallsInJacobian",stateCallsInJacobian);
        map.put("temperatureTermsCalls",temperatureTermsCalls);map.put("flashCalls",flashCalls);
        map.put("reconstructCalls",reconstructCalls);map.put("reconstructNanos",reconstructNanos);
        map.put("traceOmittedUnknowns",traceOmittedUnknowns);map.put("truncatedNodePasses",truncatedNodePasses);
        map.put("traceReactivations",traceReactivations);
        map.put("solidMomentProjections",solidMomentProjections);
        map.put("solidMomentProjectionsAtAcceptedPoints",solidMomentProjectionsAtAcceptedPoints);
        map.put("solidTransitionRejections",solidTransitionRejections);
        map.put("workspaceReuses",workspaceReuses);map.put("workspaceBuilds",workspaceBuilds);
        map.put("endpointRateReuses",endpointRateReuses);map.put("endpointRateBuilds",endpointRateBuilds);
        map.put("stepAttempts",stepAttempts);map.put("stepAttemptsAccepted",stepAttemptsAccepted);
        return Collections.unmodifiableMap(map);
    }

    /** Immutable readout of every counter, in declaration order. */
    public record Sample(Map<String,Long> counters,List<Attempt> attempts) {
        public Sample {counters=Map.copyOf(counters);attempts=List.copyOf(attempts);}
        public long value(String name) {
            var found=counters.get(name);
            if(found==null)throw new IllegalArgumentException("Unknown solver diagnostic "+name);
            return found;
        }
    }
    /** Ordered counter names, for tabular reports. */
    public static List<String> names(){return List.copyOf(COUNTERS.keySet());}
    /**
     * Every counter and the retained attempts. Under a pool the adders are read one after another
     * while workers keep recording, so a snapshot taken with {@link #ENABLED} still true is
     * consistent per counter but not across counters; the benchmark clears the switch first.
     */
    public static Sample sample() {
        var values=new LinkedHashMap<String,Long>();
        COUNTERS.forEach((name,adder)->values.put(name,adder.sum()));
        return new Sample(values,attempts());
    }
    /**
     * Clears every counter and the attempt log, and this thread's markers. Worker markers need no
     * clearing: each is set and restored in the same {@code finally} on its own thread.
     */
    public static void reset() {
        COUNTERS.values().forEach(LongAdder::reset);
        synchronized(ATTEMPTS){ATTEMPTS.clear();}
        var markers=MARKERS.get();markers.jacobian=false;markers.reconstruct=false;
    }
}
