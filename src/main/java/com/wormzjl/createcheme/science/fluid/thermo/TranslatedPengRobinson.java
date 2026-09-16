package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;

/**
 * Constant volume-translated PR78 with analytic fixed-composition volumetric/caloric derivatives.
 *
 * <p>The equation of state itself is the shared {@link PengRobinsonKernel} - the same arithmetic the column
 * solves on - selected with {@link PengRobinsonKernel.Mixing#SPARSE_PAIRS}, because the network's package has
 * 11 nonzero binary interactions among 21 components and the dense quadratic rule spent three n x n matrices
 * per distinct temperature to express them. What stays here is what the kernel does not model: the ideal-gas
 * caloric polynomials, the constant volume translation, and the volumetric block (dv/dT, dv/dP, d2v/dT2, cp,
 * dh/dP, the isothermal compressibility and the partial molar volumes), which is explicit algebra in
 * {@code (v, a, b, da/dT, d2a/dT2, S_i)} and is unchanged.
 */
public final class TranslatedPengRobinson {
    private static final double R = PengRobinson78.GAS_CONSTANT;
    private static final double SQRT_TWO = Math.sqrt(2);
    private static final double REFERENCE_T = 298.15;
    private final List<ThermoComponent> components;
    private final double[][] heatCapacityCoefficients;
    private final double[] translations;
    private final PengRobinsonKernel kernel;

