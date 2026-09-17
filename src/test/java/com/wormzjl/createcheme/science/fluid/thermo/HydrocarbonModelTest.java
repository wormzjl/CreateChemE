package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import org.junit.jupiter.api.Test;

class HydrocarbonModelTest {
    @Test void sameCompressionAppliesToPureLiquidAndCrudeMixturesWithoutChangingColumnData() {
        var catalog=MaterialCatalog.bundled();var original=catalog.requirePackage("createcheme:tjl20_methane").fingerprint();
        var model=new HydrocarbonModel(catalog,"createcheme:tjl20_methane",1e-9);
        double[] liquid=new double[model.componentCount()];liquid[6]=1;
        var pure=model.phase(300,1e6,liquid,PhaseRoot.LIQUID);
        assertEquals(1e-9,-pure.volumePressureDerivative()/pure.molarVolume(),1e-22);
        liquid[6]=.05;liquid[12]=.45;liquid[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE-1]=.5;
        var crude=model.phase(350,1e6,liquid,PhaseRoot.LIQUID);
        assertEquals(1e-9,-crude.volumePressureDerivative()/crude.molarVolume(),1e-22);
        assertTrue(crude.molarVolume()>0);
        assertEquals(original,catalog.requirePackage("createcheme:tjl20_methane").fingerprint());
    }
    @Test void liquidPressureCorrectionSatisfiesTheChemicalPotentialVolumeIdentity() {
        var model=new HydrocarbonModel(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        double[] n=new double[model.componentCount()];n[6]=1;
        double p=1e6,dtp=10,t=300;
        double gl=model.phase(t,p-dtp,n,PhaseRoot.LIQUID).logFugacity()[6]+Math.log(p-dtp);
        double gh=model.phase(t,p+dtp,n,PhaseRoot.LIQUID).logFugacity()[6]+Math.log(p+dtp);
        double volume=(gh-gl)/(2*dtp)*8.31446261815324*t;
        assertEquals(model.phase(t,p,n,PhaseRoot.LIQUID).molarVolume(),volume,1e-10);
    }
}
