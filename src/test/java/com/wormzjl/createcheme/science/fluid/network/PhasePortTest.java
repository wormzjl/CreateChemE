package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture 1 of documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_PLAN.md section 6 (work package WP1): a
 * 1 m3 two-phase vessel - water under nitrogen, and a light hydrocarbon liquid (n-pentane) under methane - with three
 * lines to voids, one drawing through a VAPOR port, one through a LIQUID port and one through a BULK end. Each line must
 * carry its own phase: the vapour line no hydrocarbon liquid and no free water, the liquid line no vapour, the bulk line
 * the whole state. Every committed interval closes the component ledger to 1e-12 and the energy ledger to 1e-10
 * (relative to max(1, initial)), and the vessel integrates at the probe's 0.1 s slices and at the product's 5 s slices.
 * The bulk line is bitwise the bulk: at step level against the bulk sampling of the same donor state, and against a
 * bulk-only graph whose phase lines are closed.
 */
class PhasePortTest {
    private static final Runnable NOOP=()->{};
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=index("Water"),nitrogen=index("Nitrogen"),methane=index("Methane"),pentane=index("N-pentane");
    /** Ledger bounds: the reconstruction is exact, so these are roundoff (relative to max(1, initial)). */
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(10,.01,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    private static final long VAPOR_LINE=10,LIQUID_LINE=11,BULK_LINE=12;

    private enum Vessel { WATER_UNDER_NITROGEN, PENTANE_UNDER_METHANE }

    private int index(String component){int i=model.components().indexOf(component);assertTrue(i>=0,component);return i;}
    /** 1 m3 at 298.15 K: half water and half nitrogen at 200 kPa, or 0.4 m3 of n-pentane under 0.6 m3 of methane at 300 kPa,
     * flashed and scaled to exactly the vessel volume. */
    private FluidThermodynamics.State vessel(Vessel kind) {
        double t=298.15,p;double[] n=new double[mw.length];
        if(kind==Vessel.WATER_UNDER_NITROGEN){p=200000;n[water]=.5*997/mw[water];n[nitrogen]=p*.5/(FluidThermodynamics.R*t);}
        else{p=300000;n[pentane]=.4*626/mw[pentane];n[methane]=p*.6/(FluidThermodynamics.R*t);}
        var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        var state=model.flashTP(t,p,n,NOOP);
        assertTrue(FluidThermodynamics.holdsVapor(state)&&FluidThermodynamics.holdsLiquid(state),kind+" must be two-phase");
        return state;
    }
    /** The vessel (node 0) and three nitrogen voids at 1 atm, one line each; the vessel end of each line has the given
     * port, and {@code closedPhaseLines} closes the first two lines both ways. */
    private PassiveNetwork outlets(Vessel kind,PhasePort vaporLine,PhasePort liquidLine,boolean closedPhaseLines) {
        var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,vessel(kind)),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID),
                new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID));
        int blocked=closedPhaseLines?3:0;
        return new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(VAPOR_LINE,0,1,List.of(LINE),new FlowControl.Passive(),blocked,null,vaporLine,PhasePort.BULK),
                new PassiveNetwork.Pipe(LIQUID_LINE,0,2,List.of(LINE),new FlowControl.Passive(),blocked,null,liquidLine,PhasePort.BULK),
                new PassiveNetwork.Pipe(BULK_LINE,0,3,LINE)));
    }

    @Test void onlyAVesselEndMayCarryAPhasePortAndEveryOtherConstructorIsBulk() {
        var state=vessel(Vessel.WATER_UNDER_NITROGEN);
        var legacy=new PassiveNetwork.Pipe(1,0,1,LINE);
        assertEquals(PhasePort.BULK,legacy.firstPort());assertEquals(PhasePort.BULK,legacy.secondPort());assertTrue(legacy.bulk());
        assertEquals(legacy,legacy.withPorts(PhasePort.BULK,PhasePort.BULK));
        assertEquals(legacy.identity(),legacy.withPorts(PhasePort.BULK,PhasePort.BULK).identity());
        var vapor=legacy.withPorts(PhasePort.VAPOR,PhasePort.BULK);
        assertNotEquals(legacy.identity(),vapor.identity(),"a port changes the equations, so it is part of the identity");
        assertEquals(PhasePort.VAPOR,vapor.withBlockedDirections(3).firstPort(),"withBlockedDirections keeps the ports");
        assertEquals(PhasePort.VAPOR,vapor.withFilter(InlineFilter.empty()).firstPort(),"withFilter keeps the ports");
        assertEquals(PhasePort.VAPOR,vapor.portAt(0));assertEquals(PhasePort.BULK,vapor.portAt(1));
        assertEquals(PhasePort.VAPOR,vapor.drawPort(1));assertEquals(PhasePort.BULK,vapor.drawPort(-1));
        for(var kind:PassiveNetwork.NodeKind.values()) {
            var far=new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.VOID);
            var near=new PassiveNetwork.Reservoir(1,0,state,kind);
            boolean vessel=kind==PassiveNetwork.NodeKind.RESERVOIR||kind==PassiveNetwork.NodeKind.PORT;
            for(var port:List.of(PhasePort.VAPOR,PhasePort.LIQUID)) {
                var pipe=legacy.withPorts(port,PhasePort.BULK);
                if(vessel)assertDoesNotThrow(()->new PassiveNetwork(List.of(near,far),List.of(pipe)),kind+" "+port);
                else assertThrows(IllegalArgumentException.class,()->new PassiveNetwork(List.of(near,far),List.of(pipe)),kind+" "+port);
            }
            assertDoesNotThrow(()->new PassiveNetwork(List.of(near,far),List.of(legacy)),kind+" BULK");
        }
    }

    /**
     * One backward-Euler step: each line's transferred stream is exactly its port's phase split of the vessel's end
     * state (the stream is sampled on the step's end state), its mass is the moved mass, the reconstruction's boundary
     * booking agrees with it to the Newton's tolerance, and the vessel's ledger closes against the boundaries.
     */
    @Test void aStepCarriesExactlyEachPortsPhase() {
        for(var kind:Vessel.values()) {
            var graph=outlets(kind,PhasePort.VAPOR,PhasePort.LIQUID,false);
            var result=new PassiveStepSolver(model).solve(graph,.5,NOOP);
            var end=result.states().getFirst();double[] flows=result.massFlows();
            for(int i=0;i<3;i++)assertTrue(flows[i]>0,kind+": line "+i+" must drain the vessel, flow "+flows[i]);
            var byId=new HashMap<Long,PipeTransfer>();for(var t:result.pipeTransfers())byId.put(t.pipeId(),t);
            var vapor=byId.get(VAPOR_LINE).forward();var liquid=byId.get(LIQUID_LINE).forward();var bulk=byId.get(BULK_LINE).forward();
            // What each port draws, and nothing else.
            assertZero(vapor.phaseMoles()[0],kind+" vapour line hydrocarbon liquid");assertZero(vapor.phaseMoles()[1],kind+" vapour line free water");
            assertEquals(0,vapor.phaseVolumes()[0]);assertEquals(0,vapor.phaseVolumes()[1]);assertTrue(vapor.solids().empty());
            assertZero(liquid.phaseMoles()[2],kind+" liquid line vapour");assertEquals(0,liquid.phaseVolumes()[2]);
            double[] y=Arrays.copyOf(end.vapor(),mw.length);y[water]=end.waterVapor();
            double[] x=Arrays.copyOf(end.liquid(),mw.length);x[water]=end.waterLiquid();
            double worst=Math.max(proportional(vapor.phaseMoles()[2],y),proportional(add(liquid.phaseMoles()[0],liquid.phaseMoles()[1]),x));
            assertTrue(worst<1e-12,kind+": a line's composition is its phase's to "+worst);
            for(var line:List.of(vapor,liquid,bulk))assertEquals(line.massKg(),mass(line.componentMoles()),1e-12*line.massKg(),kind+" stream mass");
            assertEquals(.5*flows[0],vapor.massKg(),1e-15*vapor.massKg());assertEquals(.5*flows[1],liquid.massKg(),1e-15*liquid.massKg());
            // The bulk line is the bulk sampling of the same donor state, to the last bit.
            var allBulk=outlets(kind,PhasePort.BULK,PhasePort.BULK,false);
            var reference=PipeTransfer.sample(allBulk,result.states(),flows,.5).get(2).forward();
            assertBitwise(reference,bulk,kind+" bulk line");
            // The reconstruction booked the frozen candidate stream on the solved flow (no junction, so pinned = solved):
            // the same phase split the transfer samples on the reconstructed end state, to the Newton's tolerance.
            var booked=new HashMap<Long,double[]>();for(var b:result.boundaries())booked.put(b.nodeId(),b.moles());
            double bookedWorst=0;
            for(var entry:Map.of(2L,vapor,3L,liquid,4L,bulk).entrySet()){var n=booked.get(entry.getKey());var moved=entry.getValue().componentMoles();
                for(int c=0;c<n.length;c++)bookedWorst=Math.max(bookedWorst,Math.abs(-n[c]-moved[c])/Math.max(1e-12,sum(moved)));}
            System.out.println("PHASE_PORT_STEP "+kind+" composition="+worst+" booked-vs-sampled="+bookedWorst+" flows="+Arrays.toString(flows));
            assertTrue(bookedWorst<1e-8,kind+": booked and sampled streams differ by "+bookedWorst);
            // Ledger: the vessel lost exactly what the voids received.
            var before=graph.reservoirs().getFirst().inventory().moles();var after=result.inventories().getFirst().moles();
            double[] external=new double[mw.length];for(var b:result.boundaries()){var n=b.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];}
            for(int c=0;c<mw.length;c++)assertEquals(before[c]+external[c],after[c],COMPONENT_LEDGER*Math.max(1,before[c]),kind+" component "+c);
        }
    }

    @Test void phasePortLinesIntegrateAndCloseTheirLedgerAtTenthSecondSlices(){integrate(.1,100);}
    @Test void phasePortLinesIntegrateAndCloseTheirLedgerAtFiveSecondSlices(){integrate(5,2);}

    /**
     * Ten seconds of draining, cut into {@code count} slices of {@code interval}, each a job as the island runtime runs it
     * ({@link PassiveIntervalSolver#replayStart} from the committed interval). Every slice must commit, close the ledger,
     * and every line must carry only its phase, with the drawn phase's composition that of the vessel.
     */
    private void integrate(double interval,int count) {
        for(var kind:Vessel.values()) {
            var graph=outlets(kind,PhasePort.VAPOR,PhasePort.LIQUID,false);
            double[] initial=graph.reservoirs().getFirst().inventory().moles(),external=new double[mw.length];
            double initialEnergy=energy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0,worstComposition=0,worstExcess=0;int accepted=0,rejected=0;
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
            for(int slice=0;slice<count;slice++) {
                var start=graph.reservoirs().getFirst().state();
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
                accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();
                for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
                var now=graph.reservoirs().getFirst().inventory().moles();
                for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);worstMoles=Math.max(worstMoles,error);
                    assertTrue(error<COMPONENT_LEDGER,kind+" slice "+slice+": component "+c+" ledger "+error);}
                double error=Math.abs(energy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));worstEnergy=Math.max(worstEnergy,error);
                assertTrue(error<ENERGY_LEDGER,kind+" slice "+slice+": energy ledger "+error);
                var byId=new HashMap<Long,PipeTransfer>();for(var t:result.pipeTransfers())byId.put(t.pipeId(),t);
                var vapor=byId.get(VAPOR_LINE);var liquid=byId.get(LIQUID_LINE);var bulk=byId.get(BULK_LINE);
                for(var t:List.of(vapor,liquid,bulk))assertEquals(0,t.reverse().massKg(),kind+" slice "+slice+": every line drains the vessel");
                assertZero(vapor.forward().phaseMoles()[0],kind+" vapour line hydrocarbon liquid");assertZero(vapor.forward().phaseMoles()[1],kind+" vapour line free water");
                assertZero(liquid.forward().phaseMoles()[2],kind+" liquid line vapour");
                for(int p=0;p<3;p++)if(bulk.forward().phaseVolumes()[p]==0)assertEquals(0,vapor.forward().phaseVolumes()[p]+liquid.forward().phaseVolumes()[p],kind+" bulk line phase "+p);
                assertTrue(bulk.forward().phaseVolumes()[0]+bulk.forward().phaseVolumes()[1]>0&&bulk.forward().phaseVolumes()[2]>0,kind+": the bulk line carries both phases");
                // The drawn phase's composition is the vessel's. A step samples its line on the step's end state, so a
                // slice's line carries the average of its steps' end compositions: component by component it may stand off
                // the slice's end composition only by what that composition moved over the slice (start to end).
                var end=graph.reservoirs().getFirst().state();
                double[] lineVapor=vapor.forward().phaseMoles()[2],lineLiquid=add(liquid.forward().phaseMoles()[0],liquid.forward().phaseMoles()[1]);
                worstComposition=Math.max(worstComposition,Math.max(fractionGap(lineVapor,vaporOf(end)),fractionGap(lineLiquid,liquidOf(end))));
                worstExcess=Math.max(worstExcess,Math.max(excess(lineVapor,vaporOf(start),vaporOf(end)),excess(lineLiquid,liquidOf(start),liquidOf(end))));
            }
            System.out.println("PHASE_PORT_INTEGRATION "+kind+" interval="+interval+" slices="+count+" accepted="+accepted+" rejected="+rejected
                    +" worstComponentLedger="+worstMoles+" worstEnergyLedger="+worstEnergy+" worstCompositionGap="+worstComposition+" worstGapBeyondDrift="+worstExcess);
            assertTrue(worstExcess<=1e-12,kind+" at "+interval+" s: a line's mole fractions left its phase's by "+worstExcess+" more than the vessel's own drift");
        }
    }
    private double[] vaporOf(FluidThermodynamics.State s){double[] y=Arrays.copyOf(s.vapor(),mw.length);y[water]=s.waterVapor();return y;}
    private double[] liquidOf(FluidThermodynamics.State s){double[] x=Arrays.copyOf(s.liquid(),mw.length);x[water]=s.waterLiquid();return x;}
    /** How far, beyond the drift {@code |x_start - x_end|} of each mole fraction over the slice, the line's mole fractions
     * stand off the slice's end composition. */
    private static double excess(double[] moved,double[] start,double[] end) {
        double a=sum(moved),b=sum(start),c=sum(end),worst=0;
        for(int i=0;i<moved.length;i++)worst=Math.max(worst,Math.abs(moved[i]/a-end[i]/c)-Math.abs(start[i]/b-end[i]/c));
        return worst;
    }

    /**
     * Phase ports feeding owned-holdup junctions: the vessel's vapour and liquid each reach a void through a junction. This
     * exercises the reconstruction's frozen stream booked into a junction's vessel row on the pinned flows, and the cold
     * start's rate solve on the vessel's port copy (a PORT node drawing a phase, booked as a fixed donor). Every slice
     * commits and the ledger (vessel plus the junctions' owned holdups plus the boundaries) closes; each junction's
     * outlet carries its port's phase.
     *
     * <p>Each junction is seeded with the fluid its port delivers (the headspace gas, humid, and the liquid), flashed at
     * 150 kPa. A junction seeded without water carries no water unknown ({@code PhaseLayout}), and one that is then fed
     * an unsaturated water-bearing gas fails the reconstruction's equation gate at every step size: a defect of the base
     * (reproduced on 6e1c5b6 with a humid nitrogen vessel drawn through a BULK end), not of the ports, reported in
     * documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md (WP1, open defect).
     */
    @Test void phasePortsFeedJunctionsAndCloseTheLedger() {
        for(double interval:new double[]{.1,5}) {
            int count=interval==5?2:20;
            var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);var tank=vessel(Vessel.WATER_UNDER_NITROGEN);
            var liquidSeed=model.flashTP(298.15,150000,liquidOf(tank),NOOP);var gasSeed=model.flashTP(298.15,150000,vaporOf(tank),NOOP);
            var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,gasSeed,PassiveNetwork.NodeKind.JUNCTION),
                    new PassiveNetwork.Reservoir(3,0,liquidSeed,PassiveNetwork.NodeKind.JUNCTION),
                    new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(5,0,sink,PassiveNetwork.NodeKind.VOID));
            var graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(
                    new PassiveNetwork.Pipe(20,0,1,List.of(LINE),new FlowControl.Passive(),0,null,PhasePort.VAPOR,PhasePort.BULK),new PassiveNetwork.Pipe(21,1,3,LINE),
                    new PassiveNetwork.Pipe(22,0,2,List.of(LINE),new FlowControl.Passive(),0,null,PhasePort.LIQUID,PhasePort.BULK),new PassiveNetwork.Pipe(23,2,4,LINE))),model);
            double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0,worstMoles=0,worstEnergy=0;
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
            for(int slice=0;slice<count;slice++) {
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
                for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
                var now=owned(graph);
                for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);worstMoles=Math.max(worstMoles,error);
                    assertTrue(error<COMPONENT_LEDGER,interval+" s slice "+slice+": component "+c+" ledger "+error);}
                double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));worstEnergy=Math.max(worstEnergy,error);
                assertTrue(error<ENERGY_LEDGER,interval+" s slice "+slice+": energy ledger "+error);
                var byId=new HashMap<Long,PipeTransfer>();for(var t:result.pipeTransfers())byId.put(t.pipeId(),t);
                assertZero(byId.get(20L).forward().phaseMoles()[0],"vapour port hydrocarbon liquid");assertZero(byId.get(20L).forward().phaseMoles()[1],"vapour port free water");
                assertZero(byId.get(22L).forward().phaseMoles()[2],"liquid port vapour");
                assertTrue(byId.get(20L).forward().massKg()>0&&byId.get(22L).forward().massKg()>0,"both ports drain the vessel");
            }
            var gasOut=committed.pipeTransfers().stream().filter(t->t.pipeId()==21).findFirst().orElseThrow().forward().componentMoles();
            var waterOut=committed.pipeTransfers().stream().filter(t->t.pipeId()==23).findFirst().orElseThrow().forward().componentMoles();
            System.out.println("PHASE_PORT_JUNCTIONS interval="+interval+" worstComponentLedger="+worstMoles+" worstEnergyLedger="+worstEnergy
                    +" gasLineNitrogenFraction="+gasOut[nitrogen]/sum(gasOut)+" liquidLineWaterFraction="+waterOut[water]/sum(waterOut));
            assertTrue(gasOut[nitrogen]/sum(gasOut)>.9,"the vapour junction's outlet carries the headspace gas");
            assertTrue(waterOut[water]/sum(waterOut)>.999,"the liquid junction's outlet carries the water");
        }
    }
    /** Tank and junction inventories (the junctions' owned holdups count like a tank's). */
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

    /**
     * Solids leave through a liquid port and never through a vapour port (plan 3.8): a vessel of water carrying 100 kg of
     * the demo particle under nitrogen drains its liquid through a filter and its headspace through a plain line. The
     * vapour line carries no solid, the filter captures what the liquid line carries, and the solid, component and energy
     * ledgers close (the solid event integrator's rate solves run on the vessel's port copy with its ports).
     */
    @Test void solidsLeaveThroughTheLiquidPortOnly() {
        var solids=new com.wormzjl.createcheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.createcheme.science.fluid.state.SolidInventory.Population(
                model.solids.require("createcheme:demo_particle"),com.wormzjl.createcheme.science.fluid.state.ParticleSize.micrometres("100"),100)));
        var tank=vessel(Vessel.WATER_UNDER_NITROGEN).withSolids(solids);
        var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID));
        var graph=new PassiveNetwork(nodes,List.of(
                new PassiveNetwork.Pipe(VAPOR_LINE,0,1,List.of(LINE),new FlowControl.Passive(),0,null,PhasePort.VAPOR,PhasePort.BULK),
                new PassiveNetwork.Pipe(LIQUID_LINE,0,2,List.of(new PipeResistance.Geometry(2,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)),new FlowControl.Passive(),0,InlineFilter.empty(),PhasePort.LIQUID,PhasePort.BULK)));
        double[] initial=graph.reservoirs().getFirst().inventory().moles(),external=new double[mw.length];
        double initialSolids=graph.reservoirs().getFirst().inventory().solids().massKg(),initialEnergy=energy(graph),externalEnergy=0,boundarySolids=0;
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int slice=0;slice<4;slice++) {
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,1,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();boundarySolids+=transfer.solidDirection()*transfer.solids().massKg();}
            var byId=new HashMap<Long,PipeTransfer>();for(var t:result.pipeTransfers())byId.put(t.pipeId(),t);
            assertTrue(byId.get(VAPOR_LINE).forward().solids().empty(),"slice "+slice+": the vapour port carries no solid");
            assertTrue(byId.get(LIQUID_LINE).forward().solids().massKg()>0,"slice "+slice+": the liquid port carries the solids");
            var now=graph.reservoirs().getFirst().inventory().moles();
            for(int c=0;c<now.length;c++)assertEquals(initial[c]+external[c],now[c],COMPONENT_LEDGER*Math.max(1,initial[c]),"slice "+slice+" component "+c);
        }
        var filter=graph.pipes().get(1).filter();double vesselSolids=graph.reservoirs().getFirst().inventory().solids().massKg();
        double filterEnergy=filter.energyJoule();
        System.out.println("PHASE_PORT_SOLIDS vessel="+vesselSolids+" captured="+filter.captured().massKg()+" boundary="+boundarySolids+" initial="+initialSolids);
        assertTrue(filter.captured().massKg()>0,"the filter captured what the liquid port carried");
        assertEquals(initialSolids,vesselSolids+filter.captured().massKg()-boundarySolids,1e-10*initialSolids,"solid ledger");
        assertEquals(initialEnergy+externalEnergy,energy(graph)+filterEnergy,ENERGY_LEDGER*Math.abs(initialEnergy),"energy ledger (vessel plus cake)");
    }

    /**
     * A phase port that carries nothing is inert: with the vapour and liquid lines closed both ways, the vessel, its bulk
     * line and every boundary evolve to the last bit as they do with bulk ends on those closed lines. (A closed connection
     * neither builds a phase stream nor costs its node a structural zero.) Stepped with the step solver, five 1 s steps
     * and ten 0.1 s steps: an island holding a hydrocarbon liquid is integrated by {@link SolidEventIntegrator}, which
     * reconsiders every closure at each interval start and so reopens a caller's both-way block; the step solver keeps it.
     * The water vessel (no hydrocarbon liquid) also runs through the interval solver at 5 s and 0.1 s slices.
     */
    @Test void closedPhasePortsLeaveTheBulkLineBitwise() {
        for(var kind:Vessel.values())for(double dt:new double[]{1,.1}) {
            int count=dt==1?5:10;
            var phase=steps(outlets(kind,PhasePort.VAPOR,PhasePort.LIQUID,true),dt,count);
            var bulk=steps(outlets(kind,PhasePort.BULK,PhasePort.BULK,true),dt,count);
            assertEquals(bulk,phase,kind+" at "+dt+" s steps: closed phase ports must leave the island bitwise");
        }
        for(double interval:new double[]{5,.1}) {
            int count=interval==5?2:20;
            var phase=run(outlets(Vessel.WATER_UNDER_NITROGEN,PhasePort.VAPOR,PhasePort.LIQUID,true),interval,count);
            var bulk=run(outlets(Vessel.WATER_UNDER_NITROGEN,PhasePort.BULK,PhasePort.BULK,true),interval,count);
            assertEquals(bulk,phase,"water vessel at "+interval+" s slices: closed phase ports must leave the island bitwise");
        }
    }
    /** Every double of {@code count} chained backward-Euler steps, each graph carrying the previous step's states and
     * owned inventories forward, as raw bits (see {@link #bits}). */
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
    /** Every double of every committed slice, as raw bits (a zero of either sign read as +0: a zero-flow line books
     * {@code 0*h}, whose sign is the sign of the enthalpy it multiplies). */
    private List<Long> run(PassiveNetwork graph,double interval,int count) {
        var bits=new ArrayList<Long>();var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int slice=0;slice<count;slice++) {
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
            bits.add((long)result.acceptedSubsteps());bits.add((long)result.rejectedSubsteps());
            for(var node:graph.reservoirs()){var s=node.state();
                for(double v:new double[]{s.temperature(),s.pressure(),s.mass(),s.volume(),s.liquidVolume(),s.waterVolume(),s.vaporVolume(),node.inventory().internalEnergy()})bits.add(bits(v));
                for(double n:node.inventory().moles())bits.add(bits(n));}
            for(double q:result.averageMassFlows())bits.add(bits(q));
            for(var b:result.boundaries()){bits.add(b.nodeId());bits.add(bits(b.totalEnergyJoule()));for(double n:b.moles())bits.add(bits(n));}
            for(var t:result.pipeTransfers())for(var s:List.of(t.forward(),t.reverse())){bits.add(bits(s.massKg()));for(var p:s.phaseMoles())for(double n:p)bits.add(bits(n));for(double v:s.phaseVolumes())bits.add(bits(v));}
        }
        return bits;
    }
    private static long bits(double v){return Double.doubleToRawLongBits(v==0?0:v);}

    private double energy(PassiveNetwork graph) {
        var node=graph.reservoirs().getFirst();
        return node.inventory().internalEnergy()+mass(node.inventory().moles())*PassiveStepSolver.GRAVITY*node.elevation();
    }
    private double mass(double[] moles){double m=0;for(int c=0;c<moles.length;c++)m+=moles[c]*mw[c];return m;}
    private static double sum(double[] v){double s=0;for(double x:v)s+=x;return s;}
    private static double[] add(double[] a,double[] b){var r=a.clone();for(int i=0;i<r.length;i++)r[i]+=b[i];return r;}
    private static void assertZero(double[] values,String what){for(double v:values)assertEquals(0,v,what);}
    /** The largest relative gap between {@code moved} and a multiple of {@code phase}: 0 when the line carries exactly
     * the phase's composition. */
    private static double proportional(double[] moved,double[] phase) {
        double scale=sum(moved)/sum(phase),worst=0;
        for(int c=0;c<moved.length;c++)worst=Math.max(worst,Math.abs(moved[c]-scale*phase[c])/Math.max(1e-300,sum(moved)));
        return worst;
    }
    /** The largest absolute gap between the mole fractions of {@code moved} and of {@code phase}. */
    private static double fractionGap(double[] moved,double[] phase) {
        double a=sum(moved),b=sum(phase),worst=0;
        for(int c=0;c<moved.length;c++)worst=Math.max(worst,Math.abs(moved[c]/a-phase[c]/b));
        return worst;
    }
    private static void assertBitwise(PipeTransfer.Stream expected,PipeTransfer.Stream actual,String what) {
        assertEquals(Double.doubleToRawLongBits(expected.massKg()),Double.doubleToRawLongBits(actual.massKg()),what+" mass");
        var e=expected.phaseMoles();var a=actual.phaseMoles();
        for(int p=0;p<3;p++){for(int c=0;c<e[p].length;c++)assertEquals(Double.doubleToRawLongBits(e[p][c]),Double.doubleToRawLongBits(a[p][c]),what+" phase "+p+" component "+c);
            assertEquals(Double.doubleToRawLongBits(expected.phaseVolumes()[p]),Double.doubleToRawLongBits(actual.phaseVolumes()[p]),what+" volume "+p);}
        assertEquals(expected.solids(),actual.solids(),what+" solids");
    }
}
