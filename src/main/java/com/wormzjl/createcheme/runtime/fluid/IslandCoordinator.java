package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Server-thread authority over island clocks and atomic result publication. Workers receive only commands.
 * A round contains at most the available dispatch capacity; waiting owners retain duration, not queued snapshots.
 * The dispatcher must deliver terminal callbacks asynchronously on this coordinator's owning thread.
 */
public final class IslandCoordinator {
    public interface Dispatcher {
        default void demand(int eligibleOwners) {}
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
            if(id<=0||revision<0)throw new IllegalArgumentException("Invalid island identity");
            Objects.requireNonNull(graph);Objects.requireNonNull(clock);Objects.requireNonNull(allowance);
            Objects.requireNonNull(anchor);Objects.requireNonNull(lastResult);Objects.requireNonNull(status);
            fences=Map.copyOf(fences);
            if(fences.values().stream().anyMatch(t->t<clock.committedTick()))throw new IllegalArgumentException("Persisted fence precedes state");
            if(allowance.acceptedIntervals()>0&&anchor.isEmpty())throw new IllegalArgumentException("Degraded state needs its episode anchor");
        }
    }
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
        private Island(Snapshot saved,FluidThermodynamics model) {
            id=saved.id;revision=saved.revision;this.model=Objects.requireNonNull(model);graph=saved.graph;
            clock=new IslandClock(saved.clock);allowance=saved.allowance;anchor=saved.anchor;lastResult=saved.lastResult;status=saved.status;fences.putAll(saved.fences);
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
    private final Map<Long,Island> islands=new LinkedHashMap<>();
    private final Map<Long,Pending> pending=new HashMap<>();
    private final FairIslandQueue ready=new FairIslandQueue();
    private record Round(List<Pending> entries,long startedNanos) {}
    private final List<Round> rounds=new ArrayList<>();
    private int dispatchedThisTick;
    private boolean stopped,pumping;
    private String suspension;

    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings) {
        this(dispatcher,publisher,nanoClock,settings,CommitHook.NO_MATERIAL);
    }
    public IslandCoordinator(Dispatcher dispatcher,Publisher publisher,LongSupplier nanoClock,Settings settings,CommitHook commitHook) {
        this.dispatcher=Objects.requireNonNull(dispatcher);this.publisher=Objects.requireNonNull(publisher);
        this.nanoClock=Objects.requireNonNull(nanoClock);this.settings=Objects.requireNonNull(settings);this.commitHook=Objects.requireNonNull(commitHook);
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Island coordinator belongs to its server thread");}
    public void register(Snapshot saved,FluidThermodynamics model) {
        owned();if(stopped||islands.containsKey(saved.id))throw new IllegalStateException("Stopped/duplicate island");
        if(saved.graph.reservoirs().stream().anyMatch(n->n.kind()==PassiveNetwork.NodeKind.PORT))throw new IllegalArgumentException("Internal ports cannot own world state");
        islands.put(saved.id,new Island(saved,model));ready.register(saved.id);
    }
    public Snapshot snapshot(long id){owned();return require(id).snapshot();}
    public Optional<Metrics> metrics(long id){owned();return Optional.ofNullable(require(id).metrics);}
    public List<Snapshot> snapshots(){owned();return islands.values().stream().map(Island::snapshot).toList();}
    public int pendingCount(){owned();return pending.size();}

    /** Invalidates old proposals without changing committed stock, debt, fences or allowance.
     * Cancellation retains ownership until the actual terminal completion is drained. */
    public void suspendForPropertyChange(String reason) {
        owned();Objects.requireNonNull(reason);if(stopped||suspension!=null)return;
        suspension=reason;dispatcher.demand(0);
        for(var island:islands.values()) {
            island.revision=Math.addExact(island.revision,1);island.suspended=true;island.status=reason;island.retained=new RetainedSolver();
            island.anchor=island.anchor.map(a->new ApproximationAnchor("invalidated:property-reload",a.graph(),a.modes()));
        }
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
        publisher.published(snapshots());
    }
    /** Only the world property's compatibility guard may resume this pinned model. */
    public void resumeQualifiedProperties() {
        owned();if(stopped||suspension==null)return;suspension=null;
        for(var island:islands.values()){island.suspended=false;island.status="WAITING: full solve after property reload";island.clock.inputsChanged();}
        publisher.published(snapshots());
    }

    /** Called once per elapsed server tick, never from wall time or for time spent offline. */
    public void tick() {
        owned();if(stopped)return;dispatchedThisTick=0;
        for(var island:islands.values())island.clock.accrueOnlineTicks(1);
        pump();
    }
    /** May also run after terminal draining, allowing catch-up between ordinary tick boundaries. */
    public void pump() {
        owned();if(stopped||pumping)return;pumping=true;
        try {
            if(suspension!=null){dispatcher.demand(0);return;}
            for(var round:List.copyOf(rounds)) {
                if(round.entries.stream().allMatch(p->p.terminal)||nanoClock.getAsLong()-round.startedNanos>=settings.hardBudgetNanos) {
                    rounds.remove(round);closeRound(round);
                }
            }
            int eligible=0;for(var island:islands.values())if(!hasPending(island.id)&&island.clock.nextSlice(1,island.fence(),island.maximumSliceTicks).isPresent())eligible++;
            dispatcher.demand(eligible);
            // Completed results waiting at another group's barrier retain a bounded staging slot.
            int capacity=Math.min(dispatcher.availableWorkers(),Math.min(settings.maximumDispatchesPerTick-dispatchedThisTick,settings.maximumDispatchesPerTick-pending.size()));
            if(capacity<=0)return;
            var admitted=new ArrayList<Pending>();long roundStarted=nanoClock.getAsLong();
            for(int slot=0;slot<capacity;slot++) {
                var next=ready.nextReady(id->{var i=require(id);return !hasPending(id)&&i.clock.nextSlice(1,i.fence(),i.maximumSliceTicks).isPresent();});
                if(next.isEmpty())break;
                var island=require(next.getAsLong());long request=dispatcher.nextRequestId();
                var slice=island.clock.nextSlice(request,island.fence(),island.maximumSliceTicks).orElseThrow();
                var attempt=new Attempt(island.id,island.revision,slice);
                var policy=island.anchor.isPresent()&&!com.wormzjl.createcheme.science.fluid.transport.SolidMobility.requiresFull(island.model,island.graph)?FluidFallbackPolicy.active(island.anchor.orElseThrow(),island.allowance,island.clock.snapshot().cadenceTicks(),settings.softBudgetNanos):FluidFallbackPolicy.disabled();
                var command=new ProcessSolveServices.FluidIslandCommand(island.model,island.graph,slice.seconds(),PassiveIntervalSolver.Settings.defaults(),settings.hardBudgetNanos,policy,island.retained);
                if(!dispatcher.submit(attempt,command)) {island.status="WAITING: shared worker capacity";break;}
                island.clock.admitted(slice);island.status="SOLVING";
                var entry=new Pending(attempt,island.clock.snapshot().onlineTick(),nanoClock.getAsLong());pending.put(request,entry);admitted.add(entry);dispatchedThisTick++;
            }
            if(!admitted.isEmpty())rounds.add(new Round(List.copyOf(admitted),roundStarted));
        } finally {pumping=false;}
    }
    /** A missing result denotes cancellation, failure or abandonment. Duplicate/late results never advance clocks. */
    public void completed(Attempt attempt,Optional<ProcessSolveServices.FluidIslandSolveResult> result) {
        owned();Objects.requireNonNull(result);var entry=pending.get(attempt.slice.requestId());
        if(entry==null||!entry.attempt.equals(attempt)||entry.terminal)return;
        entry.terminal=true;entry.result=nanoClock.getAsLong()-entry.admittedNanos>=settings.hardBudgetNanos?null:result.orElse(null);
        if(entry.closed){pending.remove(attempt.slice.requestId());return;}
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
            if(entry.terminal)pending.remove(attempt.slice.requestId());else dispatcher.cancel(attempt.slice.requestId());
            changed.add(island.snapshot());
        }
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
        for(long id:affected){var island=require(id);island.fences.put(event,tick);island.clock.inputsChanged();}
    }
    public boolean aligned(UUID event,Collection<Long> affected) {
        owned();return affected.stream().allMatch(id->{var i=require(id);var tick=i.fences.get(event);return tick!=null&&i.clock.committedTick()==tick&&!hasPending(id);});
    }
    public void releaseFence(UUID event,Collection<Long> affected) {
        owned();if(!aligned(event,affected))throw new IllegalStateException("Event owners have not aligned");
        for(long id:affected){var i=require(id);i.fences.remove(event);i.clock.inputsChanged();}
    }
    /** Production has become known (including zero production). A lagging receiver need not
     * reach the promised time to resolve this dependency. The host separately retains a known
     * input-time boundary when needed, so it cannot inject into an already admitted interval. */
    public void resolveDeliveryFence(UUID event,Collection<Long> receivers) {
        owned();for(long id:receivers)if(!require(id).fences.containsKey(event))throw new IllegalStateException("Missing delivery horizon");
        for(long id:receivers){var island=require(id);island.fences.remove(event);island.clock.inputsChanged();}
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
            staged.add(new Island(new Snapshot(replacement.id,revision,replacement.graph,new IslandClock.Snapshot(online,committed,0,cadence),allowance,anchor,Optional.empty(),"WAITING: full solve after topology change",fences),model));
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
        for(long id:affected){islands.remove(id);ready.remove(id);}
        for(var island:staged){islands.put(island.id,island);ready.register(island.id);}
        publisher.published(staged.stream().map(Island::snapshot).toList());
    }
    /** Stops admission; unresolved proposals are held. Actual execution capacity is owned by the shared pool. */
    public void stop() {
        owned();if(stopped)return;stopped=true;
        var closing=List.copyOf(rounds);rounds.clear();
        for(var round:closing){for(var entry:round.entries)entry.result=null;closeRound(round);}
    }
    private boolean hasPending(long id){return pending.values().stream().anyMatch(p->p.attempt.islandId==id);}
    private Island require(long id){var value=islands.get(id);if(value==null)throw new IllegalArgumentException("Unknown island "+id);return value;}
}
