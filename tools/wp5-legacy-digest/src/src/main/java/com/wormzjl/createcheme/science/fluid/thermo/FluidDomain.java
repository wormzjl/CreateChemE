package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Objects;

/**
 * Where the fluid network may evaluate a state of one property package, read from the package's data. The rule:
 *
 * <p><b>The package range is the outer envelope; each component's range must lie within it (the catalog refuses a
 * package otherwise); a state is valid only inside the envelope and inside the range of every component it carries.</b>
 * A component is carried when the state holds any amount of it in any phase, a trace included: every amount a state
 * carries is evaluated by every correlation of its phase (the translated PR78 with its ideal-gas heat capacity, the
 * pure-component viscosity tables, the water correlations), and those are only as good as the range they were fitted
 * or sampled over. Water's range runs from its triple point (ice is not modelled) to its enthalpy correlation's limit,
 * both from the water model's data, and takes its pressure range from the envelope.
 *
 * <p>Nothing here is a constant of the code: the envelope is the package's {@code fluid_domain}, a component's range
 * its property record's {@code fluid_domain}, and the water range the water record's {@code triple_point} and
 * {@code max_enthalpy_temperature}. A state inside the intersection of every range (for the bundled network package
 * 293.15..900 K and 100 Pa..2 MPa) is accepted by four comparisons, the cost of the fixed bounds this replaces.
 */
public final class FluidDomain {
    private final String packageId;
    private final MaterialCatalog.Validity envelope;
    private final String[] names;
    private final double[] minimumTemperature,maximumTemperature,minimumPressure,maximumPressure;
    private final double waterMinimum,waterMaximum;
    /** The intersection of the envelope and of every range: a state inside it needs no per-component look. */
    private final double commonMinimumT,commonMaximumT,commonMinimumP,commonMaximumP;

    private FluidDomain(String packageId,MaterialCatalog.Validity envelope,String[] names,MaterialCatalog.Validity[] ranges,double waterMinimum,double waterMaximum) {
        this.packageId=packageId;this.envelope=envelope;this.names=names;
        int n=ranges.length;minimumTemperature=new double[n];maximumTemperature=new double[n];minimumPressure=new double[n];maximumPressure=new double[n];
        double lowT=Math.max(envelope.minimumTemperature(),waterMinimum),highT=Math.min(envelope.maximumTemperature(),waterMaximum);
        double lowP=envelope.minimumPressure(),highP=envelope.maximumPressure();
        for(int i=0;i<n;i++) {
            var range=ranges[i];
            minimumTemperature[i]=range.minimumTemperature();maximumTemperature[i]=range.maximumTemperature();
            minimumPressure[i]=range.minimumPressure();maximumPressure[i]=range.maximumPressure();
            lowT=Math.max(lowT,minimumTemperature[i]);highT=Math.min(highT,maximumTemperature[i]);
            lowP=Math.max(lowP,minimumPressure[i]);highP=Math.min(highP,maximumPressure[i]);
        }
        this.waterMinimum=waterMinimum;this.waterMaximum=waterMaximum;
        commonMinimumT=lowT;commonMaximumT=highT;commonMinimumP=lowP;commonMaximumP=highP;
    }

    /** The domain of a package the fluid network is built on. A package without a {@code fluid_domain} is refused. */
    public static FluidDomain of(MaterialCatalog catalog,String packageId) {
        Objects.requireNonNull(catalog);Objects.requireNonNull(packageId);
        var p=catalog.requirePackage(packageId);
        var envelope=catalog.fluidValidity(packageId).orElseThrow(()->new IllegalArgumentException(
                "Package "+packageId+" declares no fluid_domain; the fluid network cannot evaluate its states"));
        int n=p.components().size();var names=new String[n];var ranges=new MaterialCatalog.Validity[n];
        for(int i=0;i<n;i++) {
            names[i]=p.components().get(i);
            ranges[i]=catalog.fluidValidity(packageId,names[i]).orElseThrow(()->new IllegalArgumentException("Component declares no fluid_domain"));
        }
        return new FluidDomain(packageId,envelope,names,ranges,p.water().triplePoint(),p.water().maximumTemperature());
    }

    public String packageId(){return packageId;}
    public MaterialCatalog.Validity envelope(){return envelope;}
    /** A component's own range: {@code [Tmin, Tmax, Pmin, Pmax]}; the water slot (the index after the last hydrocarbon) is the water range. */
    public double[] range(int component) {
        if(component==names.length)return new double[]{waterMinimum,waterMaximum,envelope.minimumPressure(),envelope.maximumPressure()};
        return new double[]{minimumTemperature[component],maximumTemperature[component],minimumPressure[component],maximumPressure[component]};
    }

