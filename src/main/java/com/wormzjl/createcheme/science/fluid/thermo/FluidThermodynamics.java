package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import java.util.Arrays;

/** Immutable fluid property snapshot and standalone TP initializer; coupled steps consume phase() directly. */
public final class FluidThermodynamics {
    public static final double R=8.31446261815324;
    public static final double DEFAULT_MAXIMUM_VELOCITY=100;
    public final HydrocarbonModel hydrocarbon;
    public final MixtureViscosity viscosity;
    public final double waterMolecularWeight;
    private final GlobalLiquidResponse liquidResponse;
    /** Resolved once from the catalog and package this model was built for, instead of through the
     * calculation context on every water evaluation: the context costs a {@code Context} allocation
     * and a {@code ThreadLocal} set/remove per call, and a coefficient read inside costs a
     * {@code ThreadLocal} lookup and two immutable-map probes. */
    private final MaterialCatalog.Water water;
    private final double waterEnthalpyOffset;
    private final double maximumVelocity;

    /** Canonical gameplay basis includes nitrogen; direct construction retains an explicitly supplied test/science basis. */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double liquidCompressibility) {
        return forNetwork(catalog,packageId,liquidCompressibility,DEFAULT_MAXIMUM_VELOCITY);
    }
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity) {
        return new FluidThermodynamics(FluidMaterialCatalog.withNitrogen(catalog,packageId),packageId,liquidCompressibility,maximumVelocity);
    }

    /** Placement initializer only. Persistence must restore inventory instead of calling this again. */
    public State initialNitrogenCharge(double volume,double temperature,double pressure,Runnable checkpoint) {
        if(!Double.isFinite(volume)||volume<=0)throw new IllegalArgumentException("Positive finite vessel volume required");
        int nitrogen=hydrocarbon.components().indexOf("Nitrogen");if(nitrogen<0)throw new IllegalStateException("Network basis has no nitrogen");
        double[] n=new double[hydrocarbon.componentCount()+1];n[nitrogen]=1;
        var unit=flashTP(temperature,pressure,n,checkpoint);n[nitrogen]=volume/unit.volume();
        return flashTP(temperature,pressure,n,checkpoint);
    }

    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double liquidCompressibility) {
        this(catalog,packageId,liquidCompressibility,DEFAULT_MAXIMUM_VELOCITY);
    }
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity) {
        if(!Double.isFinite(maximumVelocity)||maximumVelocity<=0)throw new IllegalArgumentException("Positive finite maximum velocity required");
        this.maximumVelocity=maximumVelocity;
        hydrocarbon=new HydrocarbonModel(catalog,packageId,liquidCompressibility);
        viscosity=new MixtureViscosity(catalog,packageId);
        waterMolecularWeight=catalog.requirePackage(packageId).water().molarMass();
        liquidResponse=new GlobalLiquidResponse(liquidCompressibility);
        // The same resolution the context form performs, including its fallback package.
        water=com.wormzjl.createcheme.science.material.MaterialRuntime.with(catalog,packageId,
                com.wormzjl.createcheme.science.material.MaterialRuntime::water);
        var reference=waterLiquidRaw(298.15,101325,null);
        waterEnthalpyOffset=V3WaterProperties.liquidMolarEnthalpy(water,298.15)-reference.molarEnthalpy();
    }
    /** Conserved component basis: registered hydrocarbon/gas components, followed by water. */
    public int componentCount() { return hydrocarbon.componentCount()+1; }

    /** Immutable conserved-component names, including water in the final slot. */
    public java.util.List<String> components() {
        var components=new java.util.ArrayList<>(hydrocarbon.components());
        components.add("Water");
        return java.util.List.copyOf(components);
    }

    /** Molecular weight in kg/mol for an index in the complete conserved basis. */
    public double molecularWeight(int component) {
        return component==hydrocarbon.componentCount()?waterMolecularWeight:hydrocarbon.molecularWeight(component);
    }

    /** Independently owned molecular-weight array in conserved-component order. */
    public double[] molecularWeights() {
        double[] weights=new double[componentCount()];
        for(int c=0;c<weights.length;c++)weights[c]=molecularWeight(c);
        return weights;
    }
    public double saturationPressure(double t) {
        return t>=647.096?Double.POSITIVE_INFINITY:V3WaterProperties.saturationPressurePascal(water,t);
    }
    /** The saturation pressure of a prepared node temperature, evaluated once for that temperature. */
    public double saturationPressure(Prepared prepared) { return own(prepared).saturationPressure(); }
    public double vaporWaterEnthalpy(double t) { return V3WaterProperties.vaporMolarEnthalpy(water,t); }
    public double maximumVelocityMetresPerSecond(){return maximumVelocity;}
    /** A hydraulic rate bound only; temperature and phase allocation still come from the EOS/energy equations. */
    public double velocityLimit(State state){return Math.min(maximumVelocity,isothermalAcousticBound(state));}
    /** Frozen-phase isothermal acoustic bound used by the configured hydraulic velocity clamp. */
    public double isothermalAcousticBound(State state) {
        double compliance=(state.liquidVolume()+state.waterVolume())*liquidResponse.compressibilityPerPascal();
        if(state.vaporVolume()>0) {
            double stiffness=state.waterPartialPressure();
            if(state.vaporProperties()!=null){var gas=state.vaporProperties();stiffness+=-gas.molarVolume()/gas.volumePressureDerivative();}
            compliance+=state.vaporVolume()/stiffness;
        }
        double speed=state.volume()/Math.sqrt(state.mass()*compliance);
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid acoustic flow-domain bound");
        return speed;
    }
    public static double waterVaporPressureLimit(double t) {
        if(t>=900)return 2e6;if(t>=750)return 1.5e6;if(t>=600)return .8e6;
        if(t>=500)return .4e6;if(t>=450)return .25e6;if(t>=400)return .15e6;return .125e6;
    }
    private GlobalLiquidResponse.State waterLiquidRaw(double t,double p,Prepared prepared) {
        var ref=prepared==null?WaterRegion1.evaluate(water,t,HydrocarbonModel.REFERENCE_PRESSURE):prepared.referenceWater();
        return liquidResponse.evaluate(t,p,HydrocarbonModel.REFERENCE_PRESSURE,
                ref.specificVolume()*waterMolecularWeight,ref.volumeTemperatureDerivative()*waterMolecularWeight,
                ref.volumeSecondTemperatureDerivative()*waterMolecularWeight,ref.specificEnthalpy()*waterMolecularWeight,
                ref.specificHeatCapacity()*waterMolecularWeight);
    }
    public WaterLiquid waterLiquid(double t,double p) { return waterLiquid(t,p,null); }
    /** The Region 1 reference state is a function of temperature alone, so a prepared temperature
     * answers every pressure and composition trial at that temperature without re-evaluating it. */
    public WaterLiquid waterLiquid(double t,double p,Prepared prepared) {
        var value=waterLiquidRaw(t,p,match(prepared,t));
        return new WaterLiquid(value.molarVolume(),value.molarEnthalpy()+waterEnthalpyOffset);
    }

    /** Direct properties of specified phase amounts, without an inner flash. */
    public State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {
        return state(t,p,liquid.clone(),vapor.clone(),waterLiquid,waterVapor,hydrocarbonPressure,null,null);
    }
    public State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,TranslatedPengRobinson.Workspace terms) {
        return state(t,p,liquid.clone(),vapor.clone(),waterLiquid,waterVapor,hydrocarbonPressure,terms,null);
    }
    /** Every temperature-only property comes from the prepared bundle; a standalone caller passes
     * {@code null} and each one is computed here, exactly as the mixing terms already were. */
    public State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,Prepared prepared) {
        return state(t,p,liquid.clone(),vapor.clone(),waterLiquid,waterVapor,hydrocarbonPressure,null,match(prepared,t));
    }
    /** The returned state adopts {@code liquid} and {@code vapor} instead of copying them: for a
     * caller that allocated them for this call and never reads or writes them again. */
    public State adoptingState(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,Prepared prepared) {
        return state(t,p,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure,null,match(prepared,t));
    }
    private State state(double t,double p,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,
                        TranslatedPengRobinson.Workspace terms,Prepared prepared) {
        if(SolverDiagnostics.ENABLED) {
            SolverDiagnostics.stateCalls.increment();
            if(SolverDiagnostics.inJacobian())SolverDiagnostics.stateCallsInJacobian.increment();
        }
        int n=hydrocarbon.componentCount();
        if(liquid.length!=n||vapor.length!=n||!Double.isFinite(t)||!Double.isFinite(p)||t<273.16||t>600||p<100||p>2e6)throw new IllegalArgumentException("Fluid state outside domain");
        double nl=sum(liquid),nv=sum(vapor),volume=0,h=0,mass=0,gasVolume=0,vl=0,vw=0;
        if(terms==null&&(nl>0||nv>0))terms=prepared==null?hydrocarbon.prepare(t):prepared.pengRobinson();
        HydrocarbonModel.Phase lp=null,vp=null;
        if(nl>0) {lp=hydrocarbon.phase(t,p,liquid,PhaseRoot.LIQUID,terms);vl=nl*lp.molarVolume();volume+=vl;h+=nl*lp.molarEnthalpy();}
        if(nv>0) {vp=hydrocarbon.phase(t,hydrocarbonPressure,vapor,PhaseRoot.VAPOR,terms);gasVolume=nv*vp.molarVolume();h+=nv*vp.molarEnthalpy();}
        if(waterLiquid<0||waterVapor<0||!Double.isFinite(waterLiquid+waterVapor))throw new IllegalArgumentException("Invalid water split");
        if(waterLiquid>0) {var w=waterLiquid(t,p,prepared);vw=waterLiquid*w.molarVolume;volume+=vw;h+=waterLiquid*w.molarEnthalpy;}
        if(waterVapor>0) {if(nv==0)gasVolume=waterVapor*R*t/p;h+=waterVapor*(prepared==null?vaporWaterEnthalpy(t):prepared.vaporEnthalpy());}
        double pw=waterVapor>0?waterVapor*R*t/gasVolume:0;
        if(pw>waterVaporPressureLimit(t))throw new IllegalArgumentException("Water-vapor approximation outside qualified partial-pressure range");
        volume+=gasVolume;
        for(int i=0;i<n;i++)mass+=(liquid[i]+vapor[i])*hydrocarbon.molecularWeight(i);
        mass+=(waterLiquid+waterVapor)*waterMolecularWeight;
        if(!(volume>0)||!(mass>0)||!Double.isFinite(h))throw new IllegalArgumentException("Empty/nonfinite fluid properties");
        return new State(t,p,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure,pw,volume,h,h-p*volume,mass,
                vl,vw,gasVolume,lp,vp);
    }

    /** Initializer/boundary flash only. The time-step solver must not call this in residual evaluation. */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint) {
        SolverDiagnostics.count(SolverDiagnostics.flashCalls);
        int n=hydrocarbon.componentCount();if(overall.length!=n+1)throw new IllegalArgumentException("Fluid basis mismatch");
        double[] hc=Arrays.copyOf(overall,n);double nh=sum(hc),w=overall[n];
        if(!Double.isFinite(w)||w<0||nh+w<=0)throw new IllegalArgumentException("Empty fluid initialization");
        if(nh==0)return state(t,p,hc,hc,p>=saturationPressure(t)?w:0,p>=saturationPressure(t)?0:w,0);
        if(w==0) {var terms=hydrocarbon.prepare(t);var split=splitHydrocarbon(t,p,p,hc,checkpoint,terms);return state(t,p,split[0],split[1],0,0,p,terms);}
        double ps=saturationPressure(t);
        TranslatedPengRobinson.Workspace terms=null;
        if(p>ps+1e-6) {
            terms=hydrocarbon.prepare(t);
            double pc=p-ps;var split=splitHydrocarbon(t,p,pc,hc,checkpoint,terms);
            double nv=sum(split[1]);double vg=nv>0?nv*hydrocarbon.phase(t,pc,split[1],PhaseRoot.VAPOR,terms).molarVolume():0;
            double required=ps*vg/(R*t);
            if(required<=w)return state(t,p,split[0],split[1],w-required,required,pc,terms);
        }
        double low=1e-6,high=p;double[][] split=null;double pc=high;
        for(int iteration=0;iteration<70;iteration++) {
            checkpoint.run();pc=(low+high)*.5;if(terms==null)terms=hydrocarbon.prepare(t);split=splitHydrocarbon(t,p,pc,hc,checkpoint,terms);
            double nv=sum(split[1]);double vg=nv>0?nv*hydrocarbon.phase(t,pc,split[1],PhaseRoot.VAPOR,terms).molarVolume():0;
            double residual=vg>0?pc+w*R*t/vg-p:Double.POSITIVE_INFINITY;
            if(Math.abs(residual)<1e-8*p)break;
            if(residual>0)high=pc;else low=pc;
        }
        var result=state(t,p,split[0],split[1],0,w,pc,terms);
        if(Math.abs(pc+result.waterPartialPressure-p)>1e-6*p)throw new IllegalArgumentException("Hybrid TP initialization did not converge");
        return result;
    }

    private double[][] splitHydrocarbon(double t,double liquidPressure,double vaporPressure,double[] amounts,Runnable checkpoint,TranslatedPengRobinson.Workspace terms) {
        int n=amounts.length;double total=sum(amounts);double[] z=amounts.clone(),k=new double[n],x=new double[n],y=new double[n];
        for(int i=0;i<n;i++) {z[i]/=total;var c=hydrocarbon.propertyPackage().properties().get(i).pr();
            k[i]=Math.exp(Math.clamp(Math.log(c.criticalPressure()/vaporPressure)+5.373*(1+c.acentricFactor())*(1-c.criticalTemperature()/t),-600,600));}
        double beta=0;
        for(int iteration=0;iteration<200;iteration++) {
            checkpoint.run();double f0=0,f1=0;for(int i=0;i<n;i++){f0+=z[i]*(k[i]-1);f1+=z[i]*(1-1/k[i]);}
            if(f0<=0)beta=0;else if(f1>=0)beta=1;else {
                double lo=0,hi=1;
                for(int j=0;j<70;j++){double mid=(lo+hi)/2,f=0;for(int i=0;i<n;i++)f+=z[i]*(k[i]-1)/((1-mid)+mid*k[i]);if(f>0)lo=mid;else hi=mid;}
                beta=(lo+hi)/2;
            }
            double xs=0,ys=0;
            for(int i=0;i<n;i++){x[i]=z[i]/((1-beta)+beta*k[i]);y[i]=k[i]*x[i];xs+=x[i];ys+=y[i];}
            for(int i=0;i<n;i++){x[i]/=xs;y[i]/=ys;}
            var pl=hydrocarbon.phase(t,liquidPressure,x,PhaseRoot.LIQUID,terms);
            var pv=hydrocarbon.phase(t,vaporPressure,y,PhaseRoot.VAPOR,terms);
            double error=0;double[] fl=pl.logFugacityView(),fv=pv.logFugacityView();
            for(int i=0;i<n;i++) {double target=fl[i]-fv[i]+Math.log(liquidPressure/vaporPressure);
                if(!Double.isFinite(target)||Math.abs(target)>600)throw new IllegalArgumentException("Equilibrium ratio outside numerical range");
                if(z[i]>0)error=Math.max(error,Math.abs(target-Math.log(k[i])));k[i]=Math.exp(.5*Math.log(k[i])+.5*target);}
            if(error<1e-8) {
                if(!pv.vaporBranch())return new double[][] {amounts.clone(),new double[n]};
                double[] l=new double[n],v=new double[n];
                // Allocate each component by its phase ratio, so no negative-inventory clamp is needed.
                for(int i=0;i<n;i++) {
                    double denominator=(1-beta)+beta*k[i];
                    if(beta*k[i]<1-beta){v[i]=amounts[i]*beta*k[i]/denominator;l[i]=amounts[i]-v[i];}
                    else{l[i]=amounts[i]*(1-beta)/denominator;v[i]=amounts[i]-l[i];}
                }
                return new double[][] {l,v};
            }
        }
        throw new IllegalArgumentException("Hydrocarbon TP initialization did not converge");
    }
    private static double sum(double[] values) {double total=0;for(double value:values){if(value<0||!Double.isFinite(value))throw new IllegalArgumentException("Invalid phase amount");total+=value;}return total;}

    /** The temperature-only property bundle for one exact node temperature. */
    public Prepared prepare(double temperature) { return new Prepared(this,temperature); }

    /** Caller-owned hydrocarbon derivative storage for an analytic node Jacobian: one per solve, not per node. */
    public TranslatedPengRobinson.Derivatives newHydrocarbonDerivatives(){return hydrocarbon.newDerivatives();}
    /**
     * Every first derivative of one hydrocarbon phase at a prepared node temperature, filled into
     * {@code output} without allocating: {@code d ln phi_i/dn_j}, {@code d ln phi_i/dT}, {@code d ln phi_i/dP},
     * the partial molar enthalpies and residual enthalpies, {@code dH^R/dT}, and the volumetric block.
     *
     * <p>The bundle describes the equation of state at the pressure given. {@link HydrocarbonModel#phase}
     * passes a vapor its own pressure, but evaluates a liquid at the 2 MPa reference and then corrects it
     * with {@link GlobalLiquidResponse}; a caller assembling a liquid node block must differentiate that
     * correction itself. {@link #state} composes the phases, the free water and the ideal water vapor on top
     * of this, and their derivatives are the caller's too - this exposes the equation of state, not the node.</p>
     */
    public void hydrocarbonDerivatives(double t,double p,double[] amounts,PhaseRoot root,Prepared prepared,
                                       TranslatedPengRobinson.Derivatives output) {
        hydrocarbon.differentiate(t,p,amounts,root,match(prepared,t).pengRobinson(),output);
    }
    private Prepared own(Prepared prepared) {
        if(prepared.owner!=this)throw new IllegalArgumentException("Prepared temperature belongs to another model");
        return prepared;
    }
    private Prepared match(Prepared prepared,double t) {
        if(prepared!=null&&own(prepared).temperature!=t)throw new IllegalArgumentException("Prepared temperature belongs to another state");
        return prepared;
    }
    /**
     * Everything at one exact temperature that neither composition nor pressure can change: the PR
     * mixing terms, the reference-pressure IF97 Region 1 water state, the water saturation pressure
     * and the ideal-gas water-vapor enthalpy.
     *
     * <p>A colored Jacobian perturbs a node's composition and pressure columns dozens of times at an
     * unchanged temperature, and every residual at that temperature repeats them, so this is where
     * those evaluations meet. Each member is computed when it is first asked for, because a trial
     * point can be outside the domain of a property it does not use - free water at a temperature
     * whose saturation pressure is above the Region 1 reference pressure, for instance - and must
     * fail on the property it does use, exactly as it did before.</p>
     */
    public static final class Prepared {
        private final FluidThermodynamics owner;
        private final double temperature;
        private TranslatedPengRobinson.Workspace terms;
        private WaterRegion1.State referenceWater;
        private MixtureViscosity.Prepared transport;
        private double saturationPressure=Double.NaN,vaporEnthalpy=Double.NaN;
        private Prepared(FluidThermodynamics owner,double temperature) {
            if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid prepared temperature");
            this.owner=owner;this.temperature=temperature;
        }
        public double temperature() { return temperature; }
        /** The prepared Peng-Robinson workspace of this temperature. It is mutable scratch, so a
         * {@code Prepared} - like the node cache that holds it - belongs to one solving thread. */
        TranslatedPengRobinson.Workspace pengRobinson() {
            return terms==null?terms=owner.hydrocarbon.prepare(temperature):terms;
        }
        WaterRegion1.State referenceWater() {
            return referenceWater==null?referenceWater=WaterRegion1.evaluate(owner.water,temperature,HydrocarbonModel.REFERENCE_PRESSURE):referenceWater;
        }
        double saturationPressure() {
            return Double.isNaN(saturationPressure)?saturationPressure=owner.saturationPressure(temperature):saturationPressure;
        }
        double vaporEnthalpy() {
            return Double.isNaN(vaporEnthalpy)?vaporEnthalpy=owner.vaporWaterEnthalpy(temperature):vaporEnthalpy;
        }
        /** The pure-component transport terms of this temperature, for the decode's Transport row. */
        public MixtureViscosity.Prepared viscosities() {
            return transport==null?transport=owner.viscosity.prepare(temperature):transport;
        }
    }
    public record WaterLiquid(double molarVolume,double molarEnthalpy) {}
    /**
     * The phase amounts belong to the record from construction on. {@link #state} is the only
     * place one is built: it copies a caller's arrays, and {@link #adoptingState} is the entry for
     * a caller that allocated them for this call alone, so a state still never shares an array with
     * anything a caller can reach.
     */
    public record State(double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,
                        double hydrocarbonPartialPressure,double waterPartialPressure,double volume,double enthalpy,double internalEnergy,
                        double mass,double liquidVolume,double waterVolume,double vaporVolume,HydrocarbonModel.Phase liquidProperties,
                        HydrocarbonModel.Phase vaporProperties) {
        /** Conserved component basis length, with water in the final position. */
        public int componentCount(){return liquid.length+1;}
        @Override public double[] liquid(){return liquid.clone();}
        @Override public double[] vapor(){return vapor.clone();}
        /** The amounts themselves, for the solver packages. The caller must not mutate them. */
        public double[] liquidView(){return liquid;}
        public double[] vaporView(){return vapor;}
    }
}
