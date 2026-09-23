package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import java.util.*;

/**
 * The physical registry's compiled form and its topology events, apart from the server: which island owns each
 * registered device, what the compiler said about each device, which devices sit in which chunk, and the path that
 * queues an edit as a ledger event and later applies it to the islands. {@link FluidWorldAuthority} owns one and
 * supplies the world through {@link Host}; a test supplies an {@link IslandCoordinator} of its own.
 */
public final class PhysicalRegistry {
    /** What the registry needs from its world. Called on the owning thread only. */
    public interface Host {
        IslandCoordinator coordinator();
        /** Replaces the affected islands (see {@link IslandCoordinator#topology}); {@code commit} runs inside, once the change is validated. */
        void topology(String dimension,UUID event,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,
                      Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit);
        /** Whether a recovery's player can take its item now (online, with a free slot). */
        boolean acceptsRecovery(UUID player);
        /** An applied event did nothing for the player who asked; the reason reaches them with the reply to their input. */
        void refused(UUID event,String reason);
        /** An application committed; {@code active} is the registry after it. */
        void committed(Map<Long,WorldTopologyLedger.Registration> active);
    }
    private record ChunkKey(String dimension,long chunk) {}
    private final WorldTopologyLedger topology;
    private final FluidThermodynamics model;
    private final double[] weights;
    private final InlineFilter emptyFilter;
    private final Host host;
    private PhysicalFluidTopology.Compiled compiled;
    private Map<Long,Long> owners=new HashMap<>();
    private Map<Long,List<Long>> members=Map.of();
    private Map<ChunkKey,List<Long>> chunkMembers=Map.of();
    private Map<Long,WorldTopologyLedger.Registration> latest;
    private Map<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration> positions;

