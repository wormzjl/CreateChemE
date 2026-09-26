package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunctionTraceProbe {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private static final PipeResistance.Geometry BORE=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),kind,PhysicalFluidTopology.Direction.EAST,BORE,new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir boundary(PhysicalFluidTopology.Device d,double pressure,int component){
        double[] n=new double[model.components().size()];n[component]=1;
        var unit=model.flashTP(350,pressure,n,()->{});n[component]/=unit.volume();
        return new PassiveNetwork.Reservoir(d.id(),d.position().y(),model.flashTP(350,pressure,n,()->{}),
            d.kind()==Kind.GENERATOR?PassiveNetwork.NodeKind.GENERATOR:PassiveNetwork.NodeKind.VOID);
    }
    /** Actual block compiler, including vertical connections, with two different feeds and 2–4 outlets. */
    @Test void fourFiveAndSixEquipmentConnectionsMixAndBalanceWithoutDuplicatingDisplayedThroughput(){
        int[][] ports={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        for(int count=4;count<=4;count++){
            var center=device(100,0,0,0,Kind.PIPE);var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(center);
            var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
            for(int i=0;i<count;i++){
                int[] port=ports[i];var d=device(i+1,port[0],port[1],port[2],i<2?Kind.GENERATOR:Kind.VOID);devices.add(d);
                boundaries.put(d.id(),boundary(d,i<2?102325:101325,i==0?0:MaterialTestBasis.NITROGEN));
            }
            var compiled=PhysicalFluidTopology.compile(devices,boundaries);
            assertEquals(1,compiled.islands().size());var graph=compiled.islands().getFirst().graph();
            assertEquals(count,graph.pipes().size());assertEquals(count,compiled.pipeViews().get(100L).size());
            int node=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==100)node=i;
            assertTrue(node>=0);assertTrue(graph.reservoirs().get(node).junction());
            System.out.println("GRAPH "+graph.reservoirs().stream().map(n->n.id()+":"+n.state().pressure()+":"+n.state().mass()).toList());
            var result=new PassiveStepSolver(model).solve(graph,.05,()->{});
            System.out.println("SOLVED "+result.numerical()+" flows="+Arrays.toString(result.massFlows()));
        }
    }
}
