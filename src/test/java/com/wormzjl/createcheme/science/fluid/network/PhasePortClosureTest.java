package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures 2 and 3 of documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_PLAN.md section 6 (work package
 * WP2, the "outlet phase absent" state of plan 3.4): a phase port carries outflow only while its phase fills at least
 * {@link PassiveStepSolver#PHASE_PORT_OPEN} of its vessel on the state a step starts from, an open port's outflow is
 * throttled so that one step never draws its phase below {@link PassiveStepSolver#PHASE_PORT_RESERVE}, inflow through a
 * closed port is never refused, and the boundary-reopen retry counts the port refusal as one more per-link allowance.
 *
 * <p>Every committed slice closes the component ledger to 1e-12 and the energy ledger to 1e-10 (relative to
 * max(1, initial); owned inventories - vessels and junction holdups - against the boundaries), commits the whole slice
 * (no held slice) and is a job as the island runtime runs it ({@link PassiveIntervalSolver#replayStart} from the
 * committed interval), at the probe's 0.1 s slices and at the product's 5 s slices. Each fixture prints one line per
 * slice ({@code PHASE_PORT_CLOSURE_SLICE}: the watched port's phase share at the slice start and end, the vessel
 * pressure, what left and entered through the port, accepted and rejected steps, boundary reopens, the rejection map)
 * and one summary line; the measured numbers are in PHASE_PORTS_REVIEW.md, WP2.
 */
class PhasePortClosureTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=index("Water"),nitrogen=index("Nitrogen"),methane=index("Methane"),pentane=index("N-pentane");
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private static final double OPEN=PassiveStepSolver.PHASE_PORT_OPEN,RESERVE=PassiveStepSolver.PHASE_PORT_RESERVE;
    /** How far below the reserve the flash may move a drained phase over one step on these fixtures: water evaporating
     * into a headspace that grows by at most a tenth of the vessel is about 2e-6 of the vessel volume. */
    private static final double FLASH_DRIFT=1e-5;
    private static final String REOPEN="Backward-Euler boundary reopened";
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}

    private int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen (0: gas only),
     * flashed and scaled to exactly the vessel volume. */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    private FluidThermodynamics.State waterSupply(double pressure) {
        var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,pressure,n,NOOP);
    }
    private FluidThermodynamics.State nitrogenSink(){return model.initialNitrogenCharge(1,298.15,101325,NOOP);}

    /** One committed slice as the fixtures read it; {@code modes} are the slice's endpoint modes, {@code pressures} every
     * node's pressure at the slice end. */
    private record Slice(int index,double phiStart,double phiEnd,double pressureStart,double pressureEnd,double out,double in,
                         int accepted,int rejected,int reopens,Map<String,Integer> reasons,List<FlowControl.Mode> modes,double[] pressures) {
        boolean openAtStart(){return phiStart>=OPEN;}
    }
    /** {@code count} slices of {@code interval}; before slice {@code changeAt} the graph is passed through {@code change}.
     * The port under watch is the phase-port end of connection {@code watched}. */
    private List<Slice> drive(String label,PassiveNetwork graph,double interval,int count,long watched,int changeAt,UnaryOperator<PassiveNetwork> change) {
        int edge=-1;for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).id()==watched)edge=i;
        var pipe=graph.pipes().get(edge);int vessel=pipe.firstPort()!=PhasePort.BULK?pipe.first():pipe.second();var port=pipe.portAt(vessel);
        boolean vesselFirst=vessel==pipe.first();
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0;
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;var slices=new ArrayList<Slice>();
        for(int slice=0;slice<count;slice++) {
            if(slice==changeAt&&change!=null)graph=change.apply(graph);
            var start=graph.reservoirs().get(vessel).state();
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance(),label+" slice "+slice);
            assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice+": the whole slice commits");
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            var now=owned(graph);
            for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);worstMoles=Math.max(worstMoles,error);
                assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
            double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));worstEnergy=Math.max(worstEnergy,error);
            assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
            var transfer=result.pipeTransfers().stream().filter(t->t.pipeId()==watched).findFirst().orElseThrow();
            double out=vesselFirst?transfer.forward().massKg():transfer.reverse().massKg(),in=vesselFirst?transfer.reverse().massKg():transfer.forward().massKg();
            var end=graph.reservoirs().get(vessel).state();
            double[] pressures=graph.reservoirs().stream().mapToDouble(n->n.state().pressure()).toArray();
            slices.add(new Slice(slice,PassiveStepSolver.portPhaseShare(start,port),PassiveStepSolver.portPhaseShare(end,port),start.pressure(),end.pressure(),out,in,
                    result.acceptedSubsteps(),result.rejectedSubsteps(),result.rejectionReasons().getOrDefault(REOPEN,0),result.rejectionReasons(),result.endpointModes(),pressures));
        }
        int rejected=0,reopens=0,worst=0;var reasons=new TreeMap<String,Integer>();
        for(var s:slices) {
            rejected+=s.rejected();reopens+=s.reopens();worst=Math.max(worst,s.rejected());s.reasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            System.out.println("PHASE_PORT_CLOSURE_SLICE "+label+" "+s.index()+" phiStart="+s.phiStart()+" phiEnd="+s.phiEnd()+" P="+s.pressureStart()+"->"+s.pressureEnd()
                    +" out="+s.out()+" in="+s.in()+" accepted="+s.accepted()+" rejected="+s.rejected()+" reopens="+s.reopens()+" reasons="+s.reasons()+" modes="+s.modes());
        }
        System.out.println("PHASE_PORT_CLOSURE_RUN "+label+" slices="+count+" accepted="+slices.stream().mapToInt(Slice::accepted).sum()+" rejected="+rejected
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
    private double mass(double[] moles){double m=0;for(int c=0;c<moles.length;c++)m+=moles[c]*mw[c];return m;}
    /** The first slice whose start finds the watched port closed, after at least one that found it open; asserts the
     * port was open at every slice start before it, closed at every one after it (at most one open-to-closed transition,
     * no reopening), and carried no outflow from it on. */
    private int closure(String label,List<Slice> slices,int watchedEdge) {
        int closed=-1;
        for(var s:slices)if(!s.openAtStart()){closed=s.index();break;}
        assertTrue(closed>0,label+": the port must start open and close");
        int transitions=0;
        for(int i=1;i<slices.size();i++)if(slices.get(i-1).openAtStart()!=slices.get(i).openAtStart())transitions++;
        assertEquals(1,transitions,label+": one open-to-closed transition and no reopening");
        for(var s:slices.subList(closed,slices.size())) {
            assertEquals(0,s.out(),label+" slice "+s.index()+": a closed port carries no outflow");
            assertEquals(FlowControl.Mode.CLOSED,s.modes().get(watchedEdge),label+" slice "+s.index()+": the closed line's endpoint mode");
        }
        return closed;
    }

    // ---- Fixture 2: outlet runs dry -------------------------------------------------------------------------------

    /** 0.1 m3 of water under nitrogen at 200 kPa, drained through a LIQUID port (10 m, 25 mm) to a void at 1 atm. */
    private PassiveNetwork liquidDrain() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.1,200000)),new PassiveNetwork.Reservoir(2,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(30,0,1,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK)));
    }
    @Test void aLiquidPortDrainsDryAndStaysClosedAtFiveSecondSlices(){outletRunsDry(5,40);}
    @Test void aLiquidPortDrainsDryAndStaysClosedAtTenthSecondSlices(){outletRunsDry(.1,1450);}
    /**
     * The port drains until a step starts with the water below {@code phi_open}; that step and every later one hold it
     * closed. The water left is at or above the reserve (the throttle's floor, less the flash) and below {@code phi_open}:
     * with the state-change controller's short steps no single step spans the band, so the port closes at the first step
     * start below 1 % (0.99 % here) rather than on the reserve (the throttle's own landing is
     * {@link #theThrottleLandsAnOpenPortAtItsReserveInOneStep}). The closed vessel then stands still for a hundred seconds.
     */
    private void outletRunsDry(double interval,int count) {
        String label="dry-"+interval;
        var slices=drive(label,liquidDrain(),interval,count,30,-1,null);
        int closed=closure(label,slices,0);
        for(var s:slices.subList(0,closed))assertTrue(s.out()>0&&s.in()==0,label+" slice "+s.index()+": the open port drains");
        double landed=slices.get(closed).phiStart();
        System.out.println("PHASE_PORT_DRY interval="+interval+" closedAtSlice="+closed+" landed="+landed+" final="+slices.getLast().phiEnd()+" closedSlices="+(count-closed));
        assertTrue(landed>=RESERVE-FLASH_DRIFT&&landed<OPEN,label+": the water left, "+landed);
        assertTrue((count-closed)*interval>=100,label+": closed for at least twenty 5 s slices' worth of time");
        for(var s:slices.subList(closed,count))assertEquals(landed,s.phiEnd(),FLASH_DRIFT,label+" slice "+s.index()+": the closed vessel keeps its water");
    }

    /** 0.8 m3 of water under nitrogen at 1 atm, filled from a 300 kPa water generator through its LIQUID port (inflow;
     * 10 m, 25 mm) and vented through a VAPOR port (10 m, 25 mm) to a void at 1 atm. */
    private PassiveNetwork vaporSqueeze() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.8,101325)),new PassiveNetwork.Reservoir(2,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(31,1,0,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.BULK,PhasePort.LIQUID),
                new PassiveNetwork.Pipe(32,0,2,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.VAPOR,PhasePort.BULK)));
    }
    @Test void aVaporPortIsSqueezedShutByLiquidAtFiveSecondSlices(){vaporSqueezed(5,40);}
    @Test void aVaporPortIsSqueezedShutByLiquidAtTenthSecondSlices(){vaporSqueezed(.1,900);}
    /**
     * Water fills the vessel and pushes its nitrogen out of the top port until a step starts with the gas below
     * {@code phi_open}; the port then stays closed, the vessel has no outflow left, and the generator compresses the gas
     * cushion that stays (nitrogen does not dissolve in free water) until the vessel stands at the generator's pressure:
     * the liquid-full vessel's pressure rise of plan 3.4. At 0.1 s the gas at the closing step start is at or above the
     * reserve; at 5 s the closing slice already compresses it below (compression, not a draw).
     */
    private void vaporSqueezed(double interval,int count) {
        String label="squeeze-"+interval;
        var slices=drive(label,vaporSqueeze(),interval,count,32,-1,null);
        int closed=closure(label,slices,1);
        double landed=slices.get(closed).phiStart(),finalPressure=slices.getLast().pressureEnd();
        System.out.println("PHASE_PORT_SQUEEZE interval="+interval+" closedAtSlice="+closed+" gasAtClosingStart="+landed+" pressureAtClosingStart="+slices.get(closed).pressureStart()
                +" finalGas="+slices.getLast().phiEnd()+" finalPressure="+finalPressure);
        assertTrue(landed<OPEN&&landed>0,label+": closed with gas left, "+landed);
        if(interval<1)assertTrue(landed>=RESERVE-FLASH_DRIFT,label+": the vent never drew the gas below its reserve, "+landed);
        assertEquals(300000,finalPressure,1,label+": the closed vessel rises to the generator's pressure");
        assertTrue(slices.getLast().phiEnd()>0&&slices.getLast().phiEnd()<landed,label+": the cushion is compressed, not vented");
    }

    // ---- Fixture 3: inflow through a closed port -------------------------------------------------------------------

    /** A gas-only tank (nitrogen, 150 kPa) whose LIQUID port faces a 130 kPa water generator (10 m, 25 mm) and which
     * vents through a BULK line (10 m, 10 mm) to a void at 1 atm. */
    private PassiveNetwork closedPortInflow() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(0,150000)),new PassiveNetwork.Reservoir(2,0,waterSupply(130000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(40,1,0,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.BULK,PhasePort.LIQUID),
                new PassiveNetwork.Pipe(41,0,2,line(10,.01))));
    }
    @Test void inflowOpensAClosedLiquidPortPartWayThroughAStepAtFiveSecondSlices(){closedPortInflow(5,12);}
    @Test void inflowOpensAClosedLiquidPortPartWayThroughAStepAtTenthSecondSlices(){closedPortInflow(.1,600);}
    /**
     * The generator line starts dead-headed ({@code closeDeadHeads}: its only admissible direction, into the tank, is not
     * driven, and the port refuses the other) and must open inside the step in which the venting tank falls below the
     * generator: the BROKEN class 3' of review 8.8 (c), through a closed port. No slice may end bottled - the tank more
     * than the reopen band below the generator with nothing entering - and the port never carries outflow.
     */
    private void closedPortInflow(double interval,int count) {
        String label="inflow-"+interval;
        var slices=drive(label,closedPortInflow(),interval,count,40,-1,null);
        int first=-1;
        for(var s:slices) {
            assertEquals(0,s.out(),label+" slice "+s.index()+": nothing leaves through the port");
            boolean below=s.pressureEnd()<130000-PassiveStepSolver.reopenBand(130000,s.pressureEnd());
            if(below)assertTrue(s.in()>0,label+" slice "+s.index()+": bottled, the tank ends below the generator with the port shut");
            if(first<0&&s.in()>0)first=s.index();
            if(first<0)assertEquals(FlowControl.Mode.CLOSED,s.modes().getFirst(),label+" slice "+s.index()+": dead-headed while the tank stands above the generator");
        }
        assertTrue(first>0,label+": water enters once the tank falls below the generator");
        System.out.println("PHASE_PORT_INFLOW interval="+interval+" firstInflowSlice="+first+" reopensThere="+slices.get(first).reopens()+" rejectedThere="+slices.get(first).rejected()
                +" waterShareAtEnd="+slices.getLast().phiEnd());
        assertTrue(slices.get(first).reopens()>=1,label+": the dead-headed run is opened by the reopen retry");
        for(var s:slices.subList(first,count))assertTrue(s.in()>0,label+" slice "+s.index()+": water keeps entering");
    }

    /** Two gas-only tanks: A (150 kPa) joined through its LIQUID port (10 m, 25 mm) to B (120 kPa, BULK end), B fed
     * nitrogen from a 200 kPa generator (10 m, 10 mm). The port is the only link refusing A's outflow. */
    private PassiveNetwork portOnlyRefusal() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(0,150000)),new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(0,120000)),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,200000,NOOP),PassiveNetwork.NodeKind.GENERATOR));
        return new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(60,0,1,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK),
                new PassiveNetwork.Pipe(61,2,1,line(10,.01))));
    }
    @Test void thePortAloneRefusesOutflowAndInflowReopensItAtFiveSecondSlices(){portOnlyRefusal(5,24);}
    @Test void thePortAloneRefusesOutflowAndInflowReopensItAtTenthSecondSlices(){portOnlyRefusal(.1,600);}
    /**
     * While A stands above B, A's closed port leaves the run no driven admissible direction: it is dead-headed, carries
     * nothing, and is never offered back by the reopen retry, which reads the same start-state availability as the solve
     * (plan C.3). Without that, every step of those slices was refused once and re-solved (20 reopens at 5 s, 322 at 0.1 s,
     * measured with the reopen test reading no mask). Once B passes A, the run opens for B's gas into A inside the step:
     * inflow through a closed port, the port refusal counted as one more per-link allowance.
     */
    private void portOnlyRefusal(double interval,int count) {
        String label="refusal-"+interval;
        var slices=drive(label,portOnlyRefusal(),interval,count,60,-1,null);
        int first=-1;
        for(var s:slices) {
            assertEquals(0,s.out(),label+" slice "+s.index()+": the closed port refuses A's outflow");
            if(first<0&&s.in()>0)first=s.index();
            if(first<0) {
                assertEquals(0,s.reopens(),label+" slice "+s.index()+": a run only the port refuses is not reopened in the refused direction");
                assertEquals(FlowControl.Mode.CLOSED,s.modes().getFirst(),label+" slice "+s.index()+": dead-headed");
                assertEquals(150000,s.pressureEnd(),0,label+" slice "+s.index()+": A holds");
            }
        }
        assertTrue(first>0,label+": B's gas enters A once B passes A");
        System.out.println("PHASE_PORT_REFUSAL interval="+interval+" firstInflowSlice="+first+" reopensThere="+slices.get(first).reopens()
                +" reopensTotal="+slices.stream().mapToInt(Slice::reopens).sum());
        assertTrue(slices.get(first).reopens()>=1,label+": opened by the reopen retry");
        for(var s:slices.subList(first,count))assertTrue(s.in()>0,label+" slice "+s.index()+": B's gas keeps entering A");
    }

    /** A gas-only tank (nitrogen, 1 atm) whose LIQUID port (10 m, 25 mm) joins a junction fed by a water generator
     * (300 kPa, 10 m, 25 mm) and drained by a line (10 m, 10 mm) to a void at 1 atm. */
    private PassiveNetwork portThroughJunction() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(0,101325)),new PassiveNetwork.Reservoir(2,0,waterSupply(200000),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,waterSupply(300000),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(4,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
        return PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(50,0,1,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK),
                new PassiveNetwork.Pipe(51,2,1,line(10,.025)),new PassiveNetwork.Pipe(52,1,3,line(10,.01)))),model);
    }
    private PassiveNetwork setGenerator(PassiveNetwork graph,double pressure) {
        var nodes=new ArrayList<>(graph.reservoirs());var old=nodes.get(2);
        nodes.set(2,new PassiveNetwork.Reservoir(old.id(),old.elevation(),waterSupply(pressure),PassiveNetwork.NodeKind.GENERATOR));
        return new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }
    @Test void theSamePortDrainsOnceItsLiquidIsThereAtFiveSecondSlices(){portThroughJunction(5,40,8);}
    @Test void theSamePortDrainsOnceItsLiquidIsThereAtTenthSecondSlices(){portThroughJunction(.1,1000,400);}
    /**
     * Water enters the gas-only tank through its closed LIQUID port (inputs are not distinguished); once the port's water
     * fills at least {@code phi_open} of the tank and the generator is lowered to 110 kPa, below the tank, the same port
     * drains the tank through the junction to the void, and the generator's own line closes.
     */
    private void portThroughJunction(double interval,int count,int lowerAt) {
        String label="junction-"+interval;
        var slices=drive(label,portThroughJunction(),interval,count,50,lowerAt,g->setGenerator(g,110000));
        for(var s:slices.subList(0,lowerAt))assertTrue(s.in()>0&&s.out()==0,label+" slice "+s.index()+": water enters through the port");
        assertTrue(slices.get(lowerAt).openAtStart(),label+": the tank holds at least phi_open of water when the generator is lowered");
        for(var s:slices.subList(lowerAt,count)) {
            assertTrue(s.out()>0&&s.in()==0,label+" slice "+s.index()+": the same port drains the tank");
            assertEquals(FlowControl.Mode.CLOSED,s.modes().get(1),label+" slice "+s.index()+": the lowered generator's line closes");
        }
        System.out.println("PHASE_PORT_JUNCTION interval="+interval+" waterShareWhenLowered="+slices.get(lowerAt).phiStart()+" tankPressureWhenLowered="+slices.get(lowerAt).pressureStart()
                +" drainedFirstSlice="+slices.get(lowerAt).out()+" waterShareAtEnd="+slices.getLast().phiEnd());
    }
    /** The same line with the generator lowered to 100 kPa after 2 s, while the tank's water is still under
     * {@code phi_open}: the tank stands above the junction, which the void drains, but its port is closed for outflow, so
     * it keeps its water (the refusal on a junction end: the pass loop's forbidden-direction closure, then the start
     * closure once the structure has an accepted step). */
    @Test void aPortBelowItsOpeningShareRefusesToDrainIntoAJunction() {
        String label="early-0.1";
        var slices=drive(label,portThroughJunction(),.1,120,50,20,g->setGenerator(g,100000));
        double kept=slices.get(20).phiStart();
        assertTrue(kept>0&&kept<OPEN,label+": lowered with the water under phi_open, "+kept);
        for(var s:slices.subList(20,slices.size())) {
            assertEquals(0,s.out(),label+" slice "+s.index()+": the closed port refuses to drain");
            assertTrue(s.pressureEnd()>s.pressures()[1],label+" slice "+s.index()+": the tank stands above the junction");
            assertEquals(kept,s.phiEnd(),FLASH_DRIFT,label+" slice "+s.index()+": the tank keeps its water");
        }
        System.out.println("PHASE_PORT_EARLY waterShareKept="+kept+" tank="+slices.getLast().pressureEnd()+" junction="+slices.getLast().pressures()[1]);
    }

    // ---- The throttle, and a phase vanishing inside a step --------------------------------------------------------

    /** A 1 m3 tank at 2 MPa holding 2 % water under nitrogen, drained through {@code ports} LIQUID lines (2 m, 50 mm) to
     * voids at 1 atm, one 5 s step: the hydraulics and the velocity cap would empty it many times over, so the throttle
     * binds, each line carries exactly (phi - phi_reserve) V rho / (n dt) of the start state, and the water lands on the
     * reserve; the next step starts with the port closed. */
    @Test void theThrottleLandsAnOpenPortAtItsReserveInOneStep() {
        for(int ports=1;ports<=2;ports++) {
            var tank=waterUnderNitrogen(.02,2000000);
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();nodes.add(new PassiveNetwork.Reservoir(1,0,tank));
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(int i=0;i<ports;i++){nodes.add(new PassiveNetwork.Reservoir(2+i,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
                pipes.add(new PassiveNetwork.Pipe(70+i,0,1+i,List.of(line(2,.05)),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK));}
            var graph=new PassiveNetwork(nodes,pipes);
            double dt=5,phi=PassiveStepSolver.portPhaseShare(tank,PhasePort.LIQUID);
            double throttle=(phi-RESERVE)*tank.volume()*model.liquidDensity(tank)/(ports*dt);
            var solver=new PassiveStepSolver(model);
            var first=solver.solve(graph,dt,NOOP);var end=first.states().getFirst();
            double landed=PassiveStepSolver.portPhaseShare(end,PhasePort.LIQUID);
            System.out.println("PHASE_PORT_THROTTLE ports="+ports+" phiStart="+phi+" throttle="+throttle+" flows="+Arrays.toString(first.massFlows())+" landed="+landed+" modes="+first.modes());
            assertTrue(phi>=OPEN);
            for(double q:first.massFlows())assertEquals(throttle,q,1e-9*throttle,ports+" ports: each line carries its share of the throttle");
            assertEquals(RESERVE,landed,FLASH_DRIFT,ports+" ports: the water lands on the reserve");
            var next=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs());
            next.set(0,new PassiveNetwork.Reservoir(1,0,end,PassiveNetwork.NodeKind.RESERVOIR,first.inventories().getFirst()));
            var second=solver.solve(new PassiveNetwork(next,pipes),dt,NOOP);
            System.out.println("PHASE_PORT_THROTTLE_NEXT ports="+ports+" flows="+Arrays.toString(second.massFlows())+" modes="+second.modes());
            for(int i=0;i<ports;i++){assertEquals(0,second.massFlows()[i],ports+" ports: the next step starts closed");assertEquals(FlowControl.Mode.CLOSED,second.modes().get(i));}
        }
    }

    /** n-pentane under a methane-rich cap of 1.5 % of the vessel at 200 kPa (bisected on the methane charge). */
    private FluidThermodynamics.State pentaneWithCap(double capShare,double pressure) {
        double t=298.15,lo=0,hi=200;FluidThermodynamics.State state=null;
        for(int k=0;k<60;k++) {
            double methaneMoles=.5*(lo+hi);double[] n=new double[mw.length];n[pentane]=(1-capShare)*626/mw[pentane];n[methane]=methaneMoles;
            var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            state=model.flashTP(t,pressure,n,NOOP);
            if(state.vaporVolume()/state.volume()<capShare)lo=methaneMoles;else hi=methaneMoles;
        }
        return state;
    }
    /** That vessel fed n-pentane at 1 MPa (10 m, 25 mm) through its LIQUID port and vented through a VAPOR port (10 m,
     * 10 mm) to a void: the compression dissolves the cap. */
    private PassiveNetwork dissolvingCap() {
        var supply=new double[mw.length];supply[pentane]=1;
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,pentaneWithCap(.015,200000)),new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,1000000,supply,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenSink(),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(80,1,0,List.of(line(10,.025)),new FlowControl.Passive(),0,null,PhasePort.BULK,PhasePort.LIQUID),
                new PassiveNetwork.Pipe(81,0,2,List.of(line(10,.01)),new FlowControl.Passive(),0,null,PhasePort.VAPOR,PhasePort.BULK)));
    }
    /**
     * Plan 3.4, "phase vanishing inside a step": one 5 s step opens with the cap at 1.5 % (the VAPOR port open) and ends
     * with it dissolved. The pass whose layout has lost the vapour reads the port's stream as absent, whose velocity cap is
     * zero (decision A9), so the converged flow through it is zero with no illegal direction - the connection is not
     * closed by the pass loop - and the next step, starting from no vapour, closes the port.
     */
    @Test void aPhaseThatVanishesInsideAStepLeavesZeroFlowAndTheNextStepClosesThePort() {
        var graph=dissolvingCap();var solver=new PassiveStepSolver(model);
        var start=graph.reservoirs().getFirst().state();
        var first=solver.solve(graph,5,NOOP);var end=first.states().getFirst();
        System.out.println("PHASE_PORT_VANISH phiV "+PassiveStepSolver.portPhaseShare(start,PhasePort.VAPOR)+" -> "+PassiveStepSolver.portPhaseShare(end,PhasePort.VAPOR)
                +" P "+start.pressure()+" -> "+end.pressure()+" flows="+Arrays.toString(first.massFlows())+" modes="+first.modes());
        assertTrue(PassiveStepSolver.portPhaseShare(start,PhasePort.VAPOR)>=OPEN,"the step starts with the port open");
        assertFalse(FluidThermodynamics.holdsVapor(end),"the cap dissolved inside the step");
        assertTrue(Math.abs(first.massFlows()[1])<1e-30,"zero flow through the port whose phase vanished: "+first.massFlows()[1]);
        assertNotEquals(FlowControl.Mode.CLOSED,first.modes().get(1),"no illegal direction: the pass loop did not close the line");
        var next=new ArrayList<PassiveNetwork.Reservoir>(graph.reservoirs());
        next.set(0,new PassiveNetwork.Reservoir(1,0,end,PassiveNetwork.NodeKind.RESERVOIR,first.inventories().getFirst()));
        var second=solver.solve(new PassiveNetwork(next,graph.pipes()),5,NOOP);
        System.out.println("PHASE_PORT_VANISH_NEXT flows="+Arrays.toString(second.massFlows())+" modes="+second.modes());
        assertEquals(0,second.massFlows()[1],"the next step starts with the port closed");
        assertEquals(FlowControl.Mode.CLOSED,second.modes().get(1));
    }
    @Test void aDissolvingCapIntegratesAtFiveSecondSlices(){dissolvingCap(5,24);}
    @Test void aDissolvingCapIntegratesAtTenthSecondSlices(){dissolvingCap(.1,300);}
    /** The same vessel through the interval solver: the cap goes, the port closes once and stays closed, and the vessel
     * rises to the pentane generator's pressure. */
    private void dissolvingCap(double interval,int count) {
        String label="vanish-"+interval;
        var slices=drive(label,dissolvingCap(),interval,count,81,-1,null);
        int closed=closure(label,slices,1);
        assertEquals(0,slices.getLast().phiEnd(),label+": no vapour left");
        assertEquals(1000000,slices.getLast().pressureEnd(),1,label+": the liquid-full vessel stands at the generator's pressure");
        System.out.println("PHASE_PORT_VANISH_RUN interval="+interval+" closedAtSlice="+closed+" finalPressure="+slices.getLast().pressureEnd());
    }
}
