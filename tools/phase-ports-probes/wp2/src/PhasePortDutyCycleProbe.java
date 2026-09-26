package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * WP2 scratch probe (risk R1, plan section 8): a LIQUID port that can drain far faster than its vessel is fed. A 1 m3
 * nitrogen tank at 150 kPa holding 2 % water, fed water from a 200 kPa generator through a thin line (10 m, 4 mm, BULK
 * end) and drained through a LIQUID port (10 m, 25 mm) to a void at 1 atm. Under the stateless rule the port opens
 * whenever a step starts with at least 1 % water and closes at the first step start below it, so at steady state it
 * duty-cycles around phi_open. Prints per-slice status and the number of open/closed transitions, at 0.1 s slices (one
 * step each: slice level = step level) and at 5 s slices, and with the step solver at fixed 1 s steps.
 * Not a test: copy into src/test/java/com/wormzjl/createcheme/science/fluid/network/ and run with the harness
 * (select --select-class=...PhasePortDutyCycleProbe).
 */
class PhasePortDutyCycleProbe {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=model.components().indexOf("Water"),nitrogen=model.components().indexOf("Nitrogen");
    private static PipeResistance.Geometry line(double l,double d){return new PipeResistance.Geometry(l,d,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private FluidThermodynamics.State tank(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    private PassiveNetwork graph() {
        var supply=new double[mw.length];supply[water]=1;
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank(.02,150000)),new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,200000,supply,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,101325,NOOP),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(90,1,0,line(10,.004)),
                new PassiveNetwork.Pipe(91,0,2,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK)));
    }
    private static double phi(FluidThermodynamics.State s){return PassiveStepSolver.portPhaseShare(s,PhasePort.LIQUID);}
    @Test void slices() {
        for(double interval:new double[]{.1,5}) {
            int count=(int)Math.round(300/interval);var graph=graph();var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
            int transitions=0,rejected=0,openSlices=0;Boolean was=null;double minPhi=1,maxPhi=0,drained=0,fed=0;var reasons=new TreeMap<String,Integer>();
            for(int slice=0;slice<count;slice++) {
                var start=graph.reservoirs().getFirst().state();boolean open=phi(start)>=PassiveStepSolver.PHASE_PORT_OPEN;
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
                rejected+=result.rejectedSubsteps();result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
                if(was!=null&&was!=open)transitions++;was=open;if(open)openSlices++;
                if(slice*interval>=100){minPhi=Math.min(minPhi,phi(graph.reservoirs().getFirst().state()));maxPhi=Math.max(maxPhi,phi(graph.reservoirs().getFirst().state()));}
                double[] q=result.averageMassFlows();fed+=interval*q[0];drained+=interval*q[1];
                if(slice<40||slice%(int)Math.round(20/interval)==0)System.out.println("DUTY "+interval+" "+slice+" open="+open+" phi="+phi(graph.reservoirs().getFirst().state())+" P="+graph.reservoirs().getFirst().state().pressure()+" q="+Arrays.toString(q)+" rejected="+result.rejectedSubsteps()+" modes="+result.endpointModes());
            }
            System.out.println("DUTY_SUMMARY interval="+interval+" slices="+count+" transitions="+transitions+" openSlices="+openSlices+" rejected="+rejected+" reasons="+reasons
                    +" phiRangeAfter100s=["+minPhi+","+maxPhi+"] fed="+fed+" drained="+drained);
        }
    }
    @Test void fixedSteps() {
        var graph=graph();var solver=new PassiveStepSolver(model);int transitions=0;Boolean was=null;int open=0;
        for(int step=0;step<300;step++) {
            var start=graph.reservoirs().getFirst().state();boolean o=phi(start)>=PassiveStepSolver.PHASE_PORT_OPEN;if(was!=null&&was!=o)transitions++;was=o;if(o)open++;
            var result=solver.solve(graph,1,NOOP);
            var nodes=new ArrayList<>(graph.reservoirs());nodes.set(0,new PassiveNetwork.Reservoir(1,0,result.states().getFirst(),PassiveNetwork.NodeKind.RESERVOIR,result.inventories().getFirst()));
            graph=new PassiveNetwork(nodes,graph.pipes());
            if(step<30||step%20==0)System.out.println("DUTYSTEP "+step+" open="+o+" phi="+phi(result.states().getFirst())+" q="+Arrays.toString(result.massFlows())+" modes="+result.modes());
        }
        System.out.println("DUTYSTEP_SUMMARY steps=300 dt=1 transitions="+transitions+" openSteps="+open);
    }
}
