package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;

/**
 * Fluid-specific property snapshot. Does not replace or modify the column's property model.
 *
 * <p>Every hydrocarbon phase is the translated PR78 evaluated directly at its own pressure: a liquid at the state
 * pressure, a vapour at its hydrocarbon partial pressure. There is no reference pressure and no global liquid
 * compressibility: a liquid's volume, enthalpy, {@code ln phi} and compressibility are the equation of state's own at
 * the state, and the volume translation of each component is anchored at its reference point's own temperature and
 * pressure.
 */
public final class HydrocarbonModel {
    /** A numerical guard on the hydrocarbon <em>partial</em> pressure a vapour is evaluated at, which in a gas that is
     * nearly all steam can be arbitrarily small; the state's own pressure is bounded by the package's domain. */
    public static final double VAPOR_PARTIAL_PRESSURE_FLOOR = 1e-6;
    /** The formulation tag this model's revision carries: liquids at the state pressure, anchors at their own. */
    public static final String FORMULATION="direct-liquid-v1";
    private final MaterialCatalog.Package propertyPackage;
    private final TranslatedPengRobinson translated;
    private final String revision;
    /** Every temperature and pressure range comes from the package's data; see {@link FluidDomain}. */
    private final FluidDomain domain;
    /** Each component's PR co-volume, for the phase identification parameter of a single-root liquid evaluation. */
    private final double[] coVolumes;

