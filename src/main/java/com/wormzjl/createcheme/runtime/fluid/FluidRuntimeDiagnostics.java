package com.wormzjl.createcheme.runtime.fluid;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in counters of the scheduling work the fluid runtime does on the logical server thread: how often the
 * coordinator examines one island's scheduling state, how often the module host scans its modules, how many
 * topology and island snapshots are built, how many device views and menu packets are produced, how often the
 * readiness pump runs and how many solves it dispatches. They exist to show which of that work happens on
 * ticks where nothing is due.
 *
 * <p>{@link #ENABLED} defaults to {@code false}; every recording site then performs one volatile read and
 * nothing else, so production scheduling is unchanged whether or not it is enabled. The counters are
 * measurement, not simulation state: nothing reads them to decide anything, and they are never persisted.
 * A harness that observes the world through the same APIs brackets its own reads with {@link #pause()} and
 * {@link #resume()}, so its observation overhead is not charged to the engine.
 *
 * <p>Definitions. An <em>island visit</em> is one examination of one island's scheduling state by the
 * coordinator: a re-derivation of its readiness and deadline, or a readiness test in the fair queue (before
 * the deadline scheduler: a clock accrual, an eligibility test, or a fair-queue test). A <em>module
 * scan</em> is one pass of {@link CausalModuleCoordinator#advance()} over every module and pending transfer.
 * A <em>readiness pump</em> is one execution of the coordinator's pump past its re-entrancy guard.
 */
public final class FluidRuntimeDiagnostics {
    private FluidRuntimeDiagnostics() {}

    /** Master switch. Off in production; only a diagnostic harness or test turns it on. */
    public static volatile boolean ENABLED=false;
    private static volatile int paused;

    public static final LongAdder islandVisits=new LongAdder();
    public static final LongAdder moduleScans=new LongAdder();
    public static final LongAdder topologySnapshots=new LongAdder();
    public static final LongAdder islandSnapshots=new LongAdder();
    public static final LongAdder viewBuilds=new LongAdder();
    public static final LongAdder menuPackets=new LongAdder();
    public static final LongAdder readinessPumps=new LongAdder();
    public static final LongAdder solvesDispatched=new LongAdder();
    /** Terminal worker results the coordinator accepted for an owned attempt. */
    public static final LongAdder completionsRouted=new LongAdder();
    /** Island snapshots handed to the publisher by round closure. */
    public static final LongAdder islandsPublished=new LongAdder();
    /** Scheduler deadlines popped while still current and acted on; stale entries are not counted. */
    public static final LongAdder deadlinesFired=new LongAdder();
    /** Completion drains that stopped at the per-tick budget with work left and owed one continuation, and the
     * continuations that ran inside the exhausted tick and therefore deferred to the next tick's drain. */
    public static final LongAdder drainContinuations=new LongAdder();
    public static final LongAdder drainContinuationsDeferred=new LongAdder();
    /** Certificates issued from a qualifying streak, renewed at a horizon, and ended by a wake (drive, horizon, hold). */
    public static final LongAdder certificatesIssued=new LongAdder();
    public static final LongAdder certificatesRenewed=new LongAdder();
    public static final LongAdder certificateWakes=new LongAdder();
    /** Materialisations of certified islands, and the online ticks they advanced by replay (the identity at drift 0 included). */
    public static final LongAdder materialisations=new LongAdder();
    public static final LongAdder replayedTicks=new LongAdder();
    /** Presentation (plan section 3.4): bucket flushes that had a dirty device or an open menu, views handed to
     * loaded block entities, static payloads among {@link #menuPackets}, player inputs queued for a reply, and
     * replies composed at a bucket. */
    public static final LongAdder bucketFlushes=new LongAdder();
    public static final LongAdder devicePresentations=new LongAdder();
    public static final LongAdder staticPayloads=new LongAdder();
    public static final LongAdder queuedInputs=new LongAdder();
    public static final LongAdder inputReplies=new LongAdder();
    /** Persistence (plan section 3.5): saved certificates restored and discarded at load, and island payloads a
     * checkpoint encoded afresh or copied from its cache. */
    public static final LongAdder certificatesRestored=new LongAdder();
    public static final LongAdder certificatesDiscarded=new LongAdder();
    public static final LongAdder payloadsEncoded=new LongAdder();
    public static final LongAdder payloadsReused=new LongAdder();
    /** The hold policy (IslandCoordinator.hold): held attempts the wall or soft budget cut, held attempts whose solve
     * failed, and retries at the one-tick slice deferred past the next cadence. */
    public static final LongAdder budgetHolds=new LongAdder();
    public static final LongAdder numericalHolds=new LongAdder();
    public static final LongAdder retriesDeferred=new LongAdder();
    /** Thermo-domain holds whose fresh-solver retry reproduced the same violation: the island waits for an input change. */
    public static final LongAdder domainHolds=new LongAdder();
    /** Topology events (PhysicalRegistry): batches applied, events they applied, and devices they compiled - the
     * connected components the batches touched, never the rest of the registry. */
    public static final LongAdder topologyBatches=new LongAdder();
    public static final LongAdder topologyEvents=new LongAdder();
    public static final LongAdder compiledDevices=new LongAdder();

    private static final Map<String,LongAdder> COUNTERS=counters();
    private static Map<String,LongAdder> counters() {
        var map=new LinkedHashMap<String,LongAdder>();
        map.put("islandVisits",islandVisits);map.put("moduleScans",moduleScans);
        map.put("topologySnapshots",topologySnapshots);map.put("islandSnapshots",islandSnapshots);
        map.put("viewBuilds",viewBuilds);map.put("menuPackets",menuPackets);
        map.put("readinessPumps",readinessPumps);map.put("solvesDispatched",solvesDispatched);
        map.put("completionsRouted",completionsRouted);map.put("islandsPublished",islandsPublished);
        map.put("deadlinesFired",deadlinesFired);
        map.put("drainContinuations",drainContinuations);map.put("drainContinuationsDeferred",drainContinuationsDeferred);
        map.put("certificatesIssued",certificatesIssued);map.put("certificatesRenewed",certificatesRenewed);map.put("certificateWakes",certificateWakes);
        map.put("materialisations",materialisations);map.put("replayedTicks",replayedTicks);
        map.put("bucketFlushes",bucketFlushes);map.put("devicePresentations",devicePresentations);map.put("staticPayloads",staticPayloads);
        map.put("queuedInputs",queuedInputs);map.put("inputReplies",inputReplies);
        map.put("certificatesRestored",certificatesRestored);map.put("certificatesDiscarded",certificatesDiscarded);
        map.put("payloadsEncoded",payloadsEncoded);map.put("payloadsReused",payloadsReused);
        map.put("budgetHolds",budgetHolds);map.put("numericalHolds",numericalHolds);map.put("retriesDeferred",retriesDeferred);map.put("domainHolds",domainHolds);
        map.put("topologyBatches",topologyBatches);map.put("topologyEvents",topologyEvents);map.put("compiledDevices",compiledDevices);
        return Collections.unmodifiableMap(map);
    }

    public static void count(LongAdder target){if(ENABLED&&paused==0)target.increment();}
    public static void count(LongAdder target,long amount){if(ENABLED&&paused==0&&amount!=0)target.add(amount);}
    /** Excludes the caller's own observation of the world from the counters until {@link #resume()}. */
    public static void pause(){paused++;}
    public static void resume(){if(paused<=0)throw new IllegalStateException("Unbalanced diagnostics pause");paused--;}

    /** Ordered counter names, for tabular reports. */
    public static java.util.List<String> names(){return java.util.List.copyOf(COUNTERS.keySet());}
    /** Every counter, in declaration order. */
    public static Map<String,Long> sample() {
        var values=new LinkedHashMap<String,Long>();COUNTERS.forEach((name,adder)->values.put(name,adder.sum()));
        return Collections.unmodifiableMap(values);
    }
    public static void reset(){COUNTERS.values().forEach(LongAdder::reset);}
}
