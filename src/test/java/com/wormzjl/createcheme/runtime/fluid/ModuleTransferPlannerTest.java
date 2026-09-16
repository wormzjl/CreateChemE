package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleTransferPlannerTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private final double[] weights=weights();
    private double[] weights(){double[] w=new double[22];for(int c=0;c<22;c++)w[c]=c==21?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);return w;}
    private EnergyReference reference(){var basis=new ArrayList<>(model.hydrocarbon.components());basis.add("Water");return EnergyReference.sensible(basis);}
    private PassiveNetwork graph(double volume){return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(volume,298.15,101325,()->{}))),List.of());}
    private MaterialParcel water(double mass){double[] n=new double[22];n[21]=mass/model.waterMolecularWeight;var state=model.flashTP(298.15,101325,n,()->{});return new MaterialParcel(n,weights,state.enthalpy(),reference());}
    @Test void requestedTwentyAndEightyKgCommitAsSeparateIntervalsWithoutReplay() {
        var original=graph(1);var material=water(100);var id=UUID.randomUUID();var planner=new ModuleTransferPlanner(model);
        var first=planner.prepare(original,0,100,List.of(new ModuleTransferPlanner.Input(id,1,0,material,20)),List.of(),()->{});
        assertEquals(20,first.delivered().get(id).massKg(),1e-10);assertTrue(first.candidate().graph().scheduledTransfers().isEmpty());
        var remainder=material.takeMass(20).remainder();var second=planner.prepare(first.candidate().graph(),100,100,List.of(new ModuleTransferPlanner.Input(id,1,0,remainder,80)),List.of(),()->{});
        assertEquals(80,second.delivered().get(id).massKg(),1e-10);
        assertEquals(100,second.candidate().graph().reservoirs().getFirst().inventory().moles()[21]*model.waterMolecularWeight,1e-8);
        assertEquals(original.reservoirs().getFirst().inventory().internalEnergy()+material.energyJoule(),second.candidate().graph().reservoirs().getFirst().inventory().internalEnergy(),1e-4);
        assertEquals(0,original.reservoirs().getFirst().inventory().moles()[21]);
    }
    @Test void overfullDeliveryIsReducedToAFeasiblePortionAndOtherDueInputStillAdvances() {
        var original=graph(.022);var first=UUID.randomUUID();var second=UUID.randomUUID();var planner=new ModuleTransferPlanner(model);
        var result=planner.prepare(original,0,100,List.of(new ModuleTransferPlanner.Input(first,1,0,water(100),100),new ModuleTransferPlanner.Input(second,1,0,water(1),1)),List.of(),()->{});
        double accepted=result.delivered().get(first).massKg();assertTrue(accepted>0&&accepted<100);assertEquals(1,result.delivered().get(second).massKg(),1e-10);
        assertEquals(accepted+1,result.candidate().graph().reservoirs().getFirst().inventory().moles()[21]*model.waterMolecularWeight,1e-8);
    }
    @Test void withdrawalProducesOnlyActuallyIntegratedMaterialAndCancellationPropagates() {
        var graph=graph(1);var id=UUID.randomUUID();var planner=new ModuleTransferPlanner(model);
        var result=planner.prepare(graph,0,100,List.of(),List.of(new ModuleTransferPlanner.Withdrawal(id,1,.005)),()->{});
        var parcel=result.withdrawn().get(id);assertEquals(.005,parcel.massKg(),1e-12);
        assertEquals(graph.reservoirs().getFirst().inventory().internalEnergy(),result.candidate().graph().reservoirs().getFirst().inventory().internalEnergy()+parcel.energyJoule(),1e-5);
        assertThrows(java.util.concurrent.CancellationException.class,()->planner.prepare(graph,0,100,List.of(),List.of(new ModuleTransferPlanner.Withdrawal(id,1,.005)),()->{throw new java.util.concurrent.CancellationException();}));
    }
}
