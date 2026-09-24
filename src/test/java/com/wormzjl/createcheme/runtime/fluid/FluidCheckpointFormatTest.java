package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import net.minecraft.nbt.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoint format 4 (F3, replacing format 3 of plan section 3.5): clocks, material, fences, pending events, modules
 * and certificates round-trip exactly through the core record and the island units; every index and certificate
 * field is validated; certificates are discarded, keeping inventory, when certificates are off or their signature
 * changed; a certified island's unit is reused in place, never re-encoded, while it stays certified; a thousand
 * certified islands load without a solve burst; and a save taken during a property hold restarts awake from the held
 * state. Format 3 and older are refused with the instruction to create a fresh world; nothing reads them.
 */
class FluidCheckpointFormatTest {
    private static final String PACKAGE="createcheme:tjl20_methane_nitrogen";
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final int components=model.components().size();
    private final int water=components-1,nitrogen=MaterialTestBasis.NITROGEN;

    @AfterEach void counters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
    private static long counter(String name){return FluidRuntimeDiagnostics.sample().get(name);}
    private static void countFromHere(){FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;}

    // ---------------- fixtures: islands with distinct identities ----------------

    private double[] pure(int component){var n=new double[components];n[component]=1;return n;}
    /** A line of blocks along x in row z, identities first, first + 1, ...; pipes between the ends. */
    private static List<PhysicalFluidTopology.Device> line(long first,int z,Kind... kinds) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        for(int i=0;i<kinds.length;i++)devices.add(new PhysicalFluidTopology.Device(first+i,new PhysicalFluidTopology.Position("minecraft:overworld",i,0,z),kinds[i],PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive()));
        return devices;
    }
    private PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs) {
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices){var spec=specs.get(d.id());if(spec!=null)stock.put(d.id(),spec.initialize(d,model,()->{}));}
        var connected=PhysicalFluidTopology.compile(devices,stock).islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size());return connected.getFirst().graph();
    }
    /** Water at 1 atm against nitrogen at 2 bar: the line cannot flow, it certifies REST. */
    private PassiveNetwork deadHeadedLine(long first,int z) {
        return island(line(first,z,Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR),
                Map.of(first,new FluidDeviceSpec(1,298.15,101325,pure(water)),first+4,new FluidDeviceSpec(1,298.15,200000,pure(nitrogen))));
    }
    /** Two nitrogen tanks a kilopascal apart: they settle to solver-noise flows and certify STEADY with finite deltas. */
    private PassiveNetwork closedPair(long first,int z) {
        return island(line(first,z,Kind.RESERVOIR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR),
                Map.of(first,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),first+4,new FluidDeviceSpec(1,298.15,149000,pure(nitrogen))));
    }
    /** A nitrogen generator feeding a void: STEADY through-flow with no finite node. */
    private PassiveNetwork generatorToVoid(long first,int z) {
        return island(line(first,z,Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.VOID),
                Map.of(first,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),first+5,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen))));
    }
    /** A 1000 m3 tank filling from a 150 kPa generator: it stays awake for hours. */
    private PassiveNetwork slowFill(long first,int z) {
        return island(line(first,z,Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR),
                Map.of(first,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),first+5,new FluidDeviceSpec(1000,298.15,101325,pure(nitrogen))));
    }
    private PassiveNetwork loneTank(long node) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(node,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
    }

    // ---------------- a coordinator whose worker solves each admitted slice at once ----------------

    private final class Rig {
        final long[] epoch;final FluidThermodynamics model;
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final IslandCoordinator coordinator;
        long requests;int solves;
        Rig(CertificatePolicy policy){this(policy,FluidCheckpointFormatTest.this.model,0);}
        Rig(CertificatePolicy policy,FluidThermodynamics model,long start) {
            this.model=model;epoch=new long[]{start};
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 64-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){throw new AssertionError("no deadlines here");}
            },changed->{},()->0L,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false,100,policy),IslandCoordinator.CommitHook.NO_MATERIAL,()->epoch[0]);
        }
        void register(long id,PassiveNetwork graph) {
            coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(epoch[0],epoch[0],0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        }
        void load(FluidCheckpointCodec.Checkpoint checkpoint){for(var entry:checkpoint.islands())coordinator.register(entry.snapshot(),model);}
        void drain() {
            while(!attempts.isEmpty()) {
                for(long request:List.copyOf(attempts.keySet())) {
                    var attempt=attempts.remove(request);var command=commands.remove(request);solves++;
                    coordinator.completed(attempt,Optional.of((ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER)));
                }
                coordinator.pump();
            }
        }
        void tick(){epoch[0]++;coordinator.tick();drain();}
        void run(long ticks){for(long i=0;i<ticks;i++)tick();}
        void runUntilCertified(Collection<Long> ids,int ticks) {
            for(int i=0;i<ticks&&!ids.stream().allMatch(id->stored(id).certificate().isPresent());i++)tick();
            for(long id:ids)assertTrue(stored(id).certificate().isPresent(),"island "+id+" did not certify: "+coordinator.certificationRefusal(id));
        }
        IslandCoordinator.Snapshot stored(long id){return coordinator.observe(id);}
        IslandCoordinator.Snapshot read(long id){return coordinator.snapshot(id);}
        /** What a save captures: every island materialised to now. */
        FluidCheckpointCodec.Checkpoint checkpoint(){return checkpoint(new BufferedTransfers.Snapshot(0,Map.of(),Map.of()),List.of());}
        FluidCheckpointCodec.Checkpoint checkpoint(BufferedTransfers.Snapshot transfers,List<FixedSplitModule.Snapshot> modules) {
            return new FluidCheckpointCodec.Checkpoint(coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,1e-9,s)).toList(),transfers,modules);
        }
    }
    private WorldTopologyLedger.Snapshot world(long onlineTick) {
        var empty=WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());
        return new WorldTopologyLedger.Snapshot(onlineTick,empty.nextIdentity(),empty.active(),empty.events(),empty.constructed(),empty.destroyed(),empty.basis(),empty.recoveries());
    }
    private static long[] column(CompoundTag core,String name){return core.getCompound("Islands").getLongArray(name);}
    private static long index(CompoundTag core,long island,String name){return column(core,name)[FluidCheckpointCodec.row(core,island)];}
    private static void set(CompoundTag core,long island,String name,long value){column(core,name)[FluidCheckpointCodec.row(core,island)]=value;}
    private static FluidCheckpointCodec.Image copy(FluidCheckpointCodec.Image image){return new FluidCheckpointCodec.Image(image.core().copy(),image.packs());}
    private static void assertInventories(PassiveNetwork expected,PassiveNetwork actual,String what) {
        assertEquals(expected.reservoirs().size(),actual.reservoirs().size(),what);
        for(int n=0;n<expected.reservoirs().size();n++) {
            var a=expected.reservoirs().get(n);var b=actual.reservoirs().get(n);
            assertEquals(a.id(),b.id(),what);assertEquals(a.kind(),b.kind(),what);
            assertEquals(a.inventory(),b.inventory(),what+": node "+a.id()+" inventory, bit for bit");
            assertEquals(a.state().temperature(),b.state().temperature(),what+": node "+a.id()+" temperature");
            assertEquals(a.state().pressure(),b.state().pressure(),what+": node "+a.id()+" pressure");
        }
    }
    /** A certified island's snapshot with its certificate base and interval start graphs replaced. */
    private static FluidCheckpointCodec.IslandEntry withCertificateGraphs(FluidCheckpointCodec.IslandEntry entry,java.util.function.UnaryOperator<PassiveNetwork> base,java.util.function.UnaryOperator<PassiveNetwork> before) {
        var s=entry.snapshot();var c=s.certificate().orElseThrow();var saved=c.saved().orElseThrow();var interval=saved.interval();var r=interval.result();
        var result=new PassiveIntervalSolver.Result(base.apply(r.graph()),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),r.boundaries(),r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.acceptance(),r.pipeTransfers());
        var edited=new IslandCertificate.Saved(saved.kind(),saved.sinceTick(),saved.horizonTick(),new IslandCertificate.Interval(interval.startTick(),interval.endTick(),before.apply(interval.before()),result),saved.signature());
        var certified=new IslandCoordinator.Certified(c.kind(),c.sinceTick(),c.baseTick(),c.horizonTick(),c.largestFlow(),Optional.of(edited));
        return new FluidCheckpointCodec.IslandEntry(entry.dimension(),entry.packageId(),entry.compressibility(),new IslandCoordinator.Snapshot(s.id(),s.revision(),s.graph(),s.clock(),s.allowance(),s.anchor(),Optional.of(result),s.status(),s.fences(),Optional.of(certified)));
    }
    private static PassiveNetwork withNodeEnergy(PassiveNetwork graph,double energy) {
        var nodes=new ArrayList<>(graph.reservoirs());
        for(int n=0;n<nodes.size();n++){var node=nodes.get(n);if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;var i=node.inventory();
            nodes.set(n,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),new PassiveNetwork.Inventory(i.volume(),i.moles(),energy,i.solids())));break;}
        return new PassiveNetwork(nodes,graph.pipes());
    }
    private static PassiveNetwork withLongerSections(PassiveNetwork graph) {
        return new PassiveNetwork(graph.reservoirs(),graph.pipes().stream().map(p->new PassiveNetwork.Pipe(p.id(),p.first(),p.second(),
                p.sections().stream().map(g->new PipeResistance.Geometry(g.length()+1,g.diameter(),g.roughness(),g.minorLoss())).toList(),p.control(),p.blockedDirections(),p.filter())).toList());
    }

    // ---------------- round trips ----------------

    /** Every saved island, the pending material, a module, a queued topology event and the certificates come back exactly. */
    @Test void clocksMaterialFencesPendingEventsModulesAndCertificatesRoundTripExactly() {
        var rig=new Rig(CertificatePolicy.defaults());
        rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));rig.register(3,generatorToVoid(300,8));rig.register(4,slowFill(400,12));
        rig.runUntilCertified(List.of(1L,2L,3L),2_000);
        rig.run(1_234-rig.epoch[0]);
        assertTrue(rig.stored(4).certificate().isEmpty(),"the slow fill stays awake");
        var fence=UUID.randomUUID();rig.coordinator.fence(fence,1_500,List.of(4L));
        // Pending material, capacity and a module in the ledger.
        var outA=UUID.randomUUID();var outB=UUID.randomUUID();var feed=UUID.randomUUID();var pendingId=UUID.randomUUID();
        var ledger=new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(outA,new BufferedTransfers.Buffer(outA,1000,0,Map.of()),outB,new BufferedTransfers.Buffer(outB,1000,0,Map.of())),Map.of()));
        var parcel=new MaterialParcel(new double[]{2000,2000},new double[]{.018,.032},123456,EnergyReference.sensible(List.of("Water","Oxygen")));
        ledger.commit(ledger.reserve(List.of(new PendingTransfers.Pending(pendingId,UUID.randomUUID(),outA,1_300,0,parcel))));
        var module=new FixedSplitModule.Snapshot(new FixedSplitModule.Definition(UUID.randomUUID(),List.of(new FixedSplitModule.Feed(feed,2)),outA,outB,300,new double[]{1,0}),3,1_200,false,null);
        var checkpoint=rig.checkpoint(ledger.snapshot(),List.of(module));
        // A queued topology event, pending in the world ledger at the save.
        var topology=new WorldTopologyLedger(world(0));for(int i=0;i<1_234;i++)topology.tick();
        var device=new PhysicalFluidTopology.Device(5_000,new PhysicalFluidTopology.Position("minecraft:overworld",40,0,40),Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive());
        topology.commit(topology.queue(List.of(new WorldTopologyLedger.Edit(5_000,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen)),0))),Set.of(5_000L),5_001));
        var world=topology.snapshot();assertEquals(1,world.events().size());

        var data=new FluidSavedData(checkpoint,world,key->model);var tag=data.save(new CompoundTag(),null);
        assertEquals(4,tag.getInt("FluidFormat"));assertEquals(1_234,tag.getLong("Epoch"));
        assertEquals(FluidCheckpointCodec.AWAKE,index(tag,4,"Base"));
        assertEquals(checkpoint.islands().get(1).snapshot().certificate().orElseThrow().baseTick(),index(tag,2,"Base"));
        assertEquals(IslandCertificate.Kind.STEADY.ordinal()+1,tag.getCompound("Islands").getByteArray("Kind")[FluidCheckpointCodec.row(tag,2)],"the certificate summary names the kind");
        var loaded=FluidSavedData.load(tag,key->model,data.store().reopen());
        for(int i=0;i<4;i++) {
            var a=checkpoint.islands().get(i).snapshot();var b=loaded.checkpoint().islands().get(i).snapshot();String what="island "+a.id();
            assertEquals(a.id(),b.id());assertEquals(a.revision(),b.revision(),what);assertEquals(a.clock(),b.clock(),what+" clock, exact");
            assertEquals(a.fences(),b.fences(),what);assertEquals(a.status(),b.status(),what);assertEquals(a.allowance(),b.allowance(),what);
            assertInventories(a.graph(),b.graph(),what);
            assertEquals(a.certificate().isPresent(),b.certificate().isPresent(),what);
            if(a.certificate().isPresent()) {
                var x=a.certificate().orElseThrow();var y=b.certificate().orElseThrow();
                assertEquals(x.kind(),y.kind(),what);assertEquals(x.sinceTick(),y.sinceTick(),what);assertEquals(x.baseTick(),y.baseTick(),what);
                assertEquals(x.horizonTick(),y.horizonTick(),what);assertEquals(x.largestFlow(),y.largestFlow(),what);
                var s=x.saved().orElseThrow();var t=y.saved().orElseThrow();
                assertEquals(s.signature(),t.signature(),what+" signature");
                assertEquals(s.interval().startTick(),t.interval().startTick(),what);assertEquals(s.interval().endTick(),t.interval().endTick(),what);
                assertInventories(s.interval().before(),t.interval().before(),what+" certified interval start");
                assertInventories(s.interval().result().graph(),t.interval().result().graph(),what+" certificate base");
                assertArrayEquals(s.interval().result().averageMassFlows(),t.interval().result().averageMassFlows(),what);
            }
        }
        assertEquals(Map.of(fence,1_500L),loaded.checkpoint().islands().get(3).snapshot().fences());
        var a=checkpoint.transfers();var b=loaded.checkpoint().transfers();
        assertEquals(a.revision(),b.revision());assertEquals(a.buffers(),b.buffers(),"buffers and their reservations");assertEquals(a.planned(),b.planned());
        assertEquals(a.pending().keySet(),b.pending().keySet(),"pending material");
        for(var id:a.pending().keySet()) {
            var x=a.pending().get(id);var y=b.pending().get(id);
            assertEquals(List.of(x.producer(),x.receiver(),x.dueTick(),x.revision()),List.of(y.producer(),y.receiver(),y.dueTick(),y.revision()));
            assertArrayEquals(x.remaining().moles(),y.remaining().moles());assertArrayEquals(x.remaining().molecularWeights(),y.remaining().molecularWeights());
            assertEquals(x.remaining().internalEnergy(),y.remaining().internalEnergy());assertEquals(x.remaining().solids(),y.remaining().solids());
        }
        var m=checkpoint.modules().getFirst();var n=loaded.checkpoint().modules().getFirst();var d=m.definition();var e=n.definition();
        assertEquals(List.of(d.id(),d.feeds(),d.firstProduct(),d.secondProduct(),d.cadenceTicks()),List.of(e.id(),e.feeds(),e.firstProduct(),e.secondProduct(),e.cadenceTicks()),"module definition");
        assertArrayEquals(d.firstFractions(),e.firstFractions());assertEquals(List.of(m.revision(),m.committedTick(),m.running()),List.of(n.revision(),n.committedTick(),n.running()),"module state");assertNull(n.cycle());
        var events=loaded.world().orElseThrow().events();
        assertEquals(world.onlineTick(),loaded.world().orElseThrow().onlineTick());assertEquals(1,events.size());
        assertEquals(world.events().getFirst().id(),events.getFirst().id());assertEquals(world.events().getFirst().tick(),events.getFirst().tick());assertEquals(world.events().getFirst().touched(),events.getFirst().touched());
        // Seeded by the load, the store writes nothing but the same core record again: every unit stays where it is.
        assertEquals(tag,loaded.save(new CompoundTag(),null),"a loaded checkpoint saves back to the same core record");
        assertEquals(0,loaded.lastSave().payloadsEncoded());assertEquals(4,loaded.lastSave().payloadsReused());assertFalse(loaded.lastSave().topologyEncoded());assertEquals(0,loaded.lastSave().stats().pack(),"no pack written");
    }

    /**
     * A certified island is saved at its base and loaded at its committed tick, materialised without a solve; restored,
     * it keeps its since tick and holds only its horizon, and it continues bit for bit as if it had never been saved.
     */
    @Test void aRestoredCertificateContinuesWithoutASolveExactlyAsIfNeverSaved() {
        var original=new Rig(CertificatePolicy.defaults());
        original.register(1,deadHeadedLine(100,0));original.register(2,closedPair(200,4));
        original.runUntilCertified(List.of(1L,2L),2_000);original.run(1_234-original.epoch[0]);
        var image=FluidCheckpointCodec.encode(original.checkpoint(),1_234,key->model);
        // The unit holds the certificate base; the load materialises it to the saved committed tick.
        var base=original.stored(2).certificate().orElseThrow().saved().orElseThrow().interval().result().graph();
        var now=original.read(2).graph();
        assertTrue(java.util.stream.IntStream.range(0,now.reservoirs().size()).anyMatch(n->!now.reservoirs().get(n).inventory().equals(base.reservoirs().get(n).inventory())),"the closed pair moved since its base");
        var decoded=FluidCheckpointCodec.decode(image,key->model);
        assertInventories(base,decoded.islands().get(1).snapshot().certificate().orElseThrow().saved().orElseThrow().interval().result().graph(),"the unit records the base");
        assertInventories(original.read(2).graph(),decoded.islands().get(1).snapshot().graph(),"materialised at load");
        countFromHere();
        var restored=new Rig(CertificatePolicy.defaults(),model,1_234);restored.load(decoded);
        assertEquals(2,counter("certificatesRestored"));assertEquals(0,counter("certificatesDiscarded"));
        for(long id:List.of(1L,2L)) {
            assertFalse(restored.coordinator.retainsSolver(id),"a restored certified island keeps no solver caches");
            assertEquals(original.stored(id).certificate().orElseThrow().sinceTick(),restored.stored(id).certificate().orElseThrow().sinceTick());
            assertEquals(original.stored(id).status(),restored.stored(id).status(),"the status line continues from the saved since tick");
        }
        assertEquals(restored.coordinator.epochTick(2,original.stored(2).certificate().orElseThrow().horizonTick()),restored.coordinator.nextDue(),"only the STEADY horizon is scheduled");
        FluidRuntimeDiagnostics.reset();
        original.run(5_000);restored.run(5_000);
        assertEquals(0,restored.solves);assertEquals(0,counter("solvesDispatched"),"no solve after the load");
        for(long id:List.of(1L,2L)) {
            var a=original.read(id);var b=restored.read(id);
            assertEquals(a.clock(),b.clock());assertInventories(a.graph(),b.graph(),"island "+id+" 5000 ticks after the load");
            assertEquals(a.status(),b.status());
        }
    }

    // ---------------- validation ----------------

    /** Every index and certificate field is validated on load, and an invalid one refuses the load with a clear reason. */
    @Test void everyInvalidCertificateFieldIsRefused() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(2,closedPair(200,4));
        rig.runUntilCertified(List.of(2L),2_000);rig.run(1_234-rig.epoch[0]);
        var image=FluidCheckpointCodec.encode(rig.checkpoint(),1_234,key->model);var core=image.core();
        long base=index(core,2,"Base"),committed=index(core,2,"Committed"),horizon=index(core,2,"Horizon"),start=index(core,2,"Start");
        assertTrue(base<committed&&committed<horizon);
        var strings=(ListTag)core.get("Strings");int policy=-1;for(int i=0;i<strings.size();i++)if(strings.getString(i).startsWith("restDetection="))policy=i;
        assertTrue(policy>=0,"the certificate policy is in the string table");int policyIndex=policy;
        record Case(String expected,Consumer<CompoundTag> edit) {}
        var cases=List.<Case>of(
                new Case("later than its committed tick",t->set(t,2,"Committed",base-1)),
                new Case("earlier than its committed tick",t->set(t,2,"Horizon",committed-1)),
                new Case("invalid certificate base",t->set(t,2,"Base",-2)),
                new Case("carries a certificate",t->set(t,2,"Base",FluidCheckpointCodec.AWAKE)),
                new Case("lacks its certificate record",t->t.getCompound("Islands").getByteArray("Kind")[FluidCheckpointCodec.row(t,2)]=0),
                new Case("is empty or negative",t->set(t,2,"Start",base)),
                new Case("disagrees with its result",t->set(t,2,"Start",start-20)),
                new Case("since <= base",t->set(t,2,"Since",base+1)),
                new Case("since <= base",t->set(t,2,"Since",-1)),
                new Case("unknown certificate kind",t->t.getCompound("Islands").getByteArray("Kind")[FluidCheckpointCodec.row(t,2)]=9),
                new Case("field Horizon",t->t.getCompound("Islands").remove("Horizon")),
                new Case("Invalid certificate signature",t->((ListTag)t.get("Strings")).set(policyIndex,StringTag.valueOf(""))),
                new Case("ahead of the checkpoint's world epoch",t->t.putLong("Epoch",1_233)),
                new Case("lies in pack 7",t->set(t,2,"Pack",7)),
                new Case("does not lie inside pack",t->set(t,2,"Offset",1L<<40)),
                new Case("the index expects island 2 revision 5",t->set(t,2,"Revision",5)),
                new Case("Invalid or duplicate unit generation",t->set(t,2,"Generation",0)),
                new Case("is not after its last pack",t->t.putLong("NextPack",1)));
        for(var c:cases) {
            var edited=copy(image);c.edit().accept(edited.core());
            assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(edited,key->model),c.expected()+": an unsealed edit is refused as corrupt");
            // A removed field is refused before any checksum can be computed; everything else is resealed to reach its own check.
            try{FluidCheckpointCodec.reseal(edited.core());}catch(IllegalArgumentException missing){assertTrue(missing.getMessage().contains(c.expected()),missing.getMessage());}
            var refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(edited,key->model));
            assertTrue(refusal.getMessage().contains(c.expected()),c.expected()+" / got: "+refusal.getMessage());
        }
        // Nonfinite deltas: the energy between the interval's start and its base overflows.
        var overflow=FluidCheckpointCodec.withIsland(image,2,key->model,entry->withCertificateGraphs(entry,g->withNodeEnergy(g,1.7e308),g->withNodeEnergy(g,-1.7e308)));
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(overflow,key->model)).getMessage().contains("nonfinite energy delta"));
        // A fence before the committed tick: a fence at the committed tick, then the committed tick moved past it.
        var event=UUID.randomUUID();
        var fenced=FluidCheckpointCodec.withIsland(image,2,key->model,entry->{var s=entry.snapshot();return new FluidCheckpointCodec.IslandEntry(entry.dimension(),entry.packageId(),entry.compressibility(),
                new IslandCoordinator.Snapshot(s.id(),s.revision(),s.graph(),s.clock(),s.allowance(),s.anchor(),s.lastResult(),s.status(),Map.of(event,committed),s.certificate()));});
        assertEquals(1,FluidCheckpointCodec.decode(fenced,key->model).islands().getFirst().snapshot().fences().size(),"the fence itself reads back");
        set(fenced.core(),2,"Committed",committed+1);FluidCheckpointCodec.reseal(fenced.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(fenced,key->model)).getMessage().contains("precedes the committed tick"));
        // A unit whose own bytes are malformed: truncated, or with trailing bytes, each resealed so only the unit is wrong.
        var bytes=FluidCheckpointCodec.unitBytes(image,2);
        var truncated=FluidCheckpointCodec.withUnit(image,2,Arrays.copyOf(bytes,bytes.length-1));
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(truncated,key->model)).getMessage().contains("Invalid saved unit of island 2"));
        var trailing=FluidCheckpointCodec.withUnit(image,2,Arrays.copyOf(bytes,bytes.length+3));
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(trailing,key->model)).getMessage().contains("trailing bytes"));
        var otherFormat=bytes.clone();otherFormat[4]=2;
        var refused=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(FluidCheckpointCodec.withUnit(image,2,otherFormat),key->model)).getMessage();
        assertTrue(refused.contains("unit format 2")&&refused.contains("Create a fresh world"),refused);
    }

    /** restDetection=false discards every saved certificate; the island keeps its materialised inventory and solves again. */
    @Test void restDetectionOffDiscardsEverySavedCertificateAndKeepsTheInventory() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));
        rig.runUntilCertified(List.of(1L,2L),2_000);rig.run(1_234-rig.epoch[0]);
        var decoded=FluidCheckpointCodec.decode(FluidCheckpointCodec.encode(rig.checkpoint(),1_234,key->model),key->model);
        countFromHere();
        var off=new Rig(CertificatePolicy.disabled(),model,1_234);off.load(decoded);
        assertEquals(0,counter("certificatesRestored"));assertEquals(2,counter("certificatesDiscarded"));
        for(int i=0;i<2;i++) {
            var saved=decoded.islands().get(i).snapshot();var now=off.stored(saved.id());
            assertTrue(now.certificate().isEmpty());assertTrue(off.coordinator.retainsSolver(saved.id()));
            assertTrue(now.status().startsWith("WAITING: saved "+saved.certificate().orElseThrow().kind()+" certificate discarded: certificates are off"),now.status());
            assertInventories(rig.read(saved.id()).graph(),now.graph(),"island "+saved.id()+" keeps the inventory it was saved with");
            assertEquals(saved.clock(),now.clock());
            assertNotEquals(saved.payloadGeneration(),now.payloadGeneration(),"a discarded certificate changes what the unit records");
        }
        off.run(200);assertEquals(4,off.solves,"both islands solve again, one interval each per cadence");
    }

    /** A saved certificate whose signature is not its island's current one is discarded, and the inventory kept. */
    @Test void aSignatureMismatchDiscardsTheCertificateAndKeepsTheInventory() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(2,closedPair(200,4));
        rig.runUntilCertified(List.of(2L),2_000);rig.run(1_234-rig.epoch[0]);
        var expected=rig.read(2).graph();
        var image=FluidCheckpointCodec.encode(rig.checkpoint(),1_234,key->model);
        var defaults=CertificatePolicy.defaults();
        record Case(String reason,FluidThermodynamics model,CertificatePolicy policy,FluidCheckpointCodec.Image image) {}
        // A graph-identity change: every run of pipe one metre longer, in the base and in the interval's start alike.
        var longer=FluidCheckpointCodec.withIsland(image,2,key->model,entry->withCertificateGraphs(entry,FluidCheckpointFormatTest::withLongerSections,FluidCheckpointFormatTest::withLongerSections));
        var cases=List.of(
                new Case("property revision",FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,10),defaults,image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-8,1e-6,17_280,2,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-5,17_280,2,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-6,17_279,2,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-6,17_280,3,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-6,17_280,2,60),image),
                new Case("graph identity",model,defaults,longer));
        assertNotEquals(ApproximationAnchor.revision(model),ApproximationAnchor.revision(cases.getFirst().model()));
        assertEquals(ApproximationAnchor.thermodynamicRevision(model),ApproximationAnchor.thermodynamicRevision(cases.getFirst().model()),"a velocity limit leaves the saved inventory readable");
        for(var c:cases) {
            var decoded=FluidCheckpointCodec.decode(c.image(),key->c.model());
            countFromHere();
            var loaded=new Rig(c.policy(),c.model(),1_234);loaded.load(decoded);
            assertEquals(1,counter("certificatesDiscarded"),c.reason());assertEquals(0,counter("certificatesRestored"),c.reason());
            var now=loaded.stored(2);
            assertTrue(now.certificate().isEmpty(),c.reason());assertTrue(loaded.coordinator.retainsSolver(2));
            assertTrue(now.status().contains("its "+c.reason()),c.reason()+": "+now.status());
            assertTrue(loaded.coordinator.certificationRefusal(2).startsWith("saved certificate discarded"));
            for(int n=0;n<expected.reservoirs().size();n++)assertEquals(expected.reservoirs().get(n).inventory(),now.graph().reservoirs().get(n).inventory(),c.reason()+": inventory kept");
            loaded.run(100);assertEquals(1,loaded.solves,c.reason()+": the island solves its next interval");
        }
    }

    // ---------------- the dirty rule ----------------

    /**
     * A certified island's unit stays where it is, never re-encoded, on every save while it stays certified; a solve, a
     * wake at the horizon and a new certificate make the next save write it. A save after no change writes no unit and
     * no pack; after one island's solve it writes exactly that island's unit. (Tests run with the scheduler's
     * self-verification on, so every reused unit is also compared with a fresh encoding of its island.)
     */
    @Test void aCertifiedPayloadIsCopiedNotReencodedUntilASolveOrACertificate() {
        assertTrue(Boolean.getBoolean("createcheme.fluid.scheduler.verify"),"the reused units are verified against a fresh encoding");
        var rig=new Rig(new CertificatePolicy(true,1e-9,1e-6,5,2,0));
        rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));rig.register(3,slowFill(300,8));
        rig.runUntilCertified(List.of(1L,2L),2_000);
        // The world ledger, advanced with the islands' epoch as the world advances it; captured as the world captures it.
        var ledger=new WorldTopologyLedger(world(0));
        java.util.function.Supplier<WorldTopologyLedger.Snapshot> topology=()->{while(ledger.onlineTick()<rig.epoch[0])ledger.tick();return ledger.snapshot();};
        var data=new FluidSavedData(rig.checkpoint(),topology.get(),key->model);
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),topology.get()));
        countFromHere();
        data.save(new CompoundTag(),null);var first=data.lastSave();
        assertEquals(3,first.payloadsEncoded());assertEquals(0,first.payloadsReused(),"the first save encodes every island");assertTrue(first.topologyEncoded());assertTrue(first.stats().full());
        var immediate=data.save(new CompoundTag(),null);var second=data.lastSave();
        assertEquals(0,second.payloadsEncoded());assertEquals(3,second.payloadsReused(),"nothing changed: every unit stays");
        assertFalse(second.topologyEncoded(),"an unchanged world ledger keeps its unit");assertEquals(0,second.stats().pack(),"and no pack is written");
        long horizon=rig.stored(2).certificate().orElseThrow().horizonTick();
        rig.run(100);
        data.save(new CompoundTag(),null);var third=data.lastSave();
        assertFalse(third.topologyEncoded(),"the ledger is unchanged at another online tick");
        assertEquals(1,third.payloadsEncoded(),"only the awake island solved");assertEquals(2,third.payloadsReused(),"the certified islands were only materialised");
        assertEquals(third.stats().encodedBytes()+third.stats().copiedBytes()+FluidCheckpointStore.PACK_HEADER,third.stats().packBytes(),"the new pack holds that one unit");
        assertTrue(horizon>rig.epoch[0],"the horizon lies ahead");
        rig.run(horizon-rig.epoch[0]+100);
        assertTrue(rig.stored(2).clock().committedTick()>horizon,"the closed pair woke at its horizon and solved its revalidating interval");
        data.save(new CompoundTag(),null);var fourth=data.lastSave();
        assertEquals(2,fourth.payloadsEncoded(),"the revalidated pair and the awake island are encoded");assertEquals(1,fourth.payloadsReused(),"the resting line stays");
        assertEquals(6,counter("payloadsEncoded"));assertEquals(6,counter("payloadsReused"));
        assertTrue(first.payloadBytes()>0&&first.payloadBytes()==second.payloadBytes());
        assertNotNull(immediate.get("Islands"));
    }

    // ---------------- load without a solve burst ----------------

    /** Plan 5 item 7: a thousand certified islands load and dispatch no solve until their horizon, then revalidate once. */
    @Test void aThousandCertifiedIslandsLoadWithoutASolveBurst() {
        // A 60 s recheck gives each resting tank a horizon, so the test can see the first solve come exactly there.
        var policy=new CertificatePolicy(true,1e-9,1e-6,17_280,2,60);
        var original=new Rig(policy);for(long id=1;id<=1_000;id++)original.register(id,loneTank(100_000+id));
        original.run(300);
        var checkpoint=original.checkpoint();
        assertTrue(checkpoint.islands().stream().allMatch(i->i.snapshot().certificate().map(c->c.kind()==IslandCertificate.Kind.REST&&c.horizonTick()==1_400).orElse(false)),"every tank rests with its horizon at 1400");
        long started=System.nanoTime();var image=FluidCheckpointCodec.encode(checkpoint,300,key->model);long encoded=System.nanoTime();
        var decoded=FluidCheckpointCodec.decode(image,key->model);long decodedAt=System.nanoTime();
        countFromHere();
        var loaded=new Rig(policy,model,300);loaded.load(decoded);long registered=System.nanoTime();
        assertEquals(1_000,counter("certificatesRestored"));assertEquals(0,counter("certificatesDiscarded"));
        assertEquals(0,loaded.coordinator.readyCount(),"no island is ready at the load");
        System.out.printf(Locale.ROOT,"1000 certified islands: encode %.1f ms, decode %.1f ms, register %.1f ms, %d unit bytes, core record %d bytes%n",(encoded-started)/1e6,(decodedAt-encoded)/1e6,(registered-decodedAt)/1e6,
                Arrays.stream(image.core().getCompound("Islands").getIntArray("Length")).asLongStream().sum(),image.core().sizeInBytes());
        FluidRuntimeDiagnostics.reset();
        loaded.run(1_099);
        assertEquals(1_399,loaded.epoch[0]);assertEquals(0,loaded.solves);
        for(var name:List.of("solvesDispatched","readinessPumps","deadlinesFired","materialisations","islandVisits"))assertEquals(0,counter(name),name+" before the horizon");
        loaded.tick();
        assertEquals(1_000,counter("deadlinesFired"),"every horizon falls at 1400");assertEquals(0,loaded.solves);
        // Each woken tank solves one revalidating interval, [1400, 1500], at most 64 dispatches a tick.
        loaded.run(120);
        assertEquals(1_000,loaded.solves,"one revalidating solve per island after its horizon, none before");
        for(long id=1;id<=1_000;id++)assertTrue(loaded.stored(id).certificate().isPresent(),"island "+id+" renewed");
    }

    // ---------------- property hold ----------------

    /**
     * Plan 3.6 with a restart: a save taken during a hold keeps committed time at the hold tick and the online time
     * accrued since, and no certificate; the restarted island solves its debt from the held state and certifies again
     * only after restConfirmIntervals fresh intervals.
     */
    @Test void holdSaveAndRestartKeepsCommittedFrozenAndDebtAndRequalifiesOverTheConfirmCount() {
        for(int confirm:new int[]{2,3}) {
            var policy=new CertificatePolicy(true,1e-9,1e-6,17_280,confirm,0);
            var rig=new Rig(policy);rig.register(1,deadHeadedLine(100,0));
            rig.runUntilCertified(List.of(1L),2_000);rig.run(537);
            rig.coordinator.suspendForPropertyChange("HELD: test property change");
            long hold=rig.epoch[0];rig.run(1_000);
            var during=rig.read(1);
            assertTrue(during.certificate().isPresent()&&during.certificate().orElseThrow().saved().isEmpty(),"a held certificate is not saved");
            var image=FluidCheckpointCodec.encode(rig.checkpoint(),rig.epoch[0],key->model);
            assertEquals(FluidCheckpointCodec.AWAKE,index(image.core(),1,"Base"));
            var decoded=FluidCheckpointCodec.decode(image,key->model).islands().getFirst().snapshot();
            assertEquals(hold,decoded.clock().committedTick(),"committed time stays frozen at the hold");
            assertEquals(hold+1_000,decoded.clock().onlineTick(),"the online time accrued during the hold is kept as debt");
            assertEquals(during.status(),decoded.status(),"the hold's status line is kept");
            assertTrue(decoded.certificate().isEmpty());assertInventories(during.graph(),decoded.graph(),"held inventory");
            var restarted=new Rig(policy,model,rig.epoch[0]);restarted.load(new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,1e-9,decoded)),new BufferedTransfers.Snapshot(0,Map.of(),Map.of())));
            assertTrue(restarted.coordinator.retainsSolver(1));assertEquals(1,restarted.coordinator.readyCount(),"awake with debt, ready at once");
            restarted.coordinator.pump();restarted.drain();
            var again=restarted.stored(1).certificate().orElseThrow();
            assertEquals(hold+100L*confirm,again.baseTick(),"requalified over "+confirm+" intervals after the restart");
            assertEquals(hold+100L*confirm,again.sinceTick());assertEquals(confirm,restarted.solves);
            var now=restarted.read(1).graph();
            for(int n=0;n<now.reservoirs().size();n++)assertEquals(during.graph().reservoirs().get(n).inventory(),now.reservoirs().get(n).inventory(),"rest is the identity");
        }
    }

    // ---------------- other formats ----------------

    /** Format 3 and older are refused with the instruction to create a fresh world, and nothing in the tag is changed. */
    @Test void formatThreeAndOlderAreRefusedWithAFreshWorldInstructionAndLeftUntouched() {
        for(int format:new int[]{1,2,3}) {
            var old=new CompoundTag();old.putInt("FluidFormat",format);old.putLong("Epoch",40);
            byte[] json=("{\"version\":"+format+",\"islands\":[],\"transfers\":{\"revision\":0,\"buffers\":[],\"pending\":[]}}").getBytes(StandardCharsets.UTF_8);
            old.putByteArray(format==3?"Ledger":"Checkpoint",json);old.put("Islands",new ListTag());old.putByteArray("SHA256",new byte[32]);var before=old.copy();
            var refusal=assertThrows(IllegalArgumentException.class,()->FluidSavedData.load(old,key->model,FluidCheckpointStore.memory()));
            assertTrue(refusal.getMessage().contains("format "+format)&&refusal.getMessage().contains("Create a fresh world"),refusal.getMessage());
            assertEquals(before,old,"a refused save is left exactly as it was");
        }
        var data=new FluidSavedData(new FluidCheckpointCodec.Checkpoint(List.of(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of())),world(40),key->{throw new AssertionError("no model needed");});
        var current=data.save(new CompoundTag(),null);var packs=Map.copyOf(((FluidCheckpointStore.Memory)data.store().backend()).packs);
        assertTrue(FluidSavedData.load(current,key->{throw new AssertionError();},data.store().reopen()).world().isPresent());
        // The topology is saved without its online tick: the checkpoint's epoch is the one clock of the world.
        var moved=current.copy();moved.putLong("Epoch",41);FluidCheckpointCodec.reseal(moved);
        assertEquals(41,FluidSavedData.load(moved,key->{throw new AssertionError();},FluidCheckpointStore.memory(new FluidCheckpointCodec.Image(moved,packs))).world().orElseThrow().onlineTick());
        var unsigned=current.copy();unsigned.remove("SHA256");
        assertThrows(IllegalArgumentException.class,()->FluidSavedData.load(unsigned,key->{throw new AssertionError();},FluidCheckpointStore.memory(new FluidCheckpointCodec.Image(unsigned,packs))));
        // A pack of another pack format is refused likewise.
        var otherPacks=new HashMap<>(packs);var pack=otherPacks.get(1L).clone();pack[7]=2;otherPacks.put(1L,pack);
        var refusal=assertThrows(IllegalArgumentException.class,()->FluidSavedData.load(current,key->{throw new AssertionError();},FluidCheckpointStore.memory(new FluidCheckpointCodec.Image(current,otherPacks)))).getMessage();
        assertTrue(refusal.contains("pack format 2")&&refusal.contains("Create a fresh world"),refusal);
    }
}
