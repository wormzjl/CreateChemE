package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** Traces the D11 overflow fixture (0.8 m3 water under N2 at 1 atm, filled from a 300 kPa water generator through the
 * LIQUID port or a BULK end, vented through a VAPOR port to a 1 atm void), per slice, with pass traces from slice {@code from}. */
public class OverflowTraceProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        double interval=Double.parseDouble(a[0]);int from=Integer.parseInt(a[1]),to=Integer.parseInt(a[2]);var fill=PassiveNetwork.PhasePort.valueOf(a.length>3?a[3]:"LIQUID");
        var vent=PassiveNetwork.PhasePort.valueOf(a.length>4?a[4]:"VAPOR");
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int w=model.components().indexOf("Water"),n2=model.components().indexOf("Nitrogen");
        double t=298.15,p0=101325;double[] n=new double[mw.length];n[w]=.8*997/mw[w];n[n2]=p0*.2/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,p0,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,p0,n,NOOP);
        var supply=new double[mw.length];supply[w]=1;
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,model.flashTP(t,300000,supply,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,t,101325,NOOP),PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(10,.025,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(31,1,0,List.of(g),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.BULK,fill),
                new PassiveNetwork.Pipe(32,0,2,List.of(g),new FlowControl.Passive(),0,null,vent,PassiveNetwork.PhasePort.BULK)));
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int slice=0;slice<=to;slice++){PassiveStepSolver.D11TRACE=slice>=from;
            if(slice>=from)System.out.println("=== slice "+slice);
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            try{var r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;graph=r.graph();}
            catch(RuntimeException e){System.out.println("FAILED slice "+slice+": "+e.getMessage());return;}
            var s=graph.reservoirs().get(0).state();
            System.out.println("SLICE "+slice+" P="+s.pressure()+" gas="+s.vaporVolume()+" water="+s.waterVolume()+" T="+s.temperature()+" flows="+Arrays.toString(committed.averageMassFlows())+" acc="+committed.acceptedSubsteps()+" rej="+committed.rejectedSubsteps()+" "+committed.rejectionReasons());}
    }
}
