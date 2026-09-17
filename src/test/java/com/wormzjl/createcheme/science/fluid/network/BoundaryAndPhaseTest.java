package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.*;
import org.junit.jupiter.api.Test;

class BoundaryAndPhaseTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private PassiveNetwork.Pipe pipe(){return new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0));}
    private PassiveNetwork.Reservoir source(double pressure) {
        double[] n=new double[model.hydrocarbon.componentCount()+1];n[n.length-1]=1;
        return new PassiveNetwork.Reservoir(1,0,model.flashTP(298.15,pressure,n,()->{}),PassiveNetwork.NodeKind.GENERATOR);
    }
    @Test void sourceFillsNitrogenChargedVesselAndWaterAppearsWithoutLosingTheInitialNitrogen() {
        var initial=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var graph=new PassiveNetwork(List.of(source(200000),new PassiveNetwork.Reservoir(2,0,initial)),List.of(pipe()));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});var filled=result.states().get(1);
        int nitrogen=model.hydrocarbon.components().indexOf("Nitrogen"),water=model.hydrocarbon.componentCount();
        assertEquals(initial.vapor()[nitrogen],PhaseLayout.totalAmounts(filled)[nitrogen],1e-8);
        assertTrue(filled.waterLiquid()>0&&filled.waterVapor()>0);assertTrue(result.massFlows()[0]>0);
        assertEquals(result.externalMoles()[water],filled.waterLiquid()+filled.waterVapor(),1e-8);
        assertEquals(initial.internalEnergy()+result.externalEnergyJoule(),filled.internalEnergy(),.001);
        assertEquals(graph.reservoirs().getFirst().state(),result.states().getFirst());
    }
    @Test void sourceToVoidHasSteadyThroughputAndBothBoundariesRejectReverseFlow() {
        var sink=new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,101325,()->{}),PassiveNetwork.NodeKind.VOID);
        var graph=new PassiveNetwork(List.of(source(200000),sink),List.of(pipe()));
        var result=new PassiveStepSolver(model).solve(graph,1,()->{});assertTrue(result.massFlows()[0]>0);
        assertArrayEquals(new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],result.externalMoles(),1e-10);assertEquals(0,result.externalEnergyJoule(),1e-10);
        var highSink=new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,300000,()->{}),PassiveNetwork.NodeKind.VOID);
        var blocked=new PassiveStepSolver(model).solve(new PassiveNetwork(List.of(source(200000),highSink),List.of(pipe())),1,()->{});
        assertEquals(0,blocked.massFlows()[0],1e-10);
    }
    @Test void aThreePhaseWetCrudeFeedCanEnterNitrogenWithoutLosingAnyComponent() {
        String id="createcheme:tjl20_methane_nitrogen";var n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.2;
        var source=new PassiveNetwork.Reservoir(1,0,model.flashTP(350,200000,n,()->{}),PassiveNetwork.NodeKind.GENERATOR);
        var initial=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var graph=new PassiveNetwork(List.of(source,new PassiveNetwork.Reservoir(2,0,initial)),List.of(pipe()));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});var state=result.states().get(1);
        assertTrue(source.state().liquidVolume()>0&&source.state().waterVolume()>0&&source.state().vaporVolume()>0);
        assertTrue(result.massFlows()[0]>0);assertTrue(state.liquidVolume()>0);assertTrue(state.vaporVolume()>0);
        // A small incoming free-water amount may all evaporate into the nitrogen headspace.
        assertTrue(state.waterLiquid()+state.waterVapor()>0);
        assertTrue(state.waterPartialPressure()<=model.saturationPressure(state.temperature())*(1+1e-8));
        assertArrayEquals(result.externalMoles(),difference(PhaseLayout.totalAmounts(state),PhaseLayout.totalAmounts(initial)),1e-8);
    }
    private static double[] difference(double[] a,double[] b){for(int i=0;i<a.length;i++)a[i]-=b[i];return a;}
}
