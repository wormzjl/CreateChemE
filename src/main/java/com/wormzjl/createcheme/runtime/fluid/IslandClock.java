package com.wormzjl.createcheme.runtime.fluid;

import java.util.Optional;

/** Server-thread-owned simulation clock. Persisted ticks accrue only while the server is running. */
public final class IslandClock {
    private final Thread owner=Thread.currentThread();
    private long onlineTick,committedTick,retryAtTick;
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
    public IslandClock(Snapshot saved) {
        onlineTick=saved.onlineTick;committedTick=saved.committedTick;retryAtTick=saved.retryAtTick;cadenceTicks=saved.cadenceTicks;
    }
    public static IslandClock fresh(){return new IslandClock(new Snapshot(0,0,0,100));}
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island clock must stay on its coordinator thread");}
    public void accrueOnlineTicks(long elapsed){owned();if(elapsed<0)throw new IllegalArgumentException("Negative elapsed ticks");onlineTick=Math.addExact(onlineTick,elapsed);}
    public long debtTicks(){owned();return onlineTick-committedTick;}
    public long committedTick(){owned();return committedTick;}
    public boolean busy(){owned();return outstanding!=null;}
    public Snapshot snapshot(){owned();return new Snapshot(onlineTick,committedTick,retryAtTick,cadenceTicks);}

    /** A known event/fence can split a cadence; a fence at the current time blocks future reads. */
    public Optional<Slice> nextSlice(long requestId,long causalFenceTick) {
        return nextSlice(requestId,causalFenceTick,Integer.MAX_VALUE);
    }
    /** A failed wall-budget attempt may be retried as a shorter whole interval. This is
     * separate from configured cadence and never commits a partial result from that attempt. */
    public Optional<Slice> nextSlice(long requestId,long causalFenceTick,int maximumSliceTicks) {
        if(maximumSliceTicks<1)throw new IllegalArgumentException("Positive retry span required");
        owned();if(causalFenceTick<committedTick)throw new IllegalArgumentException("Causal fence precedes committed state");
        if(outstanding!=null||onlineTick<retryAtTick||onlineTick==committedTick||causalFenceTick==committedTick)return Optional.empty();
        long due=Math.min(Math.addExact(committedTick,Math.min(cadenceTicks,maximumSliceTicks)),causalFenceTick);
        if(onlineTick<due)return Optional.empty();
        return Optional.of(new Slice(committedTick,due,requestId));
    }
    /** Call only after the shared executor accepts this exact slice. A queue rejection leaves it ready. */
    public void admitted(Slice slice){owned();if(outstanding!=null||slice.startTick!=committedTick||slice.endTick>onlineTick)throw new IllegalStateException("Invalid clock admission");outstanding=slice;}
    /** Full and qualified approximate results advance once; a held result retains all of its debt. */
    public void completed(Slice slice,boolean accepted) {
        owned();if(!slice.equals(outstanding))throw new IllegalStateException("Stale/duplicate clock completion");outstanding=null;
        if(accepted){committedTick=slice.endTick;retryAtTick=0;}
        else retryAtTick=Math.addExact(onlineTick,cadenceTicks);
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
