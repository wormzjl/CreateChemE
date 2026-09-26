package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipeJunctionMixedFeedProbe {
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
        for(int count=4;count<=6;count++){
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
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            var accepted=result.graph();double outgoing=0,incoming=0;int inlets=0,outlets=0;
            double[] inComponents=new double[model.components().size()],outComponents=new double[model.components().size()];
            for(var t:result.pipeTransfers()){
                var edge=accepted.pipes().stream().filter(e->e.id()==t.pipeId()).findFirst().orElseThrow();
                var out=edge.first()==node?t.forward():t.reverse();var in=edge.first()==node?t.reverse():t.forward();
                outgoing+=out.massKg();incoming+=in.massKg();if(out.massKg()>1e-12)outlets++;if(in.massKg()>1e-12)inlets++;
                double[] a=in.componentMoles(),b=out.componentMoles();for(int c=0;c<a.length;c++){inComponents[c]+=a[c];outComponents[c]+=b[c];}
            }
            assertEquals(2,inlets,"two sources at "+count+" ports");assertEquals(count-2,outlets);
            assertEquals(incoming,outgoing,Math.max(1e-8,incoming*1e-7));
            for(int c=0;c<inComponents.length;c++)assertEquals(inComponents[c],outComponents[c],Math.max(1e-7,inComponents[c]*1e-6),"component "+c+" at "+count+" ports");
            var info=PipePresentation.inspect(center,accepted,compiled.pipeViews().get(100L),result.pipeTransfers(),result.advancedSeconds(),model.components().size());
            assertTrue(info.junction());assertEquals(count,info.connections().size());
            assertEquals(outgoing,info.contents().massKg(),1e-12,"do not add inbound and outbound twice");
            assertTrue(info.contents().componentMoles()[MaterialTestBasis.NITROGEN]>0);
            assertTrue(info.connections().stream().allMatch(c->Double.isFinite(c.velocityMetresPerSecond())&&c.velocityMetresPerSecond()>0));
        }
    }
    private PipeTransfer.Stream moved(double mass,double volume,int component){
        double[][] n=new double[3][model.components().size()];n[2][component]=mass/model.molecularWeights()[component];
        return new PipeTransfer.Stream(mass,n,new double[]{0,0,volume});
    }
    @Test void aStraightPipeAutomaticallyShowsReverseFlowAndPreservesBothDirectionsOfAReversal(){
        var a=device(1,-1,0,0,Kind.GENERATOR);var b=device(2,1,0,0,Kind.VOID);var center=device(100,0,0,0,Kind.PIPE);
        var graph=new PassiveNetwork(List.of(boundary(a,150000,0),boundary(b,101325,0)),List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var mappings=List.of(new PhysicalFluidTopology.View(0,9,true));var zero=moved(0,0,0);
        var reverse=PipePresentation.inspect(center,graph,mappings,List.of(new PipeTransfer(9,zero,moved(2,.5,0))),2,model.components().size());
        assertFalse(reverse.junction());assertTrue(reverse.connections().getFirst().reverse());assertEquals(2,reverse.contents().massKg());
        assertEquals(.5/2/BORE.area(),reverse.connections().getFirst().velocityMetresPerSecond(),1e-12);
        assertEquals((150000-101325)/10.0,reverse.connections().getFirst().pressureDropPascalPerMetre(),1e-10);
        var mixed=PipePresentation.inspect(center,graph,mappings,List.of(new PipeTransfer(9,moved(1,.25,0),moved(2,.5,MaterialTestBasis.NITROGEN))),2,model.components().size());
        assertTrue(mixed.changedDirection());assertEquals(3,mixed.contents().massKg());assertTrue(mixed.contents().componentMoles()[0]>0);
        assertTrue(mixed.contents().componentMoles()[MaterialTestBasis.NITROGEN]>0);
    }
    @Test void anUnsolvedPipeHasZeroTransportButStillHasAPressureReading(){
        var a=device(1,-1,0,0,Kind.GENERATOR);var b=device(2,1,0,0,Kind.VOID);var center=device(100,0,0,0,Kind.PIPE);
        var graph=new PassiveNetwork(List.of(boundary(a,150000,0),boundary(b,101325,0)),List.of(new PassiveNetwork.Pipe(9,0,1,BORE)));
        var info=PipePresentation.inspect(center,graph,List.of(new PhysicalFluidTopology.View(0,9,true)),List.of(),0,model.components().size());
        assertEquals(0,info.contents().massKg());assertEquals(0,info.connections().getFirst().velocityMetresPerSecond());
        assertEquals(48675,info.connections().getFirst().pressureDropPascalPerMetre(),1e-10);
    }
}
