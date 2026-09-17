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
        revision=catalog.viscosityFingerprint(packageId)+":log-liquid-wilke-v1:dwsim-conditional-solute-ambient-v2";
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

    /**
     * The pure-component terms of one exact temperature: the logarithm of each liquid viscosity
     * with the regime it came from, each vapor viscosity and its square root, and the water liquid
     * value. Composition-dependent mixing - the logarithmic weighting and Wilke's double loop -
     * stays per evaluation, but nothing here depends on composition or pressure.
     *
     * <p>Each component is filled the first time a mixture actually contains it, never in advance:
     * the correlations refuse temperatures outside their own range and the conditional-solute
     * curves refuse temperatures outside their sampled domain, and which of those a caller meets
     * must stay exactly what the composition it passed would have produced.
     */
    public static final class Prepared {
        private static final byte UNFILLED=0,CARRIER=1,CONDITIONAL_SOLUTE=2;
        private final MixtureViscosity owner;
        private final double temperature;
        private final double[] liquidLog,vaporMu,vaporSqrt;
        private final byte[] liquidKind;
        private double water=Double.NaN;
        private Prepared(MixtureViscosity owner,double temperature) {
            this.owner=owner;this.temperature=temperature;
            int count=owner.propertyPackage.components().size();
            liquidLog=new double[count];liquidKind=new byte[count];
            vaporMu=new double[count+1];vaporSqrt=new double[count+1];
            Arrays.fill(vaporMu,Double.NaN);
        }
        public double temperature() { return temperature; }
        private byte liquid(int component) {
            if(liquidKind[component]==UNFILLED) {
                owner.fillLiquid(this,component);
            }
            return liquidKind[component];
        }
        private double vapor(int component) {
            if(Double.isNaN(vaporMu[component])) {
                vaporMu[component]=owner.pureVapor(temperature,component);vaporSqrt[component]=Math.sqrt(vaporMu[component]);
            }
            return vaporMu[component];
        }
    }
    /** The bundle for one exact temperature; a caller holds one per node and reuses it across trials. */
    public Prepared prepare(double temperature) { return new Prepared(this,temperature); }
    private Prepared own(Prepared prepared,double temperature) {
        if(prepared!=null&&(prepared.owner!=this||prepared.temperature!=temperature)) {
            throw new IllegalArgumentException("Prepared viscosities belong to another model/temperature");
        }
        return prepared;
    }
    private void fillLiquid(Prepared prepared,int component) {
        String name=propertyPackage.components().get(component);
        var correlation=liquidCorrelations[component];double temperature=prepared.temperature,mu;
        if(correlation!=null && temperature>=correlation.minimumTemperatureKelvin()
                && temperature<=correlation.maximumTemperatureKelvin()) {
            mu=correlation.dynamicViscosityPascalSeconds(temperature,correlation.minimumPressurePascal());
            prepared.liquidKind[component]=Prepared.CARRIER;
        } else {
            Curve curve=CONDITIONAL.get(name);
            if(curve==null || correlation==null || temperature<correlation.minimumTemperatureKelvin()) {
                throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: liquid viscosity for "+name);
            }
            mu=curve.evaluate(temperature);prepared.liquidKind[component]=Prepared.CONDITIONAL_SOLUTE;
        }
        prepared.liquidLog[component]=Math.log(mu);
    }
    private double pureVapor(double temperature,int component) {
        if(component==liquidCorrelations.length)return WaterRegion1.diluteVaporViscosity(temperature);
        var c=vaporCorrelations[component];if(c==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: vapor viscosity");
        return c.dynamicViscosityPascalSeconds(temperature,c.minimumPressurePascal());
    }

    /** Conditional solute factors are only used in a mixture with a supported liquid carrier. */
    public Result liquid(double temperature,double[] amounts) {
        return liquid(temperature,amounts,null);
    }
    public Result liquid(double temperature,double[] amounts,Prepared prepared) {
        checkBasis(amounts);own(prepared,temperature);
        var terms=prepared==null?prepare(temperature):prepared;
        double sum=0,logSum=0,carrier=0,conditional=0;
        for(int i=0;i<amounts.length;i++) {
            double n=amounts[i];if(n==0)continue;sum+=n;
            if(terms.liquid(i)==Prepared.CARRIER)carrier+=n;else conditional+=n;
            logSum+=n*terms.liquidLog[i];
        }
        if(!(sum>0) || !Double.isFinite(sum) || !(carrier>0)) {
            throw new IllegalArgumentException("Liquid mixture needs a supported carrier; no pure-liquid claim for conditional solutes");
        }
        return new Result(positive(Math.exp(logSum/sum)),conditional>0);
    }

    public double waterLiquid(double temperature) {
        return waterLiquid(temperature,null);
    }
    public double waterLiquid(double temperature,Prepared prepared) {
        own(prepared,temperature);
        if(prepared!=null&&!Double.isNaN(prepared.water))return prepared.water;
        var c=waterLiquidCorrelation;if(c==null)throw new IllegalArgumentException("PROPERTY_UNAVAILABLE: water liquid viscosity");
        double value=c.dynamicViscosityPascalSeconds(temperature,c.minimumPressurePascal());
        if(prepared!=null)prepared.water=value;
        return value;
    }

    /** Per-thread scratch for {@link #vapor}: the amount vector over the basis with water, refilled
     * by the solver that owns it instead of allocated per decoded node. */
    public static final class Workspace {
        private double[] n=new double[0];
        private double[] amounts(int count) {
            if(n.length!=count)n=new double[count];
            return n;
        }
    }
    public double vapor(double temperature,double[] amounts,double waterMoles) {
        return vapor(temperature,amounts,waterMoles,null,null);
    }
    public double vapor(double temperature,double[] amounts,double waterMoles,Workspace workspace,Prepared prepared) {
        checkBasis(amounts);own(prepared,temperature);
        if(!Double.isFinite(waterMoles)||waterMoles<0)throw new IllegalArgumentException("Invalid water amount");
        var terms=prepared==null?prepare(temperature):prepared;
        int count=amounts.length+1;
        double[] n=workspace==null?new double[count]:workspace.amounts(count);
        System.arraycopy(amounts,0,n,0,amounts.length);n[count-1]=waterMoles;
        // Pure-component viscosity and its square root depend on temperature alone; fill in index
        // order, so the first component without a correlation still refuses the same mixture.
        for(int i=0;i<count;i++)if(n[i]>0)terms.vapor(i);
        double[] mu=terms.vaporMu,sqrtMu=terms.vaporSqrt;
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
