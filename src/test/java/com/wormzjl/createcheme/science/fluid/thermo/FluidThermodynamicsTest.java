package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

class FluidThermodynamicsTest {
    private final FluidThermodynamics model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private double[] pure(int i) {double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1];n[i]=1;return n;}
    private void closure(double[] expected,FluidThermodynamics.State state) {
        for(int i=0;i<model.hydrocarbon.componentCount();i++)assertEquals(expected[i],state.liquid()[i]+state.vapor()[i],1e-13);
        assertEquals(expected[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE],state.waterLiquid()+state.waterVapor(),1e-13);
        assertEquals(state.enthalpy()-state.pressure()*state.volume(),state.internalEnergy(),1e-10);
        if(state.vaporVolume()>0)assertEquals(state.pressure(),state.hydrocarbonPartialPressure()+state.waterPartialPressure(),.01);
        assertTrue(Double.isFinite(state.volume())&&state.volume()>0);
    }
    @Test void pureWaterLiquidSteamAndHydrocarbonLimits() {
        var weights=model.molecularWeights();
        assertEquals(model.componentCount(),weights.length);
        assertEquals("Water",model.components().getLast());
        assertThrows(UnsupportedOperationException.class,()->model.components().add("Mutated"));
        double original=weights[0];weights[0]=0;
        assertEquals(original,model.molecularWeights()[0],0,"Weight arrays must be independently owned");
        var water=pure(com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE);var l=model.flashTP(300,101325,water,()->{});closure(water,l);assertEquals(1,l.waterLiquid());
        var steam=model.flashTP(400,100000,water,()->{});closure(water,steam);assertEquals(1,steam.waterVapor());
        var methane=pure(0);var gas=model.flashTP(300,101325,methane,()->{});closure(methane,gas);assertEquals(1,gas.vapor()[0],1e-8);
        assertEquals(com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1,gas.componentCount());double[] exposed=gas.vapor();exposed[0]=0;
        assertEquals(1,gas.vapor()[0],1e-8,"State component access must remain defensive");
        var pentane=pure(6);var liquid=model.flashTP(300,1e6,pentane,()->{});closure(pentane,liquid);assertEquals(1,liquid.liquid()[6],1e-8);
        exposed=liquid.liquid();exposed[6]=0;assertEquals(1,liquid.liquid()[6],1e-8,"State component access must remain defensive");
    }
    @Test void crudePresetsAndThreePhaseWaterMaintainComponentInventory() {
        var catalog=MaterialCatalog.bundled();
        for(String packageId:new String[]{"createcheme:tjl20_methane","createcheme:wti_light_export_tjl20","createcheme:cold_lake_blend_tjl20"}) {
            var p=catalog.requirePackage(packageId);String id=p.assays().keySet().iterator().next();
            var composition=V3PengRobinsonThermo.fromRegisteredPackage(p.id()).crudeFeed(id).moleFractions();
            var n=Arrays.copyOf(composition,com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE]=.2;
            var state=model.flashTP(350,101325,n,()->{});closure(n,state);
            assertTrue(state.liquidVolume()>0,id);assertTrue(state.waterVolume()>0,id);assertTrue(state.vaporVolume()>0,id);
        }
    }
    @Test void subsaturatedWaterUsesTheSameVaporVolumeAsHydrocarbons() {
        var n=pure(0);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE]=.001;var state=model.flashTP(350,101325,n,()->{});closure(n,state);
        assertEquals(0,state.waterLiquid());assertTrue(state.waterPartialPressure()<model.saturationPressure(350));
    }
}
