package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import java.util.*;

/** Server-owned physical registry and hydraulic authority. Only publication inspects loaded block entities. */
public final class FluidWorldAuthority implements AutoCloseable {
    private static final Map<MinecraftServer,FluidWorldAuthority> SERVERS=new IdentityHashMap<>();
    private final MinecraftServer server;
    private final MaterialCatalog catalog;
    private final Map<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models=new HashMap<>();
    private final FluidThermodynamics model;
    private final FluidPropertyReloadGuard propertyReload;
    private String propertyHold;
    private final CreateChemE.FluidOptions options;
    private final double compressibility;
    private final List<FluidPresetCatalog.Preset> presetCache;
    private final List<String> componentNames;
    private final FluidSavedData data;
    private final WorldTopologyLedger topology;
    private final BufferedTransfers transfers;
    private final MinecraftFluidRuntime runtime;
    private final CausalModuleCoordinator moduleHost;
    private final boolean legacyUnbound;
    private final double[] weights;
    private PhysicalFluidTopology.Compiled compiled;
    private Map<Long,Long> owners=new HashMap<>();
    /** The owners map the module host was last rebound to; a topology commit replaces {@link #owners}. */
    private Map<Long,Long> boundOwners;
    /** How long an undeliverable recovery (offline player, full inventory, unloaded chunk) waits for its next attempt. */
    private static final int RECOVERY_RETRY_TICKS=20;
    private Map<Long,List<Long>> members=Map.of();
    private record ChunkKey(String dimension,long chunk) {}
    private Map<ChunkKey,List<Long>> chunkMembers=Map.of();
    private final Set<Long> unboundBindings=new HashSet<>();
    private final ArrayDeque<Long> pendingViews=new ArrayDeque<>();
    private final Set<Long> queuedViews=new HashSet<>();
    private Map<Long,WorldTopologyLedger.Registration> latest;
    private boolean closed;
    private long lastDebugChat=Long.MIN_VALUE;
    private MaterialCatalog observedSolidData;
    private String solidPropertyHold;
    private java.util.function.BiConsumer<List<IslandCoordinator.Snapshot>,Map<Long,IslandCoordinator.Metrics>> observer;

