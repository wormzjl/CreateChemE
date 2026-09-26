package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.StageGuard;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.Objects;

/**
 * One interval solver kept for one island across its jobs, so the sparsity pattern, the colouring and the
 * fill-reducing ordering survive between intervals instead of being rebuilt per job.
 *
 * <p>Nothing numeric crosses a job boundary: at the start of every job the solver is reset to what a fresh solver would
 * derive from the island's committed interval ({@link PassiveIntervalSolver#replayStart}; the coordinator hands the
 * committed interval over at dispatch, {@link #committed}), and an ordinary interval starts at
 * {@code min(initialStep, duration)}, a function of the interval alone. A certified island is woken with a new handle,
 * and its replay must reproduce the island that solved every interval bit for bit; a factorization or a step estimate
 * carried from an earlier job made them differ in the last digits (HANDOFF_REVIEW.md 8.9 of the mixed-gas junction
 * batch). The cost is one fresh Jacobian per job, +8 % wall time at 5 s slices.
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
    /** Step a trial starts from: small enough that a transient does not begin with an oversized attempt, and the
     * controller doubles out of it within a few substeps. */
    public static final double COLD_START_SECONDS=.05;

    private final SolverOwnership ownership=SolverOwnership.released();
    private PassiveIntervalSolver solver;
    private FluidThermodynamics model;
    /** The island's committed interval (null for none), handed over at each dispatch, and whether the job in progress
     * has not yet reset the solver from it. */
    private PassiveIntervalSolver.Result committed;
    private boolean replayPending;
    /** Records the island's committed interval (null for none) for the next job's start. Called by the coordinator when
     * it dispatches, while no job holds this handle. */
    public void committed(PassiveIntervalSolver.Result result){this.committed=result;}
    private void begin(PassiveIntervalSolver solver,PassiveNetwork snapshot) {
        if(!replayPending)return;
        replayPending=false;solver.replayStart(snapshot,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
    }

    /** One job's exclusive use of this island's solver. A job may integrate its interval more than
     * once - the module planner searches feasible transfer sizes - and every attempt then shares
     * the retained structure instead of rebuilding it per attempt. */
    public interface Job {
        PassiveIntervalSolver.Result solve(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint);
        /** For an attempt that introduces a boundary change rather than continuing the island's
         * trajectory - a trial source or sink term. It starts from the cold step. The retained
         * pattern, colouring and ordering are still shared. */
        PassiveIntervalSolver.Result solveTrial(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint);
        PassiveIntervalSolver.Result solveApproximate(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,
                                                      Runnable checkpoint,StageGuard guard);
    }
    /** Claims the latch for the whole job and returns it in a {@code finally}, including on
     * cancellation and on the wall/soft deadlines. */
    public <T>T run(FluidThermodynamics model,java.util.function.Function<Job,T> job) {
        Objects.requireNonNull(job);var solver=acquire(model);replayPending=true;
        try{return job.apply(new Leased(solver));}
        finally{ownership.release();}
    }
    public PassiveIntervalSolver.Result solve(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                              PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
        return run(model,job->job.solve(snapshot,duration,settings,checkpoint));
    }
    public PassiveIntervalSolver.Result solveApproximate(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                                         PassiveIntervalSolver.Settings settings,Runnable checkpoint,StageGuard guard) {
        return run(model,job->job.solveApproximate(snapshot,duration,settings,checkpoint,guard));
    }
    private final class Leased implements Job {
        private final PassiveIntervalSolver solver;
        private Leased(PassiveIntervalSolver solver){this.solver=solver;}
        @Override public PassiveIntervalSolver.Result solve(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
            begin(solver,snapshot);
            return solver.solve(snapshot,duration,settings,checkpoint,Math.min(settings.initialStep(),duration));
        }
        @Override public PassiveIntervalSolver.Result solveTrial(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
            begin(solver,snapshot);
            return solver.solve(snapshot,duration,settings,checkpoint,Math.min(COLD_START_SECONDS,duration));
        }
        @Override public PassiveIntervalSolver.Result solveApproximate(PassiveNetwork snapshot,double duration,PassiveIntervalSolver.Settings settings,
                                                                       Runnable checkpoint,StageGuard guard) {
            begin(solver,snapshot);
            return solver.solveApproximate(snapshot,duration,settings,checkpoint,guard);
        }
    }
    private PassiveIntervalSolver acquire(FluidThermodynamics model) {
        Objects.requireNonNull(model);ownership.acquire();
        try {
            if(solver==null||this.model!=model){solver=new PassiveIntervalSolver(model,ownership);this.model=model;}
            return solver;
        }catch(RuntimeException|Error failure){ownership.release();throw failure;}
    }
}
