package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** Traces the passes of chosen slices of the D11 drain fixture (0.1 m3 water under N2 at 120 kPa, LIQUID port 10 m 25 mm to a 1 atm void). */
public class DrainTraceProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        double interval=Double.parseDouble(a[0]);int from=Integer.parseInt(a[1]),to=Integer.parseInt(a[2]);
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int w=model.components().indexOf("Water"),n2=model.components().indexOf("Nitrogen");
        double t=298.15,p0=120000;double[] n=new double[mw.length];n[w]=.1*997/mw[w];n[n2]=p0*.9/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,p0,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,p0,n,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,t,101325,NOOP),PassiveNetwork.NodeKind.VOID));
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(30,0,1,List.of(new PipeResistance.Geometry(10,.025,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK)));
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int slice=0;slice<=to;slice++){PassiveStepSolver.D11TRACE=slice>=from;
            if(slice>=from)System.out.println("=== slice "+slice);
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;graph=r.graph();
            if(slice>=from)System.out.println("=== end slice "+slice+" P="+graph.reservoirs().get(0).state().pressure()+" flows="+Arrays.toString(r.averageMassFlows())+" reasons="+r.rejectionReasons());}
    }
}
