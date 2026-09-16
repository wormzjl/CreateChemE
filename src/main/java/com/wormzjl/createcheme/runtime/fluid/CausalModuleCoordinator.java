package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owner-thread host for the fixed-split qualification equipment. Scientific work stays in the
 * shared worker service through BufferedIslandCommand; ownership and horizons publish here.
 * Call advance before the first island pump, after publication, and after online ticks. */
public final class CausalModuleCoordinator {
    public record Binding(UUID buffer,long island,long reservoir) {
        /** island=0 retains an explicitly stranded buffer identity after its reservoir is removed. */
        public Binding {Objects.requireNonNull(buffer);if(island<0||reservoir<=0)throw new IllegalArgumentException("Invalid buffer binding");}
    }
    private final Thread owner=Thread.currentThread();
    private final FluidThermodynamics model;
    private Map<UUID,Binding> bindings;
    private final BufferedTransfers transfers;
    private final Map<UUID,FixedSplitModule> modules=new LinkedHashMap<>();
    private final double[] weights;
    private final EnergyReference reference;
    private IslandCoordinator islands;
    private boolean advancing;
    public CausalModuleCoordinator(FluidThermodynamics model,List<Binding> bindings,BufferedTransfers.Snapshot ledger,List<FixedSplitModule.Snapshot> saved) {
        this.model=Objects.requireNonNull(model);this.transfers=new BufferedTransfers(ledger);var map=new LinkedHashMap<UUID,Binding>();var nodes=new HashSet<String>();
        for(var binding:bindings)if(map.putIfAbsent(binding.buffer(),binding)!=null||!nodes.add(binding.island()+":"+binding.reservoir())||!ledger.buffers().containsKey(binding.buffer()))throw new IllegalArgumentException("Duplicate or unknown buffer binding");
        this.bindings=Map.copyOf(map);weights=model.molecularWeights();
        reference=EnergyReference.sensible(model.components());
        for(var snapshot:saved) {
            var d=snapshot.definition();binding(d.firstProduct());binding(d.secondProduct());for(var feed:d.feeds())binding(feed.buffer());
            if(modules.putIfAbsent(d.id(),new FixedSplitModule(snapshot,transfers,BufferBackpressure.defaults()))!=null)throw new IllegalArgumentException("Duplicate module");
        }
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Module coordinator belongs to the logical server thread");}
    public void attach(IslandCoordinator coordinator) {
        owned();if(islands!=null)throw new IllegalStateException("Module coordinator already attached");islands=Objects.requireNonNull(coordinator);
        for(var binding:bindings.values())if(binding.island()>0)node(islands.snapshot(binding.island()).graph(),binding.reservoir());
    }
    public BufferedTransfers.Snapshot transfers(){owned();return transfers.snapshot();}
    public List<FixedSplitModule.Snapshot> snapshots(){owned();return modules.values().stream().map(FixedSplitModule::snapshot).toList();}
    public List<Binding> bindings(){owned();return List.copyOf(bindings.values());}
    private boolean stranded(FixedSplitModule.Definition definition) {
        return products(definition).contains(0L)||definition.feeds().stream().anyMatch(f->binding(f.buffer()).island()==0);
    }
    /** Called after a conservative topology publication. Removed targets retain their identity,
     * pending products, promises, and module holdup; no stock is redirected to a replacement. */
    public void rebind(Map<Long,Long> reservoirOwners) {
        owned();var changed=new HashMap<UUID,Binding>();var removed=new HashMap<UUID,Double>();boolean any=false;
        for(var old:bindings.values()) {
            long owner=reservoirOwners.getOrDefault(old.reservoir(),0L);var next=new Binding(old.buffer(),owner,old.reservoir());changed.put(next.buffer(),next);any|=!next.equals(old);
            if(owner==0&&old.island()!=0)removed.put(old.buffer(),0.0);
        }
        if(!any)return;
        var occupancy=removed.isEmpty()?null:transfers.occupancy(removed);
        if(occupancy!=null)transfers.commit(occupancy);bindings=Map.copyOf(changed);
    }
    public String waitingReason(long island) {
        owned();for(var module:modules.values()) {
            var s=module.snapshot();boolean associated=feeds(s.definition()).contains(island)||products(s.definition()).contains(island);
            if(associated&&stranded(s.definition()))return "STRANDED: module "+s.definition().id()+" retains owned material for a removed buffer";
            if(s.cycle()!=null&&products(s.definition()).contains(island)&&islands.snapshot(island).clock().committedTick()==s.cycle().endTick()&&!s.cycle().knownZero()&&!s.cycle().inputsComplete())return "WAITING: module "+s.definition().id()+" feed withdrawals through tick "+s.cycle().endTick();
        }
        return "";
    }
    private void resolveStranded(FixedSplitModule.Snapshot saved) {
        var ids=new HashSet<UUID>();ids.add(horizon(saved.definition().id(),saved.committedTick(),"start"));
        if(saved.cycle()!=null){var c=saved.cycle();ids.add(horizon(saved.definition().id(),c.startTick(),"feed"));ids.add(horizon(saved.definition().id(),c.startTick(),"product"));ids.add(horizon(saved.definition().id(),c.endTick(),"product"));}
        for(var island:islands.snapshots())for(var event:ids)if(island.fences().containsKey(event))islands.resolveDeliveryFence(event,Set.of(island.id()));
    }
    private Binding binding(UUID id){return Objects.requireNonNull(bindings.get(id),"Missing buffer binding "+id);}
    private static PassiveNetwork.Reservoir node(PassiveNetwork graph,long id){return graph.reservoirs().stream().filter(n->n.id()==id&&n.kind()==PassiveNetwork.NodeKind.RESERVOIR).findFirst().orElseThrow(()->new IllegalStateException("Missing finite buffer reservoir "+id));}
    private MaterialParcel parcel(PassiveNetwork.Reservoir node){var inventory=node.inventory();double mass=0;var n=inventory.moles();for(int c=0;c<n.length;c++)mass+=n[c]*weights[c];return new MaterialParcel(n,weights,inventory.internalEnergy()+mass*PassiveStepSolver.GRAVITY*node.elevation(),reference);}
    private static UUID horizon(UUID module,long start,String role){return UUID.nameUUIDFromBytes((module+":"+start+":"+role).getBytes(StandardCharsets.UTF_8));}
    private Set<Long> feeds(FixedSplitModule.Definition d){var ids=new HashSet<Long>();for(var feed:d.feeds())ids.add(binding(feed.buffer()).island());return ids;}
    private Set<Long> products(FixedSplitModule.Definition d){var ids=new HashSet<Long>();if(Arrays.stream(d.firstFractions()).anyMatch(f->f>0))ids.add(binding(d.firstProduct()).island());if(Arrays.stream(d.firstFractions()).anyMatch(f->f<1))ids.add(binding(d.secondProduct()).island());return ids;}
    private void ensureFence(UUID id,long tick,Set<Long> owners){for(long owner:owners)if(!islands.snapshot(owner).fences().containsKey(id))islands.fence(id,tick,List.of(owner));}
    private void productionHorizon(FixedSplitModule.Definition definition,FixedSplitModule.Cycle cycle,Set<Long> receivers) {
        var current=horizon(definition.id(),cycle.startTick(),"product");
        if(!cycle.knownZero()){ensureFence(current,cycle.endTick(),receivers);return;}
        // Zero is known for this interval. The next interval is still unknown: announce that
        // later horizon before releasing this one, even if the producer's feed is lagging.
        ensureFence(horizon(definition.id(),cycle.endTick(),"product"),Math.addExact(cycle.endTick(),definition.cadenceTicks()),receivers);
        for(long receiver:receivers)if(islands.snapshot(receiver).fences().containsKey(current))islands.resolveDeliveryFence(current,Set.of(receiver));
    }

    public void advance() {
        owned();if(advancing)return;advancing=true;
        try {
            for(var module:modules.values()) {
                var saved=module.snapshot();var d=saved.definition();var feedOwners=feeds(d);var productOwners=products(d);
                if(stranded(d)){resolveStranded(saved);continue;}
                if(saved.cycle()!=null) {
                    var c=saved.cycle();var feedHorizon=horizon(d.id(),c.startTick(),"feed");var productHorizon=horizon(d.id(),c.startTick(),"product");
                    ensureFence(feedHorizon,c.endTick(),feedOwners);productionHorizon(d,c,productOwners);
                    if(!c.inputsComplete()||!islands.aligned(feedHorizon,feedOwners))continue;
                    long online=feedOwners.stream().mapToLong(id->islands.snapshot(id).clock().onlineTick()).min().orElseThrow();
                    module.commit(module.finish(online));islands.releaseFence(feedHorizon,feedOwners);
                    if(!c.knownZero())islands.resolveDeliveryFence(productHorizon,productOwners);
                    saved=module.snapshot();
                }
                var startHorizon=horizon(d.id(),saved.committedTick(),"start");ensureFence(startHorizon,saved.committedTick(),feedOwners);
                if(!islands.aligned(startHorizon,feedOwners))continue;
                var observations=new HashMap<UUID,FixedSplitModule.Observation>();
                for(var feed:d.feeds()){var binding=binding(feed.buffer());var island=islands.snapshot(binding.island());observations.put(feed.buffer(),new FixedSplitModule.Observation(island.clock().committedTick(),parcel(node(island.graph(),binding.reservoir()))));}
                var prepared=module.begin(observations);var c=prepared.after().cycle();
                // Fences are checked before consuming capacity or publishing a new module interval.
                ensureFence(horizon(d.id(),c.startTick(),"feed"),c.endTick(),feedOwners);
                productionHorizon(d,c,productOwners);
                module.commit(prepared);islands.releaseFence(startHorizon,feedOwners);
            }
            // A known but deferred input creates no unresolved-production wait. A future due time
            // is still an integration boundary until that receiver reaches it.
            for(var pending:transfers.snapshot().pending().values()) {
                long receiver=binding(pending.receiver()).island();var event=horizon(pending.id(),pending.dueTick(),"input");
                if(receiver==0){for(var snapshot:islands.snapshots())if(snapshot.fences().containsKey(event))islands.resolveDeliveryFence(event,Set.of(snapshot.id()));continue;}
                var snapshot=islands.snapshot(receiver);
                if(snapshot.clock().committedTick()<pending.dueTick())ensureFence(event,pending.dueTick(),Set.of(receiver));
                else if(snapshot.fences().containsKey(event)&&islands.aligned(event,Set.of(receiver)))islands.releaseFence(event,Set.of(receiver));
            }
        } finally {advancing=false;}
    }

    /** Command advisor passed to MinecraftFluidRuntime; no live world or ledger reaches a worker. */
    public ProcessSolveServices.FluidSolveCommand command(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand original) {
        owned();var inputs=new ArrayList<ModuleTransferPlanner.Input>();var withdrawals=new ArrayList<ModuleTransferPlanner.Withdrawal>();long start=attempt.slice().startTick(),end=attempt.slice().endTick();
        transfers.snapshot().pending().values().stream().filter(p->binding(p.receiver()).island()==attempt.islandId()&&p.dueTick()<=start)
                .sorted(Comparator.comparingLong(PendingTransfers.Pending::dueTick).thenComparing(p->p.id().toString())).limit(64)
                .forEach(p->inputs.add(new ModuleTransferPlanner.Input(p.id(),binding(p.receiver()).reservoir(),p.dueTick(),p.remaining(),p.remaining().massKg())));
        for(var module:modules.values())if(module.snapshot().cycle()!=null&&!stranded(module.snapshot().definition())) {
            var feedBuffers=new HashSet<UUID>();for(var feed:module.snapshot().definition().feeds())if(binding(feed.buffer()).island()==attempt.islandId())feedBuffers.add(feed.buffer());
            if(!feedBuffers.isEmpty())for(var withdrawal:module.withdrawals(start,end,feedBuffers))withdrawals.add(new ModuleTransferPlanner.Withdrawal(withdrawal.id(),binding(withdrawal.buffer()).reservoir(),withdrawal.maximumKg()));
        }
        if(inputs.isEmpty()&&withdrawals.isEmpty())return original;
        var current=islands.snapshot(attempt.islandId());
        return new ProcessSolveServices.BufferedIslandCommand(model,original.snapshot(),start,Math.toIntExact(end-start),inputs,withdrawals,original.wallBudgetNanos(),current.allowance(),current.anchor());
    }

    /** Preflight the complete ledger/module update before the island coordinator publishes its graph. */
    public Optional<Runnable> prepare(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandSolveResult result) {
        owned();if(result.candidate().isEmpty())return Optional.empty();var before=transfers.snapshot();var staged=new BufferedTransfers(before);var physical=result.candidate().orElseThrow();
        var delivered=result.materialTransfers().map(ModuleTransferPlanner.Proposal::delivered).orElse(Map.of());
        var withdrawn=result.materialTransfers().map(ModuleTransferPlanner.Proposal::withdrawn).orElse(Map.of());
        var portions=new ArrayList<BufferedTransfers.Feasible>();
        for(var entry:delivered.entrySet()) {
            var record=Objects.requireNonNull(before.pending().get(entry.getKey()),"Unknown staged input");
            if(binding(record.receiver()).island()!=attempt.islandId()||record.dueTick()>attempt.slice().startTick())throw new IllegalStateException("Future or misdirected input");
            var expected=record.remaining().takeMass(entry.getValue().massKg()).delivered();sameMaterial(expected,entry.getValue());portions.add(new BufferedTransfers.Feasible(record.id(),record.revision(),entry.getValue().massKg()));
        }
        staged.commit(staged.deliver(attempt.slice().startTick(),portions));
        var occupancy=new HashMap<UUID,Double>();for(var binding:bindings.values())if(binding.island()==attempt.islandId())occupancy.put(binding.buffer(),parcel(node(physical.graph(),binding.reservoir())).massKg());
        staged.commit(staged.occupancy(occupancy));var ledger=transfers.combine(before,staged.snapshot());
        var receipts=new LinkedHashMap<FixedSplitModule,FixedSplitModule.Prepared>();var recognized=new HashSet<UUID>();
        for(var module:modules.values())if(module.snapshot().cycle()!=null) {
            if(stranded(module.snapshot().definition())&&module.snapshot().cycle().inputs().values().stream().noneMatch(i->withdrawn.containsKey(i.withdrawalId())))continue;
            var feedBuffers=new HashSet<UUID>();for(var feed:module.snapshot().definition().feeds())if(binding(feed.buffer()).island()==attempt.islandId())feedBuffers.add(feed.buffer());
            if(feedBuffers.isEmpty())continue;var group=new ArrayList<FixedSplitModule.Receipt>();
            for(var request:module.withdrawals(attempt.slice().startTick(),attempt.slice().endTick(),feedBuffers)) {
                recognized.add(request.id());var actual=withdrawn.getOrDefault(request.id(),new MaterialParcel(new double[weights.length],weights,0,reference));
                group.add(new FixedSplitModule.Receipt(request.id(),attempt.slice().startTick(),attempt.slice().endTick(),actual));
            }
            if(!group.isEmpty())receipts.put(module,module.receive(group));
        }
        if(!recognized.containsAll(withdrawn.keySet()))throw new IllegalStateException("Unowned staged withdrawal");
        transfers.validate(ledger);receipts.forEach(FixedSplitModule::validate);
        return Optional.of(()->{transfers.commit(ledger);receipts.forEach(FixedSplitModule::commit);});
    }
    private static void sameMaterial(MaterialParcel expected,MaterialParcel actual) {
        expected.takeMass(0).delivered().plus(actual.takeMass(0).delivered());var a=expected.moles();var b=actual.moles();
        for(int c=0;c<a.length;c++)if(Math.abs(a[c]-b[c])>1e-10*Math.max(1,a[c]))throw new IllegalStateException("Staged input changed composition");
        if(Math.abs(expected.energyJoule()-actual.energyJoule())>1e-9*Math.max(1,Math.abs(expected.energyJoule())))throw new IllegalStateException("Staged input changed energy");
    }
}
