package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunctionRobustnessProbe {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private static final PipeResistance.Geometry BORE=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),kind,PhysicalFluidTopology.Direction.EAST,BORE,new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir boundary(PhysicalFluidTopology.Device d,double pressure,int component){
        double temperature=asymmetric&&d.kind()==Kind.GENERATOR?(d.id()==1?300:400):350;
        double[] n=new double[model.components().size()];n[component]=1;
        var unit=model.flashTP(temperature,pressure,n,()->{});n[component]/=unit.volume();
        return new PassiveNetwork.Reservoir(d.id(),d.position().y(),model.flashTP(temperature,pressure,n,()->{}),
            d.kind()==Kind.GENERATOR?PassiveNetwork.NodeKind.GENERATOR:PassiveNetwork.NodeKind.VOID);
    }
    private boolean swapped,asymmetric;
    @Test void unequalFeedsAndSourceOrder(){
        for(boolean swap:new boolean[]{false,true})for(boolean unequal:new boolean[]{false,true}) {
            swapped=swap;asymmetric=unequal;
            for(double pressure:new double[]{102325,150000})for(int count=3;count<=6;count++) {
                try {runCase(count,pressure,1);
                    System.out.println("ROBUST ports="+count+" pressure="+pressure+" swap="+swap+" unequal="+unequal+" PASS conservation");}
                catch(Exception|AssertionError failure){System.out.println("ROBUST ports="+count+" pressure="+pressure+" swap="+swap+" unequal="+unequal+" FAIL "+failure);}
            }
        }
    }
    private void runCase(int count,double pressure,double seed) {
        int[][] ports={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};

            var center=device(100,0,0,0,Kind.PIPE);var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(center);
            var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
            for(int i=0;i<count;i++){
                int[] port=ports[i];var d=device(i+1,port[0],port[1],port[2],i<2?Kind.GENERATOR:Kind.VOID);devices.add(d);
                boundaries.put(d.id(),boundary(d,i==0?pressure:i==1?pressure-(asymmetric?(pressure>110000?5000:100):0):101325,i<2?(((i==0)^swapped)?0:MaterialTestBasis.NITROGEN):MaterialTestBasis.NITROGEN));
            }
            var compiled=PhysicalFluidTopology.compile(devices,boundaries);
            assertEquals(1,compiled.islands().size());var graph=compiled.islands().getFirst().graph();
            assertEquals(count,graph.pipes().size());assertEquals(count,compiled.pipeViews().get(100L).size());
            int node=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==100)node=i;
            assertTrue(node>=0);assertTrue(graph.reservoirs().get(node).junction());
            if(seed<1) {
                var nodes=new ArrayList<>(graph.reservoirs());var old=nodes.get(node);
                var state=model.flashTP(old.state().temperature(),101325+seed*(pressure-101325),
                    com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(old.state()),()->{});
                nodes.set(node,new PassiveNetwork.Reservoir(old.id(),old.elevation(),state,old.kind()));
                graph=new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
            }
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            var accepted=result.graph();double outgoing=0,incoming=0;int inlets=0,outlets=0;
            double[] inComponents=new double[model.components().size()],outComponents=new double[model.components().size()];
            for(var t:result.pipeTransfers()){
                var edge=accepted.pipes().stream().filter(e->e.id()==t.pipeId()).findFirst().orElseThrow();
                var out=edge.first()==node?t.forward():t.reverse();var in=edge.first()==node?t.reverse():t.forward();
                outgoing+=out.massKg();incoming+=in.massKg();if(out.massKg()>1e-12)outlets++;if(in.massKg()>1e-12)inlets++;
                double[] a=in.componentMoles(),b=out.componentMoles();for(int c=0;c<a.length;c++){inComponents[c]+=a[c];outComponents[c]+=b[c];}
            }
            if(!asymmetric)assertEquals(2,inlets,"two sources at "+count+" ports");else assertTrue(inlets>=1);assertEquals(count-2,outlets);
            assertEquals(incoming,outgoing,Math.max(1e-8,incoming*1e-7));
            for(int c=0;c<inComponents.length;c++)assertEquals(inComponents[c],outComponents[c],Math.max(1e-7,inComponents[c]*1e-6),"component "+c+" at "+count+" ports");
            var info=PipePresentation.inspect(center,accepted,compiled.pipeViews().get(100L),result.pipeTransfers(),result.advancedSeconds(),model.components().size());
            assertTrue(info.junction());assertEquals(count,info.connections().size());
            assertEquals(outgoing,info.contents().massKg(),1e-12,"do not add inbound and outbound twice");
            assertTrue(info.contents().componentMoles()[MaterialTestBasis.NITROGEN]>0);
            assertTrue(info.connections().stream().allMatch(c->Double.isFinite(c.velocityMetresPerSecond())&&c.velocityMetresPerSecond()>0));

    }
}
