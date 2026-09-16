package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;

/**
 * Versioned codec for committed science/runtime state. Rebuilds properties through the immutable model;
 * it never deserializes implementation caches or creates a nitrogen charge. World block/event identities
 * are supplied by the encompassing world-authority format, not inferred from loaded chunks here.
 */
public final class FluidCheckpointCodec {
    public static final int VERSION=1;
    private static final int MAXIMUM_JSON_CHARS=64*1024*1024;
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
    private record Pipe(long id,int first,int second,List<PipeResistance.Geometry> sections,Control control) {}
    private record Graph(List<Node> nodes,List<Pipe> pipes) {}
    private record Anchor(String revision,Graph graph,List<FlowControl.Mode> modes) {}
    private record History(double seconds,double[] flows,int accepted,int rejected,double pumpWork,
                           List<ConservativeTransport.BoundaryTransfer> boundaries,Map<String,Integer> rejectionReasons,
                           List<FlowControl.Mode> modes,double[] heads,PassiveStepSolver.Acceptance acceptance,List<PipeTransfer> pipes) {}
    private record Island(String dimension,String packageId,double compressibility,String propertyRevision,Energy reference,
                          long id,long revision,Graph graph,IslandClock.Snapshot clock,FallbackAllowance allowance,
                          Anchor anchor,History history,String status,Map<UUID,Long> fences) {}
    private record Energy(String revision,List<String> components,double[] offsets,boolean formationQualified) {}
    private record Parcel(double[] moles,double[] weights,double energy,Energy reference) {}
    private record Pending(UUID id,UUID producer,UUID receiver,long dueTick,long revision,Parcel remaining) {}
    private record ProductionCapacity(int version,List<BufferedTransfers.CapacityReservation> reservations) {}
    private record Ledger(long revision,List<BufferedTransfers.Buffer> buffers,List<Pending> pending,ProductionCapacity productionCapacity) {}
    private record ModuleInput(UUID withdrawalId,long throughTick,double targetKg,Parcel owned) {}
    private record ModuleCycle(long startTick,long endTick,Map<UUID,ModuleInput> inputs,List<FixedSplitModule.Promise> promises,String status) {}
    private record ModuleState(String type,String scientificRevision,FixedSplitModule.Definition definition,long revision,long committedTick,boolean running,ModuleCycle cycle) {}
    private record Modules(int version,List<ModuleState> states,List<CausalModuleCoordinator.Binding> bindings) {}
    private record Envelope(int version,List<Island> islands,Ledger transfers,Modules modules) {}

    public static String encodeWorld(WorldTopologyLedger.Snapshot world) {
        String json=GSON.toJson(world);if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalStateException("World topology exceeds format bound");return json;
    }
    public static WorldTopologyLedger.Snapshot decodeWorld(String json) {
        if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalArgumentException("World topology exceeds format bound");
        var tree=JsonParser.parseString(json);strictShape(tree,WorldTopologyLedger.Snapshot.class,0,new int[]{0});
        var snapshot=GSON.fromJson(tree,WorldTopologyLedger.Snapshot.class);new WorldTopologyLedger(snapshot);return snapshot;
    }
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