    public HydrocarbonModel(MaterialCatalog catalog,String packageId) {
        propertyPackage=catalog.requirePackage(packageId);
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
        double[] shifts=new double[count],anchorT=new double[count],anchorP=new double[count];
        for(int i=0;i<count;i++) {
            var property=propertyPackage.properties().get(i);
            var point=calibrations.get(property.component());
            double t=point==null?property.standardTemperature():point.temperatureKelvin();
            double targetVolume=point==null?property.molecularWeight()/property.density():point.molarVolumeCubicMetres();
            double referenceP=point==null?property.standardPressure():point.pressurePascal();
            double[] pure=new double[count];pure[i]=1;
            // The translation makes the model's liquid volume at the anchor's own (T, P) the target: the untranslated
            // liquid root there, with no compressibility carrying the target to another pressure.
            double rawVolume=raw.evaluate(t,referenceP,pure,PhaseRoot.LIQUID).compressibilityFactor()
                    *PengRobinson78.GAS_CONSTANT*t/referenceP;
            shifts[i]=targetVolume-rawVolume;
            anchorT[i]=t;anchorP[i]=referenceP;
        }
        translated=new TranslatedPengRobinson(components,interactions,cp,shifts,joints,low);
        coVolumes=new double[count];
        for(int i=0;i<count;i++)coVolumes[i]=translated.kernel().coVolume(i);
        // An anchor is a liquid volume, so the equation of state must have a liquid root at the anchor's state: a
        // single vapour-like root there would translate the component by a gas volume.
        for(int i=0;i<count;i++) {
            double[] pure=new double[count];pure[i]=1;
            var values=translated.evaluateValues(anchorT[i],anchorP[i],pure,PhaseRoot.LIQUID,translated.prepare(anchorT[i]));
            if(values.physicalRootCount()==1&&!liquidLike(values,anchorT[i],anchorP[i],coVolumes[i]))
                throw new IllegalArgumentException("Package "+packageId+": component "+components.get(i).id()+" has no liquid root at its volume anchor "
                        +anchorT[i]+" K, "+anchorP[i]+" Pa; the translation needs a liquid there");
        }
        revision=catalog.fluidThermoFingerprint(packageId)+":"+FORMULATION+":fluid-domain-data-v1:cp-segments-v1:catalog-volume-reference-v1";
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
     * <p>This is the equation of state at the pressure given, which is exactly what {@link #phase} evaluates for a
     * liquid (at the state pressure) and for a vapour (at its partial pressure): the bundle describes the phase the
     * network evaluates, and nothing has to be chained onto it.</p>
     */
    public void differentiate(double t,double p,double[] amounts,PhaseRoot root,
                              TranslatedPengRobinson.Workspace terms,TranslatedPengRobinson.Derivatives output) {
        if(amounts.length!=componentCount())throw new IllegalArgumentException("Hydrocarbon basis mismatch");
        translated.differentiate(t,p,amounts,root,terms,output);
    }
    /** {@link #phase(double, double, double[], PhaseRoot, TranslatedPengRobinson.Workspace, boolean)} of a phase with no
     * vapour beside it: a liquid on a single vapour-like root is accepted (and flagged {@link Phase#liquidRootAbsent()}). */
    public Phase phase(double t,double p,double[] amounts,PhaseRoot root,TranslatedPengRobinson.Workspace terms) {
        return phase(t,p,amounts,root,terms,false);
    }
    /**
     * One hydrocarbon phase at {@code p}: the state pressure for a liquid, the hydrocarbon partial pressure for a vapour.
     *
     * <p>The liquid-root rule. At the state pressure the cubic can have one physical root, and it can be vapour-like
     * (the phase identification parameter of Venkatarathnam and Oellrich 2011 at most one): the liquid root is absent.
     * If the phase's node also carries a hydrocarbon vapour ({@code vaporCoexists}), the two slots would evaluate the
     * same kind of root, a trial collapsing onto the trivial solution, so the evaluation is refused with the typed
     * {@link LiquidRootAbsent} (an {@link IllegalArgumentException}: the line search backtracks and the outer check
     * re-decides the regime). In a liquid-only node the single root is the same fluid continuing and is accepted.
     */
    public Phase phase(double t,double p,double[] amounts,PhaseRoot root,TranslatedPengRobinson.Workspace terms,boolean vaporCoexists) {
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
        var values=translated.evaluateValues(t,p,amounts,root,terms);
        boolean absent=root==PhaseRoot.LIQUID&&values.physicalRootCount()==1&&!liquidLike(values,t,p,mixtureCoVolume(amounts));
        if(absent&&vaporCoexists)throw new LiquidRootAbsent(t,p);
        return new Phase(values.molarVolume(),values.molarEnthalpy(),values.molarInternalEnergy(),
                values.volumePressureDerivative(),values.logFugacityCoefficientsView().clone(),
                root==PhaseRoot.VAPOR&&values.vaporBranch(),absent);
    }

    private double mixtureCoVolume(double[] amounts) {
        double total=0,b=0;
        for(int i=0;i<amounts.length;i++){total+=amounts[i];b+=amounts[i]*coVolumes[i];}
        return b/total;
    }
    /**
     * Whether an evaluated root is liquid-like: the phase identification parameter
     * {@code PIP = v [(d2P/dT dv)/(dP/dT)_v - (d2P/dv2)_T/(dP/dv)_T]} of Venkatarathnam and Oellrich (2011) above one
     * (an ideal gas is exactly one, a dilute real gas below the Boyle-like limit below one, a dense liquid well
     * above). {@code v} is the untranslated root; the PR mixture parameters are recovered from the root's own
     * {@code Z}, {@code dv/dP} and {@code dv/dT} and the mixture co-volume {@code b}. The P3 plan's parenthesis
     * "(> 1 vapour-like)" has the convention reversed and is not followed (the paper, and
     * {@code science.thermo.phase.PhaseIdentification}, which WP7 should make the one implementation).
     */
    static boolean liquidLike(TranslatedPengRobinson.Values values,double t,double p,double b) {
        return phaseIdentificationParameter(values.compressibility(),values.volumePressureDerivative(),
                values.volumeTemperatureDerivative(),t,p,b)>1;
    }
    static double phaseIdentificationParameter(double z,double dvdp,double dvdt,double t,double p,double b) {
        double r=PengRobinson78.GAS_CONSTANT,v=z*r*t/p,u=v-b;
        double dpdv=1/dvdp,dpdt=-dvdt/dvdp;
        double denominator=v*v+2*b*v-b*b;
        double a=(r*t/u-p)*denominator,da=(r/u-dpdt)*denominator;
        double dpdvv=2*r*t/(u*u*u)+2*a/(denominator*denominator)-8*a*(v+b)*(v+b)/(denominator*denominator*denominator);
        double dpdtv=-r/(u*u)+2*da*(v+b)/(denominator*denominator);
        return v*(dpdtv/dpdt-dpdvv/dpdv);
    }

    /**
     * A liquid trial whose equation of state has no liquid root at the state pressure while its node also carries a
     * hydrocarbon vapour. It is an {@link IllegalArgumentException}, so a Newton trial or a Jacobian column that meets
     * it is refused like any state outside the evaluable domain; it is not a {@link ThermoDomainViolation}, because the
     * state is inside every declared range and only the phase layout is wrong.
     */
    public static final class LiquidRootAbsent extends IllegalArgumentException {
        public LiquidRootAbsent(double temperature,double pressure) {
            super("Liquid root absent: the hydrocarbon liquid of a two-phase node has only a vapour-like root at "
                    +temperature+" K, "+pressure+" Pa");
        }
    }

    /**
     * The coefficients belong to the record from construction on. {@link #phase} is the only place one is built,
     * from a copy of the evaluation's buffer. {@code vaporBranch} is the vapour root's branch heuristic (false for a
     * liquid); {@code liquidRootAbsent} marks a liquid evaluation that found only a vapour-like root (false for a vapour).
     */
    public record Phase(double molarVolume,double molarEnthalpy,double molarInternalEnergy,
                        double volumePressureDerivative,double[] logFugacity,boolean vaporBranch,boolean liquidRootAbsent) {
        @Override public double[] logFugacity() { return logFugacity.clone(); }
        /** The coefficients themselves, for the solver packages. The caller must not mutate them. */
        public double[] logFugacityView() { return logFugacity; }
        /** {@code -(dv/dP)/v}: the phase's own isothermal compressibility, 1/Pa. */
        public double isothermalCompressibility() { return -volumePressureDerivative/molarVolume; }
    }
}
