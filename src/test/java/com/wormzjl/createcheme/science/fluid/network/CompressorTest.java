package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The gas compressor (decisions D2, D7, D8 of documentation/2026-09-26-phase-ports-and-compressor; plan 3.7): the
 * pump's modes and rows with a pressure-ratio limit {@code (r_max - 1) P_suction}, a suction volume-flow target, its shaft
 * work the ideal isothermal work on suction properties booked as heat into the discharge, and a gas-only inlet. Plan
 * fixtures 6 (on gas: target, limit, shutoff, energy ledger, 5 s against 0.1 s) and 7 (on liquid and two-phase supplies:
 * refused, the island integrating). Each fixture prints a {@code COMPRESSOR_RUN} line; the numbers are recorded in
 * PHASE_PORTS_REVIEW.md, section "WP4".
 */
class CompressorTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=index("Water"),nitrogen=index("Nitrogen");
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10,G=PassiveStepSolver.GRAVITY;
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl control,PhasePort pa){return new PassiveNetwork.Pipe(id,a,b,List.of(g),control,0,null,pa,PhasePort.BULK);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl control){return pipe(id,a,b,g,control,PhasePort.BULK);}
    private int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    private FluidThermodynamics.State nitrogenAt(double pressure){return model.initialNitrogenCharge(1,298.15,pressure,NOOP);}
    private FluidThermodynamics.State waterSupply(double pressure){var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,pressure,n,NOOP);}
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }

    private record Run(List<PassiveIntervalSolver.Result> slices,List<PassiveNetwork> graphs,Map<String,Integer> reasons,int accepted,int rejected,double work) {
        PassiveNetwork last(){return graphs.getLast();}
    }
    /** {@code count} slices of {@code interval}, each a job from the committed interval, the ledgers checked with the
     * compressor's work as the energy it adds. */
    private Run drive(String label,PassiveNetwork graph,double interval,int count) {
        graph=PassiveNetwork.sizeJunctionHoldups(graph,model);
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0,work=0;
        var slices=new ArrayList<PassiveIntervalSolver.Result>();var graphs=new ArrayList<PassiveNetwork>();var reasons=new TreeMap<String,Integer>();int accepted=0,rejected=0;
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance(),label+" slice "+slice);
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice);
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            externalEnergy+=result.pumpWorkJoule();work+=result.pumpWorkJoule();
            var now=owned(graph);
            for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);
                assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
            double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
            assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
            accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            slices.add(result);graphs.add(graph);
        }
        return new Run(slices,graphs,reasons,accepted,rejected,work);
    }
    private double[] owned(PassiveNetwork graph) {
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    private double ownedEnergy(PassiveNetwork graph) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction())
            energy+=node.inventory().internalEnergy()+mass(node.inventory().moles())*G*node.elevation();
        return energy;
    }
    private double mass(double[] moles){double m=0;for(int c=0;c<moles.length;c++)m+=moles[c]*mw[c];return m;}

    // ---- Fixture 6: a compressor on gas ------------------------------------------------------------------------------

    /** Two closed 1 m3 nitrogen tanks at 1 atm and a compressor (0.02 m3/s, ratio 1.5) from the first into the second. */
    private PassiveNetwork transfer() {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,nitrogenAt(101325)),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325))),
                List.of(pipe(10,0,1,line(2,.05),new FlowControl.Compressor(.02,1.5,1))));
    }
    /** On its target the compressor moves its suction volume flow at inlet conditions, {@code q = Q rho_s}, and books the
     * isothermal work {@code q (P_s/rho_s) ln(1 + head/P_s) / eff} of the step (decision D8). */
    @Test void onItsTargetTheFlowIsTheSuctionVolumeFlowAndTheWorkTheIsothermalWork() {
        var graph=transfer();
        var result=new PassiveStepSolver(model).solve(graph,.5,NOOP);var suction=result.states().getFirst();
        double rho=suction.mass()/suction.volume(),q=result.massFlows()[0],head=result.devicePressureChanges()[0];
        assertEquals(FlowControl.Mode.PUMP_TARGET,result.modes().getFirst());
        assertEquals(.02*rho,q,1e-10*q,"q = Q rho_s");
        assertTrue(head>0);
        double expected=.5*q*(suction.pressure()/rho)*Math.log1p(head/suction.pressure());
        assertEquals(expected,result.pumpWorkJoule(),1e-6*expected);
        System.out.println("COMPRESSOR_RUN target q="+q+" rhoS="+rho+" head="+head+" work="+result.pumpWorkJoule()+" formula="+expected);
    }
    /**
     * Plan fixture 6. The transfer at 5 s and at 0.1 s slices: on target, then on its limit, then CLOSED at its shutoff with
     * the discharge at {@code r_max} times the suction, every slice FULL with the ledgers closed (the work entering the
     * energy ledger as the compressor adds it). At both cadences the discharge lands at {@code r_max P_s} within the
     * pump's 3 Pa landing tolerance; the two cadences' end states agree within the bounds the extreme-topology fixtures
     * hold 5 s to 0.1 s by (2 % in pressure, 0.5 K), the difference being backward Euler's first order over the ramp.
     */
    @Test void aTransferRunsOnTargetThenOnItsRatioLimitThenClosesAtShutoffAtEitherCadence() {
        var ends=new ArrayList<PassiveNetwork>();
        for(double interval:new double[]{5,.1}) {
            int count=(int)Math.round(60/interval);
            var run=drive("transfer@"+interval,transfer(),interval,count);
            var modes=new LinkedHashSet<FlowControl.Mode>();for(var s:run.slices())modes.add(s.endpointModes().getFirst());
            var last=run.slices().getLast();var suction=run.last().reservoirs().get(0).state();var discharge=run.last().reservoirs().get(1).state();
            System.out.println("COMPRESSOR_RUN transfer interval="+interval+" modes="+modes+" suction="+suction.pressure()+"Pa/"+suction.temperature()+"K discharge="+discharge.pressure()+"Pa/"+discharge.temperature()
                    +"K ratio="+discharge.pressure()/suction.pressure()+" landing="+(discharge.pressure()-1.5*suction.pressure())+"Pa work="+run.work()+"J accepted="+run.accepted()+" rejected="+run.rejected()+" reasons="+run.reasons());
            assertTrue(modes.contains(FlowControl.Mode.PUMP_TARGET)||interval==5,"on target first: "+modes);
            assertEquals(FlowControl.Mode.CLOSED,last.endpointModes().getFirst(),"closed at its shutoff");assertEquals(0.0,last.averageMassFlows()[0]);
            assertEquals(1.5*suction.pressure(),discharge.pressure(),3,"the discharge at r_max times the suction, within the pump's landing tolerance");
            assertEquals(discharge.pressure()-suction.pressure(),last.endpointHeads()[0],.05,"the closed compressor holds the whole difference");
            assertTrue(discharge.temperature()>suction.temperature()+10,"the compression and the work heat the discharge");
            assertTrue(run.work()>0);
            ends.add(run.last());
        }
        for(int n=0;n<2;n++) {
            var coarse=ends.get(0).reservoirs().get(n).state();var fine=ends.get(1).reservoirs().get(n).state();
            assertEquals(fine.pressure(),coarse.pressure(),.02*fine.pressure(),"5 s against 0.1 s, node "+n+" pressure");
            assertEquals(fine.temperature(),coarse.temperature(),.5,"5 s against 0.1 s, node "+n+" temperature");
        }
    }
    /**
     * The heating of plan 3.7, measured: nitrogen from a generator at 298.15 K and 1 atm compressed into a void at 3 atm
     * through a junction. At steady flow the junction holds what the discharge delivers, the suction enthalpy plus the
     * isothermal work per kilogram, {@code (P_s/rho_s) ln(1 + head/P_s)}; for an ideal gas at ratio 3 that heats it by
     * {@code T (R/(M c_p)) ln 3}, about 93 K (plan 3.7's hand estimate; the ideal adiabatic compression 110 K).
     */
    @Test void theDischargeOfARatioThreeCompressionOfNitrogenIsAboutNinetyKelvinWarmer() {
        var generator=new PassiveNetwork.Reservoir(1,0,nitrogenAt(101325),PassiveNetwork.NodeKind.GENERATOR);
        var graph=new PassiveNetwork(List.of(generator,new PassiveNetwork.Reservoir(2,0,nitrogenAt(3*101325),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(3*101325),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(10,0,1,line(.5,.05),new FlowControl.Compressor(.02,4,1)),pipe(11,1,2,line(.5,.05),new FlowControl.Passive())));
        // 0.02 m3/s: the junction's holdup (0.05 s of its connections' cap flow, 0.034 kg) turns over in 1.5 s; 40 s is steady.
        var run=drive("heating",graph,5,8);
        var junction=run.last().reservoirs().get(1).state();var suction=generator.state();var last=run.slices().getLast();
        double head=last.endpointHeads()[0],perKg=suction.pressure()/(suction.mass()/suction.volume())*Math.log1p(head/suction.pressure());
        double q=last.averageMassFlows()[0];
        System.out.println("COMPRESSOR_RUN heating junction="+junction.temperature()+"K "+junction.pressure()+"Pa head="+head+"Pa ratio="+(1+head/suction.pressure())+" workPerKg="+perKg+" J/kg rise="+(junction.temperature()-298.15)+"K mode="+last.endpointModes().getFirst());
        assertEquals(FlowControl.Mode.PUMP_TARGET,last.endpointModes().getFirst());
        assertEquals(5*q*perKg,last.pumpWorkJoule(),1e-6*last.pumpWorkJoule(),"the slice's work is the isothermal work of its flow");
        double hIn=suction.enthalpy()/suction.mass(),hOut=junction.enthalpy()/junction.mass();
        assertEquals(hIn+perKg,hOut,1e-3*perKg,"the discharge carries the suction's enthalpy plus the work");
        assertEquals(93,junction.temperature()-298.15,5,"about 93 K warmer, the ideal-gas estimate");
    }

    // ---- Fixture 7: a compressor on liquid, two-phase and solids -------------------------------------------------------

    /**
     * Plan fixture 7: a compressor on a water tank's side (its bulk: half water), on a two-phase generator, on a gas tank
     * whose solids exceed the trace fraction and on a wet tank's bottom port is refused ({@code INLET_WRONG_PHASE}) and
     * carries exactly nothing; a compressor on the same wet tank's top port draws its gas and runs; every slice commits
     * FULL at 5 s and at 0.1 s, the island integrating around the refused lines.
     */
    @Test void aCompressorOnLiquidOrTwoPhaseOrSolidsIsRefusedAndTheIslandIntegrates() {
        // A gas tank carrying 1 g of the demo particle: a solid volume fraction far above the 1e-8 trace.
        var dusty=nitrogenAt(150000).withSolids(new SolidInventory(List.of(new SolidInventory.Population(
                model.solids.require("createcheme:demo_particle"),com.wormzjl.createcheme.science.fluid.state.ParticleSize.micrometres("100"),1e-3))));
        for(double interval:new double[]{5,.1}) {
            int count=interval==5?4:40;
            var wet=waterUnderNitrogen(.5,150000);
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,wet),new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.5,200000),PassiveNetwork.NodeKind.GENERATOR),
                    new PassiveNetwork.Reservoir(3,0,dusty),new PassiveNetwork.Reservoir(4,0,nitrogenAt(101325)),new PassiveNetwork.Reservoir(5,0,nitrogenAt(101325)),
                    new PassiveNetwork.Reservoir(6,0,nitrogenAt(101325)),new PassiveNetwork.Reservoir(7,0,nitrogenAt(101325)),new PassiveNetwork.Reservoir(8,0,nitrogenAt(101325)));
            var island=new PassiveNetwork(nodes,List.of(
                    pipe(10,0,3,line(1,.05),new FlowControl.Compressor(.001,3,1)),
                    pipe(11,1,4,line(1,.05),new FlowControl.Compressor(.001,3,1)),
                    pipe(12,2,5,line(1,.05),new FlowControl.Compressor(.001,3,1)),
                    pipe(13,0,6,line(1,.05),new FlowControl.Compressor(.001,3,1),PhasePort.LIQUID),
                    pipe(14,0,7,line(1,.05),new FlowControl.Compressor(.001,3,1),PhasePort.VAPOR)));
            var run=drive("fixture7@"+interval,island,interval,count);
            for(int i=0;i<count;i++) {
                var s=run.slices().get(i);
                for(int edge:new int[]{0,1,2,3}){assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,s.endpointModes().get(edge),"edge "+edge+" refused, slice "+i);assertEquals(0.0,s.averageMassFlows()[edge]);}
                assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,s.endpointModes().get(4),"the top port's gas is admitted");
                assertTrue(s.averageMassFlows()[4]>0,"the top-port compressor runs");
            }
            var reasons=new ArrayList<String>();for(int edge=0;edge<4;edge++)reasons.add(InletPhase.reason(model,island,edge,interval));
            System.out.println("COMPRESSOR_RUN fixture7 interval="+interval+" accepted="+run.accepted()+" rejected="+run.rejected()+" reasons="+run.reasons()+" topFlow="+run.slices().getLast().averageMassFlows()[4]+" texts="+reasons);
            assertTrue(reasons.get(0).startsWith("ERROR: compressor inlet not gas (condensed ")&&reasons.get(0).endsWith("by mass, from node 1)"),reasons.get(0));
            assertTrue(reasons.get(2).startsWith("ERROR: compressor inlet not gas (solids "),reasons.get(2));
        }
    }
    /** The compressor's thresholds (decision D6): refuse above 1 % condensed by mass, resume below 0.2 %; any solid
     * population above the trace volume fraction refuses whatever the condensed share. */
    @Test void theCompressorsThresholds() {
        var compressor=new FlowControl.Compressor(.001,3,1);
        for(double[] c:new double[][]{{.0099,0,0,0},{.0101,0,0,1},{.0099,1,0,1},{.0021,1,0,1},{.0019,1,0,0},{0,0,2e-8,1},{0,0,1e-8,0}})
            assertEquals(c[3]==1,compressor.refuses(new InletPhase.Supply(0,3,PhasePort.BULK,0,c[0],c[2]),c[1]==1),Arrays.toString(c));
        assertEquals("ERROR: compressor inlet not gas (condensed 2.5 % by mass, from node 3)",InletPhase.reason(compressor,new InletPhase.Supply(0,3,PhasePort.BULK,0,.025,0)));
        assertThrows(IllegalArgumentException.class,()->new FlowControl.Compressor(.001,1,1),"a ratio of 1 adds nothing");
    }
}
