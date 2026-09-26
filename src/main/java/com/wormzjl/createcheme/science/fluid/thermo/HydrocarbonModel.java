package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;

/** Fluid-specific property snapshot. Does not replace or modify the column's property model. */
public final class HydrocarbonModel {
    /** The pressure the liquid equation of state is evaluated at before the global compressibility response carries
     * it to the state's own; a formulation constant, not a domain bound. */
    public static final double REFERENCE_PRESSURE = 2e6;
    /** A numerical guard on the hydrocarbon <em>partial</em> pressure a vapour is evaluated at, which in a gas that is
     * nearly all steam can be arbitrarily small; the state's own pressure is bounded by the package's domain. */
    public static final double VAPOR_PARTIAL_PRESSURE_FLOOR = 1e-6;
    private final MaterialCatalog.Package propertyPackage;
    private final TranslatedPengRobinson translated;
    private final GlobalLiquidResponse liquidResponse;
    private final String revision;
    /** Every temperature and pressure range comes from the package's data; see {@link FluidDomain}. */
    private final FluidDomain domain;

    public HydrocarbonModel(MaterialCatalog catalog,String packageId,double compressibility) {
        propertyPackage=catalog.requirePackage(packageId);
        liquidResponse=new GlobalLiquidResponse(compressibility);
        domain=FluidDomain.of(catalog,packageId);
        List<ThermoComponent> components=propertyPackage.properties().stream().map(p->new ThermoComponent(
                p.component(),p.pr().criticalTemperature(),p.pr().criticalPressure(),p.pr().acentricFactor(),p.molecularWeight())).toList();
        int count=components.size();
        double[][] interactions=new double[count][count], cp=new double[count][6], low=new double[count][];
        double[] joints=new double[count];
        for(int i=0;i<count;i++) {
            for(int j=0;j<count;j++) interactions[i][j]=propertyPackage.interactions().get(i).get(j);
            var property=propertyPackage.properties().get(i);
            for(int j=0;j<6;j++) cp[i][j]=property.cp().get(j);
            var segment=property.lowTemperatureCp();
            joints[i]=segment==null?Double.NEGATIVE_INFINITY:segment.belowKelvin();
            if(segment!=null){low[i]=new double[6];for(int j=0;j<6;j++)low[i][j]=segment.coefficients().get(j);}
        }
        var raw=new PengRobinson78(components,interactions);
        var calibrations=catalog.fluidData().volumeReferences();
        double[] shifts=new double[count];
        for(int i=0;i<count;i++) {
            var property=propertyPackage.properties().get(i);
            var point=calibrations.get(property.component());
            double t=point==null?property.standardTemperature():point.temperatureKelvin();
            double targetVolume=point==null?property.molecularWeight()/property.density():point.molarVolumeCubicMetres();
            double referenceP=point==null?property.standardPressure():point.pressurePascal();
            double[] pure=new double[count];pure[i]=1;
            double rawVolume=raw.evaluate(t,REFERENCE_PRESSURE,pure,PhaseRoot.LIQUID).compressibilityFactor()
                    *PengRobinson78.GAS_CONSTANT*t/REFERENCE_PRESSURE;
            shifts[i]=targetVolume*Math.exp(compressibility*(referenceP-REFERENCE_PRESSURE))-rawVolume;
        }
        translated=new TranslatedPengRobinson(components,interactions,cp,shifts,joints,low);
        revision=catalog.fluidThermoFingerprint(packageId)+":fluid-shared-k-v1:fluid-domain-data-v1:cp-segments-v1:catalog-volume-reference-v1:k="+Double.toHexString(compressibility);
    }

    public String revision() { return revision; }
    /** The package's fluid domain: the envelope and every component's range. */
    public FluidDomain domain() { return domain; }
    public int componentCount() { return propertyPackage.components().size(); }
    public List<String> components() { return propertyPackage.components(); }
    public double molecularWeight(int i) { return propertyPackage.properties().get(i).molecularWeight(); }
    public MaterialCatalog.Package propertyPackage() { return propertyPackage; }

