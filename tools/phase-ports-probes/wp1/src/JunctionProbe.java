package com.wormzjl.createcheme.science.fluid.network;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** Scratch: classify the junction gate failure (phase ports vs all-BULK, which junction). */
public class JunctionProbe {
    static final Runnable NOOP=()->{};
    public static void main(String[] a){
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        double[] mw=model.molecularWeights();int water=model.components().indexOf("Water"),n2=model.components().indexOf("Nitrogen");
        double t=298.15,p=200000;double[] n=new double[mw.length];n[water]=.5*997/mw[water];n[n2]=p*.5/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();var tank=model.flashTP(t,p,n,NOOP);
        var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        double[] liq=Arrays.copyOf(tank.liquid(),mw.length);liq[water]=tank.waterLiquid();
        var liquidSeed=model.flashTP(298.15,150000,liq,NOOP);
        var line=new PipeResistance.Geometry(10,.01,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        for(String variant:new String[]{"vaporOnly"}) {
            PhasePort v=variant.equals("phase")||variant.equals("vaporOnly")?PhasePort.VAPOR:PhasePort.BULK;
            PhasePort l=variant.equals("phase")||variant.equals("liquidOnly")?PhasePort.LIQUID:PhasePort.BULK;
            boolean vb=!variant.equals("liquidOnly")&&!variant.equals("bulkLiquidBranch"),lb=!variant.equals("vaporOnly")&&!variant.equals("bulkVaporBranch");
            var nodes=new ArrayList<PassiveNetwork.Reservoir>(List.of(new PassiveNetwork.Reservoir(1,0,tank)));var pipes=new ArrayList<PassiveNetwork.Pipe>();
            if(vb){nodes.add(new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.JUNCTION));nodes.add(new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID));int j=nodes.size()-2;
                pipes.add(new PassiveNetwork.Pipe(20,0,j,List.of(line),new FlowControl.Passive(),0,null,v,PhasePort.BULK));pipes.add(new PassiveNetwork.Pipe(21,j,j+1,line));}
            if(lb){nodes.add(new PassiveNetwork.Reservoir(3,0,liquidSeed,PassiveNetwork.NodeKind.JUNCTION));nodes.add(new PassiveNetwork.Reservoir(5,0,sink,PassiveNetwork.NodeKind.VOID));int j=nodes.size()-2;
                pipes.add(new PassiveNetwork.Pipe(22,0,j,List.of(line),new FlowControl.Passive(),0,null,l,PhasePort.BULK));pipes.add(new PassiveNetwork.Pipe(23,j,j+1,line));}
            var graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,pipes),model);
            for(double interval:new double[]{.1,5}) {
                var g=graph;var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;String outcome="ok";int count=interval==5?2:20,acc=0,rej=0;
                try{for(int s=0;s<count;s++){solver.replayStart(g,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                    var r=solver.solve(g,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;g=r.graph();acc+=r.acceptedSubsteps();rej+=r.rejectedSubsteps();}}
                catch(RuntimeException e){outcome="FAILED "+e.getMessage();}
                System.out.println(variant+" interval="+interval+" acc="+acc+" rej="+rej+" "+(outcome.length()>200?outcome.substring(0,200):outcome));
            }
        }
    }
}
