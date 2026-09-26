package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;

/** Scratch: the head drain at 5 s, slice by slice. args: tankPressure voidPressure interval count drainLength drainDiameter */
public class DrainProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] args) {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int water=model.components().indexOf("Water"),nitrogen=model.components().indexOf("Nitrogen");
        double tankP=Double.parseDouble(args[0]),voidP=Double.parseDouble(args[1]),interval=Double.parseDouble(args[2]);int count=Integer.parseInt(args[3]);
        double len=Double.parseDouble(args[4]),dia=Double.parseDouble(args[5]);double wv=args.length>6?Double.parseDouble(args[6]):.1;
        double t=298.15;double[] n=new double[mw.length];n[water]=wv*997/mw[water];n[nitrogen]=tankP*(1-wv)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,tankP,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,tankP,n,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,t,101325,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,t,voidP,NOOP),PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(len,dia,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);var v=new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        PhasePort dp=args.length>7?PhasePort.valueOf(args[7]):PhasePort.LIQUID;
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(90,0,2,List.of(g),new FlowControl.Passive(),0,null,dp,PhasePort.BULK),
                new PassiveNetwork.Pipe(91,1,0,List.of(v),new FlowControl.Passive(),0,null,PhasePort.BULK,PhasePort.VAPOR)));
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int i=0;i<count;i++){
            var s0=graph.reservoirs().get(0).state();
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            PassiveIntervalSolver.Result r;
            try{r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);}catch(RuntimeException e){System.out.println("slice "+i+" FAILED "+e.getMessage());return;}
            committed=r;graph=r.graph();var s=graph.reservoirs().get(0).state();
            System.out.printf(Locale.ROOT,"slice %d share %.6f -> %.6f P %.3f head %.3f flows %s modes %s acc %d rej %d %s%n",i,PassiveStepSolver.portPhaseShare(s0,PhasePort.LIQUID),PassiveStepSolver.portPhaseShare(s,PhasePort.LIQUID),
                    s.pressure(),PassiveStepSolver.GRAVITY*model.liquidMass(s)/1,Arrays.toString(r.averageMassFlows()),r.endpointModes(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.rejectionReasons());
        }
    }
}
