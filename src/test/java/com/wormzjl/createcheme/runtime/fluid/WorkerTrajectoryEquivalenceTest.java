package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.WorkerAllocation.Demand;
import com.wormzjl.createcheme.science.fluid.network.ApproximationAnchor;
import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.PipeResistance;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** P12: worker scheduling may change wall completion order, never the physical trajectory. */
class WorkerTrajectoryEquivalenceTest {
    private static final int ISLANDS=12;
    private static final int FINAL_TICK=60;
    private static final long WAIT_SECONDS=45;

    private enum Mode {
        FIXED_ONE(1,false), FIXED_TWO(2,false), AUTOMATIC_TWELVE(12,true);
        final int capacity;
        final boolean automatic;
        Mode(int capacity,boolean automatic){this.capacity=capacity;this.automatic=automatic;}
    }
    private record NodeFrame(long id,double elevation,double[] moles,double internalEnergy,
                             double pressure,double temperature,double mass,double vaporVolume,
                             double liquidVolume,double waterVolume) {
        private NodeFrame {
            moles=moles.clone();
        }
        @Override public double[] moles(){return moles.clone();}
    }
    private record Frame(long committedTick,List<NodeFrame> nodes,double[] averageMassFlows,
                         int acceptedSubsteps,int rejectedSubsteps) {
        private Frame {
            nodes=List.copyOf(nodes);averageMassFlows=averageMassFlows.clone();
        }
        @Override public double[] averageMassFlows(){return averageMassFlows.clone();}
    }
    private record Run(Map<Long,List<Frame>> histories,Map<Long,IslandClock.Snapshot> clocks,
                       int maximumWorkerLimit,int maximumOutstanding) {
        private Run {
            histories=Map.copyOf(histories);clocks=Map.copyOf(clocks);
        }
    }

