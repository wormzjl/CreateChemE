package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;

/**
 * Checkpoint format 3 of committed science and runtime state (plan section 3.5). Rebuilds properties through the
 * immutable model; it never deserializes implementation caches or creates a nitrogen charge. World block and event
 * identities are supplied by the encompassing world-authority format, not inferred from loaded chunks here.
 *
 * <p>Layout, in one NBT compound: {@code FluidFormat = 3}; the world {@code Epoch} (the online tick the checkpoint
 * was taken at); the {@code Ledger} of buffered transfers, pending material and module states, as checksummed JSON;
 * and {@code Islands}, one compound per island. An island compound holds its identity and revision, its clock
 * ({@code Online}, {@code Committed}, {@code Retry}, {@code Cadence}), {@code Base} - the base tick of its
 * certificate, or {@link #AWAKE} - with the certificate's own fields and signature when certified, and an opaque,
 * checksummed {@code Payload}: the JSON of its graph, anchor, last solved interval, fallback allowance, fences and
 * status, and for a certified island the graph its certified interval started from. A certified island's payload
 * records its certificate base, not its materialised state, so while it stays certified its payload never changes
 * and a {@link PayloadCache} copies it into every save instead of encoding it again; a load materialises it from
 * the base to the saved committed tick without a solve. {@code SHA256} covers every scalar field, the ledger and
 * every payload digest.
 *
 * <p>No other format is read: there is no upgrade from format 2 or older, which are refused with the instruction
 * to create a fresh world (the owner's standing rule on save compatibility).
 */
