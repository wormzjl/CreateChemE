package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** P31 physical cadence qualification. CPU samples are injected to exercise clock policy;
 * this test does not claim that the aggregate-load classifier measures actual CPU saturation. */
class CadenceTrajectoryQualificationTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final double[] molecularWeights=FluidTestSupport.molecularWeights(model);
    private record Frame(int tick,PassiveNetwork graph,double massTransferred) {}
    /** {@code published} holds every interval, and {@code clocks} the clock each adaptive publication carried. */
    private record Trajectory(List<Frame> frames,Set<Integer> cadences,int intervals,List<Frame> published,List<IslandClock.Snapshot> clocks) {
        Trajectory(List<Frame> frames,Set<Integer> cadences,int intervals){this(frames,cadences,intervals,frames,List.of());}
    }

    @Test void fixedAndAdaptiveCadencesPreserveTheRefinedPhysicalTrajectory() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();var digests=new TreeMap<String,String>();
        for(String fluid:List.of("nitrogen","water","wet-crude")) {
            var initial=initial(fluid);
            var coarse=reference(initial,.5);var fine=reference(initial,.25);
            compare(fluid,"reference-refinement",coarse,fine,.001,rows);
            for(int cadence:new int[]{1,5,20}){var fixed=runFixed(initial,cadence);digests.put(fluid+"/fixed-"+cadence,digest(fixed));compare(fluid,"fixed-"+cadence,fixed,fine,.005,rows);}
            var adaptive=runAdaptive(initial);digests.put(fluid+"/adaptive-controlled-cpu",digest(adaptive));
            assertTrue(adaptive.cadences().stream().anyMatch(c->c>100),"Controlled high CPU samples did not increase cadence");
            assertTrue(adaptive.cadences().size()>2,"Controlled recovery did not exercise cadence changes");
            compare(fluid,"adaptive-controlled-cpu",adaptive,fine,.005,rows);
        }
        // Ordered maps throughout, so an unchanged trajectory writes a byte-identical report.
        var output=Path.of("build/reports/fluid/P31-cadence-trajectories.json");Files.createDirectories(output.getParent());
        var report=new LinkedHashMap<String,Object>();
        report.put("reference","TR-BDF2 step-doubling with 0.5/0.25 s ceilings and 1e-5 tolerance; refinement <=0.1%");
        report.put("scope","Fixed 1/5/20 s and production clock with injected CPU observations; not aggregate-load performance qualification");
        report.put("trajectoryDigests",digests);report.put("rows",rows);
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private PassiveNetwork initial(String fluid) {
        var mixture=switch(fluid){case "nitrogen"->FluidTestSupport.Mixture.NITROGEN;case "water"->FluidTestSupport.Mixture.WATER;case "wet-crude"->FluidTestSupport.Mixture.WET_CRUDE;default->throw new IllegalArgumentException("Unknown fluid "+fluid);};
        double upper=fluid.equals("water")?500000:200000;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(double pressure:new double[]{upper,150000}) {
            var state=FluidTestSupport.oneCubicMetreState(model,mixture,350,pressure);
            if(fluid.equals("wet-crude"))assertTrue(state.vaporVolume()>0&&state.liquidVolume()+state.waterVolume()>0,"Wet-crude cadence fixture must contain multiple phases");
            nodes.add(new PassiveNetwork.Reservoir(nodes.size()+1,0,state));
        }
        var geometry=fluid.equals("water")?new PipeResistance.Geometry(10000,.006,.000045,0):new PipeResistance.Geometry(200,.01,.000045,0);
        return new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(3,0,1,geometry)));
    }

    private Trajectory reference(PassiveNetwork initial,double maximumStep) {
        var graph=initial;double moved=0;var frames=new ArrayList<Frame>();
        var settings=new PassiveIntervalSolver.Settings(maximumStep,maximumStep,1e-5,8192);
        for(int tick=400;tick<=1600;tick+=400) {
            var result=new PassiveIntervalSolver(model,PassiveIntervalSolver.ErrorControl.STEP_DOUBLING).solve(graph,20,settings,()->{});
            graph=result.graph();assertClosed(initial,graph);moved+=20*result.averageMassFlows()[0];frames.add(new Frame(tick,graph,moved));
        }
        return new Trajectory(frames,Set.of(),4);
    }
    private Trajectory runFixed(PassiveNetwork initial,int cadenceSeconds) {
        var graph=initial;double moved=0;var frames=new ArrayList<Frame>();var published=new ArrayList<Frame>();int intervals=0;
        for(int tick=20*cadenceSeconds;tick<=1600;tick+=20*cadenceSeconds) {
            var result=new PassiveIntervalSolver(model).solve(graph,cadenceSeconds,PassiveIntervalSolver.Settings.defaults(),()->{});
            graph=result.graph();assertClosed(initial,graph);moved+=cadenceSeconds*result.averageMassFlows()[0];intervals++;
            published.add(new Frame(tick,graph,moved));if(tick%400==0)frames.add(new Frame(tick,graph,moved));
        }
        return new Trajectory(frames,Set.of(20*cadenceSeconds),intervals,published,List.of());
    }
    private Trajectory runAdaptive(PassiveNetwork initial) {
        class Dispatch implements IslandCoordinator.Dispatcher {
            long sequence;IslandCoordinator.Attempt attempt;ProcessSolveServices.FluidIslandCommand command;
            public int availableWorkers(){return attempt==null?1:0;}
            public long nextRequestId(){return ++sequence;}
            public boolean submit(IslandCoordinator.Attempt a,ProcessSolveServices.FluidIslandCommand c){attempt=a;command=c;return true;}
            public void cancel(long request){fail("Controlled cadence fixture must not time out");}
        }
        var dispatch=new Dispatch();double[] moved={0};var frames=new ArrayList<Frame>();var published=new ArrayList<Frame>();var clocks=new ArrayList<IslandClock.Snapshot>();var cadences=new TreeSet<Integer>();int[] intervals={0};
        var coordinator=new IslandCoordinator(dispatch,changes->{
            for(var state:changes) {
                var result=state.lastResult().orElseThrow();assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance());assertClosed(initial,state.graph());
                moved[0]+=result.advancedSeconds()*result.averageMassFlows()[0];cadences.add(state.clock().cadenceTicks());intervals[0]++;
                published.add(new Frame((int)state.clock().committedTick(),state.graph(),moved[0]));clocks.add(state.clock());
                if(state.clock().committedTick()%400==0)frames.add(new Frame((int)state.clock().committedTick(),state.graph(),moved[0]));
            }
        // Certificates off: these rows qualify every solved interval and must stay byte-identical to the WP0 fingerprint.
        },()->0,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,true,100,CertificatePolicy.disabled()));
        coordinator.register(new IslandCoordinator.Snapshot(1,0,initial,new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        UUID boundary=UUID.randomUUID();coordinator.fence(boundary,400,List.of(1L));
        for(int tick=1;tick<=1600;tick++) {
            coordinator.tick();
            while(dispatch.attempt!=null) {
                var attempt=dispatch.attempt;var command=dispatch.command;dispatch.attempt=null;dispatch.command=null;
                var result=new PassiveIntervalSolver(model).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
                long cpu=intervals[0]<3?1_600_000_000L:10_000_000L;
                coordinator.completed(attempt,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(result),"FULL",cpu,cpu,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,result)))));
                coordinator.pump();
            }
            if(tick%400==0&&tick<1600){coordinator.releaseFence(boundary,List.of(1L));boundary=UUID.randomUUID();coordinator.fence(boundary,tick+400,List.of(1L));}
        }
        assertEquals(1600,coordinator.snapshot(1).clock().committedTick());assertEquals(0,coordinator.pendingCount());
        coordinator.stop();return new Trajectory(frames,cadences,intervals[0],published,clocks);
    }

    private void compare(String fluid,String cadence,Trajectory candidate,Trajectory reference,double relativeTolerance,List<Map<String,Object>> rows) {
        assertEquals(reference.frames().size(),candidate.frames().size());
        for(int frame=0;frame<reference.frames().size();frame++) {
            var expected=reference.frames().get(frame);var actual=candidate.frames().get(frame);assertEquals(expected.tick(),actual.tick());
            double maximumPressureError=0,maximumTemperatureError=0,maximumPhaseError=0;
            for(int node=0;node<2;node++) {
                var a=actual.graph().reservoirs().get(node);var b=expected.graph().reservoirs().get(node);
                double pressure=Math.abs(a.state().pressure()-b.state().pressure());maximumPressureError=Math.max(maximumPressureError,pressure/Math.max(100,b.state().pressure()));
                maximumTemperatureError=Math.max(maximumTemperatureError,Math.abs(a.state().temperature()-b.state().temperature()));
                double[] phaseA={a.state().vaporVolume(),a.state().liquidVolume(),a.state().waterVolume()};
                double[] phaseB={b.state().vaporVolume(),b.state().liquidVolume(),b.state().waterVolume()};
                for(int phase=0;phase<phaseA.length;phase++)maximumPhaseError=Math.max(maximumPhaseError,Math.abs(phaseA[phase]/a.state().volume()-phaseB[phase]/b.state().volume()));
                assertTrue(pressure<=1+relativeTolerance*Math.abs(b.state().pressure()),fluid+" "+cadence+" pressure");
                var n=a.inventory().moles();var target=b.inventory().moles();
                for(int c=0;c<n.length;c++)assertEquals(target[c],n[c],1e-10+relativeTolerance*Math.abs(target[c]),fluid+" "+cadence+" component "+c);
            }
            assertTrue(maximumTemperatureError<=.5,fluid+" "+cadence+" temperature");
            assertTrue(maximumPhaseError<=relativeTolerance,fluid+" "+cadence+" phase");
            double massError=Math.abs(actual.massTransferred()-expected.massTransferred());
            assertTrue(massError<=1e-8+relativeTolerance*Math.abs(expected.massTransferred()),fluid+" "+cadence+" cumulative flow: "+massError);
            var row=new LinkedHashMap<String,Object>();
            row.put("fluid",fluid);row.put("cadence",cadence);row.put("tick",actual.tick());row.put("intervals",candidate.intervals());
            row.put("cadenceTicksObserved",new TreeSet<>(candidate.cadences()));row.put("maximumPressureRelativeError",maximumPressureError);
            row.put("maximumTemperatureErrorK",maximumTemperatureError);row.put("maximumPhaseVolumeFractionError",maximumPhaseError);
            row.put("cumulativeMassAbsoluteErrorKg",massError);row.put("status","PASS");rows.add(row);
        }
    }
    /** SHA-256 over the raw bits of every published interval (tick, transferred mass, each node's inventory and
     * state) and of every clock an adaptive publication carried, which also fixes when each slice was dispatched. */
    private static String digest(Trajectory trajectory) {
        try {
            var sha=java.security.MessageDigest.getInstance("SHA-256");var buffer=java.nio.ByteBuffer.allocate(8);
            java.util.function.LongConsumer put=value->{buffer.clear();buffer.putLong(value);sha.update(buffer.array());};
            for(var clock:trajectory.clocks()){put.accept(clock.onlineTick());put.accept(clock.committedTick());put.accept(clock.retryAtTick());put.accept(clock.cadenceTicks());}
            for(var frame:trajectory.published()) {
                put.accept(frame.tick());put.accept(Double.doubleToLongBits(frame.massTransferred()));
                for(var node:frame.graph().reservoirs()) {
                    put.accept(node.id());for(double n:node.inventory().moles())put.accept(Double.doubleToLongBits(n));
                    put.accept(Double.doubleToLongBits(node.inventory().internalEnergy()));var s=node.state();
                    for(double v:new double[]{s.pressure(),s.temperature(),s.mass(),s.vaporVolume(),s.liquidVolume(),s.waterVolume()})put.accept(Double.doubleToLongBits(v));
                }
            }
            put.accept(trajectory.intervals());for(int cadence:new TreeSet<>(trajectory.cadences()))put.accept(cadence);
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private void assertClosed(PassiveNetwork initial,PassiveNetwork actual) {
        var before=FluidTestSupport.finiteLedger(initial,molecularWeights);
        var after=FluidTestSupport.finiteLedger(actual,molecularWeights);
        var beforeComponents=before.components();var afterComponents=after.components();
        for(int component=0;component<beforeComponents.length;component++) {
            assertEquals(beforeComponents[component],afterComponents[component],
                    1e-10+1e-8*Math.abs(beforeComponents[component]));
        }
        assertEquals(before.energy(),after.energy(),1e-4+1e-6*Math.abs(before.energy()));
    }
}
