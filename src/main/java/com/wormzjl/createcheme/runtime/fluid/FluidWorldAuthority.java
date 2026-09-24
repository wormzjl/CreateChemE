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

/** Server-owned physical registry and hydraulic authority. Only presentation buckets inspect loaded block entities. */
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
    /** Island ownership, compiler diagnostics and chunk membership of the registered devices, and the event path. */
    private final PhysicalRegistry registry;
    /** The ownership version the module host was last rebound to; every topology commit changes the registry's. */
    private long boundOwnersVersion=-1;
    /**
     * Queued topology events apply as one batch at the next tick's hook, or earlier when something reads what they
     * change (a view, a capture, a filter recovery). A run of events inside one tick (a command placing thousands of
     * blocks) applies every this many, far inside the ledger's queue bound, so the queue never waits on a whole command.
     */
    static final int APPLY_BATCH=1024;
    /** How long an undeliverable recovery (offline player, full inventory, unloaded chunk) waits for its next attempt. */
    private static final int RECOVERY_RETRY_TICKS=20;
    private final Set<Long> unboundBindings=new HashSet<>();
    /** Engine-owned presentation: loaded devices and open menus, updated on their island's bucket only. */
    private final FluidPresentation<com.wormzjl.createcheme.world.inventory.FluidDeviceMenu> presentation;
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
        // The configured charge of a newly placed reservoir must be a state the network package can evaluate; the config
        // range itself is only physical (it is fixed before any data pack loads), so the domain is checked here, once.
        try{new FluidDeviceSpec(options.volume(),options.temperature(),options.pressure(),FluidDeviceSpec.nitrogen(catalog).composition()).validate(model,TopologyCompiler.Kind.RESERVOIR);}
        catch(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation invalid){
            throw new IllegalStateException("Fluid config initialNitrogenTemperatureKelvin/initialNitrogenPressurePascal: "+invalid.getMessage(),invalid);
        }
        legacyUnbound=data.world().isEmpty()&&!data.checkpoint().islands().isEmpty();
        var savedTopology=data.world().orElseGet(()->WorldTopologyLedger.Snapshot.empty(catalog));savedTopology.basis().requireCurrent(catalog);
        SolidCompatibility.validate(catalog.solids(),data.checkpoint(),savedTopology);observedSolidData=catalog;
        topology=new WorldTopologyLedger(savedTopology);transfers=new BufferedTransfers(data.checkpoint().transfers());
        presentation=new FluidPresentation<>(new PresentationHost(),topology.onlineTick());
        moduleHost=data.checkpoint().modules().isEmpty()||legacyUnbound?null:new CausalModuleCoordinator(model,data.checkpoint().moduleBindings(),data.checkpoint().transfers(),data.checkpoint().modules());
        var settings=new IslandCoordinator.Settings(options.wallBudgetNanos(),options.wallBudgetNanos()*3/4,64,options.adaptiveCadence(),options.initialCadenceTicks(),options.certificates());
        // Island clocks read the world's online tick: one epoch increment per tick advances every island.
        runtime=moduleHost==null?new MinecraftFluidRuntime(server,this::published,settings,(attempt,command)->command,IslandCoordinator.CommitHook.NO_MATERIAL,topology::onlineTick)
                :new MinecraftFluidRuntime(server,this::published,settings,moduleHost::command,moduleHost::prepare,topology::onlineTick);
        runtime.coordinator().onReleased(this::released);runtime.coordinator().onReplayed(this::replayed);
        runtime.coordinator().onDomainHold(this::domainHeld);runtime.coordinator().nodeNames(this::deviceLabel);
        registry=new PhysicalRegistry(topology,model,new InlineFilter(options.solids().filterCapacity(),options.solids().filterResistance(),com.wormzjl.createcheme.science.fluid.state.SolidInventory.EMPTY,0),new RegistryHost());
        if(legacyUnbound) {
            CreateChemE.LOGGER.error("fluid_world status=LEGACY_UNBOUND detail=Core inventories preserved; physical bindings are unavailable in this older checkpoint");return;
        }
        for(var entry:data.checkpoint().islands())runtime.register(dimension(entry.dimension()),entry.snapshot(),model(new FluidCheckpointCodec.PackageKey(entry.packageId(),entry.compressibility())));
        logLoad();
        registry.load();data.bindCapture(this::capture);
        // Block entities whose chunks loaded before this authority existed never reported themselves: every device
        // in an already loaded chunk is marked once, and its bucket binds and presents it. Nothing is pushed here.
        registry.forEachChunk((name,chunk,devices)->{var level=server.getLevel(dimension(name));var at=new net.minecraft.world.level.ChunkPos(chunk);
            if(level!=null&&level.hasChunk(at.x,at.z))for(long id:devices)presentation.markDirty(id);});
        if(moduleHost!=null){moduleHost.attach(runtime.coordinator());advanceModules();}
        // Saved recoveries are first attempted on the first tick, as before, then on their retry deadline.
        if(topology.hasRecoveries())scheduleRecoveryRetry(1);
    }
    /** What the load restored: the checkpoint format, islands, certificates saved and restored (a restored island is
     * materialised and holds only its horizon; it solves nothing at the load), and every discarded certificate. */
    private void logLoad() {
        var loaded=runtime.coordinator().observe();
        long saved=data.checkpoint().islands().stream().filter(e->e.snapshot().certificate().flatMap(IslandCoordinator.Certified::saved).isPresent()).count();
        long restored=loaded.stream().filter(s->s.certificate().isPresent()).count();
        CreateChemE.LOGGER.info("fluid_world status=LOADED format={} online_tick={} islands={} certificates_saved={} certificates_restored={} awake={}",
                FluidCheckpointCodec.VERSION,topology.onlineTick(),loaded.size(),saved,restored,loaded.size()-restored);
        for(var s:loaded)if(s.status().startsWith("WAITING: saved "))CreateChemE.LOGGER.warn("fluid_island={} status=CERTIFICATE_DISCARDED detail={}",s.id(),s.status());
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
        presentation.markAllLoaded();
        data.setDirty();
    }
    public static void stop(MinecraftServer server){find(server).ifPresent(FluidWorldAuthority::close);}
    public static void forget(MinecraftServer server){com.wormzjl.createcheme.network.ColumnV3Network.forgetPresentation(server);var world=SERVERS.remove(server);if(world!=null)world.close();}
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid authority requires the logical server thread");}
    /** Every island of this world solves on a model built from the same captured options, so the
     * configured trace cutoff is one value for every worker and every retained solver: a retained
     * solver is replaced whenever its model identity changes, and the model is the cutoff's home. */
    private FluidThermodynamics model(FluidCheckpointCodec.PackageKey key){return models.computeIfAbsent(key,k->FluidThermodynamics.forNetwork(catalog,k.packageId(),k.compressibility(),options.maximumVelocity(),options.traceCutoffMoleFraction(),options.solids()));}
    private static ResourceKey<Level> dimension(String name){return ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(name));}
    public List<FluidPresetCatalog.Preset> presets(){owned();return presetCache;}
    public List<String> components(){owned();return componentNames;}
    /** The world's network property model; its {@code domain()} is where a state may be evaluated. */
    public FluidThermodynamics model(){owned();return model;}
    public Map<String,MaterialName> materialNames(){owned();var result=new LinkedHashMap<String,MaterialName>();for(String id:componentNames)result.put(id,catalog.name(id));return Map.copyOf(result);}
    public long onlineTick(){owned();return topology.onlineTick();}
    /** Optional read-only diagnostics; all values are immutable and callbacks run on the server thread. */
    public void observe(java.util.function.BiConsumer<List<IslandCoordinator.Snapshot>,Map<Long,IslandCoordinator.Metrics>> observer){owned();this.observer=observer;}
    /** Every island as stored, without materialising certified ones: a diagnostic read that advances no island. The
     * events queued since the last application are applied first, so the islands they create are among them. */
    public List<IslandCoordinator.Snapshot> diagnosticSnapshots(){owned();flush();return runtime.coordinator().observe();}
    /** Materialises every certified island to now, as a save does; the observer receives the replayed accounting. */
    public void materialiseAll(){owned();flush();runtime.coordinator().snapshots();}
    /** The rest and steady-flow certificate policy captured at server start. */
    public CertificatePolicy certificates(){owned();return options.certificates();}
    /** Why an island's last closed interval did not certify it, or null; diagnostics only. */
    public String certificationRefusal(long island){owned();return runtime.coordinator().certificationRefusal(island);}
    /** What the certificate evidence measured at an island's last usable solved interval, or null; diagnostics only. */
    public IslandCoordinator.Evidence certificationEvidence(long island){owned();return runtime.coordinator().certificationEvidence(island);}
    public Map<Long,WorldTopologyLedger.Registration> registrations(){owned();return registry.registrations();}
    /** The registration at a position (every block-entity load and binding asks). */
    public Optional<WorldTopologyLedger.Registration> at(PhysicalFluidTopology.Position position){owned();return registry.at(position);}
    /** Applied events that did nothing for the player who asked (a recovery that found no cake or a full inventory), by event, for their reply. Bounded. */
    private final LinkedHashMap<UUID,String> refusedEvents=new LinkedHashMap<>();
    private void refuseEvent(UUID event,String reason){refusedEvents.put(event,reason);if(refusedEvents.size()>WorldTopologyLedger.MAXIMUM_EVENTS)refusedEvents.remove(refusedEvents.keySet().iterator().next());}
    public WorldTopologyLedger.Registration place(PhysicalFluidTopology.Position position,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        owned();if(at(position).isPresent())throw new IllegalStateException("A fluid identity already occupies this position");
        long id=topology.nextIdentity();var control=switch(kind){case PUMP->new FlowControl.Pump(.01,500000,1);case VALVE->new FlowControl.PressureValve(200000);default->new FlowControl.Passive();};
        var device=new PhysicalFluidTopology.Device(id,position,kind,facing,new PipeResistance.Geometry(1,.05,.000045,0),control);
        var spec=kind==TopologyCompiler.Kind.GENERATOR?FluidDeviceSpec.water(catalog):FluidDeviceSpec.nitrogen(catalog);
        if(kind==TopologyCompiler.Kind.RESERVOIR)spec=new FluidDeviceSpec(options.volume(),options.temperature(),options.pressure(),spec.composition());
        var record=new WorldTopologyLedger.Registration(device,spec,0);
        submit(List.of(new WorldTopologyLedger.Edit(id,record)),Math.addExact(id,1));return record;
    }
    /** Queues a configuration change as a ledger event and returns it, or null when nothing would change. */
    public WorldTopologyLedger.Event edit(long id,long expectedRevision,PhysicalFluidTopology.Device device,FluidDeviceSpec spec) {
        owned();if(spec.composition().length!=componentNames.size())throw new IllegalArgumentException("Composition differs from captured network axis");var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||device.id()!=id)throw new IllegalStateException("Stale fluid controls");
        if(old.device().equals(device)&&old.spec().equals(spec))return null;
        return submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,Math.addExact(expectedRevision,1)))),topology.nextIdentity());
    }
    public void remove(long id){owned();var old=registrations().get(id);if(old!=null)submit(List.of(new WorldTopologyLedger.Edit(id,null)),topology.nextIdentity(),old.device().kind()==TopologyCompiler.Kind.FILTER?new WorldTopologyLedger.Recovery(id,null):null);}
    /** Queues a filter's solid recovery as a ledger event and returns it. */
    public WorldTopologyLedger.Event recoverFilter(long id,long expectedRevision,net.minecraft.server.level.ServerPlayer player){
        owned();flush();var old=registrations().get(id);if(old==null||old.revision()!=expectedRevision||old.device().kind()!=TopologyCompiler.Kind.FILTER)throw new IllegalStateException("Stale filter controls");
        if(player.getInventory().getFreeSlot()<0)throw new IllegalStateException("Inventory full; filter unchanged");
        var cake=registry.cake(id);if(cake==null||cake.captured().empty())throw new IllegalStateException("Filter has no captured solids");
        // A recovery applies at once, as it always has, so the player's item is delivered with the action.
        var event=submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(old.device(),old.spec(),Math.addExact(old.revision(),1)))),topology.nextIdentity(),new WorldTopologyLedger.Recovery(id,player.getUUID()));
        applyPending();deliverRecoveries();
        return event;
    }
    private WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId){return submit(edits,nextId,null);}
    private WorldTopologyLedger.Event submit(List<WorldTopologyLedger.Edit> edits,long nextId,WorldTopologyLedger.Recovery recovery) {
        if(closed||legacyUnbound)throw new IllegalStateException("Fluid world is closed or has unbound legacy inventories");
        var event=registry.submit(edits,nextId,recovery);data.setDirty();
        if(registry.unapplied()>=APPLY_BATCH)applyPending();
        return event;
    }
    /** Applies the events queued since the last application before a read that must see them. */
    private void flush(){if(!closed&&!legacyUnbound&&registry.unapplied()>0)applyPending();}
    /**
     * The per-tick hook does only what is due. The epoch increment advances every island clock at once; the
     * coordinator's tick resets the dispatch budget and pops due deadlines (island slices and retries, round
     * timeouts, the allocator shrink, module horizons, recovery retries) and pumps only if that would act;
     * queued topology events are tried while any exist - everything queued since the last tick applies here as
     * batches, each compiling only the components it touches - and an island an event created or released is
     * offered to the pump in the same tick. No island, module or registry is visited otherwise. Last, the
     * presentation bucket of this tick is flushed if one is due (an O(1) check otherwise), after the tick's
     * events, so its replies and views include them.
     */
    private void tick() {
        owned();if(closed||legacyUnbound)return;refreshProperties();topology.tick();
        runtime.tick();
        if(topology.hasPendingEvents()){applyPending();runtime.coordinator().pumpIfUseful();}
        presentation.tick(topology.onlineTick());
        com.wormzjl.createcheme.network.ColumnV3Network.presentationTick(server, topology.onlineTick());
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
    private void applyPending() {
        if(propertyHold!=null)return;
        // A newly queued recovery is attempted at once, as the old per-tick attempt after applying events did.
        if(registry.applyPending())deliverRecoveries();
        data.setDirty();
    }
    /** A chunk loaded: its registered devices are marked for their buckets, which bind and present them. Nothing is pushed. */
    public void loadedChunk(ResourceKey<Level> dimension,long chunk){owned();for(long id:registry.chunk(dimension.location().toString(),chunk))presentation.markDirty(id);}
    /** A device's block entity loaded or was bound: marked for its bucket, never presented at once. */
    public void deviceLoaded(long id){owned();if(!closed)presentation.deviceLoaded(id);}
    /** A device's block entity unloaded or was removed: publications stop marking it. */
    public void deviceUnloaded(long id){owned();presentation.deviceUnloaded(id);}
    /** Marks a device for its next bucket (a test or tool asking for a refresh gets it on the engine's schedule). */
    public void markViewDirty(long id){owned();presentation.markDirty(id);}
    /** An open menu becomes a consumer of its device's bucket. The first delivery comes with that bucket. */
    public void subscribe(com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu){owned();if(!closed)presentation.subscribe(menu,menu.identity());}
    public void unsubscribe(com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu){owned();presentation.unsubscribe(menu);}
    /**
     * A player input from a menu, already validated and queued as a ledger event (or refused): recorded for the
     * reply its menu receives with its next bucket. Returns nothing to the handler, which sends nothing.
     */
    public void input(com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu,WorldTopologyLedger.Event event,String refusal) {
        owned();long now=topology.onlineTick();
        presentation.input(menu,menu.identity(),refusal!=null?FluidPresentation.Input.refused(now,refusal):event==null?new FluidPresentation.Input(now,null,null,-1):FluidPresentation.Input.queued(now,event.id(),event.tick()));
    }
    /** Diagnostics: what one menu has been delivered, bucket by bucket. */
    public List<FluidPresentation.Delivery> deliveries(com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu){owned();return presentation.deliveries(menu);}
    public FluidPresentation.Stats presentationStats(){owned();return presentation.stats();}
    /** Diagnostics: a device's bucket key (its island, or itself without one); its buckets fall at ticks congruent to it modulo 100. */
    public long presentationKey(long device){owned();flush();return bucketKey(device);}
    /** The bucket key as the presentation reads it, with no event applied: marking a device never forces a batch. */
    private long bucketKey(long device){Long owner=registry.owner(device);return owner!=null?owner:device;}
    public boolean viewDirty(long device){owned();return presentation.dirty(device);}
    public FluidSavedData.Capture capture() {
        owned();flush();var islands=new ArrayList<FluidCheckpointCodec.IslandEntry>();var world=topology.snapshot();
        for(var snapshot:runtime.coordinator().snapshots()) {
            String dimension=world.active().get(registry.members(snapshot.id()).getFirst()).device().position().dimension();
            islands.add(new FluidCheckpointCodec.IslandEntry(dimension,com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog),compressibility,snapshot));
        }
        return new FluidSavedData.Capture(new FluidCheckpointCodec.Checkpoint(islands,moduleHost==null?transfers.snapshot():moduleHost.transfers(),moduleHost==null?data.checkpoint().modules():moduleHost.snapshots(),moduleHost==null?data.checkpoint().moduleBindings():moduleHost.bindings()),world);
    }
    /** A device's view, as a presentation read: a certified island is materialised to now but never woken by it. */
    public FluidView view(long id){owned();flush();return view(id,new HashMap<>());}
    /** {@code islands} caches island reads, so one bucket reads each island once however many of its devices it presents. */
    private FluidView view(long id,Map<Long,IslandCoordinator.Snapshot> islands) {
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.viewBuilds);var registration=registrations().get(id);if(registration==null)return new FluidView(id,0,0,topology.onlineTick(),"REMOVED",null,0,List.of());
        Long owner=registry.owner(id);if(owner==null)return new FluidView(id,registration.revision(),0,topology.onlineTick(),legacyUnbound?"LEGACY UNBOUND":!topology.active().containsKey(id)?"WAITING: topology event":Objects.requireNonNullElse(registry.diagnostic(id),"NO FLOW: no hydraulic boundary"),null,0,List.of(),null,false,0,"",List.of());
        var snapshot=islands.computeIfAbsent(owner,runtime.coordinator()::presentation);FluidView.State state=null;int nodeIndex=-1;boolean empty=false;
        for(int i=0;i<snapshot.graph().reservoirs().size();i++)if(snapshot.graph().reservoirs().get(i).id()==id){nodeIndex=i;var node=snapshot.graph().reservoirs().get(i);empty=node.empty();if(!empty)state=FluidView.State.from(node.state());break;}
        InlineFilter filter=null;if(registration.device().kind()==TopologyCompiler.Kind.FILTER){state=null;for(var p:snapshot.graph().pipes())if(p.id()==PhysicalFluidTopology.filterIdentity(id))filter=p.filter();}
        var history=new ArrayList<PipeTransfer>();double flow=0;Double devicePressureChange=null;String status=snapshot.status();
        if(empty)status="EMPTY: no fluid temperature / "+status;
        if(snapshot.lastResult().isPresent()) {
            var result=snapshot.lastResult().orElseThrow();var q=result.averageMassFlows();
            for(int i=0;i<snapshot.graph().pipes().size();i++){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex)flow+=q[i];if(pipe.second()==nodeIndex)flow-=q[i];}
            for(var mapping:registry.pipeViews(id))for(var value:result.pipeTransfers())if(value.pipeId()==mapping.pipeId()) {
                history.add(value);
                for(int edge=0;edge<snapshot.graph().pipes().size();edge++)if(snapshot.graph().pipes().get(edge).id()==value.pipeId()&&result.endpointModes().get(edge).name().contains("VELOCITY_LIMIT"))status+=" / "+result.endpointModes().get(edge);
            }
            if(!history.isEmpty())flow=(history.getFirst().forward().massKg()-history.getFirst().reverse().massKg())/result.advancedSeconds();
            if(registration.device().actuator())for(int i=0;i<snapshot.graph().pipes().size();i++){var pipe=snapshot.graph().pipes().get(i);if(pipe.first()==nodeIndex&&!(pipe.control() instanceof FlowControl.Passive)){flow=q[i];devicePressureChange=result.endpointHeads()[i];status+=" / "+result.endpointModes().get(i);
                // The setting is the rise for water; on what the pump actually draws its limit scales with the density.
                if(pipe.control() instanceof FlowControl.Pump pump){var suction=snapshot.graph().reservoirs().get(pipe.first()).state();status+=String.format(java.util.Locale.ROOT," (limit %.0f Pa on this fluid)",pump.maximumAddedPressure()*(suction.mass()/suction.volume())/model.pumpReferenceDensity());}}}
        }
        if(filter!=null)for(var pipe:snapshot.graph().pipes())if(pipe.id()==PhysicalFluidTopology.filterIdentity(id))devicePressureChange=snapshot.graph().reservoirs().get(pipe.first()).state().pressure()-snapshot.graph().reservoirs().get(pipe.second()).state().pressure();
        if(filter!=null&&filter.clogged())status="filter clogged / "+status;
        var closures=snapshot.lastResult().map(PassiveIntervalSolver.Result::rejectionReasons).orElse(Map.of());
        for(var mapping:registry.pipeViews(id))for(var pipe:snapshot.graph().pipes())if(pipe.id()==mapping.pipeId()&&pipe.blockedDirections()!=0&&pipe.filter()==null)status=solidClosure(mapping.pipeId(),closures)+" / "+status;
        if(registry.diagnostic(id)!=null)status=registry.diagnostic(id)+" / "+status;
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
    /** A node as a status line names it: a device by kind and position, anything else as the pipe junction it is. */
    private String deviceLabel(long id) {
        var registration=topology.active().get(id);if(registration==null)return "pipe junction "+id;
        var p=registration.device().position();return registration.device().kind().name().toLowerCase(java.util.Locale.ROOT)+" at "+p.x()+", "+p.y()+", "+p.z();
    }
    /**
     * One WARN per island per thermo-domain hold (the coordinator rate-limits repeats of the same island and violation):
     * where, which package, component, property, value and range, and what to do about it.
     */
    private void domainHeld(long island,com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation,String where,boolean parked) {
        String dimension="unknown";
        var members=registry.members(island);if(!members.isEmpty()){var record=topology.active().get(members.getFirst());if(record!=null)dimension=record.device().position().dimension();}
        CreateChemE.LOGGER.warn("fluid_island={} dimension={} node={} device={} status=THERMO_DOMAIN code={} package={} component={} property={} value={} range={} waits_for_inputs={} detail={} action=Extend the component's validity range in its data file only with data validated for it; see documentation/fluid-followups/THERMO_DOMAIN_ERROR.md and FLUID_PUMP_AND_THERMO_DOMAIN_REVIEW.md",
                island,dimension,violation.node()==com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation.NO_NODE?"unknown":violation.node(),where.isEmpty()?"unknown":where,
                violation.code(),violation.packageId(),violation.component(),violation.property().label(),violation.value(),violation.range(),parked,violation.getMessage());
    }
    private String nodeLabel(long id) {
        var registration=topology.active().get(id);if(registration==null)return "Junction "+id;
        var p=registration.device().position();return p.x()+", "+p.y()+", "+p.z();
    }
    /** The world side of {@link FluidPresentation}: ownership, views, loaded block entities and menu delivery. */
    private final class PresentationHost implements FluidPresentation.Host<com.wormzjl.createcheme.world.inventory.FluidDeviceMenu> {
        public long key(long device){return bucketKey(device);}
        public long revision(long device){var record=registrations().get(device);return record==null?-1:record.revision();}
        public boolean eventPending(UUID event){for(var queued:topology.events())if(queued.id().equals(event))return true;return false;}
        public String eventRefusal(UUID event){return refusedEvents.remove(event);}
        public boolean open(com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu) {
            var player=menu.serverPlayer();return player!=null&&!player.hasDisconnected()&&player.containerMenu==menu&&menu.stillValid(player);
        }
        public FluidView view(long device,Map<Long,IslandCoordinator.Snapshot> islands){return FluidWorldAuthority.this.view(device,islands);}
        /** Binds a loaded block entity that does not yet carry its identity, then hands it the view. Never loads a chunk. */
        public boolean present(long id,java.util.function.Supplier<FluidView> view) {
            var record=registrations().get(id);if(record==null)return false;var p=record.device().position();var level=server.getLevel(dimension(p.dimension()));if(level==null)return false;
            var pos=new BlockPos(p.x(),p.y(),p.z());if(!level.hasChunkAt(pos))return false;
            if(!(level.getBlockState(pos).getBlock() instanceof com.wormzjl.createcheme.world.level.block.FluidDeviceBlock block)||block.kind()!=record.device().kind()) {
                if(unboundBindings.add(id))CreateChemE.LOGGER.warn("fluid_binding={} status=UNBOUND detail=Loaded block mismatch; saved inventory and topology retained",id);
                return false;
            }
            unboundBindings.remove(id);
            if(level.getBlockEntity(pos) instanceof com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity entity&&entity.fluidIdentity()!=id)entity.bindIdentity(id);
            if(!(level.getBlockEntity(pos) instanceof FluidView.Receiver receiver)||receiver.fluidIdentity()!=id)return false;
            receiver.acceptFluidView(view.get());return true;
        }
        public void failed(long device,RuntimeException failure){CreateChemE.LOGGER.error("fluid_presentation device={} status=FAILED detail=The view could not be presented at its bucket",device,failure);}
        public void deliver(com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu,long device,boolean withStatic,FluidView view,String reply) {
            var record=registrations().get(device);if(record!=null)com.wormzjl.createcheme.network.FluidNetwork.deliver(FluidWorldAuthority.this,menu,record,withStatic,view,reply);
        }
    }
    /** The world side of {@link PhysicalRegistry}: the runtime's islands, players for recoveries, and what a commit changes here. */
    private final class RegistryHost implements PhysicalRegistry.Host {
        public IslandCoordinator coordinator(){return runtime.coordinator();}
        public void topology(String name,Set<UUID> events,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,
                             Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit) {
            runtime.topology(dimension(name),events,affected,replacements,model,committed,online,additions,removals,releasedFilters,commit);
        }
        public boolean acceptsRecovery(UUID player){var online=server.getPlayerList().getPlayer(player);return online!=null&&online.getInventory().getFreeSlot()>=0;}
        public void refused(UUID event,String reason){refuseEvent(event,reason);}
        public void committed(Set<Long> removed) {
            unboundBindings.removeAll(removed);
            // Open menus follow their devices to the replacement islands' buckets.
            presentation.rekey();
        }
    }
    /** A certified island's materialisation: only accounting (the diagnostic observer) and the save mark need it.
     * It changes no dependency, so modules are not advanced, and it queues no view refresh of its own. */
    private void replayed(List<IslandCoordinator.Snapshot> changed) {
        if(observer!=null){var timings=new HashMap<Long,IslandCoordinator.Metrics>();for(var island:changed)runtime.coordinator().metrics(island.id()).ifPresent(m->timings.put(island.id(),m));observer.accept(changed,Map.copyOf(timings));}
        data.setDirty();
    }
    private void published(List<IslandCoordinator.Snapshot> changed) {
        if(moduleHost!=null&&propertyHold==null) {
            // Rebind only when a topology commit changed the owners; advance only when the publication
            // can change what the modules decide.
            long version=registry.ownersVersion();boolean rebound=boundOwnersVersion!=version;if(rebound){moduleHost.rebind(registry.owners());boundOwnersVersion=version;}
            if(rebound||moduleHost.dependsOnAny(changed))advanceModules();
        }
        if(observer!=null){var timings=new HashMap<Long,IslandCoordinator.Metrics>();for(var island:changed)runtime.coordinator().metrics(island.id()).ifPresent(m->timings.put(island.id(),m));observer.accept(changed,Map.copyOf(timings));}
        // A thermo-domain hold has its own, rate-limited line with the full detail (domainHeld); every other hold keeps this one.
        data.setDirty();var ids=new HashSet<Long>();for(var snapshot:changed){ids.add(snapshot.id());if(snapshot.status().startsWith("HELD")&&!snapshot.status().startsWith(com.wormzjl.createcheme.runtime.ProcessSolveServices.THERMO_DOMAIN))CreateChemE.LOGGER.warn("fluid_island={} committed_tick={} status={}",snapshot.id(),snapshot.clock().committedTick(),snapshot.status());}
        // Only loaded devices are marked; their bucket presents them. A publication itself pushes nothing.
        for(long island:ids)for(long physical:registry.members(island))presentation.markIfLoaded(physical);
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
