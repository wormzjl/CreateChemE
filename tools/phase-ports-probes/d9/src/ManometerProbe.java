package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;

/** Scratch: manometer. args: interval count port pa pb waterA waterB linkDia ventPortB(VAPOR|BULK) */
public class ManometerProbe {
    static final Runnable NOOP=()->{};
    static FluidThermodynamics model;static double[] mw;static int water,nitrogen;
    static FluidThermodynamics.State tank(double wv,double p){double t=298.15;double[] n=new double[mw.length];n[water]=wv*997/mw[water];n[nitrogen]=p*(1-wv)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();return model.flashTP(t,p,n,NOOP);}
    public static void main(String[] args) {
        model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        mw=model.molecularWeights();water=model.components().indexOf("Water");nitrogen=model.components().indexOf("Nitrogen");
        double interval=Double.parseDouble(args[0]);int count=Integer.parseInt(args[1]);PhasePort port=PhasePort.valueOf(args[2]);
        double pa=Double.parseDouble(args[3]),pb=Double.parseDouble(args[4]),wa=Double.parseDouble(args[5]),wb=Double.parseDouble(args[6]),dia=Double.parseDouble(args[7]);
        PhasePort vb=PhasePort.valueOf(args[8]);PhasePort va=args.length>9?PhasePort.valueOf(args[9]):PhasePort.VAPOR;
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank(wa,pa)),new PassiveNetwork.Reservoir(2,0,tank(wb,pb)),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,pa,NOOP),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(4,0,model.initialNitrogenCharge(1,298.15,pb,NOOP),PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(2,dia,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);var v=new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(96,0,1,List.of(g),new FlowControl.Passive(),0,null,port,port),
                new PassiveNetwork.Pipe(97,2,0,List.of(v),new FlowControl.Passive(),0,null,PhasePort.BULK,va),
                new PassiveNetwork.Pipe(98,1,3,List.of(v),new FlowControl.Passive(),0,null,vb,PhasePort.BULK)));
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int i=0;i<count;i++){
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            PassiveIntervalSolver.Result r;
            try{r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);}catch(RuntimeException e){System.out.println("slice "+i+" FAILED "+e.getMessage());return;}
            committed=r;graph=r.graph();var a=graph.reservoirs().get(0).state();var b=graph.reservoirs().get(1).state();
            double ha=PassiveStepSolver.GRAVITY*model.liquidMass(a),hb=PassiveStepSolver.GRAVITY*model.liquidMass(b);
            System.out.printf(Locale.ROOT,"slice %d PA %.4f PB %.4f bottomDiff %.6f mA %.3f mB %.3f yWA %.3e flows %s modes %s acc %d rej %d %s%n",i,a.pressure(),b.pressure(),a.pressure()+ha-b.pressure()-hb,
                    model.liquidMass(a),model.liquidMass(b),a.waterVapor()/(a.waterVapor()+a.vaporView()[nitrogen]),Arrays.toString(r.averageMassFlows()),r.endpointModes(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.rejectionReasons());
        }
    }
}
