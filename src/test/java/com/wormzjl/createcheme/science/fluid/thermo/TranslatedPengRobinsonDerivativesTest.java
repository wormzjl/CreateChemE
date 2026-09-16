package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Holds the derivative bundle {@link TranslatedPengRobinson#differentiate} exposes for the analytic node
 * Jacobian against central differences of the values it claims to differentiate.
 *
 * <p>Each step is chosen for the quantity it perturbs and each probe is refused unless both of its endpoints
 * stay on the same cubic root, because a derivative is only a derivative of a branch: a probe that crosses to
 * the other root would measure the jump, not the slope. A milli-kelvin leaves a central difference's
 * truncation, which grows as the square of the step, far below the tolerance while keeping the cancellation
 * of two nearly equal values well above roundoff; the mole step is a micromole in a mole, and the pressure
 * step is a part in ten thousand, which the equation of state is far smoother in than it is in temperature.
 * {@link #TOLERANCE} is relative to the largest magnitude each quantity reaches at the same state, so a
 * component whose own derivative is near zero is not judged against nothing.</p>
 *
 * <p>Two identities are checked with no finite difference at all, because they cannot be satisfied by
 * accident: the partial molar enthalpies must sum to the molar enthalpy, and the heat capacity, which the
 * volumetric block computes from {@code dv/dT} and the attraction term, must equal the ideal-gas capacity
 * plus the kernel's {@code dH^R/dT}, which is a completely different expression.</p>
 */
class TranslatedPengRobinsonDerivativesTest {
    private static final String PACKAGE_ID="createcheme:tjl20_methane";
    private static final double TEMPERATURE_STEP=1.0e-3;
    private static final double MOLE_STEP=1.0e-6;
    private static final double PRESSURE_FRACTION=1.0e-4;
    private static final double TOLERANCE=1.0e-6;

    @Test void everyExposedDerivativeMatchesCentralDifferencesOfWhatItDifferentiates() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE_ID,1e-9);
        var eos=model.hydrocarbon.translated();
        var output=eos.newDerivatives();
        var worst=new LinkedHashMap<String,Worst>();
        int compared=0,identities=0;
        for(var state:states(model.hydrocarbon.componentCount()))for(PhaseRoot root:PhaseRoot.values()) {
            var base=eos.prepare(state.temperature);
            TranslatedPengRobinson.Phase phase;
            try {
                phase=eos.evaluate(state.temperature,state.pressure,state.amounts,root,base);
                eos.differentiate(state.temperature,state.pressure,state.amounts,root,base,output);
            } catch(RuntimeException unavailable){continue;}
            compared++;
            int count=state.amounts.length;
            String at=state+" "+root;

            // Two identities, no differencing: the partial molar enthalpies are a decomposition of h, and the
            // volumetric heat capacity and the kernel's dH^R/dT are independent routes to the same number.
            double summed=0;
            double[] partialMolar=output.partialMolarEnthalpyView();
            for(int i=0;i<count;i++)summed+=state.fractions[i]*partialMolar[i];
            record(worst,"sum x_i h_i = h",at,phase.molarEnthalpy(),summed,Math.abs(phase.molarEnthalpy()));
            record(worst,"cp = cp_ideal + dH^R/dT",at,phase.heatCapacity(),
                    output.values().idealGasHeatCapacity()+output.residualEnthalpyTemperatureDerivative(),
                    Math.abs(phase.heatCapacity()));
            identities++;

            if(state.keepsRoot(eos,root,TEMPERATURE_STEP,0,0)) {
                var higher=state.at(eos,root,TEMPERATURE_STEP,0,0);
                var lower=state.at(eos,root,-TEMPERATURE_STEP,0,0);
                double scale=0;
                for(int i=0;i<count;i++)scale=Math.max(scale,Math.abs(output.logFugacityTemperatureDerivativeView()[i]));
                for(int i=0;i<count;i++) {
                    record(worst,"d ln phi_i/dT",at,
                            (higher.logFugacityCoefficientsView()[i]-lower.logFugacityCoefficientsView()[i])/(2*TEMPERATURE_STEP),
                            output.logFugacityTemperatureDerivativeView()[i],scale);
                }
                record(worst,"dh/dT (cp)",at,(higher.molarEnthalpy()-lower.molarEnthalpy())/(2*TEMPERATURE_STEP),
                        phase.heatCapacity(),Math.abs(phase.heatCapacity()));
                record(worst,"dv/dT",at,(higher.molarVolume()-lower.molarVolume())/(2*TEMPERATURE_STEP),
                        phase.volumeTemperatureDerivative(),Math.abs(phase.volumeTemperatureDerivative()));
                record(worst,"d2v/dT2",at,
                        (higher.volumeTemperatureDerivative()-lower.volumeTemperatureDerivative())/(2*TEMPERATURE_STEP),
                        phase.volumeSecondTemperatureDerivative(),Math.abs(phase.volumeSecondTemperatureDerivative()));
            }

            double pressureStep=state.pressure*PRESSURE_FRACTION;
            if(state.keepsRoot(eos,root,0,pressureStep,0)) {
                var higher=state.at(eos,root,0,pressureStep,0);
                var lower=state.at(eos,root,0,-pressureStep,0);
                double scale=0;
                for(int i=0;i<count;i++)scale=Math.max(scale,Math.abs(output.logFugacityPressureDerivativeView()[i]));
                for(int i=0;i<count;i++) {
                    record(worst,"d ln phi_i/dP",at,
                            (higher.logFugacityCoefficientsView()[i]-lower.logFugacityCoefficientsView()[i])/(2*pressureStep),
                            output.logFugacityPressureDerivativeView()[i],scale);
                }
                record(worst,"dv/dP",at,(higher.molarVolume()-lower.molarVolume())/(2*pressureStep),
                        phase.volumePressureDerivative(),Math.abs(phase.volumePressureDerivative()));
                record(worst,"dh/dP",at,(higher.molarEnthalpy()-lower.molarEnthalpy())/(2*pressureStep),
                        phase.enthalpyPressureDerivative(),Math.abs(phase.enthalpyPressureDerivative()));
            }

            double logPhiScale=0,enthalpyScale=0;
            for(int i=0;i<count;i++) {
                enthalpyScale=Math.max(enthalpyScale,Math.abs(partialMolar[i]));
                for(int j=0;j<count;j++)logPhiScale=Math.max(logPhiScale,Math.abs(output.logFugacityCompositionDerivative(i,j)));
            }
            for(int j=0;j<count;j++) {
                if(!(state.fractions[j]>10*MOLE_STEP)||!state.keepsRoot(eos,root,0,0,j+1))continue;
                var higher=state.at(eos,root,0,0,j+1);
                var lower=state.at(eos,root,0,0,-(j+1));
                for(int i=0;i<count;i++) {
                    record(worst,"d ln phi_i/dn_j",at+" d"+i+"/dn"+j,
                            (higher.logFugacityCoefficientsView()[i]-lower.logFugacityCoefficientsView()[i])/(2*MOLE_STEP),
                            output.logFugacityCompositionDerivative(i,j),logPhiScale);
                }
                // n h(n) is the extensive enthalpy; its derivative in n_j is the partial molar one.
                record(worst,"dH/dn_j",at+" dH/dn"+j,
                        ((1+MOLE_STEP)*higher.molarEnthalpy()-(1-MOLE_STEP)*lower.molarEnthalpy())/(2*MOLE_STEP),
                        partialMolar[j],enthalpyScale);
                record(worst,"dV/dn_j",at+" dV/dn"+j,
                        ((1+MOLE_STEP)*higher.molarVolume()-(1-MOLE_STEP)*lower.molarVolume())/(2*MOLE_STEP),
                        phase.partialMolarVolumesView()[j],Math.abs(phase.partialMolarVolumesView()[j]));
            }
        }
        System.out.println("TranslatedPengRobinson derivatives vs central differences: "+compared
                +" phase evaluations, "+identities+" identity checks");
        worst.forEach((quantity,value)->System.out.println("    "+String.format("%-26s",quantity)+value));
        assertTrue(compared>=16,"too few single-branch states: "+compared);
        assertTrue(worst.containsKey("d ln phi_i/dn_j")&&worst.get("d ln phi_i/dn_j").count>1000,
                "the composition block must actually be compared");
        worst.forEach((quantity,value)->assertTrue(value.deviation<=TOLERANCE,quantity+": "+value));
    }

    private static List<State> states(int count) {
        var all=new ArrayList<State>();
        var random=new Random(20260916L);
        for(double[] point:new double[][]{{300,101325},{330,250000},{350,500000},{400,1000000},{450,2000000},{520,250000}}) {
            double[] fractions=new double[count];
            for(int i=0;i<count;i++)fractions[i]=Math.pow(10,-2*random.nextDouble());
            all.add(new State(point[0],point[1],fractions));
        }
        double[] heavy=new double[count];
        for(int i=0;i<count;i++)heavy[i]=Math.exp(-i/6.0);
        all.add(new State(360,300000,heavy));
        all.add(new State(480,900000,heavy));
        double[] light=new double[count];
        light[0]=.7;light[1]=.2;light[2]=.1;
        all.add(new State(310,700000,light));
        return List.copyOf(all);
    }

    /** {@code amounts} always sums to one mole, so a mole perturbation is also a fraction perturbation. */
    private record State(double temperature,double pressure,double[] amounts,double[] fractions) {
        State(double temperature,double pressure,double[] raw) {
            this(temperature,pressure,normalized(raw),normalized(raw));
        }
        private static double[] normalized(double[] raw) {
            double total=0;for(double value:raw)total+=value;
            double[] fractions=new double[raw.length];
            for(int i=0;i<raw.length;i++)fractions[i]=raw[i]/total;
            return fractions;
        }
        /** {@code component} is a signed one-based index; zero means no composition perturbation. */
        TranslatedPengRobinson.Phase at(TranslatedPengRobinson eos,PhaseRoot root,
                                       double temperatureStep,double pressureStep,int component) {
            double[] moles=amounts;
            if(component!=0) {
                moles=amounts.clone();
                moles[Math.abs(component)-1]+=Math.signum(component)*MOLE_STEP;
            }
            double temperature=this.temperature+temperatureStep;
            return eos.evaluate(temperature,pressure+pressureStep,moles,root,eos.prepare(temperature));
        }
        boolean keepsRoot(TranslatedPengRobinson eos,PhaseRoot root,double temperatureStep,double pressureStep,int component) {
            try {
                var base=eos.evaluate(temperature,pressure,amounts,root,eos.prepare(temperature));
                var higher=at(eos,root,temperatureStep,pressureStep,component);
                var lower=at(eos,root,-temperatureStep,-pressureStep,component==0?0:-component);
                return sameBranch(base,higher)&&sameBranch(base,lower);
            } catch(RuntimeException unavailable){return false;}
        }
        private static boolean sameBranch(TranslatedPengRobinson.Phase base,TranslatedPengRobinson.Phase probe) {
            return probe.vaporBranch()==base.vaporBranch()
                    && Math.abs(probe.molarVolume()-base.molarVolume())<1e-3*Math.abs(base.molarVolume());
        }
        @Override public String toString(){return "T="+temperature+" P="+pressure;}
    }

    private static void record(Map<String,Worst> worst,String quantity,String at,double expected,double actual,double scale) {
        assertTrue(Double.isFinite(expected)&&Double.isFinite(actual),quantity+" must be finite at "+at);
        double denominator=Math.max(scale,Math.max(Math.abs(expected),Math.abs(actual)));
        double deviation=denominator==0?0:Math.abs(expected-actual)/denominator;
        var previous=worst.get(quantity);
        if(previous==null)worst.put(quantity,new Worst(deviation,expected,actual,at,1));
        else if(deviation>previous.deviation)worst.put(quantity,new Worst(deviation,expected,actual,at,previous.count+1));
        else worst.put(quantity,new Worst(previous.deviation,previous.expected,previous.actual,previous.at,previous.count+1));
    }

    private record Worst(double deviation,double expected,double actual,String at,int count) {
        @Override public String toString() {
            return String.format(Locale.ROOT,"max %.3e over %d comparisons at %s (difference %.12g, analytic %.12g)",
                    deviation,count,at,expected,actual);
        }
    }
}
