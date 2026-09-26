package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** Classification probe (D11): a 1 m3 nitrogen tank at p0 with a trace of water vapour (no free water), vented through a
 * BULK end (10 m, 25 mm) to a 1 atm void: does the adiabatic expansion take the humid gas below the water domain? */
public class HumidVentProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int w=model.components().indexOf("Water"),n2=model.components().indexOf("Nitrogen");
        for(double p0:new double[]{200000,180000,130000})for(double interval:new double[]{5,.1}){
            double t=298.15;double[] n=new double[mw.length];n[n2]=p0/(FluidThermodynamics.R*t);n[w]=n[n2]*1e-3;
            var unit=model.flashTP(t,p0,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,p0,n,NOOP);
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,t,101325,NOOP),PassiveNetwork.NodeKind.VOID));
            var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(30,0,1,new PipeResistance.Geometry(10,.025,PipeResistance.DEFAULT_ROUGHNESS_METRES,0))));
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;int count=(int)Math.round(100/interval);double tmin=1e9;
            String outcome="OK";int slice=0;
            try{for(slice=0;slice<count;slice++){solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;graph=r.graph();tmin=Math.min(tmin,graph.reservoirs().get(0).state().temperature());}}
            catch(Exception e){outcome="FAILED at slice "+slice+" ("+slice*interval+" s): "+e.getMessage().substring(0,Math.min(200,e.getMessage().length()));}
            var s=graph.reservoirs().get(0).state();
            System.out.println("HUMID_VENT p0="+p0+" interval="+interval+" "+outcome+" Tmin="+tmin+" P="+s.pressure()+" waterLiquid="+s.waterLiquid()+" waterVapor="+s.waterVapor());
        }
    }
}
