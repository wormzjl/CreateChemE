package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CausalModuleCoordinatorTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private FluidThermodynamics.State state(boolean wet) {
        var nitrogen=model.initialNitrogenCharge(1,298.15,101325,()->{});if(!wet)return nitrogen;
        var n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];System.arraycopy(nitrogen.vapor(),0,n,0,model.hydrocarbon.componentCount());n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=100/model.waterMolecularWeight;
        return model.flashTP(298.15,101325,n,()->{});
    }
    private static UUID id(int i){return new UUID(0,i);}
    private FixedSplitModule.Snapshot module(int id,int firstFeed,int secondFeed,int firstProduct,int secondProduct,int cadence,boolean split) {
        double[] fractions=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];Arrays.fill(fractions,split?.5:1);
        var feeds=new ArrayList<FixedSplitModule.Feed>();feeds.add(new FixedSplitModule.Feed(id(firstFeed),.2));if(secondFeed>0)feeds.add(new FixedSplitModule.Feed(id(secondFeed),.1));
        return new FixedSplitModule.Snapshot(new FixedSplitModule.Definition(id(id),feeds,id(firstProduct),id(secondProduct),cadence,fractions),0,0,false,null);
    }
    private final class Harness {
        private record Job(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidSolveCommand command) {}
        final IslandCoordinator islands;
        final CausalModuleCoordinator modules;
        final ArrayDeque<Job> work=new ArrayDeque<>();
        final double[] initial;
        int active;long requests;int commits,advances;
        /** Polled: advance before every tick and after every completion, the host's old contract. Otherwise the
         * modules advance only from dependent publications, drained stragglers and their own horizon deadline. */
        final boolean polled;
        Map<Long,Long> bound;
        /** Every publication, in order: each published island's clock and inventory bits, then every module's state. */
        final List<String> trajectory=new ArrayList<>();
        Harness(List<PassiveNetwork> graphs,List<FixedSplitModule.Snapshot> definitions) {
            this(initialCheckpoint(graphs,definitions));
        }
        Harness(List<PassiveNetwork> graphs,List<FixedSplitModule.Snapshot> definitions,boolean polled) {
            this(initialCheckpoint(graphs,definitions),polled);
        }
        Harness(FluidCheckpointCodec.Checkpoint checkpoint){this(checkpoint,true);}
        Harness(FluidCheckpointCodec.Checkpoint checkpoint,boolean polled) {
            this.polled=polled;
            modules=new CausalModuleCoordinator(model,checkpoint.moduleBindings(),checkpoint.transfers(),checkpoint.modules());
            islands=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 2-active;}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){work.add(new Job(attempt,modules.command(attempt,command)));active++;return true;}
                public void cancel(long request){throw new AssertionError("No deadlines in deterministic qualification");}
            },changed->{commits+=changed.size();published(changed);},()->0,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false),modules::prepare);
            for(var entry:checkpoint.islands())islands.register(entry.snapshot(),model);
            modules.attach(islands);initial=totals();
            if(polled)advance();
            else {
                islands.onReleased(id->{if(modules.dependsOn(id))islands.schedule(IslandScheduler.Kind.MODULE_HORIZON,islands.now(),this::advance);});
                advance();
            }
        }
        private void advance() {
            advances++;modules.advance();
            if(!polled)islands.schedule(IslandScheduler.Kind.MODULE_HORIZON,modules.nextHorizon(islands::epochTick,islands.now()),this::advance);
        }
        private void record(List<IslandCoordinator.Snapshot> changed) {
            var line=new StringBuilder();
            for(var island:changed) {
                line.append(island.id()).append(island.clock()).append(island.fences().values().stream().sorted().toList());
                for(var node:island.graph().reservoirs()){for(double n:node.inventory().moles())line.append(':').append(Long.toHexString(Double.doubleToLongBits(n)));line.append('/').append(Long.toHexString(Double.doubleToLongBits(node.inventory().internalEnergy())));}
            }
            for(var module:modules.snapshots()) {
                line.append('|').append(module.committedTick()).append(',').append(module.revision()).append(',').append(module.running());
                if(module.cycle()!=null){var c=module.cycle();line.append(',').append(c.startTick()).append('-').append(c.endTick()).append(',').append(c.status());
                    for(var input:c.inputs().values().stream().sorted(Comparator.comparingDouble(FixedSplitModule.Input::targetKg)).toList())line.append(',').append(input.throughTick()).append('@').append(Long.toHexString(Double.doubleToLongBits(input.owned().massKg())));}
            }
            for(var pending:modules.transfers().pending().values().stream().sorted(Comparator.comparingLong(PendingTransfers.Pending::dueTick).thenComparingDouble(p->p.remaining().massKg())).toList())
                line.append("|p").append(pending.dueTick()).append('@').append(Long.toHexString(Double.doubleToLongBits(pending.remaining().massKg())));
            trajectory.add(line.toString());
        }
        FluidCheckpointCodec.Checkpoint checkpoint() {
            assertTrue(work.isEmpty());assertEquals(0,active);
            return new FluidCheckpointCodec.Checkpoint(islands.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry("minecraft:overworld","createcheme:tjl20_methane_nitrogen",1e-9,s)).toList(),modules.transfers(),modules.snapshots(),modules.bindings());
        }
        private void published(List<IslandCoordinator.Snapshot> changed) {
            var owners=new HashMap<Long,Long>();for(var island:islands.snapshots())for(var node:island.graph().reservoirs())owners.put(node.id(),island.id());
            if(polled){modules.rebind(owners);advance();}
            else {
                boolean rebound=!owners.equals(bound);if(rebound){modules.rebind(owners);bound=owners;}
                if(rebound||modules.dependsOnAny(changed))advance();
            }
            record(changed);
        }
        void remove(int islandId) {
            assertEquals(0,active);var snapshot=islands.snapshot(islandId);var removed=snapshot.graph().reservoirs().getFirst().inventory();
            var n=removed.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)initial[c]-=n[c];initial[22]-=removed.internalEnergy();
            var event=UUID.randomUUID();long tick=snapshot.clock().committedTick();islands.fence(event,tick,Set.of((long)islandId));
            islands.topology(event,Set.of((long)islandId),List.of(),model,tick,snapshot.clock().onlineTick(),Map.of(),Set.of((long)islandId),()->{});
        }
        private static FluidCheckpointCodec.Checkpoint initialCheckpoint(List<PassiveNetwork> graphs,List<FixedSplitModule.Snapshot> definitions) {
            var bindings=new ArrayList<CausalModuleCoordinator.Binding>();var buffers=new HashMap<UUID,BufferedTransfers.Buffer>();
            for(int i=0;i<graphs.size();i++){var node=graphs.get(i).reservoirs().getFirst();bindings.add(new CausalModuleCoordinator.Binding(id(i+1),i+1,node.id()));buffers.put(id(i+1),new BufferedTransfers.Buffer(id(i+1),1000,node.state().mass(),Map.of()));}
            var entries=new ArrayList<FluidCheckpointCodec.IslandEntry>();
            for(int i=0;i<graphs.size();i++)entries.add(new FluidCheckpointCodec.IslandEntry("minecraft:overworld","createcheme:tjl20_methane_nitrogen",1e-9,new IslandCoordinator.Snapshot(i+1,0,graphs.get(i),new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY")));
            return new FluidCheckpointCodec.Checkpoint(entries,new BufferedTransfers.Snapshot(0,buffers,Map.of()),definitions,bindings);
        }
        void run(int ticks) {
            var token=new BoundedCpuSolveService.CancellationToken(){public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}};
            for(int tick=0;tick<ticks;tick++) {
                if(polled)advance();islands.tick();int drained=0;
                while(!work.isEmpty()) {
                    assertTrue(++drained<100,"Unbounded dispatch loop");var job=work.removeFirst();var result=(ProcessSolveServices.FluidIslandSolveResult)job.command.solve(token);
                    assertTrue(result.candidate().isPresent(),result.detail());active--;islands.completed(job.attempt,Optional.of(result));if(polled)advance();islands.pump();assertConserved();
                }
            }
        }
        double[] totals() {
            double[] sum=new double[23];for(var island:islands.snapshots())for(var node:island.graph().reservoirs()) {var n=node.inventory().moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)sum[c]+=n[c];sum[22]+=node.inventory().internalEnergy();}
            for(var pending:modules.transfers().pending().values())add(sum,pending.remaining());
            for(var module:modules.snapshots())if(module.cycle()!=null)for(var input:module.cycle().inputs().values())add(sum,input.owned());return sum;
        }
        void assertConserved(){var actual=totals();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)assertEquals(initial[c],actual[c],1e-9*Math.max(1,initial[c]),"Component "+c);assertEquals(initial[22],actual[22],1e-6*Math.max(1,Math.abs(initial[22])));}
    }
    private static void add(double[] sum,MaterialParcel parcel){var n=parcel.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)sum[c]+=n[c];sum[22]+=parcel.energyJoule();}
    private List<PassiveNetwork> graphs(boolean... wet){var graphs=new ArrayList<PassiveNetwork>();for(int i=0;i<wet.length;i++)graphs.add(new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(i+1,0,state(wet[i]))),List.of()));return graphs;}
    @Test void coupledHydraulicsAndFifteenSecondModulePreserveAllOwnersAtEveryCommit() {
        var harness=new Harness(graphs(true,true,false,false),List.of(module(100,1,2,3,4,300,true)));harness.run(900);
        assertEquals(900,harness.modules.snapshots().getFirst().committedTick());for(var island:harness.islands.snapshots())assertEquals(900,island.clock().committedTick(),island.status());
        assertTrue(harness.islands.snapshot(3).graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]>0);assertTrue(harness.islands.snapshot(4).graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]>0);
        assertTrue(harness.modules.transfers().pending().size()<=2);assertTrue(harness.modules.transfers().planned().size()<=2);assertEquals(0,harness.active);
    }
    /** Advancing only from dependent publications, drained stragglers and the modules' own horizon deadline
     * reproduces, publication for publication, the cycles and trajectories of advancing on every tick. */
    @Test void deadlineDrivenAdvanceReproducesThePolledCycleSequence() {
        record Case(String name,boolean[] wet,List<FixedSplitModule.Snapshot> modules) {}
        for(var scenario:List.of(
                new Case("coupled",new boolean[]{true,true,false,false},List.of(module(100,1,2,3,4,300,true))),
                new Case("empty-cycle",new boolean[]{false,false,false},List.of(module(100,1,0,2,3,100,false),module(200,2,0,1,3,300,false))),
                new Case("recycle",new boolean[]{true,true,false},List.of(module(100,1,0,2,3,100,false),module(200,2,0,1,3,300,false))))) {
            var polled=new Harness(graphs(scenario.wet()),scenario.modules(),true);polled.run(900);
            var driven=new Harness(graphs(scenario.wet()),scenario.modules(),false);driven.run(900);
            assertEquals(polled.trajectory.size(),driven.trajectory.size(),scenario.name()+" publications");
            for(int i=0;i<polled.trajectory.size();i++)assertEquals(polled.trajectory.get(i),driven.trajectory.get(i),scenario.name()+" publication "+i);
            for(var module:driven.modules.snapshots())assertEquals(900,module.committedTick(),scenario.name());
            driven.assertConserved();
            assertTrue(driven.advances<polled.advances,scenario.name()+": module scans "+driven.advances+" driven vs "+polled.advances+" polled");
            System.out.printf(Locale.ROOT,"%s: %d publications, module scans %d polled, %d deadline-driven%n",scenario.name(),polled.trajectory.size(),polled.advances,driven.advances);
        }
    }
    /** The same across a buffer removal: stranding, the fences it resolves and the islands it releases. */
    @Test void deadlineDrivenAdvanceMatchesThePolledHostAcrossABufferRemoval() {
        for(int stop:new int[]{200,300}) {
            var polled=new Harness(graphs(true,false,false),List.of(module(100,1,0,2,3,300,true)),true);
            var driven=new Harness(graphs(true,false,false),List.of(module(100,1,0,2,3,300,true)),false);
            for(var harness:List.of(polled,driven)){harness.run(stop);harness.remove(2);harness.run(600);}
            assertEquals(polled.trajectory,driven.trajectory,"removal at "+stop);
            assertTrue(driven.modules.waitingReason(1).startsWith("STRANDED"));
            for(var island:driven.islands.snapshots()){assertEquals(stop+600,island.clock().committedTick());assertTrue(island.fences().isEmpty());}
        }
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
        assertTrue(harness.islands.snapshot(2).graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]>0);harness.assertConserved();
    }
}
