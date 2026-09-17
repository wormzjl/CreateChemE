package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VelocityClampTest {
    private FluidThermodynamics model(double limit){return FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9,limit);}
    private FluidThermodynamics.State state(FluidThermodynamics model,double pressure,boolean wet) {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];if(wet){n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane_nitrogen").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.2;}else n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var unit=model.flashTP(350,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();return model.flashTP(350,pressure,n,()->{});
    }
    private PassiveNetwork graph(FluidThermodynamics model,boolean reverse,boolean wet,FlowControl control,boolean finite) {
        var a=state(model,reverse?101325:200000,wet);var b=state(model,reverse?200000:101325,wet);
        var kind=finite?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.PORT;
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a,kind),new PassiveNetwork.Reservoir(2,0,b,kind)),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(.1,.01,0,0),control)));
    }
    private void bounded(FluidThermodynamics model,PassiveNetwork graph,PassiveStepSolver.Result result) {
        var pipe=graph.pipes().getFirst();double q=result.massFlows()[0];var donor=result.states().get(q>=0?0:1);
        double limit=donor.mass()/donor.volume()*pipe.minimumArea()*model.velocityLimit(donor);
        assertTrue(Math.abs(q)<=limit*(1+2e-8)+1e-12,"q="+q+" cap="+limit);
    }
    @Test void configuredAndAcousticLimitsCapBothDirectionsAndPreserveExternalLedgers() {
        for(double limit:new double[]{100,100000})for(boolean reverse:new boolean[]{false,true}) {
            var model=model(limit);var graph=graph(model,reverse,false,new FlowControl.Passive(),false);
            var result=new PassiveStepSolver(model).solve(graph,1,()->{});bounded(model,graph,result);
            var donor=result.states().get(reverse?1:0);double expected=donor.mass()/donor.volume()*graph.pipes().getFirst().minimumArea()*model.velocityLimit(donor);
            assertEquals(reverse?-expected:expected,result.massFlows()[0],expected*1e-8+1e-10);assertEquals(FlowControl.Mode.VELOCITY_LIMITED,result.modes().getFirst());
            assertArrayEquals(new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],result.externalMoles(),1e-9);assertEquals(0,result.externalEnergyJoule(),1e-6);
            assertEquals(350,result.states().getFirst().temperature()); // fixed boundary, not a clamp-imposed temperature
        }
    }
    @Test void threePhaseClampingTransfersAllPhasesAndDevicesRespectTheRateLimit() {
        var model=model(.2);
        for(FlowControl control:List.of(new FlowControl.Passive(),new FlowControl.Pump(1,500000,1),new FlowControl.PressureValve(150000))) {
            var graph=graph(model,false,true,control,false);var result=new PassiveStepSolver(model).solve(graph,.1,()->{});bounded(model,graph,result);
            var stream=result.pipeTransfers().getFirst().forward();for(double volume:stream.phaseVolumes())assertTrue(volume>0);
            double[] moved=stream.componentMoles();var donor=graph.reservoirs().getFirst();var n=donor.inventory().moles();
            for(int c=0;c<n.length;c++)assertEquals(stream.massKg()*n[c]/donor.state().mass(),moved[c],1e-10+1e-8*Math.abs(moved[c]));
            assertArrayEquals(new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],result.externalMoles(),1e-9);assertEquals(-result.pumpWorkJoule(),result.externalEnergyJoule(),1e-4);
        }
    }
    @Test void finiteReservoirTemperatureComesFromConservedEnergyAndEosAtTheClampedRate() {
        var model=model(1);var graph=graph(model,false,false,new FlowControl.Passive(),true);
        var result=new PassiveStepSolver(model).solve(graph,1,()->{});bounded(model,graph,result);
        double beforeU=0,afterU=0,beforeN=0,afterN=0;
        for(int i=0;i<2;i++) {
            var old=graph.reservoirs().get(i);var next=result.inventories().get(i);var state=result.states().get(i);
            beforeU+=old.inventory().internalEnergy();afterU+=next.internalEnergy();beforeN+=old.inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN];afterN+=next.moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN];
            var eos=model.flashTP(state.temperature(),state.pressure(),next.moles(),()->{});
            assertEquals(next.volume(),eos.volume(),1e-8);assertEquals(next.internalEnergy(),eos.internalEnergy(),1e-4+1e-6*Math.abs(next.internalEnergy()));
        }
        assertEquals(beforeN,afterN,1e-10+1e-8*beforeN);assertEquals(beforeU,afterU,1e-4+1e-6*Math.abs(beforeU));
        assertTrue(Math.abs(result.states().getFirst().temperature()-350)>1e-5,"The clamp must not hold temperature fixed");
    }
    @Test void invalidConfigurationIsRejectedAndDifferentLimitsInvalidateFallbackAnchors() {
        assertThrows(IllegalArgumentException.class,()->model(0));assertThrows(IllegalArgumentException.class,()->model(Double.NaN));
        var model=model(100);var graph=graph(model,false,false,new FlowControl.Passive(),true);
        var full=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var anchor=ApproximationAnchor.fromFull(model,full);assertThrows(ApproximationRejected.class,()->anchor.guard(model(10),full.graph()));
        assertEquals(ApproximationAnchor.thermodynamicRevision(model),ApproximationAnchor.thermodynamicRevision(model(10)));
    }
    @Test void aWarmWorkspaceReleasesTheClampWhenThePressureDriveFalls() {
        var model=model(1);var solver=new PassiveStepSolver(model);var high=graph(model,false,false,new FlowControl.Passive(),false);
        var capped=solver.solve(high,1,()->{});assertEquals(FlowControl.Mode.VELOCITY_LIMITED,capped.modes().getFirst());
        var low=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state(model,101325.1,false),PassiveNetwork.NodeKind.PORT),new PassiveNetwork.Reservoir(2,0,state(model,101325,false),PassiveNetwork.NodeKind.PORT)),high.pipes());
        var released=solver.solve(low,1,()->{});assertEquals(FlowControl.Mode.PASSIVE,released.modes().getFirst());bounded(model,low,released);
        var donor=low.reservoirs().getFirst().state();double rho=donor.mass()/donor.volume(),mu=model.viscosity.vapor(350,donor.vapor(),0);
        double expected=.1*Math.PI*rho*Math.pow(.01,4)/(128*mu*.1);
        assertEquals(expected,released.massFlows()[0],1e-10+1e-4*expected);
        assertEquals(FlowControl.Mode.VELOCITY_LIMITED,solver.solve(high,1,()->{}).modes().getFirst());
    }
}
