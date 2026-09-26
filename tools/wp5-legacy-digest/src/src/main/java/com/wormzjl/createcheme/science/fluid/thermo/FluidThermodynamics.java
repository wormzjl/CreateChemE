package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.TraceTruncationPolicy;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import java.util.Arrays;
import java.util.Objects;

/** Immutable fluid property snapshot and standalone TP initializer; coupled steps consume phase() directly. */
public final class FluidThermodynamics {
    public static final double R=8.31446261815324;
    public static final double DEFAULT_MAXIMUM_VELOCITY=100;
    /**
     * The network's ceiling on the trace cutoff, a thousandth of the column's. The column verifies a
     * truncated flash against an unmasked reference and declares explicit error budgets, so it may
     * ask for a percent; the network has no reference to answer to inside a Newton pass, and the
     * energy it can misplace by folding an omitted phase into the retained one is bounded by
     * {@code sum_omitted y_i n_V |h_i^V - h_i^L|}, i.e. by the cutoff times the phase amount times
     * the largest enthalpy of vaporization on the basis. At 1e-5 that is still inside the interval
     * controller's own energy gate; above it, it is not.
     */
    public static final double MAX_TRACE_CUTOFF_MOLE_FRACTION=1e-5;
    /** The gameplay default for a network model built without an explicit cutoff, and the default of
     * the {@code fluidTraceCutoffMoleFraction} config entry that supplies one: ten times inside the
     * ceiling. 0 is the exact off switch, the identical numerical path rather than a small cutoff. */
    public static final double DEFAULT_TRACE_CUTOFF_MOLE_FRACTION=1e-6;
    public final HydrocarbonModel hydrocarbon;
    public final com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solidSettings;
    public final MixtureViscosity viscosity;
    public final com.wormzjl.createcheme.science.material.SolidMaterialCatalog solids;
    public final double waterMolecularWeight;
    /** Resolved once from the catalog and package this model was built for, instead of through the
     * calculation context on every water evaluation: the context costs a {@code Context} allocation
     * and a {@code ThreadLocal} set/remove per call, and a coefficient read inside costs a
     * {@code ThreadLocal} lookup and two immutable-map probes. */
    private final MaterialCatalog.Water water;
    private final double waterEnthalpyOffset;
    private final double maximumVelocity;
    /** Carried on the model, like {@link #maximumVelocity}, because every retained solver, workspace
     * and factorization is keyed on the model: replacing it is what makes a changed cutoff reach a
     * running island deterministically, and {@code ApproximationAnchor.revision} carries it so a
     * saved fallback anchor built under a different unknown set cannot be reused. */
    private final TraceTruncationPolicy tracePolicy;

