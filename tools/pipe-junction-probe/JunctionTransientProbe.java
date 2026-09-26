package com.wormzjl.createcheme.runtime.fluid;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunctionTransientProbe {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private int completed;
    private double bore;
    private final double[] mw=model.molecularWeights();
    @Test void finiteTanksChangePressureCompositionAndMayReverse(){
        for(double diameter:new double[]{.05,.02})for(int ports=4;ports<=6;ports++)for(boolean unequal:new boolean[]{false,true}) {
            bore=diameter;
            completed=0;
            try{runCase(ports,unequal);System.out.println("DYNAMIC bore="+bore+" ports="+ports+" unequal="+unequal+" PASS steps="+completed);}
            catch(Exception|AssertionError failure){System.out.println("DYNAMIC bore="+bore+" ports="+ports+" unequal="+unequal+" FAIL step="+completed+" "+failure);}
        }
    }
    private void runCase(int ports,boolean unequal){
        int[][] positions={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        var geometry=new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(new PhysicalFluidTopology.Device(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<ports;i++){
            int[] at=positions[i];var kind=i<2?Kind.RESERVOIR:Kind.VOID;
            var d=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",at[0],at[1],at[2]),kind,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());devices.add(d);
            double pressure=i==0?150000:i==1?(unequal?90000:150000):101325;
            double temperature=i==1&&unequal?400:350;
            double[] n=new double[mw.length];n[i==0?0:MaterialTestBasis.NITROGEN]=1;
            var unit=model.flashTP(temperature,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            var state=model.flashTP(temperature,pressure,n,()->{});
            boundaries.put(d.id(),new PassiveNetwork.Reservoir(d.id(),at[1],state,i<2?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.VOID));
        }
        var graph=PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph();
        double[] initial=totals(graph),external=new double[mw.length];
        double initialEnergy=energy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0;
        int[] signs=new int[graph.pipes().size()];
        var solver=new PassiveIntervalSolver(model);
        try {for(int step=0;step<50;step++){
            if(Boolean.getBoolean("junction.coldEachStep"))solver=new PassiveIntervalSolver(model);
            var result=solver.solve(graph,.1,PassiveIntervalSolver.Settings.defaults(),()->{});
            graph=result.graph();completed++;
            for(var transfer:result.boundaries()){
                var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];
                externalEnergy+=transfer.totalEnergyJoule();
            }
            var total=totals(graph);
            for(int c=0;c<total.length;c++){
                double error=Math.abs(total[c]-initial[c]-external[c])/Math.max(1,initial[c]);worstMoles=Math.max(worstMoles,error);
                assertTrue(error<1e-7,"component balance "+c+" error "+error);
            }
            double error=Math.abs(energy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
            worstEnergy=Math.max(worstEnergy,error);assertTrue(error<1e-7,"energy balance "+error);
            var flows=result.averageMassFlows();for(int i=0;i<flows.length;i++)if(Math.abs(flows[i])>1e-7)signs[i]|=flows[i]>0?1:2;
            if(step%10==9||step>=13&&step<=17)System.out.println("TRAJECTORY bore="+bore+" ports="+ports+" unequal="+unequal+" time="+completed*.1+" P="+graph.reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).map(n->n.state().pressure()).toList()+" q="+Arrays.toString(flows));
        }
        } finally {
        long reversals=Arrays.stream(signs).filter(s->s==3).count();
        double mixedMethane=graph.reservoirs().stream().filter(n->n.id()==2).findFirst().orElseThrow().inventory().moles()[0];
        System.out.println("DYNAMIC_METRICS bore="+bore+" ports="+ports+" unequal="+unequal+" maxComponentError="+worstMoles+" maxEnergyError="+worstEnergy+" reversedEdges="+reversals+" methaneInTank2="+mixedMethane);
        }
    }
    private double[] totals(PassiveNetwork graph){
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    private double energy(PassiveNetwork graph){
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR){
            double mass=0;var n=node.inventory().moles();for(int c=0;c<n.length;c++)mass+=mw[c]*n[c];
            energy+=node.inventory().internalEnergy()+mass*PassiveStepSolver.GRAVITY*node.elevation();
        }
        return energy;
    }
}
