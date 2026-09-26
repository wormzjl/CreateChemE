package com.wormzjl.createcheme.science.fluid;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exclusive-use latch for solver workspaces that outlive a single job. Every workspace that caches
 * numeric state (step solver caches, TR-BDF2 endpoint rates, Newton workspaces, sparse
 * factorizations) holds one, and checks it wherever it used to compare against its creating thread.
 *
 * <p>A workspace built for one job keeps the old contract through {@link #confinedToCurrentThread()}:
 * the creating thread holds the latch forever, so any other thread is refused. A workspace retained
 * across jobs uses {@link #released()} and is claimed by each job with {@link #acquire()} and handed
 * back in a {@code finally} with {@link #release()}; a second concurrent claim fails fast rather
 * than corrupting the cached factorization.
 */
public final class SolverOwnership {
    private final AtomicReference<Thread> holder=new AtomicReference<>();
    private SolverOwnership(Thread initial){holder.set(initial);}

    /** Permanently owned by the calling thread: the per-job confinement the solver had before. */
    public static SolverOwnership confinedToCurrentThread(){return new SolverOwnership(Thread.currentThread());}
    /** Unheld until a job acquires it; for workspaces retained across jobs. */
    public static SolverOwnership released(){return new SolverOwnership(null);}

    public void acquire() {
        var current=Thread.currentThread();
        if(!holder.compareAndSet(null,current))
            throw new IllegalStateException("Retained fluid solver is already held by "+holder.get());
    }
    /** No-op when already free; a foreign holder is a programming error, never a solver failure. */
    public void release() {
        var current=Thread.currentThread();var owner=holder.get();
        if(owner==null)return;
        if(owner!=current)throw new IllegalStateException("Retained fluid solver is held by "+owner);
        holder.set(null);
    }
    public void check(String what) {
        if(holder.get()!=Thread.currentThread())throw new IllegalStateException(Objects.requireNonNull(what));
    }
}