    /**
     * A state of these phase amounts at {@code t} and {@code p} (the total pressure): refuses it with the violation of
     * the carried component the largest amount of which is out of range, temperature before pressure, then the
     * envelope. {@code vapor} may be {@code null} when {@code liquid} already holds every amount.
     */
    public void check(double t,double p,double[] liquid,double[] vapor,double water) {
        if(t>=commonMinimumT&&t<=commonMaximumT&&p>=commonMinimumP&&p<=commonMaximumP)return;
        requireComponents(t,p,true,liquid,vapor,water);
        requireEnvelope(t,p);
    }
    /** An overall composition in conserved order, water last. */
    public void checkTotals(double t,double p,double[] overall) {
        if(overall.length!=names.length+1)throw new IllegalArgumentException("Fluid domain basis mismatch");
        if(t>=commonMinimumT&&t<=commonMaximumT&&p>=commonMinimumP&&p<=commonMaximumP)return;
        requireComponents(t,p,true,overall,null,overall[names.length]);
        requireEnvelope(t,p);
    }
    /**
     * One hydrocarbon phase at {@code t}: every carried component's temperature range and the envelope's. The phase's
     * pressure is not a state pressure (a vapour is evaluated at its hydrocarbon partial pressure), so it is checked by
     * the caller that knows the total.
     */
    public void checkPhaseTemperature(double t,double[] amounts) {
        if(t>=commonMinimumT&&t<=commonMaximumT)return;
        requireComponents(t,Double.NaN,false,amounts,null,0);
        if(t<envelope.minimumTemperature()||t>envelope.maximumTemperature())throw violation(ThermoDomainViolation.PACKAGE,ThermoDomainViolation.Property.TEMPERATURE,t,envelope.minimumTemperature(),envelope.maximumTemperature());
    }
    /** A state that carries no fluid component (dry solids): the envelope alone. */
    public void checkEnvelope(double t,double p){requireEnvelope(t,p);}
    /** A pressure a device holds a fluid at (a valve's regulated inlet): the envelope's pressure range. */
    public void checkPressure(double p) {
        if(!Double.isFinite(p))throw new IllegalArgumentException("Pressure is not finite");
        if(p<envelope.minimumPressure()||p>envelope.maximumPressure())
            throw violation(ThermoDomainViolation.PACKAGE,ThermoDomainViolation.Property.PRESSURE,p,envelope.minimumPressure(),envelope.maximumPressure());
    }
    /** Water at {@code t}: the water model's own range, from its triple point. */
    public void checkWaterTemperature(double t) {
        if(t<waterMinimum||t>waterMaximum)throw violation("Water",ThermoDomainViolation.Property.TEMPERATURE,t,waterMinimum,waterMaximum);
    }

    private void requireComponents(double t,double p,boolean pressure,double[] first,double[] second,double water) {
        int worst=-1;double largest=-1;
        for(int i=0;i<names.length;i++) {
            double amount=first[i]+(second==null?0:second[i]);
            if(amount>0&&(t<minimumTemperature[i]||t>maximumTemperature[i])&&amount>largest){worst=i;largest=amount;}
        }
        boolean waterOut=water>0&&(t<waterMinimum||t>waterMaximum);
        if(waterOut&&water>largest)throw violation("Water",ThermoDomainViolation.Property.TEMPERATURE,t,waterMinimum,waterMaximum);
        if(worst>=0)throw violation(names[worst],ThermoDomainViolation.Property.TEMPERATURE,t,minimumTemperature[worst],maximumTemperature[worst]);
        if(!pressure)return;
        worst=-1;largest=-1;
        for(int i=0;i<names.length;i++) {
            double amount=first[i]+(second==null?0:second[i]);
            if(amount>0&&(p<minimumPressure[i]||p>maximumPressure[i])&&amount>largest){worst=i;largest=amount;}
        }
        if(worst>=0)throw violation(names[worst],ThermoDomainViolation.Property.PRESSURE,p,minimumPressure[worst],maximumPressure[worst]);
    }
    private void requireEnvelope(double t,double p) {
        if(t<envelope.minimumTemperature()||t>envelope.maximumTemperature())
            throw violation(ThermoDomainViolation.PACKAGE,ThermoDomainViolation.Property.TEMPERATURE,t,envelope.minimumTemperature(),envelope.maximumTemperature());
        if(p<envelope.minimumPressure()||p>envelope.maximumPressure())
            throw violation(ThermoDomainViolation.PACKAGE,ThermoDomainViolation.Property.PRESSURE,p,envelope.minimumPressure(),envelope.maximumPressure());
    }
    private ThermoDomainViolation violation(String component,ThermoDomainViolation.Property property,double value,double minimum,double maximum) {
        return new ThermoDomainViolation(packageId,component,property,value,minimum,maximum);
    }
}
