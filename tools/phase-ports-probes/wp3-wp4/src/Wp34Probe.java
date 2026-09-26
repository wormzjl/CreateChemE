package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** WP3/WP4 scratch probe: fixture 8 fill rate; fixture 4 bitwise comparison variants. */
public class Wp34Probe {
    static final Runnable NOOP=()->{};
    static FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    static double[] mw=model.molecularWeights();static int water=model.components().indexOf("Water"),nitrogen=model.components().indexOf("Nitrogen");
    static PipeResistance.Geometry line(double l,double d){return new PipeResistance.Geometry(l,d,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl c){return new PassiveNetwork.Pipe(id,a,b,List.of(g),c,0,null,PhasePort.BULK,PhasePort.BULK);}
    static FluidThermodynamics.State wun(double v,double p){double t=298.15;double[] n=new double[mw.length];n[water]=v*997/mw[water];n[nitrogen]=p*(1-v)/(FluidThermodynamics.R*t);var u=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=u.volume();return model.flashTP(t,p,n,NOOP);}
    static FluidThermodynamics.State ws(double p){var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,p,n,NOOP);}
    static FluidThermodynamics.State n2(double p){return model.initialNitrogenCharge(1,298.15,p,NOOP);}
    static double share(FluidThermodynamics.State s){return s.vaporVolume()/(s.vaporVolume()+s.liquidVolume()+s.waterVolume());}
    static List<PassiveIntervalSolver.Result> drive(PassiveNetwork g,double dt,int count){var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result c=null;var out=new ArrayList<PassiveIntervalSolver.Result>();
        for(int i=0;i<count;i++){solver.replayStart(g,c==null?null:c.graph(),c==null?null:c.endpointModes());var r=solver.solve(g,dt,PassiveIntervalSolver.Settings.defaults(),NOOP);c=r;g=r.graph();out.add(r);}return out;}
    public static void main(String[] a) {
        if(a[0].equals("f8")) {
            for(double d:new double[]{.01,.02}) {
                var start=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,wun(.97,101325)),new PassiveNetwork.Reservoir(2,0,ws(400000),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(3,0,n2(101325),PassiveNetwork.NodeKind.VOID)),
                        List.of(pipe(10,1,0,line(10,d),new FlowControl.Passive()),pipe(11,0,2,line(2,.02),new FlowControl.Pump(.0001,500000,1))));
                System.out.println("d="+d+" initial share "+share(start.reservoirs().get(0).state()));
                for(var r:drive(start,5,12))System.out.printf(Locale.ROOT,"flow %.6f P %.1f share %.6f modes %s%n",r.averageMassFlows()[0],r.graph().reservoirs().get(0).state().pressure(),share(r.graph().reservoirs().get(0).state()),r.endpointModes());
            }
        } else {
            double dt=Double.parseDouble(a[1]);int count=(int)Math.round(Double.parseDouble(a[2]));
            var wl=List.of(new PassiveNetwork.Reservoir(1,0,ws(300000),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,n2(101325)));
            var alone=new PassiveNetwork(wl,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive())));
            var nodes=new ArrayList<>(wl);nodes.add(new PassiveNetwork.Reservoir(3,0,n2(200000)));nodes.add(new PassiveNetwork.Reservoir(4,0,n2(101325)));
            var twin=new ArrayList<>(wl);twin.add(new PassiveNetwork.Reservoir(3,0,n2(200000)));twin.add(new PassiveNetwork.Reservoir(4,0,n2(101325)));
            var refused=new PassiveNetwork(nodes,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive()),pipe(11,2,3,line(1,.05),new FlowControl.Pump(.01,500000,1))));
            var closed=new PassiveNetwork(twin,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive()),pipe(11,2,3,line(1,.05),new FlowControl.Passive()).withBlockedDirections(3)));
            var blockedPump=new PassiveNetwork(twin,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive()),pipe(11,2,3,line(1,.05),new FlowControl.Compressor(.01,1.5,1)).withBlockedDirections(3)));
            var ra=drive(alone,dt,count);var rr=drive(refused,dt,count);var rc=drive(closed,dt,count);var rb=drive(blockedPump,dt,count);
            for(int i=0;i<count;i++)System.out.printf(Locale.ROOT,"slice %d alone %s refused %s closed %s blockedCompressor %s | modes %s %s%n",i,Double.toHexString(ra.get(i).averageMassFlows()[0]),Double.toHexString(rr.get(i).averageMassFlows()[0]),Double.toHexString(rc.get(i).averageMassFlows()[0]),Double.toHexString(rb.get(i).averageMassFlows()[0]),rr.get(i).endpointModes(),rb.get(i).endpointModes());
        }
    }
}
