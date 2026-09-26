import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** Scratch, base API only: a humid (unsaturated) nitrogen vessel drawn in BULK into a junction seeded dry. */
public class HumidJunctionProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int water=model.components().indexOf("Water"),n2=model.components().indexOf("Nitrogen");
        for(double humidity:new double[]{0,.005,.01}){
            double t=298.15,p=200000;double[] n=new double[mw.length];n[n2]=1-humidity;n[water]=humidity;
            var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,p,n,NOOP);
            var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
            var line=new PipeResistance.Geometry(10,.01,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID));
            var graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(20,0,1,line),new PassiveNetwork.Pipe(21,1,2,line))),model);
            for(double interval:new double[]{.1,5}){
                var g=graph;var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;String outcome="ok";int acc=0,rej=0;
                try{for(int s=0;s<(interval==5?2:20);s++){solver.replayStart(g,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                    var r=solver.solve(g,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;g=r.graph();acc+=r.acceptedSubsteps();rej+=r.rejectedSubsteps();}}
                catch(RuntimeException e){outcome="FAILED "+e.getMessage();}
                System.out.println("humidity="+humidity+" liquidWater="+tank.waterLiquid()+" interval="+interval+" acc="+acc+" rej="+rej+" "+(outcome.length()>160?outcome.substring(0,160):outcome));
            }
        }
    }
}
