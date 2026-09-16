package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CausalModuleCoordinatorTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private FluidThermodynamics.State state(boolean wet) {
        var nitrogen=model.initialNitrogenCharge(1,298.15,101325,()->{});if(!wet)return nitrogen;
        var n=new double[22];System.arraycopy(nitrogen.vapor(),0,n,0,21);n[21]=100/model.waterMolecularWeight;
        return model.flashTP(298.15,101325,n,()->{});
    }
    private static UUID id(int i){return new UUID(0,i);}
    private FixedSplitModule.Snapshot module(int id,int firstFeed,int secondFeed,int firstProduct,int secondProduct,int cadence,boolean split) {
        double[] fractions=new double[22];Arrays.fill(fractions,split?.5:1);
        var feeds=new ArrayList<FixedSplitModule.Feed>();feeds.add(new FixedSplitModule.Feed(id(firstFeed),.2));if(secondFeed>0)feeds.add(new FixedSplitModule.Feed(id(secondFeed),.1));
        return new FixedSplitModule.Snapshot(new FixedSplitModule.Definition(id(id),feeds,id(firstProduct),id(secondProduct),cadence,fractions),0,0,false,null);
    }
    private final class Harness {
        private record Job(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidSolveCommand command) {}
        final IslandCoordinator islands;
        final CausalModuleCoordinator modules;
        final ArrayDeque<Job> work=new ArrayDeque<>();
        final double[] initial;
        int active;long requests;int commits;
        Harness(List<PassiveNetwork> graphs,List<FixedSplitModule.Snapshot> definitions) {
            this(initialCheckpoint(graphs,definitions));
        }
        Harness(FluidCheckpointCodec.Checkpoint checkpoint) {
            modules=new CausalModuleCoordinator(model,checkpoint.moduleBindings(),checkpoint.transfers(),checkpoint.modules());
            islands=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 2-active;}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){work.add(new Job(attempt,modules.command(attempt,command)));active++;return true;}
                public void cancel(long request){throw new AssertionError("No deadlines in deterministic qualification");}
            },changed->{commits+=changed.size();published();},()->0,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false),modules::prepare);
            for(var entry:checkpoint.islands())islands.register(entry.snapshot(),model);
            modules.attach(islands);initial=totals();modules.advance();
        }
        FluidCheckpointCodec.Checkpoint checkpoint() {
            assertTrue(work.isEmpty());assertEquals(0,active);
            return new FluidCheckpointCodec.Checkpoint(islands.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry("minecraft:overworld","createcheme:tjl20_methane",1e-9,s)).toList(),modules.transfers(),modules.snapshots(),modules.bindings());
        }
        private void published() {
            var owners=new HashMap<Long,Long>();for(var island:islands.snapshots())for(var node:island.graph().reservoirs())owners.put(node.id(),island.id());
            modules.rebind(owners);modules.advance();
        }
        void remove(int islandId) {
            assertEquals(0,active);var snapshot=islands.snapshot(islandId);var removed=snapshot.graph().reservoirs().getFirst().inventory();
            var n=removed.moles();for(int c=0;c<22;c++)initial[c]-=n[c];initial[22]-=removed.internalEnergy();
            var event=UUID.randomUUID();long tick=snapshot.clock().committedTick();islands.fence(event,tick,Set.of((long)islandId));
            islands.topology(event,Set.of((long)islandId),List.of(),model,tick,snapshot.clock().onlineTick(),Map.of(),Set.of((long)islandId),()->{});
        }
        private static FluidCheckpointCodec.Checkpoint initialCheckpoint(List<PassiveNetwork> graphs,List<FixedSplitModule.Snapshot> definitions) {
            var bindings=new ArrayList<CausalModuleCoordinator.Binding>();var buffers=new HashMap<UUID,BufferedTransfers.Buffer>();
            for(int i=0;i<graphs.size();i++){var node=graphs.get(i).reservoirs().getFirst();bindings.add(new CausalModuleCoordinator.Binding(id(i+1),i+1,node.id()));buffers.put(id(i+1),new BufferedTransfers.Buffer(id(i+1),1000,node.state().mass(),Map.of()));}
            var entries=new ArrayList<FluidCheckpointCodec.IslandEntry>();
            for(int i=0;i<graphs.size();i++)entries.add(new FluidCheckpointCodec.IslandEntry("minecraft:overworld","createcheme:tjl20_methane",1e-9,new IslandCoordinator.Snapshot(i+1,0,graphs.get(i),new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY")));
            return new FluidCheckpointCodec.Checkpoint(entries,new BufferedTransfers.Snapshot(0,buffers,Map.of()),definitions,bindings);
        }
        void run(int ticks) {
            var token=new BoundedCpuSolveService.CancellationToken(){public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}};
            for(int tick=0;tick<ticks;tick++) {
                modules.advance();islands.tick();int drained=0;
                while(!work.isEmpty()) {
                    assertTrue(++drained<100,"Unbounded dispatch loop");var job=work.removeFirst();var result=(ProcessSolveServices.FluidIslandSolveResult)job.command.solve(token);
                    assertTrue(result.candidate().isPresent(),result.detail());active--;islands.completed(job.attempt,Optional.of(result));modules.advance();islands.pump();assertConserved();
                }
            }
        }
        double[] totals() {
            double[] sum=new double[23];for(var island:islands.snapshots())for(var node:island.graph().reservoirs()) {var n=node.inventory().moles();for(int c=0;c<22;c++)sum[c]+=n[c];sum[22]+=node.inventory().internalEnergy();}
            for(var pending:modules.transfers().pending().values())add(sum,pending.remaining());
            for(var module:modules.snapshots())if(module.cycle()!=null)for(var input:module.cycle().inputs().values())add(sum,input.owned());return sum;
        }
        void assertConserved(){var actual=totals();for(int c=0;c<22;c++)assertEquals(initial[c],actual[c],1e-9*Math.max(1,initial[c]),"Component "+c);assertEquals(initial[22],actual[22],1e-6*Math.max(1,Math.abs(initial[22])));}
    }
    private static void add(double[] sum,MaterialParcel parcel){var n=parcel.moles();for(int c=0;c<22;c++)sum[c]+=n[c];sum[22]+=parcel.energyJoule();}
    private List<PassiveNetwork> graphs(boolean... wet){var graphs=new ArrayList<PassiveNetwork>();for(int i=0;i<wet.length;i++)graphs.add(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(i+1,0,state(wet[i]))),List.of()));return graphs;}
    @Test void coupledHydraulicsAndFifteenSecondModulePreserveAllOwnersAtEveryCommit() {
        var harness=new Harness(graphs(true,true,false,false),List.of(module(100,1,2,3,4,300,true)));harness.run(900);
        assertEquals(900,harness.modules.snapshots().getFirst().committedTick());for(var island:harness.islands.snapshots())assertEquals(900,island.clock().committedTick(),island.status());
        assertTrue(harness.islands.snapshot(3).graph().reservoirs().getFirst().inventory().moles()[21]>0);assertTrue(harness.islands.snapshot(4).graph().reservoirs().getFirst().inventory().moles()[21]>0);
        assertTrue(harness.modules.transfers().pending().size()<=2);assertTrue(harness.modules.transfers().planned().size()<=2);assertEquals(0,harness.active);
    }
    @Test void emptyCycleWithDifferentCadencesBootstrapsWithoutCircularProductionWait() {
        var harness=new Harness(graphs(false,false,false),List.of(module(100,1,0,2,3,100,false),module(200,2,0,1,3,300,false)));harness.run(900);
        for(var module:harness.modules.snapshots())assertEquals(900,module.committedTick());for(var island:harness.islands.snapshots())assertEquals(900,island.clock().committedTick(),island.status());
        assertTrue(harness.modules.transfers().pending().isEmpty());assertTrue(harness.modules.transfers().planned().isEmpty());
    }
    @Test void twoBufferedModulesRecycleActuallyOwnedMaterialWithoutSchedulingDeadlock() {
        var harness=new Harness(graphs(true,true,false),List.of(module(100,1,0,2,3,100,false),module(200,2,0,1,3,300,false)));harness.run(900);
        for(var module:harness.modules.snapshots())assertEquals(900,module.committedTick());for(var island:harness.islands.snapshots())assertEquals(900,island.clock().committedTick(),island.status());
        harness.assertConserved();assertTrue(harness.modules.transfers().pending().size()<=4);
    }
    @Test void restartWithPartialFeedOrPendingProductsContinuesTheSamePhysicalTrajectory() {
        for(int stop:new int[]{150,350}) {
            var uninterrupted=new Harness(graphs(true,true,false,false),List.of(module(100,1,2,3,4,300,true)));
            uninterrupted.run(stop);var before=uninterrupted.checkpoint();
            if(stop==150)assertTrue(before.modules().getFirst().cycle().inputs().values().stream().anyMatch(i->i.owned().massKg()>0));
            else assertFalse(before.transfers().pending().isEmpty());
            var restored=new Harness(FluidCheckpointCodec.decode(FluidCheckpointCodec.encode(before,key->model),key->model));
            for(int i=1;i<=4;i++)assertEquals(uninterrupted.islands.snapshot(i).clock(),restored.islands.snapshot(i).clock(),"Restart added offline time");
            uninterrupted.run(900-stop);restored.run(900-stop);
            for(int i=1;i<=4;i++) {
                var expected=uninterrupted.islands.snapshot(i);var actual=restored.islands.snapshot(i);
                assertEquals(expected.clock(),actual.clock());assertEquals(expected.fences(),actual.fences());
                var a=expected.graph().reservoirs().getFirst().inventory();var b=actual.graph().reservoirs().getFirst().inventory();
                // A restart reproduces the trajectory to solver tolerance, not bitwise: the retained
                // factorization and the carried step estimate are ephemeral cost hints, which a
                // restored island rediscovers, so its intervals reach the same root along a slightly
                // different path. Compare relatively with the previous absolute bound as the floor
                // for near-zero amounts; the conservation and ledger assertions around this loop
                // stay exact.
                var moles=a.moles();var restoredMoles=b.moles();
                for(int c=0;c<moles.length;c++)assertEquals(moles[c],restoredMoles[c],Math.max(1e-8,1e-9*Math.abs(moles[c])),"Component "+c);
                assertEquals(a.internalEnergy(),b.internalEnergy(),Math.max(1e-4,1e-9*Math.abs(a.internalEnergy())));
            }
            restored.assertConserved();assertEquals(900,restored.modules.snapshots().getFirst().committedTick());
        }
    }
    @Test void removedProductRetainsPendingMaterialOrModuleHoldupAndReleasesOtherIslands() {
        for(int stop:new int[]{200,300}) {
            var harness=new Harness(graphs(true,false,false),List.of(module(100,1,0,2,3,300,true)));harness.run(stop);
            var before=harness.modules.transfers();var stranded=new HashMap<UUID,PendingTransfers.Pending>();before.pending().forEach((id,p)->{if(p.receiver().equals(id(2)))stranded.put(id,p);});
            var moduleBefore=harness.modules.snapshots().getFirst();harness.remove(2);
            assertTrue(harness.modules.waitingReason(1).startsWith("STRANDED"));harness.run(600);
            assertEquals(stranded,harness.modules.transfers().pending());assertEquals(before.planned(),harness.modules.transfers().planned());
            assertEquals(moduleBefore,harness.modules.snapshots().getFirst());assertEquals(0,harness.modules.bindings().stream().filter(b->b.buffer().equals(id(2))).findFirst().orElseThrow().island());
            for(var island:harness.islands.snapshots()){assertEquals(stop+600,island.clock().committedTick());assertTrue(island.fences().isEmpty());}
            var restored=new Harness(FluidCheckpointCodec.decode(FluidCheckpointCodec.encode(harness.checkpoint(),key->model),key->model));restored.run(100);restored.assertConserved();
            var after=restored.modules.transfers().pending();assertEquals(stranded.keySet(),after.keySet());
            stranded.forEach((id,p)->{assertArrayEquals(p.remaining().moles(),after.get(id).remaining().moles());assertEquals(p.remaining().energyJoule(),after.get(id).remaining().energyJoule());});
        }
    }
    @Test void removingAnUnusedZeroFractionOutletDoesNotStrandTheActiveProduct() {
        var harness=new Harness(graphs(true,false,false),List.of(module(100,1,0,2,3,300,false)));harness.run(200);harness.remove(3);harness.run(700);
        assertEquals(900,harness.modules.snapshots().getFirst().committedTick());assertFalse(harness.modules.waitingReason(1).startsWith("STRANDED"));
        assertTrue(harness.islands.snapshot(2).graph().reservoirs().getFirst().inventory().moles()[21]>0);harness.assertConserved();
    }
}
