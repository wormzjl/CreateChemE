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
    public record Settings(long hardBudgetNanos, long softBudgetNanos, int maximumDispatchesPerTick, boolean adaptive,int initialCadenceTicks) {
        public Settings(long hardBudgetNanos,long softBudgetNanos,int maximumDispatchesPerTick,boolean adaptive){this(hardBudgetNanos,softBudgetNanos,maximumDispatchesPerTick,adaptive,100);}
        public Settings {
            if(hardBudgetNanos<=0||softBudgetNanos<=0||softBudgetNanos>=hardBudgetNanos||maximumDispatchesPerTick<1||initialCadenceTicks<20||initialCadenceTicks>400)
                throw new IllegalArgumentException("Invalid coordinator settings");
        }
        public static Settings defaults(){return new Settings(2_000_000_000L,1_500_000_000L,64,true);}
    }
    public record Attempt(long islandId,long revision,IslandClock.Slice slice) {
        public Attempt {if(islandId<=0||revision<0)throw new IllegalArgumentException("Invalid attempt identity");Objects.requireNonNull(slice);}
    }
    /** Queue debt and publication latency remain separate from the worker's own wall/CPU time. */
    public record Metrics(long sequence,long startTick,long endTick,long dispatchedAtTick,long publishedAtTick,
                          long workerNanos,long workerCpuNanos,long dispatchToPublicationNanos,boolean accepted) {}
    public record Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,
                           FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,
                           Optional<PassiveIntervalSolver.Result> lastResult,String status,Map<UUID,Long> fences) {
        public Snapshot(long id,long revision,PassiveNetwork graph,IslandClock.Snapshot clock,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,Optional<PassiveIntervalSolver.Result> lastResult,String status) {
            this(id,revision,graph,clock,allowance,anchor,lastResult,status,Map.of());
        }
        public Snapshot {
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandSnapshots);
            if(id<=0||revision<0)throw new IllegalArgumentException("Invalid island identity");
            Objects.requireNonNull(graph);Objects.requireNonNull(clock);Objects.requireNonNull(allowance);
            Objects.requireNonNull(anchor);Objects.requireNonNull(lastResult);Objects.requireNonNull(status);
            fences=Map.copyOf(fences);
            if(fences.values().stream().anyMatch(t->t<clock.committedTick()))throw new IllegalArgumentException("Persisted fence precedes state");
            if(allowance.acceptedIntervals()>0&&anchor.isEmpty())throw new IllegalArgumentException("Degraded state needs its episode anchor");
        }
    }
    /** Nominal online tick length; converts the wall-clock round budget into a first deadline. */
    private static final long NOMINAL_TICK_NANOS=50_000_000L;
    /** Test and GameTest runs set this to re-derive every island's readiness and deadline from scratch at each
     * pump and tick and fail on any difference from the incrementally maintained schedule. */
    private static final boolean VERIFY=Boolean.getBoolean("createcheme.fluid.scheduler.verify");
    private static final class Island {
        private final long id;
        private long revision;
        private final FluidThermodynamics model;
        private PassiveNetwork graph;
        private final IslandClock clock;
        private FallbackAllowance allowance;
        private Optional<ApproximationAnchor> anchor;
        private Optional<PassiveIntervalSolver.Result> lastResult;
        private String status;
        private Metrics metrics;
        private boolean suspended;
        // Ephemeral cost hint, not material state or a renewed fallback allowance. A restart
        // may rediscover the hint; saved inventory, cadence, debt and fences stay authoritative.
        private int maximumSliceTicks=Integer.MAX_VALUE;
        // Ephemeral solver caches for this island's jobs; replaced, never mutated, on a revision
        // bump, so a job still running under the old revision keeps its own handle.
        private RetainedSolver retained=new RetainedSolver();
        private final Map<UUID,Long> fences=new HashMap<>();
        // Scheduling state, never saved: attempts still owning this island (running, or closed but not yet
        // terminally drained), whether it is ready, and its one live deadline with that entry's generation.
        private int pendingEntries;
        private boolean ready;
        private long deadline=Long.MAX_VALUE,generation;
        private Island(Snapshot saved,FluidThermodynamics model,LongSupplier epoch) {
            id=saved.id;revision=saved.revision;this.model=Objects.requireNonNull(model);graph=saved.graph;
            clock=new IslandClock(saved.clock,epoch);allowance=saved.allowance;anchor=saved.anchor;lastResult=saved.lastResult;status=saved.status;fences.putAll(saved.fences);
        }
        private Snapshot snapshot(){return new Snapshot(id,revision,graph,clock.snapshot(),allowance,anchor,lastResult,
                !suspended&&!clock.busy()&&fence()==clock.committedTick()?"WAITING: event alignment":status,fences);}
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
    private record Round(long id,List<Pending> entries,long startedNanos) {}
    private final List<Round> rounds=new ArrayList<>();
    private long roundSequence,generations,shrinkTick=Long.MAX_VALUE;
    private final EnumMap<IslandScheduler.Kind,Runnable> externalActions=new EnumMap<>(IslandScheduler.Kind.class);
    private final EnumMap<IslandScheduler.Kind,Long> externalTicks=new EnumMap<>(IslandScheduler.Kind.class);
    private LongConsumer released=island->{};
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
        var island=new Island(saved,model,epoch);islands.put(saved.id,island);ready.register(saved.id,false);index(island);reconsider(island);
    }
    public Snapshot snapshot(long id){owned();return require(id).snapshot();}
    public Optional<Metrics> metrics(long id){owned();return Optional.ofNullable(require(id).metrics);}
    public List<Snapshot> snapshots(){owned();return islands.values().stream().map(Island::snapshot).toList();}
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
            island.revision=Math.addExact(island.revision,1);island.suspended=true;island.status=reason;island.retained=new RetainedSolver();
            island.anchor=island.anchor.map(a->new ApproximationAnchor("invalidated:property-reload",a.graph(),a.modes()));
            reconsider(island);
        }
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        publisher.published(snapshots());
    }
    /** Only the world property's compatibility guard may resume this pinned model. */
    public void resumeQualifiedProperties() {
        owned();if(stopped||suspension==null)return;suspension=null;
        for(var island:islands.values()){island.suspended=false;island.status="WAITING: full solve after property reload";island.clock.inputsChanged();reconsider(island);}
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
                case SLICE_DUE,RETRY->{
                    var island=islands.get(due.id());if(island==null||island.generation!=due.generation())continue;
                    FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.deadlinesFired);island.deadline=Long.MAX_VALUE;reconsider(island);
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
                var policy=island.anchor.isPresent()&&!com.wormzjl.createcheme.science.fluid.transport.SolidMobility.requiresFull(island.model,island.graph)?FluidFallbackPolicy.active(island.anchor.orElseThrow(),island.allowance,island.clock.snapshot().cadenceTicks(),settings.softBudgetNanos):FluidFallbackPolicy.disabled();
                var command=new ProcessSolveServices.FluidIslandCommand(island.model,island.graph,slice.seconds(),PassiveIntervalSolver.Settings.defaults(),settings.hardBudgetNanos,policy,island.retained);
                if(!dispatcher.submit(attempt,command)) {island.status="WAITING: shared worker capacity";break;}
                island.clock.admitted(slice);island.status="SOLVING";FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.solvesDispatched);
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
            var outcome=entry.result;
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
                    island.allowance=outcome.proposedAllowance();island.anchor=outcome.proposedAnchor();island.status=outcome.detail();
                }
            }
            if(!accept)island.status=refusal!=null?"HELD: "+refusal:outcome==null?"HELD: round deadline or worker failure":outcome.detail().startsWith("HELD")?outcome.detail():"HELD: "+outcome.detail();
            island.clock.completed(attempt.slice,accept);
            if(suspension!=null)island.status=suspension;
            if(suspension==null&&!accept&&(outcome==null||outcome.detail().startsWith("HELD: wall deadline"))) {
                island.maximumSliceTicks=Math.max(1,(int)((attempt.slice.endTick()-attempt.slice.startTick())/2));
            } else if(accept&&outcome.candidate().orElseThrow().acceptance()==PassiveStepSolver.Acceptance.FULL&&island.maximumSliceTicks!=Integer.MAX_VALUE) {
                int cadence=island.clock.snapshot().cadenceTicks();
                island.maximumSliceTicks=island.maximumSliceTicks>=cadence/2?Integer.MAX_VALUE:island.maximumSliceTicks*2;
            }
            island.metrics=new Metrics(island.metrics==null?1:Math.addExact(island.metrics.sequence(),1),attempt.slice.startTick(),attempt.slice.endTick(),entry.dispatchedAtTick,island.clock.snapshot().onlineTick(),outcome==null?-1:outcome.workerNanos(),outcome==null?-1:outcome.workerCpuNanos(),Math.max(0,nanoClock.getAsLong()-entry.admittedNanos),accept);
            if(settings.adaptive&&outcome!=null) {
                long cpu=outcome.workerCpuNanos(),wall=outcome.workerNanos();
                boolean cpuBound=cpu>=0&&wall>0&&cpu/(double)wall>=.75;
                island.clock.performanceSample(cpuBound&&wall>=settings.softBudgetNanos,accept&&cpu>=0&&wall<settings.softBudgetNanos/4);
            }
            entry.closed=true;
            if(entry.terminal){pending.remove(attempt.slice.requestId());island.pendingEntries--;}else dispatcher.cancel(attempt.slice.requestId());
            reconsider(island);
            changed.add(island.snapshot());
        }
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandsPublished,changed.size());
        publisher.published(List.copyOf(changed));
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
        for(long id:affected){var island=require(id);island.fences.put(event,tick);fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(id);island.clock.inputsChanged();reconsider(island);}
    }
    public boolean aligned(UUID event,Collection<Long> affected) {
        owned();return affected.stream().allMatch(id->{var i=require(id);var tick=i.fences.get(event);return tick!=null&&i.clock.committedTick()==tick&&i.pendingEntries==0;});
    }
    public void releaseFence(UUID event,Collection<Long> affected) {
        owned();if(!aligned(event,affected))throw new IllegalStateException("Event owners have not aligned");
        for(long id:affected){var i=require(id);removeFence(i,event);i.clock.inputsChanged();reconsider(i);}
    }
    /** Production has become known (including zero production). A lagging receiver need not
     * reach the promised time to resolve this dependency. The host separately retains a known
     * input-time boundary when needed, so it cannot inject into an already admitted interval. */
    public void resolveDeliveryFence(UUID event,Collection<Long> receivers) {
        owned();for(long id:receivers)if(!require(id).fences.containsKey(event))throw new IllegalStateException("Missing delivery horizon");
        for(long id:receivers){var island=require(id);removeFence(island,event);island.clock.inputsChanged();reconsider(island);}
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
        owned();Objects.requireNonNull(model);Objects.requireNonNull(metadataCommit);
        if(stopped||committed<0||online<committed||!aligned(event,affected))throw new IllegalStateException("Topology event is not ready");
        var originals=affected.stream().map(this::require).toList();
        long revision=0;int cadence=originals.isEmpty()?settings.initialCadenceTicks:20;
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var fences=new HashMap<UUID,Long>();
        var allowance=FallbackAllowance.NONE;Optional<ApproximationAnchor> anchor=Optional.empty();
        for(var old:originals) {
            if(old.clock.committedTick()!=committed||old.clock.snapshot().onlineTick()!=online||!ApproximationAnchor.revision(old.model).equals(ApproximationAnchor.revision(model)))throw new IllegalStateException("Incompatible clocks or property packages");
            revision=Math.max(revision,old.revision);cadence=Math.max(cadence,old.clock.snapshot().cadenceTicks());
            for(var node:old.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&stock.putIfAbsent(node.id(),node)!=null)throw new IllegalStateException("Duplicate stock ownership");
            for(var fence:old.fences.entrySet())if(!fence.getKey().equals(event)) {
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
            for(var other:islands.values())if(!affected.contains(other.id)&&other.graph.reservoirs().stream().anyMatch(n->n.id()==node.id()))throw new IllegalStateException("Constructed stock already exists");
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
            staged.add(new Island(new Snapshot(replacement.id,revision,replacement.graph,new IslandClock.Snapshot(online,committed,0,cadence),allowance,anchor,Optional.empty(),"WAITING: full solve after topology change",fences),model,epoch));
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
        for(long id:affected){var old=islands.remove(id);ready.remove(id);unindex(old);old.deadline=Long.MAX_VALUE;old.generation=++generations;}
        for(var island:staged){islands.put(island.id,island);ready.register(island.id,false);index(island);reconsider(island);}
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
        if(!stopped&&suspension==null&&island.pendingEntries==0) {
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
    private void schedule(long tick,IslandScheduler.Kind kind,long id,long generation) {
        scheduler.schedule(tick,kind,id,generation);
        // Stale entries are popped lazily; compact only if they come to dominate the heap.
        if(scheduler.size()>4*(islands.size()+rounds.size()+8))scheduler.compact(this::live);
    }
    private boolean live(IslandScheduler.Deadline entry) {
        return switch(entry.kind()) {
            case SLICE_DUE,RETRY->{var island=islands.get(entry.id());yield island!=null&&island.generation==entry.generation();}
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
            if(!stopped&&suspension==null&&island.pendingEntries==0) {
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
    }
}
