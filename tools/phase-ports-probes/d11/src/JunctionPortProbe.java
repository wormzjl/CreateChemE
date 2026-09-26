package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
public class JunctionPortProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        var P=PassiveNetwork.PhasePort.values();
        for(var pa:new PassiveNetwork.PhasePort[]{PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.VAPOR})for(var pb:new PassiveNetwork.PhasePort[]{PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.VAPOR})for(double interval:new double[]{5,.1}){
            var methane=charge(model,pure(model,"Methane"),350,150000,1);var nitrogen=charge(model,pure(model,"Nitrogen"),400,90000,1);
            var sink=model.initialNitrogenCharge(1,350,101325,NOOP);
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,methane),new PassiveNetwork.Reservoir(2,0,nitrogen),new PassiveNetwork.Reservoir(3,0,methane,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID));
            var g=new PipeResistance.Geometry(1,.02,.000045,0);
            var graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(port(10,0,2,g,pa,PassiveNetwork.PhasePort.BULK),port(11,1,2,g,pb,PassiveNetwork.PhasePort.BULK),port(12,2,3,g,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK))),model);
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
            try{for(int i=0;i<(interval==5?3:20);i++){graph=new PassiveNetwork(graph.reservoirs(),graph.pipes());solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;graph=r.graph();}
                System.out.println(pa+" "+pb+" "+interval+" OK flows="+Arrays.toString(committed.averageMassFlows())+" rej="+committed.rejectedSubsteps()+" "+committed.rejectionReasons());}
            catch(Exception e){System.out.println(pa+" "+pb+" "+interval+" FAILED "+e.getMessage());}
        }
    }
    static FluidThermodynamics.State charge(FluidThermodynamics model,double[] n,double t,double p,double volume){n=n.clone();var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]*=volume/unit.volume();return model.flashTP(t,p,n,NOOP);}
    static double[] pure(FluidThermodynamics model,String name){var n=new double[model.componentCount()];n[model.components().indexOf(name)]=1;return n;}
    static PassiveNetwork.Pipe port(long id,int a,int b,PipeResistance.Geometry g,PassiveNetwork.PhasePort pa,PassiveNetwork.PhasePort pb){return new PassiveNetwork.Pipe(id,a,b,List.of(g),new FlowControl.Passive(),0,null,pa,pb);}
}
