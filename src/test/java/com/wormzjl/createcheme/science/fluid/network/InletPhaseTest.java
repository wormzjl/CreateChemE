package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The liquid-only pump (decisions D1, D4, D6 and A37 of documentation/2026-09-26-phase-ports-and-compressor; plan 3.6):
 * the supply walk and its measures ({@link InletPhase}), the refusal {@link FlowControl.Mode#INLET_WRONG_PHASE} decided
 * once per slice on the committed state and the committed endpoint mode, with its hysteresis, and the plan's fixtures 4
 * (refused pumps, the rest of the island bitwise; hysteresis), 5 (a pump draining a tank's bottom port) and 8 (certified
 * replay). Every slice is a job as the island runtime runs it ({@link PassiveIntervalSolver#replayStart} from the committed
 * interval), commits whole, and closes the component ledger to 1e-12 and the energy ledger to 1e-10. Each fixture prints
 * an {@code INLET_PHASE_RUN} line; the numbers are recorded in PHASE_PORTS_REVIEW.md, section "WP3".
 */
class InletPhaseTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=index("Water"),nitrogen=index("Nitrogen");
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10,G=PassiveStepSolver.GRAVITY;
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl control,PhasePort pa,PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(g),control,0,null,pa,pb);
    }
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,FlowControl control){return pipe(id,a,b,g,control,PhasePort.BULK,PhasePort.BULK);}

    private int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen. */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    private FluidThermodynamics.State waterSupply(double pressure){var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,pressure,n,NOOP);}
    private FluidThermodynamics.State nitrogenAt(double pressure){return model.initialNitrogenCharge(1,298.15,pressure,NOOP);}
    /** Vapour share of a state's fluid volume. */
    private static double share(FluidThermodynamics.State s){return s.vaporVolume()/(s.vaporVolume()+s.liquidVolume()+s.waterVolume());}
    /** Water with nitrogen at 298.15 K and {@code pressure} whose vapour fills {@code target} of its fluid volume (bisection
     * on the nitrogen amount; 1 m3 of water). */
    private FluidThermodynamics.State twoPhase(double target,double pressure) {
        double[] n=new double[mw.length];n[water]=997/mw[water];
        if(target==0)return model.flashTP(298.15,pressure,n,NOOP);
        double lo=0,hi=1;
        while(true){n[nitrogen]=hi;if(share(model.flashTP(298.15,pressure,n,NOOP))>target)break;hi*=2;}
        for(int i=0;i<80;i++){n[nitrogen]=.5*(lo+hi);if(share(model.flashTP(298.15,pressure,n,NOOP))>target)hi=n[nitrogen];else lo=n[nitrogen];}
        n[nitrogen]=.5*(lo+hi);return model.flashTP(298.15,pressure,n,NOOP);
    }

    /** Committed slices of one run. */
    private record Run(List<PassiveIntervalSolver.Result> slices,List<PassiveNetwork> graphs,Map<String,Integer> reasons,int accepted,int rejected) {
        PassiveNetwork last(){return graphs.getLast();}
    }
    private Run drive(String label,PassiveNetwork graph,double interval,int count){return drive(label,graph,interval,count,new PassiveIntervalSolver(model),null);}
    /** {@code count} slices of {@code interval}, each a job from the committed interval {@code committed}, ledgers checked. */
    private Run drive(String label,PassiveNetwork graph,double interval,int count,PassiveIntervalSolver solver,PassiveIntervalSolver.Result committed) {
        graph=PassiveNetwork.sizeJunctionHoldups(graph,model);
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0;
        var slices=new ArrayList<PassiveIntervalSolver.Result>();var graphs=new ArrayList<PassiveNetwork>();var reasons=new TreeMap<String,Integer>();int accepted=0,rejected=0;
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance(),label+" slice "+slice);
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice+": the whole slice commits");
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            externalEnergy+=result.pumpWorkJoule();
            var now=owned(graph);
            for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);
                assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
            double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
            assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
            accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            slices.add(result);graphs.add(graph);
        }
        return new Run(slices,graphs,reasons,accepted,rejected);
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
    private static void assertSameDoubles(String label,PassiveIntervalSolver.Result a,PassiveIntervalSolver.Result b,int nodes,int edges) {
        for(int e=0;e<edges;e++)assertEquals(Double.doubleToLongBits(a.averageMassFlows()[e]),Double.doubleToLongBits(b.averageMassFlows()[e]),label+" edge "+e+" flow");
        assertEquals(a.acceptedSubsteps(),b.acceptedSubsteps(),label+" accepted");
        for(int n=0;n<nodes;n++) {
            var x=a.graph().reservoirs().get(n);var y=b.graph().reservoirs().get(n);
            assertEquals(Double.doubleToLongBits(x.state().pressure()),Double.doubleToLongBits(y.state().pressure()),label+" node "+n+" pressure");
            assertEquals(Double.doubleToLongBits(x.state().temperature()),Double.doubleToLongBits(y.state().temperature()),label+" node "+n+" temperature");
            assertArrayEquals(x.inventory().moles(),y.inventory().moles(),label+" node "+n+" moles");
            assertEquals(Double.doubleToLongBits(x.inventory().internalEnergy()),Double.doubleToLongBits(y.inventory().internalEnergy()),label+" node "+n+" energy");
        }
    }

    // ---- The supply walk and its measures ---------------------------------------------------------------------------

    /**
     * The walk reads the source, not the pump's own junction: through its junction and every degree-two passive junction
     * to the vessel, whose BULK end supplies its bulk; a branch junction supplies its own mixture; a junction an actuator
     * touches ends the walk. A phase port supplies its priority draw at the pump's target over the slice (decision A37):
     * a bottom port on plenty of water reads water, a top port gas, a dry bottom port gas, a bottom port holding less
     * water than one slice of the target reads the gas it would break through to; with no target, the leading phase.
     */
    @Test void theSupplyWalkReadsTheSourceThroughDegreeTwoJunctionsAndAPortsDraw() {
        var vessel=waterUnderNitrogen(.5,150000);double vesselShare=share(vessel);
        var junctionSeed=waterSupply(150000);
        // vessel 0 - J1 - J2 - pump - void 3, and a second void 4 hanging off J1 when branched.
        for(boolean branched:new boolean[]{false,true}) {
            var nodes=new ArrayList<>(List.of(new PassiveNetwork.Reservoir(10,0,vessel),new PassiveNetwork.Reservoir(11,0,junctionSeed,PassiveNetwork.NodeKind.JUNCTION),
                    new PassiveNetwork.Reservoir(12,0,junctionSeed,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(13,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)));
            var pipes=new ArrayList<>(List.of(pipe(1,0,1,line(1,.05),new FlowControl.Passive()),pipe(2,2,1,line(1,.05),new FlowControl.Passive()),pipe(3,2,3,line(1,.05),new FlowControl.Pump(.001,500000,1))));
            if(branched){nodes.add(new PassiveNetwork.Reservoir(14,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));pipes.add(pipe(4,1,4,line(1,.05),new FlowControl.Passive()));}
            var graph=new PassiveNetwork(nodes,pipes);
            var supply=InletPhase.supply(model,graph,2,.001,5);
            if(branched){assertEquals(11,supply.nodeId(),"a branch junction ends the walk");assertEquals(0,supply.vapourVolumeShare(),"the junction's own (liquid) mixture");}
            else{assertEquals(10,supply.nodeId(),"the walk reaches the vessel");assertEquals(vesselShare,supply.vapourVolumeShare(),1e-15,"its bulk");}
            assertEquals(PhasePort.BULK,supply.port());
        }
        // A valve upstream of the pump's junction ends the walk there: it is not a passive link.
        var valved=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(10,0,vessel),new PassiveNetwork.Reservoir(11,0,junctionSeed,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(12,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(1,0,1,line(1,.05),new FlowControl.PressureValve(120000)),pipe(2,1,2,line(1,.05),new FlowControl.Pump(.001,500000,1))));
        assertEquals(11,InletPhase.supply(model,valved,1,.001,5).nodeId());
        // Ports on a vessel pumped directly: bottom on plenty of water, top, a dry bottom, a bottom short of one slice.
        var dry=nitrogenAt(150000);var shortOfWater=waterUnderNitrogen(.004,150000);
        for(var c:List.of(Map.entry("bottom",vessel),Map.entry("top",vessel),Map.entry("dry bottom",dry),Map.entry("short bottom",shortOfWater))) {
            var port=c.getKey().equals("top")?PhasePort.VAPOR:PhasePort.LIQUID;
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(10,0,c.getValue()),new PassiveNetwork.Reservoir(13,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                    List.of(pipe(3,0,1,line(1,.05),new FlowControl.Pump(.001,500000,1),port,PhasePort.BULK)));
            var supply=InletPhase.supply(model,graph,0,.001,5);var leading=InletPhase.supply(model,graph,0,.001,0);
            System.out.println("INLET_PHASE_RUN walk "+c.getKey()+" vapourShare="+supply.vapourVolumeShare()+" leading="+leading.vapourVolumeShare());
            switch(c.getKey()) {
                case "bottom"->{assertEquals(0,supply.vapourVolumeShare());assertEquals(0,leading.vapourVolumeShare());}
                case "top","dry bottom"->{assertEquals(1,supply.vapourVolumeShare());assertEquals(1,leading.vapourVolumeShare());}
                default->{
                    // 4 L of water against 5 L of target over the slice: the draw is 1 L of gas in 5 (by the fluid volumes).
                    double waterVolume=shortOfWater.waterVolume();
                    assertEquals((.005-waterVolume)/.005,supply.vapourVolumeShare(),1e-12,"the draw breaks through to gas");
                    assertEquals(0,leading.vapourVolumeShare(),"the leading phase alone reads water");
                }
            }
        }
    }

    /** The decision of D6 on the measure alone: refuse above 2 % vapour by volume; a pump refused at the committed slice end
     * stays refused until below 0.5 %; the reason names the share and the source node. */
    @Test void thePumpsThresholdsAndReason() {
        var pump=new FlowControl.Pump(.001,500000,1);
        for(double[] c:new double[][]{{.0199,0,0},{.0201,0,1},{.0199,1,1},{.0051,1,1},{.0049,1,0},{.0049,0,0},{0,1,0}})
            assertEquals(c[2]==1,pump.refuses(new InletPhase.Supply(0,7,PhasePort.BULK,c[0],0,0),c[1]==1),Arrays.toString(c));
        assertEquals("ERROR: pump inlet not liquid (vapour 34.0 % by volume, from node 7)",InletPhase.reason(pump,new InletPhase.Supply(0,7,PhasePort.BULK,.34,0,0)));
    }

    // ---- Fixture 4: refused pumps; the rest of the island bitwise; hysteresis ---------------------------------------

    /**
     * Plan fixture 4. One island (one graph): an independent water line (a 300 kPa water generator filling a nitrogen
     * tank), a pump between two nitrogen tanks, and a pump on a two-phase generator (30 % vapour by volume) into a
     * nitrogen tank. Both pumps are refused ({@code INLET_WRONG_PHASE}), carry exactly zero, every slice commits FULL (no
     * hold), at 5 s and 0.1 s. The water line integrates bit for bit as in the same island whose nitrogen line is a
     * compressor closed both ways (the existing closed-device path, mode CLOSED): a refusal is exactly a closed device.
     * Against the water line alone (no device lines at all) it is bit for bit at 5 s; at 0.1 s a closed device's head
     * unknown moves the water line's flow by a few units in the last place in some slices, as the pre-existing
     * closed-device path does (measured: the refused and the closed-compressor islands agree bitwise, both 1-2 ulp from
     * the line alone, drifting to a few ulp over 60 slices), so that comparison is held to 1e-13 relative.
     */
    @Test void refusedPumpsCarryNothingAndTheIslandsWaterLineIntegratesBitwise() {
        for(double interval:new double[]{5,.1}) {
            int count=interval==5?6:60;
            var waterLine=List.of(new PassiveNetwork.Reservoir(1,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325)));
            var alone=new PassiveNetwork(waterLine,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive())));
            var nodes=new ArrayList<>(waterLine);
            nodes.add(new PassiveNetwork.Reservoir(3,0,nitrogenAt(200000)));nodes.add(new PassiveNetwork.Reservoir(4,0,nitrogenAt(101325)));
            var twoPhase=twoPhase(.3,150000);
            nodes.add(new PassiveNetwork.Reservoir(5,0,twoPhase,PassiveNetwork.NodeKind.GENERATOR));nodes.add(new PassiveNetwork.Reservoir(6,0,nitrogenAt(101325)));
            var island=new PassiveNetwork(nodes,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive()),
                    pipe(11,2,3,line(1,.05),new FlowControl.Pump(.01,500000,1)),pipe(12,4,5,line(1,.05),new FlowControl.Pump(.001,500000,1))));
            // The control: the nitrogen line's device a compressor (a gas is its phase) closed both ways; the two-phase line as is.
            var closedControl=new PassiveNetwork(nodes,List.of(pipe(10,0,1,line(10,.02),new FlowControl.Passive()),
                    pipe(11,2,3,line(1,.05),new FlowControl.Compressor(.01,1.5,1)).withBlockedDirections(3),pipe(12,4,5,line(1,.05),new FlowControl.Pump(.001,500000,1))));
            var reference=drive("alone@"+interval,alone,interval,count);var run=drive("island@"+interval,island,interval,count);
            var control=drive("closed@"+interval,closedControl,interval,count);
            int worstUlps=0;
            for(int i=0;i<count;i++) {
                var s=run.slices().get(i);
                assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,s.endpointModes().get(1),"nitrogen pump refused, slice "+i);
                assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,s.endpointModes().get(2),"two-phase pump refused, slice "+i);
                assertEquals(0.0,s.averageMassFlows()[1]);assertEquals(0.0,s.averageMassFlows()[2]);assertEquals(0.0,s.pumpWorkJoule());
                assertEquals(FlowControl.Mode.CLOSED,control.slices().get(i).endpointModes().get(1),"the control's compressor is closed, not refused");
                assertSameDoubles("water line vs the closed-device island @"+interval+" slice "+i,control.slices().get(i),s,2,1);
                if(interval==5)assertSameDoubles("water line vs the line alone @"+interval+" slice "+i,reference.slices().get(i),s,2,1);
                else{double a=reference.slices().get(i).averageMassFlows()[0],b=s.averageMassFlows()[0];
                    worstUlps=Math.max(worstUlps,(int)Math.abs(Double.doubleToLongBits(a)-Double.doubleToLongBits(b)));
                    assertEquals(a,b,1e-13*Math.abs(a),"water line vs the line alone @"+interval+" slice "+i);
                    var x=reference.slices().get(i).graph().reservoirs().get(1).state();var y=s.graph().reservoirs().get(1).state();
                    assertEquals(x.pressure(),y.pressure(),1e-13*x.pressure());assertEquals(x.temperature(),y.temperature(),1e-13*x.temperature());}
            }
            assertTrue(run.slices().getLast().averageMassFlows()[0]>0,"the water line flows");
            var n2=run.last().reservoirs().get(2);assertArrayEquals(nodes.get(2).inventory().moles(),n2.inventory().moles(),"the suction tank keeps its nitrogen");
            System.out.println("INLET_PHASE_RUN fixture4 interval="+interval+" slices="+count+" worstUlpsVsAlone="+worstUlps+" accepted="+run.accepted()+"/"+reference.accepted()+" rejected="+run.rejected()+"/"+reference.rejected()
                    +" reasons="+run.reasons()+" waterLineFlow="+run.slices().getLast().averageMassFlows()[0]+" twoPhaseShare="+share(twoPhase)
                    +" reasons: "+InletPhase.reason(model,island,1,interval)+" | "+InletPhase.reason(model,island,2,interval));
        }
    }

    /**
     * Plan fixture 4, hysteresis: a pump on a generator whose supply is swept 0 % to 5 % vapour by volume and back, one 5 s
     * slice per step; the generator's state changes between slices (an input change) and each slice is a job from the
     * committed interval. It refuses first at 2.1 % (not at 1.9 %) and runs again first at 0.4 % (not at 0.6 %, nor
     * anywhere in between).
     */
    @Test void aSweptSupplyRefusesAboveTwoPercentAndResumesBelowHalfAPercent() {
        double[] sweep={0,.01,.019,.021,.03,.05,.03,.01,.006,.004,0};
        boolean[] expected={false,false,false,true,true,true,true,true,true,false,false};
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        PassiveNetwork.Reservoir tank=new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.2,101325));
        var refused=new ArrayList<Boolean>();
        for(int i=0;i<sweep.length;i++) {
            var generator=new PassiveNetwork.Reservoir(1,0,twoPhase(sweep[i],150000),PassiveNetwork.NodeKind.GENERATOR);
            var graph=new PassiveNetwork(List.of(generator,tank),List.of(pipe(10,0,1,line(2,.03),new FlowControl.Pump(.0005,500000,1))));
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;tank=result.graph().reservoirs().get(1);
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance());
            boolean off=result.endpointModes().getFirst()==FlowControl.Mode.INLET_WRONG_PHASE;refused.add(off);
            if(off)assertEquals(0.0,result.averageMassFlows()[0]);else assertTrue(result.averageMassFlows()[0]>0,"runs at "+sweep[i]);
        }
        System.out.println("INLET_PHASE_RUN hysteresis sweep="+Arrays.toString(sweep)+" refused="+refused);
        for(int i=0;i<sweep.length;i++)assertEquals(expected[i],refused.get(i),"slice "+i+" at "+sweep[i]);
    }

    // ---- Fixture 5: a pump drains a tank's bottom port ---------------------------------------------------------------

    /**
     * Plan fixture 5 (under decision D11 and default A37): a pump draws water from a tank's bottom (LIQUID) port and lifts
     * it into a void 30 kPa above the tank. The tank holds 2.99 slices of the pump's target volume. The pump runs on its
     * target for two slices; the third starts with 0.99 of a slice of water (the draw over the slice is 1 % gas, admitted),
     * and within it the port's water runs out and the draw turns to gas; from the fourth slice on the supply is gas and the
     * pump is refused ({@code INLET_WRONG_PHASE}), with no held slice.
     */
    @Test void aPumpOnABottomPortRunsThenMeetsGasAndIsRefusedAtTheNextSlice() {
        double target=.002,interval=5;
        // The tank's water volume made exactly 2.99 slices of the target (the flash moves it from the charged volume).
        double charged=2.99*target*interval;var tankState=waterUnderNitrogen(charged,120000);
        for(int i=0;i<3;i++)tankState=waterUnderNitrogen(charged*=2.99*target*interval/tankState.waterVolume(),120000);
        var start=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,tankState),
                new PassiveNetwork.Reservoir(2,0,nitrogenAt(150000),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(10,0,1,line(5,.025),new FlowControl.Pump(target,500000,1),PhasePort.LIQUID,PhasePort.BULK)));
        var run=drive("bottom drain",start,interval,8);
        var modes=new ArrayList<String>();var drawn=new ArrayList<Double>();
        for(var s:run.slices()){modes.add(String.valueOf(s.endpointModes().getFirst()));drawn.add(s.averageMassFlows()[0]*interval);}
        var tank=run.last().reservoirs().getFirst().state();
        System.out.println("INLET_PHASE_RUN fixture5 modes="+modes+" drawnKg="+drawn+" tankWater="+tank.waterLiquid()*mw[water]+"kg accepted="+run.accepted()+" rejected="+run.rejected()+" reasons="+run.reasons());
        assertEquals(FlowControl.Mode.PUMP_TARGET,run.slices().get(0).endpointModes().getFirst());
        assertEquals(FlowControl.Mode.PUMP_TARGET,run.slices().get(1).endpointModes().getFirst());
        for(int i=0;i<2;i++)assertEquals(target*interval*997,drawn.get(i),.01*target*interval*997,"slice "+i+" on target");
        assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,run.slices().get(2).endpointModes().getFirst(),"admitted with 1 % of the slice's draw gas");
        for(int i=3;i<8;i++){assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,run.slices().get(i).endpointModes().getFirst(),"refused from slice 3: "+modes);assertEquals(0.0,drawn.get(i));}
        assertTrue(tank.waterVolume()<1e-6,"the port drew the water");
    }

    // ---- Fixture 8: certified replay with a refused pump --------------------------------------------------------------

    /**
     * Plan fixture 8: a pump refused on a vessel whose gas cushion a water generator compresses, so its supply's vapour
     * share falls from 3 % into the hysteresis band (0.5 % to 2 %); the refusal holds there only because the committed
     * endpoint mode says it was refused. A fresh solver started from the committed interval of slice k
     * ({@link PassiveIntervalSolver#replayStart}) reproduces the island that solved every interval bit for bit; started
     * without the committed interval, a fresh solver in the band admits the pump instead (the input the replay needs).
     */
    @Test void aFreshSolverReplaysARefusedPumpBitwise() {
        var vessel=new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.97,101325));
        var start=new PassiveNetwork(List.of(vessel,new PassiveNetwork.Reservoir(2,0,waterSupply(600000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                List.of(pipe(10,1,0,line(10,.01),new FlowControl.Passive()),pipe(11,0,2,line(2,.02),new FlowControl.Pump(.0001,500000,1))));
        var reference=drive("replay",start,5,12);
        var shares=new ArrayList<Double>();for(var g:reference.graphs())shares.add(share(g.reservoirs().getFirst().state()));
        System.out.println("INLET_PHASE_RUN fixture8 shares="+shares+" modes="+reference.slices().stream().map(s->s.endpointModes().get(1)).toList()+" accepted="+reference.accepted()+" rejected="+reference.rejected());
        int band=-1;
        for(int i=0;i<reference.slices().size();i++) {
            assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,reference.slices().get(i).endpointModes().get(1),"refused throughout, slice "+i);
            if(band<0&&shares.get(i)<FlowControl.Pump.VAPOUR_REFUSE&&shares.get(i)>FlowControl.Pump.VAPOUR_RESUME)band=i;
        }
        assertTrue(band>=0&&band<11,"the supply enters the hysteresis band: "+shares);
        for(int resume:new int[]{1,band+1}) {
            var resumed=drive("replay-from-"+resume,reference.graphs().get(resume-1),5,12-resume,new PassiveIntervalSolver(model),reference.slices().get(resume-1));
            for(int i=0;i<resumed.slices().size();i++)assertSameDoubles("resume "+resume+" slice "+(resume+i),reference.slices().get(resume+i),resumed.slices().get(i),3,2);
            for(int i=0;i<resumed.slices().size();i++)assertEquals(reference.slices().get(resume+i).endpointModes(),resumed.slices().get(i).endpointModes());
        }
        var forgetful=drive("no-committed-interval",reference.graphs().get(band),5,1);
        assertNotEquals(FlowControl.Mode.INLET_WRONG_PHASE,forgetful.slices().getFirst().endpointModes().get(1),"without the committed mode the band admits the pump");
    }
}
