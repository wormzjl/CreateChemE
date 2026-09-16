package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GlobalLiquidResponseTest {
    @Test void allLiquidReferenceDensitiesUseTheSameConfiguredCompression() {
        var model = new GlobalLiquidResponse(1e-9);
        for (double referenceVolume : new double[] {18e-6,100e-6,2000e-6}) {
            var state = model.evaluate(300,2e6,1e5,referenceVolume,0,0,1000,100);
            assertEquals(1e-9,-state.volumePressureDerivative()/state.molarVolume(),1e-22);
            assertTrue(state.molarVolume()>0 && state.molarVolume()<referenceVolume);
        }
    }
    @Test void energyAndPressureDerivativesAreConsistent() {
        var model = new GlobalLiquidResponse(1e-9);
        var state = model.evaluate(300,1e6,2e6,1e-4,1e-7,1e-10,1000,100);
        var low = model.evaluate(300,1e6-10,2e6,1e-4,1e-7,1e-10,1000,100);
        var high = model.evaluate(300,1e6+10,2e6,1e-4,1e-7,1e-10,1000,100);
        assertEquals(state.volumePressureDerivative(),(high.molarVolume()-low.molarVolume())/20,1e-20);
        assertEquals(state.enthalpyPressureDerivative(),(high.molarEnthalpy()-low.molarEnthalpy())/20,1e-11);
        assertEquals(state.molarEnthalpy()-1e6*state.molarVolume(),state.molarInternalEnergy());
    }
    @Test void invalidOrUnboundedCompressionIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new GlobalLiquidResponse(0));
        assertThrows(IllegalArgumentException.class,()->new GlobalLiquidResponse(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new GlobalLiquidResponse(1e-9)
                .evaluate(300,2e9,1e5,1e-4,0,0,0,100));
    }
}
