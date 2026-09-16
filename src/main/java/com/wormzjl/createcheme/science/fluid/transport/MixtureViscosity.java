package com.wormzjl.createcheme.science.fluid.transport;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.fluid.thermo.WaterRegion1;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.ViscosityCorrelation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Reference-pressure transport approximation: logarithmic liquid mixing and Wilke vapor mixing. */
public final class MixtureViscosity {
    private static final Map<String,Curve> CONDITIONAL = loadConditional();
    private final String revision;
    private final MaterialCatalog.Package propertyPackage;
    private final ViscosityCorrelation[] liquidCorrelations,vaporCorrelations;
    private final ViscosityCorrelation waterLiquidCorrelation;
    private final double[][] wilkeMassQuarter,wilkeDenominator;

    public MixtureViscosity(MaterialCatalog catalog,String packageId) {
        propertyPackage=catalog.requirePackage(packageId);
        // The catalog is an immutable property snapshot. Compute its transport hash once,
        // not on every server-thread admission, anchor check and worker publication.
        revision=catalog.viscosityFingerprint(packageId)+":log-liquid-wilke-v1:dwsim-conditional-solute-v1";
        int count=propertyPackage.components().size();liquidCorrelations=new ViscosityCorrelation[count];vaporCorrelations=new ViscosityCorrelation[count];
        double[] mw=new double[count+1];
        for(int i=0;i<count;i++) {
            String component=propertyPackage.components().get(i);
            liquidCorrelations[i]=catalog.viscosity(packageId,component,ViscosityCorrelation.Phase.LIQUID).orElse(null);
            vaporCorrelations[i]=catalog.viscosity(packageId,component,ViscosityCorrelation.Phase.VAPOR).orElse(null);
            mw[i]=propertyPackage.properties().get(i).molecularWeight();
        }
        mw[count]=propertyPackage.water().molarMass();waterLiquidCorrelation=catalog.viscosity(packageId,"Water",ViscosityCorrelation.Phase.LIQUID).orElse(null);
        wilkeMassQuarter=new double[count+1][count+1];wilkeDenominator=new double[count+1][count+1];
        for(int i=0;i<=count;i++)for(int j=0;j<=count;j++){wilkeMassQuarter[i][j]=Math.sqrt(Math.sqrt(mw[j]/mw[i]));wilkeDenominator[i][j]=1/Math.sqrt(8*(1+mw[i]/mw[j]));}
    }
    public String revision() {
        return revision;
    }

    /** Conditional solute factors are only used in a mixture with a supported liquid carrier. */
    public Result liquid(double temperature,double[] amounts) {
        checkBasis(amounts);
        double sum=0,logSum=0,carrier=0,conditional=0;
        for(int i=0;i<amounts.length;i++) {
            double n=amounts[i];if(n==0)continue;sum+=n;
            String name=propertyPackage.components().get(i);
            var correlation=liquidCorrelations[i];
            double mu;
            if(correlation!=null && temperature>=correlation.minimumTemperatureKelvin()
                    && temperature<=correlation.maximumTemperatureKelvin()) {
                mu=correlation.dynamicViscosityPascalSeconds(temperature,correlation.minimumPressurePascal());carrier+=n;
            } else {
                Curve curve=CONDITIONAL.get(name);
                if(curve==null || correlation==null || temperature<correlation.minimumTemperatureKelvin()) {
                    throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: liquid viscosity for "+name);
                }
                mu=curve.evaluate(temperature);conditional+=n;
            }
            logSum+=n*Math.log(mu);
        }
        if(!(sum>0) || !Double.isFinite(sum) || !(carrier>0)) {
            throw new IllegalArgumentException("Liquid mixture needs a supported carrier; no pure-liquid claim for conditional solutes");
        }
        return new Result(positive(Math.exp(logSum/sum)),conditional>0);
    }

    public double waterLiquid(double temperature) {
        var c=waterLiquidCorrelation;if(c==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: water liquid viscosity");
        return c.dynamicViscosityPascalSeconds(temperature,c.minimumPressurePascal());
    }

    public double vapor(double temperature,double[] amounts,double waterMoles) {
        checkBasis(amounts);
        if(!Double.isFinite(waterMoles)||waterMoles<0)throw new IllegalArgumentException("Invalid water amount");
        int count=amounts.length+1;double[] n=Arrays.copyOf(amounts,count),mu=new double[count],sqrtMu=new double[count];
        n[count-1]=waterMoles;
        for(int i=0;i<amounts.length;i++) {
            if(n[i]==0)continue;
            var c=vaporCorrelations[i];if(c==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: vapor viscosity");
            mu[i]=c.dynamicViscosityPascalSeconds(temperature,c.minimumPressurePascal());
        }
        if(waterMoles>0)mu[count-1]=WaterRegion1.diluteVaporViscosity(temperature);
        for(int i=0;i<count;i++)if(n[i]>0)sqrtMu[i]=Math.sqrt(mu[i]);
        double result=0;
        for(int i=0;i<count;i++) {
            if(n[i]==0)continue;
            double denominator=0;
            for(int j=0;j<count;j++)if(n[j]>0) {
                double factor=1+sqrtMu[i]/sqrtMu[j]*wilkeMassQuarter[i][j];
                denominator+=n[j]*factor*factor*wilkeDenominator[i][j];
            }
            result+=n[i]*mu[i]/denominator;
        }
        return positive(result);
    }

    private void checkBasis(double[] amounts) {
        if(amounts.length!=propertyPackage.components().size())throw new IllegalArgumentException("Transport basis mismatch");
        for(double n:amounts)if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid transport amount");
    }
    private static double positive(double value) {
        if(!(value>0)||!Double.isFinite(value))throw new IllegalArgumentException("Invalid viscosity result");return value;
    }
    private static Map<String,Curve> loadConditional() {
        try(var input=MixtureViscosity.class.getResourceAsStream("/data/createcheme/fluid/dissolved_viscosity.json")) {
            if(input==null)throw new IllegalStateException("Missing conditional-solute reference data");
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            var result=new HashMap<String,Curve>();
            for(var value:json.getAsJsonArray("curves")) {
                var curve=value.getAsJsonObject();
                double[] t=new double[curve.getAsJsonArray("temperatures_kelvin").size()],mu=new double[t.length];
                for(int i=0;i<t.length;i++) {
                    t[i]=curve.getAsJsonArray("temperatures_kelvin").get(i).getAsDouble();
                    mu[i]=curve.getAsJsonArray("viscosities_pascal_seconds").get(i).getAsDouble();
                    positive(t[i]);positive(mu[i]);if(i>0&&t[i]<=t[i-1])throw new IllegalArgumentException("Unsorted reference temperatures");
                }
                result.put(curve.get("component").getAsString(),new Curve(t,mu));
            }
            return Map.copyOf(result);
        } catch(java.io.IOException e){throw new IllegalStateException("Cannot read conditional references",e);}
    }
    private record Curve(double[] t,double[] mu) {
        double evaluate(double temperature) {
            if(!Double.isFinite(temperature)||temperature<t[0]||temperature>t[t.length-1]) {
                throw new IllegalArgumentException("Conditional-solute reference outside sampled domain");
            }
            int at=Arrays.binarySearch(t,temperature);if(at>=0)return mu[at];
            int hi=-at-1,lo=hi-1;double f=(temperature-t[lo])/(t[hi]-t[lo]);
            return Math.exp(Math.log(mu[lo])+f*(Math.log(mu[hi])-Math.log(mu[lo])));
        }
    }
    public record Result(double pascalSeconds,boolean conditionalSoluteApproximation) {}
}
