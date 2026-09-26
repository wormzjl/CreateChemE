package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NetworkRegimeTest {
    @Test void serialSectionsPreserveIndependentLaminarAndFittingLosses() {
        var sections=List.of(new PipeResistance.Geometry(4,.02,.00001,2),new PipeResistance.Geometry(6,.01,.00002,3));
        var pipe=new PassiveNetwork.Pipe(1,0,1,sections,new FlowControl.Passive());
        double q=.0001,rho=1000,mu=.001,expected=0;
        for(var section:sections)expected+=128*mu*section.length()*q/(Math.PI*rho*Math.pow(section.diameter(),4))
                +section.minorLoss()*q*q/(2*rho*Math.pow(Math.PI*section.diameter()*section.diameter()/4,2));
        assertEquals(expected,pipe.loss(q,rho,mu).pressureDrop(),1e-12);
        assertEquals(-expected,pipe.loss(-q,rho,mu).pressureDrop(),1e-12);
    }
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final PipeResistance.Geometry geometry=new PipeResistance.Geometry(100,.02,.000045,0);
    private PassiveNetwork.Reservoir gas(long id,double t,double p,int component,PassiveNetwork.NodeKind kind) {
        var n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[component]=1;var unit=model.flashTP(t,p,n,()->{});n[component]/=unit.volume();
        return new PassiveNetwork.Reservoir(id,0,model.flashTP(t,p,n,()->{}),kind);
    }
    @Test void twoDifferentFeedsMixAtAZeroHoldupJunction() {
        var graph=new PassiveNetwork(List.of(gas(1,350,220000,0,PassiveNetwork.NodeKind.GENERATOR),gas(2,400,210000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),
                gas(3,350,195000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.JUNCTION),gas(4,350,180000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,2,geometry),new PassiveNetwork.Pipe(2,1,2,geometry),new PassiveNetwork.Pipe(3,2,3,geometry)));
        var result=new PassiveStepSolver(model).solve(graph,1,()->{});var q=result.massFlows();assertTrue(q[0]>0&&q[1]>0);
        assertEquals(q[0]+q[1],q[2],1e-9);var mixture=result.states().get(2);var n=PhaseLayout.totalAmounts(mixture);
        assertEquals(q[0]/q[2],n[0]*model.hydrocarbon.molecularWeight(0)/mixture.mass(),1e-8);
        double h=0;for(int i=0;i<2;i++){var feed=graph.reservoirs().get(i).state();h+=q[i]*feed.enthalpy()/feed.mass();}
        assertEquals(h/q[2],mixture.enthalpy()/mixture.mass(),.001);
    }
    @Test void reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable() {
        var reversed=new PassiveNetwork(List.of(gas(1,350,220000,0,PassiveNetwork.NodeKind.GENERATOR),gas(2,400,170000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),
                gas(3,350,195000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.JUNCTION),gas(4,350,180000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,2,geometry),new PassiveNetwork.Pipe(2,1,2,geometry),new PassiveNetwork.Pipe(3,2,3,geometry)));
        var back=new PassiveStepSolver(model).solve(reversed,.1,()->{});assertTrue(back.massFlows()[1]<0);
        assertEquals(0,PhaseLayout.totalAmounts(back.states().get(2))[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN],1e-9);assertTrue(back.inventories().get(1).moles()[0]>0);
        var serial=new PassiveNetwork(List.of(gas(1,350,250000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),gas(2,350,200000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.JUNCTION),gas(3,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,1,geometry,new FlowControl.PressureValve(200000)),new PassiveNetwork.Pipe(2,1,2,geometry,new FlowControl.PressureValve(180000))));
        var result=new PassiveStepSolver(model).solve(serial,1,()->{});assertTrue(result.massFlows()[0]>0);assertEquals(result.massFlows()[0],result.massFlows()[1],1e-9);
        assertEquals(FlowControl.Mode.VALVE_OPEN,result.modes().getFirst());
    }
    @Test void aRejectedTrialRestartsFromTheSameConservedStateAtTheSmallerStep() {
        var graph=new PassiveNetwork(List.of(gas(1,350,200000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(new PassiveNetwork.Pipe(1,0,1,geometry)));
        var calls=new AtomicInteger();var before=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList();
        var rejected=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{if(calls.incrementAndGet()==30)throw new com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence("Forced rejection");});
        var fresh=new PassiveIntervalSolver(model).solve(graph,1,new PassiveIntervalSolver.Settings(.5,20,.001,1024),()->{});
        assertTrue(rejected.rejectedSubsteps()>0);assertArrayEquals(fresh.averageMassFlows(),rejected.averageMassFlows(),1e-8);
        assertEquals(before,graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList());
    }
    @Test void pumpShutoffUsesAddedPressureAndTankRecycleIsAllowed() {
        var source=gas(1,350,1e6,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR);
        // A pump's setting is its rise for water (F4, P1): the setting that is 500 kPa on this generator's nitrogen.
        double setting=500000*model.pumpReferenceDensity()/(source.state().mass()/source.state().volume());
        for(double p:new double[]{1.49e6,1.51e6}) {
            var graph=new PassiveNetwork(List.of(source,gas(2,350,p,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(new PassiveNetwork.Pipe(1,0,1,geometry,new FlowControl.Pump(.001,setting,1))));
            var result=new PassiveStepSolver(model).solve(graph,1,()->{});
            if(p<1.5e6)assertTrue(result.massFlows()[0]>0);else assertEquals(0,result.massFlows()[0],1e-9);
        }
        var recycle=new PassiveNetwork(List.of(gas(1,350,150000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,150000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(
                new PassiveNetwork.Pipe(1,0,1,geometry,new FlowControl.Pump(.001,500000,1)),new PassiveNetwork.Pipe(2,1,0,geometry)));
        var result=new PassiveIntervalSolver(model).solve(recycle,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(result.averageMassFlows()[0]>0&&result.averageMassFlows()[1]>0);assertTrue(result.pumpWorkJoule()>0);
    }
    @Test void bypassCanPreventAValveFromMaintainingItsUpstreamSetpoint() {
        var graph=new PassiveNetwork(List.of(gas(1,350,180000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(
                new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(10,.05,.000045,0),new FlowControl.PressureValve(175000)),
                new PassiveNetwork.Pipe(2,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(result.graph().reservoirs().getFirst().state().pressure()<175000);assertTrue(result.averageMassFlows()[1]>0);
    }
    @Test void hotNitrogenCanEvaporateTheLastFreeWaterWithoutDiscardingIt() {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=40;n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=5;var initial=model.flashTP(298.15,150000,n,()->{});double scale=1/initial.volume();for(int i=0;i<n.length;i++)n[i]*=scale;
        var tank=new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,150000,n,()->{}));assertTrue(tank.state().waterLiquid()>0);
        var graph=new PassiveNetwork(List.of(gas(1,500,500000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),tank),List.of(new PassiveNetwork.Pipe(1,0,1,geometry)));
        for(int i=0;i<3;i++)graph=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        var end=graph.reservoirs().get(1);assertEquals(0,end.state().waterLiquid(),1e-9,"T="+end.state().temperature()+" P="+end.state().pressure());assertEquals(n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK],end.inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK],1e-8);
    }
    @Test void hydrocarbonLiquidCanDisappearIntoTheSharedGasPhase() {
        int pentane=model.hydrocarbon.components().indexOf("N-pentane");assertTrue(pentane>=0);
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=30;n[pentane]=50;var unit=model.flashTP(298.15,150000,n,()->{});for(int i=0;i<n.length;i++)n[i]/=unit.volume();
        var tank=new PassiveNetwork.Reservoir(2,0,model.flashTP(298.15,150000,n,()->{}));assertTrue(tank.state().liquidVolume()>0);
        var graph=new PassiveNetwork(List.of(gas(1,500,500000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),tank,gas(3,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(1,0,1,geometry),new PassiveNetwork.Pipe(2,1,2,geometry)));
        double removed=0;
        for(int i=0;i<6;i++) {var result=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),()->{});graph=result.graph();for(var entry:result.boundaries())if(entry.nodeId()==3)removed-=entry.moles()[pentane];}
        var end=graph.reservoirs().get(1);assertEquals(0,end.state().liquidVolume(),1e-12);assertEquals(n[pentane],end.inventory().moles()[pentane]+removed,1e-8);
    }
    /** The sonic case's generator state; its pump is given the setting that is 500 kPa on this gas (F4, P1). */
    private FluidThermodynamics.State sonicSource(){return gas(1,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR).state();}
    @Test void cancellationCannotLeavePartialTransfersAndExcessVelocityIsConservativelyLimited() {
        var graph=new PassiveNetwork(List.of(gas(1,350,200000,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR),gas(2,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.RESERVOIR)),List.of(new PassiveNetwork.Pipe(1,0,1,geometry)));
        var solver=new PassiveIntervalSolver(model);var count=new AtomicInteger();
        assertThrows(CancellationException.class,()->solver.solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{if(count.incrementAndGet()==30)throw new CancellationException();}));
        var retry=solver.solve(graph,1,new PassiveIntervalSolver.Settings(.1,.1,.001,1024),()->{});
        var fresh=new PassiveIntervalSolver(model).solve(graph,1,new PassiveIntervalSolver.Settings(.1,.1,.001,1024),()->{});
        assertArrayEquals(fresh.averageMassFlows(),retry.averageMassFlows(),1e-8);
        var sonic=new PassiveNetwork(List.of(gas(1,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.GENERATOR),gas(2,350,101325,com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(.1,.01,0,0),new FlowControl.Pump(1,500000*model.pumpReferenceDensity()/(sonicSource().mass()/sonicSource().volume()),1))));
        var capped=new PassiveStepSolver(model).solve(sonic,1,()->{});var upstream=sonic.reservoirs().getFirst().state();
        double expected=upstream.mass()/upstream.volume()*sonic.pipes().getFirst().minimumArea()*model.velocityLimit(upstream);
        assertEquals(expected,capped.massFlows()[0],1e-10+expected*1e-8);assertEquals(FlowControl.Mode.PUMP_VELOCITY_LIMIT,capped.modes().getFirst());
    }
}
