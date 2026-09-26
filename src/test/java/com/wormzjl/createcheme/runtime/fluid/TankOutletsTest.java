package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A tank's outlet lines (plan 4.3 under decision D11, phase-ports WP5): read from the committed graph and the last
 * interval's pipe transfers, never solved. The fixture is plan section 6's first in-game scenario built from blocks: a
 * water/nitrogen tank with a pipe on its top, one side and its bottom, each to a void at 1 atm.
 */
class TankOutletsTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private static final PhysicalFluidTopology.Direction N=PhysicalFluidTopology.Direction.NORTH;
    private int component(String name){int i=model.components().indexOf(name);assertTrue(i>=0,name);return i;}
    private PhysicalFluidTopology.Device at(long id,int x,int y,int z,Kind kind) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),kind,N,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
    }
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen (LevelHeadTest's). */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] mw=model.molecularWeights();double[] n=new double[mw.length];int water=component("Water");
        n[water]=waterVolume*997/mw[water];n[component("Nitrogen")]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,()->{});
    }
    private static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError(id);}

    @Test void aTankWithTopSideAndBottomOutletsListsWhatEachDrew() {
        // Tank 1 at the origin; pipes 2 (top), 4 (east side), 6 (bottom); voids 3, 5, 7 beyond them. Vent pressure 120 kPa,
        // below the humid-vent floor of decision D14.
        var devices=List.of(at(1,0,0,0,Kind.RESERVOIR),at(2,0,1,0,Kind.PIPE),at(3,0,2,0,Kind.VOID),at(4,1,0,0,Kind.PIPE),at(5,2,0,0,Kind.VOID),at(6,0,-1,0,Kind.PIPE),at(7,0,-2,0,Kind.VOID));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();boundaries.put(1L,new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.5,120000)));
        for(long v:new long[]{3,5,7})boundaries.put(v,new PassiveNetwork.Reservoir(v,devices.get((int)v-1).position().y(),model.initialNitrogenCharge(1,298.15,101325,()->{}),PassiveNetwork.NodeKind.VOID));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);var graph=compiled.islands().getFirst().graph();int tank=node(graph,1);
        assertTrue(TankOutlets.of(model,graph,tank,null).stream().allMatch(o->o.massFlow()==0&&o.drawn()==FluidView.Outlet.NONE),"no interval: no flow");
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance());
        var outlets=TankOutlets.of(model,result.graph(),tank,result);
        assertEquals(3,outlets.size());var byPort=new EnumMap<PassiveNetwork.PhasePort,FluidView.Outlet>(PassiveNetwork.PhasePort.class);
        for(var o:outlets)assertNull(byPort.put(o.port(),o),"one outlet per port kind here");
        var top=byPort.get(PassiveNetwork.PhasePort.VAPOR);var side=byPort.get(PassiveNetwork.PhasePort.BULK);var bottom=byPort.get(PassiveNetwork.PhasePort.LIQUID);
        for(var o:outlets)System.out.println("TANK_OUTLET port="+o.port()+" massFlow="+o.massFlow()+" moleFlow="+o.moleFlow()+" drawn="+o.drawn()+" head="+o.head());
        assertTrue(top.massFlow()>0&&side.massFlow()>0&&bottom.massFlow()>0,"every outlet draws to its void");
        assertEquals(2,top.drawn(),"top: drawing gas");assertEquals(1,bottom.drawn(),"bottom: drawing water");
        // What each line carried (the solver's samples): the top no condensed phase, the bottom no gas, the side both.
        var transfers=new HashMap<Long,PipeTransfer>();for(var t:result.pipeTransfers())transfers.put(t.pipeId(),t);
        var topOut=out(result.graph(),tank,top.pipeId(),transfers);var bottomOut=out(result.graph(),tank,bottom.pipeId(),transfers);var sideOut=out(result.graph(),tank,side.pipeId(),transfers);
        assertEquals(0,Arrays.stream(topOut.phaseMoles()[0]).sum()+Arrays.stream(topOut.phaseMoles()[1]).sum(),0,"the top outlet drew gas only");
        assertEquals(0,Arrays.stream(bottomOut.phaseMoles()[2]).sum(),0,"the bottom outlet drew water only");
        assertTrue(Arrays.stream(sideOut.phaseMoles()[1]).sum()>0&&Arrays.stream(sideOut.phaseMoles()[2]).sum()>0,"the side outlet draws all phases together");
        // Net flows are the interval's mean mass flows of each connection, out of the tank.
        for(var o:outlets){int edge=edge(result.graph(),o.pipeId());var p=result.graph().pipes().get(edge);
            assertEquals((p.first()==tank?1:-1)*result.averageMassFlows()[edge],o.massFlow(),1e-9*Math.abs(o.massFlow()),"port "+o.port());}
        // The level head is shown at the bottom outlet only, as the solver states it on the committed state (decision D9).
        var committed=result.graph().reservoirs().get(tank);
        assertEquals(PassiveStepSolver.GRAVITY*model.liquidMass(committed.state())*PassiveStepSolver.LEVEL_HEAD_HEIGHT/committed.inventory().volume(),bottom.head(),0);
        assertEquals(0,top.head());assertEquals(0,side.head());
    }
    @Test void theLeadingPhaseIsTheOneThatCarriedTheMostMass() {
        double[] w={.016,.028,.018};double[][] n=new double[3][3];
        n[2][1]=1;n[1][2]=10;// 0.028 kg of gas, 0.18 kg of water: an overflow at a top port
        assertEquals(1,TankOutlets.leading(new PipeTransfer.Stream(.208,n,new double[]{0,1.8e-4,.02}),w));
        n[1][2]=1;// 0.028 kg of gas, 0.018 kg of water
        assertEquals(2,TankOutlets.leading(new PipeTransfer.Stream(.046,n,new double[]{0,1.8e-5,.02}),w));
        assertEquals(FluidView.Outlet.NONE,TankOutlets.leading(new PipeTransfer.Stream(0,new double[3][3],new double[3]),w));
    }
    private static PipeTransfer.Stream out(PassiveNetwork graph,int tank,long pipe,Map<Long,PipeTransfer> transfers) {
        var p=graph.pipes().get(edge(graph,pipe));var t=transfers.get(pipe);return p.first()==tank?t.forward():t.reverse();
    }
    private static int edge(PassiveNetwork graph,long id){for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).id()==id)return i;throw new AssertionError(id);}
}
