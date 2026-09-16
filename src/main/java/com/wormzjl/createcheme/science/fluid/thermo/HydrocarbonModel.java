package com.wormzjl.createcheme.science.fluid.thermo;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fluid-specific property snapshot. Does not replace or modify the column's property model. */
public final class HydrocarbonModel {
    public static final double REFERENCE_PRESSURE = 2e6;
    public static final double MINIMUM_PRESSURE = 100;
    private final MaterialCatalog.Package propertyPackage;
    private final TranslatedPengRobinson translated;
    private final GlobalLiquidResponse liquidResponse;
    private final String revision;

    public HydrocarbonModel(MaterialCatalog catalog,String packageId,double compressibility) {
        propertyPackage=catalog.requirePackage(packageId);
        liquidResponse=new GlobalLiquidResponse(compressibility);
        List<ThermoComponent> components=propertyPackage.properties().stream().map(p->new ThermoComponent(
                p.component(),p.pr().criticalTemperature(),p.pr().criticalPressure(),p.pr().acentricFactor(),p.molecularWeight())).toList();
        int count=components.size();
        double[][] interactions=new double[count][count], cp=new double[count][6];
        for(int i=0;i<count;i++) {
            for(int j=0;j<count;j++) interactions[i][j]=propertyPackage.interactions().get(i).get(j);
            for(int j=0;j<6;j++) cp[i][j]=propertyPackage.properties().get(i).cp().get(j);
        }
        var raw=new PengRobinson78(components,interactions);
        var calibrations=loadCalibration();
        double[] shifts=new double[count];
        for(int i=0;i<count;i++) {
            var property=propertyPackage.properties().get(i);
            var point=calibrations.get(property.component());
            double t=point==null?property.standardTemperature():point.temperature;
            double targetVolume=point==null?property.molecularWeight()/property.density():point.volume;
            double referenceP=point==null?property.standardPressure():point.pressure;
            double[] pure=new double[count];pure[i]=1;
            double rawVolume=raw.evaluate(t,REFERENCE_PRESSURE,pure,PhaseRoot.LIQUID).compressibilityFactor()
                    *PengRobinson78.GAS_CONSTANT*t/REFERENCE_PRESSURE;
            shifts[i]=targetVolume*Math.exp(compressibility*(referenceP-REFERENCE_PRESSURE))-rawVolume;
        }
        translated=new TranslatedPengRobinson(components,interactions,cp,shifts);
        revision=propertyPackage.scientificRevision()+":fluid-shared-k-v1:nist-liquid-calibration-20260915:k="+Double.toHexString(compressibility);
    }

    public String revision() { return revision; }
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
    public Phase phase(double t,double p,double[] amounts,PhaseRoot root,TranslatedPengRobinson.Workspace terms) {
        if(amounts.length!=componentCount())throw new IllegalArgumentException("Hydrocarbon basis mismatch");
        double minimumTemperature=0;
        for(int i=0;i<amounts.length;i++)if(amounts[i]>0)minimumTemperature=Math.max(minimumTemperature,propertyPackage.properties().get(i).minimumTemperature());
        if(!Double.isFinite(t) || t<minimumTemperature || t>propertyPackage.maximumTemperature()
                || !Double.isFinite(p) || p<(root==PhaseRoot.VAPOR?1e-6:MINIMUM_PRESSURE)
                || p>Math.min(REFERENCE_PRESSURE,propertyPackage.maximumPressure())) {
            throw new IllegalArgumentException("Fluid hydrocarbon state outside model domain");
        }
        if(root==PhaseRoot.VAPOR) {
            var gas=translated.evaluate(t,p,amounts,root,terms);
            return new Phase(gas.molarVolume(),gas.molarEnthalpy(),gas.molarInternalEnergy(),
                    gas.volumePressureDerivative(),gas.logFugacityCoefficientsView(),gas.vaporBranch());
        }
        var reference=translated.evaluate(t,REFERENCE_PRESSURE,amounts,PhaseRoot.LIQUID,terms);
        var liquid=liquidResponse.evaluate(t,p,REFERENCE_PRESSURE,reference.molarVolume(),
                reference.volumeTemperatureDerivative(),reference.volumeSecondTemperatureDerivative(),
                reference.molarEnthalpy(),reference.heatCapacity());
        double[] phi=liquidResponse.logFugacity(t,p,REFERENCE_PRESSURE,liquid.pressureIntegral(),
                reference.logFugacityCoefficientsView(),reference.partialMolarVolumesView());
        return new Phase(liquid.molarVolume(),liquid.molarEnthalpy(),liquid.molarInternalEnergy(),
                liquid.volumePressureDerivative(),phi,false);
    }

    private static Map<String,Calibration> loadCalibration() {
        String path="/data/createcheme/fluid/liquid_calibration.json";
        try(var input=HydrocarbonModel.class.getResourceAsStream(path)) {
            if(input==null) throw new IllegalStateException("Missing fluid calibration data");
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            var result=new HashMap<String,Calibration>();
            for(var value:json.getAsJsonArray("points")) {
                var point=value.getAsJsonObject();
                result.put(point.get("component").getAsString(),new Calibration(point.get("temperatureKelvin").getAsDouble(),
                        point.get("pressurePascal").getAsDouble(),point.get("molarVolumeCubicMetres").getAsDouble()));
            }
            return Map.copyOf(result);
        } catch(java.io.IOException error) { throw new IllegalStateException("Cannot read fluid calibration",error); }
    }
    private record Calibration(double temperature,double pressure,double volume) {}
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
