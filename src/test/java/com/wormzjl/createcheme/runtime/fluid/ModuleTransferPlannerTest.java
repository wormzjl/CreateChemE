package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleTransferPlannerTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] weights=weights();
    private double[] weights(){double[] w=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)w[c]=c==com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);return w;}
    private EnergyReference reference(){var basis=new ArrayList<>(model.hydrocarbon.components());basis.add("Water");return EnergyReference.sensible(basis);}
    private PassiveNetwork graph(double volume){return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(volume,298.15,101325,()->{}))),List.of());}
    private MaterialParcel water(double mass){double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=mass/model.waterMolecularWeight;var state=model.flashTP(298.15,101325,n,()->{});return new MaterialParcel(n,weights,state.enthalpy(),reference());}
    @Test void solidsArrivingDuringAnIntervalRefuseOnlyTheAffectedWithdrawal() {
        var original=graph(1);var fluid=water(10);
        var solids=new com.wormzjl.createcheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.createcheme.science.fluid.state.SolidInventory.Population(model.solids.require("createcheme:demo_particle"),com.wormzjl.createcheme.science.fluid.state.ParticleSize.micrometres("100"),1)));
        var parcel=new MaterialParcel(fluid.moles(),weights,fluid.energyJoule(),reference(),solids);
        var input=UUID.randomUUID();var withdrawal=UUID.randomUUID();
        var result=new ModuleTransferPlanner(model).prepare(original,0,20,List.of(new ModuleTransferPlanner.Input(input,1,0,parcel,parcel.massKg())),List.of(new ModuleTransferPlanner.Withdrawal(withdrawal,1,.001)),()->{});
        assertEquals(parcel.massKg(),result.delivered().get(input).massKg(),1e-10);
        assertFalse(result.withdrawn().containsKey(withdrawal));
        assertEquals(solids.massKg(),result.candidate().graph().reservoirs().getFirst().inventory().solids().massKg(),1e-10);
        assertTrue(original.reservoirs().getFirst().inventory().solids().empty());
    }
    @Test void requestedTwentyAndEightyKgCommitAsSeparateIntervalsWithoutReplay() {
        var original=graph(1);var material=water(100);var id=UUID.randomUUID();var planner=new ModuleTransferPlanner(model);
        var first=planner.prepare(original,0,100,List.of(new ModuleTransferPlanner.Input(id,1,0,material,20)),List.of(),()->{});
        assertEquals(20,first.delivered().get(id).massKg(),1e-10);assertTrue(first.candidate().graph().scheduledTransfers().isEmpty());
        var remainder=material.takeMass(20).remainder();var second=planner.prepare(first.candidate().graph(),100,100,List.of(new ModuleTransferPlanner.Input(id,1,0,remainder,80)),List.of(),()->{});
        assertEquals(80,second.delivered().get(id).massKg(),1e-10);
        assertEquals(100,second.candidate().graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]*model.waterMolecularWeight,1e-8);
        assertEquals(original.reservoirs().getFirst().inventory().internalEnergy()+material.energyJoule(),second.candidate().graph().reservoirs().getFirst().inventory().internalEnergy(),1e-4);
        assertEquals(0,original.reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]);
    }
    @Test void overfullDeliveryIsReducedToAFeasiblePortionAndOtherDueInputStillAdvances() {
        var original=graph(.022);var first=UUID.randomUUID();var second=UUID.randomUUID();var planner=new ModuleTransferPlanner(model);
        var result=planner.prepare(original,0,100,List.of(new ModuleTransferPlanner.Input(first,1,0,water(100),100),new ModuleTransferPlanner.Input(second,1,0,water(1),1)),List.of(),()->{});
        double accepted=result.delivered().get(first).massKg();assertTrue(accepted>0&&accepted<100);assertEquals(1,result.delivered().get(second).massKg(),1e-10);
        assertEquals(accepted+1,result.candidate().graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]*model.waterMolecularWeight,1e-8);
    }
    @Test void withdrawalProducesOnlyActuallyIntegratedMaterialAndCancellationPropagates() {
        var graph=graph(1);var id=UUID.randomUUID();var planner=new ModuleTransferPlanner(model);
        var result=planner.prepare(graph,0,100,List.of(),List.of(new ModuleTransferPlanner.Withdrawal(id,1,.005)),()->{});
        var parcel=result.withdrawn().get(id);assertEquals(.005,parcel.massKg(),1e-12);
        assertEquals(graph.reservoirs().getFirst().inventory().internalEnergy(),result.candidate().graph().reservoirs().getFirst().inventory().internalEnergy()+parcel.energyJoule(),1e-5);
        assertThrows(java.util.concurrent.CancellationException.class,()->planner.prepare(graph,0,100,List.of(),List.of(new ModuleTransferPlanner.Withdrawal(id,1,.005)),()->{throw new java.util.concurrent.CancellationException();}));
    }
}