    public Phase phase(double t,double p,double[] amounts,PhaseRoot root) {
        return phase(t,p,amounts,root,translated.prepare(t));
    }
    public TranslatedPengRobinson.Workspace prepare(double t){return translated.prepare(t);}
    /** The translated EOS this model evaluates; the package's derivative test differentiates against it. */
    TranslatedPengRobinson translated(){return translated;}
    /** Caller-owned derivative storage for an analytic node Jacobian; one per solve, refilled per node. */
    public TranslatedPengRobinson.Derivatives newDerivatives(){return translated.newDerivatives();}
    /**
     * Every first derivative of one hydrocarbon phase, filled into {@code output} without allocating.
     *
     * <p>This is the equation of state at the pressure given, which is what {@link #phase} passes it for a
     * vapor. For a liquid {@link #phase} evaluates at {@link #REFERENCE_PRESSURE} and then applies
     * {@link GlobalLiquidResponse}; a caller assembling a liquid node block must differentiate that
     * correction itself, because nothing here applies it.</p>
     */
    public void differentiate(double t,double p,double[] amounts,PhaseRoot root,
                              TranslatedPengRobinson.Workspace terms,TranslatedPengRobinson.Derivatives output) {
        if(amounts.length!=componentCount())throw new IllegalArgumentException("Hydrocarbon basis mismatch");
        translated.differentiate(t,p,amounts,root,terms,output);
    }
    /**
     * The log fugacity coefficients {@link #phase} would return, written into {@code out} with the same arithmetic and
     * the same domain checks, and the vapor-branch flag; no {@link Phase} record and no coefficient copies. For the
     * flash's equilibrium iteration.
     */
    public boolean logFugacityInto(double t,double p,double[] amounts,PhaseRoot root,TranslatedPengRobinson.Workspace terms,double[] out) {
        if(amounts.length!=componentCount())throw new IllegalArgumentException("Hydrocarbon basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid hydrocarbon state is not finite");
        domain.checkPhaseTemperature(t,amounts);
        if(root==PhaseRoot.VAPOR) {
            if(p<VAPOR_PARTIAL_PRESSURE_FLOOR)throw new IllegalArgumentException("Hydrocarbon vapour partial pressure below the numerical floor");
            if(p>domain.envelope().maximumPressure())domain.checkEnvelope(t,p);
        } else domain.check(t,p,amounts,null,0);
        if(root==PhaseRoot.VAPOR) {
            var gas=translated.evaluateValues(t,p,amounts,root,terms);
            System.arraycopy(gas.logFugacityCoefficientsView(),0,out,0,out.length);
            return gas.vaporBranch();
        }
        var reference=translated.evaluateValues(t,REFERENCE_PRESSURE,amounts,PhaseRoot.LIQUID,terms);
        var liquid=liquidResponse.evaluate(t,p,REFERENCE_PRESSURE,reference.molarVolume(),
                reference.volumeTemperatureDerivative(),reference.volumeSecondTemperatureDerivative(),
                reference.molarEnthalpy(),reference.heatCapacity());
        liquidResponse.logFugacityInto(t,p,REFERENCE_PRESSURE,liquid.pressureIntegral(),
                reference.logFugacityCoefficientsView(),reference.partialMolarVolumesView(),out);
        return false;
    }
    public Phase phase(double t,double p,double[] amounts,PhaseRoot root,TranslatedPengRobinson.Workspace terms) {
        if(amounts.length!=componentCount())throw new IllegalArgumentException("Hydrocarbon basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid hydrocarbon state is not finite");
        // Every carried component within its own temperature range (a thermo-domain violation otherwise). A liquid is
        // evaluated at the state pressure, which must lie in the range of every component it carries; a vapour at its
        // hydrocarbon partial pressure, which is only guarded numerically here and capped by the envelope, since the
        // state that carries it checks the total pressure.
        domain.checkPhaseTemperature(t,amounts);
        if(root==PhaseRoot.VAPOR) {
            if(p<VAPOR_PARTIAL_PRESSURE_FLOOR)throw new IllegalArgumentException("Hydrocarbon vapour partial pressure below the numerical floor");
            if(p>domain.envelope().maximumPressure())domain.checkEnvelope(t,p);
        } else domain.check(t,p,amounts,null,0);
        // The same numbers from the workspace's own buffer, copying only the coefficients the returned record keeps (the
        // vapor's ln phi; the liquid's is the response's own array).
        if(root==PhaseRoot.VAPOR) {
            var gas=translated.evaluateValues(t,p,amounts,root,terms);
            return new Phase(gas.molarVolume(),gas.molarEnthalpy(),gas.molarInternalEnergy(),
                    gas.volumePressureDerivative(),gas.logFugacityCoefficientsView().clone(),gas.vaporBranch());
        }
        var reference=translated.evaluateValues(t,REFERENCE_PRESSURE,amounts,PhaseRoot.LIQUID,terms);
        var liquid=liquidResponse.evaluate(t,p,REFERENCE_PRESSURE,reference.molarVolume(),
                reference.volumeTemperatureDerivative(),reference.volumeSecondTemperatureDerivative(),
                reference.molarEnthalpy(),reference.heatCapacity());
        double[] phi=liquidResponse.logFugacity(t,p,REFERENCE_PRESSURE,liquid.pressureIntegral(),
                reference.logFugacityCoefficientsView(),reference.partialMolarVolumesView());
        return new Phase(liquid.molarVolume(),liquid.molarEnthalpy(),liquid.molarInternalEnergy(),
                liquid.volumePressureDerivative(),phi,false);
    }

    /** The coefficients belong to the record from construction on. {@link #phase} is the only place
     * one is built, from an array allocated for it: the liquid response's result, or the vapor
     * evaluation's own, whose enclosing record it discards on the same line. */
    public record Phase(double molarVolume,double molarEnthalpy,double molarInternalEnergy,
                        double volumePressureDerivative,double[] logFugacity,boolean vaporBranch) {
        @Override public double[] logFugacity() { return logFugacity.clone(); }
        /** The coefficients themselves, for the solver packages. The caller must not mutate them. */
        public double[] logFugacityView() { return logFugacity; }
    }
}