    /** Cp coefficients are powers of (T - 298.15 K); each component has exactly six coefficients. */
    public TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions,
                                 double[][] heatCapacityCoefficients, double[] translations) {
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || interactions.length != count || heatCapacityCoefficients.length != count
                || translations.length != count) throw new IllegalArgumentException("Inconsistent property basis");
        this.heatCapacityCoefficients = new double[count][];
        this.translations = translations.clone();
        double[] criticalTemperatures=new double[count],criticalPressures=new double[count],acentricFactors=new double[count];
        for (int i = 0; i < count; i++) {
            if (interactions[i].length != count || heatCapacityCoefficients[i].length != 6) {
                throw new IllegalArgumentException("Invalid property matrix dimensions");
            }
            this.heatCapacityCoefficients[i] = heatCapacityCoefficients[i].clone();
            for (double value : interactions[i]) finite(value);
            for (double value : this.heatCapacityCoefficients[i]) finite(value);
            finite(this.translations[i]);
            var component=components.get(i);
            criticalTemperatures[i]=component.criticalTemperatureKelvin();
            criticalPressures[i]=component.criticalPressurePascal();
            acentricFactors[i]=component.acentricFactor();
        }
        for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) {
            if (interactions[i][j] != interactions[j][i]) throw new IllegalArgumentException("Asymmetric interactions");
        }
        // The kernel's own domain is left open: this class validates every state itself, with the messages it
        // always produced, and a property package's validity range is the caller's business, not the EOS's.
        kernel=new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,interactions,
                Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE,
                PengRobinsonKernel.Mixing.SPARSE_PAIRS);
    }

    public Phase evaluate(double temperature, double pressure, double[] amounts, PhaseRoot root) {
        return evaluate(temperature,pressure,amounts,root,prepare(temperature));
    }

    /**
     * One exact temperature's prepared state, and the scratch every evaluation at it reuses.
     *
     * <p>This replaces the immutable {@code TemperatureTerms} and its three n x n matrices. The mixing terms a
     * workspace carries are the kernel's 3n pure-component vectors, and the composition-dependent sums that
     * used to be read out of those matrices are now formed per evaluation in O(n + pairs). It is mutable
     * scratch, so - like every other solver workspace here - it belongs to one solving thread at a time; the
     * caller that holds it (a node's {@link FluidThermodynamics.Prepared}) already has that confinement.</p>
     */
    public static final class Workspace {
        private final TranslatedPengRobinson owner;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation evaluation;
        private final double[] heatCapacity,enthalpy;
        private final double[] rootDt,rootDt2,crossDt;
        private double temperature=Double.NaN;
        private Workspace(TranslatedPengRobinson owner) {
            this.owner=owner;int count=owner.components.size();
            mixture=owner.kernel.newWorkspace();evaluation=owner.kernel.newEvaluation();
            heatCapacity=new double[count];enthalpy=new double[count];
            rootDt=new double[count];rootDt2=new double[count];crossDt=new double[count];
        }
        public double temperature(){return temperature;}
    }

    public Workspace newWorkspace() { return new Workspace(this); }

    /** A workspace prepared at one temperature; a solve reuses it across every composition and pressure trial. */
    public Workspace prepare(double temperature) {
        Workspace workspace=new Workspace(this);
        prepare(temperature,workspace);
        return workspace;
    }
    public void prepare(double temperature,Workspace workspace) {
        SolverDiagnostics.count(SolverDiagnostics.temperatureTermsCalls);
        if(workspace.owner!=this)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(!Double.isFinite(temperature)||temperature<=0)throw new IllegalArgumentException("Invalid coefficient temperature");
        int count=components.size();double delta=temperature-REFERENCE_T;
        for(int i=0;i<count;i++) {
            double cp=0,h=0;
            for(int term=5;term>=0;term--){cp=cp*delta+heatCapacityCoefficients[i][term];h=h*delta+heatCapacityCoefficients[i][term]/(term+1);}
            workspace.heatCapacity[i]=cp;workspace.enthalpy[i]=h*delta;
        }
        kernel.prepareTemperature(temperature,workspace.mixture);
        // sqrt(a_i) vanishes exactly where the PR alpha term does, which is where every d/dT below divides
        // by zero. The same state the dense form refused as a degenerate alpha derivative.
        double[] roots=workspace.mixture.attractionRootsView();
        for(int i=0;i<count;i++)if(roots[i]==0)throw new IllegalArgumentException("Degenerate PR alpha derivative");
        // The pure-component root derivatives depend on temperature alone, exactly like the dense form's
        // three matrices did; only the composition-weighted sums below are per evaluation.
        kernel.prepareRootDerivatives(temperature,workspace.mixture,workspace.rootDt,workspace.rootDt2);
        workspace.temperature=temperature;
    }

    public Phase evaluate(double temperature,double pressure,double[] amounts,PhaseRoot root,Workspace workspace) {
        if (!Double.isFinite(temperature) || temperature <= 0 || !Double.isFinite(pressure) || pressure <= 0) {
            throw new IllegalArgumentException("Positive finite temperature and pressure required");
        }
        if(workspace.owner!=this||temperature!=workspace.temperature)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(amounts.length!=components.size())throw new IllegalArgumentException("Composition basis mismatch");
        kernel.evaluate(temperature,pressure,amounts,
                root==PhaseRoot.VAPOR?PengRobinsonKernel.Root.VAPOR:PengRobinsonKernel.Root.LIQUID,
                workspace.mixture,workspace.evaluation);
        double[] x=workspace.mixture.compositionView(),attractionRows=workspace.mixture.attractionRowsView();
        double a=workspace.evaluation.aMix(),b=workspace.evaluation.bMix(),da=workspace.evaluation.daMixDt();
        double dda=kernel.mixtureSecondTemperatureDerivative(workspace.mixture,workspace.rootDt,workspace.rootDt2,workspace.crossDt);
        double shift = 0, idealH = 0, idealCp = 0;
        for (int i = 0; i < x.length; i++) {
            shift += x[i] * translations[i];
            idealCp+=x[i]*workspace.heatCapacity[i];idealH+=x[i]*workspace.enthalpy[i];
        }
        double rt=R*temperature;
        double z=workspace.evaluation.compressibility(),v=z*rt/pressure;
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
        double h=idealH+workspace.evaluation.residualEnthalpyJoulesPerMol()+pressure*shift;
        double dlogdv = 1/(v+(1+SQRT_TWO)*b) - 1/(v+(1-SQRT_TWO)*b);
        double cp = idealCp + pressure*dvdt - R + temperature*dda/(2*SQRT_TWO*b)*log
                + (temperature*da-a)/(2*SQRT_TWO*b)*dlogdv*dvdt;
        double dhdp = physicalV - temperature*dvdt;
        double[] logPhi = new double[x.length];
        double[] partialVolumes = new double[x.length];
        double[] mixtureLogPhi=workspace.evaluation.logFugacityCoefficientsView();
        for (int i=0;i<x.length;i++) {
            logPhi[i]=mixtureLogPhi[i]+pressure*translations[i]/rt;
            double db=coVolume(i)-b;
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

    private double coVolume(int component) { return kernel.coVolume(component); }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite property coefficient");
    }

    /** The construction inputs, for the package's test-only legacy oracle. */
    record Inputs(List<ThermoComponent> components,double[][] interactions,double[][] heatCapacityCoefficients,double[] translations) {}
    Inputs inputs() {
        double[][] cp=new double[heatCapacityCoefficients.length][];
        for(int i=0;i<cp.length;i++)cp[i]=heatCapacityCoefficients[i].clone();
        return new Inputs(components,kernel.binaryInteractions(),cp,translations.clone());
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
