package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.transport.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolidTransportAcceptanceTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private SolidInventory stock(int count,double total) {
        var entries=new ArrayList<SolidInventory.Population>();
        for(int i=0;i<count;i++)entries.add(new SolidInventory.Population(model.solids.require("createcheme:demo_particle"),ParticleSize.micrometres(Integer.toString(i+1)),total/count));
        return new SolidInventory(entries);
    }
    private FluidThermodynamics.State water(double p) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(350,p,n,()->{}).volume();
        return model.flashTP(350,p,n,()->{});
    }
    private PassiveNetwork.Pipe pipe(){return new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0));}
    @Test void suspensionBoundaryIsStrictAndReversalUsesTheSameLiquidVelocity() {
        var state=water(160000).withSolids(stock(1,100));var pipe=pipe();
        double dep=SolidMobility.check(model,state,pipe,0,SolidMobility.Outlet.MIXED).minimumVelocity();
        double q=dep*pipe.maximumArea()*state.mass()/state.waterVolume();
        assertFalse(SolidMobility.check(model,state,pipe,q*(1-1e-10),SolidMobility.Outlet.MIXED).allowed());
        assertTrue(SolidMobility.check(model,state,pipe,q*(1+1e-10),SolidMobility.Outlet.MIXED).allowed());
        assertTrue(SolidMobility.check(model,state,pipe,-q*(1+1e-10),SolidMobility.Outlet.MIXED).allowed());
        assertEquals(SolidMobility.Reason.DEPOSITION,SolidMobility.check(model,state,pipe,0,SolidMobility.Outlet.MIXED).reason());
    }
    @Test void dryParticlesBlockGasButTracesStillTravelAndRetainTheirSlots() {
        var gas=model.initialNitrogenCharge(1,298.15,101325,()->{});
        assertEquals(SolidMobility.Reason.NO_LIQUID_CARRIER,SolidMobility.check(model,gas.withSolids(stock(1,1)),pipe(),1,SolidMobility.Outlet.MIXED).reason());
        var traces=gas.withSolids(stock(64,1e-12));
        assertTrue(SolidMobility.check(model,traces,pipe(),1,SolidMobility.Outlet.MIXED).allowed());
        assertEquals(64,traces.solids().populations().size());assertTrue(traces.mass()>gas.mass());
    }
    @Test void reverseFiltrationCapturesTheOtherDonorAndConservesEnergy() {
        var source=water(160000).withSolids(stock(8,100));var sink=water(150000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,sink),new PassiveNetwork.Reservoir(2,0,source,PassiveNetwork.NodeKind.GENERATOR)),
                List.of(pipe().withFilter(InlineFilter.empty())));
        var result=new PassiveStepSolver(model).solve(graph,.001,()->{});
        assertTrue(result.massFlows()[0]<0);assertTrue(result.inventories().getFirst().solids().empty());
        var captured=result.filters().get(3L);assertEquals(8,captured.captured().populations().size());
        double mass=result.boundaries().stream().mapToDouble(b->b.solids().massKg()*b.solidDirection()).sum();
        assertEquals(mass,captured.captured().massKg(),1e-9);
        double before=sink.internalEnergy(),after=result.inventories().getFirst().internalEnergy()+captured.energyJoule();
        assertEquals(result.externalEnergyJoule(),after-before,1e-5);
    }
    @Test void drainingSlurryClosesAtAReproducibleInteriorEvent() {
        var solids=new SolidInventory(List.of(new SolidInventory.Population(model.solids.require("createcheme:demo_particle"),ParticleSize.micrometres("100"),100)));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,water(160000).withSolids(solids)),new PassiveNetwork.Reservoir(2,0,water(150000))),List.of(pipe()));
        var first=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var second=new PassiveIntervalSolver(model).solve(graph,.05,new PassiveIntervalSolver.Settings(.0005,.001,1024),()->{});
        assertNotEquals(0,first.graph().pipes().getFirst().blockedDirections());
        assertTrue(first.averageMassFlows()[0]>0);
        assertTrue(first.rejectionReasons().keySet().stream().anyMatch(s->s.contains("DEPOSITION")));
        assertEquals(first.graph().reservoirs().get(1).inventory().solids().massKg(),second.graph().reservoirs().get(1).inventory().solids().massKg(),2e-6);
        double after=first.graph().reservoirs().stream().mapToDouble(r->r.inventory().solids().massKg()).sum();
        assertEquals(100,after,1e-9);
    }
    @Test void equalParallelBranchesChooseTheSameClosuresAcrossReplays() {
        var source=water(150001).withSolids(stock(64,100));
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,water(150000),PassiveNetwork.NodeKind.VOID));
        var pipes=List.of(pipe(),new PassiveNetwork.Pipe(4,0,1,pipe().sections(),new FlowControl.Passive()));
        var graph=new PassiveNetwork(nodes,pipes);
        var a=new PassiveIntervalSolver(model).solve(graph,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        var b=new PassiveIntervalSolver(model).solve(graph,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(a.graph().pipes(),b.graph().pipes());assertArrayEquals(a.averageMassFlows(),b.averageMassFlows());
        assertTrue(a.graph().pipes().stream().allMatch(p->p.blockedDirections()!=0));
    }
    @Test void highViscosityBlocksTheMixedOutletBeforeSlurryThickening() {
        var n=new double[model.componentCount()];n[model.components().indexOf("crude_pc12")]=1;
        var cold=model.flashTP(330,150000,n,()->{});var hot=model.flashTP(340,150000,n,()->{});
        assertEquals(SolidMobility.Reason.IMMOBILE_LIQUID,SolidMobility.check(model,cold,pipe(),1,SolidMobility.Outlet.MIXED).reason());
        assertTrue(SolidMobility.check(model,hot,pipe(),1,SolidMobility.Outlet.MIXED).allowed());
        assertTrue(SolidMobility.check(model,cold,pipe(),1,SolidMobility.Outlet.GAS).allowed());
        assertTrue(SolidMobility.requiresFull(model,new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,cold)),List.of())));
    }
    @Test void populationCountDoesNotIncreaseTheNonlinearDimension() throws Exception {
        int dimension=-1,unknowns=-1,colors=-1;
        var report=new ArrayList<Map<String,Object>>();
        for(int count:new int[]{1,8,64}) {
            var source=water(160000).withSolids(stock(count,100));
            var layout=new PhaseLayout(model,source);
            if(dimension<0)dimension=layout.size();else assertEquals(dimension,layout.size());
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,water(150000))),List.of(pipe()));
            var result=new PassiveStepSolver(model).solve(graph,.001,()->{});
            var times=new ArrayList<Double>();for(int repeat=0;repeat<5;repeat++){long start=System.nanoTime();result=new PassiveStepSolver(model).solve(graph,.001,()->{});times.add((System.nanoTime()-start)/1e6);}
            if(unknowns<0){unknowns=result.numerical().variables().length;colors=result.numerical().colors();}
            assertEquals(unknowns,result.numerical().variables().length);assertEquals(colors,result.numerical().colors());
            assertEquals(count,result.inventories().get(1).solids().populations().size());
            assertEquals(result.inventories().get(1).solids().moments(),result.states().get(1).solidMoments());
            report.add(Map.of("populations",count,"layout",dimension,"nonlinearUnknowns",unknowns,"jacobianColors",colors,"warmMilliseconds",times));
        }
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/fluid"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/fluid/solid-populations.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    /** {@code count} distinct grades from {@code from} micrometres up, each so light that its
     * volume fraction is orders below the trace cutoff: conserved and transported, never active
     * for blockage, so what closes here can only be the population limit. */
    private SolidInventory traceGrades(int from,int count) {
        var entries=new ArrayList<SolidInventory.Population>();
        for(int i=0;i<count;i++)entries.add(new SolidInventory.Population(model.solids.require("createcheme:demo_particle"),
                ParticleSize.micrometres(Integer.toString(from+i)),1e-12));
        return new SolidInventory(entries);
    }
    @Test void aSixtyFifthPopulationClosesAFeedInsteadOfStallingTheInterval() {
        // Two feeds of 64 grades each into one reservoir. Their union is 128 and a conserved stock
        // holds 64, so the reconstruction refused every step of every interval and no smaller step
        // could ever make one work: the island was held forever. It is a feasibility statement
        // about the connection, so it closes one like any other transport failure does.
        var run=new PipeResistance.Geometry(100,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,water(200000).withSolids(traceGrades(1,64)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(190000).withSolids(traceGrades(65,64)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,water(150000)),
                new PassiveNetwork.Reservoir(4,0,water(100000),PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(10,0,2,run),new PassiveNetwork.Pipe(11,1,2,run),new PassiveNetwork.Pipe(12,2,3,run)));
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});

        assertEquals(5,result.advancedSeconds());
        assertTrue(result.rejectionReasons().keySet().stream().anyMatch(k->k.contains("POPULATION_LIMIT")),
                "The limit must be declared as a closure: "+result.rejectionReasons());
        var receiver=result.graph().reservoirs().get(2).inventory().solids();
        assertTrue(receiver.populations().size()<=SolidInventory.MAXIMUM_POPULATIONS,
                "The receiver holds "+receiver.populations().size()+" populations");
        assertFalse(receiver.empty(),"The surviving feed must still deliver");
        assertEquals(1,result.graph().pipes().stream().filter(p->p.blockedDirections()!=0).count(),
                "Only the one feed the deterministic rule names may close: "+result.graph().pipes());
        System.out.println("solid populations: "+receiver.populations().size()+" held, reasons="+result.rejectionReasons());
    }
    @Test void theFlaggedFeedThatCarriesTheKeysNobodyElseSuppliesIsTheOneThatCloses() {
        // A receiver already holding sixty grades, fed four new ones by one connection and ten by
        // another. The union is 74 and a conserved stock holds 64, so both feeds are flagged; but
        // closing the four-grade feed leaves 70, still over the limit, and the ten-grade feed then
        // closes on the next pass - two closures, one of them for a delivery that always fit.
        // Only the feed whose keys nobody else supplies has to stop.
        var run=new PipeResistance.Geometry(100,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,water(200000).withSolids(traceGrades(1,4)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(200000).withSolids(traceGrades(5,10)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,water(150000).withSolids(traceGrades(101,60))),
                new PassiveNetwork.Reservoir(4,0,water(100000),PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(10,0,2,run),new PassiveNetwork.Pipe(11,1,2,run),new PassiveNetwork.Pipe(12,2,3,run)));
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});

        assertEquals(5,result.advancedSeconds());
        var closed=result.graph().pipes().stream().filter(p->p.blockedDirections()!=0).toList();
        assertEquals(1,closed.size(),"Only one feed may close: "+result.graph().pipes());
        assertEquals(11,closed.getFirst().id(),"The ten-grade feed is the one whose keys nobody else supplies");
        assertTrue(result.averageMassFlows()[0]>0,"The four-grade feed still delivers");
        assertTrue(result.rejectionReasons().keySet().stream().anyMatch(k->k.contains("POPULATION_LIMIT")&&k.contains("pipe=11")),
                "The closure names the connection it closed: "+result.rejectionReasons());
        var receiver=result.graph().reservoirs().get(2).inventory().solids();
        assertEquals(SolidInventory.MAXIMUM_POPULATIONS,receiver.populations().size(),
                "Sixty held plus four delivered is exactly what a conserved stock holds");
    }
    @Test void closedParticlesRestartWhenDrivingPressureIncreases() {
        var source=water(150001).withSolids(stock(64,100));var sink=water(150000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID)),List.of(pipe()));
        var stopped=new PassiveIntervalSolver(model).solve(graph,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,stopped.averageMassFlows()[0],1e-10);
        assertNotEquals(0,stopped.graph().pipes().getFirst().blockedDirections());
        var driven=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,water(250000).withSolids(source.solids()),PassiveNetwork.NodeKind.GENERATOR),graph.reservoirs().get(1)),stopped.graph().pipes());
        var restarted=new PassiveIntervalSolver(model).solve(driven,.01,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(restarted.averageMassFlows()[0]>0);assertEquals(0,restarted.graph().pipes().getFirst().blockedDirections());
    }
}
