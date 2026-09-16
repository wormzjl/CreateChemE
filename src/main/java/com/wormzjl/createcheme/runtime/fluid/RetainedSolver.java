package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.TrBdf2StepSolver;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Objects;

/**
 * One interval solver kept for one island across its jobs, so the sparsity pattern, the colouring,
 * the fill-reducing ordering, the last factorization (the modified-Newton preconditioner) and the
 * TR-BDF2 endpoint rate survive between intervals instead of being rebuilt per job.
 *
 * <p>It also carries the adaptive controller's step estimate between intervals, so a quiet island
 * stops rediscovering its step size from 1 s at every boundary. Like {@code maximumSliceTicks} in
 * the coordinator this is an ephemeral cost hint and is deliberately not persisted: a restart
 * rediscovers it from {@link #COLD_START_SECONDS} within a few substeps, while saved inventory,
 * energy, cadence, debt and fences stay authoritative.
 *
 * <p>This is ephemeral cost state, never material state: dropping it costs one Jacobian build and a
 * few substeps and changes nothing that is committed. The coordinator replaces the handle whenever
 * an island's revision changes (property suspension, topology repartition), and a new island gets a
 * new handle, so a stale model or topology can never reach a retained workspace.
 *
 * <p>Exclusive use is enforced by {@link SolverOwnership}: each call claims the latch and returns it
 * in a {@code finally}, including on cancellation and on the wall/soft deadlines, and a second
 * concurrent job on the same island fails fast instead of corrupting the cached factorization.
 * Workers therefore still touch only the immutable snapshot and this handle.
 */
public final class RetainedSolver {
    /** Step an island starts from with no history: after a revision change, a hold, or a restart.
     * Small enough that a transient does not begin with an oversized attempt, and the controller
     * doubles out of it within a few substeps. */
    public static final double COLD_START_SECONDS=.05;

    private final SolverOwnership ownership=SolverOwnership.released();
    private PassiveIntervalSolver solver;
    private FluidThermodynamics model;
    private double stepHint=COLD_START_SECONDS;

    /** One job's exclusive use of this island's solver. A job may integrate its interval more than
     * once - the module planner searches feasible transfer sizes - and every attempt then shares
     * the retained structure and preconditioner instead of rebuilding them per attempt. */
    public interface Job {
        PassiveIntervalSolver.Result solve(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint);
        /** For an attempt that introduces a boundary change rather than continuing the island's
         * trajectory - a trial source or sink term. It starts from the cold step, because the
         * island's carried estimate describes the undisturbed problem, and it leaves that estimate
         * alone, because a trial that is never committed must not steer the next interval. The
         * retained pattern, colouring, ordering and preconditioner are still shared. */
        PassiveIntervalSolver.Result solveTrial(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint);
        PassiveIntervalSolver.Result solveApproximate(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,
                                                      Runnable checkpoint,TrBdf2StepSolver.StageGuard guard);
    }
    /** Claims the latch for the whole job and returns it in a {@code finally}, including on
     * cancellation and on the wall/soft deadlines. */
    public <T>T run(FluidThermodynamics model,java.util.function.Function<Job,T> job) {
        Objects.requireNonNull(job);var solver=acquire(model);
        try{return job.apply(new Leased(solver));}
        finally{ownership.release();}
    }
    public PassiveIntervalSolver.Result solve(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                              PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
        return run(model,job->job.solve(snapshot,duration,settings,checkpoint));
    }
    public PassiveIntervalSolver.Result solveApproximate(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                                         PassiveIntervalSolver.Settings settings,Runnable checkpoint,TrBdf2StepSolver.StageGuard guard) {
        return run(model,job->job.solveApproximate(snapshot,duration,settings,checkpoint,guard));
    }
    private final class Leased implements Job {
        private final PassiveIntervalSolver solver;
        private Leased(PassiveIntervalSolver solver){this.solver=solver;}
        @Override public PassiveIntervalSolver.Result solve(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
            try {
                var result=solver.solve(snapshot,duration,settings,checkpoint,Math.min(stepHint,duration));
                stepHint=solver.nextStepEstimate();return result;
            }catch(RuntimeException|Error failure){stepHint=COLD_START_SECONDS;throw failure;}
        }
        @Override public PassiveIntervalSolver.Result solveTrial(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
            return solver.solve(snapshot,duration,settings,checkpoint,Math.min(COLD_START_SECONDS,duration));
        }
        @Override public PassiveIntervalSolver.Result solveApproximate(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,
                                                                       Runnable checkpoint,TrBdf2StepSolver.StageGuard guard) {
            // A degraded interval is evidence that this island is struggling: restart the next one small.
            try{return solver.solveApproximate(snapshot,duration,settings,checkpoint,guard);}
            finally{stepHint=COLD_START_SECONDS;}
        }
    }
    private PassiveIntervalSolver acquire(FluidThermodynamics model) {
        Objects.requireNonNull(model);ownership.acquire();
        try {
            if(solver==null||this.model!=model){solver=new PassiveIntervalSolver(model,PassiveIntervalSolver.ErrorControl.EMBEDDED,ownership);this.model=model;}
            return solver;
        }catch(RuntimeException|Error failure){ownership.release();throw failure;}
    }
}
