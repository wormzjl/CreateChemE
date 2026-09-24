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
 *
 * <p>Cost. An event costs time in the part of the registry it touches, never in the size of the world:
 * <ul>
 * <li>{@link #submit} walks from the edited position through the devices no island owns and no queued event edits, and
 * stops at the first device an island owns (that island is fenced whole) or a queued event edits (that event orders this
 * one). So the event's touched set is its neighbourhood, plus any ownerless stretch of pipe it reaches.</li>
 * <li>{@link #applyPending} applies the queued events in batches: a batch is every queued event of one tick and one
 * dimension whose owners are aligned, in queue order, each one meeting no earlier event left out of the batch. The
 * batch compiles only the connected components (after the batch) of the devices it touched and of the islands it
 * replaces, reads only those islands, and keeps the compiled form of the rest of the registry. A command that places
 * thousands of devices therefore compiles each component it builds once.</li>
 * </ul>
 * The compiled form is kept per device (diagnostics, pipe views, owner), so replacing a component replaces exactly the
 * entries of its devices. An island no event touched is never read, recompiled, materialised or replaced: a certified one
 * keeps its certificate and its saved payload.
 */
public final class PhysicalRegistry {
    /** What the registry needs from its world. Called on the owning thread only. */
    public interface Host {
        IslandCoordinator coordinator();
        /** Replaces the affected islands for a batch of events (see {@link IslandCoordinator#topology}); {@code commit}
         * runs inside, once the change is validated. */
        void topology(String dimension,Set<UUID> events,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,
                      Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit);
        /** Whether a recovery's player can take its item now (online, with a free slot). */
        boolean acceptsRecovery(UUID player);
        /** An applied event did nothing for the player who asked; the reason reaches them with the reply to their input. */
        void refused(UUID event,String reason);
        /** An application committed; {@code removed} are the devices it took out of the registry. */
        void committed(Set<Long> removed);
    }
    private record ChunkKey(String dimension,long chunk) {}
    private final WorldTopologyLedger topology;
    private final FluidThermodynamics model;
    private final double[] weights;
    private final InlineFilter emptyFilter;
    private final Host host;
    private FluidThermodynamics.State idleSeed;
    private final Map<Long,String> diagnostics=new HashMap<>();
    private final Map<Long,List<PhysicalFluidTopology.View>> pipeViews=new HashMap<>();
    private final Map<Long,Long> owners=new HashMap<>();
    private final Map<Long,Long> ownersView=Collections.unmodifiableMap(owners);
    private long ownersVersion;
    private final Map<Long,List<Long>> members=new HashMap<>();
    private final Map<ChunkKey,Set<Long>> chunkMembers=new HashMap<>();
    private int unapplied;

    /** {@code emptyFilter} is a new filter's cake: the configured capacity and resistance, nothing captured. */
    public PhysicalRegistry(WorldTopologyLedger topology,FluidThermodynamics model,InlineFilter emptyFilter,Host host) {
        this.topology=Objects.requireNonNull(topology);this.model=Objects.requireNonNull(model);weights=model.molecularWeights();
        this.emptyFilter=Objects.requireNonNull(emptyFilter);this.host=Objects.requireNonNull(host);
    }
    /** The zero-flow property seed of a disconnected filter's ports; the same state every compile used. */
    private FluidThermodynamics.State idleSeed(){if(idleSeed==null)idleSeed=model.initialNitrogenCharge(1,298.15,101325,()->{});return idleSeed;}
    /** Compiles the loaded registry once and binds every device to the island a checkpoint restored for it. */
    public void load() {
        var islands=host.coordinator().snapshots();
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var cakes=new HashMap<Long,InlineFilter>();var byBoundary=new HashMap<Long,Long>();var byFilter=new HashMap<Long,Long>();
        for(var island:islands) {
            for(var node:island.graph().reservoirs())if(!node.junction()&&node.id()>0){stock.put(node.id(),node);byBoundary.put(node.id(),island.id());}
            for(var pipe:island.graph().pipes())if(pipe.filter()!=null){cakes.put(pipe.id()-Long.MIN_VALUE,pipe.filter());byFilter.put(pipe.id(),island.id());}
        }
        for(var r:topology.latest().values())if(r.device().kind()==TopologyCompiler.Kind.FILTER)cakes.putIfAbsent(r.device().id(),emptyFilter);
        var active=topology.active();
        var compiled=PhysicalFluidTopology.compile(active.values().stream().map(WorldTopologyLedger.Registration::device).toList(),stock,cakes,idleSeed());
        diagnostics.putAll(compiled.diagnostics());pipeViews.putAll(compiled.pipeViews());
        for(var island:compiled.islands()) {
            var ids=java.util.stream.Stream.concat(island.graph().reservoirs().stream().filter(n->!n.junction()).map(n->byBoundary.get(n.id())),island.graph().pipes().stream().filter(p->p.filter()!=null).map(p->byFilter.get(p.id()))).filter(Objects::nonNull).distinct().toList();
            if(ids.size()!=1)throw new IllegalStateException("Saved topology and hydraulic ownership disagree");for(long id:island.physicalIds())owners.put(id,ids.getFirst());
        }
        var byIsland=new HashMap<Long,List<Long>>();owners.forEach((device,island)->byIsland.computeIfAbsent(island,ignored->new ArrayList<>()).add(device));
        byIsland.forEach((island,devices)->{devices.sort(null);members.put(island,List.copyOf(devices));});ownersVersion++;
        var registered=new HashSet<Long>();for(var r:active.values())if(r.device().kind()==TopologyCompiler.Kind.RESERVOIR)registered.add(r.device().id());
        var owned=new HashSet<Long>();for(var s:islands)for(var n:s.graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)owned.add(n.id());
        if(!registered.equals(owned))throw new IllegalStateException("Physical registry would lose or duplicate saved reservoir ownership");
        for(var r:active.values())chunkAdd(r);
    }

    // ---- reads ----

    /** Every registration with the queued events applied: what the world has been told, applied or not. A live view. */
    public Map<Long,WorldTopologyLedger.Registration> registrations(){return topology.latest();}
    /** The registration at a position, queued events applied. */
    public Optional<WorldTopologyLedger.Registration> at(PhysicalFluidTopology.Position position){return Optional.ofNullable(topology.latestAt(position));}
    /** The island that owns a device, or null. */
    public Long owner(long device){return owners.get(device);}
    /** Device to owning island: a live, unmodifiable view; {@link #ownersVersion()} changes with every application. */
    public Map<Long,Long> owners(){return ownersView;}
    public long ownersVersion(){return ownersVersion;}
    /** The devices an island owns, in identity order. */
    public List<Long> members(long island){return members.getOrDefault(island,List.of());}
    /** What the compiler said about a device (a dead end, no boundary, an invalid pump), or null. */
    public String diagnostic(long device){return diagnostics.get(device);}
    /** The compiled connections a device's view reports. */
    public List<PhysicalFluidTopology.View> pipeViews(long device){return pipeViews.getOrDefault(device,List.of());}
    /** The applied devices in one chunk. */
    public Collection<Long> chunk(String dimension,long chunk){var ids=chunkMembers.get(new ChunkKey(dimension,chunk));return ids==null?List.of():Collections.unmodifiableSet(ids);}
    @FunctionalInterface public interface ChunkVisitor{void visit(String dimension,long chunk,Collection<Long> devices);}
    /** Every chunk that holds an applied device, with its devices. */
    public void forEachChunk(ChunkVisitor visitor){chunkMembers.forEach((key,ids)->visitor.visit(key.dimension(),key.chunk(),Collections.unmodifiableSet(ids)));}
    /** A filter's captured solids: its island's, or an empty cake for a registered filter no island holds yet; null otherwise. */
    public InlineFilter cake(long filter) {
        Long island=owners.get(filter);
        if(island!=null)for(var pipe:host.coordinator().snapshot(island).graph().pipes())if(pipe.filter()!=null&&pipe.id()==PhysicalFluidTopology.filterIdentity(filter))return pipe.filter();
        var record=topology.latest().get(filter);return record!=null&&record.device().kind()==TopologyCompiler.Kind.FILTER?emptyFilter:null;
    }
    /** Events queued since the last {@link #applyPending()}. */
    public int unapplied(){return unapplied;}

    // ---- events ----

    /**
     * Queues edits as one ledger event and fences it on the islands it touches; {@link #applyPending()} applies it. The
     * touched set is the edited devices, every device beside an edited position, and every device reached from them
     * through applied devices that no island owns and no queued event edits (a dead-end branch, a line with no tank). An
     * owned device stands for its island, which the fence covers whole, and a queued device for the event that edits it,
     * which this event now follows; the walk stops at both, so its cost is the neighbourhood, not the island.
     */
    public WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId,WorldTopologyLedger.Recovery recovery) {
        var latest=topology.latest();var proposed=new HashMap<Long,Optional<WorldTopologyLedger.Registration>>();var proposedAt=new HashMap<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration>();
        var starts=new LinkedHashSet<PhysicalFluidTopology.Position>();var touched=new HashSet<Long>();
        for(var edit:edits) {
            var change=proposed.get(edit.id());var old=change!=null?change.orElse(null):latest.get(edit.id());if(old!=null){starts.add(old.device().position());proposedAt.remove(old.device().position(),old);}
            touched.add(edit.id());
            if(edit.replacement()==null)proposed.put(edit.id(),Optional.empty());
            else {
                var record=edit.replacement();starts.add(record.device().position());proposed.put(edit.id(),Optional.of(record));proposedAt.put(record.device().position(),record);
                if(record.device().boundary()&&(old==null||!record.spec().equals(old.spec()))){var initialized=record.spec().initialize(record.device(),model,()->{});MaterialRuntime.active().solids().validate(initialized.inventory().solids());}
            }
        }
        // The registry this event proposes: the queued one with these edits on top.
        java.util.function.Function<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration> at=p->{
            var mine=proposedAt.get(p);if(mine!=null)return mine;var record=topology.latestAt(p);return record==null||proposed.containsKey(record.device().id())?null:record;
        };
        var queue=new ArrayDeque<PhysicalFluidTopology.Position>(starts);var seen=new HashSet<PhysicalFluidTopology.Position>();
        // Both sides of a removed link are visited, then connections are followed in the proposed registry.
        for(var start:starts)for(var d:PhysicalFluidTopology.Direction.values())queue.add(start.offset(d));
        while(!queue.isEmpty()) {
            var position=queue.removeFirst();if(!seen.add(position))continue;var record=at.apply(position);if(record==null)continue;long id=record.device().id();touched.add(id);
            if(!starts.contains(position)&&(owners.containsKey(id)||topology.pending(id)))continue;
            for(var d:PhysicalFluidTopology.Direction.values()) {
                var neighbor=at.apply(position.offset(d));if(neighbor!=null&&record.device().connects(d)&&neighbor.device().connects(d)&&!(record.device().boundary()&&neighbor.device().boundary()))queue.add(neighbor.device().position());
            }
        }
        var prepared=topology.queue(edits,touched,nextId,recovery);var affected=new HashSet<Long>();for(long id:touched){Long owner=owners.get(id);if(owner!=null)affected.add(owner);}
        host.coordinator().fence(prepared.event().id(),prepared.event().tick(),affected);topology.commit(prepared);unapplied++;
        return prepared.event();
    }
    /** Applies what is ready, at most sixteen batches (see the class comment); returns whether one queued a solid recovery. */
    public boolean applyPending() {
        unapplied=0;boolean queuedRecovery=false;
        for(int count=0;count<16&&topology.hasPendingEvents();count++){var batch=nextBatch();if(batch==null)break;queuedRecovery|=apply(batch);}
        return queuedRecovery;
    }
    private record Batch(List<WorldTopologyLedger.Event> events,Set<Long> affected,String dimension,long tick) {}
    /**
     * The next batch, or null: in queue order, every event of the first applicable tick and dimension that meets no
     * earlier event left out and whose owners (the islands fenced for it, and the owners of what it touches) are
     * aligned at its tick. A filter recovery is applied alone. No island snapshot is built here.
     */
    private Batch nextBatch() {
        var coordinator=host.coordinator();var taken=new ArrayList<WorldTopologyLedger.Event>();var affected=new HashSet<Long>();String dimension=null;long tick=-1;
        var blockedIds=new HashSet<Long>();var blockedPositions=new HashSet<PhysicalFluidTopology.Position>();
        for(var footprint:topology.footprints()) {
            var event=footprint.event();if(tick>=0&&event.tick()>tick)break;
            String eventDimension=footprint.positions().iterator().next().dimension();
            boolean take=!footprint.meets(blockedIds,blockedPositions)&&(dimension==null||dimension.equals(eventDimension))&&(event.recovery()==null||taken.isEmpty());
            Set<Long> owners=take?owners(event):Set.of();
            if(take)take=coordinator.aligned(event.id(),owners);
            if(!take){blockedIds.addAll(footprint.ids());blockedPositions.addAll(footprint.positions());continue;}
            taken.add(event);affected.addAll(owners);dimension=eventDimension;tick=event.tick();
            if(event.recovery()!=null)break;
        }
        return taken.isEmpty()?null:new Batch(List.copyOf(taken),Set.copyOf(affected),dimension,tick);
    }
    /** An event's owners: islands already fenced for it (the coordinator's fence index) and the current owners of the
     * identities it touches; a missing fence is installed. */
    private Set<Long> owners(WorldTopologyLedger.Event event) {
        var coordinator=host.coordinator();var affected=new HashSet<Long>(coordinator.fencedIslands(event.id()));
        for(long id:event.touched()){Long owner=owners.get(id);if(owner!=null)affected.add(owner);}
        for(long id:affected)if(!coordinator.hasFence(id,event.id()))coordinator.fence(event.id(),event.tick(),List.of(id));
        return affected;
    }
    private static WorldTopologyLedger.Registration current(Map<Long,Optional<WorldTopologyLedger.Registration>> changed,Map<Long,WorldTopologyLedger.Registration> active,long id) {
        var change=changed.get(id);return change!=null?change.orElse(null):active.get(id);
    }
    /** Applies one batch; returns whether it queued a solid recovery. */
    private boolean apply(Batch batch) {
        var coordinator=host.coordinator();var events=batch.events();var affected=batch.affected();var active=topology.active();
        // What the affected islands hold - boundary stock and filter cakes - read once each, already aligned.
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var cakes=new HashMap<Long,InlineFilter>();
        for(long island:affected) {
            var graph=coordinator.snapshot(island).graph();
            for(var node:graph.reservoirs())if(!node.junction()&&node.id()>0)stock.put(node.id(),node);
            for(var pipe:graph.pipes())if(pipe.filter()!=null)cakes.put(pipe.id()-Long.MIN_VALUE,pipe.filter());
        }
        RecoveredSolid recovered=null;InlineFilter released=null;long recoveredFilter=0;
        for(var event:events)if(event.recovery()!=null) {
            var request=event.recovery();var cake=cakes.get(request.filterId());var record=active.get(request.filterId());
            if(record!=null&&cake!=null&&!cake.captured().empty()&&(request.player()==null||host.acceptsRecovery(request.player()))){
                recovered=new RecoveredSolid(record.device().position(),request.player(),cake.captured(),cake.energyJoule());released=cake;recoveredFilter=request.filterId();cakes.put(request.filterId(),cake.cleared());
            // The event still applies (its revision is spent); the refusal reaches the player with the reply to
            // the recovery input on its menu's next bucket, never as a message pushed from here.
            }else if(request.player()!=null)host.refused(event.id(),"Filter unchanged: inventory full or no captured solids");
        }
        // The registry after the batch, as changes over the applied one. The ledger books every construction and
        // destruction in order; the islands see only the net change (a tank placed and broken in one batch is neither).
        var changed=new LinkedHashMap<Long,Optional<WorldTopologyLedger.Registration>>();
        var additions=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();var removed=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        var constructed=new ArrayList<PassiveNetwork.Reservoir>();var destroyed=new ArrayList<PassiveNetwork.Reservoir>();
        for(var event:events)for(var edit:event.edits()) {
            var old=current(changed,active,edit.id());var replacement=edit.replacement();
            if(replacement==null) {
                changed.put(edit.id(),Optional.empty());var previous=stock.remove(edit.id());
                if(previous!=null&&previous.kind()==PassiveNetwork.NodeKind.RESERVOIR){destroyed.add(previous);if(additions.remove(edit.id())==null)removed.put(edit.id(),previous);}
            } else {
                changed.put(edit.id(),Optional.of(replacement));
                if(replacement.device().boundary()&&(old==null||!old.spec().equals(replacement.spec()))) {
                    var initialized=replacement.spec().initialize(replacement.device(),model,()->{});stock.put(edit.id(),initialized);
                    if(initialized.kind()==PassiveNetwork.NodeKind.RESERVOIR){constructed.add(initialized);additions.put(edit.id(),initialized);}
                }
            }
        }
        var changedAt=new HashMap<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration>();for(var change:changed.values())change.ifPresent(r->changedAt.put(r.device().position(),r));
        java.util.function.Function<PhysicalFluidTopology.Position,WorldTopologyLedger.Registration> at=p->{
            var mine=changedAt.get(p);if(mine!=null)return mine;var record=topology.activeAt(p);return record==null||changed.containsKey(record.device().id())?null:record;
        };
        // The connected components, after the batch, of every device the events touched and every device of the islands
        // they replace: the only part of the registry the batch can change.
        var selected=new HashSet<Long>();for(var event:events)selected.addAll(event.touched());for(long island:affected)selected.addAll(members(island));
        var component=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();var queue=new ArrayDeque<WorldTopologyLedger.Registration>();
        for(long id:selected){var record=current(changed,active,id);if(record!=null&&component.putIfAbsent(id,record)==null)queue.add(record);}
        while(!queue.isEmpty()) {
            var record=queue.removeFirst();
            for(var d:PhysicalFluidTopology.Direction.values()) {
                if(!record.device().connects(d))continue;var neighbor=at.apply(record.device().position().offset(d));
                if(neighbor!=null&&neighbor.device().connects(d)&&!(record.device().boundary()&&neighbor.device().boundary())&&component.putIfAbsent(neighbor.device().id(),neighbor)==null)queue.add(neighbor);
            }
        }
        var componentStock=new HashMap<Long,PassiveNetwork.Reservoir>();var componentCakes=new HashMap<Long,InlineFilter>();
        for(var record:component.values()) {
            long id=record.device().id();
            if(record.device().boundary()){var state=stock.get(id);if(state==null)throw new IllegalStateException("Topology event reached boundary "+id+" outside the islands it fenced");componentStock.put(id,state);}
            if(record.device().kind()==TopologyCompiler.Kind.FILTER) {
                var cake=cakes.get(id);
                if(cake==null){if(active.containsKey(id))throw new IllegalStateException("Topology event reached filter "+id+" outside the islands it fenced");cake=emptyFilter;}
                componentCakes.put(id,cake);
            }
        }
        var compiled=PhysicalFluidTopology.compile(component.values().stream().map(WorldTopologyLedger.Registration::device).toList(),componentStock,componentCakes,idleSeed());
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.topologyBatches);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.topologyEvents,events.size());
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.compiledDevices,component.size());
        var replacements=new ArrayList<IslandCoordinator.Replacement>();var replacementMembers=new LinkedHashMap<Long,List<Long>>();long nextId=topology.nextIdentity();
        for(var island:compiled.islands())if(island.physicalIds().stream().anyMatch(selected::contains)) {
            long id=nextId++;replacements.add(new IslandCoordinator.Replacement(id,island.graph()));replacementMembers.put(id,island.physicalIds().stream().sorted().toList());
        }
        var eventIds=new LinkedHashSet<UUID>();for(var event:events)eventIds.add(event.id());
        var preparation=topology.applyBatch(List.copyOf(eventIds),constructed,destroyed,weights,nextId);
        var prepared=recovered==null?preparation:topology.withRecovery(preparation,recovered);
        var removedDevices=new HashSet<Long>();var removedRecords=new ArrayList<WorldTopologyLedger.Registration>();var addedRecords=new ArrayList<WorldTopologyLedger.Registration>();
        for(var entry:changed.entrySet()){var before=active.get(entry.getKey());if(entry.getValue().isEmpty()){if(before!=null){removedDevices.add(entry.getKey());removedRecords.add(before);}}else if(before==null)addedRecords.add(entry.getValue().orElseThrow());}
        host.topology(batch.dimension(),eventIds,affected,replacements,batch.tick(),topology.onlineTick(),additions,removed.keySet(),recovered==null?Map.of():Map.of(PhysicalFluidTopology.filterIdentity(recoveredFilter),released),()->{
            topology.commit(prepared);
            for(long id:component.keySet()){diagnostics.remove(id);pipeViews.remove(id);}
            for(long id:removedDevices){diagnostics.remove(id);pipeViews.remove(id);owners.remove(id);}
            diagnostics.putAll(compiled.diagnostics());pipeViews.putAll(compiled.pipeViews());
            for(long island:affected){var devices=members.remove(island);if(devices!=null)for(long device:devices)owners.remove(device,island);}
            replacementMembers.forEach((island,devices)->{members.put(island,devices);for(long device:devices)owners.put(device,island);});ownersVersion++;
            for(var record:removedRecords)chunkRemove(record);for(var record:addedRecords)chunkAdd(record);
            host.committed(Set.copyOf(removedDevices));
        });
        // A queued event that touches a replacement island's devices is fenced on it now: it will change that island.
        if(!replacements.isEmpty()) {
            var fresh=new HashSet<Long>();for(var replacement:replacements)fresh.add(replacement.id());
            for(var future:topology.events())for(long id:future.touched()){Long owner=owners.get(id);if(owner!=null&&fresh.contains(owner)&&!coordinator.hasFence(owner,future.id()))coordinator.fence(future.id(),future.tick(),List.of(owner));}
        }
        return recovered!=null;
    }
    private void chunkAdd(WorldTopologyLedger.Registration record){var p=record.device().position();chunkMembers.computeIfAbsent(new ChunkKey(p.dimension(),chunkOf(p)),ignored->new LinkedHashSet<>()).add(record.device().id());}
    private void chunkRemove(WorldTopologyLedger.Registration record){var p=record.device().position();var key=new ChunkKey(p.dimension(),chunkOf(p));var ids=chunkMembers.get(key);if(ids!=null&&ids.remove(record.device().id())&&ids.isEmpty())chunkMembers.remove(key);}
    /** The packed chunk position of a block position (Minecraft's {@code ChunkPos.asLong}). */
    static long chunkOf(PhysicalFluidTopology.Position p){return (long)(p.x()>>4)&4294967295L|((long)(p.z()>>4)&4294967295L)<<32;}
}
