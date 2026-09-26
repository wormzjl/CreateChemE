package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision D11 of documentation/2026-09-26-phase-ports-and-compressor (ports carry mixed phases by priority; it replaces
 * WP2's "outlet phase absent" closure): a bottom (LIQUID) port draws the heavier liquid, then the lighter, then the gas; a
 * top (VAPOR) port the gas, then the lighter liquid, then the heavier. A phase is drawn over a step only up to what the
 * vessel held of it at the step start, {@code c = m_start / (N dt)}, and the flow beyond it draws the next phase in the
 * same step (the priority stream, {@link PhaseDraw}). No port ever closes for want of a phase.
 *
 * <p>Every committed slice closes the component ledger to 1e-12 and the energy ledger to 1e-10 (relative to
 * max(1, initial); vessels and junction holdups against the boundaries), commits the whole slice (no held slice) and is a
 * job as the island runtime runs it ({@link PassiveIntervalSolver#replayStart} from the committed interval). Each fixture
 * prints one line per slice ({@code PHASE_PORT_PRIORITY_SLICE}: the watched vessel's gas, hydrocarbon-liquid and water
 * volume shares at the slice end, its pressure, the gas, hydrocarbon liquid and water that left through the watched port,
 * what entered, the step counts and rejections) and a summary line; the measured numbers are in PHASE_PORTS_REVIEW.md,
 * section "D11".
 */
class PhasePortPriorityTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=index("Water"),nitrogen=index("Nitrogen"),pentane=index("N-pentane");
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private static final String REOPEN="Backward-Euler boundary reopened";
    private static final int GAS=FluidThermodynamics.GAS,OIL=FluidThermodynamics.OIL,WATER=FluidThermodynamics.WATER;
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,PhasePort pa,PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(g),new FlowControl.Passive(),0,null,pa,pb);
    }

    private int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    /** A 1 m3 vessel at 298.15 K and {@code pressure}: {@code oilVolume} m3 of n-pentane and {@code waterVolume} m3 of water
     * under nitrogen, flashed and scaled to exactly the vessel volume. */
    private FluidThermodynamics.State vessel(double oilVolume,double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[pentane]=oilVolume*626/mw[pentane];n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-oilVolume-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    private FluidThermodynamics.State waterSupply(double pressure){var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,pressure,n,NOOP);}
    private FluidThermodynamics.State nitrogenAt(double pressure){return model.initialNitrogenCharge(1,298.15,pressure,NOOP);}
    private static double share(FluidThermodynamics.State s,int phase) {
        return (phase==GAS?s.vaporVolume():phase==OIL?s.liquidVolume():s.waterVolume())/s.volume();
    }
    private double mass(double[] moles){double m=0;for(int c=0;c<moles.length;c++)m+=moles[c]*mw[c];return m;}
    /** The mass of each phase a transferred stream carries: [gas, hydrocarbon liquid, free water]. */
    private double[] phases(PipeTransfer.Stream stream) {
        var n=stream.phaseMoles();return new double[]{mass(n[2]),mass(n[0]),mass(n[1])};
    }

    /** One committed slice: the watched vessel's state at the slice start and end, what left through the watched port by
     * phase ([gas, hydrocarbon liquid, water], kg over the slice), what entered, and the slice's counters. */
    private record Slice(int index,FluidThermodynamics.State start,FluidThermodynamics.State end,double[] out,double in,int accepted,int rejected,int reopens,
                         Map<String,Integer> reasons,List<FlowControl.Mode> modes,double[] pressures,double headEnd,PassiveIntervalSolver.Result result) {
        double outTotal(){return out[0]+out[1]+out[2];}
    }
    /** {@code count} slices of {@code interval}; the port under watch is the phase-port end of connection {@code watched}. */
    private List<Slice> drive(String label,PassiveNetwork graph,double interval,int count,long watched) {
        int edge=-1;for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).id()==watched)edge=i;
        var pipe=graph.pipes().get(edge);int vessel=pipe.firstPort()!=PhasePort.BULK?pipe.first():pipe.second();boolean vesselFirst=vessel==pipe.first();
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0;
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;var slices=new ArrayList<Slice>();
        for(int slice=0;slice<count;slice++) {
            var start=graph.reservoirs().get(vessel).state();
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance(),label+" slice "+slice);
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice+": the whole slice commits");
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            externalEnergy+=result.pumpWorkJoule();
            var now=owned(graph);
            for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);worstMoles=Math.max(worstMoles,error);
                assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
            double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));worstEnergy=Math.max(worstEnergy,error);
            assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
            var transfer=result.pipeTransfers().stream().filter(t->t.pipeId()==watched).findFirst().orElseThrow();
            var outStream=vesselFirst?transfer.forward():transfer.reverse();double in=(vesselFirst?transfer.reverse():transfer.forward()).massKg();
            var end=graph.reservoirs().get(vessel).state();
            double[] pressures=graph.reservoirs().stream().mapToDouble(n->n.state().pressure()).toArray();
            slices.add(new Slice(slice,start,end,phases(outStream),in,result.acceptedSubsteps(),result.rejectedSubsteps(),result.rejectionReasons().getOrDefault(REOPEN,0),
                    result.rejectionReasons(),result.endpointModes(),pressures,PassiveStepSolver.GRAVITY*model.liquidMass(end)*PassiveStepSolver.LEVEL_HEAD_HEIGHT/graph.reservoirs().get(vessel).inventory().volume(),result));
        }
        int rejected=0,reopens=0,worst=0;var reasons=new TreeMap<String,Integer>();
        for(var s:slices) {
            rejected+=s.rejected();reopens+=s.reopens();worst=Math.max(worst,s.rejected());s.reasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            System.out.println("PHASE_PORT_PRIORITY_SLICE "+label+" "+s.index()+" gas="+share(s.end(),GAS)+" oil="+share(s.end(),OIL)+" water="+share(s.end(),WATER)
                    +" P="+s.start().pressure()+"->"+s.end().pressure()+" T="+s.end().temperature()+" outGas="+s.out()[0]+" outOil="+s.out()[1]+" outWater="+s.out()[2]+" in="+s.in()
                    +" accepted="+s.accepted()+" rejected="+s.rejected()+" reasons="+s.reasons()+" modes="+s.modes());
        }
        System.out.println("PHASE_PORT_PRIORITY_RUN "+label+" slices="+count+" accepted="+slices.stream().mapToInt(Slice::accepted).sum()+" rejected="+rejected
                +" worstRejectedPerSlice="+worst+" reopens="+reopens+" reasons="+reasons+" worstComponentLedger="+worstMoles+" worstEnergyLedger="+worstEnergy);
        return slices;
    }
    /** Tank and junction inventories (a junction's owned holdup counts like a tank's). */
    private double[] owned(PassiveNetwork graph) {
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    private double ownedEnergy(PassiveNetwork graph) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction())
            energy+=node.inventory().internalEnergy()+mass(node.inventory().moles())*PassiveStepSolver.GRAVITY*node.elevation();
        return energy;
    }

    // ---- 1. A bottom port drains its water to zero, then vents the gas ----------------------------------------------

    /**
     * 0.1 m3 of water under nitrogen at 120 kPa, drained through a LIQUID port (10 m, 25 mm) to a void at 1 atm.
     *
     * <p>Why 120 kPa: once its water is gone the tank vents its humid headspace to the void, and the vessel is adiabatic, so
     * the gas cools as it expands; from 180 kPa or more it cools below the water property domain (273.16 K) and every step
     * is refused. That is the base model, not the port: a humid nitrogen tank vented through a BULK end fails the same way
     * on the WP1-D9 tree (PHASE_PORTS_REVIEW.md, D11, open items); from 120 kPa it stays above 295 K.
     */
    private PassiveNetwork drain() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(0,.1,120000)),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(30,0,1,line(10,.025),PhasePort.LIQUID,PhasePort.BULK)));
    }
    @Test void aBottomPortDrainsItsWaterToZeroThenVentsTheGasAtFiveSecondSlices(){drainToGas(5,40);}
    @Test void aBottomPortDrainsItsWaterToZeroThenVentsTheGasAtTenthSecondSlices(){drainToGas(.1,2000);}
    /**
     * The port carries water, and only water, until the step whose flow exceeds the water the tank still holds: that step
     * draws the water at its capacity (all the start holds) and the gas with the rest - the gas breakthrough, within one
     * step. From then on the tank holds no free water beyond the condensate of its expanding, cooling headspace, which the
     * port also draws (the heavier liquid comes first) until the water reaches exactly zero, and the same port vents the
     * headspace until the tank stands at the void's pressure. The port is never closed while the tank stands above the
     * void, and nothing is refused on its account: no boundary reopen (the one rejection a port refusal could cause).
     */
    private void drainToGas(double interval,int count) {
        String label="drain-"+interval;
        var slices=drive(label,drain(),interval,count,30);
        int breakthrough=-1,dry=-1,rejected=0;
        for(var s:slices) {
            boolean aboveVoid=s.start().pressure()>101325+PassiveStepSolver.reopenBand(101325,s.start().pressure());
            if(breakthrough<0&&s.out()[0]>0)breakthrough=s.index();
            if(breakthrough<0)assertTrue(s.out()[2]>0,label+" slice "+s.index()+": the port draws water while the tank holds it");
            else if(s.index()>breakthrough) {
                assertTrue(share(s.end(),WATER)<=1e-6,label+" slice "+s.index()+": after the breakthrough only condensate: "+share(s.end(),WATER));
                if(aboveVoid)assertTrue(s.out()[0]>0,label+" slice "+s.index()+": the dry tank vents its gas through the bottom port");
            }
            if(aboveVoid)assertNotEquals(FlowControl.Mode.CLOSED,s.modes().getFirst(),label+" slice "+s.index()+": the port is never closed while the tank stands above the void");
            assertEquals(0,s.reopens(),label+" slice "+s.index()+": no boundary reopen");
            if(dry<0&&share(s.end(),WATER)==0)dry=s.index();
            rejected+=s.rejected();
        }
        assertTrue(breakthrough>0,label+": the gas breaks through");
        var at=slices.get(breakthrough);
        assertTrue(at.out()[2]>0,label+": the breakthrough step draws the last of the water with the gas");
        assertTrue(share(at.end(),WATER)<=1e-6,label+": the breakthrough leaves no water but condensate: "+share(at.end(),WATER));
        assertTrue(dry>=breakthrough,label+": the water drains to exactly zero");
        var last=slices.getLast();
        System.out.println("PHASE_PORT_PRIORITY_DRAIN interval="+interval+" breakthroughSlice="+breakthrough+" breakthroughSeconds="+(breakthrough+1)*interval
                +" waterThere="+at.out()[2]+" gasThere="+at.out()[0]+" waterShareAfter="+share(at.end(),WATER)+" dryAtSlice="+dry+" condensateDrawnAfter="
                +slices.subList(breakthrough+1,count).stream().mapToDouble(s->s.out()[2]).sum()+" finalPressure="+last.end().pressure()+" rejected="+rejected);
        assertEquals(101325,last.end().pressure(),101325*1e-3,label+": vented down to the void");
    }

    // ---- 2. A drain fed water slowly: steady gas breakthrough ------------------------------------------------------

    /** A nitrogen tank at 150 kPa holding 2 L of water, its headspace fed by a 150 kPa generator of nitrogen humid to 99.9 %
     * of saturation at the tank's temperature (BULK end, 2 m, 50 mm; humid, so that the throughflow evaporates almost none of
     * the water and the tank comes to rest instead of cooling towards a wet-bulb temperature), fed water at {@code FEED}
     * kg/s by a scheduled injection, drained through a LIQUID port (10 m, 10 mm) to a void at 1 atm. */
    private static final double FEED=.002;
    private PassiveNetwork fedDrain() {
        var supply=waterSupply(150000);double[] total=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(supply);
        double perMole=supply.enthalpy()/Arrays.stream(total).sum();double molesPerSecond=FEED/mw[water];
        var rates=new double[mw.length];rates[water]=molesPerSecond;
        double t=298.15,y=.999*model.saturationPressure(t)/150000;double[] gas=new double[mw.length];gas[nitrogen]=1-y;gas[water]=y;
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(0,.002,150000)),new PassiveNetwork.Reservoir(2,0,model.flashTP(t,150000,gas,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(40,0,2,line(10,.01),PhasePort.LIQUID,PhasePort.BULK),new PassiveNetwork.Pipe(41,1,0,line(2,.05))),
                List.of(new ScheduledTransfer.Injection(100,0,rates,molesPerSecond*perMole)));
    }
    @Test void aSlowlyFedDrainBreaksThroughSteadilyAtFiveSecondSlices(){fedDrain(5,60,40);}
    @Test void aSlowlyFedDrainBreaksThroughSteadilyAtTenthSecondSlices(){fedDrain(.1,1500,1000);}
    /**
     * Once the initial water has gone, each step draws the water the tank holds at its start at its capacity and the gas
     * with the rest: the port passes water at the rate it is fed and gas beside it, in every slice, with no alternation
     * between a water slice and a gas slice. At rest the liquid through the port is the feed (to the little the 99.9 %
     * humid throughflow still evaporates), so its mass fraction of the port's flow is the feed over the total flow, and it
     * varies from slice to slice by less than a thousandth of itself.
     */
    private void fedDrain(double interval,int count,int settled) {
        String label="fed-"+interval;
        var slices=drive(label,fedDrain(),interval,count,40);
        var rest=slices.subList(settled,count);
        double liquidMean=0;for(var s:rest)liquidMean+=s.out()[2];liquidMean/=rest.size();
        double worstStep=0,worstFeed=0,fed=FEED*interval;
        for(int i=0;i<rest.size();i++) {
            var s=rest.get(i);
            assertTrue(s.out()[2]>0&&s.out()[0]>0,label+" slice "+s.index()+": water and gas leave together in every slice");
            if(i>0)worstStep=Math.max(worstStep,Math.abs(s.out()[2]-rest.get(i-1).out()[2])/liquidMean);
            worstFeed=Math.max(worstFeed,Math.abs(s.out()[2]-fed)/fed);
        }
        var last=slices.getLast();double total=last.outTotal();
        System.out.println("PHASE_PORT_PRIORITY_FED interval="+interval+" liquidPerSlice="+liquidMean+" feedPerSlice="+fed+" liquidFraction="+last.out()[2]/total
                +" feedOverTotal="+fed/total+" worstLiquidVsFeed="+worstFeed+" worstSliceToSliceLiquid="+worstStep+" tankWater="+share(last.end(),WATER)
                +" tankPressure="+last.end().pressure()+" tankTemperature="+last.end().temperature());
        assertTrue(worstFeed<1e-3,label+": at rest the liquid through the port is the feed: "+worstFeed);
        assertEquals(fed/total,last.out()[2]/total,1e-3*fed/total,label+": the port's liquid fraction is the feed over the total flow");
        assertTrue(worstStep<1e-3,label+": the liquid through the port is steady from slice to slice: "+worstStep);
    }

    // ---- 3. A top vent on a tank filled liquid-full overflows the liquid -------------------------------------------

    /** 0.8 m3 of water under nitrogen at 1 atm, filled from a 300 kPa water generator through its LIQUID port (10 m, 25 mm)
     * and vented through a VAPOR port (10 m, 25 mm) to a void at 1 atm. */
    private PassiveNetwork overflow() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(0,.8,101325)),new PassiveNetwork.Reservoir(2,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(31,1,0,line(10,.025),PhasePort.BULK,PhasePort.LIQUID),pipe(32,0,2,line(10,.025),PhasePort.VAPOR,PhasePort.BULK)));
    }
    @Test void aTopVentOverflowsTheLiquidOfATankFilledFullAtFiveSecondSlices(){overflow(5,40);}
    @Test void aTopVentOverflowsTheLiquidOfATankFilledFullAtTenthSecondSlices(){overflow(.1,900);}
    /**
     * Water fills the tank and pushes its nitrogen out of the top port; once the gas is gone (nitrogen does not dissolve in
     * free water) the same port overflows the water, the lighter (here the only) liquid, and the line runs generator to
     * tank to void: the tank never stands above the generator, at its bottom port, where the generator feeds it
     * (plan 3.4's "liquid-full vessels may increase in pressure" is withdrawn by D11).
     */
    private void overflow(double interval,int count) {
        String label="overflow-"+interval;
        var slices=drive(label,overflow(),interval,count,32);
        int firstWater=-1;
        for(var s:slices) {
            assertTrue(s.end().pressure()+s.headEnd()<=300000+1,label+" slice "+s.index()+": the tank never stands above the generator: "+(s.end().pressure()+s.headEnd()));
            if(firstWater<0&&s.out()[2]>0)firstWater=s.index();
            if(firstWater<0)assertTrue(s.out()[0]>0,label+" slice "+s.index()+": the vent draws gas while the tank holds any");
        }
        assertTrue(firstWater>0,label+": the vent overflows water once the tank is full");
        var last=slices.getLast();double fed=last.result().pipeTransfers().stream().filter(t->t.pipeId()==31).findFirst().orElseThrow().forward().massKg();
        System.out.println("PHASE_PORT_PRIORITY_OVERFLOW interval="+interval+" firstWaterSlice="+firstWater+" gasThere="+slices.get(firstWater).out()[0]+" waterThere="+slices.get(firstWater).out()[2]
                +" finalGas="+share(last.end(),GAS)+" finalPressure="+last.end().pressure()+" finalHead="+last.headEnd()+" finalOverflow="+last.out()[2]+" finalFed="+fed);
        assertEquals(0,share(last.end(),GAS),label+": the tank ends liquid-full");
        assertEquals(0,last.out()[0],label+": the full tank's vent carries no gas");
        assertEquals(fed,last.out()[2],1e-5*fed,label+": at rest what the generator feeds overflows");
    }

    // ---- 4. Pentane over water: the order of each port -------------------------------------------------------------

    /** 0.05 m3 of n-pentane over 0.05 m3 of water under nitrogen at 120 kPa (see {@link #drain}), drained through a LIQUID
     * port (10 m, 25 mm) to a void at 1 atm. */
    private PassiveNetwork oilWaterDrain() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(.05,.05,120000)),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(33,0,1,line(10,.025),PhasePort.LIQUID,PhasePort.BULK)));
    }
    /** 0.3 m3 of n-pentane over 0.3 m3 of water under nitrogen at 1 atm, filled from a 300 kPa water generator through its
     * LIQUID port (10 m, 25 mm) and vented through a VAPOR port (10 m, 25 mm) to a void at 1 atm. */
    private PassiveNetwork oilWaterOverflow() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(.3,.3,101325)),new PassiveNetwork.Reservoir(2,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(34,1,0,line(10,.025),PhasePort.BULK,PhasePort.LIQUID),pipe(35,0,2,line(10,.025),PhasePort.VAPOR,PhasePort.BULK)));
    }
    /** The phase ids a slice drew through the watched port, by mass. */
    private static int dominant(Slice s){int best=0;for(int p=1;p<3;p++)if(s.out()[p]>s.out()[best])best=p;return best;}
    /**
     * The bottom port draws the water (the heavier liquid) first, then the pentane, then the gas; the top port of a tank
     * a water generator fills draws the gas first, then the pentane (the lighter liquid), then the water. Each phase is
     * drawn only while the tank holds it and before the next one; a step may draw two (the one that empties the first at
     * its capacity and the next with the rest).
     */
    @Test void pentaneOverWaterIsDrawnInEachPortsPriorityOrder() {
        var drain=drive("oil-water-drain",oilWaterDrain(),5,40,33);
        assertOrder("bottom port",drain,new int[]{WATER,OIL,GAS});
        for(var s:drain) {
            // "Gone": no more than the condensate of the cooling headspace (1e-6 of the vessel).
            if(share(s.start(),WATER)>1e-6&&s.out()[1]>0)assertTrue(share(s.end(),WATER)<=1e-6,"bottom slice "+s.index()+": pentane only once the water is gone");
            if(share(s.start(),OIL)+share(s.start(),WATER)>1e-6&&s.out()[0]>0)assertTrue(share(s.end(),OIL)+share(s.end(),WATER)<=1e-6,"bottom slice "+s.index()+": gas only once the liquids are gone");
        }
        var vent=drive("oil-water-overflow",oilWaterOverflow(),5,80,35);
        assertOrder("top port",vent,new int[]{GAS,OIL,WATER});
        for(var s:vent) {
            if(share(s.start(),GAS)>1e-6&&s.out()[1]>0)assertTrue(share(s.end(),GAS)<=1e-6,"top slice "+s.index()+": pentane only once the gas is gone");
            if(share(s.start(),GAS)+share(s.start(),OIL)>1e-6&&s.out()[2]>0)assertTrue(share(s.end(),GAS)+share(s.end(),OIL)<=1e-6,"top slice "+s.index()+": water only once the gas and the pentane are gone");
            assertTrue(s.end().pressure()+s.headEnd()<=300000+1,"top slice "+s.index()+": the tank never stands above the generator");
        }
    }
    /** The phases a port drew, slice by slice, in the order they first appear: exactly {@code expected}; once a phase has
     * begun, an earlier one is drawn only as a trace (below a thousandth of what it gave before: water condensing from a
     * cooling headspace, say), and not at all as the dominant phase of a slice. */
    private void assertOrder(String label,List<Slice> slices,int[] expected) {
        var seen=new ArrayList<Integer>();
        for(var s:slices)for(int p:expected)if(s.out()[p]>0&&!seen.contains(p))seen.add(p);
        System.out.println("PHASE_PORT_PRIORITY_ORDER "+label+" drawn="+seen+" (0 gas, 1 hydrocarbon liquid, 2 water)");
        assertEquals(Arrays.stream(expected).boxed().toList(),seen,label+": the phases in the order they are drawn");
        for(int k=0;k<expected.length-1;k++) {
            int earlier=expected[k],later=expected[k+1];int firstLater=-1;double before=0,worstTrace=0;
            for(var s:slices){if(firstLater<0&&s.out()[later]>0)firstLater=s.index();if(firstLater<0||s.index()==firstLater)before+=s.out()[earlier];}
            for(var s:slices.subList(firstLater+1,slices.size()))worstTrace=Math.max(worstTrace,s.out()[earlier]);
            System.out.println("PHASE_PORT_PRIORITY_ORDER "+label+" phase "+earlier+" gave "+before+" kg before phase "+later+" began (slice "+firstLater+"), at most "+worstTrace+" kg per slice after");
            assertTrue(worstTrace<=1e-3*before,label+": phase "+earlier+" is drawn beyond a trace after phase "+later+" began: "+worstTrace+" kg");
        }
        int previous=0;
        for(var s:slices)if(s.outTotal()>0){int rank=0;for(int k=0;k<expected.length;k++)if(expected[k]==dominant(s))rank=k;
            assertTrue(rank>=previous,label+" slice "+s.index()+": the dominant phase goes back in the order");previous=rank;}
    }

    // ---- 5. A gas-only tank's bottom port vents gas from the first step -------------------------------------------

    /** A nitrogen tank at 200 kPa drained through a LIQUID port (10 m, 20 mm) to a void at 1 atm. */
    @Test void aGasOnlyTanksBottomPortVentsGasFromTheFirstStep() {
        for(double interval:new double[]{5,.1}) {
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,nitrogenAt(200000)),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
            var graph=new PassiveNetwork(nodes,List.of(pipe(36,0,1,line(10,.02),PhasePort.LIQUID,PhasePort.BULK)));
            var slices=drive("gas-only-"+interval,graph,interval,interval<1?20:4,36);
            var first=slices.getFirst();
            System.out.println("PHASE_PORT_PRIORITY_GAS_ONLY interval="+interval+" firstSliceGas="+first.out()[0]+" firstSliceSteps="+first.accepted()+" rejected="+first.rejected());
            assertTrue(first.out()[0]>0,interval+": gas leaves in the first slice");
            assertEquals(0,first.out()[1]+first.out()[2],interval+": nothing but gas");
            assertEquals(0,first.rejected(),interval+": the first slice is not refused");
            for(var s:slices)assertNotEquals(FlowControl.Mode.CLOSED,s.modes().getFirst(),interval+" slice "+s.index()+": never closed");
        }
    }

    // ---- 6. Closed phase lines are bitwise inert on a three-phase vessel -------------------------------------------

    /** The pentane-over-water vessel (0.2 + 0.2 m3 under nitrogen at 200 kPa) with a VAPOR, a LIQUID and a BULK line to
     * voids at 1 atm (10 m, 10 mm), the first two closed both ways: five 1 s steps with the step solver equal, double for
     * double, the same graph with BULK ends on the closed lines (a closed connection draws nothing, decision A10, so it
     * builds no phase stream, decides no segment and costs its node no structural zero). */
    @Test void closedPhaseLinesOnAThreePhaseVesselLeaveTheBulkLineBitwise() {
        var phase=steps(threePhaseOutlets(PhasePort.VAPOR,PhasePort.LIQUID),1,5);
        var bulk=steps(threePhaseOutlets(PhasePort.BULK,PhasePort.BULK),1,5);
        assertEquals(bulk,phase,"closed phase lines must leave the island bitwise");
    }
    private PassiveNetwork threePhaseOutlets(PhasePort vaporLine,PhasePort liquidLine) {
        var sink=nitrogenAt(101325);var g=line(10,.01);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(.2,.2,200000)),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID),
                new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(10,0,1,List.of(g),new FlowControl.Passive(),3,null,vaporLine,PhasePort.BULK),
                new PassiveNetwork.Pipe(11,0,2,List.of(g),new FlowControl.Passive(),3,null,liquidLine,PhasePort.BULK),new PassiveNetwork.Pipe(12,0,3,g)));
    }
    private List<Long> steps(PassiveNetwork graph,double dt,int count) {
        var bits=new ArrayList<Long>();var solver=new PassiveStepSolver(model);
        for(int step=0;step<count;step++) {
            var result=solver.solve(graph,dt,NOOP);
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();
            for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);
                nodes.add(node.fixed()?node:new PassiveNetwork.Reservoir(node.id(),node.elevation(),result.states().get(i),node.kind(),result.inventories().get(i)));}
            graph=new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
            for(var node:graph.reservoirs()){var s=node.state();
                for(double v:new double[]{s.temperature(),s.pressure(),s.mass(),s.volume(),s.liquidVolume(),s.waterVolume(),s.vaporVolume(),node.inventory().internalEnergy()})bits.add(bits(v));
                for(double n:node.inventory().moles())bits.add(bits(n));}
            for(double q:result.massFlows())bits.add(bits(q));
            for(var b:result.boundaries()){bits.add(b.nodeId());bits.add(bits(b.totalEnergyJoule()));for(double n:b.moles())bits.add(bits(n));}
            for(var t:result.pipeTransfers())for(var s:List.of(t.forward(),t.reverse())){bits.add(bits(s.massKg()));for(var p:s.phaseMoles())for(double n:p)bits.add(bits(n));for(double v:s.phaseVolumes())bits.add(bits(v));}
        }
        return bits;
    }
    private static long bits(double v){return Double.doubleToRawLongBits(v==0?0:v);}

    // ---- 7. Two bottom ports share each phase's capacity -----------------------------------------------------------

    /**
     * A 1 m3 tank at 200 kPa holding 0.5 L of water under nitrogen, drained through {@code ports} LIQUID lines (10 m, 10 mm)
     * to voids at 1 atm, one 5 s step: the hydraulics would draw the water faster than the tank holds it, so each line draws
     * the water at its capacity, exactly {@code m_water / (N dt)} of the start state (N the number of lines), and the gas
     * with the rest; the tank ends the step with no free water beyond the condensate of its expanded headspace (less than
     * the water vapour it held).
     */
    @Test void twoBottomPortsShareEachPhasesCapacityEqually() {
        for(int ports=1;ports<=2;ports++) {
            var tank=vessel(0,.0005,200000);
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();nodes.add(new PassiveNetwork.Reservoir(1,0,tank));
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(int i=0;i<ports;i++){nodes.add(new PassiveNetwork.Reservoir(2+i,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
                pipes.add(pipe(70+i,0,1+i,line(10,.01),PhasePort.LIQUID,PhasePort.BULK));}
            double dt=5,capacity=model.phaseMass(tank,WATER)/(ports*dt);
            var result=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,pipes),dt,NOOP);var end=result.states().getFirst();
            double[] flows=result.massFlows();
            for(int i=0;i<ports;i++) {
                var moved=phases(result.pipeTransfers().get(i).forward());
                System.out.println("PHASE_PORT_PRIORITY_CAPACITY ports="+ports+" line="+i+" flow="+flows[i]+" capacity="+capacity+" water="+moved[2]/dt+" gas="+moved[0]/dt+" endWater="+share(end,WATER)
                        +" modes="+result.modes());
                assertEquals(capacity,moved[2]/dt,1e-12*capacity,ports+" ports, line "+i+": the water at its share of the capacity");
                assertTrue(moved[0]>0&&flows[i]>capacity,ports+" ports, line "+i+": the gas makes up the rest of the flow");
                assertEquals(flows[i]*dt,moved[0]+moved[1]+moved[2],1e-12*flows[i]*dt,ports+" ports, line "+i+": the stream is the flow");
            }
            double vapourWater=tank.waterVapor()*mw[water],left=end.waterLiquid()*mw[water];
            System.out.println("PHASE_PORT_PRIORITY_CAPACITY ports="+ports+" startWater="+model.phaseMass(tank,WATER)+" startVapourWater="+vapourWater+" endFreeWater="+left);
            assertTrue(left<vapourWater,ports+" ports: no water left but condensate: "+left+" kg");
        }
    }
}