    /** {@code emptyFilter} is a new filter's cake: the configured capacity and resistance, nothing captured. */
    public PhysicalRegistry(WorldTopologyLedger topology,FluidThermodynamics model,InlineFilter emptyFilter,Host host) {
        this.topology=Objects.requireNonNull(topology);this.model=Objects.requireNonNull(model);weights=model.molecularWeights();
        this.emptyFilter=Objects.requireNonNull(emptyFilter);this.host=Objects.requireNonNull(host);
    }
    /** Compiles the loaded registry and binds every device to the island a checkpoint restored for it. */
    public void load() {
        compiled=PhysicalFluidTopology.compile(topology.snapshot().active().values().stream().map(WorldTopologyLedger.Registration::device).toList(),boundaries(),filterStock(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        rebuildOwners();validateOwnership();chunkMembers=chunkIndex(topology.active());
    }

    // ---- reads ----

    /** Every registration with the queued events applied: what the world has been told, applied or not. */
    public Map<Long,WorldTopologyLedger.Registration> registrations(){if(latest==null)latest=topology.latest();return latest;}
    /** The registration at a position, from an index rebuilt with {@link #registrations()}. */
    public Optional<WorldTopologyLedger.Registration> at(PhysicalFluidTopology.Position position) {
        var registered=registrations();
        if(positions==null){var index=new HashMap<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration>();for(var r:registered.values())index.put(r.device().position(),r);positions=index;}
        return Optional.ofNullable(positions.get(position));
    }
    private void registrationsChanged(){latest=null;positions=null;}
    /** The island that owns a device, or null. */
    public Long owner(long device){return owners.get(device);}
    /** Device to owning island; replaced by every application, so identity tells a new ownership. */
    public Map<Long,Long> owners(){return owners;}
    /** The devices an island owns. */
    public List<Long> members(long island){return members.getOrDefault(island,List.of());}
    /** What the compiler said about a device (a dead end, no boundary, an invalid pump), or null. */
    public String diagnostic(long device){return compiled.diagnostics().get(device);}
    /** The compiled connections a device's view reports. */
    public List<PhysicalFluidTopology.View> pipeViews(long device){return compiled.pipeViews().getOrDefault(device,List.of());}
    /** The applied devices in one chunk. */
    public List<Long> chunk(String dimension,long chunk){return chunkMembers.getOrDefault(new ChunkKey(dimension,chunk),List.of());}
    @FunctionalInterface public interface ChunkVisitor{void visit(String dimension,long chunk,List<Long> devices);}
    /** Every chunk that holds an applied device, with its devices. */
    public void forEachChunk(ChunkVisitor visitor){chunkMembers.forEach((key,ids)->visitor.visit(key.dimension(),key.chunk(),ids));}
    /** Captured solids of every filter: the islands' own, and an empty cake for a filter no island holds yet. */
    public Map<Long,InlineFilter> filterStock(){
        var result=new HashMap<Long,InlineFilter>();for(var island:host.coordinator().snapshots())for(var pipe:island.graph().pipes())if(pipe.filter()!=null)result.put(pipe.id()-Long.MIN_VALUE,pipe.filter());
        for(var r:registrations().values())if(r.device().kind()==TopologyCompiler.Kind.FILTER)result.putIfAbsent(r.device().id(),emptyFilter);return result;
    }
    private Map<Long,PassiveNetwork.Reservoir> boundaries() {
        var result=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(var island:host.coordinator().snapshots())for(var node:island.graph().reservoirs())if(!node.junction()&&node.id()>0)result.put(node.id(),node);
        return result;
    }

    // ---- events ----

    /** Queues edits as one ledger event, fenced on the islands it touches; {@link #applyPending()} applies it. */
    public WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId,WorldTopologyLedger.Recovery recovery) {
        var prospective=new HashMap<>(registrations());var starts=new HashSet<PhysicalFluidTopology.Position>();var touched=new HashSet<Long>();
        for(var edit:edits) {
            var old=prospective.get(edit.id());if(old!=null)starts.add(old.device().position());touched.add(edit.id());
            if(edit.replacement()==null)prospective.remove(edit.id());
            else {
                var record=edit.replacement();starts.add(record.device().position());prospective.put(edit.id(),record);
                if(record.device().boundary()&&(old==null||!record.spec().equals(old.spec()))){var initialized=record.spec().initialize(record.device(),model,()->{});MaterialRuntime.active().solids().validate(initialized.inventory().solids());}
            }
        }
        var positions=new HashMap<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration>();for(var r:prospective.values())positions.put(r.device().position(),r);
        var queue=new ArrayDeque<PhysicalFluidTopology.Position>(starts);var seen=new HashSet<PhysicalFluidTopology.Position>();
        // Include both sides of a removed link, then traverse actual connection faces in the proposed registry.
        for(var start:starts)for(var d:PhysicalFluidTopology.Direction.values())queue.add(start.offset(d));
        while(!queue.isEmpty()) {
            var position=queue.removeFirst();if(!seen.add(position))continue;var record=positions.get(position);if(record==null)continue;touched.add(record.device().id());
            for(var d:PhysicalFluidTopology.Direction.values()) {
                var neighbor=positions.get(position.offset(d));if(neighbor!=null&&record.device().connects(d)&&neighbor.device().connects(d)&&!(record.device().boundary()&&neighbor.device().boundary()))queue.add(neighbor.device().position());
            }
        }
        var prepared=topology.queue(edits,touched,nextId,recovery);var affected=new HashSet<Long>();for(long id:touched)if(owners.containsKey(id))affected.add(owners.get(id));
        host.coordinator().fence(prepared.event().id(),prepared.event().tick(),affected);topology.commit(prepared);registrationsChanged();
        return prepared.event();
    }
    /** Applies ready events whose owners are aligned, at most sixteen; returns whether one queued a solid recovery. */
    public boolean applyPending() {
        var coordinator=host.coordinator();boolean queuedRecovery=false;
        for(int count=0;count<16;count++) {
            // No full physical-registry snapshot is constructed here: ready events come from the ledger's
            // queue, their owners from the coordinator's fence index, and state from the live ledger.
            // Snapshot validation remains at actual capture/transactions.
            if(!topology.hasPendingEvents())break;
            var selectedEvent=nextReadyEvent();if(selectedEvent==null)break;
            var event=selectedEvent.event();var affected=selectedEvent.affected();var registered=topology.active();
            var cakes=filterStock();RecoveredSolid recovered=null;
            if(event.recovery()!=null){var request=event.recovery();var cake=cakes.get(request.filterId());var record=registered.get(request.filterId());
                if(record!=null&&cake!=null&&!cake.captured().empty()&&(request.player()==null||host.acceptsRecovery(request.player()))){
                    recovered=new RecoveredSolid(record.device().position(),request.player(),cake.captured(),cake.energyJoule());cakes.put(request.filterId(),cake.cleared());
                // The event still applies (its revision is spent); the refusal reaches the player with the reply to
                // the recovery input on its menu's next bucket, never as a message pushed from here.
                }else if(request.player()!=null)host.refused(event.id(),"Filter unchanged: inventory full or no captured solids");
            }
            var active=new HashMap<>(registered);var stock=boundaries();var additions=new HashMap<Long,PassiveNetwork.Reservoir>();var removed=new HashMap<Long,PassiveNetwork.Reservoir>();
            var selected=new HashSet<Long>(event.touched());for(var entry:owners.entrySet())if(affected.contains(entry.getValue()))selected.add(entry.getKey());
            for(var edit:event.edits()) {
                var old=active.get(edit.id());var replacement=edit.replacement();
                if(replacement==null) {active.remove(edit.id());var previous=stock.remove(edit.id());if(previous!=null&&previous.kind()==PassiveNetwork.NodeKind.RESERVOIR)removed.put(edit.id(),previous);}
                else {
                    active.put(edit.id(),replacement);
                    if(replacement.device().boundary()&&(old==null||!old.spec().equals(replacement.spec()))) {
                        var initialized=replacement.spec().initialize(replacement.device(),model,()->{});stock.put(edit.id(),initialized);
                        if(initialized.kind()==PassiveNetwork.NodeKind.RESERVOIR)additions.put(edit.id(),initialized);
                    }
                }
            }
            var nextCompiled=PhysicalFluidTopology.compile(active.values().stream().map(WorldTopologyLedger.Registration::device).toList(),stock,cakes,model.initialNitrogenCharge(1,298.15,101325,()->{}));
            var replacements=new ArrayList<IslandCoordinator.Replacement>();var nextOwners=new HashMap<>(owners);nextOwners.entrySet().removeIf(e->affected.contains(e.getValue())||!active.containsKey(e.getKey()));
            long nextId=topology.nextIdentity();String dimension=null;
            for(var island:nextCompiled.islands())if(island.physicalIds().stream().anyMatch(selected::contains)) {
                long id=nextId++;replacements.add(new IslandCoordinator.Replacement(id,island.graph()));
                for(long physical:island.physicalIds()){nextOwners.put(physical,id);dimension=active.get(physical).device().position().dimension();}
            }
            if(dimension==null){var edit=event.edits().getFirst();var record=edit.replacement()!=null?edit.replacement():registered.get(edit.id());dimension=record.device().position().dimension();}
            var preparation=topology.applyReady(event.id(),additions.values(),removed.values(),weights,nextId);
            var prepared=recovered==null?preparation:topology.withRecovery(preparation,recovered);
            var nextMembers=inverse(nextOwners);
            var nextChunks=chunkIndex(active);
            host.topology(dimension,event.id(),affected,replacements,event.tick(),topology.onlineTick(),additions,removed.keySet(),recovered==null?Map.of():Map.of(PhysicalFluidTopology.filterIdentity(event.recovery().filterId()),filterStock().get(event.recovery().filterId())),()->{
                topology.commit(prepared);compiled=nextCompiled;owners=nextOwners;members=nextMembers;chunkMembers=nextChunks;registrationsChanged();
                host.committed(active);
            });
            for(var future:topology.events())for(var replacement:replacements) {
                boolean touches=future.touched().stream().anyMatch(id->Objects.equals(owners.get(id),replacement.id()));
                if(touches&&!coordinator.hasFence(replacement.id(),future.id()))coordinator.fence(future.id(),future.tick(),List.of(replacement.id()));
            }
            queuedRecovery|=recovered!=null;
        }
        return queuedRecovery;
    }
    private record ReadyEvent(WorldTopologyLedger.Event event,Set<Long> affected) {}
    /** Owners of an event: islands already fenced for it (from the coordinator's fence index) and the current
     * owners of the identities it touches. No island snapshot is built. */
    private ReadyEvent nextReadyEvent() {
        var coordinator=host.coordinator();
        for(var event:topology.readyEvents()) {
            var affected=new HashSet<Long>(coordinator.fencedIslands(event.id()));
            for(long id:event.touched())if(owners.containsKey(id))affected.add(owners.get(id));
            for(long id:affected)if(!coordinator.hasFence(id,event.id()))coordinator.fence(event.id(),event.tick(),List.of(id));
            if(coordinator.aligned(event.id(),affected))return new ReadyEvent(event,Set.copyOf(affected));
        }
        return null;
    }
    private void rebuildOwners() {
        var byBoundary=new HashMap<Long,Long>();for(var island:host.coordinator().snapshots())for(var node:island.graph().reservoirs())if(!node.junction()&&node.id()>0)byBoundary.put(node.id(),island.id());
        var byFilter=new HashMap<Long,Long>();for(var island:host.coordinator().snapshots())for(var pipe:island.graph().pipes())if(pipe.filter()!=null)byFilter.put(pipe.id(),island.id());
        for(var island:compiled.islands()) {
            var ids=java.util.stream.Stream.concat(island.graph().reservoirs().stream().filter(n->!n.junction()).map(n->byBoundary.get(n.id())),island.graph().pipes().stream().filter(p->p.filter()!=null).map(p->byFilter.get(p.id()))).filter(Objects::nonNull).distinct().toList();
            if(ids.size()!=1)throw new IllegalStateException("Saved topology and hydraulic ownership disagree");for(long id:island.physicalIds())owners.put(id,ids.getFirst());
        }
        members=inverse(owners);
    }
    private void validateOwnership() {
        var registered=new HashSet<Long>();for(var r:topology.snapshot().active().values())if(r.device().kind()==TopologyCompiler.Kind.RESERVOIR)registered.add(r.device().id());
        var owned=new HashSet<Long>();for(var s:host.coordinator().snapshots())for(var n:s.graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)owned.add(n.id());
        if(!registered.equals(owned))throw new IllegalStateException("Physical registry would lose or duplicate saved reservoir ownership");
    }
    private static Map<Long,List<Long>> inverse(Map<Long,Long> owners) {
        var result=new HashMap<Long,List<Long>>();owners.forEach((physical,island)->result.computeIfAbsent(island,ignored->new ArrayList<>()).add(physical));
        result.replaceAll((id,values)->List.copyOf(values));return Map.copyOf(result);
    }
    private static Map<ChunkKey,List<Long>> chunkIndex(Map<Long,WorldTopologyLedger.Registration> active) {
        var result=new HashMap<ChunkKey,List<Long>>();
        for(var record:active.values()){var p=record.device().position();var key=new ChunkKey(p.dimension(),chunkOf(p));result.computeIfAbsent(key,ignored->new ArrayList<>()).add(record.device().id());}
        result.replaceAll((key,ids)->List.copyOf(ids));return Map.copyOf(result);
    }
    /** The packed chunk position of a block position (Minecraft's {@code ChunkPos.asLong}). */
    static long chunkOf(PhysicalFluidTopology.Position p){return (long)(p.x()>>4)&4294967295L|((long)(p.z()>>4)&4294967295L)<<32;}
}
