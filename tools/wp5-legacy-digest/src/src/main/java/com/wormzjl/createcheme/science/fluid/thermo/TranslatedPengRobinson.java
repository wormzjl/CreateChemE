package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.thermo.PairInteractions;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;
import java.util.Objects;

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
    /**
     * Optional low-temperature ideal-gas segments: below {@code joints[i]} component {@code i} takes the heat capacity
     * {@code lowCoefficients[i]} and an enthalpy continued from the main fit's value at the joint,
     * {@code H(T) = jointEnthalpies[i] + G(T) - jointAntiderivatives[i]} with {@code G} the segment's antiderivative, so
     * the enthalpy is continuous there by construction (at the joint the continuation adds {@code G(Tj) - G(Tj)}, an
     * exact zero). A component without a segment has the joint at minus infinity and is evaluated exactly as before.
     */
    private final double[] joints,jointEnthalpies,jointAntiderivatives;
    private final double[][] lowCoefficients;
    /** See {@link #residualOnly}: no ideal-gas part, so the heat capacity is the residual one and may be any sign. */
    private final boolean residualOnly;

    /** Cp coefficients are powers of (T - 298.15 K); each component has exactly six coefficients. */
    public TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions,
                                 double[][] heatCapacityCoefficients, double[] translations) {
        this(components,interactions,heatCapacityCoefficients,translations,null,null);
    }
    /**
     * {@code joints} and {@code lowCoefficients} give each component an optional second heat-capacity segment below
     * its joint (six coefficients in powers of (T - 298.15 K), like the main fit); a joint of minus infinity, or
     * {@code null} arrays, is none.
     */
    public TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions,
                                 double[][] heatCapacityCoefficients, double[] translations,
                                 double[] joints, double[][] lowCoefficients) {
        this(components,interactions,null,heatCapacityCoefficients,translations,joints,lowCoefficients,false);
    }

    /**
     * The same equation of state without an ideal-gas part, for an evaluator that adds its own ideal-gas functions
     * (the data spine of the unified multiphase thermodynamics plan, {@code science.thermo.phase.CubicPhaseEvaluator}).
     * Every heat-capacity coefficient is zero, so the ideal-gas enthalpy and heat capacity are exact zeros:
     * {@link Values#molarEnthalpy()} is then {@code h^R + P c} bit for bit, {@link Values#heatCapacity()} is the
     * residual {@code c_p}, whose sign is not checked, and {@link Derivatives#partialMolarEnthalpyView()} holds the
     * residual partial molar enthalpies plus {@code P c_i}. Volume, fugacity coefficients and every volumetric
     * derivative are the ones the full model computes, bit for bit.
     */
    public static TranslatedPengRobinson residualOnly(List<ThermoComponent> components, double[][] interactions,
                                                      double[] translations) {
        return new TranslatedPengRobinson(components,interactions,null,new double[components.size()][6],translations,null,null,true);
    }

    /**
     * The model on a pair-interaction model instead of a constant matrix: the kernel runs
     * {@link PengRobinsonKernel.Mixing#TEMPERATURE_DEPENDENT_PAIRS}, so a group-contribution (E-PPR78) pair's
     * {@code k_ij(T)} and its temperature derivatives enter every value and derivative here. On
     * {@link PairInteractions#constant} of a matrix this model is the matrix constructor's bit for bit.
     */
    public TranslatedPengRobinson(List<ThermoComponent> components, PairInteractions interactions,
                                  double[][] heatCapacityCoefficients, double[] translations) {
        this(components,null,Objects.requireNonNull(interactions,"interactions"),
                heatCapacityCoefficients,translations,null,null,false);
    }

    /** {@link #residualOnly(List, double[][], double[])} on a pair-interaction model. */
    public static TranslatedPengRobinson residualOnly(List<ThermoComponent> components, PairInteractions interactions,
                                                      double[] translations) {
        return new TranslatedPengRobinson(components,null,Objects.requireNonNull(interactions,"interactions"),
                new double[components.size()][6],translations,null,null,true);
    }

    /** Exactly one of {@code interactions} (the constant matrix, {@code SPARSE_PAIRS}) and {@code pairModel} is set. */
    private TranslatedPengRobinson(List<ThermoComponent> components, double[][] interactions, PairInteractions pairModel,
                                   double[][] heatCapacityCoefficients, double[] translations,
                                   double[] joints, double[][] lowCoefficients, boolean residualOnly) {
        this.residualOnly = residualOnly;
        this.components = List.copyOf(components);
        int count = components.size();
        if (count == 0 || (pairModel == null ? interactions.length != count : pairModel.componentCount() != count)
                || heatCapacityCoefficients.length != count
                || translations.length != count) throw new IllegalArgumentException("Inconsistent property basis");
        if ((joints == null) != (lowCoefficients == null) || joints != null && (joints.length != count || lowCoefficients.length != count))
            throw new IllegalArgumentException("Inconsistent low-temperature heat-capacity segments");
        this.joints = new double[count]; jointEnthalpies = new double[count]; jointAntiderivatives = new double[count];
        this.lowCoefficients = new double[count][];
        for (int i = 0; i < count; i++) {
            this.joints[i] = joints == null ? Double.NEGATIVE_INFINITY : joints[i];
            if (this.joints[i] == Double.NEGATIVE_INFINITY) continue;
            if (!Double.isFinite(this.joints[i]) || this.joints[i] <= 0 || lowCoefficients[i] == null || lowCoefficients[i].length != 6
                    || heatCapacityCoefficients[i].length != 6)
                throw new IllegalArgumentException("Invalid low-temperature heat-capacity segment");
            this.lowCoefficients[i] = lowCoefficients[i].clone();
            for (double value : this.lowCoefficients[i]) finite(value);
            double delta = this.joints[i] - REFERENCE_T, h = 0, g = 0;
            for (int term = 5; term >= 0; term--) { h = h*delta + heatCapacityCoefficients[i][term]/(term+1); g = g*delta + this.lowCoefficients[i][term]/(term+1); }
            jointEnthalpies[i] = h*delta; jointAntiderivatives[i] = g*delta;
        }
        this.heatCapacityCoefficients = new double[count][];
        this.translations = translations.clone();
        double[] criticalTemperatures=new double[count],criticalPressures=new double[count],acentricFactors=new double[count];
        for (int i = 0; i < count; i++) {
            if ((pairModel == null && interactions[i].length != count) || heatCapacityCoefficients[i].length != 6) {
                throw new IllegalArgumentException("Invalid property matrix dimensions");
            }
            this.heatCapacityCoefficients[i] = heatCapacityCoefficients[i].clone();
            if (pairModel == null) for (double value : interactions[i]) finite(value);
            for (double value : this.heatCapacityCoefficients[i]) finite(value);
            finite(this.translations[i]);
            var component=components.get(i);
            criticalTemperatures[i]=component.criticalTemperatureKelvin();
            criticalPressures[i]=component.criticalPressurePascal();
            acentricFactors[i]=component.acentricFactor();
        }
        if (pairModel == null) for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) {
            if (interactions[i][j] != interactions[j][i]) throw new IllegalArgumentException("Asymmetric interactions");
        }
        // The kernel's own domain is left open: this class validates every state itself, with the messages it
        // always produced, and a property package's validity range is the caller's business, not the EOS's.
        kernel=pairModel == null
                ? new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,interactions,
                        Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE,
                        PengRobinsonKernel.Mixing.SPARSE_PAIRS)
                : new PengRobinsonKernel(criticalTemperatures,criticalPressures,acentricFactors,pairModel,
                        Double.MIN_VALUE,Double.MAX_VALUE,Double.MIN_VALUE,Double.MAX_VALUE);
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
        private final Values values;
        private final double[] heatCapacity,enthalpy;
        private final double[] rootDt,rootDt2,crossDt;
        private double temperature=Double.NaN;
        private Workspace(TranslatedPengRobinson owner) {
            this.owner=owner;int count=owner.components.size();
            mixture=owner.kernel.newWorkspace();evaluation=owner.kernel.newEvaluation();values=new Values(count);
            heatCapacity=new double[count];enthalpy=new double[count];
            rootDt=new double[count];rootDt2=new double[count];crossDt=new double[count];
        }
        public double temperature(){return temperature;}
    }

    /**
     * The same numbers a {@link Phase} carries, in a buffer the caller keeps instead of a record it allocates.
     * {@link #evaluate} fills the workspace's own and copies out of it; {@link #differentiate} fills the one
     * inside a {@link Derivatives} and copies nothing.
     */
    public static final class Values {
        private final double[] logFugacityCoefficients,partialMolarVolumes;
        private double molarVolume,molarEnthalpy,molarInternalEnergy,volumeTemperatureDerivative,
                volumePressureDerivative,heatCapacity,enthalpyPressureDerivative,isothermalCompressibility,
                volumeSecondTemperatureDerivative,compressibility,idealGasHeatCapacity;
        private boolean vaporBranch;
        private int physicalRootCount;
        private Values(int count) {
            logFugacityCoefficients=new double[count];partialMolarVolumes=new double[count];
        }
        public double molarVolume(){return molarVolume;}
        public double molarEnthalpy(){return molarEnthalpy;}
        public double molarInternalEnergy(){return molarInternalEnergy;}
        public double volumeTemperatureDerivative(){return volumeTemperatureDerivative;}
        public double volumePressureDerivative(){return volumePressureDerivative;}
        public double heatCapacity(){return heatCapacity;}
        public double enthalpyPressureDerivative(){return enthalpyPressureDerivative;}
        public double isothermalCompressibility(){return isothermalCompressibility;}
        public double volumeSecondTemperatureDerivative(){return volumeSecondTemperatureDerivative;}
        public double compressibility(){return compressibility;}
        /** The ideal-gas half of {@link #heatCapacity()}; the residual half is the kernel's dH^R/dT. */
        public double idealGasHeatCapacity(){return idealGasHeatCapacity;}
        public boolean vaporBranch(){return vaporBranch;}
        /** Physical roots of the cubic at this state and composition: 1, or 3 where both {@link PhaseRoot}s exist. */
        public int physicalRootCount(){return physicalRootCount;}
        /** The caller must not mutate these; they are refilled by the next evaluation. */
        public double[] logFugacityCoefficientsView(){return logFugacityCoefficients;}
        public double[] partialMolarVolumesView(){return partialMolarVolumes;}
        private Phase phase() {
            return new Phase(molarVolume,molarEnthalpy,molarInternalEnergy,volumeTemperatureDerivative,
                    volumePressureDerivative,heatCapacity,enthalpyPressureDerivative,isothermalCompressibility,
                    logFugacityCoefficients.clone(),volumeSecondTemperatureDerivative,
                    partialMolarVolumes.clone(),vaporBranch);
        }
    }

    /**
     * Every first derivative of one phase, filled into caller-owned storage: the value block, the kernel's
     * {@code d ln phi_i/dn_j}, {@code d ln phi_i/dT} and {@code dH^R/dT}, and the two the translation and the
     * volumetric block add - {@code d ln phi_i/dP} and the partial molar enthalpies. Allocate one per solve
     * with {@link TranslatedPengRobinson#newDerivatives()} and refill it per node; it carries an n x n block,
     * so it is deliberately not part of a prepared temperature.
     *
     * <p>This is the bundle an analytic node Jacobian assembles from. It describes the equation of state at
     * the pressure it was evaluated at, so a caller building a Jacobian for a liquid phase - which
     * {@link HydrocarbonModel} evaluates at its 2 MPa reference and then corrects with
     * {@link GlobalLiquidResponse} - must chain that correction itself; nothing here applies it.</p>
     */
    public static final class Derivatives {
        private final TranslatedPengRobinson owner;
        private final PengRobinsonKernel.Derivatives mixture;
        private final Values values;
        private final double[] dLogPhiDt,dLogPhiDp,partialMolarEnthalpy;
        private double residualEnthalpyTemperatureDerivative;
        private Derivatives(TranslatedPengRobinson owner) {
            this.owner=owner;int count=owner.components.size();
            mixture=owner.kernel.newDerivatives();values=new Values(count);
            dLogPhiDt=new double[count];dLogPhiDp=new double[count];partialMolarEnthalpy=new double[count];
        }
        /** The phase itself, evaluated on the very root these derivatives describe. */
        public Values values(){return values;}
        /** {@code d ln phi_i / dn_j} at the evaluated composition normalised to one mole. */
        public double logFugacityCompositionDerivative(int component,int respectTo) {
            return mixture.dLogPhiDnRowView(component)[respectTo];
        }
        /** {@code d ln phi_i / dT} at constant pressure and composition, including the volume translation. */
        public double[] logFugacityTemperatureDerivativeView(){return dLogPhiDt;}
        /** {@code d ln phi_i / dP} at constant temperature and composition: {@code v_i/(RT) - 1/P}. */
        public double[] logFugacityPressureDerivativeView(){return dLogPhiDp;}
        /** {@code dH/dn_i}: the ideal-gas, residual and translation parts, so these sum to the molar enthalpy. */
        public double[] partialMolarEnthalpyView(){return partialMolarEnthalpy;}
        /** {@code dH^R/dT} at constant pressure; {@link Values#heatCapacity()} is the whole {@code dH/dT}. */
        public double residualEnthalpyTemperatureDerivative(){return residualEnthalpyTemperatureDerivative;}
        /** {@code dH^R/dn_i}, which is {@code -R T^2 d ln phi_i/dT} of the untranslated EOS. */
        public double[] partialMolarResidualEnthalpyView(){return mixture.partialMolarResidualEnthalpyView();}
    }

    public Derivatives newDerivatives() { return new Derivatives(this); }

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
            if(temperature<joints[i]) {
                double[] low=lowCoefficients[i];
                for(int term=5;term>=0;term--){cp=cp*delta+low[term];h=h*delta+low[term]/(term+1);}
                workspace.heatCapacity[i]=cp;workspace.enthalpy[i]=jointEnthalpies[i]+(h*delta-jointAntiderivatives[i]);
                continue;
            }
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
        return evaluateValues(temperature,pressure,amounts,root,workspace).phase();
    }

    /**
     * {@link #evaluate} without the record: the workspace's own buffer, which the next evaluation on this workspace
     * refills. Allocates nothing.
     */
    public Values evaluateValues(double temperature,double pressure,double[] amounts,PhaseRoot root,Workspace workspace) {
        requireState(temperature,pressure,amounts,workspace);
        kernel.evaluate(temperature,pressure,amounts,kernelRoot(root),workspace.mixture,workspace.evaluation);
        fill(temperature,pressure,workspace,workspace.evaluation,workspace.values);
        return workspace.values;
    }

    /** The equation of state this model translates; the phase-stability test runs on it (the translation cancels). */
    public PengRobinsonKernel kernel() { return kernel; }

    /**
     * Fills {@code output} with the phase and every first derivative of it, on the very same root: the
     * kernel's composition and temperature derivatives of {@code ln phi}, its {@code dH^R/dT} and partial
     * molar residual enthalpies, and the pressure derivative and partial molar enthalpies that the volume
     * translation and the volumetric block add to them. Allocates nothing.
     */
    public void differentiate(double temperature,double pressure,double[] amounts,PhaseRoot root,
                              Workspace workspace,Derivatives output) {
        requireState(temperature,pressure,amounts,workspace);
        if(output.owner!=this)throw new IllegalArgumentException("Derivative bundle belongs to another model");
        kernel.evaluateDerivatives(temperature,pressure,amounts,kernelRoot(root),workspace.mixture,output.mixture);
        fill(temperature,pressure,workspace,output.mixture.evaluation(),output.values);
        double rt=R*temperature;
        double[] mixtureDt=output.mixture.dLogPhiDtView(),residual=output.mixture.partialMolarResidualEnthalpyView();
        for(int i=0;i<translations.length;i++) {
            // ln phi_i carries +P c_i/(RT) from the translation, whose T and P derivatives are these two terms.
            output.dLogPhiDt[i]=mixtureDt[i]-pressure*translations[i]/(rt*temperature);
            output.dLogPhiDp[i]=output.values.partialMolarVolumes[i]/rt-1/pressure;
            // h_i = ideal + residual + the translation's P c_i, so these sum to the molar enthalpy above.
            output.partialMolarEnthalpy[i]=workspace.enthalpy[i]+residual[i]+pressure*translations[i];
            if(!Double.isFinite(output.dLogPhiDt[i])||!Double.isFinite(output.dLogPhiDp[i])
                    ||!Double.isFinite(output.partialMolarEnthalpy[i])) {
                throw new IllegalArgumentException("Nonfinite chemical-potential derivative");
            }
        }
        output.residualEnthalpyTemperatureDerivative=output.mixture.dResidualEnthalpyDt();
    }

    private void requireState(double temperature,double pressure,double[] amounts,Workspace workspace) {
        if (!Double.isFinite(temperature) || temperature <= 0 || !Double.isFinite(pressure) || pressure <= 0) {
            throw new IllegalArgumentException("Positive finite temperature and pressure required");
        }
        if(workspace.owner!=this||temperature!=workspace.temperature)throw new IllegalArgumentException("Temperature coefficients belong to another state/model");
        if(amounts.length!=components.size())throw new IllegalArgumentException("Composition basis mismatch");
    }
    private static PengRobinsonKernel.Root kernelRoot(PhaseRoot root) {
        return root==PhaseRoot.VAPOR?PengRobinsonKernel.Root.VAPOR:PengRobinsonKernel.Root.LIQUID;
    }

    /** The translation, the caloric terms and the volumetric block, on the mixture the kernel just evaluated. */
    private void fill(double temperature,double pressure,Workspace workspace,
                      PengRobinsonKernel.Evaluation evaluation,Values out) {
        double[] x=workspace.mixture.compositionView(),attractionRows=workspace.mixture.attractionRowsView();
        double a=evaluation.aMix(),b=evaluation.bMix(),da=evaluation.daMixDt();
        double dda=kernel.mixtureSecondTemperatureDerivative(workspace.mixture,workspace.rootDt,workspace.rootDt2,workspace.crossDt);
        double shift = 0, idealH = 0, idealCp = 0;
        for (int i = 0; i < x.length; i++) {
            shift += x[i] * translations[i];
            idealCp+=x[i]*workspace.heatCapacity[i];idealH+=x[i]*workspace.enthalpy[i];
        }
        double rt=R*temperature;
        double z=evaluation.compressibility(),v=z*rt/pressure;
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
        double h=idealH+evaluation.residualEnthalpyJoulesPerMol()+pressure*shift;
        double dlogdv = 1/(v+(1+SQRT_TWO)*b) - 1/(v+(1-SQRT_TWO)*b);
        double cp = idealCp + pressure*dvdt - R + temperature*dda/(2*SQRT_TWO*b)*log
                + (temperature*da-a)/(2*SQRT_TWO*b)*dlogdv*dvdt;
        double dhdp = physicalV - temperature*dvdt;
        double[] logPhi = out.logFugacityCoefficients;
        double[] partialVolumes = out.partialMolarVolumes;
        double[] mixtureLogPhi=evaluation.logFugacityCoefficientsView();
        for (int i=0;i<x.length;i++) {
            logPhi[i]=mixtureLogPhi[i]+pressure*translations[i]/rt;
            double db=coVolume(i)-b;
            double pressureCompositionDerivative=-2*(attractionRows[i]-a)/denominator
                    +db*(R*temperature/((v-b)*(v-b))+2*a*(v-b)/(denominator*denominator));
            partialVolumes[i]=v-pressureCompositionDerivative/dpdv+translations[i];
            if(!Double.isFinite(logPhi[i])||!Double.isFinite(partialVolumes[i]))throw new IllegalArgumentException("Nonfinite chemical-potential derivative");
        }
        if (!(physicalV > 0) || !(residualOnly || cp > 0) || !Double.isFinite(h) || !Double.isFinite(cp)
                || !Double.isFinite(dvdp) || !Double.isFinite(dvdt) || !Double.isFinite(dhdp)) {
            throw new IllegalArgumentException("Invalid translated phase properties");
        }
        out.molarVolume=physicalV;out.molarEnthalpy=h;out.molarInternalEnergy=h-pressure*physicalV;
        out.volumeTemperatureDerivative=dvdt;out.volumePressureDerivative=dvdp;out.heatCapacity=cp;
        out.enthalpyPressureDerivative=dhdp;out.isothermalCompressibility=-dvdp/physicalV;
        out.volumeSecondTemperatureDerivative=dvdtt;out.compressibility=z;out.idealGasHeatCapacity=idealCp;
        out.vaporBranch=a/(R*temperature*b)<5.8773599486044 || v/b>3.9513730355914;
        out.physicalRootCount=evaluation.physicalRootCount();
    }

    private double coVolume(int component) { return kernel.coVolume(component); }

    private static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite property coefficient");
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
