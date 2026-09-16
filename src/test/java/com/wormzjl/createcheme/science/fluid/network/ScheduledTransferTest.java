package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledTransferTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private PassiveNetwork.Reservoir gas(long id,double p,double z){return new PassiveNetwork.Reservoir(id,z,model.initialNitrogenCharge(1,298.15,p,()->{}));}
    private double mass(PassiveNetwork.Inventory inventory){var n=inventory.moles();double mass=0;for(int c=0;c<n.length;c++)mass+=n[c]*(c==21?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c));return mass;}
    private double energy(PassiveNetwork graph){double total=0;for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)total+=node.inventory().internalEnergy()+mass(node.inventory())*PassiveStepSolver.GRAVITY*node.elevation();return total;}
    @Test void bulkWithdrawalIsDistributedInTimeAndCarriesEnthalpyAndElevationEnergy() {
        var initial=new PassiveNetwork(List.of(gas(1,150000,10),gas(2,149000,20)),List.of(new PassiveNetwork.Pipe(8,0,1,new PipeResistance.Geometry(100,.02,.000045,0))),List.of(new ScheduledTransfer.Withdrawal(90,1,.001)));
        var result=new PassiveIntervalSolver(model).solve(initial,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        double removedMass=0,removedEnergy=0;
        for(var transfer:result.boundaries())if(transfer.nodeId()==90) {
            removedMass-=transfer.moles()[20]*model.hydrocarbon.molecularWeight(20);removedEnergy-=transfer.totalEnergyJoule();
        }
        assertEquals(.005,removedMass,1e-12);assertEquals(energy(initial),energy(result.graph())+removedEnergy,1e-5);
        assertTrue(result.graph().reservoirs().get(1).state().temperature()<298.15);
    }
    @Test void waterCanBeDeliveredOverFiveFifteenAndThirtySecondsWithoutAnInstantaneousInventoryJump() {
        for(double seconds:new double[]{5,15,30}) {
            var tank=gas(1,101325,12);double[] water=new double[22];water[21]=1;var stream=model.flashTP(298.15,101325,water,()->{});
            double[] rates=new double[22];rates[21]=100/model.waterMolecularWeight/seconds;
            double energyRate=100/seconds*(stream.enthalpy()/stream.mass()+PassiveStepSolver.GRAVITY*12);
            var graph=new PassiveNetwork(List.of(tank),List.of(),List.of(new ScheduledTransfer.Injection(90,0,rates,energyRate)));
            var result=new PassiveIntervalSolver(model).solve(graph,seconds,PassiveIntervalSolver.Settings.defaults(),()->{});
            var after=result.graph().reservoirs().getFirst();assertEquals(100,after.inventory().moles()[21]*model.waterMolecularWeight,1e-8);
            assertEquals(tank.inventory().moles()[20],after.inventory().moles()[20],1e-10);
            assertEquals(energy(graph)+seconds*energyRate,energy(result.graph()),1e-4);
            assertTrue(after.state().waterLiquid()>0);assertEquals(0,tank.inventory().moles()[21]);
        }
    }
    @Test void anUnsupportedWithdrawalPublishesNoPartialInterval() {
        var tank=gas(1,101325,0);var graph=new PassiveNetwork(List.of(tank),List.of(),List.of(new ScheduledTransfer.Withdrawal(90,0,1)));
        var before=tank.inventory();assertThrows(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence.class,()->new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{}));
        assertEquals(before,graph.reservoirs().getFirst().inventory());
    }
}