    private FluidWorldAuthority(MinecraftServer server) {
        this.server=server;owned();catalog=MaterialRuntime.active();
        options=CreateChemE.fluidOptions();data=FluidSavedData.open(server,this::model);
        compressibility=data.checkpoint().islands().isEmpty()?options.compressibility():data.checkpoint().islands().getFirst().compressibility();
        model=model(new FluidCheckpointCodec.PackageKey(com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),compressibility));
        propertyReload=new FluidPropertyReloadGuard(catalog,com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),compressibility,model);
        presetCache=FluidPresetCatalog.resolve(catalog);componentNames=model.components();
        legacyUnbound=data.world().isEmpty()&&!data.checkpoint().islands().isEmpty();
        var savedTopology=data.world().orElseGet(()->WorldTopologyLedger.Snapshot.empty(catalog));savedTopology.basis().requireCurrent(catalog);
        SolidCompatibility.validate(catalog.solids(),data.checkpoint(),savedTopology);observedSolidData=catalog;
        topology=new WorldTopologyLedger(savedTopology);transfers=new BufferedTransfers(data.checkpoint().transfers());
        weights=model.molecularWeights();
        moduleHost=data.checkpoint().modules().isEmpty()||legacyUnbound?null:new CausalModuleCoordinator(model,data.checkpoint().moduleBindings(),data.checkpoint().transfers(),data.checkpoint().modules());
        var settings=new IslandCoordinator.Settings(options.wallBudgetNanos(),options.wallBudgetNanos()*3/4,64,options.adaptiveCadence(),options.initialCadenceTicks(),options.certificates());
        // Island clocks read the world's online tick: one epoch increment per tick advances every island.
        runtime=moduleHost==null?new MinecraftFluidRuntime(server,this::published,settings,(attempt,command)->command,IslandCoordinator.CommitHook.NO_MATERIAL,topology::onlineTick)
                :new MinecraftFluidRuntime(server,this::published,settings,moduleHost::command,moduleHost::prepare,topology::onlineTick);
        runtime.coordinator().onReleased(this::released);runtime.coordinator().onReplayed(this::replayed);
        if(legacyUnbound) {
            CreateChemE.LOGGER.error("fluid_world status=LEGACY_UNBOUND detail=Core inventories preserved; physical bindings are unavailable in this older checkpoint");return;
        }
        for(var entry:data.checkpoint().islands())runtime.register(dimension(entry.dimension()),entry.snapshot(),model(new FluidCheckpointCodec.PackageKey(entry.packageId(),entry.compressibility())));
        var stock=boundaries();compiled=PhysicalFluidTopology.compile(topology.snapshot().active().values().stream().map(WorldTopologyLedger.Registration::device).toList(),stock,filterStock(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        rebuildOwners();validateOwnership();chunkMembers=chunkIndex(topology.active());for(long id:topology.active().keySet())queueView(id);data.bindCapture(this::capture);
        if(moduleHost!=null){moduleHost.attach(runtime.coordinator());advanceModules();}
        // Saved recoveries are first attempted on the first tick, as before, then on their retry deadline.
        if(topology.hasRecoveries())scheduleRecoveryRetry(1);
    }
    public static void start(MinecraftServer server){if(SERVERS.containsKey(server))throw new IllegalStateException("Duplicate fluid world authority");SERVERS.put(server,new FluidWorldAuthority(server));}
    public static Optional<FluidWorldAuthority> find(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Fluid world lookup requires server thread");return Optional.ofNullable(SERVERS.get(server));}
    public static void tick(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::tick);}
    /** Also called before between-tick completion routing, so an old worker cannot win a reload race. */
    public static void refreshProperties(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::refreshProperties);}
    private void refreshProperties() {
        owned();if(closed||legacyUnbound)return;
        var current=MaterialRuntime.active();String next=propertyReload.inspect(current).orElse(null);
        if(current!=observedSolidData){observedSolidData=current;try{var saved=capture();SolidCompatibility.validate(current.solids(),saved.checkpoint(),saved.world());solidPropertyHold=null;}catch(IllegalArgumentException incompatible){solidPropertyHold="HELD: solid property data changed; restore qualified definitions or explicitly migrate saved solids";}}
        if(next==null)next=solidPropertyHold;
        if(Objects.equals(next,propertyHold))return;
        propertyHold=next;
        if(next!=null)runtime.coordinator().suspendForPropertyChange(next);
        else runtime.coordinator().resumeQualifiedProperties();
        for(long id:topology.active().keySet())queueView(id);
        data.setDirty();
    }
    public static void stop(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::close);}
    public static void forget(MinecraftServer server){var world=SERVERS.remove(server);if(world!=null)world.close();}
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid authority requires the logical server thread");}
    /** Every island of this world solves on a model built from the same captured options, so the
     * configured trace cutoff is one value for every worker and every retained solver: a retained
     * solver is replaced whenever its model identity changes, and the model is the cutoff's home. */
    private FluidThermodynamics model(FluidCheckpointCodec.PackageKey key){return models.computeIfAbsent(key,k->FluidThermodynamics.forNetwork(catalog,k.packageId(),k.compressibility(),options.maximumVelocity(),options.traceCutoffMoleFraction(),options.solids()));}
    private static ResourceKey<Level> dimension(String name){return ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(name));}
    public List<FluidPresetCatalog.Preset> presets(){owned();return presetCache;}
    public List<String> components(){owned();return componentNames;}
    public Map<String,MaterialName> materialNames(){owned();var result=new LinkedHashMap<String,MaterialName>();for(String id:componentNames)result.put(id,catalog.name(id));return Map.copyOf(result);}
    public long onlineTick(){owned();return topology.onlineTick();}
    /** Optional read-only diagnostics; all values are immutable and callbacks run on the server thread. */
    public void observe(java.util.function.BiConsumer<List<IslandCoordinator.Snapshot>,Map<Long,IslandCoordinator.Metrics>> observer){owned();this.observer=observer;}
    /** Every island as stored, without materialising certified ones: a diagnostic read that advances nothing. */
    public List<IslandCoordinator.Snapshot> diagnosticSnapshots(){owned();return runtime.coordinator().observe();}
    /** Materialises every certified island to now, as a save does; the observer receives the replayed accounting. */
    public void materialiseAll(){owned();runtime.coordinator().snapshots();}
    /** The rest and steady-flow certificate policy captured at server start. */
    public CertificatePolicy certificates(){owned();return options.certificates();}
    /** Why an island's last closed interval did not certify it, or null; diagnostics only. */
    public String certificationRefusal(long island){owned();return runtime.coordinator().certificationRefusal(island);}
    /** What the certificate evidence measured at an island's last usable solved interval, or null; diagnostics only. */
    public IslandCoordinator.Evidence certificationEvidence(long island){owned();return runtime.coordinator().certificationEvidence(island);}
    public Map<Long,WorldTopologyLedger.Registration> registrations(){owned();if(latest==null)latest=topology.latest();return latest;}
    public Optional<WorldTopologyLedger.Registration> at(PhysicalFluidTopology.Position position){owned();return registrations().values().stream().filter(r->r.device().position().equals(position)).findFirst();}
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        owned();if(at(position).isPresent())throw new IllegalStateException("A fluid identity already occupies this position");
        long id=topology.nextIdentity();var control=switch(kind){case PUMP->new FlowControl.Pump(.01,500000,1);case VALVE->new FlowControl.PressureValve(200000);default->new FlowControl.Passive();};
        var device=new PhysicalFluidTopology.Device(id,position,kind,facing,new PipeResistance.Geometry(1,.05,.000045,0),control);
        var spec=kind==TopologyCompiler.Kind.GENERATOR?FluidDeviceSpec.water(catalog):FluidDeviceSpec.nitrogen(catalog);
        if(kind==TopologyCompiler.Kind.RESERVOIR)spec=new FluidDeviceSpec(options.volume(),options.temperature(),options.pressure(),spec.composition());
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    public void edit(long id,long expectedRevision,PhysicalFluidTopology.Device device,FluidDeviceSpec spec) {
        owned();if(spec.composition().length!=componentNames.size())throw new IllegalArgumentException("Composition differs from captured network axis");var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||device.id()!=id)throw new IllegalStateException("Stale fluid controls");
        if(old.device().equals(device)&&old.spec().equals(spec))return;
        submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,Math.addExact(expectedRevision,1)))),topology.nextIdentity());
    }
    public void remove(long id){owned();var old=registrations().get(id);if(old!=null)submit(List.of(new WorldTopologyLedger.Edit(id,null)),topology.nextIdentity(),old.device().kind()==TopologyCompiler.Kind.FILTER?new WorldTopologyLedger.Recovery(id,null):null);}
    public void recoverFilter(long id,long expectedRevision,net.minecraft.server.level.ServerPlayer player){
        owned();var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||old.device().kind()!=TopologyCompiler.Kind.FILTER)throw new IllegalStateException("Stale filter controls");
        if(player.getInventory().getFreeSlot()<0)throw new IllegalStateException("Inventory full; filter unchanged");
        var cake=filterStock().get(id);if(cake==null||cake.captured().empty())throw new IllegalStateException("Filter has no captured solids");
        submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(old.device(),old.spec(),Math.addExact(old.revision(),1)))),topology.nextIdentity(),new WorldTopologyLedger.Recovery(id,player.getUUID()));deliverRecoveries();
    }
    private void submit(List<WorldTopologyLedger.Edit> edits,long nextId){submit(edits,nextId,null);}
    private void submit(List<WorldTopologyLedger.Edit> edits,long nextId,WorldTopologyLedger.Recovery recovery) {
        if(closed||legacyUnbound)throw new IllegalStateException("Fluid world is closed or has unbound legacy inventories");
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
        runtime.coordinator().fence(prepared.event().id(),prepared.event().tick(),affected);topology.commit(prepared);latest=null;data.setDirty();applyPending();
    }
    /**
     * The per-tick hook does only what is due. The epoch increment advances every island clock at once; the
     * coordinator's tick resets the dispatch budget and pops due deadlines (island slices and retries, round
     * timeouts, the allocator shrink, module horizons, recovery retries) and pumps only if that would act;
     * queued topology events are tried while any exist, and an island an event created or released is
     * offered to the pump in the same tick. No island, module or registry is visited otherwise.
     */
    private void tick() {
        owned();if(closed||legacyUnbound)return;refreshProperties();topology.tick();
        runtime.tick();
        if(topology.hasPendingEvents()){applyPending();runtime.coordinator().pumpIfUseful();}
        for(int i=0;i<64&&!pendingViews.isEmpty();i++){long id=pendingViews.removeFirst();queuedViews.remove(id);refreshLoaded(id);}
        data.setDirty();
    }
    /** Attempted when a recovery is queued and on its retry deadline, never polled; delivery stays exactly-once. */
    private void deliverRecoveries(){
        if(!topology.hasRecoveries())return;
        int delivered=0;boolean capped=false;for(var entry:topology.recoveries().entrySet()){
            if(delivered++>=64){capped=true;break;}var pending=entry.getValue();var stack=com.wormzjl.createcheme.world.item.RecoveredSolidsItem.create(entry.getKey(),pending);boolean accepted=false;
            if(pending.player()!=null){var player=server.getPlayerList().getPlayer(pending.player());if(player!=null){for(int slot=0;slot<player.getInventory().getContainerSize();slot++)if(com.wormzjl.createcheme.world.item.RecoveredSolidsItem.carriesTransfer(player.getInventory().getItem(slot),entry.getKey())){accepted=true;break;}if(!accepted&&player.getInventory().getFreeSlot()>=0)accepted=player.getInventory().add(stack);}}
            else {var position=pending.position();var level=server.getLevel(dimension(position.dimension()));var pos=new net.minecraft.core.BlockPos(position.x(),position.y(),position.z());
                if(level!=null&&level.hasChunkAt(pos))accepted=level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,stack));}
            if(accepted){topology.deliveredRecovery(entry.getKey());data.setDirty();}
        }
        // An offline player, a full inventory or an unloaded chunk leaves the record queued for a bounded retry;
        // more than one batch continues on the next tick.
        if(topology.hasRecoveries())scheduleRecoveryRetry(capped?1:RECOVERY_RETRY_TICKS);
    }
    private void scheduleRecoveryRetry(int ticks) {
        var coordinator=runtime.coordinator();coordinator.schedule(IslandScheduler.Kind.RECOVERY_RETRY,Math.addExact(coordinator.now(),ticks),()->{if(!closed)deliverRecoveries();});
    }
    private Map<Long,InlineFilter> filterStock(){
        var result=new HashMap<Long,InlineFilter>();for(var island:runtime.coordinator().snapshots())for(var pipe:island.graph().pipes())if(pipe.filter()!=null)result.put(pipe.id()-Long.MIN_VALUE,pipe.filter());
        for(var r:registrations().values())if(r.device().kind()==TopologyCompiler.Kind.FILTER)result.putIfAbsent(r.device().id(),new InlineFilter(options.solids().filterCapacity(),options.solids().filterResistance(),com.wormzjl.createcheme.science.fluid.state.SolidInventory.EMPTY,0));return result;
    }
    private Map<Long,PassiveNetwork.Reservoir> boundaries() {
        var result=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(var island:runtime.coordinator().snapshots())for(var node:island.graph().reservoirs())if(!node.junction()&&node.id()>0)result.put(node.id(),node);
        return result;
    }
    private void applyPending() {
        if(propertyHold!=null)return;
        boolean queuedRecovery=false;
        for(int count=0;count<16;count++) {
            // No full physical-registry snapshot is constructed here: ready events come from the ledger's
            // queue, their owners from the coordinator's fence index, and state from the live ledger.
            // Snapshot validation remains at actual capture/transactions.
            if(!topology.hasPendingEvents())break;
            var selectedEvent=nextReadyEvent();if(selectedEvent==null)break;
            var event=selectedEvent.event();var affected=selectedEvent.affected();var registered=topology.active();
            var cakes=filterStock();RecoveredSolid recovered=null;
            if(event.recovery()!=null){var request=event.recovery();var cake=cakes.get(request.filterId());var record=registered.get(request.filterId());
                var player=request.player()==null?null:server.getPlayerList().getPlayer(request.player());
                if(record!=null&&cake!=null&&!cake.captured().empty()&&(request.player()==null||player!=null&&player.getInventory().getFreeSlot()>=0)){
                    recovered=new RecoveredSolid(record.device().position(),request.player(),cake.captured(),cake.energyJoule());cakes.put(request.filterId(),cake.cleared());
                }else if(player!=null)player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Filter unchanged: inventory full or no captured solids."));
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
            runtime.topology(dimension(dimension),event.id(),affected,replacements,model,event.tick(),topology.onlineTick(),additions,removed.keySet(),recovered==null?Map.of():Map.of(PhysicalFluidTopology.filterIdentity(event.recovery().filterId()),filterStock().get(event.recovery().filterId())),()->{
                topology.commit(prepared);compiled=nextCompiled;owners=nextOwners;members=nextMembers;chunkMembers=nextChunks;unboundBindings.retainAll(active.keySet());latest=null;
            });
            for(var future:topology.events())for(var replacement:replacements) {
                boolean touches=future.touched().stream().anyMatch(id->Objects.equals(owners.get(id),replacement.id()));
                if(touches&&!runtime.coordinator().hasFence(replacement.id(),future.id()))runtime.coordinator().fence(future.id(),future.tick(),List.of(replacement.id()));
            }
            queuedRecovery|=recovered!=null;
            data.setDirty();
        }
        // A newly queued recovery is attempted at once, as the old per-tick attempt after applying events did.
        if(queuedRecovery)deliverRecoveries();
    }
    private record ReadyEvent(WorldTopologyLedger.Event event,Set<Long> affected) {}
    /** Owners of an event: islands already fenced for it (from the coordinator's fence index) and the current
     * owners of the identities it touches. No island snapshot is built. */
    private ReadyEvent nextReadyEvent() {
        for(var event:topology.readyEvents()) {
            var affected=new HashSet<Long>(runtime.coordinator().fencedIslands(event.id()));
            for(long id:event.touched())if(owners.containsKey(id))affected.add(owners.get(id));
            for(long id:affected)if(!runtime.coordinator().hasFence(id,event.id()))runtime.coordinator().fence(event.id(),event.tick(),List.of(id));
            if(runtime.coordinator().aligned(event.id(),affected))return new ReadyEvent(event,Set.copyOf(affected));
        }
        return null;
    }
    private void rebuildOwners() {
        var byBoundary=new HashMap<Long,Long>();for(var island:runtime.coordinator().snapshots())for(var node:island.graph().reservoirs())if(!node.junction()&&node.id()>0)byBoundary.put(node.id(),island.id());
        var byFilter=new HashMap<Long,Long>();for(var island:runtime.coordinator().snapshots())for(var pipe:island.graph().pipes())if(pipe.filter()!=null)byFilter.put(pipe.id(),island.id());
        for(var island:compiled.islands()) {
            var ids=java.util.stream.Stream.concat(island.graph().reservoirs().stream().filter(n->!n.junction()).map(n->byBoundary.get(n.id())),island.graph().pipes().stream().filter(p->p.filter()!=null).map(p->byFilter.get(p.id()))).filter(Objects::nonNull).distinct().toList();
            if(ids.size()!=1)throw new IllegalStateException("Saved topology and hydraulic ownership disagree");for(long id:island.physicalIds())owners.put(id,ids.getFirst());
        }
        members=inverse(owners);
    }
    private static Map<Long,List<Long>> inverse(Map<Long,Long> owners) {
        var result=new HashMap<Long,List<Long>>();owners.forEach((physical,island)->result.computeIfAbsent(island,ignored->new ArrayList<>()).add(physical));
        result.replaceAll((id,values)->List.copyOf(values));return Map.copyOf(result);
    }
    private static Map<ChunkKey,List<Long>> chunkIndex(Map<Long,WorldTopologyLedger.Registration> active) {
        var result=new HashMap<ChunkKey,List<Long>>();
        for(var record:active.values()){var p=record.device().position();var key=new ChunkKey(p.dimension(),net.minecraft.world.level.ChunkPos.asLong(p.x()>>4,p.z()>>4));result.computeIfAbsent(key,ignored->new ArrayList<>()).add(record.device().id());}
        result.replaceAll((key,ids)->List.copyOf(ids));return Map.copyOf(result);
    }
    private void queueView(long id){if(queuedViews.add(id))pendingViews.addLast(id);}
    public void loadedChunk(ResourceKey<Level> dimension,long chunk){owned();for(long id:chunkMembers.getOrDefault(new ChunkKey(dimension.location().toString(),chunk),List.of()))queueView(id);}
    private void validateOwnership() {
        var registered=new HashSet<Long>();for(var r:topology.snapshot().active().values())if(r.device().kind()==TopologyCompiler.Kind.RESERVOIR)registered.add(r.device().id());
        var owned=new HashSet<Long>();for(var s:runtime.coordinator().snapshots())for(var n:s.graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)owned.add(n.id());
        if(!registered.equals(owned))throw new IllegalStateException("Physical registry would lose or duplicate saved reservoir ownership");
    }
    public FluidSavedData.Capture capture() {
        owned();var islands=new ArrayList<FluidCheckpointCodec.IslandEntry>();var world=topology.snapshot();
        for(var snapshot:runtime.coordinator().snapshots()) {
            String dimension=world.active().get(members.get(snapshot.id()).getFirst()).device().position().dimension();
            islands.add(new FluidCheckpointCodec.IslandEntry(dimension,com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),compressibility,snapshot));
        }
        return new FluidSavedData.Capture(new FluidCheckpointCodec.Checkpoint(islands,moduleHost==null?transfers.snapshot():moduleHost.transfers(),moduleHost==null?data.checkpoint().modules():moduleHost.snapshots(),moduleHost==null?data.checkpoint().moduleBindings():moduleHost.bindings()),world);
    }
    public FluidView view(long id) {
        owned();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.viewBuilds);var registration=registrations().get(id);if(registration==null)return new FluidView(id,0,0,topology.onlineTick(),"REMOVED",null,0,List.of());
        Long owner=owners.get(id);if(owner==null)return new FluidView(id,registration.revision(),0,topology.onlineTick(),legacyUnbound?"LEGACY UNBOUND":!topology.active().containsKey(id)?"WAITING: topology event":compiled.diagnostics().getOrDefault(id,"NO FLOW: no hydraulic boundary"),null,0,List.of(),null,false,0,"",List.of());
        var snapshot=runtime.coordinator().snapshot(owner);FluidView.State state=null;int nodeIndex=-1;boolean empty=false;
        for(int i=0;i<snapshot.graph().reservoirs().size();i++)if(snapshot.graph().reservoirs().get(i).id()==id){nodeIndex=i;var node=snapshot.graph().reservoirs().get(i);empty=node.empty();if(!empty)state=FluidView.State.from(node.state());break;}
        InlineFilter filter=null;if(registration.device().kind()==TopologyCompiler.Kind.FILTER){state=null;for(var p:snapshot.graph().pipes())if(p.id()==PhysicalFluidTopology.filterIdentity(id))filter=p.filter();}
        var history=new ArrayList<PipeTransfer>();double flow=0;Double devicePressureChange=null;String status=snapshot.status();
        if(empty)status="EMPTY: no fluid temperature / "+status;
        if(snapshot.lastResult().isPresent()) {
            var result=snapshot.lastResult().orElseThrow();var q=result.averageMassFlows();
            for(int i=0;i<snapshot.graph().pipes().size();i++){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex)flow+=q[i];if(pipe.second()==nodeIndex)flow-=q[i];}
            for(var mapping:compiled.pipeViews().getOrDefault(id,List.of()))for(var value:result.pipeTransfers())if(value.pipeId()==mapping.pipeId()) {
                history.add(value);
                for(int edge=0;edge<snapshot.graph().pipes().size();edge++)if(snapshot.graph().pipes().get(edge).id()==value.pipeId()&&result.endpointModes().get(edge).name().contains("VELOCITY_LIMIT"))status+=" / "+result.endpointModes().get(edge);
            }
            if(!history.isEmpty())flow=(history.getFirst().forward().massKg()-history.getFirst().reverse().massKg())/result.advancedSeconds();
            if(registration.device().actuator())for(int i=0;i<snapshot.graph().pipes().size();i++){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex&&!(pipe.control() instanceof FlowControl.Passive)){flow=q[i];devicePressureChange=result.endpointHeads()[i];status+=" / "+result.endpointModes().get(i);}}
        }
        if(filter!=null)for(var pipe:snapshot.graph().pipes())if(pipe.id()==PhysicalFluidTopology.filterIdentity(id))devicePressureChange=snapshot.graph().reservoirs().get(pipe.first()).state().pressure()-snapshot.graph().reservoirs().get(pipe.second()).state().pressure();
        if(filter!=null&&filter.clogged())status="filter clogged / "+status;
        var closures=snapshot.lastResult().map(PassiveIntervalSolver.Result::rejectionReasons).orElse(Map.of());
        for(var mapping:compiled.pipeViews().getOrDefault(id,List.of()))for(var pipe:snapshot.graph().pipes())if(pipe.id()==mapping.pipeId()&&pipe.blockedDirections()!=0&&pipe.filter()==null)status=solidClosure(mapping.pipeId(),closures)+" / "+status;
        if(compiled.diagnostics().containsKey(id))status=compiled.diagnostics().get(id)+" / "+status;
        var active=topology.active().get(id);if(active==null||active.revision()!=registration.revision())status="WAITING: configuration event / "+status;
        if(unboundBindings.contains(id))status="UNBOUND: saved inventory retained / "+status;
        if(moduleHost!=null){var reason=moduleHost.waitingReason(owner);if(!reason.isEmpty())status=reason+" / "+status;}
        var routes=new ArrayList<FluidView.PipeRoute>();
        for(var historyEntry:history)for(var pipe:snapshot.graph().pipes())if(pipe.id()==historyEntry.pipeId()) {
            routes.add(new FluidView.PipeRoute(pipe.id(),nodeLabel(snapshot.graph().reservoirs().get(pipe.first()).id()),nodeLabel(snapshot.graph().reservoirs().get(pipe.second()).id())));
        }
        return new FluidView(id,registration.revision(),snapshot.clock().committedTick(),topology.onlineTick(),status,state,flow,history,devicePressureChange,true,
                snapshot.lastResult().map(PassiveIntervalSolver.Result::advancedSeconds).orElse(0.0),snapshot.lastResult().map(r->r.acceptance().name()).orElse(""),routes,filter);
    }
    /**
     * Why this connection carries nothing, in the words the solver used, for the device status a
     * player reads. The event integrator names the connection and the two velocities in the reason
     * it records against every closure, and a closure the interval started with is recorded again
     * each interval, so the account stays true for as long as the connection is shut. Nothing else
     * carries it: the blocked mask on the pipe says only that something closed it.
     *
     * <p>The status line is bounded and wraps to three lines on the screen, so this stays to one
     * short clause. A reason with no velocity of its own - a population limit, a stopped filter -
     * is named without one rather than with two zeroes, and an unrecognised or missing record
     * falls back to the bare text the mask alone justifies.
     */
    static String solidClosure(long pipeId,Map<String,Integer> reasons) {
        for(String key:reasons.keySet()) {
            if(!key.startsWith(SOLID_CLOSURE)||!key.contains("; pipe="+pipeId+";"))continue;
            int end=key.indexOf(';',SOLID_CLOSURE.length());if(end<0)break;
            double velocity=closureField(key,"; velocity="),minimum=closureField(key,"; deposition=");
            return SOLID_CLOSURE+key.substring(SOLID_CLOSURE.length(),end)
                    +(minimum>0?" ("+speed(velocity)+" m/s, needs "+speed(minimum)+" m/s)":"");
        }
        return "blocked with solid";
    }
    private static final String SOLID_CLOSURE="blocked with solid: ";
    private static double closureField(String key,String token) {
        int at=key.indexOf(token);if(at<0)return 0;
        int end=key.indexOf(';',at+token.length());
        try{return Double.parseDouble(key.substring(at+token.length(),end<0?key.length():end));}catch(RuntimeException unreadable){return 0;}
    }
    private static String speed(double value) {
        return String.format(java.util.Locale.ROOT,Math.abs(value)>=.01?"%.2f":"%.2e",value);
    }
    private String nodeLabel(long id) {
        var registration=topology.active().get(id);if(registration==null)return "Junction "+id;
        var p=registration.device().position();return p.x()+", "+p.y()+", "+p.z();
    }
    public void refreshLoaded(long id) {
        owned();var record=registrations().get(id);if(record==null)return;var p=record.device().position();var level=server.getLevel(dimension(p.dimension()));if(level==null)return;
        var pos=new BlockPos(p.x(),p.y(),p.z());if(!level.hasChunkAt(pos))return;
        if(!(level.getBlockState(pos).getBlock() instanceof com.wormzjl.createcheme.world.level.block.FluidDeviceBlock block)||block.kind()!=record.device().kind()) {
            if(unboundBindings.add(id))CreateChemE.LOGGER.warn("fluid_binding={} status=UNBOUND detail=Loaded block mismatch; saved inventory and topology retained",id);
            return;
        }
        unboundBindings.remove(id);
        if(level.getBlockEntity(pos) instanceof com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity entity&&entity.fluidIdentity()!=id){entity.bindIdentity(id);return;}
        if(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver&&receiver.fluidIdentity()==id)receiver.acceptFluidView(view(id));
    }
    /** A certified island's materialisation: only accounting (the diagnostic observer) and the save mark need it.
     * It changes no dependency, so modules are not advanced, and it queues no view refresh of its own. */
    private void replayed(List<IslandCoordinator.Snapshot> changed) {
        if(observer!=null){var timings=new HashMap<Long,IslandCoordinator.Metrics>();for(var island:changed)runtime.coordinator().metrics(island.id()).ifPresent(m->timings.put(island.id(),m));observer.accept(changed,Map.copyOf(timings));}
        data.setDirty();
    }
    private void published(List<IslandCoordinator.Snapshot> changed) {
        if(moduleHost!=null&&propertyHold==null) {
            // Rebind only when a topology commit replaced the owners map; advance only when the publication
            // can change what the modules decide.
            boolean rebound=boundOwners!=owners;if(rebound){moduleHost.rebind(owners);boundOwners=owners;}
            if(rebound||moduleHost.dependsOnAny(changed))advanceModules();
        }
        if(observer!=null){var timings=new HashMap<Long,IslandCoordinator.Metrics>();for(var island:changed)runtime.coordinator().metrics(island.id()).ifPresent(m->timings.put(island.id(),m));observer.accept(changed,Map.copyOf(timings));}
        data.setDirty();var ids=new HashSet<Long>();for(var snapshot:changed){ids.add(snapshot.id());if(snapshot.status().startsWith("HELD"))CreateChemE.LOGGER.warn("fluid_island={} committed_tick={} status={}",snapshot.id(),snapshot.clock().committedTick(),snapshot.status());}
        for(long island:ids)for(long physical:members.getOrDefault(island,List.of()))queueView(physical);
        if(options.debugChat()&&(lastDebugChat==Long.MIN_VALUE||topology.onlineTick()-lastDebugChat>=20)) {
            changed.stream().filter(s->s.status().startsWith("HELD")).findFirst().ifPresent(s->{
                lastDebugChat=topology.onlineTick();String message="Fluid network "+s.id()+": "+s.status();if(message.length()>180)message=message.substring(0,180)+" (see log)";
                var text=net.minecraft.network.chat.Component.literal(message);for(var player:server.getPlayerList().getPlayers())player.sendSystemMessage(text);
            });
        }
    }
    /** Advances the modules and books their next own horizon as the one module deadline. */
    private void advanceModules() {
        moduleHost.advance();var coordinator=runtime.coordinator();
        coordinator.schedule(IslandScheduler.Kind.MODULE_HORIZON,moduleHost.nextHorizon(coordinator::epochTick,coordinator.now()),this::moduleDeadline);
    }
    private void moduleDeadline(){if(!closed&&moduleHost!=null&&propertyHold==null)advanceModules();}
    /** An attempt drained after its round closed changes an island's ownership without a publication; a module
     * bound to that island sees it at once through an immediate module deadline, before the next dispatch. */
    private void released(long island) {
        if(moduleHost!=null&&moduleHost.dependsOn(island)){var coordinator=runtime.coordinator();coordinator.schedule(IslandScheduler.Kind.MODULE_HORIZON,coordinator.now(),this::moduleDeadline);}
    }
    @Override public void close(){owned();if(closed)return;closed=true;runtime.close();data.setDirty();}
}
