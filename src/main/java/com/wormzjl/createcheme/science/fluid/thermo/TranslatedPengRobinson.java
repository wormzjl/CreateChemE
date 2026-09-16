package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;

/** Constant volume-translated PR78 with analytic fixed-composition volumetric/caloric derivatives. */
public final class TranslatedPengRobinson {
    private static final double R = PengRobinson78.GAS_CONSTANT;
    private static final double SQRT_TWO = Math.sqrt(2);
    private static final double REFERENCE_T = 298.15;
    private final List<ThermoComponent> components;
    private final double[] sqrtCriticalA,kappas,inverseSqrtCriticalTemperature,coVolumes;
    private final double[][] interactions;
    private final double[][] heatCapacityCoefficients;
    private final double[] translations;

    /** Cp coefficients are powers of (T - 298.15 K); each component has exactly six coefficients. */
    public TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions,
                                 double[][] heatCapacityCoefficients, double[] translations) {
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || interactions.length != count || heatCapacityCoefficients.length != count
                || translations.length != count) throw new IllegalArgumentException("Inconsistent property basis");
        this.interactions = new double[count][];
        this.heatCapacityCoefficients = new double[count][];
        this.translations = translations.clone();
        sqrtCriticalA=new double[count];kappas=new double[count];inverseSqrtCriticalTemperature=new double[count];coVolumes=new double[count];
        for (int i = 0; i < count; i++) {
            if (interactions[i].length != count || heatCapacityCoefficients[i].length != 6) {
                throw new IllegalArgumentException("Invalid property matrix dimensions");
            }
            this.interactions[i] = interactions[i].clone();
            this.heatCapacityCoefficients[i] = heatCapacityCoefficients[i].clone();
            for (double value : this.interactions[i]) finite(value);
            for (double value : this.heatCapacityCoefficients[i]) finite(value);
            finite(this.translations[i]);
            var component=components.get(i);double w=component.acentricFactor();
            kappas[i]=w<=.491?.37464+1.54226*w-.26992*w*w:.379642+1.48503*w-.164423*w*w+.016666*w*w*w;
            sqrtCriticalA[i]=Math.sqrt(.45724*R*R*component.criticalTemperatureKelvin()*component.criticalTemperatureKelvin()/component.criticalPressurePascal());
            inverseSqrtCriticalTemperature[i]=1/Math.sqrt(component.criticalTemperatureKelvin());
            coVolumes[i]=.07780*R*component.criticalTemperatureKelvin()/component.criticalPressurePascal();
        }
        for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) {
            if (this.interactions[i][j] != this.interactions[j][i]) throw new IllegalArgumentException("Asymmetric interactions");
        }
    }

    public Phase evaluate(double temperature, double pressure, double[] amounts, PhaseRoot root) {
        return evaluate(temperature,pressure,amounts,root,temperatureTerms(temperature));
    }

    /** Immutable coefficients for a single exact temperature; a solve workspace can reuse them across composition trials. */
    public static final class TemperatureTerms {
        private final TranslatedPengRobinson owner;
        private final double temperature;
        private final double[][] a,first,second;
        private final double[] heatCapacity,enthalpy;
        private TemperatureTerms(TranslatedPengRobinson owner,double temperature,double[][] a,double[][] first,double[][] second,double[] cp,double[] h) {
            this.owner=owner;this.temperature=temperature;this.a=a;this.first=first;this.second=second;heatCapacity=cp;enthalpy=h;
        }
        public double temperature(){return temperature;}
    }
    public TemperatureTerms temperatureTerms(double temperature) {
        SolverDiagnostics.count(SolverDiagnostics.temperatureTermsCalls);
        if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid coefficient temperature");
        int count=components.size();double[] sqrtAttraction=new double[count],slopeAlpha=new double[count],curvatureAlpha=new double[count],cp=new double[count],h=new double[count];
        double sqrtT=Math.sqrt(temperature),inverseSqrtT=1/sqrtT,delta=temperature-REFERENCE_T;
        for(int i=0;i<count;i++) {
            double k=kappas[i],inverseSqrtTc=inverseSqrtCriticalTemperature[i],f=1+k*(1-sqrtT*inverseSqrtTc);
            if(f==0)throw new IllegalArgumentException("Degenerate PR alpha derivative");
            double df=-.5*k*inverseSqrtT*inverseSqrtTc,ddf=.25*k*inverseSqrtTc*inverseSqrtT/temperature;
            sqrtAttraction[i]=sqrtCriticalA[i]*Math.abs(f);slopeAlpha[i]=2*df/f;curvatureAlpha[i]=2*(ddf/f-df*df/(f*f));
            for(int term=5;term>=0;term--){cp[i]=cp[i]*delta+heatCapacityCoefficients[i][term];h[i]=h[i]*delta+heatCapacityCoefficients[i][term]/(term+1);}
            h[i]*=delta;
        }
        double[][] pair=new double[count][count],first=new double[count][count],second=new double[count][count];
        for(int i=0;i<count;i++)for(int j=i;j<count;j++) {
            double value=sqrtAttraction[i]*sqrtAttraction[j]*(1-interactions[i][j]),slope=.5*(slopeAlpha[i]+slopeAlpha[j]);
            pair[i][j]=pair[j][i]=value;first[i][j]=first[j][i]=value*slope;second[i][j]=second[j][i]=value*(slope*slope+.5*(curvatureAlpha[i]+curvatureAlpha[j]));
        }
        return new TemperatureTerms(this,temperature,pair,first,second,cp,h);
    }

    public Phase evaluate(double temperature,double pressure,double[] amounts,PhaseRoot root,TemperatureTerms terms) {
        if (!Double.isFinite(temperature) || temperature <= 0 || !Double.isFinite(pressure) || pressure <= 0) {
            throw new IllegalArgumentException("Positive finite temperature and pressure required");
        }
        if(terms.owner!=this||temperature!=terms.temperature)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        double[] x = normalize(amounts);
        double[] attractionRows=new double[x.length];
        double b = 0, shift = 0, idealH = 0, idealCp = 0;
        for (int i = 0; i < x.length; i++) {
            b += x[i] * coVolumes[i];
            shift += x[i] * translations[i];
            idealCp+=x[i]*terms.heatCapacity[i];idealH+=x[i]*terms.enthalpy[i];
        }
        double a = 0, da = 0, dda = 0;
        for(int i=0;i<x.length;i++) {
            double firstRow=0,secondRow=0;
            for(int j=0;j<x.length;j++) {
                if(x[j]==0)continue;
                attractionRows[i]+=x[j]*terms.a[i][j];
                if(x[i]==0)continue;
                firstRow+=x[j]*terms.first[i][j];secondRow+=x[j]*terms.second[i][j];
            }
            a+=x[i]*attractionRows[i];da+=x[i]*firstRow;dda+=x[i]*secondRow;
        }
        double rt=R*temperature,reducedA=a*pressure/(rt*rt),reducedB=b*pressure/rt;
        double z=PengRobinson78.compressibilityRoot(reducedA,reducedB,root),v=z*rt/pressure;
        double denominator = v*v + 2*b*v - b*b;
        double dpdv = -R*temperature / ((v-b)*(v-b)) + 2*a*(v+b) / (denominator*denominator);
        double dpdt = R/(v-b) - da/denominator;
        if (!(dpdv < 0)) throw new IllegalArgumentException("Mechanically unstable PR root");
        double dvdp = 1/dpdv, dvdt = -dpdt/dpdv;
        double dpdvv = 2*R*temperature/Math.pow(v-b,3) + 2*a/(denominator*denominator)
                - 8*a*(v+b)*(v+b)/Math.pow(denominator,3);
        double dpdtv = -R/((v-b)*(v-b)) + 2*da*(v+b)/(denominator*denominator);
        double dvdtt = -(-dda/denominator + 2*dpdtv*dvdt + dpdvv*dvdt*dvdt)/dpdv;
        double physicalV = v + shift;
        double log = Math.log((v+(1+SQRT_TWO)*b)/(v+(1-SQRT_TWO)*b));
        double h=idealH+rt*(z-1)+(temperature*da-a)/(2*SQRT_TWO*b)*log+pressure*shift;
        double dlogdv = 1/(v+(1+SQRT_TWO)*b) - 1/(v+(1-SQRT_TWO)*b);
        double cp = idealCp + pressure*dvdt - R + temperature*dda/(2*SQRT_TWO*b)*log
                + (temperature*da-a)/(2*SQRT_TWO*b)*dlogdv*dvdt;
        double dhdp = physicalV - temperature*dvdt;
        double[] logPhi = new double[x.length];
        double[] partialVolumes = new double[x.length];
        double freeVolumeLog=Math.log(z-reducedB),attractionFactor=a/(2*SQRT_TWO*b*rt);
        for (int i=0;i<x.length;i++) {
            double bRatio=coVolumes[i]/b;
            logPhi[i]=bRatio*(z-1)-freeVolumeLog-attractionFactor*(2*attractionRows[i]/a-bRatio)*log+pressure*translations[i]/rt;
            double db=coVolumes[i]-b;
            double pressureCompositionDerivative=-2*(attractionRows[i]-a)/denominator
                    +db*(R*temperature/((v-b)*(v-b))+2*a*(v-b)/(denominator*denominator));
            partialVolumes[i]=v-pressureCompositionDerivative/dpdv+translations[i];
            if(!Double.isFinite(logPhi[i])||!Double.isFinite(partialVolumes[i]))throw new IllegalArgumentException("Nonfinite chemical-potential derivative");
        }
        if (!(physicalV > 0) || !(cp > 0) || !Double.isFinite(h) || !Double.isFinite(cp)
                || !Double.isFinite(dvdp) || !Double.isFinite(dvdt) || !Double.isFinite(dhdp)) {
            throw new IllegalArgumentException("Invalid translated phase properties");
        }
        return new Phase(physicalV, h, h-pressure*physicalV, dvdt, dvdp, cp, dhdp,
                -dvdp/physicalV, logPhi, dvdtt, partialVolumes,
                a/(R*temperature*b)<5.8773599486044 || v/b>3.9513730355914);
    }

    private double[] normalize(double[] amounts) {
        if (amounts.length != components.size()) throw new IllegalArgumentException("Composition basis mismatch");
        double[] x = amounts.clone();
        double sum = 0;
        for (double n : x) { if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException("Invalid composition"); sum += n; }
        if (!(sum > 0) || !Double.isFinite(sum)) throw new IllegalArgumentException("Empty/nonfinite composition");
        for (int i = 0; i < x.length; i++) x[i] /= sum;
        return x;
    }
    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite property coefficient");
    }

    /** The arrays belong to the record from construction on; {@link #evaluate} builds them for it. */
    public record Phase(double molarVolume, double molarEnthalpy, double molarInternalEnergy,
                        double volumeTemperatureDerivative, double volumePressureDerivative,
                        double heatCapacity, double enthalpyPressureDerivative,
                        double isothermalCompressibility, double[] logFugacityCoefficients,
                        double volumeSecondTemperatureDerivative,double[] partialMolarVolumes,boolean vaporBranch) {
        @Override public double[] logFugacityCoefficients() { return logFugacityCoefficients.clone(); }
        @Override public double[] partialMolarVolumes() { return partialMolarVolumes.clone(); }
        /** The coefficients themselves, for the solver packages. The caller must not mutate them. */
        public double[] logFugacityCoefficientsView() { return logFugacityCoefficients; }
        public double[] partialMolarVolumesView() { return partialMolarVolumes; }
    }
}
