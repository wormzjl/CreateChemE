package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolidConservationTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private SolidInventory particles(double mass) {
        return new SolidInventory(List.of(new SolidInventory.Population(MaterialCatalog.bundled().solids().require("createcheme:demo_particle"),ParticleSize.micrometres("100"),mass)));
    }
    private FluidThermodynamics.State water(double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        var unit=model.flashTP(350,pressure,n,()->{});n[n.length-1]/=unit.volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    @Test void coupledStepReconstructsParticlesAndAllThreeMomentsAtTheReceiver() {
        var source=water(160000).withSolids(particles(100));
        var sink=water(150000);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,sink)),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0))));
        var step=new PassiveStepSolver(model).solve(graph,.001,()->{});
        var received=step.inventories().get(1).solids();
        assertTrue(received.massKg()>0);
        double expected=step.massFlows()[0]*.001*100/source.mass();
        assertEquals(expected,received.massKg(),1e-9);
        assertEquals(received.moments(),step.states().get(1).solidMoments());
        assertEquals(received.massKg()/2500,received.volume(),1e-15);
        assertEquals(received.massKg()*800,received.moments().heatCapacity(),1e-8);
        assertTrue(graph.reservoirs().get(1).inventory().solids().empty());
    }
    @Test void adaptiveStagesAndPipeHistoryPreserveExactPopulations() {
        var source=water(160000).withSolids(particles(100));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(150000))),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0))));
        var result=new PassiveIntervalSolver(model).solve(graph,.001,new PassiveIntervalSolver.Settings(.0001,.0001,1024),()->{});
        double received=result.graph().reservoirs().get(1).inventory().solids().massKg();
        double external=result.boundaries().stream().mapToDouble(b->b.solidDirection()*b.solids().massKg()).sum();
        assertTrue(received>0);
        assertEquals(external,received,1e-9);
        assertEquals(result.pipeTransfers().getFirst().forward().solids().massKg(),received,1e-9);
    }
    @Test void filterCapturesEveryPopulationAndPassesOnlyFluid() {
        var source=water(160000).withSolids(particles(100));
        var pipe=new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(1,.05,.000045,0)).withFilter(InlineFilter.empty());
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(150000))),List.of(pipe));
        var step=new PassiveStepSolver(model).solve(graph,.001,()->{});
        assertTrue(step.inventories().get(1).solids().empty());
        var cake=step.filters().get(3L);
        assertTrue(cake.captured().massKg()>0);
        double supplied=step.boundaries().stream().mapToDouble(b->b.solidDirection()*b.solids().massKg()).sum();
        assertEquals(supplied,cake.captured().massKg(),1e-9);
        assertTrue(step.inventories().get(1).moles()[model.componentCount()-1]>graph.reservoirs().get(1).inventory().moles()[model.componentCount()-1]);
    }
    @Test void loadedFilterClosesWithoutLosingItsCapturedMaterial() {
        var source=water(160000).withSolids(particles(100));
        var filter=new InlineFilter(1e-5,1e6,SolidInventory.EMPTY,0);
        var pipe=new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(1,.05,.000045,0)).withFilter(filter);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(150000),PassiveNetwork.NodeKind.VOID)),List.of(pipe));
        var result=new PassiveIntervalSolver(model).solve(graph,1,new PassiveIntervalSolver.Settings(.001,.01,1024),()->{});
        var cake=result.graph().pipes().getFirst().filter();
        assertEquals(3,result.graph().pipes().getFirst().blockedDirections());
        assertTrue(cake.captured().volume()<=filter.capacity());
        assertEquals(filter.capacity(),cake.captured().volume(),1e-10);
        assertEquals(result.boundaries().stream().mapToDouble(b->b.solidDirection()*b.solids().massKg()).sum(),cake.captured().massKg(),1e-8);
    }
    @Test void canonicalSizesPreserveDistinctPopulationsAndTracesStillOccupySlots() {
        assertEquals(ParticleSize.micrometres("100"),new ParticleSize("0.0001000"));
        assertNotEquals(ParticleSize.micrometres("100"),ParticleSize.micrometres("100.0000001"));
        var material=MaterialCatalog.bundled().solids().require("createcheme:demo_particle");
        var entries=new ArrayList<SolidInventory.Population>();
        for(int i=1;i<=64;i++)entries.add(new SolidInventory.Population(material,ParticleSize.micrometres(Integer.toString(i)),1e-20));
        var full=new SolidInventory(entries);
        assertEquals(64,full.populations().size());
        assertThrows(IllegalArgumentException.class,()->full.plus(new SolidInventory(List.of(new SolidInventory.Population(material,ParticleSize.micrometres("65"),1)))));
        var portion=full.takeFraction(.3);
        assertEquals(full.massKg(),portion.delivered().massKg()+portion.remaining().massKg(),1e-32);
        assertEquals(64,portion.remaining().populations().size());
    }
}