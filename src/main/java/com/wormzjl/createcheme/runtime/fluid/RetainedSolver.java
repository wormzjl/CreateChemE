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

    public PassiveIntervalSolver.Result solve(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                              PassiveIntervalSolver.Settings settings,Runnable checkpoint) {
        var solver=acquire(model);
        try {
            var result=solver.solve(snapshot,duration,settings,checkpoint,Math.min(stepHint,duration));
            stepHint=solver.nextStepEstimate();return result;
        }
        catch(RuntimeException|Error failure){stepHint=COLD_START_SECONDS;throw failure;}
        finally{ownership.release();}
    }
    public PassiveIntervalSolver.Result solveApproximate(FluidThermodynamics model,PassiveNetwork snapshot,double duration,
                                                         PassiveIntervalSolver.Settings settings,Runnable checkpoint,TrBdf2StepSolver.StageGuard guard) {
        var solver=acquire(model);
        // A degraded interval is evidence that this island is struggling, so the next one restarts small.
        try{return solver.solveApproximate(snapshot,duration,settings,checkpoint,guard);}
        finally{stepHint=COLD_START_SECONDS;ownership.release();}
    }
    private PassiveIntervalSolver acquire(FluidThermodynamics model) {
        Objects.requireNonNull(model);ownership.acquire();
        try {
            if(solver==null||this.model!=model){solver=new PassiveIntervalSolver(model,PassiveIntervalSolver.ErrorControl.EMBEDDED,ownership);this.model=model;}
            return solver;
        }catch(RuntimeException|Error failure){ownership.release();throw failure;}
    }
}