    /**
     * The canonical gameplay basis, which includes nitrogen. {@code packageId} is resolved through
     * {@link FluidMaterialCatalog#resolveNetworkPackage} - it is the registered network package, or a
     * pre-registration saved id that migrates to it - and a package without nitrogen is refused
     * rather than silently run on a shorter basis. Direct construction retains an explicitly supplied
     * test/science basis.
     *
     * <p>No overload takes a single number after the package: the retired liquid-compressibility argument sat
     * there, and a {@code (catalog, package, maximumVelocity)} form would silently give an old call a velocity
     * limit of 1e-9 m/s. The velocity limit comes with the trace cutoff instead.</p>
     */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId) {
        return forNetwork(catalog,packageId,DEFAULT_MAXIMUM_VELOCITY,DEFAULT_TRACE_CUTOFF_MOLE_FRACTION);
    }
    /**
     * The retired signature with the global liquid compressibility, whose argument is ignored: liquids are evaluated
     * at the state pressure by their own equation of state. It stays only so call sites outside the direct-liquid
     * work package's files keep compiling until their owners drop the argument; remove it with them.
     */
    @Deprecated
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double ignoredLiquidCompressibility) {
        return forNetwork(catalog,packageId);
    }
    /** {@code traceCutoffMoleFraction} is the configured network cutoff: 0 is the exact off switch,
     * and the ceiling is {@link #MAX_TRACE_CUTOFF_MOLE_FRACTION}. */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,
                                                 double maximumVelocity,double traceCutoffMoleFraction) {
        return forNetwork(catalog,packageId,maximumVelocity,traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings.defaults());
    }
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solidSettings) {
        TraceTruncationPolicy.requireCutoff(traceCutoffMoleFraction,MAX_TRACE_CUTOFF_MOLE_FRACTION);
        return new FluidThermodynamics(catalog,FluidMaterialCatalog.resolveNetworkPackage(catalog,packageId),
                maximumVelocity,TraceTruncationPolicy.of(traceCutoffMoleFraction),solidSettings);
    }

    /** Placement initializer only. Persistence must restore inventory instead of calling this again. */
    public State initialNitrogenCharge(double volume,double temperature,double pressure,Runnable checkpoint) {
        if(!Double.isFinite(volume)||volume<=0)throw new IllegalArgumentException("Positive finite vessel volume required");
        int nitrogen=hydrocarbon.components().indexOf(FluidMaterialCatalog.NITROGEN);if(nitrogen<0)throw new IllegalStateException("Network basis has no nitrogen");
        double[] n=new double[hydrocarbon.componentCount()+1];n[nitrogen]=1;
        var unit=flashTP(temperature,pressure,n,checkpoint);n[nitrogen]=volume/unit.volume();
        return flashTP(temperature,pressure,n,checkpoint);
    }

    public FluidThermodynamics(MaterialCatalog catalog,String packageId) {
        this(catalog,packageId,DEFAULT_MAXIMUM_VELOCITY,TraceTruncationPolicy.OFF);
    }
    /** The retired signature with the global liquid compressibility; see {@link #forNetwork(MaterialCatalog, String, double)}. */
    @Deprecated
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double ignoredLiquidCompressibility) {
        this(catalog,packageId);
    }
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double maximumVelocity,
                               TraceTruncationPolicy tracePolicy) {
        this(catalog,packageId,maximumVelocity,tracePolicy,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings.defaults());
    }
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double maximumVelocity,TraceTruncationPolicy tracePolicy,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solidSettings) {
        this.solidSettings=Objects.requireNonNull(solidSettings);
        if(!Double.isFinite(maximumVelocity)||maximumVelocity<=0)throw new IllegalArgumentException("Positive finite maximum velocity required");
        TraceTruncationPolicy.requireCutoff(Objects.requireNonNull(tracePolicy,"tracePolicy").cutoffMoleFraction(),MAX_TRACE_CUTOFF_MOLE_FRACTION);
        this.tracePolicy=tracePolicy;
        this.maximumVelocity=maximumVelocity;
        hydrocarbon=new HydrocarbonModel(catalog,packageId);
        viscosity=new MixtureViscosity(catalog,packageId);solids=catalog.solids();
        waterMolecularWeight=catalog.requirePackage(packageId).water().molarMass();
        // The same resolution the context form performs, including its fallback package.
        water=com.wormzjl.createcheme.science.material.MaterialRuntime.with(catalog,packageId,
                com.wormzjl.createcheme.science.material.MaterialRuntime::water);
        domain=hydrocarbon.domain();
        // Region 1 at 298.15 K and 1 atm itself, the stable liquid: the network's water datum and the pump's density.
        var reference=WaterRegion1.evaluate(domain.packageId(),water,298.15,101325);
        waterEnthalpyOffset=V3WaterProperties.liquidMolarEnthalpy(water,298.15)-reference.specificEnthalpy()*waterMolecularWeight;
        pumpReferenceDensity=waterMolecularWeight/(reference.specificVolume()*waterMolecularWeight);
    }
    /** Where this model may evaluate a state: the package envelope and every component's range, from the package's data. */
    public FluidDomain domain(){return domain;}
    private final FluidDomain domain;
    /**
     * The density a pump's "maximum pressure rise" is stated for: liquid water at 298.15 K and 101,325 Pa, from this
     * model's own water (IF97 Region 1 at that state), 997.05 kg/m3 (NIST: 997.0). A
     * pump's rise limit on another fluid is the setting scaled by the density of
     * what it withdraws over this one: a head, so a pump that pushes water 500 kPa pushes nitrogen at 1 atm about
     * 575 Pa. See {@code PassiveStepSolver.riseLimit}.
     */
    public double pumpReferenceDensity(){return pumpReferenceDensity;}
    private final double pumpReferenceDensity;
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
    /** Below the water model's triple point there is no liquid-vapour saturation (ice is not modelled): a
     * thermo-domain violation naming water, before the column's correlation is reached. */
    public double saturationPressure(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        if(t<water.triplePoint())domain.checkWaterTemperature(t);
        return t>=water.criticalTemperature()?Double.POSITIVE_INFINITY:V3WaterProperties.saturationPressurePascal(water,t);
    }
    /** The saturation pressure of a prepared node temperature, evaluated once for that temperature. */
    public double saturationPressure(Prepared prepared) { return own(prepared).saturationPressure(); }
    public double vaporWaterEnthalpy(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Non-finite water temperature");
        domain.checkWaterTemperature(t);
        return V3WaterProperties.vaporMolarEnthalpy(water,t);
    }
    public double maximumVelocityMetresPerSecond(){return maximumVelocity;}
    /** The per-node phase support the step solver freezes its Newton unknowns with. */
    public TraceTruncationPolicy traceTruncation(){return tracePolicy;}
    /** A hydraulic rate bound only; temperature and phase allocation still come from the EOS/energy equations. */
    public double velocityLimit(State state){return Math.min(maximumVelocity,isothermalAcousticBound(state));}
    /**
     * Frozen-phase isothermal acoustic bound used by the configured hydraulic velocity clamp: the node's compliance is
     * {@code sum_phases V_phase kappa_phase}, the hydrocarbon liquid's and the free water's own isothermal
     * compressibility at the state (the equation of state's and Region 1's), and the gas's.
     */
    public double isothermalAcousticBound(State state) {
        double compliance=state.waterVolume()*state.waterCompressibility();
        if(state.liquidVolume()>0)compliance+=state.liquidVolume()*state.liquidProperties().isothermalCompressibility();
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
    /**
     * Liquid water at the state pressure, into the prepared temperature's Region 1 workspace (a fresh one for a
     * standalone call): the stable liquid at or above the saturation pressure, the metastable liquid below it within
     * {@link WaterRegion1#METASTABLE_MARGIN_PASCAL}, flagged. A temperature outside Region 1 is refused before the
     * saturation pressure is evaluated, naming water.
     */
    private WaterRegion1.Workspace waterAt(double t,double p,Prepared prepared) {
        WaterRegion1.requireTemperature(domain.packageId(),water,t,p);
        var workspace=prepared==null?new WaterRegion1.Workspace():prepared.water();
        double saturation=prepared==null?saturationPressure(t):prepared.saturationPressure();
        WaterRegion1.evaluateLiquid(domain.packageId(),water,t,p,saturation,workspace);
        return workspace;
    }
    public WaterLiquid waterLiquid(double t,double p) { return waterLiquid(t,p,null); }
    /** The Region 1 temperature terms and the saturation pressure are functions of temperature alone, so a prepared
     * temperature keeps them for every pressure trial at that temperature; the pressure terms are evaluated per call. */
    public WaterLiquid waterLiquid(double t,double p,Prepared prepared) {
        var w=waterAt(t,p,match(prepared,t));
        return new WaterLiquid(w.specificVolume()*waterMolecularWeight,w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset,
                w.volumePressureDerivative()*waterMolecularWeight,w.metastable());
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
        if(liquid.length!=n||vapor.length!=n)throw new IllegalArgumentException("Fluid state basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p)||!Double.isFinite(waterLiquid)||!Double.isFinite(waterVapor))throw new IllegalArgumentException("Fluid state is not finite");
        // The package's domain, from its data: a ThermoDomainViolation names the carried component whose range the
        // state leaves, or the package envelope. Malformed input above stays a plain IllegalArgumentException.
        domain.check(t,p,liquid,vapor,waterLiquid+waterVapor);
        double nl=sum(liquid),nv=sum(vapor),volume=0,h=0,mass=0,gasVolume=0,vl=0,vw=0;
        if(terms==null&&(nl>0||nv>0))terms=prepared==null?hydrocarbon.prepare(t):prepared.pengRobinson();
        HydrocarbonModel.Phase lp=null,vp=null;
        // The liquid at the state pressure; with a vapour beside it, a liquid slot on a single vapour-like root is refused.
        if(nl>0) {lp=hydrocarbon.phase(t,p,liquid,PhaseRoot.LIQUID,terms,nv>0);vl=nl*lp.molarVolume();volume+=vl;h+=nl*lp.molarEnthalpy();}
        if(nv>0) {vp=hydrocarbon.phase(t,hydrocarbonPressure,vapor,PhaseRoot.VAPOR,terms);gasVolume=nv*vp.molarVolume();h+=nv*vp.molarEnthalpy();}
        if(waterLiquid<0||waterVapor<0||!Double.isFinite(waterLiquid+waterVapor))throw new IllegalArgumentException("Invalid water split");
        double kw=0;
        if(waterLiquid>0) {
            var w=waterAt(t,p,prepared);double molarVolume=w.specificVolume()*waterMolecularWeight;
            vw=waterLiquid*molarVolume;volume+=vw;h+=waterLiquid*(w.specificEnthalpy()*waterMolecularWeight+waterEnthalpyOffset);
            kw=-w.volumePressureDerivative()/w.specificVolume();
        }
        if(waterVapor>0) {if(nv==0)gasVolume=waterVapor*R*t/p;h+=waterVapor*(prepared==null?vaporWaterEnthalpy(t):prepared.vaporEnthalpy());}
        double pw=waterVapor>0?waterVapor*R*t/gasVolume:0;
        if(pw>waterVaporPressureLimit(t))throw new IllegalArgumentException("Water-vapor approximation outside qualified partial-pressure range");
        volume+=gasVolume;
        for(int i=0;i<n;i++)mass+=(liquid[i]+vapor[i])*hydrocarbon.molecularWeight(i);
        mass+=(waterLiquid+waterVapor)*waterMolecularWeight;
        if(!(volume>0)||!(mass>0)||!Double.isFinite(h))throw new IllegalArgumentException("Empty/nonfinite fluid properties");
        return new State(t,p,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure,pw,volume,h,h-p*volume,mass,
                vl,vw,gasVolume,lp,vp,SolidInventory.EMPTY,SolidInventory.Moments.ZERO,kw);
    }

    /** Stationary dry inventory. Pressure is retained as an initialization hint, not a gas pressure. */
    public State solidState(double temperature,double pressure,com.wormzjl.createcheme.science.fluid.state.SolidInventory solids) {
        if(solids.empty()||!Double.isFinite(temperature)||!Double.isFinite(pressure))throw new IllegalArgumentException("Dry solid state needs solids and a finite temperature and pressure");
        domain.checkEnvelope(temperature,pressure);
        var moments=solids.moments();var empty=new double[hydrocarbon.componentCount()];
        return new State(temperature,pressure,empty,empty,0,0,0,0,moments.volume(),
                moments.enthalpy(temperature,pressure),moments.internalEnergy(temperature),moments.mass(),0,0,0,null,null,solids,moments,0);
    }

    /** Initializer/boundary flash only. The time-step solver must not call this in residual evaluation. */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint) {
        SolverDiagnostics.count(SolverDiagnostics.flashCalls);
        int n=hydrocarbon.componentCount();if(overall.length!=n+1)throw new IllegalArgumentException("Fluid basis mismatch");
        double[] hc=Arrays.copyOf(overall,n);double nh=sum(hc),w=overall[n];
        if(!Double.isFinite(w)||w<0||nh+w<=0)throw new IllegalArgumentException("Empty fluid initialization");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid initialization is not finite");
        // Refused before any equilibrium ratio is formed: outside a component's range the flash's numbers mean nothing,
        // and the violation says which component, not which intermediate overflowed.
        domain.checkTotals(t,p,overall);
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
                // A component the mixture does not hold takes no part in the split (its x and y are zero whatever its
                // ratio), so its ratio is only kept finite: at cryogenic temperatures the heaviest absent fractions'
                // ratios pass e^600 in liquid nitrogen, and a pure nitrogen flash at 63 K used to be refused for them.
                // A ratio inside the range is updated exactly as before.
                if(z[i]==0){if(Double.isFinite(target))k[i]=Math.exp(.5*Math.log(k[i])+.5*Math.clamp(target,-600,600));continue;}
                if(!Double.isFinite(target)||Math.abs(target)>600)throw new IllegalArgumentException("Equilibrium ratio outside numerical range");
                if(z[i]>0)error=Math.max(error,Math.abs(target-Math.log(k[i])));k[i]=Math.exp(.5*Math.log(k[i])+.5*target);}
            if(error<1e-8) {
                if(!pv.vaporBranch())return new double[][] {amounts.clone(),new double[n]};
                // At the state pressure the liquid trial can have only a vapour-like root: both slots are then the same
                // kind of fluid, and the split is the vapour alone (a two-phase state would refuse such a liquid).
                if(pl.liquidRootAbsent()&&beta>0)return new double[][] {new double[n],amounts.clone()};
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
     * <p>The bundle describes the equation of state at the pressure given, which is the phase
     * {@link HydrocarbonModel#phase} evaluates: a liquid at the state pressure, a vapor at its own. {@link #state}
     * composes the phases, the free water and the ideal water vapor on top of this, and their derivatives are the
     * caller's - this exposes the equation of state, not the node.</p>
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
     * mixing terms, the temperature terms of IF97 Region 1 (in its workspace, which also evaluates the
     * pressure terms of each trial without allocating), the water saturation pressure and the ideal-gas
     * water-vapor enthalpy.
     *
     * <p>A colored Jacobian perturbs a node's composition and pressure columns dozens of times at an
     * unchanged temperature, and every residual at that temperature repeats them, so this is where
     * those evaluations meet. Each member is computed when it is first asked for, because a trial
     * point can be outside the domain of a property it does not use - free water above Region 1's
     * temperature boundary, for instance - and must fail on the property it does use.</p>
     */
    public static final class Prepared {
        private final FluidThermodynamics owner;
        private final double temperature;
        private TranslatedPengRobinson.Workspace terms;
        private WaterRegion1.Workspace water;
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
        /** This temperature's Region 1 workspace: the temperature terms once, the pressure terms per trial. */
        WaterRegion1.Workspace water() {
            return water==null?water=new WaterRegion1.Workspace():water;
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
    /** Liquid water at a state: molar volume, the network's molar enthalpy, {@code dv/dP} per mole, and whether the
     * pressure is below saturation (the metastable liquid {@link WaterRegion1#evaluateLiquid} admits). */
    public record WaterLiquid(double molarVolume,double molarEnthalpy,double volumePressureDerivative,boolean metastable) {}
    /**
     * The phase amounts belong to the record from construction on. {@link #state} is the only
     * place one is built: it copies a caller's arrays, and {@link #adoptingState} is the entry for
     * a caller that allocated them for this call alone, so a state still never shares an array with
     * anything a caller can reach. {@code waterCompressibility} is the free liquid water's own isothermal
     * compressibility at the state (Region 1's {@code -(dv/dP)/v}, 1/Pa), zero without free water.
     */
    public record State(double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,
                        double hydrocarbonPartialPressure,double waterPartialPressure,double volume,double enthalpy,double internalEnergy,
                        double mass,double liquidVolume,double waterVolume,double vaporVolume,HydrocarbonModel.Phase liquidProperties,
                        HydrocarbonModel.Phase vaporProperties, SolidInventory solids, SolidInventory.Moments solidMoments,
                        double waterCompressibility) {
        public State { Objects.requireNonNull(solids); Objects.requireNonNull(solidMoments); }
        public State withSolids(SolidInventory inventory) { return withSolidState(inventory,inventory.moments()); }
        /** Moment-only trial states never become owned inventories before population reconstruction. */
        public State withSolidState(SolidInventory inventory,SolidInventory.Moments moments) {
            return new State(temperature,pressure,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPartialPressure,waterPartialPressure,
                    volume-solidMoments.volume()+moments.volume(),
                    enthalpy-solidMoments.enthalpy(temperature,pressure)+moments.enthalpy(temperature,pressure),
                    internalEnergy-solidMoments.internalEnergy(temperature)+moments.internalEnergy(temperature),
                    mass-solidMoments.mass()+moments.mass(),liquidVolume,waterVolume,vaporVolume,liquidProperties,vaporProperties,inventory,moments,
                    waterCompressibility);
        }
        /** Conserved component basis length, with water in the final position. */
        public int componentCount(){return liquid.length+1;}
        @Override public double[] liquid(){return liquid.clone();}
        @Override public double[] vapor(){return vapor.clone();}
        /** The amounts themselves, for the solver packages. The caller must not mutate them. */
        public double[] liquidView(){return liquid;}
        public double[] vaporView(){return vapor;}
    }
}