public final class FluidCheckpointCodec {
    public static final int VERSION=3;
    /** The {@code Base} of an awake island: it carries no certificate. */
    public static final long AWAKE=-1;
    /** Bound on the encoded ledger plus every island payload together, as before on the whole checkpoint. */
    private static final long MAXIMUM_BYTES=64L*1024*1024;
    private static final int MAXIMUM_JSON_CHARS=64*1024*1024;
    private static final int MAXIMUM_ISLANDS=10000;
    /** Tests and GameTests (the scheduler's self-verification switch) also re-encode every reused payload and
     * fail if the cache would have written anything else. */
    private static final boolean VERIFY=Boolean.getBoolean("createcheme.fluid.scheduler.verify");
    private static final Gson GSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().registerTypeAdapter(FlowControl.class,new ControlAdapter()).create();
    private FluidCheckpointCodec() {}
    public record IslandEntry(String dimension,String packageId,double compressibility,IslandCoordinator.Snapshot snapshot) {
        public IslandEntry {Objects.requireNonNull(dimension);Objects.requireNonNull(packageId);Objects.requireNonNull(snapshot);if(dimension.isBlank()||packageId.isBlank()||!Double.isFinite(compressibility)||compressibility<=0)throw new IllegalArgumentException("Invalid saved package");}
    }
    public record Checkpoint(List<IslandEntry> islands,BufferedTransfers.Snapshot transfers,List<FixedSplitModule.Snapshot> modules,List<CausalModuleCoordinator.Binding> moduleBindings) {
        public Checkpoint(List<IslandEntry> islands,BufferedTransfers.Snapshot transfers){this(islands,transfers,List.of(),List.of());}
        public Checkpoint(List<IslandEntry> islands,BufferedTransfers.Snapshot transfers,List<FixedSplitModule.Snapshot> modules){this(islands,transfers,modules,List.of());}
        public Checkpoint {
            islands=List.copyOf(islands);Objects.requireNonNull(transfers);modules=List.copyOf(modules);moduleBindings=List.copyOf(moduleBindings);
            if(modules.size()>4096||modules.stream().map(m->m.definition().id()).distinct().count()!=modules.size())throw new IllegalArgumentException("Invalid saved module identities/count");
            for(var module:modules)new FixedSplitModule(module,new BufferedTransfers(transfers),BufferBackpressure.defaults());
            var bufferIds=new HashSet<UUID>();var boundNodes=new HashSet<String>();
            for(var binding:moduleBindings) {
                if(!bufferIds.add(binding.buffer())||!boundNodes.add(binding.island()+":"+binding.reservoir())||!transfers.buffers().containsKey(binding.buffer()))throw new IllegalArgumentException("Invalid saved buffer binding");
                if(binding.island()==0)continue;
                var owner=islands.stream().filter(i->i.snapshot().id()==binding.island()).findFirst().orElseThrow(()->new IllegalArgumentException("Missing buffer island"));
                if(owner.snapshot().graph().reservoirs().stream().noneMatch(n->n.id()==binding.reservoir()&&n.kind()==PassiveNetwork.NodeKind.RESERVOIR))throw new IllegalArgumentException("Missing finite buffer reservoir");
            }
            if(!moduleBindings.isEmpty())for(var module:modules) {
                var d=module.definition();if(!bufferIds.contains(d.firstProduct())||!bufferIds.contains(d.secondProduct())||d.feeds().stream().anyMatch(f->!bufferIds.contains(f.buffer())))throw new IllegalArgumentException("Incomplete module bindings");
            }
            var ids=new HashSet<Long>();var stock=new HashSet<String>();
            for(var entry:islands) {
                if(!ids.add(entry.snapshot.id()))throw new IllegalArgumentException("Duplicate saved island identity");
                for(var node:entry.snapshot.graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!stock.add(entry.dimension+":"+node.id()))throw new IllegalArgumentException("Duplicate saved inventory owner");
            }
        }
    }
    public record PackageKey(String packageId,double compressibility) {}
    private record Phase(double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {}
    private record Node(long id,double elevation,String kind,PassiveNetwork.Inventory inventory,Phase phase) {}
    private record Control(String kind,double target,double limit,double efficiency) {}
    private record Pipe(long id,int first,int second,List<PipeResistance.Geometry> sections,Control control,int blockedDirections,InlineFilter filter) {}
    private record Graph(List<Node> nodes,List<Pipe> pipes) {}
    private record Anchor(String revision,Graph graph,List<FlowControl.Mode> modes) {}
    private record History(double seconds,double[] flows,int accepted,int rejected,double pumpWork,
                           List<ConservativeTransport.BoundaryTransfer> boundaries,Map<String,Integer> rejectionReasons,
                           List<FlowControl.Mode> modes,double[] heads,PassiveStepSolver.Acceptance acceptance,List<PipeTransfer> pipes) {}
    /** One island's opaque payload. {@code certifiedFrom} is the graph a certified island's interval started from,
     * null for an awake island; for a certified island {@code graph} and {@code history} are its certificate base. */
    private record Payload(String dimension,String packageId,double compressibility,String propertyRevision,Energy reference,
                           Graph graph,FallbackAllowance allowance,Anchor anchor,History history,String status,Map<UUID,Long> fences,Graph certifiedFrom) {}
    private record Energy(String revision,List<String> components,double[] offsets,boolean formationQualified) {}
    private record Parcel(double[] moles,double[] weights,double energy,Energy reference,SolidInventory solids) {}
    private record Pending(UUID id,UUID producer,UUID receiver,long dueTick,long revision,Parcel remaining) {}
    private record ProductionCapacity(int version,List<BufferedTransfers.CapacityReservation> reservations) {}
    private record Ledger(long revision,List<BufferedTransfers.Buffer> buffers,List<Pending> pending,ProductionCapacity productionCapacity) {}
    private record ModuleInput(UUID withdrawalId,long throughTick,double targetKg,Parcel owned) {}
    private record ModuleCycle(long startTick,long endTick,Map<UUID,ModuleInput> inputs,List<FixedSplitModule.Promise> promises,String status) {}
    private record ModuleState(String type,String scientificRevision,FixedSplitModule.Definition definition,long revision,long committedTick,boolean running,ModuleCycle cycle) {}
    private record Modules(int version,List<ModuleState> states,List<CausalModuleCoordinator.Binding> bindings) {}
    /** The world-level part of the checkpoint: the transfer ledger with its pending material, and the modules. */
    private record Core(Ledger transfers,Modules modules) {}

    /**
     * Encoded island payloads of one saved world, keyed by island on its revision and payload generation
     * ({@link IslandCoordinator.Snapshot#payloadGeneration()}), which changes whenever anything a payload records
     * changes and never when a certified island is only materialised. An island absent from a save is forgotten.
     * Owned by the thread that saves.
     */
    public static final class PayloadCache {
        private record Entry(long revision,long generation,byte[] payload,byte[] digest) {}
        private final Map<Long,Entry> entries=new HashMap<>();
        private long encoded,reused;
        /** Payloads encoded afresh and copied from the cache, over every save through this cache. */
        public long encoded(){return encoded;}
        public long reused(){return reused;}
        public int size(){return entries.size();}
        /** Forgets every payload, so the next save encodes every island (a cold save). */
        public void clear(){entries.clear();}
    }
    /** What one save wrote: islands, how many payloads were encoded and how many copied, and the bytes written. */
    public record Written(int islands,int encoded,int reused,long payloadBytes,long ledgerBytes) {
        public long bytes(){return payloadBytes+ledgerBytes;}
    }

    public static String encodeWorld(WorldTopologyLedger.Snapshot world) {
        String json=GSON.toJson(world);if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalStateException("World topology exceeds format bound");return json;
    }
    public static WorldTopologyLedger.Snapshot decodeWorld(String json) {
        if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalArgumentException("World topology exceeds format bound");
        var tree=JsonParser.parseString(json);strictShape(tree,WorldTopologyLedger.Snapshot.class,0,new int[]{0});
        var snapshot=GSON.fromJson(tree,WorldTopologyLedger.Snapshot.class);new WorldTopologyLedger(snapshot);return snapshot;
    }
    /** The world topology ledger without its online tick, which a checkpoint keeps as its epoch: what a save writes.
     * It changes only when the ledger commits, so a save can keep the encoding of an unchanged ledger. */
    private record WorldBody(long nextIdentity,Map<Long,WorldTopologyLedger.Registration> active,List<WorldTopologyLedger.Event> events,
                             WorldTopologyLedger.MaterialTotal constructed,WorldTopologyLedger.MaterialTotal destroyed,FluidBasis basis,Map<UUID,RecoveredSolid> recoveries) {}
    public static String encodeWorldBody(WorldTopologyLedger.Snapshot world) {
        String json=GSON.toJson(new WorldBody(world.nextIdentity(),world.active(),world.events(),world.constructed(),world.destroyed(),world.basis(),world.recoveries()));
        if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalStateException("World topology exceeds format bound");return json;
    }
    /** The ledger a checkpoint saved, at the checkpoint's epoch, validated as the ledger itself validates it. */
    public static WorldTopologyLedger.Snapshot decodeWorldBody(String json,long onlineTick) {
        if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalArgumentException("World topology exceeds format bound");
        var tree=JsonParser.parseString(json);strictShape(tree,WorldBody.class,0,new int[]{0});var body=GSON.fromJson(tree,WorldBody.class);
        var snapshot=new WorldTopologyLedger.Snapshot(onlineTick,body.nextIdentity,body.active,body.events,body.constructed,body.destroyed,body.basis,body.recoveries);
        new WorldTopologyLedger(snapshot);return snapshot;
    }
    /** Whether two ledger snapshots hold the same ledger, whatever their online ticks: a snapshot shares the ledger's
     * immutable parts with the state it was taken from, so an unchanged ledger is the same objects. */
    public static boolean sameWorldBody(WorldTopologyLedger.Snapshot a,WorldTopologyLedger.Snapshot b) {
        return a.nextIdentity()==b.nextIdentity()&&a.active()==b.active()&&a.events()==b.events()&&a.constructed()==b.constructed()
                &&a.destroyed()==b.destroyed()&&a.basis()==b.basis()&&a.recoveries()==b.recoveries();
    }
    static boolean verifying(){return VERIFY;}
    private static final class ControlAdapter implements JsonSerializer<FlowControl>,JsonDeserializer<FlowControl> {
        @Override public JsonElement serialize(FlowControl control,Type type,JsonSerializationContext context) {
            return context.serialize(switch(control) {
                case FlowControl.Passive ignored->new Control("passive",0,0,0);
                case FlowControl.Pump pump->new Control("pump",pump.targetVolumeFlow(),pump.maximumAddedPressure(),pump.efficiency());
                case FlowControl.PressureValve valve->new Control("valve",valve.targetPressure(),0,0);
            });
        }
        @Override public FlowControl deserialize(JsonElement json,Type type,JsonDeserializationContext context) {
            strictShape(json,Control.class,0,new int[]{0});Control c=context.deserialize(json,Control.class);
            return switch(c.kind){case "passive"->new FlowControl.Passive();case "pump"->new FlowControl.Pump(c.target,c.limit,c.efficiency);case "valve"->new FlowControl.PressureValve(c.target);default->throw new IllegalArgumentException("Unknown world control");};
        }
    }

    // ---------------- writing ----------------

    /** A self-contained checkpoint of {@code checkpoint} alone, with no cache, at the epoch of its most advanced island. */
    public static CompoundTag encode(Checkpoint checkpoint,Function<PackageKey,FluidThermodynamics> models) {
        return encode(checkpoint,checkpoint.islands.stream().mapToLong(i->i.snapshot.clock().onlineTick()).max().orElse(0),models);
    }
    public static CompoundTag encode(Checkpoint checkpoint,long epoch,Function<PackageKey,FluidThermodynamics> models) {
        var tag=new CompoundTag();write(tag,checkpoint,epoch,models,new PayloadCache());return tag;
    }
    /**
     * Writes format 3 into {@code tag}. An island whose revision and payload generation match its cache entry has
     * its payload copied; any other is encoded, and cached when its snapshot has a generation. No island may be ahead
     * of {@code epoch}. Refuses a checkpoint whose ledger and payloads together exceed the format bound.
     */
    public static Written write(CompoundTag tag,Checkpoint checkpoint,long epoch,Function<PackageKey,FluidThermodynamics> models,PayloadCache cache) {
        Objects.requireNonNull(tag);Objects.requireNonNull(models);Objects.requireNonNull(cache);
        if(epoch<0)throw new IllegalArgumentException("Negative world epoch");
        var islands=new ListTag();var present=new HashSet<Long>();long payloadBytes=0;int encoded=0,reused=0;
        for(var entry:checkpoint.islands) {
            var s=entry.snapshot;
            if(s.clock().onlineTick()>epoch)throw new IllegalStateException("Island "+s.id()+" at online tick "+s.clock().onlineTick()+" is ahead of the world epoch "+epoch);
            var cached=s.payloadGeneration()==0?null:cache.entries.get(s.id());
            byte[] payload,digest;
            if(cached!=null&&cached.revision==s.revision()&&cached.generation==s.payloadGeneration()) {
                payload=cached.payload;digest=cached.digest;reused++;
                if(VERIFY&&!Arrays.equals(payload,payload(entry,models)))throw new IllegalStateException("Cached checkpoint payload of island "+s.id()+" at generation "+s.payloadGeneration()+" no longer matches the island");
            } else {
                payload=payload(entry,models);digest=sha256(payload);encoded++;
                if(s.payloadGeneration()!=0)cache.entries.put(s.id(),new PayloadCache.Entry(s.revision(),s.payloadGeneration(),payload,digest));
            }
            payloadBytes+=payload.length;
            if(payloadBytes>MAXIMUM_BYTES)throw new IllegalStateException("Fluid checkpoint exceeds the configured format bound");
            present.add(s.id());
            var island=new CompoundTag();
            island.putLong("Id",s.id());island.putLong("Revision",s.revision());
            island.putLong("Online",s.clock().onlineTick());island.putLong("Committed",s.clock().committedTick());island.putLong("Retry",s.clock().retryAtTick());island.putInt("Cadence",s.clock().cadenceTicks());
            var persisted=s.certificate().flatMap(IslandCoordinator.Certified::saved);
            island.putLong("Base",persisted.map(IslandCertificate.Saved::baseTick).orElse(AWAKE));
            persisted.ifPresent(saved->{
                var certificate=new CompoundTag();
                certificate.putString("Kind",saved.kind().name());certificate.putLong("Since",saved.sinceTick());
                certificate.putLong("Start",saved.interval().startTick());certificate.putLong("Horizon",saved.horizonTick());
                certificate.putString("PropertyRevision",saved.signature().propertyRevision());certificate.putString("Policy",saved.signature().policy());certificate.putString("Graph",saved.signature().graph());
                island.put("Certificate",certificate);
            });
            // Copied, never shared: the cached bytes must outlive whatever happens to this tag.
            island.putByteArray("Payload",payload.clone());island.putByteArray("PayloadSHA256",digest.clone());
            islands.add(island);
        }
        cache.entries.keySet().retainAll(present);cache.encoded+=encoded;cache.reused+=reused;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.payloadsEncoded,encoded);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.payloadsReused,reused);
        byte[] ledger=ledger(checkpoint);
        if(payloadBytes+ledger.length>MAXIMUM_BYTES)throw new IllegalStateException("Fluid checkpoint exceeds the configured format bound");
        tag.putInt("FluidFormat",VERSION);tag.putLong("Epoch",epoch);
        tag.putByteArray("Ledger",ledger);tag.putByteArray("LedgerSHA256",sha256(ledger));
        tag.put("Islands",islands);tag.putByteArray("SHA256",manifest(tag));
        return new Written(islands.size(),encoded,reused,payloadBytes,ledger.length);
    }
    private static byte[] payload(IslandEntry entry,Function<PackageKey,FluidThermodynamics> models) {
        var model=Objects.requireNonNull(models.apply(new PackageKey(entry.packageId,entry.compressibility)));var s=entry.snapshot;
        var persisted=s.certificate().flatMap(IslandCoordinator.Certified::saved);
        // A certified island records its certificate base: the solved interval's end state and the interval itself.
        var graph=persisted.map(saved->saved.interval().result().graph()).orElse(s.graph());
        var result=persisted.isPresent()?Optional.of(persisted.orElseThrow().interval().result()):s.lastResult();
        Anchor anchor=s.anchor().map(a->new Anchor(a.propertyRevision(),graph(a.graph()),a.modes())).orElse(null);
        History history=result.map(r->new History(r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),r.boundaries(),
                new TreeMap<>(r.rejectionReasons()),r.endpointModes(),r.endpointHeads(),r.acceptance(),r.pipeTransfers())).orElse(null);
        var payload=new Payload(entry.dimension,entry.packageId,entry.compressibility,ApproximationAnchor.thermodynamicRevision(model),energy(EnergyReference.sensible(model.components())),
                graph(graph),s.allowance(),anchor,history,s.status(),new TreeMap<>(s.fences()),persisted.map(saved->graph(saved.interval().before())).orElse(null));
        return GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] ledger(Checkpoint checkpoint) {
        var pending=checkpoint.transfers.pending().values().stream().sorted(Comparator.comparing(p->p.id().toString())).map(p->new Pending(p.id(),p.producer(),p.receiver(),p.dueTick(),p.revision(),parcel(p.remaining()))).toList();
        var buffers=checkpoint.transfers.buffers().values().stream().sorted(Comparator.comparing(b->b.id().toString())).toList();
        var capacity=new ProductionCapacity(1,checkpoint.transfers.planned().values().stream().sorted(Comparator.comparing(r->r.id().toString())).toList());
        var core=new Core(new Ledger(checkpoint.transfers.revision(),buffers,pending,capacity),new Modules(1,checkpoint.modules.stream().map(FluidCheckpointCodec::module).toList(),checkpoint.moduleBindings));
        return GSON.toJson(core).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------- reading ----------------

    /**
     * Reads and validates format 3. Refuses any other format (there is no upgrade: create a fresh world), a checksum
     * mismatch, a missing or mistyped field, a changed thermodynamic basis, and every invalid certificate field: a
     * base tick later than the committed tick, a horizon earlier than it, an interval that does not end at the base,
     * nonfinite deltas, and fences before the committed tick. A certified island is materialised from its base to its
     * committed tick without a solve; whether its certificate still holds is decided when it is registered.
     */
    public static Checkpoint decode(CompoundTag tag,Function<PackageKey,FluidThermodynamics> models) {
        Objects.requireNonNull(tag);Objects.requireNonNull(models);
        if(!tag.contains("FluidFormat",Tag.TAG_INT))throw new IllegalArgumentException("Not a fluid checkpoint: no FluidFormat");
        int format=tag.getInt("FluidFormat");
        if(format!=VERSION)throw new IllegalArgumentException("Fluid checkpoint format "+format+" cannot be read: this build reads format "+VERSION
                +" only and has no upgrade from older formats. Create a fresh world for this development build.");
        long epoch=requireLong(tag,"Epoch","checkpoint");if(epoch<0)throw new IllegalArgumentException("Negative world epoch");
        byte[] ledger=requireBytes(tag,"Ledger","checkpoint");requireDigest(ledger,requireBytes(tag,"LedgerSHA256","checkpoint"),"ledger");
        if(!(tag.get("Islands") instanceof ListTag list)||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Missing or mistyped fluid checkpoint field Islands");
        if(list.size()>MAXIMUM_ISLANDS)throw new IllegalArgumentException("Too many saved islands");
        long bytes=ledger.length;for(var element:list)bytes+=requireBytes((CompoundTag)element,"Payload","island").length;
        if(bytes>MAXIMUM_BYTES)throw new IllegalArgumentException("Fluid checkpoint exceeds format bound");
        if(!MessageDigest.isEqual(manifest(tag),requireBytes(tag,"SHA256","checkpoint")))throw new IllegalArgumentException("Fluid checkpoint checksum mismatch");
        var islands=new ArrayList<IslandEntry>();
        for(var element:list)islands.add(island((CompoundTag)element,epoch,models));
        var core=json(ledger,Core.class,"ledger");
        if(core.transfers==null||core.modules==null||core.transfers.productionCapacity==null)throw new IllegalArgumentException("Incomplete saved ledger");
        var buffers=new HashMap<UUID,BufferedTransfers.Buffer>();var pending=new HashMap<UUID,PendingTransfers.Pending>();
        for(var buffer:core.transfers.buffers)if(buffers.putIfAbsent(buffer.id(),buffer)!=null)throw new IllegalArgumentException("Duplicate saved buffer");
        for(var p:core.transfers.pending)if(pending.putIfAbsent(p.id,new PendingTransfers.Pending(p.id,p.producer,p.receiver,p.dueTick,p.revision,parcel(p.remaining)))!=null)throw new IllegalArgumentException("Duplicate saved pending material");
        var planned=new HashMap<UUID,BufferedTransfers.CapacityReservation>();var capacity=core.transfers.productionCapacity;
        if(capacity.version!=1)throw new IllegalArgumentException("Unsupported production-capacity version");
        for(var reservation:capacity.reservations)if(planned.putIfAbsent(reservation.id(),reservation)!=null)throw new IllegalArgumentException("Duplicate saved production reservation");
        if(core.modules.version!=1)throw new IllegalArgumentException("Unsupported module-state version");
        var modules=new ArrayList<FixedSplitModule.Snapshot>();for(var saved:core.modules.states)modules.add(module(saved));
        return new Checkpoint(islands,new BufferedTransfers.Snapshot(core.transfers.revision,buffers,pending,planned),modules,core.modules.bindings);
    }
    /** The world epoch of a checkpoint that {@link #decode} accepted. */
    public static long epoch(CompoundTag tag){return requireLong(tag,"Epoch","checkpoint");}
    private static IslandEntry island(CompoundTag island,long epoch,Function<PackageKey,FluidThermodynamics> models) {
        long id=requireLong(island,"Id","island"),revision=requireLong(island,"Revision","island");
        long online=requireLong(island,"Online","island"),committed=requireLong(island,"Committed","island"),retry=requireLong(island,"Retry","island");
        int cadence=requireInt(island,"Cadence","island");long base=requireLong(island,"Base","island");
        byte[] bytes=requireBytes(island,"Payload","island");requireDigest(bytes,requireBytes(island,"PayloadSHA256","island"),"payload of island "+id);
        if(online>epoch)throw new IllegalArgumentException("Island "+id+" at online tick "+online+" is ahead of the checkpoint's world epoch "+epoch);
        var clock=new IslandClock.Snapshot(online,committed,retry,cadence);
        var saved=json(bytes,Payload.class,"payload of island "+id);
        var model=Objects.requireNonNull(models.apply(new PackageKey(saved.packageId,saved.compressibility)));
        if(!saved.propertyRevision.equals(ApproximationAnchor.thermodynamicRevision(model)))throw new IllegalArgumentException("Incompatible fluid property basis; use a fresh development world or explicitly reset its fluid data: "+saved.packageId);
        var basis=model.components();var reference=reference(saved.reference);
        if(!reference.revision().equals(EnergyReference.sensible(basis).revision())||!basis.equals(reference.components())||reference.formationDataQualified())throw new IllegalArgumentException("Saved energy reference is not this build's; use a fresh development world");
        for(int c=0;c<basis.size();c++)if(reference.offsetJoulesPerMole(c)!=0)throw new IllegalArgumentException("Saved energy offsets are not this build's; use a fresh development world");
        for(var fence:saved.fences.entrySet())if(fence.getValue()<committed)throw new IllegalArgumentException("Island "+id+": fence "+fence.getKey()+" at tick "+fence.getValue()+" precedes the committed tick "+committed);
        var graph=graph(saved.graph,model);
        Optional<ApproximationAnchor> anchor=saved.anchor==null?Optional.empty():Optional.of(new ApproximationAnchor(saved.anchor.revision,graph(saved.anchor.graph,model),saved.anchor.modes));
        Optional<PassiveIntervalSolver.Result> result=Optional.empty();
        if(saved.history!=null) {
            var h=saved.history;
            if(h.seconds<=0||h.accepted<1||h.rejected<0||h.flows.length!=graph.pipes().size()||h.modes.size()!=graph.pipes().size()||h.heads.length!=graph.pipes().size()||h.pipes.size()!=graph.pipes().size())throw new IllegalArgumentException("Invalid saved interval history");
            result=Optional.of(new PassiveIntervalSolver.Result(graph,h.seconds,h.flows,h.accepted,h.rejected,h.pumpWork,h.boundaries,h.rejectionReasons,h.modes,h.heads,h.acceptance,h.pipes));
        }
        var dimension=saved.dimension;var packageId=saved.packageId;double compressibility=saved.compressibility;
        if(base==AWAKE) {
            if(island.contains("Certificate")||saved.certifiedFrom!=null)throw new IllegalArgumentException("Awake island "+id+" carries a certificate");
            return new IslandEntry(dimension,packageId,compressibility,new IslandCoordinator.Snapshot(id,revision,graph,clock,saved.allowance,anchor,result,saved.status,saved.fences));
        }
        if(base<0)throw new IllegalArgumentException("Island "+id+": invalid certificate base tick "+base);
        if(!(island.get("Certificate") instanceof CompoundTag certificate)||saved.certifiedFrom==null||result.isEmpty())throw new IllegalArgumentException("Certified island "+id+" lacks its certificate record");
        IslandCertificate.Kind kind;
        try{kind=IslandCertificate.Kind.valueOf(requireString(certificate,"Kind","certificate"));}catch(IllegalArgumentException unknown){throw new IllegalArgumentException("Island "+id+": unknown certificate kind");}
        long since=requireLong(certificate,"Since","certificate"),start=requireLong(certificate,"Start","certificate"),horizon=requireLong(certificate,"Horizon","certificate");
        if(base>committed)throw new IllegalArgumentException("Certified island "+id+": base tick "+base+" is later than its committed tick "+committed);
        if(horizon<committed)throw new IllegalArgumentException("Certified island "+id+": horizon "+horizon+" is earlier than its committed tick "+committed);
        if(start<0||start>=base)throw new IllegalArgumentException("Certified island "+id+": interval ["+start+", "+base+"] is empty or negative");
        var signature=new IslandCertificate.Signature(requireString(certificate,"PropertyRevision","certificate"),requireString(certificate,"Policy","certificate"),requireString(certificate,"Graph","certificate"));
        var persisted=new IslandCertificate.Saved(kind,since,horizon,new IslandCertificate.Interval(start,base,graph(saved.certifiedFrom,model),result.orElseThrow()),signature);
        // The island at its saved committed tick: its base inventory plus the replayed fraction, never a solve.
        var rebuilt=IslandCertificate.rebuild(persisted,model.molecularWeights());
        var certified=new IslandCoordinator.Certified(kind,since,base,horizon,rebuilt.largestFlow(),Optional.of(persisted));
        return new IslandEntry(dimension,packageId,compressibility,new IslandCoordinator.Snapshot(id,revision,rebuilt.graphAt(committed),clock,saved.allowance,anchor,result,saved.status,saved.fences,Optional.of(certified)));
    }
    /** A graph in the payload's JSON shape, for fixtures that keep one (the archived gameplay states). */
    static PassiveNetwork decodeGraph(JsonElement graph,FluidThermodynamics model) {
        strictShape(graph,Graph.class,0,new int[]{0});return graph(GSON.fromJson(graph,Graph.class),model);
    }
    private static <T> T json(byte[] bytes,Class<T> type,String what) {
        var tree=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
        if(!tree.isJsonObject())throw new IllegalArgumentException("Invalid saved "+what);
        strictShape(tree,type,0,new int[]{0});return GSON.fromJson(tree,type);
    }

    // ---------------- integrity ----------------

    /** The envelope digest: the format, the epoch, the ledger digest, and per island every scalar field, the certificate fields and the payload digest. */
    private static byte[] manifest(CompoundTag tag) {
        var d=new Digest();d.text("createcheme-fluid-checkpoint-3");d.number(requireLong(tag,"Epoch","checkpoint"));d.bytes(requireBytes(tag,"LedgerSHA256","checkpoint"));
        var list=(ListTag)tag.get("Islands");d.number(list.size());
        for(var element:list) {
            var island=(CompoundTag)element;
            for(var key:List.of("Id","Revision","Online","Committed","Retry"))d.number(requireLong(island,key,"island"));
            d.number(requireInt(island,"Cadence","island"));d.number(requireLong(island,"Base","island"));
            if(island.get("Certificate") instanceof CompoundTag certificate) {
                d.text("certificate");d.text(requireString(certificate,"Kind","certificate"));
                for(var key:List.of("Since","Start","Horizon"))d.number(requireLong(certificate,key,"certificate"));
                for(var key:List.of("PropertyRevision","Policy","Graph"))d.text(requireString(certificate,key,"certificate"));
            } else if(island.contains("Certificate"))throw new IllegalArgumentException("Mistyped fluid certificate field");
            else d.text("awake");
            d.bytes(requireBytes(island,"PayloadSHA256","island"));
        }
        return d.digest();
    }
    /** Recomputes every digest of a checkpoint from its current fields: for tests that alter a field and must reach
     * the validation of that field rather than the checksum. */
    static void reseal(CompoundTag tag) {
        tag.putByteArray("LedgerSHA256",sha256(tag.getByteArray("Ledger")));
        for(var element:(ListTag)tag.get("Islands")){var island=(CompoundTag)element;island.putByteArray("PayloadSHA256",sha256(island.getByteArray("Payload")));}
        tag.putByteArray("SHA256",manifest(tag));
    }
    /** The ledger JSON of a checkpoint, for tests. */
    static JsonObject ledgerJson(CompoundTag tag){return JsonParser.parseString(new String(tag.getByteArray("Ledger"),StandardCharsets.UTF_8)).getAsJsonObject();}
    /** A copy of a checkpoint with its ledger JSON replaced and every digest recomputed, for tests. */
    static CompoundTag withLedger(CompoundTag tag,JsonObject ledger){var copy=tag.copy();copy.putByteArray("Ledger",ledger.toString().getBytes(StandardCharsets.UTF_8));reseal(copy);return copy;}
    /** The payload JSON of a checkpoint's {@code index}-th island, for tests. */
    static JsonObject payloadJson(CompoundTag tag,int index){return JsonParser.parseString(new String(((ListTag)tag.get("Islands")).getCompound(index).getByteArray("Payload"),StandardCharsets.UTF_8)).getAsJsonObject();}
    /** A copy of a checkpoint with one island's payload JSON replaced and every digest recomputed, for tests. */
    static CompoundTag withPayload(CompoundTag tag,int index,JsonObject payload) {
        var copy=tag.copy();((ListTag)copy.get("Islands")).getCompound(index).putByteArray("Payload",payload.toString().getBytes(StandardCharsets.UTF_8));reseal(copy);return copy;
    }
    private static final class Digest {
        private final MessageDigest sha;private final ByteBuffer buffer=ByteBuffer.allocate(8);
        Digest(){try{sha=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
        void number(long value){buffer.clear();buffer.putLong(value);sha.update(buffer.array(),0,8);}
        void bytes(byte[] value){number(value.length);sha.update(value);}
        void text(String value){bytes(value.getBytes(StandardCharsets.UTF_8));}
        byte[] digest(){return sha.digest();}
    }
    private static byte[] sha256(byte[] bytes) {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void requireDigest(byte[] bytes,byte[] digest,String what){if(!MessageDigest.isEqual(sha256(bytes),digest))throw new IllegalArgumentException("Fluid checkpoint checksum mismatch in the "+what);}
    private static long requireLong(CompoundTag tag,String key,String where){if(!tag.contains(key,Tag.TAG_LONG))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return tag.getLong(key);}
    private static int requireInt(CompoundTag tag,String key,String where){if(!tag.contains(key,Tag.TAG_INT))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return tag.getInt(key);}
    private static String requireString(CompoundTag tag,String key,String where){if(!tag.contains(key,Tag.TAG_STRING))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return tag.getString(key);}
    private static byte[] requireBytes(CompoundTag tag,String key,String where) {
        if(!(tag.get(key) instanceof ByteArrayTag bytes))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return bytes.getAsByteArray();
    }

    // ---------------- shared record conversions ----------------

    private static ModuleState module(FixedSplitModule.Snapshot state) {
        ModuleCycle cycle=null;
        if(state.cycle()!=null) {
            var c=state.cycle();var inputs=new LinkedHashMap<UUID,ModuleInput>();
            for(var feed:state.definition().feeds()){var input=c.inputs().get(feed.buffer());inputs.put(feed.buffer(),new ModuleInput(input.withdrawalId(),input.throughTick(),input.targetKg(),parcel(input.owned())));}
            cycle=new ModuleCycle(c.startTick(),c.endTick(),inputs,c.promises(),c.status());
        }
        return new ModuleState(FixedSplitModule.TYPE,FixedSplitModule.SCIENTIFIC_REVISION,state.definition(),state.revision(),state.committedTick(),state.running(),cycle);
    }
    private static FixedSplitModule.Snapshot module(ModuleState state) {
        if(!FixedSplitModule.TYPE.equals(state.type)||!FixedSplitModule.SCIENTIFIC_REVISION.equals(state.scientificRevision))throw new IllegalArgumentException("Saved module type or scientific revision is not this build's; use a fresh development world");
        FixedSplitModule.Cycle cycle=null;
        if(state.cycle!=null) {
            var c=state.cycle;var inputs=new HashMap<UUID,FixedSplitModule.Input>();
            c.inputs.forEach((id,input)->inputs.put(id,new FixedSplitModule.Input(input.withdrawalId,input.throughTick,input.targetKg,parcel(input.owned))));
            cycle=new FixedSplitModule.Cycle(c.startTick,c.endTick,inputs,c.promises,c.status);
        }
        return new FixedSplitModule.Snapshot(state.definition,state.revision,state.committedTick,state.running,cycle);
    }
    private static Graph graph(PassiveNetwork graph) {
        if(!graph.scheduledTransfers().isEmpty())throw new IllegalArgumentException("Request-scoped transfers must be resolved into the committed ledger before saving");
        return new Graph(graph.reservoirs().stream().map(n->{var s=n.state();return new Node(n.id(),n.elevation(),n.kind().name(),n.inventory(),new Phase(s.temperature(),s.pressure(),s.liquid(),s.vapor(),s.waterLiquid(),s.waterVapor(),s.hydrocarbonPartialPressure()));}).toList(),
                graph.pipes().stream().map(p->new Pipe(p.id(),p.first(),p.second(),p.sections(),switch(p.control()) {
                    case FlowControl.Passive ignored->new Control("passive",0,0,0);
                    case FlowControl.Pump pump->new Control("pump",pump.targetVolumeFlow(),pump.maximumAddedPressure(),pump.efficiency());
                    case FlowControl.PressureValve valve->new Control("valve",valve.targetPressure(),0,0);
                },p.blockedDirections(),p.filter())).toList());
    }
    private static PassiveNetwork graph(Graph saved,FluidThermodynamics model) {
        if(saved.nodes.isEmpty()||saved.nodes.size()>10000||saved.pipes.size()>100000)throw new IllegalArgumentException("Invalid saved graph size");
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(var n:saved.nodes) {
            var kind=PassiveNetwork.NodeKind.valueOf(n.kind);if(kind==PassiveNetwork.NodeKind.PORT)throw new IllegalArgumentException("Internal port cannot be persisted");
            var p=n.phase;var state=Arrays.stream(n.inventory.moles()).sum()==0&&!n.inventory.solids().empty()?model.solidState(p.temperature,p.pressure,n.inventory.solids()):model.state(p.temperature,p.pressure,p.liquid,p.vapor,p.waterLiquid,p.waterVapor,p.hydrocarbonPressure).withSolids(n.inventory.solids());
            model.solids.validate(n.inventory.solids());
            nodes.add(new PassiveNetwork.Reservoir(n.id,n.elevation,state,kind,n.inventory));
        }
        for(var pipe:saved.pipes)if(pipe.filter!=null)model.solids.validate(pipe.filter.captured());
        return new PassiveNetwork(nodes,saved.pipes.stream().map(p->new PassiveNetwork.Pipe(p.id,p.first,p.second,p.sections,switch(p.control.kind) {
            case "passive"->new FlowControl.Passive();case "pump"->new FlowControl.Pump(p.control.target,p.control.limit,p.control.efficiency);case "valve"->new FlowControl.PressureValve(p.control.target);default->throw new IllegalArgumentException("Unknown saved control");
        },p.blockedDirections,currentFilter(p.filter,model))).toList());
    }
    private static InlineFilter currentFilter(InlineFilter saved,FluidThermodynamics model){
        if(saved==null)return null;var settings=model.solidSettings;
        return new InlineFilter(settings.filterCapacity(),settings.filterResistance(),saved.captured(),saved.energyJoule(),saved.stoppedAtCapacity()&&saved.capacity()==settings.filterCapacity());
    }
    private static Energy energy(EnergyReference ref){double[] offsets=new double[ref.components().size()];for(int c=0;c<offsets.length;c++)offsets[c]=ref.offsetJoulesPerMole(c);return new Energy(ref.revision(),ref.components(),offsets,ref.formationDataQualified());}
    private static EnergyReference reference(Energy ref){return new EnergyReference(ref.revision,ref.components,ref.offsets,ref.formationQualified);}
    private static Parcel parcel(MaterialParcel p){return new Parcel(p.moles(),p.molecularWeights(),p.internalEnergy(),energy(p.reference()),p.solids());}
    private static MaterialParcel parcel(Parcel p){return new MaterialParcel(p.moles,p.weights,p.energy,reference(p.reference),p.solids);}

    /** Reject missing fields and fractional integer stamps instead of allowing Gson's zero/coercion defaults. */
    private static void strictShape(JsonElement value,Type type,int depth,int[] count) {
        if(depth>48||++count[0]>2_000_000)throw new IllegalArgumentException("Checkpoint structure exceeds bound");
        if(value==null)throw new IllegalArgumentException("Missing checkpoint field");
        if(value.isJsonNull()) {
            if(type instanceof Class<?> c&&c.isPrimitive())throw new IllegalArgumentException("Null primitive checkpoint field");
            return;
        }
        if(type instanceof ParameterizedType p) {
            if(p.getRawType()==List.class)for(var entry:value.getAsJsonArray())strictShape(entry,p.getActualTypeArguments()[0],depth+1,count);
            else if(p.getRawType()==Map.class)for(var entry:value.getAsJsonObject().entrySet())strictShape(entry.getValue(),p.getActualTypeArguments()[1],depth+1,count);
            return;
        }
        if(!(type instanceof Class<?> c))throw new IllegalArgumentException("Unknown checkpoint field type");
        if(c.isArray()){for(var entry:value.getAsJsonArray())strictShape(entry,c.getComponentType(),depth+1,count);return;}
        if(c.isRecord()) {
            var object=value.getAsJsonObject();
            for(var field:c.getRecordComponents())strictShape(object.get(field.getName()),field.getGenericType(),depth+1,count);
        } else if(c==long.class||c==Long.class)value.getAsBigDecimal().longValueExact();
        else if(c==int.class||c==Integer.class)value.getAsBigDecimal().intValueExact();
        else if(c==double.class||c==Double.class){if(!Double.isFinite(value.getAsDouble()))throw new IllegalArgumentException("Nonfinite checkpoint number");}
    }
}
