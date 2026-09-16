package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.wormzjl.createcheme.science.fluid.solver.*;

class NitrogenInitializationTest {
    @Test void placementCreatesRealNitrogenInventoryWithoutChangingTheColumnPackage() {
        String id="createcheme:tjl20_methane";var catalog=MaterialCatalog.bundled();var original=catalog.requirePackage(id).fingerprint();
        var model=FluidThermodynamics.forNetwork(catalog,id,1e-9);var state=model.initialNitrogenCharge(1,298.15,101325,()->{});
        assertEquals(21,model.hydrocarbon.componentCount());assertEquals(1,state.volume(),1e-12);assertEquals(0,state.liquidVolume());
        assertTrue(state.mass()>1.13&&state.mass()<1.16);assertEquals(0,state.waterLiquid()+state.waterVapor());
        int nitrogen=model.hydrocarbon.components().indexOf("Nitrogen");assertTrue(state.vapor()[nitrogen]>40);
        assertTrue(model.viscosity.vapor(298.15,state.vapor(),0)>1.7e-5);
        assertEquals(original,catalog.requirePackage(id).fingerprint());assertEquals(20,catalog.requirePackage(id).components().size());
    }
    @Test void nitrogenCaloricFitMatchesIndependentShomateCheckpoints() throws Exception {
        String id="createcheme:tjl20_methane";var catalog=FluidMaterialCatalog.withNitrogen(MaterialCatalog.bundled(),id);
        var cp=catalog.requirePackage(id).properties().getLast().cp();
        try(var input=getClass().getResourceAsStream("/fluid/reference/nitrogen-cp-checks.json")) {
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var entry:json.getAsJsonArray("points")) {
                var row=entry.getAsJsonObject();double t=row.get("temperatureKelvin").getAsDouble(),actual=0,power=1;
                for(double coefficient:cp){actual+=power*coefficient;power*=t-298.15;}
                assertEquals(row.get("referenceCp").getAsDouble(),actual,actual*.001);
            }
        }
    }
    @Test void smallWaterChargeConservesEnergyUsingOnlyTheInitialNitrogenAndFluid() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var initial=model.initialNitrogenCharge(1,298.15,101325,()->{});var amounts=PhaseLayout.totalAmounts(initial);
        int water=model.hydrocarbon.componentCount();amounts[water]=.01/model.waterMolecularWeight;
        double energy=initial.internalEnergy()+amounts[water]*model.waterLiquid(298.15,101325).molarEnthalpy();
        var seed=model.flashTP(280,101325,amounts,()->{});var layout=new PhaseLayout(model,seed);
        var solution=SparseNewton.solve(layout.fixedInventory(amounts,energy,1),layout.encode(seed),new SparseNewton.Settings(30,1e-10,1e-6,24),()->{});
        var result=layout.decode(solution.variables(),0);
        assertEquals(energy,result.internalEnergy(),1e-4);assertArrayEquals(amounts,PhaseLayout.totalAmounts(result),1e-10);
        assertTrue(result.temperature()>273.16&&result.temperature()<298.15);assertTrue(result.waterLiquid()>0);assertTrue(result.waterVapor()>0);
    }
}
