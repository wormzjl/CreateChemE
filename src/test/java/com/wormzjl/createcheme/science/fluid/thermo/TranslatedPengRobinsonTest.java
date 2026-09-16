package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranslatedPengRobinsonTest {
    static final ThermoComponent BUTANE = new ThermoComponent("n-butane", 425.12, 3796000, .199, .0581222);

    static TranslatedPengRobinson model(ThermoComponent component, double translation) {
        return new TranslatedPengRobinson(List.of(component), new double[][] {{0}},
                new double[][] {{100, .1, 0, 0, 0, 0}}, new double[] {translation});
    }

    @Test void analyticDerivativesAndEnergyIdentityMatchIndependentDifferences() {
        var model = model(BUTANE, -1e-6);
        double t = 300, p = 1e6, dt = .001, dp = 10;
        var state = model.evaluate(t,p,new double[] {1},PhaseRoot.LIQUID);
        var tl = model.evaluate(t-dt,p,new double[] {1},PhaseRoot.LIQUID);
        var th = model.evaluate(t+dt,p,new double[] {1},PhaseRoot.LIQUID);
        var pl = model.evaluate(t,p-dp,new double[] {1},PhaseRoot.LIQUID);
        var ph = model.evaluate(t,p+dp,new double[] {1},PhaseRoot.LIQUID);
        relative((th.molarVolume()-tl.molarVolume())/(2*dt), state.volumeTemperatureDerivative());
        relative((ph.molarVolume()-pl.molarVolume())/(2*dp), state.volumePressureDerivative());
        relative((th.molarEnthalpy()-tl.molarEnthalpy())/(2*dt), state.heatCapacity());
        relative((ph.molarEnthalpy()-pl.molarEnthalpy())/(2*dp), state.enthalpyPressureDerivative());
        assertEquals(state.molarEnthalpy()-p*state.molarVolume(), state.molarInternalEnergy());
    }
    @Test void constantTranslationChangesDensityButNotRawPressureSlope() {
        var raw = model(BUTANE, 0).evaluate(300,1e6,new double[] {1},PhaseRoot.LIQUID);
        var translated = model(BUTANE,-1e-6).evaluate(300,1e6,new double[] {1},PhaseRoot.LIQUID);
        assertEquals(raw.volumePressureDerivative(), translated.volumePressureDerivative());
        assertEquals(raw.molarVolume()-1e-6, translated.molarVolume(),1e-16);
        assertEquals(raw.molarInternalEnergy(), translated.molarInternalEnergy(),1e-10);
        assertNotEquals(raw.isothermalCompressibility(), translated.isothermalCompressibility());
    }
    @Test void mixturePartialVolumesAndSecondTemperatureDerivativeClose() {
        var pentane=new ThermoComponent("n-pentane",469.7,3370000,.251,.07214878);
        var model=new TranslatedPengRobinson(List.of(BUTANE,pentane),new double[2][2],
                new double[][] {{100,.1,0,0,0,0},{120,.1,0,0,0,0}},new double[] {1e-6,-1e-6});
        double[] n={.4,.6}; double delta=1e-5;
        var base=model.evaluate(300,1e6,n,PhaseRoot.LIQUID);
        for(int i=0;i<2;i++) {
            double[] lo=n.clone(),hi=n.clone();lo[i]-=delta;hi[i]+=delta;
            double totalLo=(1-delta)*model.evaluate(300,1e6,lo,PhaseRoot.LIQUID).molarVolume();
            double totalHi=(1+delta)*model.evaluate(300,1e6,hi,PhaseRoot.LIQUID).molarVolume();
            relative((totalHi-totalLo)/(2*delta),base.partialMolarVolumes()[i]);
        }
        double d2=(model.evaluate(300.01,1e6,n,PhaseRoot.LIQUID).volumeTemperatureDerivative()
                -model.evaluate(299.99,1e6,n,PhaseRoot.LIQUID).volumeTemperatureDerivative())/.02;
        relative(d2,base.volumeSecondTemperatureDerivative());
    }
    private static void relative(double expected, double actual) {
        assertEquals(expected, actual, Math.max(1e-15, Math.abs(expected)*1e-5));
    }
}
