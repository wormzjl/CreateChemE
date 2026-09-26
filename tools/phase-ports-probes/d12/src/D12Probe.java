package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.createcheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;

/** D12 scratch probe: the cold start of the extreme-topology fixtures. Args: geometry spread [pairs]. */
public class D12Probe {
    static final double[] GENERATORS={110e3,140e3,170e3,200e3,130e3,160e3,190e3,120e3,150e3,180e3};
    static final double[] TANKS={140e3,110e3,180e3,150e3,120e3,190e3,160e3,130e3,100e3,170e3};
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    static PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,64,z),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive());
    }
    public static PassiveNetwork island(FluidThermodynamics model,String geometry,double spread,int n) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var pressures=new LinkedHashMap<Long,Double>();
        java.util.function.IntToDoubleFunction g=i->155e3+spread*(GENERATORS[i]-155e3),t=i->145e3+spread*(TANKS[i]-145e3);
        if(geometry.equals("grid")) {
            for(int x=0;x<n;x++){devices.add(device(1+x,x,0,Kind.GENERATOR));pressures.put(1L+x,g.applyAsDouble(x));}
            for(int x=0;x<n;x++)devices.add(device(11+x,x,1,Kind.PIPE));
            for(int x=0;x<n;x++)devices.add(device(21+x,x,2,Kind.PIPE));
            for(int x=0;x<n;x++){devices.add(device(31+x,x,3,Kind.RESERVOIR));pressures.put(31L+x,t.applyAsDouble(x));}
        } else {
            for(int x=0;x<2*n;x++){boolean gen=x%2==0;devices.add(device(1+x,x,0,gen?Kind.GENERATOR:Kind.RESERVOIR));pressures.put(1L+x,gen?g.applyAsDouble(x/2):t.applyAsDouble(x/2));}
            for(int x=0;x<2*n;x++)devices.add(device(21+x,x,1,Kind.PIPE));
            if(geometry.equals("both"))for(int x=0;x<2*n;x++)devices.add(device(41+x,x,-1,Kind.PIPE));
        }
        double[] nitrogen=new double[model.components().size()];nitrogen[MaterialTestBasis.NITROGEN]=1;
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices)if(pressures.containsKey(d.id()))boundaries.put(d.id(),new FluidDeviceSpec(1,350,pressures.get(d.id()),nitrogen).initialize(d,model,()->{}));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);
        return PassiveNetwork.sizeJunctionHoldups(compiled.islands().getFirst().graph(),model);
    }
    static PassiveNetwork ports(PassiveNetwork graph){
        var ports=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:graph.reservoirs())ports.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),
                node.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:node.kind(),node.inventory()));
        return new PassiveNetwork(ports,graph.pipes());
    }
    static void dump(String label,PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,List<FlowControl.Mode> modes) {
        System.out.println("== "+label);
        for(int i=0;i<graph.pipes().size();i++){var p=graph.pipes().get(i);var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());
            if(a.kind()!=PassiveNetwork.NodeKind.GENERATOR&&b.kind()!=PassiveNetwork.NodeKind.GENERATOR)continue;
            int gi=a.kind()==PassiveNetwork.NodeKind.GENERATOR?p.first():p.second(),ji=gi==p.first()?p.second():p.first();
            double out=gi==p.first()?flows[i]:-flows[i];
            System.out.printf(Locale.ROOT,"  gen %d P=%.0f  junction %d P=%.1f  out=%.5f  mode=%s%n",graph.reservoirs().get(gi).id(),states.get(gi).pressure(),graph.reservoirs().get(ji).id(),states.get(ji).pressure(),out,modes==null?"-":modes.get(i));
        }
    }
    public static void main(String[] args) {
        var model=FluidTestSupport.networkModel();
        String geometry=args.length>0?args[0]:"grid";double spread=args.length>1?Double.parseDouble(args[1]):1;int n=args.length>2?Integer.parseInt(args[2]):10;
        var graph=island(model,geometry,spread,n);
        var init=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        System.out.println("junction guesses: "+graph.reservoirs().stream().filter(PassiveNetwork.Reservoir::junction).map(r->String.format(Locale.ROOT,"%.0f",r.state().pressure())).toList());
        double close=args.length>3?Double.parseDouble(args[3]):0;
        if(close>0){var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(var p:graph.pipes()){var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());
                boolean low=a.kind()==PassiveNetwork.NodeKind.GENERATOR&&a.state().pressure()<close||b.kind()==PassiveNetwork.NodeKind.GENERATOR&&b.state().pressure()<close;
                pipes.add(low?p.withBlockedDirections(3):p);}
            graph=new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());}
        double seedP=args.length>4?Double.parseDouble(args[4]):0;
        if(seedP>0){var nodes=new ArrayList<PassiveNetwork.Reservoir>();
            for(var r:graph.reservoirs())nodes.add(r.junction()?new PassiveNetwork.Reservoir(r.id(),r.elevation(),model.flashTP(r.state().temperature(),seedP,PhaseLayout.totalAmounts(r.state()),()->{}),r.kind(),r.inventory()):r);
            graph=new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());}
        var solver=new PassiveStepSolver(model);
        try {
            var rate=solver.solveRate(ports(graph),()->{},PassiveStepSolver.Acceptance.FULL);
            dump("rate solve converged",graph,rate.states(),rate.massFlows(),rate.modes());
        } catch(RuntimeException e){System.out.println("rate solve FAILED: "+e.getMessage());}
    }
}
