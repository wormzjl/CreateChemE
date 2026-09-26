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
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoint format 5 through its real layout on a temporary directory (F3): the core record as the world's saved
 * data file, the island and topology units in pack files beside it. Minecraft's save path round-trips clocks,
 * material, fences, pending events, modules, certificates and held islands; a save writes only the units of islands
 * that changed; a crash between a pack and its core record leaves the previous checkpoint in force; a commit built on
 * a failed one is not written; a damaged, half-written or missing pack refuses the world and leaves its files as
 * they were; units of removed or replaced islands are dropped and their packs compacted and deleted; ten thousand
 * islands save and load through the index; and the first save after a load encodes nothing that did not change.
 */
class FluidCheckpointStoreTest {
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
        Rig(CertificatePolicy policy){this(policy,FluidCheckpointStoreTest.this.model,0);}
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
    @TempDir Path temporary;
    private WorldTopologyLedger.Snapshot world(long onlineTick) {
        var empty=WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());
        return new WorldTopologyLedger.Snapshot(onlineTick,empty.nextIdentity(),empty.active(),empty.events(),empty.constructed(),empty.destroyed(),empty.basis(),empty.recoveries());
    }
    private static FluidCheckpointStore directory(Path folder){return FluidCheckpointStore.directory(folder,Runnable::run,false);}
    private static Path core(Path folder){return folder.resolve(FluidCheckpointStore.CORE_NAME);}
    private static Path pack(Path folder,long number){return folder.resolve(FluidCheckpointStore.UNIT_DIRECTORY).resolve("fluid-"+number+".pack");}
    /** Every file under {@code folder} by relative path, with its SHA-256. */
    private static Map<String,String> files(Path folder) throws IOException {
        var result=new TreeMap<String,String>();
        try(var walk=Files.walk(folder)){for(var file:walk.filter(Files::isRegularFile).toList())result.put(folder.relativize(file).toString().replace('\\','/'),HexFormat.of().formatHex(FluidCheckpointCodec.sha256(Files.readAllBytes(file))));}
        return result;
    }
    private static Set<String> packs(Path folder) throws IOException {
        var names=new TreeSet<String>();var units=folder.resolve(FluidCheckpointStore.UNIT_DIRECTORY);
        if(Files.isDirectory(units))try(var list=Files.list(units)){list.forEach(f->names.add(f.getFileName().toString()));}
        return names;
    }
    private static FluidCheckpointCodec.IslandEntry entry(IslandCoordinator.Snapshot snapshot){return new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,1e-9,snapshot);}
    private static FluidCheckpointCodec.Checkpoint checkpoint(List<IslandCoordinator.Snapshot> snapshots){return new FluidCheckpointCodec.Checkpoint(snapshots.stream().map(FluidCheckpointStoreTest::entry).toList(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));}
    private static void assertInventories(PassiveNetwork expected,PassiveNetwork actual,String what) {
        assertEquals(expected.reservoirs().size(),actual.reservoirs().size(),what);
        for(int n=0;n<expected.reservoirs().size();n++) {
            var a=expected.reservoirs().get(n);var b=actual.reservoirs().get(n);
            assertEquals(a.id(),b.id(),what);assertEquals(a.kind(),b.kind(),what);assertEquals(a.inventory(),b.inventory(),what+": node "+a.id()+" inventory, bit for bit");
            assertEquals(Double.doubleToRawLongBits(a.state().temperature()),Double.doubleToRawLongBits(b.state().temperature()),what);assertEquals(Double.doubleToRawLongBits(a.state().pressure()),Double.doubleToRawLongBits(b.state().pressure()),what);
            assertArrayEquals(a.state().liquid(),b.state().liquid(),what);assertArrayEquals(a.state().vapor(),b.state().vapor(),what);
        }
    }
    /** A backend that fails its next pack or core write on demand, as a crash or a full disk would. */
    private static final class Failing implements FluidCheckpointStore.Backend {
        final FluidCheckpointStore.Backend inner;boolean failPack,failCore;
        Failing(FluidCheckpointStore.Backend inner){this.inner=inner;}
        public byte[] readPack(long pack) throws IOException{return inner.readPack(pack);}
        public byte[] readUnit(long pack,long offset,int length) throws IOException{return inner.readUnit(pack,offset,length);}
        public void writePack(long pack,byte[] bytes) throws IOException{if(failPack)throw new IOException("injected: the pack write failed");inner.writePack(pack,bytes);}
        public void writeCore(CompoundTag core) throws IOException{if(failCore)throw new IOException("injected: crash before the core record's rename");inner.writeCore(core);}
        public CompoundTag readCore() throws IOException{return inner.readCore();}
        public Set<Long> packs() throws IOException{return inner.packs();}
        public void deletePack(long pack) throws IOException{inner.deletePack(pack);}
        public int cleanTemporary() throws IOException{return inner.cleanTemporary();}
    }
    /** An awake lone tank with an explicit payload generation, as a coordinator's snapshot carries it. */
    private IslandCoordinator.Snapshot tank(long id,long generation,long tick) {
        return new IslandCoordinator.Snapshot(id,0,loneTanks.computeIfAbsent(id,k->loneTank(100_000+k)),new IslandClock.Snapshot(tick,tick,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY",Map.of(),Optional.empty(),generation);
    }
    private final Map<Long,PassiveNetwork> loneTanks=new HashMap<>();

    // ---------------- round trip through the files ----------------

    /**
     * Minecraft's save path (SavedData.save(File)) writes the core record and one pack; a fresh store reading the files
     * gives back clocks (the held island's retry tick too), inventories bit for bit, fences, statuses, pending material,
     * a module, a queued topology event and the no-flow and moving certificates exactly.
     */
    @Test void clocksMaterialFencesPendingEventsModulesCertificatesAndHeldIslandsRoundTripThroughTheFiles() throws IOException {
        var rig=new Rig(CertificatePolicy.defaults());
        rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));rig.register(3,generatorToVoid(300,8));rig.register(4,slowFill(400,12));
        rig.runUntilCertified(List.of(1L,2L,3L),2_000);rig.run(1_234-rig.epoch[0]);
        var fence=UUID.randomUUID();rig.coordinator.fence(fence,1_500,List.of(4L));
        // A held island as a start-up hold leaves one: awake, its retry tick ahead of its committed tick, a HELD status line.
        var held=new IslandCoordinator.Snapshot(5,3,loneTank(900),new IslandClock.Snapshot(1_234,1_100,1_260,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"HELD: start-up round over its wall budget; retry at 63.0 s");
        var outA=UUID.randomUUID();var outB=UUID.randomUUID();var feed=UUID.randomUUID();
        var ledger=new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(outA,new BufferedTransfers.Buffer(outA,1000,0,Map.of()),outB,new BufferedTransfers.Buffer(outB,1000,0,Map.of())),Map.of()));
        var parcel=new MaterialParcel(new double[]{2000,2000},new double[]{.018,.032},123456,EnergyReference.sensible(List.of("Water","Oxygen")));
        ledger.commit(ledger.reserve(List.of(new PendingTransfers.Pending(UUID.randomUUID(),UUID.randomUUID(),outA,1_300,0,parcel))));
        var module=new FixedSplitModule.Snapshot(new FixedSplitModule.Definition(UUID.randomUUID(),List.of(new FixedSplitModule.Feed(feed,2)),outA,outB,300,new double[]{1,0}),3,1_200,false,null);
        var entries=new ArrayList<>(rig.checkpoint().islands());entries.add(entry(held));
        var checkpoint=new FluidCheckpointCodec.Checkpoint(entries,ledger.snapshot(),List.of(module));
        var topology=new WorldTopologyLedger(world(0));for(int i=0;i<1_234;i++)topology.tick();
        var device=new PhysicalFluidTopology.Device(5_000,new PhysicalFluidTopology.Position("minecraft:overworld",40,0,40),Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive());
        topology.commit(topology.queue(List.of(new WorldTopologyLedger.Edit(5_000,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen)),0))),Set.of(5_000L),5_001));
        var world=topology.snapshot();

        var folder=temporary.resolve("world/data");
        var data=new FluidSavedData(checkpoint,world,key->model,directory(folder));data.setDirty();
        data.save(core(folder).toFile(),null);
        assertFalse(data.isDirty(),"Minecraft's save path wrote the checkpoint");
        assertEquals(Set.of("fluid-1.pack"),packs(folder),"one pack: every unit of the first save");assertTrue(Files.exists(core(folder)));
        assertEquals(5,data.lastSave().payloadsEncoded());assertTrue(data.lastSave().topologyEncoded());

        var loaded=FluidSavedData.read(directory(folder),key->model).orElseThrow();
        for(int i=0;i<5;i++) {
            var a=checkpoint.islands().get(i).snapshot();var b=loaded.checkpoint().islands().get(i).snapshot();String what="island "+a.id();
            assertEquals(a.id(),b.id());assertEquals(a.revision(),b.revision(),what);assertEquals(a.clock(),b.clock(),what+" clock, exact");
            assertEquals(a.fences(),b.fences(),what);assertEquals(a.status(),b.status(),what);assertEquals(a.allowance(),b.allowance(),what);
            assertInventories(a.graph(),b.graph(),what);
            assertEquals(a.lastResult().isPresent(),b.lastResult().isPresent(),what);
            a.lastResult().ifPresent(r->{var s=b.lastResult().orElseThrow();assertArrayEquals(r.averageMassFlows(),s.averageMassFlows(),what);assertArrayEquals(r.endpointHeads(),s.endpointHeads(),what);
                assertEquals(r.endpointModes(),s.endpointModes(),what);assertEquals(r.pumpWorkJoule(),s.pumpWorkJoule(),what);assertEquals(r.pipeTransfers().size(),s.pipeTransfers().size(),what);
                for(int p=0;p<r.pipeTransfers().size();p++){var x=r.pipeTransfers().get(p);var y=s.pipeTransfers().get(p);assertEquals(x.pipeId(),y.pipeId());
                    assertTrue(Arrays.deepEquals(x.forward().phaseMoles(),y.forward().phaseMoles())&&Arrays.deepEquals(x.reverse().phaseMoles(),y.reverse().phaseMoles()),what+" pipe history");}});
            assertEquals(a.certificate().isPresent(),b.certificate().isPresent(),what);
            if(a.certificate().isPresent()) {
                var x=a.certificate().orElseThrow();var y=b.certificate().orElseThrow();
                assertEquals(List.of(x.drift(),x.sinceTick(),x.baseTick(),x.horizonTick()),List.of(y.drift(),y.sinceTick(),y.baseTick(),y.horizonTick()),what);
                var s=x.saved().orElseThrow();var t=y.saved().orElseThrow();assertEquals(s.signature(),t.signature(),what);
                assertInventories(s.interval().before(),t.interval().before(),what+" certified interval start");assertInventories(s.interval().result().graph(),t.interval().result().graph(),what+" base");
            }
        }
        assertEquals(1_260,loaded.checkpoint().islands().get(4).snapshot().clock().retryAtTick(),"the held island's retry tick");
        assertEquals(checkpoint.transfers().pending().keySet(),loaded.checkpoint().transfers().pending().keySet(),"pending material");
        assertEquals(checkpoint.transfers().buffers(),loaded.checkpoint().transfers().buffers());
        assertEquals(module.definition().id(),loaded.checkpoint().modules().getFirst().definition().id());assertEquals(module.committedTick(),loaded.checkpoint().modules().getFirst().committedTick());
        assertEquals(world.events().getFirst().id(),loaded.world().orElseThrow().events().getFirst().id(),"the queued topology event");
        assertEquals(1_234,loaded.world().orElseThrow().onlineTick());
    }

    // ---------------- the dirty rule, on disk ----------------

    /** A save after no change writes no unit and no pack; after one island's solve, one new pack of exactly that unit. */
    @Test void aSaveWritesOnlyTheUnitsOfIslandsThatChanged() throws IOException {
        var rig=new Rig(new CertificatePolicy(true,1e-9,1e-6,5,2,0));
        rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));rig.register(3,slowFill(300,8));
        rig.runUntilCertified(List.of(1L,2L),2_000);
        var ledger=new WorldTopologyLedger(world(0));
        java.util.function.Supplier<WorldTopologyLedger.Snapshot> topology=()->{while(ledger.onlineTick()<rig.epoch[0])ledger.tick();return ledger.snapshot();};
        var folder=temporary.resolve("dirty");var data=new FluidSavedData(rig.checkpoint(),topology.get(),key->model,directory(folder));
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),topology.get()));
        data.save(new CompoundTag(),null);
        assertEquals(3,data.lastSave().payloadsEncoded());var first=files(folder);
        data.save(new CompoundTag(),null);var second=data.lastSave();
        assertEquals(0,second.payloadsEncoded(),"nothing changed: no unit written");assertEquals(0,second.stats().pack(),"no pack written");
        var after=files(folder);after.remove(FluidCheckpointStore.CORE_NAME);var before=new TreeMap<>(first);before.remove(FluidCheckpointStore.CORE_NAME);
        assertEquals(before,after,"every pack file is untouched");
        rig.run(100);
        data.save(new CompoundTag(),null);var third=data.lastSave();
        assertEquals(1,third.payloadsEncoded(),"only the slow fill solved");assertEquals(0,third.unitsCopied());
        assertEquals(Set.of("fluid-1.pack","fluid-2.pack"),packs(folder));
        assertEquals(FluidCheckpointStore.PACK_HEADER+third.stats().encodedBytes(),Files.size(pack(folder,2)),"the new pack holds exactly that island's unit");
        var core=NbtIo.readCompressed(core(folder),NbtAccounter.unlimitedHeap()).getCompound("data");var islands=core.getCompound("Islands");
        assertEquals(2,islands.getLongArray("Pack")[FluidCheckpointCodec.row(core,3)]);assertEquals(1,islands.getLongArray("Pack")[FluidCheckpointCodec.row(core,1)]);assertEquals(1,islands.getLongArray("Pack")[FluidCheckpointCodec.row(core,2)]);
    }

    // ---------------- atomic writes and recovery ----------------

    /**
     * A crash after the new pack but before the core record's rename: the previous checkpoint stays in force and reads
     * back; the new pack is an orphan, which the next load deletes with any temporary file a write left; the store that
     * failed writes every unit afresh at its next save.
     */
    @Test void aCrashBetweenAPackAndItsCoreRecordLeavesThePreviousCheckpointAndAnOrphanTheLoadDeletes() throws IOException {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine(100,0));rig.register(2,slowFill(200,4));
        rig.runUntilCertified(List.of(1L),2_000);
        var folder=temporary.resolve("crash");var failing=new Failing(new FluidCheckpointStore.Directory(folder,false));var store=FluidCheckpointStore.of(failing,Runnable::run);
        var data=new FluidSavedData(rig.checkpoint(),world(rig.epoch[0]),key->model,store);
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),world(rig.epoch[0])));
        data.save(new CompoundTag(),null);var savedClock=rig.stored(2).clock();var savedCore=files(folder).get(FluidCheckpointStore.CORE_NAME);
        rig.run(100);assertNotEquals(savedClock,rig.stored(2).clock());
        failing.failCore=true;
        assertThrows(IllegalStateException.class,()->data.save(new CompoundTag(),null),"the save reports it was not written");
        assertTrue(Files.exists(pack(folder,2)),"the new pack was written before the failure");
        assertEquals(savedCore,files(folder).get(FluidCheckpointStore.CORE_NAME),"the core record is the previous one");
        // What a crash in the middle of a write leaves: temporaries beside the pack and beside the core record.
        Files.write(folder.resolve(FluidCheckpointStore.UNIT_DIRECTORY).resolve("fluid-3.pack.8127.tmp"),new byte[]{1,2,3});
        Files.write(folder.resolve(FluidCheckpointStore.CORE_NAME+".4410.tmp"),new byte[]{4,5});
        var restarted=directory(folder);var loaded=FluidSavedData.read(restarted,key->model).orElseThrow();
        assertEquals(savedClock,loaded.checkpoint().islands().stream().filter(e->e.snapshot().id()==2).findFirst().orElseThrow().snapshot().clock(),"the restart reads the previous checkpoint");
        assertEquals(3,restarted.cleanOrphans(),"the orphan pack and both temporaries");
        assertEquals(Set.of("fluid-1.pack"),packs(folder));assertFalse(Files.exists(folder.resolve(FluidCheckpointStore.CORE_NAME+".4410.tmp")));
        failing.failCore=false;
        data.save(new CompoundTag(),null);
        assertTrue(data.lastSave().stats().full(),"after a failed commit every unit is written afresh");assertEquals(2,data.lastSave().payloadsEncoded());
        var reloaded=FluidSavedData.read(directory(folder),key->model).orElseThrow();
        assertEquals(rig.stored(2).clock(),reloaded.checkpoint().islands().stream().filter(e->e.snapshot().id()==2).findFirst().orElseThrow().snapshot().clock());
    }

    /** Commits run in order on the IO executor; one whose predecessor failed is not written, and the next save is whole. */
    @Test void aCommitWhosePredecessorFailedIsNotWrittenAndTheNextSaveWritesEverythingAfresh() throws IOException {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,slowFill(100,0));rig.register(2,deadHeadedLine(200,4));
        rig.runUntilCertified(List.of(2L),2_000);
        var folder=temporary.resolve("chain");var queue=new ArrayDeque<Runnable>();
        var failing=new Failing(new FluidCheckpointStore.Directory(folder,false));var store=FluidCheckpointStore.of(failing,queue::add);
        var data=new FluidSavedData(rig.checkpoint(),world(rig.epoch[0]),key->model,store);
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),world(rig.epoch[0])));
        var file=core(folder).toFile();
        data.setDirty();data.save(file,null);assertEquals(1,queue.size(),"the commit waits for the IO worker");queue.poll().run();
        rig.run(100);data.setDirty();data.save(file,null);
        rig.run(100);data.setDirty();data.save(file,null);var clockOfB=rig.stored(1).clock();
        var before=files(folder);
        failing.failPack=true;queue.poll().run();failing.failPack=false;
        queue.poll().run();
        assertFalse(store.committed(),"the save built on the failed one was not written");
        assertEquals(before,files(folder),"neither commit changed a file");
        data.setDirty();data.save(file,null);assertTrue(data.lastSave().stats().full());assertEquals(2,data.lastSave().payloadsEncoded());
        queue.poll().run();assertTrue(store.committed());
        var loaded=FluidSavedData.read(store.reopen(),key->model).orElseThrow();
        assertEquals(clockOfB,loaded.checkpoint().islands().getFirst().snapshot().clock());
    }

    /** A truncated, damaged, missing or misnamed pack refuses the world, and the refusal leaves every file as it was. */
    @Test void aHalfWrittenDamagedOrMissingPackRefusesTheWorldAndLeavesEveryFileAsItWas() throws IOException {
        var folder=temporary.resolve("source");var store=directory(folder);
        var data=new FluidSavedData(checkpoint(List.of(tank(1,1,0),tank(2,2,0))),key->model,store);
        data.save(new CompoundTag(),null);
        data.replace(checkpoint(List.of(tank(1,1,0),tank(2,3,0))));data.save(new CompoundTag(),null);
        assertEquals(Set.of("fluid-1.pack","fluid-2.pack"),packs(folder));
        Files.write(pack(folder,9),new byte[]{9,9,9});
        record Case(String expected,IOConsumer damage) {}
        var cases=List.<Case>of(
                new Case("half-written or damaged pack",f->{var p=pack(f,2);Files.write(p,Arrays.copyOf(Files.readAllBytes(p),(int)Files.size(p)-5));}),
                new Case("checksum mismatch in the unit of island 1",f->{var p=pack(f,1);var b=Files.readAllBytes(p);b[FluidCheckpointStore.PACK_HEADER+40]^=1;Files.write(p,b);}),
                new Case("is missing",f->Files.delete(pack(f,2))),
                new Case("names another pack",f->{var p=pack(f,1);var b=Files.readAllBytes(p);b[15]^=1;Files.write(p,b);}));
        for(var c:cases) {
            var copy=temporary.resolve("case-"+cases.indexOf(c));
            try(var walk=Files.walk(folder)){for(var source:walk.toList()){var target=copy.resolve(folder.relativize(source).toString());if(Files.isDirectory(source))Files.createDirectories(target);else Files.copy(source,target);}}
            c.damage().accept(copy);var before=files(copy);
            var refusal=assertThrows(IllegalArgumentException.class,()->FluidSavedData.read(directory(copy),key->model));
            assertTrue(refusal.getMessage().contains(c.expected()),c.expected()+" / got: "+refusal.getMessage());
            assertEquals(before,files(copy),c.expected()+": the refused world's files, the orphan included, are left exactly as they were");
        }
    }
    @FunctionalInterface private interface IOConsumer{void accept(Path folder) throws IOException;}
    /** The pack file names the core record on disk references. */
    private static Set<String> referenced(Path folder) throws IOException {
        var names=new TreeSet<String>();for(long p:NbtIo.readCompressed(core(folder),NbtAccounter.unlimitedHeap()).getCompound("data").getLongArray("Packs"))names.add("fluid-"+p+".pack");return names;
    }

    // ---------------- orphans and compaction ----------------

    /**
     * Units of removed or replaced islands are dropped from the index; a pack that becomes mostly dead has its live
     * units moved into the next save's pack; a pack no longer referenced is deleted one save later (a copy of the
     * world taken during a save still finds the packs of the core record it copied); at most 16 packs are referenced.
     */
    @Test void unitsOfRemovedOrReplacedIslandsAreDroppedAndTheirPacksCompactedAndDeleted() throws IOException {
        var folder=temporary.resolve("orphans");var data=new FluidSavedData(checkpoint(List.of(tank(1,1,0),tank(2,2,0),tank(3,3,0),tank(4,4,0))),key->model,directory(folder));
        data.save(new CompoundTag(),null);assertEquals(Set.of("fluid-1.pack"),packs(folder));
        // Islands 2, 3 and 4 are removed: one live unit of four is left in pack 1, which is moved into pack 2.
        data.replace(checkpoint(List.of(tank(1,1,0))));data.save(new CompoundTag(),null);var moved=data.lastSave();
        assertEquals(0,moved.payloadsEncoded());assertEquals(1,moved.unitsCopied(),"the surviving unit is moved out of the sparse pack");
        assertEquals(1,moved.stats().packs());assertEquals(Set.of("fluid-1.pack","fluid-2.pack"),packs(folder),"pack 1 is kept for the core record before this one");
        data.save(new CompoundTag(),null);assertEquals(0,data.lastSave().stats().pack(),"nothing to write");
        assertEquals(Set.of("fluid-2.pack"),packs(folder),"one save later the unreferenced pack is deleted");
        // Island 1 is replaced by island 5 (a topology change): island 1's unit is dead, pack 2 is dropped, then deleted.
        data.replace(checkpoint(List.of(tank(5,5,0))));data.save(new CompoundTag(),null);
        assertEquals(1,data.lastSave().stats().packs());assertEquals(Set.of("fluid-2.pack","fluid-3.pack"),packs(folder));
        data.save(new CompoundTag(),null);assertEquals(Set.of("fluid-3.pack"),packs(folder));
        var loaded=FluidSavedData.read(directory(folder),key->model).orElseThrow();
        assertEquals(List.of(5L),loaded.checkpoint().islands().stream().map(e->e.snapshot().id()).toList());
        // Twenty islands, one of them changing at each of forty saves: never more than 16 packs referenced, 17 on disk.
        var generations=new long[21];var snapshots=new ArrayList<IslandCoordinator.Snapshot>();long next=100;
        for(int i=1;i<=20;i++){generations[i]=next++;snapshots.add(tank(i,generations[i],0));}
        data.replace(checkpoint(snapshots));data.save(new CompoundTag(),null);var previous=referenced(folder);int most=0;
        for(int save=0;save<40;save++) {
            int changed=1+save%20;generations[changed]=next++;snapshots.set(changed-1,tank(changed,generations[changed],0));
            data.replace(checkpoint(snapshots));data.save(new CompoundTag(),null);
            assertEquals(1,data.lastSave().payloadsEncoded());
            assertTrue(data.lastSave().stats().packs()<=FluidCheckpointStore.MAXIMUM_PACKS,"packs referenced: "+data.lastSave().stats().packs());
            var now=referenced(folder);var expected=new TreeSet<String>(now);expected.addAll(previous);
            assertEquals(expected,packs(folder),"on disk: the packs of this core record and of the one before it, nothing else");previous=now;most=Math.max(most,packs(folder).size());
        }
        System.out.println("pack files on disk at most: "+most);
        var last=FluidSavedData.read(directory(folder),key->model).orElseThrow();
        assertEquals(20,last.checkpoint().islands().size());
    }

    /** A save larger than the pack size spans several packs (no save is bounded by one), all written before the core. */
    @Test void aSaveLargerThanOnePackSpansSeveralAndReadsBack() throws IOException {
        var folder=temporary.resolve("spanning");var store=directory(folder);store.packLimit(1_000);
        var snapshots=new ArrayList<IslandCoordinator.Snapshot>();for(long id=1;id<=12;id++)snapshots.add(tank(id,id,0));
        var data=new FluidSavedData(checkpoint(snapshots),key->model,store);data.save(new CompoundTag(),null);
        var stats=data.lastSave().stats();
        assertTrue(stats.newPacks()>1,"several packs: "+stats.newPacks());assertEquals(stats.newPacks(),packs(folder).size());assertEquals(stats.newPacks(),stats.packs());
        for(var name:packs(folder))assertTrue(Files.size(folder.resolve(FluidCheckpointStore.UNIT_DIRECTORY).resolve(name))<=1_000||name.equals("fluid-1.pack"),name);
        var loaded=FluidSavedData.read(directory(folder),key->model).orElseThrow();
        assertEquals(12,loaded.checkpoint().islands().size());
        for(int i=0;i<12;i++)assertInventories(snapshots.get(i).graph(),loaded.checkpoint().islands().get(i).snapshot().graph(),"island "+(i+1));
        snapshots.set(3,tank(4,40,0));data.replace(checkpoint(snapshots));data.save(new CompoundTag(),null);
        assertEquals(1,data.lastSave().payloadsEncoded());assertEquals(1,data.lastSave().stats().newPacks());
        assertEquals(12,FluidSavedData.read(directory(folder),key->model).orElseThrow().checkpoint().islands().size());
    }

    // ---------------- scale ----------------

    /** Ten thousand certified islands: the index saves and loads without a world-wide bound, and warm saves write nothing. */
    @Test void tenThousandIslandsSaveAndLoadThroughTheIndex() throws IOException {
        var policy=CertificatePolicy.defaults();
        var template=new Rig(policy);template.register(1,loneTank(1_000_000));template.runUntilCertified(List.of(1L),2_000);
        var t=template.read(1);var certified=t.certificate().orElseThrow();var saved=certified.saved().orElseThrow();
        var snapshots=new ArrayList<IslandCoordinator.Snapshot>(10_000);
        for(long id=1;id<=10_000;id++) {
            long node=1_000_000+id;
            java.util.function.UnaryOperator<PassiveNetwork> relabel=g->new PassiveNetwork(g.reservoirs().stream().map(n->new PassiveNetwork.Reservoir(node,n.elevation(),n.state(),n.kind(),n.inventory())).toList(),g.pipes());
            var r=saved.interval().result();
            var result=new PassiveIntervalSolver.Result(relabel.apply(r.graph()),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),
                    r.boundaries().stream().map(b->new ConservativeTransport.BoundaryTransfer(node,b.moles(),b.totalEnergyJoule(),b.solids(),b.solidDirection())).toList(),r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.acceptance(),r.pipeTransfers());
            var copy=new IslandCertificate.Saved(saved.sinceTick(),saved.horizonTick(),new IslandCertificate.Interval(saved.interval().startTick(),saved.interval().endTick(),relabel.apply(saved.interval().before()),result),
                    IslandCertificate.Signature.of(model,policy,result.graph()));
            snapshots.add(new IslandCoordinator.Snapshot(id,0,relabel.apply(t.graph()),t.clock(),t.allowance(),t.anchor().map(a->new ApproximationAnchor(a.propertyRevision(),relabel.apply(a.graph()),a.modes())),Optional.of(result),t.status(),Map.of(),
                    Optional.of(new IslandCoordinator.Certified(certified.drift(),certified.sinceTick(),certified.baseTick(),certified.horizonTick(),certified.largestFlow(),Optional.of(copy)))));
        }
        countFromHere();
        var rig=new Rig(policy,model,template.epoch[0]);for(var s:snapshots)rig.coordinator.register(s,model);
        assertEquals(10_000,counter("certificatesRestored"));
        var folder=temporary.resolve("ten-thousand");var world=world(template.epoch[0]);
        var data=new FluidSavedData(rig.checkpoint(),world,key->model,directory(folder));data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),world));
        data.save(new CompoundTag(),null);var cold=data.lastSave();
        data.save(new CompoundTag(),null);var warm=data.lastSave();
        assertEquals(10_000,cold.payloadsEncoded());assertEquals(0,warm.payloadsEncoded());assertEquals(10_000,warm.payloadsReused());assertEquals(0,warm.stats().pack());
        long read0=System.nanoTime();var loaded=FluidSavedData.read(directory(folder),key->model).orElseThrow();long read1=System.nanoTime();
        var restarted=new Rig(policy,model,template.epoch[0]);long register0=System.nanoTime();restarted.load(loaded.checkpoint());long register1=System.nanoTime();
        loaded.bindCapture(()->new FluidSavedData.Capture(restarted.checkpoint(),loaded.world().orElseThrow()));
        loaded.save(new CompoundTag(),null);var seeded=loaded.lastSave();
        assertEquals(0,seeded.payloadsEncoded(),"the first save after the load encodes nothing");assertEquals(10_000,seeded.payloadsReused());
        long coreFile=Files.size(core(folder));long packFiles=0;for(var name:packs(folder))packFiles+=Files.size(folder.resolve(FluidCheckpointStore.UNIT_DIRECTORY).resolve(name));
        System.out.printf(Locale.ROOT,"10000 islands: cold save capture %.1f ms, encode %.1f ms, write %.1f ms (%d unit bytes, pack %d bytes, core %d bytes); warm save capture %.1f ms, encode %.1f ms, write %.1f ms; "
                        +"load %.1f ms; register %.1f ms; first save after load capture %.1f ms, encode %.1f ms, write %.1f ms; core file %d bytes, pack files %d bytes%n",
                cold.captureNanos()/1e6,cold.encodeNanos()/1e6,cold.writeNanos()/1e6,cold.stats().encodedBytes(),cold.stats().packBytes(),cold.stats().coreBytes(),warm.captureNanos()/1e6,warm.encodeNanos()/1e6,warm.writeNanos()/1e6,
                (read1-read0)/1e6,(register1-register0)/1e6,seeded.captureNanos()/1e6,seeded.encodeNanos()/1e6,seeded.writeNanos()/1e6,coreFile,packFiles);
    }

    // ---------------- the seeded store ----------------

    /**
     * The index a load reads seeds the dirty tracking: registered as loaded, awake, no-flow and moving certified islands keep their
     * units and the topology its unit, so the first save after the load encodes nothing; a certificate discarded at
     * registration (certificates off at the restart) changes what its island records, and only those units are written.
     */
    @Test void theFirstSaveAfterALoadEncodesNothingThatDidNotChange() throws IOException {
        var policy=CertificatePolicy.defaults();
        var rig=new Rig(policy);rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));rig.register(3,generatorToVoid(300,8));rig.register(4,slowFill(400,12));
        rig.runUntilCertified(List.of(1L,2L,3L),2_000);
        var folder=temporary.resolve("seeded");var world=world(rig.epoch[0]);
        new FluidSavedData(rig.checkpoint(),world,key->model,directory(folder)).save(new CompoundTag(),null);
        for(var restartPolicy:List.of(policy,CertificatePolicy.disabled())) {
            var loaded=FluidSavedData.read(directory(folder),key->model).orElseThrow();
            var restarted=new Rig(restartPolicy,model,rig.epoch[0]);restarted.load(loaded.checkpoint());
            loaded.bindCapture(()->new FluidSavedData.Capture(restarted.checkpoint(),loaded.world().orElseThrow()));
            loaded.save(new CompoundTag(),null);var first=loaded.lastSave();
            assertFalse(first.topologyEncoded(),"the topology unit stays");
            if(restartPolicy.enabled()) {
                assertEquals(0,first.payloadsEncoded(),"nothing changed: nothing is encoded");assertEquals(4,first.payloadsReused());assertEquals(0,first.stats().pack(),"no pack written");
            } else {
                assertEquals(3,first.payloadsEncoded(),"the three discarded certificates' islands are written afresh");assertEquals(1,first.payloadsReused(),"the awake island's unit stays");
            }
        }
    }
}
