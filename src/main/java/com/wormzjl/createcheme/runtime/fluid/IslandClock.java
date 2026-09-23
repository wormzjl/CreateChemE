package com.wormzjl.createcheme.runtime.fluid;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Server-thread-owned simulation clock. Persisted ticks accrue only while the server is running.
 *
 * <p>Online time is not counted per clock. It is derived from a shared epoch supplied at construction, the
 * world's online tick, as {@code onlineTick = epoch - base}; advancing the epoch advances every clock on it
 * at once, so no island has to be visited on a tick where nothing is due. Restoring a snapshot fixes the base
 * so the restored online tick, and with it the saved debt (online minus committed), is exactly the saved one;
 * the epoch itself only advances while the server runs, so there is no offline progress. A clock built
 * without an epoch reads a constant one and moves only through {@link #accrueOnlineTicks(long)}.
 */
public final class IslandClock {
    private static final LongSupplier DETACHED=()->0;
    private final Thread owner=Thread.currentThread();
    private final LongSupplier epoch;
    private long base,committedTick,retryAtTick;
    private int cadenceTicks;
    private Slice outstanding;
    private int highLoadSamples,lowLoadSamples;
    public record Snapshot(long onlineTick,long committedTick,long retryAtTick,int cadenceTicks) {
        public Snapshot {
            if(committedTick<0||onlineTick<committedTick||retryAtTick<0||cadenceTicks<20||cadenceTicks>400)throw new IllegalArgumentException("Invalid island clock snapshot");
        }
    }
    public record Slice(long startTick,long endTick,long requestId) {
        public Slice {if(startTick<0||endTick<=startTick||requestId<=0)throw new IllegalArgumentException("Invalid simulation slice");}
        public double seconds(){return (endTick-startTick)/20.0;}
    }
    public IslandClock(Snapshot saved){this(saved,DETACHED);}
    /** Restores the saved clock on a shared epoch: its online tick reads exactly the saved one now. */
    public IslandClock(Snapshot saved,LongSupplier epoch) {
        this.epoch=Objects.requireNonNull(epoch);base=Math.subtractExact(epoch.getAsLong(),saved.onlineTick);
        committedTick=saved.committedTick;retryAtTick=saved.retryAtTick;cadenceTicks=saved.cadenceTicks;
    }
    public static IslandClock fresh(){return new IslandClock(new Snapshot(0,0,0,100));}
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island clock must stay on its coordinator thread");}
    private long online(){return Math.subtractExact(epoch.getAsLong(),base);}
    /** Moves this clock's online time forward relative to its epoch. Not on the tick path: a clock on a shared
     * epoch advances with the epoch. Kept for clocks driven directly, such as a detached test clock. */
    public void accrueOnlineTicks(long elapsed){owned();if(elapsed<0)throw new IllegalArgumentException("Negative elapsed ticks");base=Math.subtractExact(base,elapsed);}
    public long debtTicks(){owned();return online()-committedTick;}
    public long committedTick(){owned();return committedTick;}
    public long retryAtTick(){owned();return retryAtTick;}
    public boolean busy(){owned();return outstanding!=null;}
    public Snapshot snapshot(){owned();return new Snapshot(online(),committedTick,retryAtTick,cadenceTicks);}
    /** The epoch tick at which this clock's online time reads {@code onlineTick}. */
    public long epochTickAt(long onlineTick){owned();return Math.addExact(onlineTick,base);}

    /** A known event/fence can split a cadence; a fence at the current time blocks future reads. */
    public Optional<Slice> nextSlice(long requestId,long causalFenceTick) {
        return nextSlice(requestId,causalFenceTick,Integer.MAX_VALUE);
    }
    /** A failed wall-budget attempt may be retried as a shorter whole interval. This is
     * separate from configured cadence and never commits a partial result from that attempt. */
    public Optional<Slice> nextSlice(long requestId,long causalFenceTick,int maximumSliceTicks) {
        if(maximumSliceTicks<1)throw new IllegalArgumentException("Positive retry span required");
        owned();if(causalFenceTick<committedTick)throw new IllegalArgumentException("Causal fence precedes committed state");
        long onlineTick=online();
        if(outstanding!=null||onlineTick<retryAtTick||onlineTick==committedTick||causalFenceTick==committedTick)return Optional.empty();
        long due=Math.min(Math.addExact(committedTick,Math.min(cadenceTicks,maximumSliceTicks)),causalFenceTick);
        if(onlineTick<due)return Optional.empty();
        return Optional.of(new Slice(committedTick,due,requestId));
    }
    /**
     * The online tick from which {@link #nextSlice} is present through the passage of online time alone, with
     * this fence and retry span; {@link Long#MAX_VALUE} while only a completion or an input change can make it
     * present (an outstanding slice, or a fence at the committed tick). The slice is present at a tick if and
     * only if that tick is at least this value, so a scheduler can wake the clock exactly then.
     */
    public long readyAtTick(long causalFenceTick,int maximumSliceTicks) {
        if(maximumSliceTicks<1)throw new IllegalArgumentException("Positive retry span required");
        owned();if(causalFenceTick<committedTick)throw new IllegalArgumentException("Causal fence precedes committed state");
        if(outstanding!=null||causalFenceTick==committedTick)return Long.MAX_VALUE;
        long due=Math.min(Math.addExact(committedTick,Math.min(cadenceTicks,maximumSliceTicks)),causalFenceTick);
        return Math.max(due,retryAtTick);
    }
    /** Call only after the shared executor accepts this exact slice. A queue rejection leaves it ready. */
    public void admitted(Slice slice){owned();if(outstanding!=null||slice.startTick!=committedTick||slice.endTick>online())throw new IllegalStateException("Invalid clock admission");outstanding=slice;}
    /** Full and qualified approximate results advance once; a held result retains all of its debt. */
    public void completed(Slice slice,boolean accepted) {
        owned();if(!slice.equals(outstanding))throw new IllegalStateException("Stale/duplicate clock completion");outstanding=null;
        if(accepted){committedTick=slice.endTick;retryAtTick=0;}
        else retryAtTick=Math.addExact(online(),cadenceTicks);
    }
    /** A configuration/event change may justify retrying a held owner before its ordinary backoff expires. */
    public void inputsChanged(){owned();retryAtTick=0;}
    /** CPU gating is supplied by measurements; elapsed wall time alone is not evidence of CPU saturation. */
    public void performanceSample(boolean cpuGated,boolean loadLow) {
        owned();
        if(cpuGated){highLoadSamples++;lowLoadSamples=0;if(highLoadSamples>=3){cadenceTicks=Math.min(400,(int)Math.ceil(cadenceTicks*1.25));highLoadSamples=0;}}
        else if(loadLow){lowLoadSamples++;highLoadSamples=0;if(lowLoadSamples>=10){cadenceTicks=Math.max(20,(int)Math.floor(cadenceTicks*.9));lowLoadSamples=0;}}
        else{highLoadSamples=0;lowLoadSamples=0;}
    }
}