    public static String encode(Checkpoint checkpoint,Function<PackageKey,FluidThermodynamics> models) {
        var islands=new ArrayList<Island>();
        for(var entry:checkpoint.islands) {
            var model=Objects.requireNonNull(models.apply(new PackageKey(entry.packageId,entry.compressibility)));var s=entry.snapshot;
            var basis=model.components();
            Anchor anchor=s.anchor().map(a->new Anchor(a.propertyRevision(),graph(a.graph()),a.modes())).orElse(null);
            History history=s.lastResult().map(r->new History(r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),r.boundaries(),r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.acceptance(),r.pipeTransfers())).orElse(null);
            islands.add(new Island(entry.dimension,entry.packageId,entry.compressibility,ApproximationAnchor.thermodynamicRevision(model),energy(EnergyReference.sensible(basis)),s.id(),s.revision(),graph(s.graph()),s.clock(),s.allowance(),anchor,history,s.status(),s.fences()));
        }
        var pending=checkpoint.transfers.pending().values().stream().sorted(Comparator.comparing(p->p.id().toString())).map(p->new Pending(p.id(),p.producer(),p.receiver(),p.dueTick(),p.revision(),parcel(p.remaining()))).toList();
        var buffers=checkpoint.transfers.buffers().values().stream().sorted(Comparator.comparing(b->b.id().toString())).toList();
        var capacity=new ProductionCapacity(1,checkpoint.transfers.planned().values().stream().sorted(Comparator.comparing(r->r.id().toString())).toList());
        String encoded=GSON.toJson(new Envelope(VERSION,islands,new Ledger(checkpoint.transfers.revision(),buffers,pending,capacity),new Modules(1,checkpoint.modules.stream().map(FluidCheckpointCodec::module).toList(),checkpoint.moduleBindings)));
        if(encoded.length()>MAXIMUM_JSON_CHARS)throw new IllegalStateException("Fluid checkpoint exceeds the configured format bound");return encoded;
    }
    public static Checkpoint decode(String json,Function<PackageKey,FluidThermodynamics> models) {
        var envelope=readEnvelope(json);
        return decodeEnvelope(envelope,models);
    }
    private static Envelope readEnvelope(String json) {
        if(json.length()>MAXIMUM_JSON_CHARS)throw new IllegalArgumentException("Fluid checkpoint exceeds format bound");
        var tree=JsonParser.parseString(json);
        // Backward-compatible optional extension. Legacy checkpoints contain no unmaterialized
        // production promises; all of their actual pending material is preserved unchanged.
        var ledgerTree=tree.getAsJsonObject().getAsJsonObject("transfers");
        if(ledgerTree!=null&&!ledgerTree.has("productionCapacity"))ledgerTree.add("productionCapacity",JsonNull.INSTANCE);
        if(!tree.getAsJsonObject().has("modules"))tree.getAsJsonObject().add("modules",JsonNull.INSTANCE);
        var moduleTree=tree.getAsJsonObject().get("modules");if(moduleTree!=null&&moduleTree.isJsonObject()&&!moduleTree.getAsJsonObject().has("bindings"))moduleTree.getAsJsonObject().add("bindings",new JsonArray());
        if(moduleTree!=null&&moduleTree.isJsonObject()&&moduleTree.getAsJsonObject().has("states"))for(var state:moduleTree.getAsJsonObject().getAsJsonArray("states")) {
            var object=state.getAsJsonObject();
            // The first extension encoded this one test-module type implicitly. Preserve it;
            // partially missing or unknown newer identities still fail strict validation.
            if(!object.has("type")&&!object.has("scientificRevision")){object.addProperty("type",FixedSplitModule.TYPE);object.addProperty("scientificRevision",FixedSplitModule.SCIENTIFIC_REVISION);}
        }
        strictShape(tree,Envelope.class,0,new int[]{0});
        var envelope=GSON.fromJson(tree,Envelope.class);
        if(envelope.version!=VERSION)throw new IllegalArgumentException("Unsupported fluid checkpoint version "+envelope.version);
        if(envelope.islands.size()>10000)throw new IllegalArgumentException("Too many saved islands");
        return envelope;
    }
    private static Checkpoint decodeEnvelope(Envelope envelope,Function<PackageKey,FluidThermodynamics> models) {
        var islands=new ArrayList<IslandEntry>();
        for(var saved:envelope.islands) {
            var model=Objects.requireNonNull(models.apply(new PackageKey(saved.packageId,saved.compressibility)));
            if(!saved.propertyRevision.equals(ApproximationAnchor.thermodynamicRevision(model)))throw new IllegalArgumentException("Saved properties require explicit migration: "+saved.packageId);
            var basis=model.components();var reference=reference(saved.reference);
            if(!reference.revision().equals(EnergyReference.sensible(basis).revision())||!basis.equals(reference.components())||reference.formationDataQualified())throw new IllegalArgumentException("Saved energy reference requires explicit migration");
            for(int c=0;c<basis.size();c++)if(reference.offsetJoulesPerMole(c)!=0)throw new IllegalArgumentException("Saved energy offsets require explicit migration");
            var graph=graph(saved.graph,model);
            Optional<ApproximationAnchor> anchor=saved.anchor==null?Optional.empty():Optional.of(new ApproximationAnchor(saved.anchor.revision,graph(saved.anchor.graph,model),saved.anchor.modes));
            Optional<PassiveIntervalSolver.Result> result=Optional.empty();
            if(saved.history!=null) {
                var h=saved.history;
                if(h.seconds<=0||h.accepted<1||h.rejected<0||h.flows.length!=graph.pipes().size()||h.modes.size()!=graph.pipes().size()||h.heads.length!=graph.pipes().size()||h.pipes.size()!=graph.pipes().size())throw new IllegalArgumentException("Invalid saved interval history");
                result=Optional.of(new PassiveIntervalSolver.Result(graph,h.seconds,h.flows,h.accepted,h.rejected,h.pumpWork,h.boundaries,h.rejectionReasons,h.modes,h.heads,h.acceptance,h.pipes));
            }
            islands.add(new IslandEntry(saved.dimension,saved.packageId,saved.compressibility,new IslandCoordinator.Snapshot(saved.id,saved.revision,graph,saved.clock,saved.allowance,anchor,result,saved.status,saved.fences)));
        }
        var buffers=new HashMap<UUID,BufferedTransfers.Buffer>();var pending=new HashMap<UUID,PendingTransfers.Pending>();
        for(var buffer:envelope.transfers.buffers)if(buffers.putIfAbsent(buffer.id(),buffer)!=null)throw new IllegalArgumentException("Duplicate saved buffer");
        for(var p:envelope.transfers.pending)if(pending.putIfAbsent(p.id,new PendingTransfers.Pending(p.id,p.producer,p.receiver,p.dueTick,p.revision,parcel(p.remaining)))!=null)throw new IllegalArgumentException("Duplicate saved pending material");
        var planned=new HashMap<UUID,BufferedTransfers.CapacityReservation>();
        var capacity=envelope.transfers.productionCapacity;
        if(capacity!=null) {
            if(capacity.version!=1)throw new IllegalArgumentException("Unsupported production-capacity version");
            for(var reservation:capacity.reservations)if(planned.putIfAbsent(reservation.id(),reservation)!=null)throw new IllegalArgumentException("Duplicate saved production reservation");
        }
        var modules=new ArrayList<FixedSplitModule.Snapshot>();
        if(envelope.modules!=null) {
            if(envelope.modules.version!=1)throw new IllegalArgumentException("Unsupported module-state version");
            for(var saved:envelope.modules.states)modules.add(module(saved));
        }
        return new Checkpoint(islands,new BufferedTransfers.Snapshot(envelope.transfers.revision,buffers,pending,planned),modules,envelope.modules==null?List.of():envelope.modules.bindings);
    }
    /** Explicit gauge migration to the model's supported sensible datum. Normal load still rejects
     * changed references. This operation changes no component amount, physical phase guess, clock,
     * reservation, or fallback allowance, and refuses a simultaneous property-model change. */
    public static Checkpoint migrateToSensibleReference(String json,EnergyReference previousWorldReference,Function<PackageKey,FluidThermodynamics> models) {
        Objects.requireNonNull(previousWorldReference);var envelope=readEnvelope(json);var migrated=new ArrayList<Island>();
        for(var island:envelope.islands) {
            var source=reference(island.reference);requireSameReference(source,previousWorldReference);
            var model=Objects.requireNonNull(models.apply(new PackageKey(island.packageId,island.compressibility)));
            if(!island.propertyRevision.equals(ApproximationAnchor.thermodynamicRevision(model)))throw new IllegalArgumentException("Energy migration cannot silently change properties");
            var target=EnergyReference.sensible(source.components());var graph=rebase(island.graph,source,target);
            var anchor=island.anchor==null?null:new Anchor(island.anchor.revision,rebase(island.anchor.graph,source,target),island.anchor.modes);
            History history=null;
            if(island.history!=null) {
                var h=island.history;var boundaries=h.boundaries.stream().map(b->new ConservativeTransport.BoundaryTransfer(b.nodeId(),b.moles(),rebaseSigned(b.totalEnergyJoule(),b.moles(),source,target))).toList();
                history=new History(h.seconds,h.flows,h.accepted,h.rejected,h.pumpWork,boundaries,h.rejectionReasons,h.modes,h.heads,h.acceptance,h.pipes);
            }
            migrated.add(new Island(island.dimension,island.packageId,island.compressibility,island.propertyRevision,energy(target),island.id,island.revision,graph,island.clock,island.allowance,anchor,history,island.status,island.fences));
        }
        var pending=envelope.transfers.pending.stream().map(p->new Pending(p.id,p.producer,p.receiver,p.dueTick,p.revision,sensible(p.remaining))).toList();
        var ledger=new Ledger(envelope.transfers.revision,envelope.transfers.buffers,pending,envelope.transfers.productionCapacity);
        Modules modules=null;
        if(envelope.modules!=null) {
            var states=new ArrayList<ModuleState>();
            for(var m:envelope.modules.states) {
                ModuleCycle cycle=null;
                if(m.cycle!=null) {var c=m.cycle;var inputs=new HashMap<UUID,ModuleInput>();c.inputs.forEach((id,input)->inputs.put(id,new ModuleInput(input.withdrawalId,input.throughTick,input.targetKg,sensible(input.owned))));cycle=new ModuleCycle(c.startTick,c.endTick,inputs,c.promises,c.status);}
                states.add(new ModuleState(m.type,m.scientificRevision,m.definition,m.revision,m.committedTick,m.running,cycle));
            }
            modules=new Modules(envelope.modules.version,states,envelope.modules.bindings);
        }
        return decodeEnvelope(new Envelope(envelope.version,migrated,ledger,modules),models);
    }
    private static Graph rebase(Graph graph,EnergyReference source,EnergyReference target) {
        return new Graph(graph.nodes.stream().map(n->new Node(n.id,n.elevation,n.kind,new PassiveNetwork.Inventory(n.inventory.volume(),n.inventory.moles(),source.rebase(n.inventory.internalEnergy(),n.inventory.moles(),target)),n.phase)).toList(),graph.pipes);
    }
    private static Parcel sensible(Parcel saved){var p=parcel(saved);return parcel(p.rebase(EnergyReference.sensible(p.reference().components())));}
    private static void requireSameReference(EnergyReference a,EnergyReference b) {
        if(!a.revision().equals(b.revision())||!a.components().equals(b.components())||a.formationDataQualified()!=b.formationDataQualified())throw new IllegalArgumentException("Migration reference does not match the saved world");
        for(int c=0;c<a.components().size();c++)if(a.offsetJoulesPerMole(c)!=b.offsetJoulesPerMole(c))throw new IllegalArgumentException("Migration offset does not match the saved world");
    }
    private static double rebaseSigned(double energy,double[] moles,EnergyReference source,EnergyReference target) {
        if(moles.length!=source.components().size()||!Double.isFinite(energy))throw new IllegalArgumentException("Invalid energy-transfer basis");
        double result=energy;for(int c=0;c<moles.length;c++){if(!Double.isFinite(moles[c]))throw new IllegalArgumentException("Invalid signed transfer");result=Math.fma(moles[c],target.offsetJoulesPerMole(c)-source.offsetJoulesPerMole(c),result);}
        if(!Double.isFinite(result))throw new IllegalArgumentException("Migrated transfer overflow");return result;
    }
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
        if(!FixedSplitModule.TYPE.equals(state.type)||!FixedSplitModule.SCIENTIFIC_REVISION.equals(state.scientificRevision))throw new IllegalArgumentException("Saved module needs an explicit type/scientific revision migration");
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
                })).toList());
    }
    private static PassiveNetwork graph(Graph saved,FluidThermodynamics model) {
        if(saved.nodes.isEmpty()||saved.nodes.size()>10000||saved.pipes.size()>100000)throw new IllegalArgumentException("Invalid saved graph size");
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(var n:saved.nodes) {
            var kind=PassiveNetwork.NodeKind.valueOf(n.kind);if(kind==PassiveNetwork.NodeKind.PORT)throw new IllegalArgumentException("Internal port cannot be persisted");
            var p=n.phase;var state=model.state(p.temperature,p.pressure,p.liquid,p.vapor,p.waterLiquid,p.waterVapor,p.hydrocarbonPressure);
            nodes.add(new PassiveNetwork.Reservoir(n.id,n.elevation,state,kind,n.inventory));
        }
        return new PassiveNetwork(nodes,saved.pipes.stream().map(p->new PassiveNetwork.Pipe(p.id,p.first,p.second,p.sections,switch(p.control.kind) {
            case "passive"->new FlowControl.Passive();case "pump"->new FlowControl.Pump(p.control.target,p.control.limit,p.control.efficiency);case "valve"->new FlowControl.PressureValve(p.control.target);default->throw new IllegalArgumentException("Unknown saved control");
        })).toList());
    }
    private static Energy energy(EnergyReference ref){double[] offsets=new double[ref.components().size()];for(int c=0;c<offsets.length;c++)offsets[c]=ref.offsetJoulesPerMole(c);return new Energy(ref.revision(),ref.components(),offsets,ref.formationDataQualified());}
    private static EnergyReference reference(Energy ref){return new EnergyReference(ref.revision,ref.components,ref.offsets,ref.formationQualified);}
    private static Parcel parcel(MaterialParcel p){return new Parcel(p.moles(),p.molecularWeights(),p.internalEnergy(),energy(p.reference()));}
    private static MaterialParcel parcel(Parcel p){return new MaterialParcel(p.moles,p.weights,p.energy,reference(p.reference));}

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
