package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision D10 of documentation/2026-09-26-phase-ports-and-compressor (the base defect of PHASE_PORTS_REVIEW.md, WP1
 * section 8): a junction seeded dry that receives water it cannot see. Water vapour that stays unsaturated at the junction
 * does not change the junction's phase code, so no phase correction reseeds it; before D10 its {@code PhaseLayout} had no
 * water, the reconstruction booked the arriving water into it anyway, and the equation gate refused every step at every
 * step size (1.3058e-8 at 0.5 % water, 2.6151e-8 at 1 %). A junction whose reachable set holds water is now seeded with a
 * water entry trace ({@code PassiveStepSolver.initialPhaseSeeds}). Each case - a humid (unsaturated) nitrogen vessel on a
 * BULK line, and a wet two-phase vessel's VAPOR port - runs through a junction seeded as dry nitrogen to a void, at 0.1 s
 * and 5 s slices: every slice commits whole, the component ledger (vessel plus the junction's owned holdup against the
 * boundaries) closes to 1e-12 and the energy ledger to 1e-10, the junction's own inventory starts dry (the trace is a seed,
 * never stock), and the water reaches the void.
 */
class JunctionWaterTraceTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=model.components().indexOf("Water"),nitrogen=model.components().indexOf("Nitrogen");
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(10,.01,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);

    /** 1 m3 of nitrogen with {@code humidity} (mole fraction) of water at 298.15 K and 200 kPa, or, for a negative
     * argument, half water and half nitrogen (two-phase). */
    private FluidThermodynamics.State vessel(double humidity) {
        double t=298.15,p=200000;double[] n=new double[mw.length];
        if(humidity>=0){n[nitrogen]=1-humidity;n[water]=humidity;}
        else{n[water]=.5*997/mw[water];n[nitrogen]=p*.5/(FluidThermodynamics.R*t);}
        var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,p,n,NOOP);
    }
    private PassiveNetwork line(FluidThermodynamics.State tank,PhasePort port) {
        var dry=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,dry,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,dry,PassiveNetwork.NodeKind.VOID));
        return PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(20,0,1,List.of(LINE),new FlowControl.Passive(),0,null,port,PhasePort.BULK),new PassiveNetwork.Pipe(21,1,2,LINE))),model);
    }

    @Test void unsaturatedWaterVapourThroughABulkLineIntoADrySeededJunction() {
        for(double humidity:new double[]{.005,.01}) {
            var tank=vessel(humidity);
            assertEquals(0,tank.waterLiquid(),"the vessel's water is all vapour");
            integrate("bulk "+humidity,line(tank,PhasePort.BULK));
        }
    }
    @Test void aWetVesselsVaporPortIntoADrySeededJunction() {
        var tank=vessel(-1);
        assertTrue(tank.waterLiquid()>0&&tank.waterVapor()>0,"two-phase wet vessel");
        integrate("vapor port",line(tank,PhasePort.VAPOR));
    }

    private void integrate(String label,PassiveNetwork initial) {
        assertEquals(0,initial.reservoirs().get(1).inventory().moles()[water],label+": the junction starts dry");
        for(double interval:new double[]{.1,5}) {
            int count=interval==5?4:40;var graph=initial;
            double[] start=owned(graph),external=new double[mw.length];double startEnergy=ownedEnergy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0,voidWater=0;int accepted=0,rejected=0;
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
            for(int slice=0;slice<count;slice++) {
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
                assertEquals(interval,result.advancedSeconds(),0,label+" at "+interval+" s slice "+slice+": the whole slice commits");
                accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();
                for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();
                    if(transfer.nodeId()==3)voidWater-=n[water];}
                var now=owned(graph);
                for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-start[c]-external[c])/Math.max(1,start[c]);worstMoles=Math.max(worstMoles,error);
                    assertTrue(error<COMPONENT_LEDGER,label+" at "+interval+" s slice "+slice+": component "+c+" ledger "+error);}
                double error=Math.abs(ownedEnergy(graph)-startEnergy-externalEnergy)/Math.max(1,Math.abs(startEnergy));worstEnergy=Math.max(worstEnergy,error);
                assertTrue(error<ENERGY_LEDGER,label+" at "+interval+" s slice "+slice+": energy ledger "+error);
            }
            var junction=graph.reservoirs().get(1).state();
            System.out.println("JUNCTION_WATER_TRACE "+label+" interval="+interval+" slices="+count+" accepted="+accepted+" rejected="+rejected+" worstComponentLedger="+worstMoles
                    +" worstEnergyLedger="+worstEnergy+" junctionWaterFraction="+waterFraction(junction)+" waterToVoid="+voidWater);
            assertTrue(voidWater>0,label+" at "+interval+" s: the water passes the junction to the void");
            assertTrue(junction.waterVapor()>0,label+" at "+interval+" s: the junction carries the water it receives");
        }
    }
    private double[] owned(PassiveNetwork graph) {
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    private double ownedEnergy(PassiveNetwork graph) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){
            double m=0;var n=node.inventory().moles();for(int c=0;c<n.length;c++)m+=n[c]*mw[c];
            energy+=node.inventory().internalEnergy()+m*PassiveStepSolver.GRAVITY*node.elevation();}
        return energy;
    }
    /** A state's water mole fraction. */
    private double waterFraction(FluidThermodynamics.State s) {
        var n=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(s);double total=0;for(double x:n)total+=x;
        return n[water]/total;
    }
}
