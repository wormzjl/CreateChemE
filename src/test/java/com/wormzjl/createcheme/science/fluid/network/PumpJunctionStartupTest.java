package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.thermo.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Regression from actual generator/pipe/pump/reservoir startup in the isolated Minecraft world.
 *
 * <p>The crude case is re-baselined for the liquid-only pump (decision D1 of the phase-ports batch): the Tia Juana Light
 * preset at 298.15 K and 1 atm is two-phase, 11.15 % vapour by volume by one flashTP, with its bubble point at 117.8 kPa,
 * so the pump refuses it there ({@code INLET_WRONG_PHASE}). Its generator and the nitrogen receiver now stand at 150 kPa,
 * above the bubble point, so the pump still starts a liquid through its zero-storage inlet into the receiver's own
 * pressure; the water case is unchanged at 1 atm.
 */
class PumpJunctionStartupTest {
    @Test void waterAndAmbientCrudeStartThroughAZeroStoragePumpInletWithoutInventingUpstreamNitrogen() {
        var catalog=MaterialCatalog.bundled();var model=FluidThermodynamics.forNetwork(catalog,FluidMaterialCatalog.networkPackage(catalog),1e-9);
        for(String id:List.of("water","createcheme:tia_juana_light_methane")) {
            var composition=FluidPresetCatalog.resolve(catalog).stream().filter(p->p.id().equals(id)).findFirst().orElseThrow().moleFractions();
            double pressure=id.equals("water")?101325:150000;
            var unit=model.flashTP(298.15,pressure,composition,()->{});for(int c=0;c<composition.length;c++)composition[c]/=unit.volume();
            var source=new PassiveNetwork.Reservoir(1,-60,model.flashTP(298.15,pressure,composition,()->{}),PassiveNetwork.NodeKind.GENERATOR);
            assertEquals(0,source.state().vaporVolume(),0,id+": the pump's supply is a liquid");
            var junction=new PassiveNetwork.Reservoir(2,-60,source.state(),PassiveNetwork.NodeKind.JUNCTION);
            var receiver=new PassiveNetwork.Reservoir(3,-60,model.initialNitrogenCharge(1,298.15,pressure,()->{}));
            double requested=id.equals("water")?.01:.0001;
            var geometry=new PipeResistance.Geometry(1.5,.05,.000045,0);
            var graph=new PassiveNetwork(List.of(source,junction,receiver),List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,1,2,geometry,new FlowControl.Pump(requested,500000,1))));
            var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance());assertEquals(1,result.advancedSeconds());
            assertTrue(result.averageMassFlows()[0]>0,id);assertEquals(result.averageMassFlows()[0],result.averageMassFlows()[1],1e-8);
            int nitrogen=model.components().indexOf("Nitrogen");var middle=result.graph().reservoirs().get(1);
            assertEquals(0,PhaseLayout.totalAmounts(middle.state())[nitrogen],0,"No reverse nitrogen path across the pump");
            var before=receiver.inventory();var after=result.graph().reservoirs().get(2).inventory();double[] external=new double[composition.length];double externalEnergy=0;
            for(var boundary:result.boundaries()){double[] n=boundary.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=boundary.totalEnergyJoule();}
            assertEquals(before.moles()[nitrogen],after.moles()[nitrogen],1e-9);
            double beforeMass=0,afterMass=0;
            for(int c=0;c<composition.length;c++) {
                assertEquals(before.moles()[c]+external[c],after.moles()[c],1e-9+1e-8*Math.abs(after.moles()[c]),id+" component "+c);
                beforeMass+=before.moles()[c]*model.molecularWeight(c);afterMass+=after.moles()[c]*model.molecularWeight(c);
            }
            assertTrue(afterMass>beforeMass);
            double change=after.internalEnergy()-before.internalEnergy()+(afterMass-beforeMass)*PassiveStepSolver.GRAVITY*receiver.elevation();
            assertEquals(externalEnergy+result.pumpWorkJoule(),change,1e-4+1e-6*Math.abs(externalEnergy),id+" total energy");
        }
    }
}
