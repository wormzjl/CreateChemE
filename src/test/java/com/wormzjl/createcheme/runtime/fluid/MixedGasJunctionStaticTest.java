package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The 32-case fixed-boundary mixed-gas junction matrix (documentation/2026-09-24-mixed-gas-junction): a compiled junction
 * with 3 to 6 ports, two generators (102325 or 150000 Pa; methane and nitrogen, swapped or not; equal or unequal pressure
 * and temperature) and the rest voids at 101325 Pa, solved cold over one 0.05 s interval with no initializer. Every case
 * must converge; the junction passes what arrives (one-way generators may shut off in the unequal cases) and its owned
 * holdup's change closes the component balance across it. Formerly JunctionHoldupStaticProbe
 * (tools/junction-holdup-prototype), where the zero-holdup junction passed 7 of 32 and the TR-BDF2 chain 31.
 */
class MixedGasJunctionStaticTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private static final PipeResistance.Geometry BORE=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private boolean swapped,asymmetric;

    @Test void everyColdJunctionCaseConvergesAndClosesItsBalance() {
        var failures=new ArrayList<String>();int cases=0;
        for(boolean swap:new boolean[]{false,true})for(boolean unequal:new boolean[]{false,true}) {
            swapped=swap;asymmetric=unequal;
            for(double pressure:new double[]{102325,150000})for(int count=3;count<=6;count++) {
                cases++;
                try{runCase(count,pressure);}
                catch(Exception|AssertionError failure){failures.add("ports="+count+" pressure="+pressure+" swap="+swap+" unequal="+unequal+": "+failure);}
            }
        }
        assertEquals(32,cases);
        assertTrue(failures.isEmpty(),failures.size()+" of 32 cases failed:\n"+String.join("\n",failures));
    }
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
    private void runCase(int count,double pressure) {
        int[][] ports={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        var center=device(100,0,0,0,Kind.PIPE);var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(center);
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<count;i++){
            int[] port=ports[i];var d=device(i+1,port[0],port[1],port[2],i<2?Kind.GENERATOR:Kind.VOID);devices.add(d);
            boundaries.put(d.id(),boundary(d,i==0?pressure:i==1?pressure-(asymmetric?(pressure>110000?5000:100):0):101325,i<2?(((i==0)^swapped)?0:MaterialTestBasis.NITROGEN):MaterialTestBasis.NITROGEN));
        }
        // Sized here, as the solver would at its first solve, so the balance below starts from the owned holdup.
        var graph=PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
        int node=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==100)node=i;
        assertTrue(graph.reservoirs().get(node).junction());
        var before=graph.reservoirs().get(node).inventory();
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var accepted=result.graph();var afterInventory=accepted.reservoirs().get(node).inventory();
        double outgoing=0,incoming=0;int inlets=0,outlets=0;
        double[] inComponents=new double[model.components().size()],outComponents=new double[model.components().size()];
        for(var t:result.pipeTransfers()){
            var edge=accepted.pipes().stream().filter(e->e.id()==t.pipeId()).findFirst().orElseThrow();
            var out=edge.first()==node?t.forward():t.reverse();var in=edge.first()==node?t.reverse():t.forward();
            outgoing+=out.massKg();incoming+=in.massKg();if(out.massKg()>1e-12)outlets++;if(in.massKg()>1e-12)inlets++;
            double[] a=in.componentMoles(),b=out.componentMoles();for(int c=0;c<a.length;c++){inComponents[c]+=a[c];outComponents[c]+=b[c];}
        }
        if(!asymmetric)assertEquals(2,inlets,"two sources");else assertTrue(inlets>=1);
        assertEquals(count-2,outlets);
        // The junction's mass is pinned, so what arrives leaves; what it mixes into its holdup is its owned inventory's change.
        assertEquals(incoming,outgoing,Math.max(1e-8,incoming*1e-7));
        var a=before.moles();var b=afterInventory.moles();
        for(int c=0;c<inComponents.length;c++)assertEquals(inComponents[c]-outComponents[c],b[c]-a[c],Math.max(1e-7,inComponents[c]*1e-6),"component "+c);
    }
}
