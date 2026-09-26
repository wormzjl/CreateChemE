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
    private final GlobalLiquidResponse liquidResponse;
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
     */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double liquidCompressibility) {
        return forNetwork(catalog,packageId,liquidCompressibility,DEFAULT_MAXIMUM_VELOCITY);
    }
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity) {
        return forNetwork(catalog,packageId,liquidCompressibility,maximumVelocity,DEFAULT_TRACE_CUTOFF_MOLE_FRACTION);
    }
    /** {@code traceCutoffMoleFraction} is the configured network cutoff: 0 is the exact off switch,
     * and the ceiling is {@link #MAX_TRACE_CUTOFF_MOLE_FRACTION}. */
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double liquidCompressibility,
                                                 double maximumVelocity,double traceCutoffMoleFraction) {
        return forNetwork(catalog,packageId,liquidCompressibility,maximumVelocity,traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings.defaults());
    }
    public static FluidThermodynamics forNetwork(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solidSettings) {
        TraceTruncationPolicy.requireCutoff(traceCutoffMoleFraction,MAX_TRACE_CUTOFF_MOLE_FRACTION);
        return new FluidThermodynamics(catalog,FluidMaterialCatalog.resolveNetworkPackage(catalog,packageId),liquidCompressibility,
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

    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double liquidCompressibility) {
        this(catalog,packageId,liquidCompressibility,DEFAULT_MAXIMUM_VELOCITY);
    }
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity) {
        this(catalog,packageId,liquidCompressibility,maximumVelocity,TraceTruncationPolicy.OFF);
    }
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity,
                               TraceTruncationPolicy tracePolicy) {
        this(catalog,packageId,liquidCompressibility,maximumVelocity,tracePolicy,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings.defaults());
    }
    public FluidThermodynamics(MaterialCatalog catalog,String packageId,double liquidCompressibility,double maximumVelocity,TraceTruncationPolicy tracePolicy,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solidSettings) {
        this.solidSettings=Objects.requireNonNull(solidSettings);
        if(!Double.isFinite(maximumVelocity)||maximumVelocity<=0)throw new IllegalArgumentException("Positive finite maximum velocity required");
        TraceTruncationPolicy.requireCutoff(Objects.requireNonNull(tracePolicy,"tracePolicy").cutoffMoleFraction(),MAX_TRACE_CUTOFF_MOLE_FRACTION);
        this.tracePolicy=tracePolicy;
        this.maximumVelocity=maximumVelocity;
        hydrocarbon=new HydrocarbonModel(catalog,packageId,liquidCompressibility);
        viscosity=new MixtureViscosity(catalog,packageId);solids=catalog.solids();
        waterMolecularWeight=catalog.requirePackage(packageId).water().molarMass();
        liquidResponse=new GlobalLiquidResponse(liquidCompressibility);
        // The same resolution the context form performs, including its fallback package.
        water=com.wormzjl.createcheme.science.material.MaterialRuntime.with(catalog,packageId,
                com.wormzjl.createcheme.science.material.MaterialRuntime::water);
        domain=hydrocarbon.domain();
        var reference=waterLiquidRaw(298.15,101325,null);
        waterEnthalpyOffset=V3WaterProperties.liquidMolarEnthalpy(water,298.15)-reference.molarEnthalpy();
        pumpReferenceDensity=waterMolecularWeight/reference.molarVolume();
    }
    /** Where this model may evaluate a state: the package envelope and every component's range, from the package's data. */
    public FluidDomain domain(){return domain;}
    private final FluidDomain domain;
    /**
     * The density a pump's "maximum pressure rise" is stated for: liquid water at 298.15 K and 101,325 Pa, from this
     * model's own water (IF97 Region 1 at the liquid reference pressure, carried to 1 atm by the global liquid
     * compressibility), 996.0 kg/m3 (NIST: 997.0; the global 1e-9 1/Pa is about twice water's own compressibility). A
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
    /**
     * The fluid a phase-selective vessel outlet draws, read off the vessel's own state with no flash and no extra
     * equation-of-state root: every term below is a sum over the phase amounts, volumes and molar properties
     * {@link #state} already computed (plan table 3.2 of documentation/2026-09-26-phase-ports-and-compressor).
     * <ul>
     * <li>The vapour stream is the hydrocarbon vapour and the water vapour: {@code moles} are {@code vapor} with the
     * water vapour in the last slot, its volume is the gas volume, its enthalpy {@code n_v h_v + n_wv h_wv(T)}, and it
     * carries no solid.</li>
     * <li>The condensed ("liquid") stream is the hydrocarbon liquid, the free water and every solid (decision A2: no
     * decant): {@code moles} are {@code liquid} with the free water last, its volume is the two liquid volumes plus the
     * solid volume, its enthalpy {@code n_l h_l + n_wl h_wl(T,P) + H_solids(T,P)}.</li>
     * </ul>
     * The two streams partition the state: their moles, masses, volumes and enthalpies add up to the state's own. The
     * velocity limit is {@link #isothermalAcousticBound}'s formula restricted to the stream's phases,
     * {@code V_s / sqrt(M_s * compliance_s)}, i.e. {@code sqrt(stiffness/rho_v)} for the vapour and
     * {@code sqrt(1/(kappa rho))} (solids incompressible) for the condensed stream, capped by the configured maximum as
     * {@link #velocityLimit} caps the bulk: a vapour drawn off a wet tank is limited by the vapour's own acoustic bound,
     * not by the far lower bound of the two-phase mixture. The viscosity is the stream's own terms of the volume
     * average the step solver states for the bulk (the slurry correction over the condensed stream's liquids).
     *
     * <p>A stream whose phase the state does not hold (no gas volume; no liquid and no free water) is not built: the
     * builders return {@code null}, and what an absent stream means is the reader's rule (the step solver's: the
     * bulk, with a zero velocity limit; decision A8).
     */
    public record PhaseStream(double[] moles,double mass,double volume,double viscosity,double specificEnthalpy,double velocityLimit,
                              SolidInventory.Moments solidMoments) {
        public PhaseStream {Objects.requireNonNull(moles);Objects.requireNonNull(solidMoments);}
        /** The moles themselves, for the solver packages. The caller must not mutate them. */
        public double[] molesView(){return moles;}
        @Override public double[] moles(){return moles.clone();}
    }
    /** Whether the state holds a gas phase to draw. */
    public static boolean holdsVapor(State state){return state.vaporVolume()>0;}
    /** Whether the state holds a hydrocarbon liquid or free water to draw (solids alone do not flow as a liquid). */
    public static boolean holdsLiquid(State state){return state.liquidVolume()+state.waterVolume()>0;}
    /** Mass of the vapour stream: hydrocarbon vapour then water vapour, summed in the order {@link #state} sums the whole. */
    public double vaporMass(State state) {
        double mass=0;var v=state.vaporView();
        for(int i=0;i<v.length;i++)mass+=v[i]*hydrocarbon.molecularWeight(i);
        return mass+state.waterVapor()*waterMolecularWeight;
    }
    /** Mass of the condensed stream: hydrocarbon liquid, free water, then every solid. */
    public double liquidMass(State state) {
        double mass=0;var l=state.liquidView();
        for(int i=0;i<l.length;i++)mass+=l[i]*hydrocarbon.molecularWeight(i);
        return mass+state.waterLiquid()*waterMolecularWeight+state.solidMoments().mass();
    }
    /** Volume of the condensed stream: the two liquid volumes and the solid volume. */
    public static double liquidStreamVolume(State state){return state.liquidVolume()+state.waterVolume()+state.solidMoments().volume();}
    /** The vapour stream's density, for a state that {@link #holdsVapor}. */
    public double vaporDensity(State state){return vaporMass(state)/state.vaporVolume();}
    /** The condensed stream's density, for a state that {@link #holdsLiquid}. */
    public double liquidDensity(State state){return liquidMass(state)/liquidStreamVolume(state);}
    /** The vapour stream's specific enthalpy (J/kg); {@code prepared} as for {@link #state}, or null. */
    public double vaporSpecificEnthalpy(State state,Prepared prepared) {
        double h=0;
        if(state.vaporProperties()!=null)h+=total(state.vaporView())*state.vaporProperties().molarEnthalpy();
        if(state.waterVapor()>0)h+=state.waterVapor()*(prepared==null?vaporWaterEnthalpy(state.temperature()):match(prepared,state.temperature()).vaporEnthalpy());
        return h/vaporMass(state);
    }
    /** The condensed stream's specific enthalpy (J/kg), solids included; {@code prepared} as for {@link #state}, or null. */
    public double liquidSpecificEnthalpy(State state,Prepared prepared) {
        double h=0,t=state.temperature(),p=state.pressure();
        if(state.liquidProperties()!=null)h+=total(state.liquidView())*state.liquidProperties().molarEnthalpy();
        if(state.waterLiquid()>0)h+=state.waterLiquid()*waterLiquid(t,p,prepared).molarEnthalpy;
        h+=state.solidMoments().enthalpy(t,p);
        return h/liquidMass(state);
    }
    /** The vapour stream's velocity limit: {@code min(maximum, V_v/sqrt(M_v V_v/stiffness))}. */
    public double vaporVelocityLimit(State state) {
        double stiffness=state.waterPartialPressure();
        if(state.vaporProperties()!=null){var gas=state.vaporProperties();stiffness+=-gas.molarVolume()/gas.volumePressureDerivative();}
        return Math.min(maximumVelocity,acoustic(state.vaporVolume(),vaporMass(state),state.vaporVolume()/stiffness));
    }
    /** The condensed stream's velocity limit: {@code min(maximum, V/sqrt(M (V_l+V_w) kappa))}, solids incompressible. */
    public double liquidVelocityLimit(State state) {
        return Math.min(maximumVelocity,acoustic(liquidStreamVolume(state),liquidMass(state),
                (state.liquidVolume()+state.waterVolume())*liquidResponse.compressibilityPerPascal()));
    }
    private static double acoustic(double volume,double mass,double compliance) {
        double speed=volume/Math.sqrt(mass*compliance);
        if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid acoustic flow-domain bound");
        return speed;
    }
    /** The vapour stream's viscosity: the vapour term of the solver's volume average, over the gas volume. */
    public double vaporViscosity(State state,MixtureViscosity.Workspace scratch,Prepared prepared) {
        var terms=prepared==null?null:match(prepared,state.temperature()).viscosities();
        return viscosity.vapor(state.temperature(),state.vaporView(),state.waterVapor(),scratch,terms);
    }
    /** The condensed stream's viscosity: the liquid and free-water terms of the solver's volume average, with the slurry
     * correction for the solids they carry, over the condensed stream's volume. */
    public double liquidViscosity(State state,Prepared prepared) {
        var terms=prepared==null?null:match(prepared,state.temperature()).viscosities();
        double value=0,t=state.temperature();
        if(state.liquidVolume()>0)value+=state.liquidVolume()*viscosity.liquid(t,state.liquidView(),terms).pascalSeconds();
        if(state.waterVolume()>0)value+=state.waterVolume()*viscosity.waterLiquid(t,terms);
        double liquidVolume=state.liquidVolume()+state.waterVolume(),solidVolume=state.solidMoments().volume();
        if(solidVolume>0){double phi=solidVolume/(liquidVolume+solidVolume);value=com.wormzjl.createcheme.science.fluid.transport.SlurryTransport.effectiveViscosity(value/liquidVolume,Math.min(phi,0.62-1e-9))*(liquidVolume+solidVolume);}
        return value/(liquidVolume+solidVolume);
    }
    /** The carrier viscosity of the condensed stream (the liquids' volume average, no slurry correction), which an inline
     * filter's cake resistance scales with. */
    public double liquidCarrierViscosity(State state) {
        double value=0,t=state.temperature();
        if(state.liquidVolume()>0)value+=state.liquidVolume()*viscosity.liquid(t,state.liquidView()).pascalSeconds();
        if(state.waterVolume()>0)value+=state.waterVolume()*viscosity.waterLiquid(t);
        return value/(state.liquidVolume()+state.waterVolume());
    }
    /** The vapour stream of {@code state}, or null when it holds no gas. */
    public PhaseStream vaporStream(State state,Prepared prepared,MixtureViscosity.Workspace scratch) {
        if(!holdsVapor(state))return null;
        var v=state.vaporView();double[] n=Arrays.copyOf(v,v.length+1);n[v.length]=state.waterVapor();
        return new PhaseStream(n,vaporMass(state),state.vaporVolume(),vaporViscosity(state,scratch,prepared),
                vaporSpecificEnthalpy(state,prepared),vaporVelocityLimit(state),SolidInventory.Moments.ZERO);
    }
    /** The condensed stream of {@code state}, or null when it holds no liquid and no free water. */
    public PhaseStream liquidStream(State state,Prepared prepared) {
        if(!holdsLiquid(state))return null;
        var l=state.liquidView();double[] n=Arrays.copyOf(l,l.length+1);n[l.length]=state.waterLiquid();
        return new PhaseStream(n,liquidMass(state),liquidStreamVolume(state),liquidViscosity(state,prepared),
                liquidSpecificEnthalpy(state,prepared),liquidVelocityLimit(state),state.solidMoments());
    }
    /** The amount sum {@link #state} forms ({@code sum}), without its validation: a state's own amounts are valid. */
    private static double total(double[] values){double total=0;for(double value:values)total+=value;return total;}
    public static double waterVaporPressureLimit(double t) {
        if(t>=900)return 2e6;if(t>=750)return 1.5e6;if(t>=600)return .8e6;
        if(t>=500)return .4e6;if(t>=450)return .25e6;if(t>=400)return .15e6;return .125e6;
    }
    private GlobalLiquidResponse.State waterLiquidRaw(double t,double p,Prepared prepared) {
        var ref=prepared==null?WaterRegion1.evaluate(domain.packageId(),water,t,HydrocarbonModel.REFERENCE_PRESSURE):prepared.referenceWater();
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
        if(liquid.length!=n||vapor.length!=n)throw new IllegalArgumentException("Fluid state basis mismatch");
        if(!Double.isFinite(t)||!Double.isFinite(p)||!Double.isFinite(waterLiquid)||!Double.isFinite(waterVapor))throw new IllegalArgumentException("Fluid state is not finite");
        // The package's domain, from its data: a ThermoDomainViolation names the carried component whose range the
        // state leaves, or the package envelope. Malformed input above stays a plain IllegalArgumentException.
        domain.check(t,p,liquid,vapor,waterLiquid+waterVapor);
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

    /** Stationary dry inventory. Pressure is retained as an initialization hint, not a gas pressure. */
    public State solidState(double temperature,double pressure,com.wormzjl.createcheme.science.fluid.state.SolidInventory solids) {
        if(solids.empty()||!Double.isFinite(temperature)||!Double.isFinite(pressure))throw new IllegalArgumentException("Dry solid state needs solids and a finite temperature and pressure");
        domain.checkEnvelope(temperature,pressure);
        var moments=solids.moments();var empty=new double[hydrocarbon.componentCount()];
        return new State(temperature,pressure,empty,empty,0,0,0,0,moments.volume(),
                moments.enthalpy(temperature,pressure),moments.internalEnergy(temperature),moments.mass(),0,0,0,null,null,solids,moments);
    }

    /** Initializer/boundary flash only. The time-step solver must not call this in residual evaluation. */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint) {
        return flashTP(t,p,overall,checkpoint,null);
    }
    /** {@link #flashTP} that uses {@code prepared}'s Peng-Robinson workspace when it is for exactly {@code t}, instead of
     * preparing a new one (the arithmetic is the same). The equilibrium iteration writes its log fugacity coefficients
     * into two buffers rather than building two phase records per iteration (review 8.7 (c) E1: the phase-check flashes
     * were half of a flowing island's allocation). */
    public State flashTP(double t,double p,double[] overall,Runnable checkpoint,Prepared prepared) {
        TranslatedPengRobinson.Workspace given=prepared!=null&&prepared.temperature()==t?prepared.pengRobinson():null;
        SolverDiagnostics.count(SolverDiagnostics.flashCalls);
        int n=hydrocarbon.componentCount();if(overall.length!=n+1)throw new IllegalArgumentException("Fluid basis mismatch");
        double[] hc=Arrays.copyOf(overall,n);double nh=sum(hc),w=overall[n];
        if(!Double.isFinite(w)||w<0||nh+w<=0)throw new IllegalArgumentException("Empty fluid initialization");
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Fluid initialization is not finite");
        // Refused before any equilibrium ratio is formed: outside a component's range the flash's numbers mean nothing,
        // and the violation says which component, not which intermediate overflowed.
        domain.checkTotals(t,p,overall);
        if(nh==0)return state(t,p,hc,hc,p>=saturationPressure(t)?w:0,p>=saturationPressure(t)?0:w,0);
        if(w==0) {var terms=given!=null?given:hydrocarbon.prepare(t);var split=splitHydrocarbon(t,p,p,hc,checkpoint,terms);return state(t,p,split[0],split[1],0,0,p,terms);}
        double ps=saturationPressure(t);
        TranslatedPengRobinson.Workspace terms=null;
        if(p>ps+1e-6) {
            terms=given!=null?given:hydrocarbon.prepare(t);
            double pc=p-ps;var split=splitHydrocarbon(t,p,pc,hc,checkpoint,terms);
            double nv=sum(split[1]);double vg=nv>0?nv*hydrocarbon.phase(t,pc,split[1],PhaseRoot.VAPOR,terms).molarVolume():0;
            double required=ps*vg/(R*t);
            if(required<=w)return state(t,p,split[0],split[1],w-required,required,pc,terms);
        }
        double low=1e-6,high=p;double[][] split=null;double pc=high;
        for(int iteration=0;iteration<70;iteration++) {
            checkpoint.run();pc=(low+high)*.5;if(terms==null)terms=given!=null?given:hydrocarbon.prepare(t);split=splitHydrocarbon(t,p,pc,hc,checkpoint,terms);
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
        double beta=0;double[] liquidLogPhi=new double[n],vaporLogPhi=new double[n];
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
            hydrocarbon.logFugacityInto(t,liquidPressure,x,PhaseRoot.LIQUID,terms,liquidLogPhi);
            boolean vaporBranch=hydrocarbon.logFugacityInto(t,vaporPressure,y,PhaseRoot.VAPOR,terms,vaporLogPhi);
            double[] fl=liquidLogPhi,fv=vaporLogPhi;
            double error=0;
            for(int i=0;i<n;i++) {double target=fl[i]-fv[i]+Math.log(liquidPressure/vaporPressure);
                // A component the mixture does not hold takes no part in the split (its x and y are zero whatever its
                // ratio), so its ratio is only kept finite: at cryogenic temperatures the heaviest absent fractions'
                // ratios pass e^600 in liquid nitrogen, and a pure nitrogen flash at 63 K used to be refused for them.
                // A ratio inside the range is updated exactly as before.
                if(z[i]==0){if(Double.isFinite(target))k[i]=Math.exp(.5*Math.log(k[i])+.5*Math.clamp(target,-600,600));continue;}
                if(!Double.isFinite(target)||Math.abs(target)>600)throw new IllegalArgumentException("Equilibrium ratio outside numerical range");
                if(z[i]>0)error=Math.max(error,Math.abs(target-Math.log(k[i])));k[i]=Math.exp(.5*Math.log(k[i])+.5*target);}
            if(error<1e-8) {
                if(!vaporBranch)return new double[][] {amounts.clone(),new double[n]};
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
            return referenceWater==null?referenceWater=WaterRegion1.evaluate(owner.domain.packageId(),owner.water,temperature,HydrocarbonModel.REFERENCE_PRESSURE):referenceWater;
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
                        HydrocarbonModel.Phase vaporProperties, SolidInventory solids, SolidInventory.Moments solidMoments) {
        public State(double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,
                     double hydrocarbonPartialPressure,double waterPartialPressure,double volume,double enthalpy,double internalEnergy,
                     double mass,double liquidVolume,double waterVolume,double vaporVolume,HydrocarbonModel.Phase liquidProperties,
                     HydrocarbonModel.Phase vaporProperties) {
            this(temperature,pressure,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPartialPressure,waterPartialPressure,
                    volume,enthalpy,internalEnergy,mass,liquidVolume,waterVolume,vaporVolume,liquidProperties,vaporProperties,
                    SolidInventory.EMPTY,SolidInventory.Moments.ZERO);
        }
        public State { Objects.requireNonNull(solids); Objects.requireNonNull(solidMoments); }
        public State withSolids(SolidInventory inventory) { return withSolidState(inventory,inventory.moments()); }
        /** Moment-only trial states never become owned inventories before population reconstruction. */
        public State withSolidState(SolidInventory inventory,SolidInventory.Moments moments) {
            return new State(temperature,pressure,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPartialPressure,waterPartialPressure,
                    volume-solidMoments.volume()+moments.volume(),
                    enthalpy-solidMoments.enthalpy(temperature,pressure)+moments.enthalpy(temperature,pressure),
                    internalEnergy-solidMoments.internalEnergy(temperature)+moments.internalEnergy(temperature),
                    mass-solidMoments.mass()+moments.mass(),liquidVolume,waterVolume,vaporVolume,liquidProperties,vaporProperties,inventory,moments);
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
