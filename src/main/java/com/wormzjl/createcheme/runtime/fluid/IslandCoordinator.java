package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Server-thread authority over island clocks and atomic result publication. Workers receive only commands.
 * A round contains at most the available dispatch capacity; waiting owners retain duration, not queued snapshots.
 * The dispatcher must deliver terminal callbacks asynchronously on this coordinator's owning thread.
 *
 * <p>Scheduling is driven by deadlines, dependency changes and completions, never by visiting every island on
 * every tick. Island clocks read a shared online epoch. Each island is either <em>ready</em> (its next slice
 * can be dispatched now), holds one deadline in the {@link IslandScheduler} for the epoch tick at which the
 * passage of time alone makes it ready, or waits for an event that re-examines it (a completion, a fence
 * change, a property resume). Every change to what decides readiness re-examines exactly the island it
 * changed. The fair queue holds ready owners only, so dispatch order among ready owners, the per-tick
 * dispatch limit, the capacity rule and the round barrier are those of the polled coordinator this replaces.
 * Round timeouts and the worker allocator's shrink are deadlines too.
 *
 * <p>An island whose solved intervals repeat (an exact identity, or stationary within the policy's tolerance)
 * is <em>certified</em> ({@link IslandCertificate}): it is never ready, releases its solver
 * caches, and holds at most one deadline, at its certificate horizon or its next module drive. Its committed
 * time is not stored per tick but materialised on demand - at a read of its snapshot, an event alignment, a
 * property hold, its own deadline - by the identity or by scaled replay of its last solved interval, never past
 * the online clock, a fence or a hold. A module drive or the horizon wakes it; a property resume or a topology
 * event discards the certificate. A snapshot carries the certificate in its saved form; {@link #register}
 * restores it while its signature holds and otherwise discards it, keeping the materialised inventory.
 *
 * <p>Every snapshot also carries a payload generation: a JVM-wide unique number that changes whenever anything
 * a checkpoint payload records about the island changes (a solve, a dispatch, a fence, a certificate issued or
 * ended, a hold or resume), and never when a certified island is only materialised, so a checkpoint can keep an
 * encoded payload for as long as its island's generation stands.
 */
public final class IslandCoordinator {
    public interface Dispatcher {
        default void demand(int eligibleOwners) {}
        /** Ticks until the shared worker allocator would apply a lower-demand shrink when observed again, or a
         * negative value when none is pending. Answered with an allocator deadline instead of per-tick polling. */
        default long demandShrinkDelay(){return -1;}
        int availableWorkers();
        long nextRequestId();
        boolean submit(Attempt attempt, ProcessSolveServices.FluidIslandCommand command);
        void cancel(long requestId);
    }
    @FunctionalInterface public interface Publisher {void published(List<Snapshot> changed);}
    /** Preparation may refuse a stale transaction. The returned action must only publish prevalidated
     * owner-thread state, without throwing, callbacks, IO, or another solver invocation. */
    @FunctionalInterface public interface CommitHook {
        Optional<Runnable> prepare(Attempt attempt,ProcessSolveServices.FluidIslandSolveResult result);
        CommitHook NO_MATERIAL=(attempt,result)->result.materialTransfers().isPresent()?Optional.empty():Optional.of(()->{});
    }
    /**
     * {@code certificates} is the rest and steady-flow policy. The constructors without one keep the scheduling
     * of an island that solves every interval (certificates off); {@link #defaults()} and the world use the
     * configured policy, certificates on.
     */
    public record Settings(long hardBudgetNanos, long softBudgetNanos, int maximumDispatchesPerTick, boolean adaptive,int initialCadenceTicks,CertificatePolicy certificates) {
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,100);}
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive,int initialCadenceTicks){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,CertificatePolicy.disabled());}
        public Settings {
            if(hardBudgetNanos<=0||softBudgetNanos<=0||softBudgetNanos>=hardBudgetNanos||maximumDispatchesPerTick<1||initialCadenceTicks<20||initialCadenceTicks>400)
                throw new IllegalArgumentException("Invalid coordinator settings");
            Objects.requireNonNull(certificates);
        }
        public static Settings defaults(){return new Settings(2_000_000_000L,1_500_000_000L,64,true,100,CertificatePolicy.defaults());}
        public Settings withCertificates(CertificatePolicy policy){return new Settings(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,initialCadenceTicks,policy);}
    }
    public record Attempt(long islandId,long revision,IslandClock.Slice slice) {
        public Attempt {if(islandId<=0||revision<0)throw new IllegalArgumentException("Invalid attempt identity");Objects.requireNonNull(slice);}
    }
    /** How a publication advanced its island: by a solve, by replaying a STEADY certificate, or by the identity of a REST one. */
    public enum Advance {SOLVED,REPLAYED,RESTED}
    /** Queue debt and publication latency remain separate from the worker's own wall/CPU time. */
    public record Metrics(long sequence,long startTick,long endTick,long dispatchedAtTick,long publishedAtTick,
                          long workerNanos,long workerCpuNanos,long dispatchToPublicationNanos,boolean accepted,Advance advance) {
        public Metrics(long sequence,long startTick,long endTick,long dispatchedAtTick,long publishedAtTick,long workerNanos,long workerCpuNanos,long dispatchToPublicationNanos,boolean accepted) {
            this(sequence,startTick,endTick,dispatchedAtTick,publishedAtTick,workerNanos,workerCpuNanos,dispatchToPublicationNanos,accepted,Advance.SOLVED);
        }
        public Metrics {Objects.requireNonNull(advance);}
    }
    /**
     * A certified island: its kind, the tick it first certified (renewals keep it), the base of the current
     * certificate, the last tick replay may reach, the largest flow it keeps replaying, and the certificate as a
     * checkpoint keeps it. {@code saved} is empty while a property hold freezes the island: resume discards every
     * certificate, so one saved during a hold is not kept either, and the island restarts awake from the held state.
     */
    public record Certified(IslandCertificate.Kind kind,long sinceTick,long baseTick,long horizonTick,double largestFlow,Optional<IslandCertificate.Saved> saved) {
        public Certified {
            Objects.requireNonNull(kind);Objects.requireNonNull(saved);
            if(saved.isPresent()){var s=saved.orElseThrow();if(s.kind()!=kind||s.sinceTick()!=sinceTick||s.baseTick()!=baseTick||s.horizonTick()!=horizonTick)throw new IllegalArgumentException("Saved certificate disagrees with its island");}
        }
        @Override public String toString(){return "Certified["+kind+", since "+sinceTick+", base "+baseTick+", horizon "+horizonTick+", largest flow "+largestFlow+(saved.isPresent()?"":", held")+"]";}
        /** Equal when they say the same: the saved form is compared by its interval's ticks and its signature (which
         * digests the graph), since the graphs and results it carries have no value equality of their own. */
        @Override public boolean equals(Object other) {
            return other instanceof Certified c&&kind==c.kind&&sinceTick==c.sinceTick&&baseTick==c.baseTick&&horizonTick==c.horizonTick
                    &&Double.doubleToLongBits(largestFlow)==Double.doubleToLongBits(c.largestFlow)&&saved.map(Certified::identity).equals(c.saved.map(Certified::identity));
        }
        @Override public int hashCode(){return Objects.hash(kind,sinceTick,baseTick,horizonTick,largestFlow,saved.map(Certified::identity));}
        private static List<Object> identity(IslandCertificate.Saved s){return List.of(s.interval().startTick(),s.interval().endTick(),s.signature());}
    }
    public record Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,
                           FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,
                           Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences,Optional<Certified> certificate,long payloadGeneration) {
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,Optional<PassiveIntervalSolver.Result> lastResult,String status) {
            this(id,revision,graph,clock,allowance,anchor,lastResult,status,Map.of());
        }
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences) {
            this(id,revision,graph,clock,allowance,anchor,lastResult,status,fences,Optional.empty());
        }
        /** A snapshot with no payload generation (0): a checkpoint encodes it every time and never caches it. */
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences,Optional<Certified> certificate) {
            this(id,revision,graph,clock,allowance,anchor,lastResult,status,fences,certificate,0);
        }
        public Snapshot {
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandSnapshots);
            if(id<=0||revision<0||payloadGeneration<0)throw new IllegalArgumentException("Invalid island identity");
            Objects.requireNonNull(graph);Objects.requireNonNull(clock);Objects.requireNonNull(allowance);
            Objects.requireNonNull(anchor);Objects.requireNonNull(lastResult);Objects.requireNonNull(status);Objects.requireNonNull(certificate);
            fences=Map.copyOf(fences);
            if(fences.values().stream().anyMatch(t->t<clock.committedTick()))throw new IllegalArgumentException("Persisted fence precedes state");
            if(certificate.isPresent()&&(certificate.orElseThrow().baseTick()>clock.committedTick()||certificate.orElseThrow().horizonTick()<clock.committedTick()))
                throw new IllegalArgumentException("Certified island "+id+" committed at "+clock.committedTick()+" outside its certificate window ["+certificate.orElseThrow().baseTick()+", "+certificate.orElseThrow().horizonTick()+"]");
            if(allowance.acceptedIntervals()>0&&anchor.isEmpty())throw new IllegalArgumentException("Degraded state needs its episode anchor");
        }
    }
    /** Nominal online tick length; converts the wall-clock round budget into a first deadline. */
    private static final long NOMINAL_TICK_NANOS=50_000_000L;
    /** Test and GameTest runs set this to re-derive every island's readiness and deadline from scratch at each
     * pump and tick and fail on any difference from the incrementally maintained schedule. */
    private static final boolean VERIFY=Boolean.getBoolean("createcheme.fluid.scheduler.verify");
    /** Payload generations are unique in the JVM, so a checkpoint cache can never mistake one island object's
     * payload for another's that happens to share its identity, revision and count of changes. */
    private static final java.util.concurrent.atomic.AtomicLong PAYLOAD_GENERATIONS=new java.util.concurrent.atomic.AtomicLong();
    /** A new payload generation, for a snapshot decoded from a checkpoint: registered, the island keeps it. */
    static long freshPayloadGeneration(){return PAYLOAD_GENERATIONS.incrementAndGet();}
    private static final class Island {
        private final long id;
        private long revision;
        private final FluidThermodynamics model;
        private PassiveNetwork graph;
        private final IslandClock clock;
        private FallbackAllowance allowance;
        private Optional<ApproximationAnchor> anchor;
        private Optional<PassiveIntervalSolver.Result> lastResult;
        private final PipeSpeedWindow pipeSpeed = new PipeSpeedWindow();
        private String status;
        private Metrics metrics;
        private boolean suspended;
        // Ephemeral cost hint, not material state or a renewed fallback allowance. A restart
        // may rediscover the hint; saved inventory, cadence, debt and fences stay authoritative.
        private int maximumSliceTicks=Integer.MAX_VALUE;
        // Holds in a row at the one-tick slice, for the retry wait; see {@link #hold}. Ephemeral like the slice hint.
        private int holdStreak;
        // Set by a hold whose soft budget ran out and whose approximate fallback refused the slice: the retry runs the
        // full solve on the whole budget instead; cleared by the next accepted interval. See {@link #hold}.
        private boolean fullOnly;
        // The thermo-domain violation the last held attempt failed on, awaiting its fresh-solver retry, and whether that
        // retry reproduced it (the island then waits for an input change); cleared by an accepted interval or an input
        // change. With the last warned violation's key and online tick, for the rate limit on the log. Ephemeral.
        private com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation domainFailure;
        private boolean domainParked;
        private String warnedDomainKey;
        private long warnedDomainAt=Long.MIN_VALUE;
        // Ephemeral solver caches for this island's jobs; replaced, never mutated, on a revision
        // bump, so a job still running under the old revision keeps its own handle.
        private RetainedSolver retained=new RetainedSolver();
        private final Map<UUID,Long> fences=new HashMap<>();
        // Scheduling state, never saved: attempts still owning this island (running, or closed but not yet
        // terminally drained), whether it is ready, and its one live deadline with that entry's generation.
        private int pendingEntries;
        private boolean ready;
        private long deadline=Long.MAX_VALUE,generation;
        // Certificate state: the certificate while certified and the tick the island first certified (both saved by a
        // checkpoint), and in memory only the one being revalidated by the slice solved at its horizon, the last
        // qualifying interval with the length of the stationary streak it ends, and the online tick a property hold
        // caps materialisation at (a certificate saved during a hold is not kept).
        private IslandCertificate certificate,revalidating;
        private long certifiedSince=-1,holdTick=Long.MAX_VALUE;
        private IslandCertificate.Summary lastInterval;
        private int streak;
        // Why the last closed interval did not certify the island (null once it did), and what the last comparison
        // of two consecutive solved intervals measured: diagnostics only, never read by a decision.
        private String refusal;
        private Evidence evidence;
        // The certificate's validity signature, made once when it is issued or restored; and the payload generation.
        private IslandCertificate.Signature signature;
        private long payloadGeneration;
        /** An island registered from a snapshot that carries a payload generation (a loaded checkpoint's) keeps it, so
         * the checkpoint store's unit of that snapshot stays in place until something it records changes. */
        private Island(Snapshot saved,FluidThermodynamics model,LongSupplier epoch) {
            id=saved.id;revision=saved.revision;this.model=Objects.requireNonNull(model);graph=saved.graph;
            clock=new IslandClock(saved.clock,epoch);allowance=saved.allowance;anchor=saved.anchor;lastResult=saved.lastResult;status=saved.status;fences.putAll(saved.fences);
            payloadGeneration=saved.payloadGeneration!=0?saved.payloadGeneration:PAYLOAD_GENERATIONS.incrementAndGet();
        }
        private Snapshot snapshot(){return new Snapshot(id,revision,graph,clock.snapshot(),allowance,anchor,lastResult,
                !suspended&&!clock.busy()&&fence()==clock.committedTick()?"WAITING: event alignment":status,fences,
                certificate==null?Optional.empty():Optional.of(new Certified(certificate.kind(),certifiedSince,certificate.baseTick(),certificate.horizonTick(),certificate.largestFlow(),
                        suspended?Optional.empty():Optional.of(new IslandCertificate.Saved(certificate.kind(),certifiedSince,certificate.horizonTick(),certificate.interval(),signature)))),
                payloadGeneration);}
        /** Something a checkpoint payload records changed: the next checkpoint encodes this island afresh. */
        private void touch(){payloadGeneration=PAYLOAD_GENERATIONS.incrementAndGet();}
        private long fence(){return fences.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);}
    }
    private static final class Pending {
        private final Attempt attempt;
        private ProcessSolveServices.FluidIslandSolveResult result;
        private boolean terminal,closed;
        private final long dispatchedAtTick,admittedNanos;
        private Pending(Attempt attempt,long dispatchedAtTick,long admittedNanos){this.attempt=attempt;this.dispatchedAtTick=dispatchedAtTick;this.admittedNanos=admittedNanos;}
    }
    private final Thread owner=Thread.currentThread();
    private final Dispatcher dispatcher;
    private final Publisher publisher;
    private final LongSupplier nanoClock;
    private final Settings settings;
    private final CommitHook commitHook;
    private final LongSupplier epoch;
    private final boolean ownsEpoch;
    private long ownEpoch;
    private final Map<Long,Island> islands=new LinkedHashMap<>();
    private final Map<Long,Pending> pending=new HashMap<>();
    private final FairIslandQueue ready=new FairIslandQueue();
    private final IslandScheduler scheduler=new IslandScheduler();
    /** Event fences by event: which islands hold a fence for it, so an event finds its owners without a scan. */
    private final Map<UUID,Set<Long>> fenceOwners=new HashMap<>();
    /** Owned finite stock by reservoir identity: which island holds each reservoir, so a topology change can refuse
     * constructed stock that another island already holds without a scan of every island. */
    private final Map<Long,Long> stockOwners=new HashMap<>();
    private record Round(long id,List<Pending> entries,long startedNanos) {}
    private final List<Round> rounds=new ArrayList<>();
    private long roundSequence,generations,shrinkTick=Long.MAX_VALUE;
    private final EnumMap<IslandScheduler.Kind,Runnable> externalActions=new EnumMap<>(IslandScheduler.Kind.class);
    private final EnumMap<IslandScheduler.Kind,Long> externalTicks=new EnumMap<>(IslandScheduler.Kind.class);
    private LongConsumer released=island->{};
    /** Module drives: the earliest tick in [from, to] at which the module host needs this island solving
     * (a pending input's due tick, a positive withdrawal), or {@link Long#MAX_VALUE}; no side effects. */
    @FunctionalInterface public interface Drives {
        long earliest(long island,long from,long to);
        Drives NONE=(island,from,to)->Long.MAX_VALUE;
    }
    private Drives drives=Drives.NONE;
    /**
     * Told once per island per thermo-domain hold (the first held attempt of an episode, and again only for a different
     * violation or after {@link #DOMAIN_WARNING_TICKS} online ticks): the world logs it. {@code where} is the node's
     * label, {@code parked} whether the island now waits for an input change.
     */
    @FunctionalInterface public interface DomainHolds {
        void held(long island,com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation,String where,boolean parked);
        DomainHolds NONE=(island,violation,where,parked)->{};
    }
    /** Repeats of the same island and violation are logged at most once per this many online ticks (five minutes). */
    static final long DOMAIN_WARNING_TICKS=6000;
    private DomainHolds domainHolds=DomainHolds.NONE;
    /** How a network node is named in a status line: the world names a device by kind and position. */
    private java.util.function.LongFunction<String> nodeNames=id->"node "+id;
    private Publisher replayed=changed->{};
    private int dispatchedThisTick;
    private boolean stopped,pumping;
    // Reasons a pump would act; each is set where it arises and all are cleared when a pump runs.
    private boolean pumpRequested,completionsSincePump,roundDue,shrinkDue;
    private String suspension;

    /** A coordinator with its own epoch, advanced once per {@link #tick()}. */
    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings) {
        this(dispatcher,publisher,nanoClock,settings,CommitHook.NO_MATERIAL);
    }
    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings,CommitHook commitHook) {
        this(dispatcher,publisher,nanoClock,settings,commitHook,null);
    }
    /** {@code epoch} is the shared online tick (the world's), which its owner advances before calling
     * {@link #tick()}; null gives the coordinator its own epoch, advanced by {@link #tick()}. */
    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings,CommitHook commitHook,LongSupplier epoch) {
        this.dispatcher=Objects.requireNonNull(dispatcher);this.publisher=Objects.requireNonNull(publisher);
        this.nanoClock=Objects.requireNonNull(nanoClock);this.settings=Objects.requireNonNull(settings);this.commitHook=Objects.requireNonNull(commitHook);
        ownsEpoch=epoch==null;this.epoch=ownsEpoch?()->ownEpoch:epoch;
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island coordinator belongs to its server thread");}
    public void register(Snapshot saved,FluidThermodynamics model) {
        owned();if(stopped||islands.containsKey(saved.id))throw new IllegalStateException("Stopped/duplicate island");
        if(saved.graph.reservoirs().stream().anyMatch(n->n.kind()==PassiveNetwork.NodeKind.PORT))throw new IllegalArgumentException("Internal ports cannot own world state");
        var island=new Island(saved,model,epoch);
        var persisted=saved.certificate.flatMap(Certified::saved);
        if(persisted.isPresent())restore(island,persisted.orElseThrow());
        islands.put(saved.id,island);ready.register(saved.id,false);index(island);indexStock(island);reconsider(island);
    }
    /**
     * A saved certificate (plan section 3.5). The snapshot's graph is already its island at the saved committed tick,
     * materialised from the base without a solve. Restored when certificates are on and its signature is the island's
     * current one: the island is certified again with its saved since tick, keeps no solver caches, and holds only its
     * horizon (or module drive) deadline. Otherwise the certificate is discarded and the island starts awake from that
     * inventory, to solve its debt and qualify again.
     */
    private void restore(Island island,IslandCertificate.Saved saved) {
        var restored=IslandCertificate.restore(saved,island.model,settings.certificates());
        if(restored.certificate()!=null) {
            island.certificate=restored.certificate();island.certifiedSince=saved.sinceTick();island.signature=saved.signature();island.retained=null;
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesRestored);
        } else {
            // Awake now, with a new status: its unit records neither the certificate nor that status, so it is written afresh.
            island.status="WAITING: saved "+saved.kind()+" certificate discarded: "+restored.discarded();island.refusal="saved certificate discarded: "+restored.discarded();island.touch();
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesDiscarded);
        }
    }
    /** A certified island is materialised first, so what is read (a view, a save, a module decision) is current. */
    public Snapshot snapshot(long id){owned();var island=require(id);materialise(island);return island.snapshot();}
    public Optional<Metrics> metrics(long id){owned();return Optional.ofNullable(require(id).metrics);}
    /**
     * A presentation read, for a view of a loaded device or an open menu: a certified island is materialised as by
     * {@link #snapshot(long)}, but never onto a wake it has not acted on yet (a module drive or its horizon), so a
     * view can never move an island's schedule. The island's own deadline acts on the wake at the same tick with or
     * without viewers, and the materialised state is the certificate's state at that tick whatever the reads
     * before it: presentation cadence never drives scientific progress.
     */
    public Snapshot presentation(long id){owned();var island=require(id);materialise(island,true);return island.snapshot();}
    /** Reads only transport already accepted/materialised by the engine; never wakes or advances an island. */
    public Map<Long,Double> bulkVolumeRates(long id) {
        owned();var island=require(id);
        return island.pipeSpeed.volumeRates(island.clock.onlineTick());
    }

    /** Every island, certified ones materialised first; see {@link #snapshot(long)}. */
    public List<Snapshot> snapshots(){owned();for(var island:List.copyOf(islands.values()))materialise(island);return islands.values().stream().map(Island::snapshot).toList();}
    /** Every island as it is stored, without materialising a certified one: a diagnostic read that must not
     * itself advance anything. A certified island's committed tick here may lag the tick it would read as. */
    public List<Snapshot> observe(){owned();return islands.values().stream().map(Island::snapshot).toList();}
    /** One island as it is stored, without materialising it: for a caller in the middle of a decision that must
     * not see the island move (the module host between releasing one fence and installing the next). */
    public Snapshot observe(long id){owned();return require(id).snapshot();}
    /** Whether the island keeps solver caches; a certified island has released them. For tests. */
    boolean retainsSolver(long id){owned();return require(id).retained!=null;}
    /** Whether a thermo-domain hold reproduced on its retry and the island now waits for an input change. For tests. */
    boolean waitsForInputs(long id){owned();return require(id).domainParked;}
    /** Why the island's last closed interval did not certify it, or null; diagnostics only, never a decision. */
    public String certificationRefusal(long id){owned();return require(id).refusal;}
    /**
     * What the entry evidence measured at the island's last usable solved interval (ending at {@code endTick}):
     * its comparison with the interval before it, null when there was no consecutive usable one, and the
     * interval's own drift, the d of the horizon. Diagnostics only, never a decision; null when certificates
     * are off or no usable interval has closed since the island last woke or certified.
     */
    public record Evidence(long endTick,IslandCertificate.Stationarity stationarity,double drift) {}
    public Evidence certificationEvidence(long id){owned();return require(id).evidence;}
    /** Installs the module host's drive index; see {@link Drives}. */
    public void drives(Drives index){owned();drives=Objects.requireNonNull(index);}
    /** Installs the listener told of thermo-domain holds; see {@link DomainHolds}. */
    public void onDomainHold(DomainHolds listener){owned();domainHolds=Objects.requireNonNull(listener);}
    /** Installs how a node is named in a status line (a device's kind and position in the world). */
    public void nodeNames(java.util.function.LongFunction<String> names){owned();nodeNames=Objects.requireNonNull(names);}
    /** The module host changed this island's drives: a certified island may have to wake earlier or at once. */
    public void drivesChanged(long island){owned();var value=islands.get(island);if(value!=null&&value.certificate!=null)reconsider(value);}
    /** Told of every certificate materialisation, with the replayed interval as the island's last result. Only
     * accounting listens here: a replay changes no dependency and needs no view refresh of its own. */
    public void onReplayed(Publisher listener){owned();replayed=Objects.requireNonNull(listener);}
    public CertificatePolicy certificates(){return settings.certificates();}
    public int pendingCount(){owned();return pending.size();}
    /** The shared online epoch this coordinator's clocks read. */
    public long now(){owned();return epoch.getAsLong();}
    /** O(1): the earliest tick at which a deadline is scheduled, possibly a stale one; see {@link IslandScheduler}. */
    public long nextDue(){owned();return scheduler.nextDue();}
    /** Scheduled deadline entries, live and not yet popped stale ones. */
    public int scheduledDeadlines(){owned();return scheduler.size();}
    /** Owners whose next slice can be dispatched now. */
    public int readyCount(){owned();return ready.readyCount();}
    /** The epoch tick at which an island's online time reads {@code onlineTick}. */
    public long epochTick(long island,long onlineTick){owned();return require(island).clock.epochTickAt(onlineTick);}
    public boolean hasFence(long island,UUID event){owned();return require(island).fences.containsKey(event);}
    /** Islands holding a fence for this event, from the fence index rather than a scan. */
    public Set<Long> fencedIslands(UUID event){owned();return Set.copyOf(fenceOwners.getOrDefault(event,Set.of()));}
    /** Told when an island's last attempt drains after its round already closed: its ownership changed
     * without a publication, which a dependency (the module host) may have to see. */
    public void onReleased(LongConsumer listener){owned();released=Objects.requireNonNull(listener);}
    /**
     * Schedules the one deadline of an external kind ({@link IslandScheduler.Kind#MODULE_HORIZON},
     * {@link IslandScheduler.Kind#RECOVERY_RETRY}) at an epoch tick, replacing any earlier one of that kind;
     * {@link Long#MAX_VALUE} cancels it. The action runs on this thread from {@link #tick()} once due.
     */
    public void schedule(IslandScheduler.Kind kind,long epochTick,Runnable action) {
        owned();Objects.requireNonNull(action);
        if(kind!=IslandScheduler.Kind.MODULE_HORIZON&&kind!=IslandScheduler.Kind.RECOVERY_RETRY)throw new IllegalArgumentException("Not an external deadline kind: "+kind);
        if(stopped)return;
        externalActions.put(kind,action);
        if(Objects.equals(externalTicks.get(kind),epochTick))return;
        long generation=++generations;externalTicks.put(kind,epochTick);
        if(epochTick!=Long.MAX_VALUE)schedule(epochTick,kind,generation,generation);
    }

    /** Invalidates old proposals without changing committed stock, debt, fences or allowance.
     * Cancellation retains ownership until the actual terminal completion is drained. */
    public void suspendForPropertyChange(String reason) {
        owned();Objects.requireNonNull(reason);if(stopped||suspension!=null)return;
        suspension=reason;dispatcher.demand(0);
        for(var island:islands.values()) {
            // Committed time freezes where it stands: a certified island is materialised to now and capped there,
            // while online time keeps accruing so the debt is preserved. No evidence spans the hold.
            island.holdTick=island.clock.snapshot().onlineTick();materialise(island);
            island.lastInterval=null;island.streak=0;island.revalidating=null;
            island.revision=Math.addExact(island.revision,1);island.suspended=true;island.status=reason;island.retained=island.certificate==null?new RetainedSolver():null;
            island.anchor=island.anchor.map(a->new ApproximationAnchor("invalidated:property-reload",a.graph(),a.modes()));
            island.touch();reconsider(island);
        }
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        publisher.published(snapshots());
    }
    /** Only the world property's compatibility guard may resume this pinned model. Every certificate is discarded:
     * the island solves its debt from the materialised state and must qualify again over the confirm count. */
    public void resumeQualifiedProperties() {
        owned();if(stopped||suspension==null)return;suspension=null;
        for(var island:islands.values()) {
            island.holdTick=Long.MAX_VALUE;island.suspended=false;island.status="WAITING: full solve after property reload";inputsChanged(island);
            if(island.certificate!=null){island.certificate=null;island.certifiedSince=-1;island.signature=null;island.retained=new RetainedSolver();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificateWakes);}
            island.lastInterval=null;island.streak=0;
            island.touch();reconsider(island);
        }
        publisher.published(snapshots());
    }

    /**
     * Called once per elapsed server tick, after the shared epoch advanced (or advancing this coordinator's own
     * epoch), never from wall time or for time spent offline. Constant cost when nothing is due: it resets the
     * per-tick dispatch budget, pops only due deadlines, checks the wall budget of the open rounds, and pumps
     * only when a pump would act.
     */
    public void tick() {
        owned();if(stopped)return;
        if(ownsEpoch)ownEpoch=Math.addExact(ownEpoch,1);
        dispatchedThisTick=0;
        runDue();
        // The ROUND_TIMEOUT deadline assumes nominal 50 ms ticks; a slow server spends the wall budget in fewer
        // ticks. Checking each open round's budget here, O(open rounds) and nothing per island, closes an
        // expired round within one tick of its wall deadline however long the ticks are.
        if(!roundDue)for(var round:rounds)if(nanoClock.getAsLong()-round.startedNanos>=settings.hardBudgetNanos){roundDue=true;break;}
        verify();
        pumpIfUseful();
    }
    /** Pops every deadline due at the current epoch tick and acts on the ones still current. */
    private void runDue() {
        long now=epoch.getAsLong();
        for(var due=scheduler.poll(now);due!=null;due=scheduler.poll(now)) {
            switch(due.kind()) {
                case SLICE_DUE,RETRY,CERTIFICATE_HORIZON->{
                    // For a certified island this is its horizon or the next module drive: materialise and act.
                    var island=islands.get(due.id());if(island==null||island.generation!=due.generation())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);island.deadline=Long.MAX_VALUE;
                    materialise(island);reconsider(island);
                }
                case ROUND_TIMEOUT->{
                    Round round=null;for(var open:rounds)if(open.id==due.id())round=open;if(round==null)continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);
                    long remaining=settings.hardBudgetNanos-(nanoClock.getAsLong()-round.startedNanos);
                    if(remaining<=0)roundDue=true;else scheduleRoundTimeout(round,remaining);
                }
                case ALLOCATOR_SHRINK->{
                    // The current allocator deadline is the one at shrinkTick; any other entry was replaced.
                    if(shrinkTick!=due.tick())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);shrinkTick=Long.MAX_VALUE;shrinkDue=true;
                }
                case MODULE_HORIZON,RECOVERY_RETRY->{
                    var tick=externalTicks.get(due.kind());if(tick==null||tick!=due.tick())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);externalTicks.remove(due.kind());externalActions.get(due.kind()).run();
                }
                default->throw new IllegalStateException("No handler for deadline "+due.kind());
            }
        }
    }
    /**
     * The O(1) check the tick hooks make: pumps only when a pump would act, that is when a state change made
     * an owner ready, a completion arrived, a round or allocator deadline fell due, or ready owners meet free
     * capacity (for example after the per-tick dispatch budget was reset). Installed as the readiness pump
     * called after completion drains.
     */
    public void pumpIfUseful() {
        owned();if(stopped||pumping||suspension!=null)return;
        if(pumpRequested||completionsSincePump||roundDue||shrinkDue||ready.readyCount()>0&&capacity()>0)pump();
    }
    /** May also run after terminal draining, allowing catch-up between ordinary tick boundaries. */
    public void pump() {
        owned();if(stopped||pumping)return;pumping=true;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.readinessPumps);
        pumpRequested=false;completionsSincePump=false;roundDue=false;shrinkDue=false;
        try {
            if(suspension!=null){dispatcher.demand(0);return;}
            for(var round:List.copyOf(rounds)) {
                if(round.entries.stream().allMatch(p->p.terminal)||nanoClock.getAsLong()-round.startedNanos>=settings.hardBudgetNanos) {
                    rounds.remove(round);closeRound(round);
                }
            }
            verify();
            int eligible=ready.readyCount();
            dispatcher.demand(eligible);scheduleShrink();
            // Completed results waiting at another group's barrier retain a bounded staging slot.
            int capacity=capacity();
            if(capacity<=0)return;
            var admitted=new ArrayList<Pending>();long roundStarted=nanoClock.getAsLong();
            for(int slot=0;slot<capacity;slot++) {
                var next=ready.nextReady(id->{FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits);var i=require(id);return i.pendingEntries==0&&i.clock.nextSlice(1,i.fence(),i.maximumSliceTicks).isPresent();});
                if(next.isEmpty())break;
                var island=require(next.getAsLong());long request=dispatcher.nextRequestId();
                var slice=island.clock.nextSlice(request,island.fence(),island.maximumSliceTicks).orElseThrow();
                var attempt=new Attempt(island.id,island.revision,slice);
                var policy=island.anchor.isPresent()&&!island.fullOnly&&!com.wormzjl.createcheme.science.fluid.transport.SolidMobility.requiresFull(island.model,island.graph)?FluidFallbackPolicy.active(island.anchor.orElseThrow(),island.allowance,island.clock.snapshot().cadenceTicks(),settings.softBudgetNanos):FluidFallbackPolicy.disabled();
                // The job starts from the committed interval, not from the solver's history; see RetainedSolver.
                island.retained.committed(island.lastResult.orElse(null));
                var command=new ProcessSolveServices.FluidIslandCommand(island.model,island.graph,slice.seconds(),PassiveIntervalSolver.Settings.defaults(),settings.hardBudgetNanos,policy,island.retained);
                if(!dispatcher.submit(attempt,command)) {island.status="WAITING: shared worker capacity";island.touch();break;}
                island.clock.admitted(slice);island.status="SOLVING";island.touch();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.solvesDispatched);
                var entry=new Pending(attempt,island.clock.snapshot().onlineTick(),nanoClock.getAsLong());pending.put(request,entry);island.pendingEntries++;admitted.add(entry);dispatchedThisTick++;
                reconsider(island);
            }
            if(!admitted.isEmpty()){var round=new Round(roundSequence=Math.addExact(roundSequence,1),List.copyOf(admitted),roundStarted);rounds.add(round);scheduleRoundTimeout(round,settings.hardBudgetNanos);}
        } finally {
            // Owners that became ready during this pump were offered to it; any left over wait for capacity,
            // which pumpIfUseful checks directly, so no request survives the pump.
            pumping=false;pumpRequested=false;
        }
    }
    private int capacity(){return Math.min(dispatcher.availableWorkers(),Math.min(settings.maximumDispatchesPerTick-dispatchedThisTick,settings.maximumDispatchesPerTick-pending.size()));}
    /** A missing result denotes cancellation, failure or abandonment. Duplicate/late results never advance clocks. */
    public void completed(Attempt attempt,Optional<ProcessSolveServices.FluidIslandSolveResult> result) {
        owned();Objects.requireNonNull(result);var entry=pending.get(attempt.slice.requestId());
        if(entry==null||!entry.attempt.equals(attempt)||entry.terminal)return;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.completionsRouted);
        entry.terminal=true;entry.result=nanoClock.getAsLong()-entry.admittedNanos>=settings.hardBudgetNanos?null:result.orElse(null);
        completionsSincePump=true;
        if(entry.closed) {
            pending.remove(attempt.slice.requestId());var island=islands.get(attempt.islandId);
            if(island!=null){island.pendingEntries--;reconsider(island);released.accept(island.id);}
            return;
        }
        // The completion router calls pump after its entire bounded drain, avoiding recursive dispatch here.
    }
    private void closeRound(Round round) {
        var changed=new ArrayList<Snapshot>();
        for(var entry:round.entries) {
            var attempt=entry.attempt;var island=require(attempt.islandId);
            var outcome=entry.result;var before=island.graph;
            String refusal=null;
            boolean accept=entry.terminal&&outcome!=null&&outcome.candidate().isPresent()&&attempt.revision==island.revision;
            if(accept) {
                var candidate=outcome.candidate().orElseThrow();
                accept=validCandidate(island,attempt,candidate);
                Optional<Runnable> materialCommit=Optional.empty();
                if(accept) {
                    try{materialCommit=commitHook.prepare(attempt,outcome);}
                    catch(IllegalArgumentException|IllegalStateException stale){refusal=stale.getMessage();}
                    if(materialCommit.isEmpty()&&refusal==null)refusal="material transaction not ready";
                } else refusal="candidate identity or interval mismatch";
                accept&=materialCommit.isPresent();
                if(accept) {
                    materialCommit.orElseThrow().run();
                    island.graph=candidate.graph();island.lastResult=Optional.of(candidate);
                    island.pipeSpeed.accepted(attempt.slice.startTick(),attempt.slice.endTick(),candidate.pipeTransfers());
                    island.allowance=outcome.proposedAllowance();island.anchor=outcome.proposedAnchor();island.status=outcome.detail();
                }
            }
            if(!accept)island.status=refusal!=null?"HELD: "+refusal:outcome==null?"HELD: round deadline or worker failure"
                    :outcome.domain().isPresent()?domainStatus(outcome.domain().orElseThrow(),false)
                    :outcome.detail().startsWith("HELD")?outcome.detail():"HELD: "+outcome.detail();
            island.clock.completed(attempt.slice,accept);
            if(suspension!=null)island.status=suspension;
            if(suspension==null&&!accept&&refusal==null&&attempt.revision==island.revision)hold(island,attempt.slice,outcome);
            else if(accept) {
                island.holdStreak=0;island.fullOnly=false;island.domainFailure=null;island.domainParked=false;
                if(outcome.candidate().orElseThrow().acceptance()==PassiveStepSolver.Acceptance.FULL&&island.maximumSliceTicks!=Integer.MAX_VALUE) {
                    int cadence=island.clock.snapshot().cadenceTicks();
                    island.maximumSliceTicks=island.maximumSliceTicks>=cadence/2?Integer.MAX_VALUE:island.maximumSliceTicks*2;
                }
            }
            island.metrics=new Metrics(island.metrics==null?1:Math.addExact(island.metrics.sequence(),1),attempt.slice.startTick(),attempt.slice.endTick(),entry.dispatchedAtTick,island.clock.snapshot().onlineTick(),outcome==null?-1:outcome.workerNanos(),outcome==null?-1:outcome.workerCpuNanos(),Math.max(0,nanoClock.getAsLong()-entry.admittedNanos),accept);
            if(settings.adaptive&&outcome!=null) {
                long cpu=outcome.workerCpuNanos(),wall=outcome.workerNanos();
                boolean cpuBound=cpu>=0&&wall>0&&cpu/(double)wall>=.75;
                island.clock.performanceSample(cpuBound&&wall>=settings.softBudgetNanos,accept&&cpu>=0&&wall<settings.softBudgetNanos/4);
            }
            entry.closed=true;
            if(entry.terminal){pending.remove(attempt.slice.requestId());island.pendingEntries--;}else dispatcher.cancel(attempt.slice.requestId());
            qualify(island,accept?outcome:null,before,attempt);
            reconsider(island);island.touch();
            changed.add(island.snapshot());
        }
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandsPublished,changed.size());
        publisher.published(List.copyOf(changed));
    }
    /** The longest a held island waits between retries at the shortest slice, in cadences: 2^6 = 64. */
    static final int MAXIMUM_RETRY_DOUBLINGS=6;
    /**
     * The hold policy: what a held attempt (no committed candidate, no refused transaction) changes before the retry.
     *
     * <p>Every hold hands the retry a fresh {@link RetainedSolver}. The one the attempt used carries the step
     * solver's last flows, modes and factorizations from wherever inside the slice that attempt stopped - the cut
     * of a wall budget or the last pass of a failed solve - and a retry that warm-starts from them starts the slice
     * again from its first state with the flows of a later one: measured on the vented three-tank chain, a rate
     * solve that starts from 0.45 and -0.22 kg/s on tanks still at rest converges linearly through the friction kink
     * at zero and fails the same way at every retry. It is also what made a retry's trajectory depend on how far the
     * cancelled job had got. With a fresh solver a retry is the same computation as the first attempt on the same
     * committed state and slice, so a numerical failure is reproducible exactly and a retry of the same slice is
     * pointless.
     *
     * <p>So every hold also changes the problem: a slice longer than one tick is halved (a shorter whole interval,
     * never a partial commit; the first full acceptance doubles it back), whether the budget cut it or the solve
     * failed, and a hold at one tick waits {@code cadence * 2^k} ticks for its retry, k the number of such holds in a
     * row, at most {@link #MAXIMUM_RETRY_DOUBLINGS}. A budget hold at one tick is retried too, because a warmer or
     * less loaded worker can pass it; it just stops being retried every cadence. Anything that changes the island's
     * inputs (a fence, a released fence, a resolved delivery, a property resume) clears the wait and the count; a
     * topology change makes a new island. Online ticks only: no wall clock decides a delay.
     *
     * <p>A hold whose soft budget ran out and whose approximate fallback then refused the slice spent a quarter of
     * the budget on a fallback that cannot pass it: the retry runs the full solve alone on the whole budget, until
     * the next accepted interval.
     */
    private void hold(Island island,IslandClock.Slice slice,ProcessSolveServices.FluidIslandSolveResult outcome) {
        FluidRuntimeDiagnostics.count(budgetHold(outcome)?FluidRuntimeDiagnostics.budgetHolds:FluidRuntimeDiagnostics.numericalHolds);
        var domain=outcome==null?null:outcome.domain().orElse(null);
        if(domain!=null) {
            // A thermo-domain failure is retried once, on a fresh solver like every hold (and on the halved slice the ladder
            // below gives it). When that retry fails on the same boundary at the same node, the failure is the model's
            // and deterministic - the committed state, or where the island's physics takes it, lies outside the property
            // package's data - so no retry on the ladder can pass it: the island waits, dispatching nothing, until its
            // inputs change (a fence, a released or resolved one, a property resume; a topology or control edit makes a
            // new island). Every other failure keeps the ladder.
            boolean reproduced=domain.sameAs(island.domainFailure);
            island.domainFailure=domain;
            warnDomain(island,domain,reproduced);
            if(reproduced) {
                island.domainParked=true;island.status=domainStatus(domain,true);
                island.clock.holdUntilInputsChange();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.domainHolds);
                if(island.certificate==null)island.retained=new RetainedSolver();
                return;
            }
        }
        int ticks=(int)(slice.endTick()-slice.startTick());
        if(ticks>1)island.maximumSliceTicks=Math.max(1,ticks/2);
        else {
            int cadence=island.clock.snapshot().cadenceTicks();
            island.clock.deferRetry((long)cadence<<Math.min(island.holdStreak,MAXIMUM_RETRY_DOUBLINGS));
            island.holdStreak=Math.min(island.holdStreak+1,MAXIMUM_RETRY_DOUBLINGS);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.retriesDeferred);
        }
        if(outcome!=null&&outcome.detail().startsWith(ProcessSolveServices.SOFT_BUDGET_REFUSED))island.fullOnly=true;
        if(island.certificate==null)island.retained=new RetainedSolver();
    }
    /** A hold the worker's budget decided rather than the solve: no result by the round deadline, the wall budget,
     * or a soft budget whose approximate fallback refused. A repeat can pass with more CPU; the rest cannot. */
    static boolean budgetHold(ProcessSolveServices.FluidIslandSolveResult outcome) {
        return outcome==null||outcome.detail().startsWith(ProcessSolveServices.WALL_DEADLINE)||outcome.detail().startsWith(ProcessSolveServices.SOFT_BUDGET_REFUSED);
    }
    /** Something the island solves against changed: a held island is retried at once, from its first retry again - a
     * thermo-domain hold included, which starts a new episode. */
    private static void inputsChanged(Island island){island.clock.inputsChanged();island.holdStreak=0;island.domainFailure=null;island.domainParked=false;}
    /** The dedicated status line of a thermo-domain hold; see {@link com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation}. */
    private String domainStatus(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation,boolean parked) {
        return ProcessSolveServices.THERMO_DOMAIN+violation.sentence(where(violation))+" (valid "+violation.range()+", package "+violation.packageId()+")"
                +(parked?"; the retry failed the same way, so the island waits for a change to its network":"");
    }
    private String where(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation) {
        return violation.node()==com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation.NO_NODE?"":nodeNames.apply(violation.node());
    }
    /** One log line per island per hold: a new violation, or the same one again after the rate limit's online ticks. */
    private void warnDomain(Island island,com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation,boolean parked) {
        String key=violation.reasonKey()+"@"+violation.node();long now=island.clock.onlineTick();
        if(key.equals(island.warnedDomainKey)&&now-island.warnedDomainAt<DOMAIN_WARNING_TICKS)return;
        island.warnedDomainKey=key;island.warnedDomainAt=now;
        domainHolds.held(island.id,violation,where(violation),parked);
    }
    private static boolean sameConnections(List<PassiveNetwork.Pipe> before,List<PassiveNetwork.Pipe> after){
        if(before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++){var a=before.get(i);var b=after.get(i);
            if(a.id()!=b.id()||a.first()!=b.first()||a.second()!=b.second()||!a.sections().equals(b.sections())||!a.control().equals(b.control())||(a.filter()==null)!=(b.filter()==null))return false;
            if(a.filter()!=null){if(a.filter().capacity()!=b.filter().capacity()||a.filter().cleanResistance()!=b.filter().cleanResistance()||a.filter().stoppedAtCapacity()&&!b.filter().stoppedAtCapacity())return false;
                for(var p:a.filter().captured().populations())if(b.filter().captured().mass(p.key())+1e-12<p.massKg())return false;}
        }return true;
    }
    private static boolean validCandidate(Island island,Attempt attempt,PassiveIntervalSolver.Result result) {
        if(result.advancedSeconds()!=attempt.slice.seconds()||!sameConnections(island.graph.pipes(),result.graph().pipes())||!result.graph().scheduledTransfers().isEmpty())return false;
        var old=island.graph.reservoirs();var next=result.graph().reservoirs();if(old.size()!=next.size())return false;
        for(int i=0;i<old.size();i++) {
            var a=old.get(i);var b=next.get(i);
            if(a.id()!=b.id()||a.kind()!=b.kind()||a.elevation()!=b.elevation()||(!a.junction()&&a.inventory().volume()!=b.inventory().volume()))return false;
        }
        return true;
    }
    /** Event fences must be installed before a solver can include their tick. */
    public void fence(UUID event,long tick,Collection<Long> affected) {
        owned();Objects.requireNonNull(event);
        for(long id:affected) {
            var island=require(id);
            if(tick<island.clock.committedTick()||pending.values().stream().anyMatch(p->p.attempt.islandId==id&&!p.closed&&p.attempt.slice.endTick()>tick))
                throw new IllegalStateException("Event would modify an admitted or committed interval");
            if(island.fences.containsKey(event))throw new IllegalStateException("Duplicate event fence");
        }
        for(long id:affected){var island=require(id);island.fences.put(event,tick);fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(id);inputsChanged(island);island.touch();reconsider(island);}
    }
    /** A certified owner answers by materialisation: it advances to the fence and is then aligned like any other. */
    public boolean aligned(UUID event,Collection<Long> affected) {
        owned();for(long id:affected)materialise(require(id));
        return affected.stream().allMatch(id->{var i=require(id);var tick=i.fences.get(event);return tick!=null&&i.clock.committedTick()==tick&&i.pendingEntries==0;});
    }
    /** A batch's owners: each affected island holds a fence of at least one of the events, every one of them at its
     * committed tick, and runs no attempt; certified ones are materialised first, as for one event. */
    private boolean aligned(Set<UUID> events,Collection<Long> affected) {
        for(long id:affected)materialise(require(id));
        for(long id:affected) {
            var island=require(id);if(island.pendingEntries!=0)return false;boolean fenced=false;
            for(var event:events){var tick=island.fences.get(event);if(tick==null)continue;if(tick!=island.clock.committedTick())return false;fenced=true;}
            if(!fenced)return false;
        }
        return true;
    }
    public void releaseFence(UUID event,Collection<Long> affected) {
        owned();if(!aligned(event,affected))throw new IllegalStateException("Event owners have not aligned");
        for(long id:affected){var i=require(id);removeFence(i,event);inputsChanged(i);i.touch();reconsider(i);}
    }
    /** Production has become known (including zero production). A lagging receiver need not
     * reach the promised time to resolve this dependency. The host separately retains a known
     * input-time boundary when needed, so it cannot inject into an already admitted interval. */
    public void resolveDeliveryFence(UUID event,Collection<Long> receivers) {
        owned();for(long id:receivers)if(!require(id).fences.containsKey(event))throw new IllegalStateException("Missing delivery horizon");
        for(long id:receivers){var island=require(id);removeFence(island,event);inputsChanged(island);island.touch();reconsider(island);}
    }
    public record Replacement(long id,PassiveNetwork graph) {
        public Replacement {if(id<=0)throw new IllegalArgumentException("Invalid replacement identity");Objects.requireNonNull(graph);}
    }
    /**
     * Atomically merges/splits aligned islands without changing any owned reservoir inventory.
     * Placement, destruction and material delivery require their own explicitly balanced transactions.
     * Topology changes require a new full solve; they cannot renew an approximate grace period.
     */
    public void repartition(UUID event,Set<Long> affected,List<Replacement> replacements) {
        owned();if(affected.isEmpty()||replacements.isEmpty())throw new IllegalStateException("Empty repartition");var first=require(affected.iterator().next());
        topology(event,affected,replacements,first.model,first.clock.committedTick(),first.clock.snapshot().onlineTick(),Map.of(),Set.of(),()->{});
    }
    /** Placement/destruction extension: declared additions and removals must exactly match the stock delta.
     * metadataCommit publishes the already-prepared construction/destruction ledger and registry atomically. */
    public void topology(UUID event,Set<Long> affected,List<Replacement> replacements,FluidThermodynamics model,
            long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Runnable metadataCommit) {
        topology(event,affected,replacements,model,committed,online,additions,removals,Map.of(),metadataCommit);
    }
    public void topology(UUID event,Set<Long> affected,List<Replacement> replacements,FluidThermodynamics model,long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,com.wormzjl.createcheme.science.fluid.network.InlineFilter> releasedFilters,Runnable metadataCommit) {
        topology(Set.of(event),affected,replacements,model,committed,online,additions,removals,releasedFilters,metadataCommit);
    }
    /**
     * A batch of events of one tick applied as one change: every affected island holds a fence of at least one of them,
     * each at its committed tick, and none of them survives into a replacement. The rest is as for one event.
     */
    public void topology(Set<UUID> events,Set<Long> affected,List<Replacement> replacements,FluidThermodynamics model,long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,com.wormzjl.createcheme.science.fluid.network.InlineFilter> releasedFilters,Runnable metadataCommit) {
        owned();Objects.requireNonNull(model);Objects.requireNonNull(metadataCommit);if(events.isEmpty())throw new IllegalArgumentException("A topology change needs its events");
        if(stopped||committed<0||online<committed||!aligned(events,affected))throw new IllegalStateException("Topology event is not ready");
        var originals=affected.stream().map(this::require).toList();
        long revision=0;int cadence=originals.isEmpty()?settings.initialCadenceTicks:20;
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var fences=new HashMap<UUID,Long>();
        var allowance=FallbackAllowance.NONE;Optional<ApproximationAnchor> anchor=Optional.empty();
        for(var old:originals) {
            if(old.clock.committedTick()!=committed||old.clock.snapshot().onlineTick()!=online||!ApproximationAnchor.revision(old.model).equals(ApproximationAnchor.revision(model)))throw new IllegalStateException("Incompatible clocks or property packages");
            revision=Math.max(revision,old.revision);cadence=Math.max(cadence,old.clock.snapshot().cadenceTicks());
            for(var node:old.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&stock.putIfAbsent(node.id(),node)!=null)throw new IllegalStateException("Duplicate stock ownership");
            for(var fence:old.fences.entrySet())if(!events.contains(fence.getKey())) {
                Long prior=fences.putIfAbsent(fence.getKey(),fence.getValue());if(prior!=null&&!prior.equals(fence.getValue()))throw new IllegalStateException("Conflicting event timestamps");
            }
            if(old.allowance.acceptedIntervals()>0) {
                int captured=allowance.acceptedIntervals()==0?old.allowance.capturedCadenceTicks():Math.min(allowance.capturedCadenceTicks(),old.allowance.capturedCadenceTicks());
                allowance=new FallbackAllowance(3,3L*captured,captured);anchor=old.anchor;
            }
        }
        for(long removed:removals)if(stock.remove(removed)==null)throw new IllegalStateException("Destruction did not name an owned reservoir");
        for(var entry:additions.entrySet()) {
            var node=entry.getValue();
            if(node.id()!=entry.getKey()||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||stock.putIfAbsent(node.id(),node)!=null||removals.contains(node.id()))throw new IllegalStateException("Invalid constructed stock identity");
            Long holder=stockOwners.get(node.id());if(holder!=null&&!affected.contains(holder))throw new IllegalStateException("Constructed stock already exists");
        }
        revision=Math.addExact(revision,1);var seen=new HashSet<Long>();var ids=new HashSet<Long>();var staged=new ArrayList<Island>();
        for(var replacement:replacements) {
            if(!ids.add(replacement.id)||islands.containsKey(replacement.id)&&!affected.contains(replacement.id))throw new IllegalStateException("Replacement island identity collision");
            for(var node:replacement.graph.reservoirs()) {
                if(node.kind()==PassiveNetwork.NodeKind.PORT)throw new IllegalArgumentException("Internal port in topology");
                if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                var old=stock.get(node.id());
                if(!seen.add(node.id())||old==null||!old.inventory().equals(node.inventory())||old.elevation()!=node.elevation())throw new IllegalStateException("Topology event changed stock or elevation energy");
            }
            var island=new Island(new Snapshot(replacement.id,revision,replacement.graph,new IslandClock.Snapshot(online,committed,0,cadence),allowance,anchor,Optional.empty(),"WAITING: full solve after topology change",fences),model,epoch);
            // A topology change starts the island cold: its first slice is one tick and each accepted full interval
            // doubles it back to the cadence (as after a budget hold). Whatever the player just built - a pump
            // filling dry tanks, a valve opening - starts its transient here, and its first milliseconds can cost
            // more than the wall budget: measured in game, 100 pumped fills placed at once spent their first 100 s
            // being cut at 100, 50, 25, 12, 6 and 3 ticks before a slice fitted, 2 s of work thrown away each time.
            // A quiet island pays six short solves instead. Islands registered from a save start at their cadence.
            island.maximumSliceTicks=1;
            staged.add(island);
        }
        if(!seen.equals(stock.keySet()))throw new IllegalStateException("Topology event lost reservoir ownership");
        var oldFilters=new HashMap<Long,com.wormzjl.createcheme.science.fluid.network.InlineFilter>();for(var original:originals)for(var pipe:original.graph.pipes())if(pipe.filter()!=null)oldFilters.put(pipe.id(),pipe.filter());
        for(var release:releasedFilters.entrySet())if(!release.getValue().equals(oldFilters.get(release.getKey())))throw new IllegalStateException("Stale filter recovery");
        var seenFilters=new HashSet<Long>();for(var replacement:replacements)for(var pipe:replacement.graph().pipes())if(pipe.filter()!=null){
            if(!seenFilters.add(pipe.id()))throw new IllegalStateException("Duplicate filter ownership");var old=oldFilters.remove(pipe.id());
            if(old==null||releasedFilters.containsKey(pipe.id())){if(!pipe.filter().captured().empty()||pipe.filter().energyJoule()!=0)throw new IllegalStateException("Undeclared filter inventory creation");}
            else if(!old.captured().equals(pipe.filter().captured())||old.energyJoule()!=pipe.filter().energyJoule())throw new IllegalStateException("Topology changed filter inventory");
        }
        for(var removed:oldFilters.entrySet())if(!removed.getValue().captured().empty()&&!releasedFilters.containsKey(removed.getKey()))throw new IllegalStateException("Topology lost captured solids");
        metadataCommit.run();
        for(long id:affected){var old=islands.remove(id);ready.remove(id);unindex(old);unindexStock(old);old.deadline=Long.MAX_VALUE;old.generation=++generations;}
        for(var island:staged){islands.put(island.id,island);ready.register(island.id,false);index(island);indexStock(island);reconsider(island);}
        publisher.published(staged.stream().map(Island::snapshot).toList());
    }
    /** Stops admission; unresolved proposals are held. Actual execution capacity is owned by the shared pool. */
    public void stop() {
        owned();if(stopped)return;stopped=true;
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        scheduler.clear();externalTicks.clear();externalActions.clear();
    }
    private Island require(long id){var value=islands.get(id);if(value==null)throw new IllegalArgumentException("Unknown island "+id);return value;}

    /**
     * Re-derives one island's readiness and its deadline after anything that decides them changed. An island
     * is ready exactly when the polled coordinator's eligibility test held: no attempt owns it and its clock
     * offers a slice. Otherwise its clock says from which online tick time alone would make it ready, and that
     * becomes its single deadline; a stopped or suspended coordinator keeps no island ready or scheduled.
     */
    private void reconsider(Island island) {
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits);
        boolean eligible=false;long due=Long.MAX_VALUE;var kind=IslandScheduler.Kind.SLICE_DUE;
        if(island.certificate!=null) {
            // A certified island is never ready and holds at most one entry, at its horizon or its next module
            // drive. Blocked behind an earlier fence it holds none: the fence's release re-examines it. It is
            // never materialised from here, even when that tick has passed: the caller may be in the middle of a
            // decision (the module host between releasing one fence and installing the next) and must not see
            // the island move. A wake already reached fires on the next pass over due deadlines, and the
            // materialisation it makes still stops exactly at the wake tick.
            kind=IslandScheduler.Kind.CERTIFICATE_HORIZON;
            if(!stopped&&suspension==null) {
                long wake=certifiedWake(island);
                if(wake!=Long.MAX_VALUE&&island.fence()>=wake)due=island.clock.epochTickAt(wake);
            }
        }
        else if(!stopped&&suspension==null&&island.pendingEntries==0) {
            long fence=island.fence();
            if(island.clock.nextSlice(1,fence,island.maximumSliceTicks).isPresent())eligible=true;
            else {
                long at=island.clock.readyAtTick(fence,island.maximumSliceTicks);
                // The label only records which bound set the time: the held owner's backoff or its slice.
                if(at!=Long.MAX_VALUE){due=island.clock.epochTickAt(at);if(island.clock.retryAtTick()==at)kind=IslandScheduler.Kind.RETRY;}
            }
        }
        if(island.ready!=eligible) {
            island.ready=eligible;ready.setReady(island.id,eligible);
            if(eligible)pumpRequested=true;
        }
        if(due!=island.deadline) {
            island.deadline=due;island.generation=++generations;
            if(due!=Long.MAX_VALUE)schedule(due,kind,island.id,island.generation);
        }
    }

    // ---- certificates (plan section 3.2 and 3.3) ----

    /** A certified island's next own event, as an online tick: its horizon or its earliest module drive. */
    private long certifiedWake(Island island){return Math.min(island.certificate.horizonTick(),drives.earliest(island.id,island.clock.committedTick(),Long.MAX_VALUE));}
    /**
     * Advances a certified island to {@code target = min(online, nearest fence, hold tick, horizon, earliest
     * drive)} and acts on what it reached: a module drive wakes it, the horizon wakes it to solve one
     * revalidating slice. On demand only: a fence or event alignment, a module horizon, a read of its snapshot
     * (a save, a module decision), a view (a presentation read, which never wakes), its own deadline. Each
     * advance is reported to the replay listener with
     * the replayed span as the island's last result, so boundary and pump accounting stays exact.
     */
    private void materialise(Island island){materialise(island,false);}
    /** {@code presentation}: a view's read, which stops one tick short of a wake not yet acted on and never wakes. */
    private void materialise(Island island,boolean presentation) {
        var certificate=island.certificate;if(certificate==null)return;
        long committed=island.clock.committedTick(),online=island.clock.onlineTick();
        long drive=drives.earliest(island.id,committed,online),wake=Math.min(certificate.horizonTick(),drive);
        long target=Math.min(Math.min(online,island.fence()),Math.min(island.holdTick,wake));
        if(presentation&&target==wake)target=wake-1;
        if(target>committed) {
            // The island keeps its last solved interval as its result (what a save records and a view reads, whose
            // rates replay repeats); the replayed span with its scaled accounting goes to the replay listener only.
            var replay=certificate.replay(committed,target);
            island.clock.rest(target,island.fence());island.graph=replay.graph();
            island.pipeSpeed.accepted(committed,target,replay.pipeTransfers());
            var advance=certificate.kind()==IslandCertificate.Kind.REST?Advance.RESTED:Advance.REPLAYED;
            island.metrics=new Metrics(island.metrics==null?1:Math.addExact(island.metrics.sequence(),1),committed,target,online,online,-1,-1,0,true,advance);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.materialisations);
            FluidRuntimeDiagnostics.count(advance==Advance.RESTED?FluidRuntimeDiagnostics.restedTicks:FluidRuntimeDiagnostics.replayedTicks,target-committed);
            if(suspension==null)island.status=certifiedStatus(island);
            // A certified payload records the base, not the materialised state, so materialisation changes nothing a
            // checkpoint keeps - except that an island stopped at its fence reads "WAITING: event alignment".
            if(target==island.fence())island.touch();
            var stored=island.snapshot();
            replayed.published(List.of(new Snapshot(stored.id(),stored.revision(),stored.graph(),stored.clock(),stored.allowance(),stored.anchor(),Optional.of(replay),stored.status(),stored.fences(),stored.certificate(),stored.payloadGeneration())));
        }
        if(presentation||stopped||suspension!=null)return;
        if(drive!=Long.MAX_VALUE&&target==drive)wake(island,null,"WAITING: module drive at "+drive);
        else if(target==certificate.horizonTick())wake(island,certificate,"REVALIDATING: "+certificate.kind()+" certificate horizon at "+certificate.horizonTick()/20.0+" s");
    }
    /** Ends a certificate: fresh solver caches, no evidence; revalidating keeps the certificate its first slice is compared with. */
    private void wake(Island island,IslandCertificate revalidating,String status) {
        island.certificate=null;island.revalidating=revalidating;island.retained=new RetainedSolver();
        island.lastInterval=null;island.streak=0;if(revalidating==null)island.certifiedSince=-1;
        island.status=status;island.signature=null;island.touch();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificateWakes);
        reconsider(island);released.accept(island.id);
    }
    /**
     * The entry evidence of plan section 3.3, evaluated after each round closure on the server thread. An
     * accepted FULL interval with no material transfers and no degraded episode extends the stationary streak
     * when it repeats the previous interval within the tolerance, or starts a new one; once the streak reaches the
     * confirm count and no module drive falls in the coming cadence, the last interval is certified. A horizon's
     * revalidating slice renews the certificate from its solved state if it repeats the certified interval, and
     * otherwise starts a fresh streak.
     */
    private void qualify(Island island,ProcessSolveServices.FluidIslandSolveResult accepted,PassiveNetwork before,Attempt attempt) {
        var revalidation=island.revalidating;island.revalidating=null;var policy=settings.certificates();
        boolean usable=policy.enabled()&&!stopped&&suspension==null&&accepted!=null&&accepted.materialTransfers().isEmpty()
                &&island.allowance.acceptedIntervals()==0&&accepted.candidate().orElseThrow().acceptance()==PassiveStepSolver.Acceptance.FULL;
        if(!usable) {
            island.lastInterval=null;island.streak=0;island.evidence=null;if(revalidation!=null)island.certifiedSince=-1;
            island.refusal=!policy.enabled()?null:accepted==null?"no accepted interval":"not a FULL interval without material transfers or a degraded episode";return;
        }
        var summary=new IslandCertificate.Summary(new IslandCertificate.Interval(attempt.slice.startTick(),attempt.slice.endTick(),before,accepted.candidate().orElseThrow()),island.model.molecularWeights());
        if(revalidation!=null) {
            var measured=IslandCertificate.stationarity(revalidation.summary(),summary);
            island.evidence=new Evidence(summary.endTick,measured,summary.drift());
            String refusal=measured.refusal(policy.stationaryTolerance());
            if(refusal==null&&!noDrive(island))refusal="a module drive is due";
            if(refusal==null) {
                var issued=IslandCertificate.issue(summary,policy);
                if(issued.certificate()!=null){certify(island,issued.certificate());FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesRenewed);return;}
                refusal=issued.refusal();
            }
            island.certifiedSince=-1;island.lastInterval=summary;island.streak=1;island.refusal="revalidation: "+refusal;return;
        }
        var previous=island.lastInterval;
        var measured=previous==null||previous.endTick!=summary.startTick?null:IslandCertificate.stationarity(previous,summary);
        island.evidence=new Evidence(summary.endTick,measured,summary.drift());
        String refusal=previous==null?"first qualifying interval":measured==null?"not consecutive with the last one":measured.refusal(policy.stationaryTolerance());
        island.streak=refusal==null?island.streak+1:1;island.lastInterval=summary;
        int needed=summary.exactZero?policy.confirmIntervals():Math.max(2,policy.confirmIntervals());
        if(island.streak<needed){island.refusal=refusal!=null?refusal:"stationary for "+island.streak+" of "+needed+" intervals";return;}
        if(!noDrive(island)){island.refusal="a module drive is due";return;}
        var issued=IslandCertificate.issue(summary,policy);
        if(issued.certificate()!=null){island.certifiedSince=-1;certify(island,issued.certificate());}
        else island.refusal=issued.refusal();
    }
    private boolean noDrive(Island island) {
        long committed=island.clock.committedTick();
        return drives.earliest(island.id,committed,Math.addExact(committed,island.clock.snapshot().cadenceTicks()))==Long.MAX_VALUE;
    }
    private void certify(Island island,IslandCertificate certificate) {
        island.certificate=certificate;if(island.certifiedSince<0)island.certifiedSince=certificate.baseTick();
        island.signature=IslandCertificate.Signature.of(island.model,settings.certificates(),certificate.interval().result().graph());island.touch();
        island.lastInterval=null;island.streak=0;island.refusal=null;
        // Only here, after the terminal completion that produced the qualifying interval, so no worker owns the caches.
        island.retained=null;island.status=certifiedStatus(island);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesIssued);
    }
    private static String certifiedStatus(Island island) {
        var certificate=island.certificate;double since=island.certifiedSince/20.0;
        return certificate.kind()==IslandCertificate.Kind.REST?String.format(Locale.ROOT,"RESTING: no flow since %.1f s",since)
                :String.format(Locale.ROOT,"STEADY: replaying %.4g kg/s since %.1f s, next check at %.1f s",certificate.largestFlow(),since,certificate.horizonTick()/20.0);
    }
    private void schedule(long tick,IslandScheduler.Kind kind,long id,long generation) {
        scheduler.schedule(tick,kind,id,generation);
        // Stale entries are popped lazily; compact only if they come to dominate the heap.
        if(scheduler.size()>4*(islands.size()+rounds.size()+8))scheduler.compact(this::live);
    }
    private boolean live(IslandScheduler.Deadline entry) {
        return switch(entry.kind()) {
            case SLICE_DUE,RETRY,CERTIFICATE_HORIZON->{var island=islands.get(entry.id());yield island!=null&&island.generation==entry.generation();}
            case ROUND_TIMEOUT->rounds.stream().anyMatch(round->round.id==entry.id());
            case ALLOCATOR_SHRINK->shrinkTick==entry.tick();
            case MODULE_HORIZON,RECOVERY_RETRY->Objects.equals(externalTicks.get(entry.kind()),entry.tick());
            default->false;
        };
    }
    /** A round's first check comes when its budget would expire at the nominal tick length; a round that ticks
     * outran is checked again when its measured remainder would expire. */
    private void scheduleRoundTimeout(Round round,long remainingNanos) {
        long ticks=Math.max(1,(remainingNanos+NOMINAL_TICK_NANOS-1)/NOMINAL_TICK_NANOS);
        schedule(Math.addExact(epoch.getAsLong(),ticks),IslandScheduler.Kind.ROUND_TIMEOUT,round.id,0);
    }
    /** After each demand observation: the allocator's next shrink, if one is pending, becomes a deadline. */
    private void scheduleShrink() {
        long delay=dispatcher.demandShrinkDelay();
        long tick=delay<0?Long.MAX_VALUE:Math.addExact(epoch.getAsLong(),Math.max(1,delay));
        if(tick==shrinkTick)return;
        shrinkTick=tick;if(tick!=Long.MAX_VALUE)schedule(tick,IslandScheduler.Kind.ALLOCATOR_SHRINK,0,++generations);
    }
    private void index(Island island){for(var event:island.fences.keySet())fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(island.id);}
    private void indexStock(Island island){for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stockOwners.put(node.id(),island.id);}
    private void unindexStock(Island island){for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stockOwners.remove(node.id(),island.id);}
    private void unindex(Island island){for(var event:island.fences.keySet()){var owners=fenceOwners.get(event);if(owners!=null){owners.remove(island.id);if(owners.isEmpty())fenceOwners.remove(event);}}}
    private void removeFence(Island island,UUID event) {
        island.fences.remove(event);var owners=fenceOwners.get(event);
        if(owners!=null){owners.remove(island.id);if(owners.isEmpty())fenceOwners.remove(event);}
    }
    /** In verification runs, the incremental schedule must equal a from-scratch derivation for every island. */
    private void verify() {
        if(!VERIFY)return;
        for(var island:islands.values()) {
            boolean eligible=false;long due=Long.MAX_VALUE;
            if(island.certificate!=null) {
                if(island.pendingEntries!=0||island.retained!=null)throw new IllegalStateException("Certified island "+island.id+" owns an attempt or solver caches");
                if(!stopped&&suspension==null) {
                    long wake=certifiedWake(island);
                    if(wake!=Long.MAX_VALUE&&island.fence()>=wake)due=island.clock.epochTickAt(wake);
                }
            }
            else if(!stopped&&suspension==null&&island.pendingEntries==0) {
                long fence=island.fence();
                if(island.clock.nextSlice(1,fence,island.maximumSliceTicks).isPresent())eligible=true;
                else{long at=island.clock.readyAtTick(fence,island.maximumSliceTicks);if(at!=Long.MAX_VALUE)due=island.clock.epochTickAt(at);}
            }
            long owning=pending.values().stream().filter(p->p.attempt.islandId==island.id).count();
            if(island.ready!=eligible||ready.isReady(island.id)!=eligible||island.deadline!=due||owning!=island.pendingEntries)
                throw new IllegalStateException("Scheduler state diverged for island "+island.id+": ready="+island.ready+"/"+ready.isReady(island.id)+" expected "+eligible
                        +", deadline="+island.deadline+" expected "+due+", pending="+island.pendingEntries+" expected "+owning+", epoch="+epoch.getAsLong());
            for(var event:island.fences.keySet())if(!fenceOwners.getOrDefault(event,Set.of()).contains(island.id))throw new IllegalStateException("Fence index lost island "+island.id);
        }
        if(ready.readyCount()!=islands.values().stream().filter(i->i.ready).count())throw new IllegalStateException("Ready queue holds unknown owners");
        for(var entry:fenceOwners.entrySet())for(long id:entry.getValue()){var island=islands.get(id);if(island==null||!island.fences.containsKey(entry.getKey()))throw new IllegalStateException("Fence index holds a stale owner "+id);}
        var stock=new HashMap<Long,Long>();for(var island:islands.values())for(var node:island.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)stock.put(node.id(),island.id);
        if(!stock.equals(stockOwners))throw new IllegalStateException("Stock index diverged from the islands' reservoirs");
    }
}
