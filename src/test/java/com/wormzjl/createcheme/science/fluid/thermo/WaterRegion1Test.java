package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WaterRegion1Test {
    @Test void matchesOfficialRegionOneCheckpoints() {
        checkpoint(300,3e6,.00100215168,115331.273,112324.818,4173.01218);
        checkpoint(300,80e6,.000971180894,184142.828,106448.356,4010.08987);
        checkpoint(500,3e6,.00120241800,975542.239,971934.985,4655.80682);
    }
    @Test void verifiesAnalyticPressureAndTemperatureDerivatives() {
        var s=WaterRegion1.evaluate(300,3e6);
        var tl=WaterRegion1.evaluate(299.99,3e6); var th=WaterRegion1.evaluate(300.01,3e6);
        var pl=WaterRegion1.evaluate(300,3e6-100); var ph=WaterRegion1.evaluate(300,3e6+100);
        relative(s.volumeTemperatureDerivative(),(th.specificVolume()-tl.specificVolume())/.02);
        relative(s.volumePressureDerivative(),(ph.specificVolume()-pl.specificVolume())/200);
        relative(s.specificHeatCapacity(),(th.specificEnthalpy()-tl.specificEnthalpy())/.02);
        relative(s.enthalpyPressureDerivative(),(ph.specificEnthalpy()-pl.specificEnthalpy())/200);
    }
    @Test void refusesVaporAndOutOfRegionStates() {
        assertThrows(IllegalArgumentException.class,()->WaterRegion1.evaluate(500,1e5));
        assertThrows(IllegalArgumentException.class,()->WaterRegion1.evaluate(650,80e6));
        assertThrows(IllegalArgumentException.class,()->WaterRegion1.evaluate(300,101e6));
        assertThrows(IllegalArgumentException.class,()->WaterRegion1.diluteVaporViscosity(Double.NaN));
        assertTrue(WaterRegion1.diluteVaporViscosity(300)>9e-6);
    }
    private static void checkpoint(double t,double p,double v,double h,double u,double cp) {
        var s=WaterRegion1.evaluate(t,p);
        assertEquals(v,s.specificVolume(),5e-12);
        assertEquals(h,s.specificEnthalpy(),.001);
        assertEquals(u,s.specificInternalEnergy(),.001);
        assertEquals(cp,s.specificHeatCapacity(),.0001);
    }
    private static void relative(double expected,double actual) {
        assertEquals(expected,actual,Math.abs(expected)*1e-5+1e-18);
    }
}
