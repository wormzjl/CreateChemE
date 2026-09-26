package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** Classification probe (D11): a humid nitrogen tank drained to a 1 atm void through a BULK end vs a LIQUID port. */
public class ColdVentProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int w=model.components().indexOf("Water"),n2=model.components().indexOf("Nitrogen");
        for(double p0:new double[]{200000,120000})for(var port:new PassiveNetwork.PhasePort[]{PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.LIQUID})for(double interval:new double[]{5,.1}){
            double t=298.15;double[] n=new double[mw.length];n[w]=.1*997/mw[w];n[n2]=p0*.9/(FluidThermodynamics.R*t);
            var unit=model.flashTP(t,p0,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,p0,n,NOOP);
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,t,101325,NOOP),PassiveNetwork.NodeKind.VOID));
            var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(30,0,1,List.of(new PipeResistance.Geometry(10,.025,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,null,port,PassiveNetwork.PhasePort.BULK)));
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;int count=(int)Math.round(200/interval);double tmin=1e9;
            String outcome="OK";int slice=0;
            try{for(slice=0;slice<count;slice++){solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;graph=r.graph();tmin=Math.min(tmin,graph.reservoirs().get(0).state().temperature());}}
            catch(Exception e){outcome="FAILED at slice "+slice+" ("+slice*interval+" s): "+e.getMessage().substring(0,Math.min(160,e.getMessage().length()));}
            var s=graph.reservoirs().get(0).state();
            System.out.println("COLD_VENT p0="+p0+" port="+port+" interval="+interval+" "+outcome+" Tmin="+tmin+" T="+s.temperature()+" P="+s.pressure()+" water="+(s.waterVolume()));
        }
    }
}