    @Test void realBoundedExecutorPreservesClosedPhysicalTrajectoriesAtOneTwoAndAutomaticTwelveWorkers() throws Exception {
        var runs=new EnumMap<Mode,Run>(Mode.class);
        for(var mode:Mode.values())try(var harness=new Harness(mode)){runs.put(mode,harness.run());}

        var reference=runs.get(Mode.FIXED_ONE);
        assertEquals(1,reference.maximumWorkerLimit());assertEquals(1,reference.maximumOutstanding());
        assertEquals(2,runs.get(Mode.FIXED_TWO).maximumWorkerLimit());
        assertEquals(2,runs.get(Mode.FIXED_TWO).maximumOutstanding());
        assertEquals(12,runs.get(Mode.AUTOMATIC_TWELVE).maximumWorkerLimit(),
                "independent demand must grow the configurable automatic limit to twelve");
        assertEquals(12,runs.get(Mode.AUTOMATIC_TWELVE).maximumOutstanding(),
                "all twelve owners must be outstanding before the owner thread drains completions");
        for(var mode:List.of(Mode.FIXED_TWO,Mode.AUTOMATIC_TWELVE))assertSameTrajectory(reference,runs.get(mode),mode);
        // A bitwise fingerprint of each mode's trajectory, so a scheduler change can be compared against a
        // previous build's report as well as across worker counts within this run.
        var digests=new java.util.TreeMap<String,String>();for(var entry:runs.entrySet())digests.put(entry.getKey().name(),digest(entry.getValue()));
        var output=java.nio.file.Path.of("build/reports/fluid/P12-worker-trajectories.json");java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(digests));
    }

    private static String digest(Run run) throws java.security.NoSuchAlgorithmException {
        var sha=java.security.MessageDigest.getInstance("SHA-256");var buffer=java.nio.ByteBuffer.allocate(8);
        java.util.function.LongConsumer put=value->{buffer.clear();buffer.putLong(value);sha.update(buffer.array());};
        for(long island:new java.util.TreeSet<>(run.histories().keySet())) {
            put.accept(island);var clock=run.clocks().get(island);put.accept(clock.onlineTick());put.accept(clock.committedTick());put.accept(clock.retryAtTick());put.accept(clock.cadenceTicks());
            for(var frame:run.histories().get(island)) {
                put.accept(frame.committedTick());put.accept(frame.acceptedSubsteps());put.accept(frame.rejectedSubsteps());
                for(double flow:frame.averageMassFlows())put.accept(Double.doubleToLongBits(flow));
                for(var node:frame.nodes()) {
                    put.accept(node.id());for(double n:node.moles())put.accept(Double.doubleToLongBits(n));
                    for(double v:new double[]{node.elevation(),node.internalEnergy(),node.pressure(),node.temperature(),node.mass(),node.vaporVolume(),node.liquidVolume(),node.waterVolume()})put.accept(Double.doubleToLongBits(v));
                }
            }
        }
        return java.util.HexFormat.of().formatHex(sha.digest());
    }

    private static final class Harness implements AutoCloseable {
        private final Mode mode;
        private final FluidThermodynamics model=FluidTestSupport.networkModel();
        private final double[] molecularWeights=FluidTestSupport.molecularWeights(model);
        private final Semaphore completionSignal=new Semaphore(0);
        private final BoundedCpuSolveService<Long,ProcessSolveServices.FluidIslandCommand,ProcessSolveServices.FluidIslandSolveResult> service;
        private final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        private final Map<Long,BoundedCpuSolveService.JobStamp<Long>> stamps=new LinkedHashMap<>();
        private final Map<Long,List<Frame>> histories=new LinkedHashMap<>();
        private final List<PassiveNetwork> initialGraphs=new ArrayList<>();
        private final Demand allocator;
        private final IslandCoordinator coordinator;
        private long onlineTick,sequence;
        private int maximumWorkerLimit,maximumOutstanding;
        private boolean closed;

        private Harness(Mode mode) {
            this.mode=mode;
            var config=new BoundedCpuSolveService.Config(mode.capacity,mode.capacity,"p12-worker-trajectory-",true,
                    Duration.ofSeconds(5),Duration.ofSeconds(5));
            service=new BoundedCpuSolveService<>(1200L+mode.ordinal(),config,completionSignal::release);
            allocator=mode.automatic?new Demand(mode.capacity):null;
            if(mode.automatic)service.setWorkerLimit(1);
            var dispatcher=new IslandCoordinator.Dispatcher() {
                @Override public void demand(int eligibleOwners) {
                    if(allocator!=null) {
                        int demand=Math.addExact(eligibleOwners,service.diagnostics().outstandingJobs());
                        service.setWorkerLimit(allocator.observe(demand,onlineTick));
                    }
                    observeCapacity();
                }
                @Override public int availableWorkers() {
                    var diagnostics=service.diagnostics();observeCapacity();
                    return diagnostics.readyJobs()>0?0:Math.max(0,diagnostics.workerCount()-diagnostics.activeWorkers());
                }
                @Override public long nextRequestId(){return ++sequence;}
                @Override public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command) {
                    long request=attempt.slice().requestId();
                    var stamp=new BoundedCpuSolveService.JobStamp<Long>(service.serverEpoch(),request,attempt.islandId(),
                            attempt.revision(),ApproximationAnchor.revision(model),BoundedCpuSolveService.NO_DEADLINE);
                    attempts.put(request,attempt);stamps.put(request,stamp);
                    var admission=service.trySubmit(stamp,command,(snapshot,token)->
                            (ProcessSolveServices.FluidIslandSolveResult)snapshot.solve(token));
                    if(admission!=BoundedCpuSolveService.Admission.ACCEPTED){attempts.remove(request);stamps.remove(request);return false;}
                    observeCapacity();return true;
                }
                @Override public void cancel(long requestId) {
                    var stamp=stamps.get(requestId);
                    if(stamp!=null)service.cancel(stamp,BoundedCpuSolveService.CancelReason.STALE_REVISION);
                }
            };
            coordinator=new IslandCoordinator(dispatcher,this::published,System::nanoTime,
                    new IslandCoordinator.Settings(Duration.ofSeconds(30).toNanos(),Duration.ofSeconds(20).toNanos(),64,false,20));
            for(int index=0;index<ISLANDS;index++) {
                long islandId=index+1L;var graph=graph(index);initialGraphs.add(graph);histories.put(islandId,new ArrayList<>());
                coordinator.register(new IslandCoordinator.Snapshot(islandId,0,graph,new IslandClock.Snapshot(0,0,0,20),
                        FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
            }
        }

        private Run run() throws Exception {
            var initial=FluidTestSupport.finiteLedger(initialGraphs,molecularWeights);
            for(int tick=1;tick<=FINAL_TICK;tick++) {
                onlineTick=tick;coordinator.tick();drain();
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while(coordinator.snapshots().stream().anyMatch(snapshot->snapshot.clock().committedTick()<FINAL_TICK)) {
                drain();
                if(coordinator.snapshots().stream().allMatch(snapshot->snapshot.clock().committedTick()==FINAL_TICK))break;
                long remaining=deadline-System.nanoTime();
                assertTrue(remaining>0,"Timed out waiting for "+mode+" physical trajectory");
                assertTrue(completionSignal.tryAcquire(remaining,TimeUnit.NANOSECONDS),
                        "No worker completion before the "+mode+" trajectory deadline");
            }
            drain();
            assertEquals(0,coordinator.pendingCount());assertTrue(attempts.isEmpty());assertTrue(stamps.isEmpty());
            var snapshots=coordinator.snapshots();
            var after=FluidTestSupport.finiteLedger(snapshots.stream().map(IslandCoordinator.Snapshot::graph).toList(),molecularWeights);
            FluidTestSupport.assertClosed(initial,after);
            var clocks=new LinkedHashMap<Long,IslandClock.Snapshot>();
            for(var snapshot:snapshots) {
                clocks.put(snapshot.id(),snapshot.clock());
                assertEquals(new IslandClock.Snapshot(FINAL_TICK,FINAL_TICK,0,20),snapshot.clock());
                assertEquals(List.of(20L,40L,60L),histories.get(snapshot.id()).stream().map(Frame::committedTick).toList());
            }
            var immutableHistories=new LinkedHashMap<Long,List<Frame>>();histories.forEach((id,frames)->immutableHistories.put(id,List.copyOf(frames)));
            return new Run(immutableHistories,clocks,maximumWorkerLimit,maximumOutstanding);
        }

        private void drain() {
            var completed=service.drainCompletions(64);
            for(var completion:completed) {
                long request=completion.stamp().sequence();
                var attempt=attempts.remove(request);stamps.remove(request);
                assertNotNull(attempt,"completion lost its coordinator attempt");
                assertEquals(BoundedCpuSolveService.TerminalStatus.SUCCESS,completion.status(),completion.detail());
                coordinator.completed(attempt,completion.result());
            }
            coordinator.pump();observeCapacity();
        }

        private void published(List<IslandCoordinator.Snapshot> changed) {
            for(var snapshot:changed) {
                assertEquals("FULL",snapshot.status());
                var result=snapshot.lastResult().orElseThrow();
                var nodes=new ArrayList<NodeFrame>();
                for(var node:snapshot.graph().reservoirs()) {
                    var state=node.state();var inventory=node.inventory();
                    nodes.add(new NodeFrame(node.id(),node.elevation(),inventory.moles(),inventory.internalEnergy(),
                            state.pressure(),state.temperature(),state.mass(),state.vaporVolume(),state.liquidVolume(),state.waterVolume()));
                }
                histories.get(snapshot.id()).add(new Frame(snapshot.clock().committedTick(),nodes,result.averageMassFlows(),
                        result.acceptedSubsteps(),result.rejectedSubsteps()));
            }
        }

        private PassiveNetwork graph(int index) {
            var fluid=index==0?FluidTestSupport.Mixture.WET_CRUDE:(index&1)!=0
                    ?FluidTestSupport.Mixture.WATER:FluidTestSupport.Mixture.NITROGEN;
            double base=150_000+37*index;long node=100L*index+1;
            double pressureDifference=fluid==FluidTestSupport.Mixture.NITROGEN?100:1;
            var first=new PassiveNetwork.Reservoir(node,0,
                    FluidTestSupport.oneCubicMetreState(model,fluid,350,base+pressureDifference));
            var second=new PassiveNetwork.Reservoir(node+1,0,
                    FluidTestSupport.oneCubicMetreState(model,fluid,350,base));
            double diameter=fluid==FluidTestSupport.Mixture.NITROGEN?.012:.008;
            var pipe=new PassiveNetwork.Pipe(10_000L+index,0,1,
                    new PipeResistance.Geometry(100+5*index,diameter,.000045,index%2));
            var graph=new PassiveNetwork(List.of(first,second),List.of(pipe));
            if(fluid==FluidTestSupport.Mixture.WET_CRUDE) {
                assertTrue(Arrays.stream(first.inventory().moles()).allMatch(amount->amount>0),"wet-crude fixture must exercise all 22 gameplay components");
                assertTrue(first.state().vaporVolume()>0&&first.state().liquidVolume()+first.state().waterVolume()>0,
                        "wet-crude fixture must remain multiphase");
            }
            return graph;
        }

        private void observeCapacity() {
            var diagnostics=service.diagnostics();
            maximumWorkerLimit=Math.max(maximumWorkerLimit,diagnostics.workerCount());
            maximumOutstanding=Math.max(maximumOutstanding,diagnostics.outstandingJobs());
        }

        @Override public void close() {
            if(closed)return;closed=true;coordinator.stop();
            var report=service.shutdown(Duration.ofSeconds(5),Duration.ofSeconds(5));
            assertTrue(report.terminated(),"P12 executor did not terminate: "+report);
        }
    }

    private static void assertSameTrajectory(Run expected,Run actual,Mode mode) {
        assertEquals(expected.clocks(),actual.clocks(),mode+" simulation clocks");
        assertEquals(expected.histories().keySet(),actual.histories().keySet());
        for(long island:expected.histories().keySet()) {
            var first=expected.histories().get(island);var second=actual.histories().get(island);
            assertEquals(first.size(),second.size(),mode+" island "+island+" publication count");
            for(int interval=0;interval<first.size();interval++)assertSameFrame(first.get(interval),second.get(interval),mode,island);
        }
    }

    private static void assertSameFrame(Frame expected,Frame actual,Mode mode,long island) {
        String label=mode+" island "+island+" tick "+expected.committedTick();
        assertEquals(expected.committedTick(),actual.committedTick(),label);
        assertEquals(expected.acceptedSubsteps(),actual.acceptedSubsteps(),label);
        assertEquals(expected.rejectedSubsteps(),actual.rejectedSubsteps(),label);
        assertExact(expected.averageMassFlows(),actual.averageMassFlows(),label+" average flows");
        assertEquals(expected.nodes().size(),actual.nodes().size(),label);
        for(int index=0;index<expected.nodes().size();index++) {
            var a=expected.nodes().get(index);var b=actual.nodes().get(index);
            assertEquals(a.id(),b.id(),label);assertExact(a.elevation(),b.elevation(),label+" elevation");
            assertExact(a.moles(),b.moles(),label+" node "+a.id()+" moles");
            assertExact(a.internalEnergy(),b.internalEnergy(),label+" internal energy");
            assertExact(a.pressure(),b.pressure(),label+" pressure");assertExact(a.temperature(),b.temperature(),label+" temperature");
            assertExact(a.mass(),b.mass(),label+" mass");assertExact(a.vaporVolume(),b.vaporVolume(),label+" vapor volume");
            assertExact(a.liquidVolume(),b.liquidVolume(),label+" liquid volume");assertExact(a.waterVolume(),b.waterVolume(),label+" water volume");
        }
    }

    private static void assertExact(double[] expected,double[] actual,String label) {
        assertEquals(expected.length,actual.length,label);
        for(int index=0;index<expected.length;index++)assertExact(expected[index],actual[index],label+"["+index+"]");
    }
    private static void assertExact(double expected,double actual,String label) {
        assertEquals(Double.doubleToLongBits(expected),Double.doubleToLongBits(actual),label);
    }
}
