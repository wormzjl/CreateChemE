package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The twelve finite-tank mixed-gas junction transients (documentation/2026-09-24-mixed-gas-junction): two 1 m3 tanks
 * (methane at 150 kPa; nitrogen at 150 kPa, or 90 kPa and 400 K) feed a compiled junction with 2 to 4 void outlets, 50 or
 * 20 mm bore. 10 s through the approach to rest, 3 s of 0.5 mol/s methane injected into the first tank (a restart), 3 s of
 * settling. Every case must converge at the probe's 0.1 s intervals and at the product's 5 s cycle, and every committed
 * interval must close the ledger (tanks plus the junction's owned holdup plus the boundary transfers) at roundoff. Each
 * interval is a job as the island runtime runs it: {@link PassiveIntervalSolver#replayStart} from the committed interval,
 * then the settings' initial step. Formerly JunctionHoldupTransientProbe (tools/junction-holdup-prototype); the TR-BDF2
 * chain failed the 50 mm six-port restart.
 */
class MixedGasJunctionTransientTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    /** Newton solves per case at the 5 s cycle in HANDOFF_REVIEW.md 8.9 run 115 (281 in all), keyed bore:ports:unequal. */
    private static final Map<String,Integer> RUN_115_NEWTON_SOLVES=Map.ofEntries(
            Map.entry("0.05:4:false",26),Map.entry("0.05:4:true",27),Map.entry("0.05:5:false",30),Map.entry("0.05:5:true",29),
            Map.entry("0.05:6:false",32),Map.entry("0.05:6:true",31),Map.entry("0.02:4:false",16),Map.entry("0.02:4:true",16),
            Map.entry("0.02:5:false",18),Map.entry("0.02:5:true",17),Map.entry("0.02:6:false",20),Map.entry("0.02:6:true",19));
    /** The ledger closes by construction (exact reconstruction); these bounds are roundoff, relative to max(1, initial)
     * (measured at most 5.4e-15 over both cadences). */
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-12;
    private record Outcome(String key,double worstMoles,double worstEnergy,long newtonSolves,long ms,long bytes) {}

    @Test void everyCaseConvergesAndClosesItsLedgerAtTenthSecondIntervals() {
        var failures=new ArrayList<String>();
        for(var outcome:runAll(.1,false,failures))System.out.println("MIXED_GAS_TRANSIENT interval=0.1 "+outcome);
        assertTrue(failures.isEmpty(),failures.size()+" of 12 cases failed at 0.1 s:\n"+String.join("\n",failures));
    }
    @Test void everyCaseConvergesAtTheFiveSecondCycleWithinTwiceTheMeasuredCost() {
        var failures=new ArrayList<String>();long ms=0,solves=0,bytes=0;
        for(var outcome:runAll(5,true,failures)) {
            System.out.println("MIXED_GAS_TRANSIENT interval=5 "+outcome);ms+=outcome.ms();solves+=outcome.newtonSolves();bytes+=outcome.bytes();
            int bound=2*RUN_115_NEWTON_SOLVES.get(outcome.key());
            if(outcome.newtonSolves()>=bound)failures.add(outcome.key()+": "+outcome.newtonSolves()+" Newton solves, bound "+bound);
        }
        System.out.println("MIXED_GAS_COST interval=5 cases=12 ms="+ms+" newtonSolves="+solves+" allocatedMB="+String.format(Locale.ROOT,"%.1f",bytes/1048576.0));
        assertTrue(failures.isEmpty(),failures.size()+" failures at 5 s:\n"+String.join("\n",failures));
    }

    private List<Outcome> runAll(double interval,boolean cost,List<String> failures) {
        var outcomes=new ArrayList<Outcome>();
        for(double bore:new double[]{.05,.02})for(int ports=4;ports<=6;ports++)for(boolean unequal:new boolean[]{false,true}) {
            String key=bore+":"+ports+":"+unequal;
            boolean enabled=SolverDiagnostics.ENABLED;
            if(cost){SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;}
            long bytes=threadAllocated(),start=System.nanoTime();
            try{
                var ledger=runCase(bore,ports,unequal,interval);
                long elapsed=(System.nanoTime()-start)/1000000;bytes=threadAllocated()-bytes;
                outcomes.add(new Outcome(key,ledger[0],ledger[1],cost?SolverDiagnostics.sample().value("newtonSolves"):0,elapsed,bytes));
            }catch(Exception|AssertionError failure){failures.add(key+": "+failure);}
            finally{if(cost){SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}}
        }
        return outcomes;
    }
    private static long threadAllocated() {
        return java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean sun?sun.getCurrentThreadAllocatedBytes():0;
    }
    /** Runs one case and returns its worst relative component and energy ledger errors. */
    private double[] runCase(double bore,int ports,boolean unequal,double interval){
        int[][] positions={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        var geometry=new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(new PhysicalFluidTopology.Device(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<ports;i++){
            int[] at=positions[i];var kind=i<2?Kind.RESERVOIR:Kind.VOID;
            var d=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",at[0],at[1],at[2]),kind,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());devices.add(d);
            double pressure=i==0?150000:i==1?(unequal?90000:150000):101325;
            double temperature=i==1&&unequal?400:350;
            double[] n=new double[mw.length];n[i==0?0:MaterialTestBasis.NITROGEN]=1;
            var unit=model.flashTP(temperature,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            var state=model.flashTP(temperature,pressure,n,()->{});
            boundaries.put(d.id(),new PassiveNetwork.Reservoir(d.id(),at[1],state,i<2?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.VOID));
        }
        // Sized here, as the solver would at its first solve, so the ledger's initial totals hold the owned holdup.
        var graph=PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
        double[] initial=totals(graph),external=new double[mw.length];
        double initialEnergy=energy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0;
        int tank=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==1)tank=i;
        // 0.5 mol/s methane at 350 K and 150 kPa, enthalpy plus the tank's gravitational term.
        double[] feed=new double[mw.length];feed[0]=.5;
        var feedState=model.flashTP(350,150000,feed,()->{});
        double feedEnergy=feedState.enthalpy()+.5*mw[0]*PassiveStepSolver.GRAVITY*graph.reservoirs().get(tank).elevation();
        var solver=new PassiveIntervalSolver(model);
        // The schedule (10 s rest, 3 s injection, 3 s settle) cut into intervals of the given length, a segment's last
        // interval shortened to its end (5 s: 5, 5, 3, 3).
        var durations=new ArrayList<Double>();var injecting=new ArrayList<Boolean>();
        for(double[] segment:new double[][]{{10,0},{3,1},{3,0}}){
            double length=segment[0];long whole=Math.round(length/interval);
            if(Math.abs(whole*interval-length)<=1e-9*length){for(long k=0;k<whole;k++){durations.add(interval);injecting.add(segment[1]>0);}}
            else{long full=(long)Math.floor(length/interval);for(long k=0;k<full;k++){durations.add(interval);injecting.add(segment[1]>0);}durations.add(length-full*interval);injecting.add(segment[1]>0);}
        }
        PassiveIntervalSolver.Result committed=null;
        for(int step=0;step<durations.size();step++){
            List<ScheduledTransfer> transfers=injecting.get(step)?List.of(new ScheduledTransfer.Injection(900,tank,feed,feedEnergy)):List.of();
            graph=new PassiveNetwork(graph.reservoirs(),graph.pipes(),transfers);
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,durations.get(step),PassiveIntervalSolver.Settings.defaults(),()->{});committed=result;
            graph=result.graph();
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            var sum=totals(graph);
            for(int c=0;c<sum.length;c++){
                double error=Math.abs(sum[c]-initial[c]-external[c])/Math.max(1,initial[c]);worstMoles=Math.max(worstMoles,error);
                assertTrue(error<COMPONENT_LEDGER,"interval "+step+": component balance "+c+" error "+error);
            }
            double error=Math.abs(energy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
            worstEnergy=Math.max(worstEnergy,error);assertTrue(error<ENERGY_LEDGER,"interval "+step+": energy balance "+error);
        }
        return new double[]{worstMoles,worstEnergy};
    }
    /** Tanks and the junction's owned holdup. */
    private double[] totals(PassiveNetwork graph){
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    /** The junction's owned inventory (energy field m h) is counted like a tank's. */
    private double energy(PassiveNetwork graph){
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){
            double mass=0;var n=node.inventory().moles();for(int c=0;c<n.length;c++)mass+=mw[c]*n[c];
            energy+=node.inventory().internalEnergy()+mass*PassiveStepSolver.GRAVITY*node.elevation();
        }
        return energy;
    }
}
