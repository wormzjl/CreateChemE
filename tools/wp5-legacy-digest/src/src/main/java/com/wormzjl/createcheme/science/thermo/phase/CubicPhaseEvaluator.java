package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.fluid.thermo.TranslatedPengRobinson;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.IdealGasFunction;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The translated PR78 as a {@link PhaseEvaluator}: family {@value #FAMILY}.
 *
 * <p>The residual parts are {@link TranslatedPengRobinson#residualOnly} (the shared {@link PengRobinsonKernel} with a
 * constant per-component volume translation), evaluated at the state pressure for every root: a liquid is the cubic's
 * liquid root at (T, P), not the fluid network's 2 MPa reference carried by a global compressibility
 * ({@code HydrocarbonModel.REFERENCE_PRESSURE}, which the plan retires in P3). The volume, {@code ln phi} and
 * volumetric derivatives are therefore bit for bit those of a {@link TranslatedPengRobinson} with the same constants.
 * The ideal-gas parts are the {@link IdealGasFunction}s given at construction, one per component.</p>
 *
 * <p>The translation {@code c_i} adds {@code P c} to {@code h} and {@code g} and {@code P c_i/(R T)} to
 * {@code ln phi_i}, nothing to {@code s} or {@code c_p}; it cancels from every phase-equilibrium condition.</p>
 */
public final class CubicPhaseEvaluator implements PhaseEvaluator {
    public static final String FAMILY = "pr78_translated_v1";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(EnumSet.allOf(Capability.class));

    private final String sourceId;
    private final List<String> names;
    private final List<ThermoComponent> components;
    private final IdealGasFunction[] idealGas;
    private final TranslatedPengRobinson eos;
    private final double[] translations;

    /**
     * @param sourceId the package the constants come from; named in a {@link ThermoDomainViolation}
     * @param interactions square, symmetric PR78 {@code k_ij}
     * @param translations constant volume translation per component, m3/mol ({@code v = v_PR + c})
     * @param idealGas one ideal-gas function per component, in basis order
     */
    public CubicPhaseEvaluator(String sourceId, List<ThermoComponent> components, double[][] interactions,
                               double[] translations, List<? extends IdealGasFunction> idealGas) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.components = List.copyOf(components);
        if (idealGas.size() != this.components.size()) throw new IllegalArgumentException("One ideal-gas function per component");
        this.idealGas = idealGas.toArray(new IdealGasFunction[0]);
        for (IdealGasFunction function : this.idealGas) Objects.requireNonNull(function, "idealGas");
        this.names = this.components.stream().map(ThermoComponent::id).toList();
        if (names.stream().distinct().count() != names.size()) throw new IllegalArgumentException("Duplicate component");
        this.eos = TranslatedPengRobinson.residualOnly(this.components, interactions, translations);
        this.translations = translations.clone();
    }

    /**
     * The named components of a catalog package, in the given order, with their PR78 constants and interactions. The
     * translations are the caller's: their anchoring is a P3 decision (D1: saturated liquid at Tr = 0.8).
     */
    public static CubicPhaseEvaluator fromPackage(MaterialCatalog.Package propertyPackage, List<String> componentIds,
                                                  double[] translations, List<? extends IdealGasFunction> idealGas) {
        int n = componentIds.size();
        int[] index = new int[n];
        List<ThermoComponent> components = new java.util.ArrayList<>(n);
        for (int a = 0; a < n; a++) {
            index[a] = propertyPackage.components().indexOf(propertyPackage.canonicalId(componentIds.get(a)));
            if (index[a] < 0) throw new IllegalArgumentException("Component outside package: " + componentIds.get(a));
            var property = propertyPackage.properties().get(index[a]);
            var pr = property.pr();
            components.add(new ThermoComponent(propertyPackage.components().get(index[a]), pr.criticalTemperature(),
                    pr.criticalPressure(), pr.acentricFactor(), property.molecularWeight()));
        }
        double[][] interactions = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) interactions[a][b] = propertyPackage.interactions().get(index[a]).get(index[b]);
        }
        return new CubicPhaseEvaluator(propertyPackage.id(), components, interactions, translations, idealGas);
    }

    @Override public String family() { return FAMILY; }
    @Override public List<String> components() { return names; }
    @Override public Set<Capability> capabilities() { return CAPABILITIES; }
    public String sourceId() { return sourceId; }
    public ThermoComponent component(int index) { return components.get(index); }
    public IdealGasFunction idealGas(int index) { return idealGas[index]; }
    /** The constant volume translation {@code c_i} of a component, m3/mol ({@code v = v_PR + c}). */
    public double translation(int index) { return translations[index]; }
    /** A copy of every translation, in basis order. */
    public double[] translations() { return translations.clone(); }
    /** The untranslated equation of state; stability tests and flash iterations run on it (the translation cancels). */
    public PengRobinsonKernel kernel() { return eos.kernel(); }

    @Override
    public Workspace newWorkspace() { return new Workspace(this); }

    /** Scratch for one thread: the translated model's prepared temperature and the per-evaluation buffers. */
    public static final class Workspace implements PhaseEvaluator.Workspace {
        private final CubicPhaseEvaluator owner;
        private final TranslatedPengRobinson.Workspace terms;
        private final TranslatedPengRobinson.Derivatives derivatives;
        private final double[] composition;
        private final double[] idealGibbs;

        private Workspace(CubicPhaseEvaluator owner) {
            this.owner = owner;
            terms = owner.eos.newWorkspace();
            derivatives = owner.eos.newDerivatives();
            composition = new double[owner.names.size()];
            idealGibbs = new double[owner.names.size()];
        }
    }

    @Override
    public PhaseState evaluate(double temperature, double pressure, double[] amounts, PhaseRoot preference,
                               PhaseEvaluator.Workspace workspace, PhaseState out) {
        Workspace w = own(workspace);
        prepare(temperature, pressure, amounts, preference, w);
        TranslatedPengRobinson.Values values = eos.evaluateValues(temperature, pressure, amounts, preference, w.terms);
        fill(temperature, pressure, preference, values, w, Objects.requireNonNull(out, "out"));
        return out;
    }

    @Override
    public PhaseDerivatives derivatives(double temperature, double pressure, double[] amounts, PhaseRoot preference,
                                        PhaseEvaluator.Workspace workspace, PhaseDerivatives out) {
        Workspace w = own(workspace);
        Objects.requireNonNull(out, "out");
        if (out.componentCount() != names.size()) throw new IllegalArgumentException("Derivative basis mismatch");
        prepare(temperature, pressure, amounts, preference, w);
        eos.differentiate(temperature, pressure, amounts, preference, w.terms, w.derivatives);
        TranslatedPengRobinson.Values values = w.derivatives.values();
        fill(temperature, pressure, preference, values, w, out.state());
        out.begin(FAMILY);
        int n = names.size();
        for (int i = 0; i < n; i++) {
            double[] row = out.logFugacityCompositionRowBuffer(i);
            for (int j = 0; j < n; j++) row[j] = w.derivatives.logFugacityCompositionDerivative(i, j);
        }
        out.provide(Capability.FUGACITY_COMPOSITION_DERIVATIVES);
        System.arraycopy(w.derivatives.logFugacityTemperatureDerivativeView(), 0, out.logFugacityTemperatureBuffer(), 0, n);
        System.arraycopy(w.derivatives.logFugacityPressureDerivativeView(), 0, out.logFugacityPressureBuffer(), 0, n);
        out.provide(Capability.FUGACITY_STATE_DERIVATIVES);
        out.setVolumetric(values.volumeTemperatureDerivative(), values.volumePressureDerivative());
        double idealHeatCapacity = 0.0;
        for (int i = 0; i < n; i++) {
            double x = w.composition[i];
            if (x > 0.0) idealHeatCapacity += x * idealGas[i].heatCapacity(temperature);
        }
        // The residual-only model's heat capacity is the residual c_p; the whole c_p must be positive.
        double heatCapacity = idealHeatCapacity + values.heatCapacity();
        if (!(heatCapacity > 0.0) || !Double.isFinite(heatCapacity)) {
            throw new IllegalArgumentException("Invalid phase heat capacity " + heatCapacity);
        }
        out.setCaloric(heatCapacity, values.enthalpyPressureDerivative());
        return out;
    }

    /**
     * The carried component, with the largest amount, whose ideal-gas function does not cover {@code temperature};
     * {@code null} when every carried component is covered. The amounts must already be valid.
     */
    public ThermoDomainViolation idealGasViolation(double temperature, double[] amounts) {
        int worst = -1;
        double largest = -1.0;
        for (int i = 0; i < idealGas.length; i++) {
            double amount = amounts[i];
            if (amount > 0.0 && (temperature < idealGas[i].minimumTemperature()
                    || temperature > idealGas[i].maximumTemperature()) && amount > largest) {
                worst = i;
                largest = amount;
            }
        }
        if (worst < 0) return null;
        return new ThermoDomainViolation(sourceId, names.get(worst), ThermoDomainViolation.Property.TEMPERATURE,
                temperature, idealGas[worst].minimumTemperature(), idealGas[worst].maximumTemperature());
    }

    private Workspace own(PhaseEvaluator.Workspace workspace) {
        if (!(Objects.requireNonNull(workspace, "workspace") instanceof Workspace w) || w.owner != this) {
            throw new IllegalArgumentException("Workspace belongs to another evaluator");
        }
        return w;
    }

    /** Validates the state, normalises the composition exactly as the kernel does, and prepares the temperature. */
    private void prepare(double temperature, double pressure, double[] amounts, PhaseRoot preference, Workspace w) {
        Objects.requireNonNull(preference, "preference");
        if (!Double.isFinite(temperature) || !(temperature > 0.0) || !Double.isFinite(pressure) || !(pressure > 0.0)) {
            throw new IllegalArgumentException("Positive finite temperature and pressure required");
        }
        if (amounts == null || amounts.length != names.size()) throw new IllegalArgumentException("Composition basis mismatch");
        double total = 0.0;
        for (double amount : amounts) {
            if (!Double.isFinite(amount) || amount < 0.0) throw new IllegalArgumentException("Invalid composition");
            total += amount;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("Composition has no material");
        for (int i = 0; i < amounts.length; i++) w.composition[i] = amounts[i] / total;
        ThermoDomainViolation violation = idealGasViolation(temperature, amounts);
        if (violation != null) throw violation;
        if (w.terms.temperature() != temperature) eos.prepare(temperature, w.terms);
    }

    private void fill(double temperature, double pressure, PhaseRoot preference, TranslatedPengRobinson.Values values,
                      Workspace w, PhaseState out) {
        double rt = R * temperature;
        double[] logPhi = values.logFugacityCoefficientsView();
        double idealEnthalpy = 0.0;
        double idealEntropy = 0.0;
        double gibbs = 0.0;
        double weightedLogPhi = 0.0;
        for (int i = 0; i < names.size(); i++) {
            double x = w.composition[i];
            if (!(x > 0.0)) {
                w.idealGibbs[i] = Double.NaN;
                continue;
            }
            double h = idealGas[i].enthalpy(temperature);
            double s = idealGas[i].entropy(temperature, pressure);
            double g = h - temperature * s;
            double logX = Math.log(x);
            w.idealGibbs[i] = g;
            idealEnthalpy += x * h;
            idealEntropy += x * (s - R * logX);
            gibbs += x * (g + rt * (logX + logPhi[i]));
            weightedLogPhi += x * logPhi[i];
        }
        // Residual-only model: its enthalpy is h^R + P c exactly, and g^R = R T sum x ln phi (translation included).
        double residualEnthalpy = values.molarEnthalpy();
        double residualEntropy = (residualEnthalpy - rt * weightedLogPhi) / temperature;
        PhaseKind kind = values.physicalRootCount() > 1
                ? (preference == PhaseRoot.VAPOR ? PhaseKind.VAPOR : PhaseKind.LIQUID)
                : PhaseKind.FLUID;
        out.set(kind, null, preference, values.physicalRootCount(), temperature, pressure, values.molarVolume(),
                idealEnthalpy, residualEnthalpy, idealEntropy, residualEntropy, gibbs, w.composition, logPhi,
                w.idealGibbs);
    }
}
