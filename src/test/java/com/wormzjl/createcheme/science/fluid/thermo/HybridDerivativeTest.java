package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import org.junit.jupiter.api.Test;

class HybridDerivativeTest {
    @Test void sharedWaterResponsePreservesHeatAndPressureIdentities() {
        var model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        for(double t:new double[]{298.15,350,375})for(double p:new double[]{101325,1e6,2e6}) {
            var s=model.waterLiquid(t,p);double dt=.001,dp=10;
            double vp=(model.waterLiquid(t,p+dp).molarVolume()-model.waterLiquid(t,p-dp).molarVolume())/(2*dp);
            double vt=(model.waterLiquid(t+dt,p).molarVolume()-model.waterLiquid(t-dt,p).molarVolume())/(2*dt);
            double hp=(model.waterLiquid(t,p+dp).molarEnthalpy()-model.waterLiquid(t,p-dp).molarEnthalpy())/(2*dp);
            assertEquals(-1e-9*s.molarVolume(),vp,1e-20);
            assertEquals(s.molarVolume()-t*vt,hp,1e-11);
        }
    }
    @Test void liquidMixtureChemicalPotentialPressureDerivativesSumToPhysicalVolume() {
        var model=new HydrocarbonModel(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE];n[0]=.01;n[6]=.09;n[12]=.4;n[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE-1]=.5;
        double t=350,p=1e6,dp=10;
        var mid=model.phase(t,p,n,PhaseRoot.LIQUID);
        var a=model.phase(t,p-dp,n,PhaseRoot.LIQUID).logFugacity();var b=model.phase(t,p+dp,n,PhaseRoot.LIQUID).logFugacity();
        double v=0;for(int i=0;i<n.length;i++)v+=n[i]*(b[i]-a[i]+Math.log((p+dp)/(p-dp)))/(2*dp)*FluidThermodynamics.R*t;
        assertEquals(mid.molarVolume(),v,1e-9*mid.molarVolume()+1e-12);
    }
}
