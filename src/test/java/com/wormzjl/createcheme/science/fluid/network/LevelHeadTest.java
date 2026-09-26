package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The level head at a vessel's bottom (LIQUID) port, decision D9 (option B of
 * documentation/2026-09-26-phase-ports-and-compressor/LEVEL_HEAD_REVIEW.md): the port's driving pressure is the
 * headspace pressure plus {@code g * condensed mass * H / V} with {@code H} = {@link PassiveStepSolver#LEVEL_HEAD_HEIGHT}
 * (1 m) and {@code V} the vessel's inventory volume, in both directions, read by every static driving pressure the solver
 * states ({@link PassiveStepSolver#endPressure}). The fixtures of the review's section 5, items 1 to 5 (item 6, the
 * physical-topology face test, waits for WP5's face-to-port compile), and the two start-of-solve decisions the head must
 * reach ({@code closeDeadHeads} and the boundary-reopen test {@code reopenable}).
 *
 * <p>Every slice is a job as the island runtime runs it ({@link PassiveIntervalSolver#replayStart} from the committed
 * interval), commits whole, and closes the component ledger to 1e-12 and the energy ledger to 1e-10 (relative to
 * max(1, initial); vessels and junction holdups against the boundaries). Each fixture prints a {@code LEVEL_HEAD_RUN}
 * line with its solver counters (Newton solves and iterations, active-set passes, Jacobian builds; rejections by reason)
 * and its measured numbers; the numbers are recorded in PHASE_PORTS_REVIEW.md, section "D9".
 */
class LevelHeadTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=index("Water"),nitrogen=index("Nitrogen");
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private static final double OPEN=PassiveStepSolver.PHASE_PORT_OPEN,RESERVE=PassiveStepSolver.PHASE_PORT_RESERVE,FLASH_DRIFT=1e-5;
    private static final double G=PassiveStepSolver.GRAVITY,H=PassiveStepSolver.LEVEL_HEAD_HEIGHT;
    private static final String REOPEN="Backward-Euler boundary reopened";
    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PipeResistance.Geometry g,PhasePort pa,PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(g),new FlowControl.Passive(),0,null,pa,pb);
    }

    private int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen, flashed and
     * scaled to exactly the vessel volume. */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    private FluidThermodynamics.State waterSupply(double pressure){var n=new double[mw.length];n[water]=1;return model.flashTP(298.15,pressure,n,NOOP);}
    private FluidThermodynamics.State nitrogenAt(double pressure){return model.initialNitrogenCharge(1,298.15,pressure,NOOP);}
    /** The level head of a vessel's LIQUID port, restated from the formula of decision D9. */
    private double head(PassiveNetwork.Reservoir vessel){return G*model.liquidMass(vessel.state())*H/vessel.inventory().volume();}
    /** The bottom (LIQUID) port's driving pressure of a vessel. */
    private double bottom(PassiveNetwork.Reservoir vessel){return vessel.state().pressure()+head(vessel);}
    private static double liquidShare(PassiveNetwork.Reservoir vessel){return PassiveStepSolver.portPhaseShare(vessel.state(),PhasePort.LIQUID);}

    /** Committed slices of one run and the solver counters over it. */
    private record Run(List<PassiveIntervalSolver.Result> slices,List<PassiveNetwork> graphs,Map<String,Integer> reasons,long newtonSolves,long newtonIterations,
                       long passes,long jacobians,int accepted,int rejected) {
        int reopens(){return reasons.getOrDefault(REOPEN,0);}
        PassiveNetwork last(){return graphs.getLast();}
        String counters(){return "accepted="+accepted+" rejected="+rejected+" reasons="+reasons+" newtonSolves="+newtonSolves+" newtonIterations="+newtonIterations
                +" iterationsPerSolve="+(newtonSolves==0?0:(double)newtonIterations/newtonSolves)+" passes="+passes+" jacobianBuilds="+jacobians;}
    }
    /** {@code count} slices of {@code interval}, each a job from the committed interval, with the ledgers checked. */
    private Run drive(String label,PassiveNetwork graph,double interval,int count){return drive(label,graph,interval,count,new PassiveIntervalSolver(model),null);}
    private Run drive(String label,PassiveNetwork graph,double interval,int count,PassiveIntervalSolver solver,PassiveIntervalSolver.Result committed) {
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0;
        var slices=new ArrayList<PassiveIntervalSolver.Result>();var graphs=new ArrayList<PassiveNetwork>();var reasons=new TreeMap<String,Integer>();int accepted=0,rejected=0;
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        try {
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
        } finally {SolverDiagnostics.ENABLED=false;}
        var run=new Run(slices,graphs,reasons,SolverDiagnostics.newtonSolves.sum(),SolverDiagnostics.newtonIterations.sum(),SolverDiagnostics.activeSetPasses.sum(),
                SolverDiagnostics.jacobianBuilds.sum(),accepted,rejected);
        SolverDiagnostics.reset();
        return run;
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
    private static double outOf(PassiveIntervalSolver.Result result,long pipe,boolean first) {
        var t=result.pipeTransfers().stream().filter(x->x.pipeId()==pipe).findFirst().orElseThrow();
        return first?t.forward().massKg():t.reverse().massKg();
    }
    private static int edge(PassiveNetwork graph,long id){for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).id()==id)return i;throw new AssertionError(id);}

    // ---- 1. Drain to a void on the head alone ----------------------------------------------------------------------

    /** 0.1 m3 of water under nitrogen whose headspace stands 50 Pa below the void's 1 atm, vented through its VAPOR port
     * from a nitrogen generator at 1 atm (2 m, 50 mm; open from the first step, since the tank starts below it) and drained
     * through {@code drainPort} (level) to a void at 1 atm. The pressures alone drive nothing out of the tank: the only
     * drive is the level head, about 950 Pa at the start. */
    private PassiveNetwork headDrain(PhasePort drainPort,PipeResistance.Geometry drain) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.1,101275)),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(90,0,2,drain,drainPort,PhasePort.BULK),pipe(91,1,0,line(2,.05),PhasePort.BULK,PhasePort.VAPOR)));
    }
    @Test void aTankAtTheVoidsPressureDrainsOnItsHeadAloneAtFiveSecondSlices(){drainOnTheHead(5,40);}
    @Test void aTankAtTheVoidsPressureDrainsOnItsHeadAloneAtTenthSecondSlices(){drainOnTheHead(.1,1500);}
    /**
     * The drain opens at the first step: {@code closeDeadHeads} reads the head (a drive of about 900 Pa where the pressures
     * alone give -50 Pa), so the run is never closed at a step start while the bottom stands above the void, and the
     * boundary-reopen retry is never needed (zero reopens: a bottled drain would show up as reopens or as a drain that
     * never starts). The port then closes as WP2's availability closes it - at the first step start below
     * {@code phi_open}, the water left in [{@code phi_reserve} - flash drift, {@code phi_open}) - and stays closed. The
     * same tank with a BULK drain end (no head) carries nothing beyond roundoff: the vent lifts its headspace to the void's
     * pressure and no further.
     */
    private void drainOnTheHead(double interval,int count) {
        String label="drain-"+interval;
        var start=headDrain(PhasePort.LIQUID,line(2,.05));
        System.out.println("LEVEL_HEAD_DRAIN_START interval="+interval+" head="+head(start.reservoirs().getFirst())+" P="+start.reservoirs().getFirst().state().pressure());
        var run=drive(label,start,interval,count);
        int closedAt=-1,transitions=0;boolean wasOpen=true;
        for(int i=0;i<count;i++) {
            var before=i==0?start:run.graphs().get(i-1);boolean open=liquidShare(before.reservoirs().getFirst())>=OPEN;
            if(open!=wasOpen)transitions++;wasOpen=open;
            double out=outOf(run.slices().get(i),90,true);
            if(open)assertTrue(out>0,label+" slice "+i+": the open port drains on the head");
            else{if(closedAt<0)closedAt=i;assertEquals(0,out,label+" slice "+i+": the closed port carries nothing");
                assertEquals(FlowControl.Mode.CLOSED,run.slices().get(i).endpointModes().get(edge(start,90)),label+" slice "+i);}
        }
        assertTrue(outOf(run.slices().getFirst(),90,true)>0,label+": the drain starts in the first slice");
        assertEquals(0,run.reopens(),label+": no boundary reopen (a drain the start closure bottled would need one)");
        assertTrue(closedAt>0,label+": the port closes");
        assertEquals(1,transitions,label+": one open-to-closed transition");
        double landed=liquidShare(run.graphs().get(closedAt-1).reservoirs().getFirst());
        double finalShare=liquidShare(run.last().reservoirs().getFirst());
        assertTrue(landed>=RESERVE-FLASH_DRIFT&&landed<OPEN,label+": the water left, "+landed);
        assertEquals(landed,finalShare,FLASH_DRIFT,label+": the closed vessel keeps its water");
        var control=drive(label+"-bulk",headDrain(PhasePort.BULK,line(2,.05)),interval,Math.min(count,40));
        double controlOut=0;for(var s:control.slices())controlOut+=outOf(s,90,true);
        assertTrue(controlOut<1e-4,label+": a BULK drain end has no drive out of the tank: "+controlOut+" kg");
        System.out.println("LEVEL_HEAD_RUN drain interval="+interval+" closedAtSlice="+closedAt+" closedAtSeconds="+closedAt*interval+" landed="+landed+" final="+finalShare
                +" firstSliceOut="+outOf(run.slices().getFirst(),90,true)+" bulkControlOut="+controlOut+" "+run.counters());
    }

    // ---- 1b, 1c. The start closure and the reopen test read the head ----------------------------------------------

    /** A closed tank (0.3 m3 of water under nitrogen, headspace {@code tankPressure}) fed water through its LIQUID port by a
     * generator at {@code supply} (level, 2 m, 50 mm). */
    private PassiveNetwork bottomFeed(double tankPressure,double supply) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.3,tankPressure)),new PassiveNetwork.Reservoir(2,0,waterSupply(supply),PassiveNetwork.NodeKind.GENERATOR));
        return new PassiveNetwork(nodes,List.of(pipe(92,1,0,line(2,.05),PhasePort.BULK,PhasePort.LIQUID)));
    }
    @Test void aGeneratorBelowTheBottomPressureStaysDeadHeadedWithoutReopens() {
        for(double interval:new double[]{5,.1}) {
            String label="against-"+interval;
            var start=bottomFeed(100000,101325);var tank=start.reservoirs().getFirst();
            assertTrue(tank.state().pressure()<101325&&bottom(tank)>101325,"the generator stands between the headspace and the bottom");
            var run=drive(label,start,interval,interval<1?100:12);
            for(int i=0;i<run.slices().size();i++) {
                var s=run.slices().get(i);
                assertEquals(0,outOf(s,92,true),label+" slice "+i+": nothing enters against the head");
                assertEquals(FlowControl.Mode.CLOSED,s.endpointModes().getFirst(),label+" slice "+i+": dead-headed on the bottom pressure");
                assertEquals(tank.state().pressure(),run.graphs().get(i).reservoirs().getFirst().state().pressure(),0,label+" slice "+i+": the tank holds");
            }
            assertEquals(0,run.reopens(),label+": the reopen test reads the same bottom pressure as the start closure");
            System.out.println("LEVEL_HEAD_RUN against interval="+interval+" headspace="+tank.state().pressure()+" bottom="+bottom(tank)+" "+run.counters());
        }
    }
    /** A closed tank (0.3 m3 of water under nitrogen at 98 kPa: bottom about 100.9 kPa) drained through its LIQUID port to a
     * void at 1 atm (level, 2 m, 50 mm) and filled through a BULK end from a 200 kPa water generator (10 m, 10 mm). */
    private PassiveNetwork reopenByHead() {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.3,98000)),new PassiveNetwork.Reservoir(2,0,waterSupply(200000),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(93,0,2,line(2,.05),PhasePort.LIQUID,PhasePort.BULK),pipe(94,1,0,line(10,.01),PhasePort.BULK,PhasePort.BULK)));
    }
    @Test void aDrainTheHeadLiftsAboveTheVoidInsideAStepIsReopenedAndNeverBottled() {
        for(double interval:new double[]{5,.1}) {
            String label="reopen-"+interval;
            var start=reopenByHead();
            assertTrue(bottom(start.reservoirs().getFirst())<101325,"the drain starts dead-headed");
            var run=drive(label,start,interval,interval<1?600:24);
            int first=-1;
            for(int i=0;i<run.slices().size();i++) {
                var tank=run.graphs().get(i).reservoirs().getFirst();double out=outOf(run.slices().get(i),93,true);
                double band=PassiveStepSolver.reopenBand(bottom(tank),101325);
                if(out==0)assertTrue(bottom(tank)<=101325+band,label+" slice "+i+": bottled, the bottom ends above the void with the drain shut: "+bottom(tank)+" headspace "+tank.state().pressure());
                if(first<0&&out>0)first=i;
            }
            assertTrue(first>0,label+": the drain opens once the bottom passes the void");
            var tank=run.last().reservoirs().getFirst();
            assertTrue(tank.state().pressure()<101325,label+": the headspace stays below the void: only the head drains it, "+tank.state().pressure());
            System.out.println("LEVEL_HEAD_RUN reopen interval="+interval+" firstDrainSlice="+first+" reopensThere="+run.slices().get(first).rejectionReasons().getOrDefault(REOPEN,0)
                    +" finalHeadspace="+tank.state().pressure()+" finalBottom="+bottom(tank)+" finalOut="+outOf(run.slices().getLast(),93,true)+" "+run.counters());
        }
    }

    // ---- 2. Two tanks equalise their levels -----------------------------------------------------------------------

    /** Two closed 1 m3 tanks at 1 atm, 0.8 and 0.2 m3 of water under nitrogen, joined bottom to bottom at the same y
     * (LIQUID to LIQUID, 10 m, 20 mm). */
    private PassiveNetwork equalisation(PhasePort port) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.8,101325)),new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.2,101325)));
        return new PassiveNetwork(nodes,List.of(pipe(95,0,1,line(10,.02),port,port)));
    }
    /**
     * They rest where their bottoms stand level, {@code P1 + g m1 H/V = P2 + g m2 H/V}, to the Newton's tolerance, by a
     * monotone approach at 5 s slices (the bottom difference never changes sign and never grows), and then stand still: the
     * flow and every state repeat from slice to slice (the stationarity a REST certificate is issued on; the certificate
     * itself is runtime-package code, IslandCertificate, and is not reachable from this package). Without the head (BULK
     * ends) the pair charged at one pressure never moves.
     */
    @Test void twoTanksAtOnePressureEqualiseTheirBottomsMonotonically() {
        var start=equalisation(PhasePort.LIQUID);
        double initial=bottom(start.reservoirs().get(0))-bottom(start.reservoirs().get(1));
        var run=drive("equalise",start,5,60);
        double previous=initial;int monotoneBreaks=0;double worstOvershoot=0;
        for(int i=0;i<run.slices().size();i++) {
            var g=run.graphs().get(i);double d=bottom(g.reservoirs().get(0))-bottom(g.reservoirs().get(1));
            worstOvershoot=Math.min(worstOvershoot,d);
            if(d>previous)monotoneBreaks++;
            previous=d;
            if(i%5==0||i<8)System.out.println("LEVEL_HEAD_EQUALISE slice="+i+" bottomDifference="+d+" flow="+run.slices().get(i).averageMassFlows()[0]
                    +" P1="+g.reservoirs().get(0).state().pressure()+" P2="+g.reservoirs().get(1).state().pressure()+" h1="+head(g.reservoirs().get(0))+" h2="+head(g.reservoirs().get(1)));
        }
        var last=run.last();double rest=bottom(last.reservoirs().get(0))-bottom(last.reservoirs().get(1));
        double scale=Math.max(1e5,Math.max(last.reservoirs().get(0).state().pressure(),last.reservoirs().get(1).state().pressure()));
        double moved=model.liquidMass(start.reservoirs().get(0).state())-model.liquidMass(last.reservoirs().get(0).state());
        System.out.println("LEVEL_HEAD_RUN equalise initialDifference="+initial+" restDifference="+rest+" worstOvershoot="+worstOvershoot+" monotoneBreaks="+monotoneBreaks
                +" waterMovedKg="+moved+" lastFlow="+run.slices().getLast().averageMassFlows()[0]+" "+run.counters());
        assertTrue(initial>5000,"the bottoms start apart: "+initial);
        assertEquals(0,monotoneBreaks,"the bottom difference never grows");
        assertTrue(worstOvershoot>=-1e-9*scale,"no overshoot: "+worstOvershoot);
        assertEquals(0,rest,1e-9*scale,"at rest the bottoms stand level to the Newton tolerance");
        assertTrue(moved>0,"water moved from the fuller tank");
        // Stationary: the last slices repeat.
        var a=run.graphs().get(run.graphs().size()-2);
        for(int n=0;n<2;n++){var x=a.reservoirs().get(n).state();var y=last.reservoirs().get(n).state();
            assertEquals(x.pressure(),y.pressure(),1e-9*x.pressure(),"tank "+n+" pressure stationary");
            assertEquals(model.liquidMass(x),model.liquidMass(y),1e-9*model.liquidMass(x),"tank "+n+" water stationary");}
        assertTrue(Math.abs(run.slices().getLast().averageMassFlows()[0])<1e-6,"the flow has stopped: "+run.slices().getLast().averageMassFlows()[0]);
        var bulk=drive("equalise-bulk",equalisation(PhasePort.BULK),5,4);
        for(var s:bulk.slices())assertEquals(0,s.averageMassFlows()[0],"without the head a pair at one pressure never moves");
    }

    // ---- 3. Manometer --------------------------------------------------------------------------------------------

    /** Tanks A and B, 0.3 m3 of water under nitrogen each, joined bottom to bottom ({@code port} at both ends, 2 m, 50 mm,
     * level). A's headspace is held at 104325 Pa by a nitrogen generator on a BULK end (2 m, 50 mm; inflow only); B is
     * closed, charged at 101325 Pa. The cushions start 3 kPa apart, below {@code rho g H} (9.8 kPa).
     *
     * <p>B is closed rather than vented: a VAPOR vent on a tank filled through its LIQUID port fails the reconstruction's
     * equation gate at every step size on this fixture, on the WP2 tree as on this one (PHASE_PORTS_REVIEW.md, D9 section,
     * WP2 option 3), and a BULK vent would carry B's water out. */
    private PassiveNetwork manometer(PhasePort port) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.3,104325)),new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.3,101325)),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(104325),PassiveNetwork.NodeKind.GENERATOR));
        return new PassiveNetwork(nodes,List.of(pipe(96,0,1,line(2,.05),port,port),pipe(97,2,0,line(2,.05),PhasePort.BULK,PhasePort.BULK)));
    }
    /**
     * Water runs from A to B until the bottoms stand level: the cushions end held apart by {@code dP = P_A - P_B}, less
     * than {@code rho g H}, and the level difference is {@code dP/(rho g)} (in mass form {@code g (m_B - m_A) H/V = dP}),
     * A's headspace at its generator's pressure. With BULK ends (no head) the pair rests where the headspaces are equal,
     * whatever the levels: B's cushion is compressed to A's pressure and the level difference left (the water the bulk
     * mixture carried over) stands unbalanced at the bottoms.
     */
    @Test void cushionsHeldApartStandAtALevelDifferenceOfDpOverRhoG() {
        var run=drive("manometer",manometer(PhasePort.LIQUID),5,60);
        var last=run.last();var a=last.reservoirs().get(0);var b=last.reservoirs().get(1);
        double dp=a.state().pressure()-b.state().pressure();
        double rhoA=model.liquidDensity(a.state()),rhoB=model.liquidDensity(b.state());
        double levelDifference=(FluidThermodynamics.liquidStreamVolume(b.state())-FluidThermodynamics.liquidStreamVolume(a.state()))*H/a.inventory().volume();
        double expected=dp/(.5*(rhoA+rhoB)*G);
        System.out.println("LEVEL_HEAD_RUN manometer PA="+a.state().pressure()+" PB="+b.state().pressure()+" dP="+dp+" mA="+model.liquidMass(a.state())+" mB="+model.liquidMass(b.state())
                +" levelDifference="+levelDifference+" dPOverRhoG="+expected+" bottomDifference="+(bottom(a)-bottom(b))+" lastFlow="+run.slices().getLast().averageMassFlows()[0]+" "+run.counters());
        assertEquals(104325,a.state().pressure(),1e-3,"A's cushion stands at its generator");
        assertTrue(dp>0&&dp<model.liquidDensity(a.state())*G*H,"the cushions are held apart by less than rho g H: "+dp);
        assertEquals(0,bottom(a)-bottom(b),1e-3,"the bottoms stand level");
        assertEquals(expected,levelDifference,1e-4*expected,"the level difference is dP/(rho g)");
        var control=drive("manometer-bulk",manometer(PhasePort.BULK),5,60);
        var ca=control.last().reservoirs().get(0);var cb=control.last().reservoirs().get(1);
        System.out.println("LEVEL_HEAD_RUN manometer-bulk PA="+ca.state().pressure()+" PB="+cb.state().pressure()+" mA="+model.liquidMass(ca.state())+" mB="+model.liquidMass(cb.state())
                +" lastFlow="+control.slices().getLast().averageMassFlows()[0]+" "+control.counters());
        assertEquals(ca.state().pressure(),cb.state().pressure(),1e-3,"without the head the headspaces equalise");
        double controlLevels=(FluidThermodynamics.liquidStreamVolume(cb.state())-FluidThermodynamics.liquidStreamVolume(ca.state()))*H/ca.inventory().volume();
        assertTrue(Math.abs(controlLevels)>1e-3,"and a level difference stands with no pressure difference to hold it: "+controlLevels+" m");
    }

    // ---- 4. Pump into a bottom port --------------------------------------------------------------------------------

    /** A water generator at 1 atm, a junction, a pump (0.01 m3/s, 300 kPa) and a nitrogen tank at 1 atm, the pump's
     * discharge on the tank's {@code tankPort}; every line 1 m, 50 mm, level. */
    private PassiveNetwork pumpedFill(PhasePort tankPort) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterSupply(101325),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,waterSupply(101325),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325)));
        var g=line(1,.05);
        return PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(20,0,1,g),
                new PassiveNetwork.Pipe(21,1,2,List.of(g),new FlowControl.Pump(.01,300000,.7),0,null,PhasePort.BULK,tankPort))),model);
    }
    /** The pump closes when the discharge it sees reaches its shutoff: into a side (BULK) port that is the headspace, into a
     * bottom (LIQUID) port the headspace plus the level head, so the bottom-fed tank's headspace closes {@code rho g L}
     * lower - the head of the water it received. */
    @Test void aPumpIntoABottomPortClosesTheLevelHeadEarlier() {
        var side=drive("pump-side",pumpedFill(PhasePort.BULK),5,30);var low=drive("pump-bottom",pumpedFill(PhasePort.LIQUID),5,30);
        var s=side.last().reservoirs().get(2);var b=low.last().reservoirs().get(2);
        double sideShutoff=s.state().pressure(),bottomHeadspace=b.state().pressure(),bottomHead=head(b);
        System.out.println("LEVEL_HEAD_RUN pump sideHeadspace="+sideShutoff+" bottomHeadspace="+bottomHeadspace+" bottomHead="+bottomHead+" bottomPort="+(bottomHeadspace+bottomHead)
                +" sideWater="+model.liquidMass(s.state())+" bottomWater="+model.liquidMass(b.state())+" modes="+side.slices().getLast().endpointModes()+"/"+low.slices().getLast().endpointModes()
                +" side: "+side.counters()+" bottom: "+low.counters());
        assertEquals(FlowControl.Mode.CLOSED,side.slices().getLast().endpointModes().get(1),"the side-fed pump has closed");
        assertEquals(FlowControl.Mode.CLOSED,low.slices().getLast().endpointModes().get(1),"the bottom-fed pump has closed");
        assertTrue(bottomHead>5000,"the bottom port stands under several kPa of water: "+bottomHead);
        assertEquals(sideShutoff,bottomHeadspace+bottomHead,1,"both close at the same discharge pressure, the bottom's including its head");
        assertEquals(bottomHead,sideShutoff-bottomHeadspace,1,"the bottom-fed headspace closes the level head earlier");
    }

    // ---- 5. Certified replay is bitwise with the head --------------------------------------------------------------

    /**
     * A slow drain on the head alone (the drain fixture through 10 m of 10 mm line): an island woken from a certificate
     * restarts with a fresh solver from the committed interval ({@link PassiveIntervalSolver#replayStart}), and its slices
     * must be the doubles of the island that solved every interval with one solver. The head is a function of the state,
     * H and V only, with no history, so the replay stays bitwise.
     */
    @Test void aFreshSolverReplaysASlowHeadDrainBitwise() {
        var start=headDrain(PhasePort.LIQUID,line(10,.01));
        var reference=drive("replay",start,5,12);
        assertTrue(outOf(reference.slices().getFirst(),90,true)>0&&outOf(reference.slices().getLast(),90,true)>0,"the tank drains slowly throughout");
        for(int resume:new int[]{1,4,8}) {
            var resumed=drive("replay-from-"+resume,reference.graphs().get(resume-1),5,12-resume,new PassiveIntervalSolver(model),reference.slices().get(resume-1));
            for(int i=0;i<resumed.slices().size();i++)assertSameDoubles("resume "+resume+" slice "+(resume+i),reference.slices().get(resume+i),resumed.slices().get(i));
        }
        System.out.println("LEVEL_HEAD_RUN replay slices=12 drained="+(model.liquidMass(start.reservoirs().getFirst().state())-model.liquidMass(reference.last().reservoirs().getFirst().state()))
                +"kg head="+head(start.reservoirs().getFirst())+"->"+head(reference.last().reservoirs().getFirst())+" "+reference.counters());
    }
    private static void assertSameDoubles(String label,PassiveIntervalSolver.Result a,PassiveIntervalSolver.Result b) {
        assertArrayEquals(a.averageMassFlows(),b.averageMassFlows(),label+" flows");
        assertEquals(a.acceptedSubsteps(),b.acceptedSubsteps(),label+" accepted");
        assertEquals(a.endpointModes(),b.endpointModes(),label+" modes");
        for(int n=0;n<a.graph().reservoirs().size();n++) {
            var x=a.graph().reservoirs().get(n);var y=b.graph().reservoirs().get(n);
            assertEquals(Double.doubleToLongBits(x.state().pressure()),Double.doubleToLongBits(y.state().pressure()),label+" node "+n+" pressure");
            assertEquals(Double.doubleToLongBits(x.state().temperature()),Double.doubleToLongBits(y.state().temperature()),label+" node "+n+" temperature");
            assertArrayEquals(x.inventory().moles(),y.inventory().moles(),label+" node "+n+" moles");
            assertEquals(Double.doubleToLongBits(x.inventory().internalEnergy()),Double.doubleToLongBits(y.inventory().internalEnergy()),label+" node "+n+" energy");
        }
        for(int i=0;i<a.boundaries().size();i++){assertArrayEquals(a.boundaries().get(i).moles(),b.boundaries().get(i).moles(),label+" boundary "+i);
            assertEquals(Double.doubleToLongBits(a.boundaries().get(i).totalEnergyJoule()),Double.doubleToLongBits(b.boundaries().get(i).totalEnergyJoule()),label+" boundary energy "+i);}
    }
}
