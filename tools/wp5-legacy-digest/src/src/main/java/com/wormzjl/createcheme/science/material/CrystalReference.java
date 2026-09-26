package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * A pure chemical crystal (a {@code materials/crystals/<species>_<crystal>.json} record), distinct from the inert
 * particles of {@code solids/}: solid heat capacity, molar volume with thermal expansion and compressibility,
 * solid-solid transitions, and the triple point it is anchored at. Plan section 4 (D1, D2): the solid's Gibbs energy
 * is anchored at the triple point to the fluid model, so g_s(T_tp, P_tp) = mu_liquid(T_tp, P_tp) and
 * h_s(T_tp, P_tp) = h_liquid(T_tp, P_tp) - dH_fus; the caller supplies the fluid values, this object supplies
 * everything relative to the anchor.
 *
 * <p>Model. Cp(T) from the heat-capacity segments (polynomials in T, or tables interpolated linearly), taken as
 * pressure-independent; v(T, P) = v0 [1 + alpha (T - T0) - kappa (P - P0)] + the volume changes of the transitions
 * between T0 and T, which is thermodynamically consistent with a pressure-independent Cp (d2v/dT2 = 0). A transition
 * adds its enthalpy and dH/T to h and s once T is above it; its temperature is taken as pressure-independent (a
 * limit of this form: the Clapeyron slope of a transition is not represented).</p>
 *
 * <p>A record whose data do not reach its anchor, or that lacks the expansion, compressibility or volume range, is
 * loaded only with the grade research_only or unavailable, and every evaluation refuses it
 * ({@link IllegalStateException} naming what is missing): missing data are never filled with guesses.</p>
 */
public final class CrystalReference {
    public record Transition(double temperature, double enthalpy, double volumeChange, String toCrystal) {}
    public record Anchor(double temperature, double pressure, double fusionEnthalpy, boolean estimated, String source) {}

    private final String id;
    private final String component;
    private final String crystal;
    private final String revision;
    private final String source;
    private final MaterialCoverage coverage;
    private final List<String> missing;
    // Heat capacity: segments over [cpMinimum, cpMaximum]; cumulative integrals from cpMinimum to each segment start.
    private final CpSegment[] cp;
    private final double[] cumulativeEnthalpy;
    private final double[] cumulativeEntropy;
    // Molar volume; NaN marks a field the record does not carry.
    private final double volumeTemperature, volumePressure, volume, expansion, compressibility;
    private final double volumeMinimumTemperature, volumeMaximumTemperature, minimumPressure, maximumPressure;
    private final List<Transition> transitions;
    private final double[] transitionTemperature, transitionEnthalpy, transitionVolume;
    private final Anchor anchor;
    private final double minimumTemperature, maximumTemperature;
    private final Object science;

    private CrystalReference(JsonObject o) {
        id = MaterialCatalog.string(o, "id");
        component = MaterialCatalog.string(o, "component");
        crystal = MaterialCatalog.string(o, "crystal");
        revision = ReferenceSpine.revision(o, "revision");
        source = MaterialCatalog.string(o, "source");
        coverage = MaterialCoverage.read(o);
        var gaps = new ArrayList<String>();

        JsonArray segments = MaterialCatalog.array(MaterialCatalog.object(o, "heat_capacity"), "segments");
        if (segments.isEmpty() || segments.size() > 16) throw new IllegalArgumentException("heat_capacity.segments: expected 1 to 16 segments");
        cp = new CpSegment[segments.size()];
        for (int k = 0; k < cp.length; k++) {
            try {
                JsonElement element = segments.get(k);
                if (!element.isJsonObject()) throw new IllegalArgumentException("expected object");
                cp[k] = CpSegment.read(element.getAsJsonObject());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("heat_capacity.segments[" + k + "]: " + invalid.getMessage(), invalid);
            }
            if (k > 0 && cp[k].minimum < cp[k - 1].maximum)
                throw new IllegalArgumentException("heat_capacity.segments[" + k + "]: overlaps segment " + (k - 1));
            if (k > 0 && cp[k].minimum > cp[k - 1].maximum)
                throw new IllegalArgumentException("heat_capacity.segments[" + k + "]: gap after segment " + (k - 1)
                        + " (" + cp[k - 1].maximum + " to " + cp[k].minimum + " K)");
        }
        cumulativeEnthalpy = new double[cp.length];
        cumulativeEntropy = new double[cp.length];
        for (int k = 1; k < cp.length; k++) {
            cumulativeEnthalpy[k] = cumulativeEnthalpy[k - 1] + cp[k - 1].enthalpyIntegral(cp[k - 1].maximum);
            cumulativeEntropy[k] = cumulativeEntropy[k - 1] + cp[k - 1].entropyIntegral(cp[k - 1].maximum);
        }
        double cpMinimum = cp[0].minimum, cpMaximum = cp[cp.length - 1].maximum;

        JsonObject v = MaterialCatalog.object(o, "molar_volume");
        MaterialCatalog.string(v, "source");
        volumeTemperature = MaterialCatalog.positive(v, "reference_temperature_kelvin");
        volumePressure = MaterialCatalog.positive(v, "reference_pressure_pascal");
        volume = MaterialCatalog.positive(v, "value_m3_per_mol");
        expansion = optional(v, "thermal_expansion_per_kelvin", false, gaps);
        compressibility = optional(v, "isothermal_compressibility_per_pascal", true, gaps);
        volumeMinimumTemperature = optional(v, "temperature_min_kelvin", true, gaps);
        volumeMaximumTemperature = optional(v, "temperature_max_kelvin", true, gaps);
        minimumPressure = optional(v, "pressure_min_pascal", true, gaps);
        maximumPressure = optional(v, "pressure_max_pascal", true, gaps);
        if (volumeMaximumTemperature <= volumeMinimumTemperature || maximumPressure <= minimumPressure)
            throw new IllegalArgumentException("molar_volume: invalid temperature or pressure range");
        if (volumeTemperature < volumeMinimumTemperature || volumeTemperature > volumeMaximumTemperature
                || volumePressure < minimumPressure || volumePressure > maximumPressure)
            throw new IllegalArgumentException("molar_volume: reference state lies outside its declared range");

        var list = new ArrayList<Transition>();
        for (JsonElement element : MaterialCatalog.array(o, "transitions")) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("transitions: expected objects");
            JsonObject t = element.getAsJsonObject();
            var transition = new Transition(MaterialCatalog.positive(t, "temperature_kelvin"), MaterialCatalog.positive(t, "enthalpy_j_per_mol"),
                    MaterialCatalog.number(t, "volume_change_m3_per_mol"), MaterialCatalog.string(t, "to_crystal"));
            if (!(transition.temperature() > cpMinimum && transition.temperature() < cpMaximum))
                throw new IllegalArgumentException("transitions: " + transition.temperature() + " K lies outside the heat-capacity range "
                        + cpMinimum + ".." + cpMaximum + " K");
            if (!list.isEmpty() && transition.temperature() <= list.getLast().temperature())
                throw new IllegalArgumentException("transitions: temperatures must increase");
            list.add(transition);
        }
        transitions = List.copyOf(list);
        transitionTemperature = new double[list.size()]; transitionEnthalpy = new double[list.size()]; transitionVolume = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            transitionTemperature[i] = list.get(i).temperature();
            transitionEnthalpy[i] = list.get(i).enthalpy();
            transitionVolume[i] = list.get(i).volumeChange();
        }

        JsonObject a = MaterialCatalog.object(o, "anchor");
        if (!MaterialCatalog.string(a, "type").equals("triple_point")) throw new IllegalArgumentException("anchor.type: expected triple_point");
        anchor = new Anchor(MaterialCatalog.positive(a, "temperature_kelvin"), MaterialCatalog.positive(a, "pressure_pascal"),
                MaterialCatalog.positive(a, "fusion_enthalpy_j_per_mol"), a.has("estimated") && MaterialCatalog.bool(a, "estimated"),
                MaterialCatalog.string(a, "source"));

        minimumTemperature = Double.isNaN(volumeMinimumTemperature) ? cpMinimum : Math.max(cpMinimum, volumeMinimumTemperature);
        maximumTemperature = Double.isNaN(volumeMaximumTemperature) ? cpMaximum : Math.min(cpMaximum, volumeMaximumTemperature);
        if (!(anchor.temperature() >= cpMinimum && anchor.temperature() <= cpMaximum))
            gaps.add("heat capacity " + cpMinimum + ".." + cpMaximum + " K does not reach the triple-point anchor at " + anchor.temperature() + " K");
        if (!Double.isNaN(volumeMinimumTemperature) && !(anchor.temperature() >= volumeMinimumTemperature && anchor.temperature() <= volumeMaximumTemperature))
            gaps.add("molar volume range " + volumeMinimumTemperature + ".." + volumeMaximumTemperature + " K does not reach the anchor");
        if (!Double.isNaN(minimumPressure) && !(anchor.pressure() >= minimumPressure && anchor.pressure() <= maximumPressure))
            gaps.add("molar volume pressure range does not reach the anchor pressure " + anchor.pressure() + " Pa");
        if (!Double.isNaN(volumeMinimumTemperature) && !Double.isNaN(volumeMaximumTemperature) && !(maximumTemperature > minimumTemperature))
            gaps.add("heat-capacity and molar-volume ranges do not overlap");
        missing = List.copyOf(gaps);
        if (!missing.isEmpty() && (coverage.grade() == MaterialCoverage.Grade.QUALIFIED || coverage.grade() == MaterialCoverage.Grade.ESTIMATED_DECLARED_ERROR))
            throw new IllegalArgumentException("coverage.grade: an incomplete crystal record must be research_only or unavailable; missing " + missing);

        var cpScience = new ArrayList<Object>();
        for (CpSegment segment : cp) cpScience.add(segment.science());
        var transitionScience = new ArrayList<Object>();
        for (Transition t : transitions) transitionScience.add(List.of(t.temperature(), t.enthalpy(), t.volumeChange(), t.toCrystal()));
        science = List.of(component, crystal, cpScience,
                List.of(volumeTemperature, volumePressure, volume, present(expansion), present(compressibility), present(volumeMinimumTemperature),
                        present(volumeMaximumTemperature), present(minimumPressure), present(maximumPressure)),
                transitionScience, List.of(anchor.temperature(), anchor.pressure(), anchor.fusionEnthalpy()));
    }

    static CrystalReference read(JsonObject o) { return new CrystalReference(o); }

    private static Object present(double value) { return Double.isNaN(value) ? "absent" : value; }

    private static double optional(JsonObject o, String key, boolean positive, List<String> gaps) {
        if (!o.has(key)) { gaps.add("molar_volume." + key + " absent"); return Double.NaN; }
        return positive ? MaterialCatalog.positive(o, key) : MaterialCatalog.number(o, key);
    }

    public String id() { return id; }
    public String component() { return component; }
    public String crystal() { return crystal; }
    public String revision() { return revision; }
    public String source() { return source; }
    public MaterialCoverage coverage() { return coverage; }
    public Anchor anchor() { return anchor; }
    public List<Transition> transitions() { return transitions; }
    /** Whether the record carries everything the anchored Gibbs function needs; see {@link #missing()}. */
    public boolean usable() { return missing.isEmpty(); }
    /** What an unusable record lacks; empty for a usable one. */
    public List<String> missing() { return missing; }
    public double minimumTemperature() { return minimumTemperature; }
    public double maximumTemperature() { return maximumTemperature; }
    /** Numeric content for the package spine fingerprint. */
    Object science() { return science; }

    private void check(double t, double p) {
        if (!missing.isEmpty()) throw new IllegalStateException(id + ": crystal record is not usable, missing " + missing);
        if (!(t >= minimumTemperature && t <= maximumTemperature))
            throw new IllegalArgumentException(id + ": temperature " + t + " K outside " + minimumTemperature + ".." + maximumTemperature + " K");
        if (!(p >= minimumPressure && p <= maximumPressure))
            throw new IllegalArgumentException(id + ": pressure " + p + " Pa outside " + minimumPressure + ".." + maximumPressure + " Pa");
    }

    private int segment(double t) {
        for (int k = 0; k < cp.length - 1; k++) if (t <= cp[k].maximum) return k;
        return cp.length - 1;
    }

    private double enthalpyFromStart(double t) { int k = segment(t); return cumulativeEnthalpy[k] + cp[k].enthalpyIntegral(t); }

    private double entropyFromStart(double t) { int k = segment(t); return cumulativeEntropy[k] + cp[k].entropyIntegral(t); }

    /** Transition sums between {@code from} and {@code to}: a transition counts once T is strictly above it. */
    private double transitionSum(double[] values, double from, double to, boolean perTemperature) {
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            double t = transitionTemperature[i];
            int crossed = (t < to ? 1 : 0) - (t < from ? 1 : 0);
            if (crossed != 0) sum += crossed * (perTemperature ? values[i] / t : values[i]);
        }
        return sum;
    }

    /** Temperature-dependent part of the volume at the reference pressure, m3/mol. */
    private double baseVolume(double t) {
        return volume * (1 + expansion * (t - volumeTemperature)) + transitionSum(transitionVolume, volumeTemperature, t, false);
    }

    private double pressureEnthalpy(double t, double p) {
        double dp = p - volumePressure;
        return (baseVolume(t) - t * volume * expansion) * dp - 0.5 * volume * compressibility * dp * dp;
    }

    private double pressureEntropy(double p) { return -volume * expansion * (p - volumePressure); }

    /** Solid isobaric heat capacity, J/(mol K). */
    public double heatCapacity(double temperatureKelvin, double pressurePascal) {
        check(temperatureKelvin, pressurePascal);
        return cp[segment(temperatureKelvin)].heatCapacity(temperatureKelvin);
    }

    /** Solid molar volume, m3/mol. */
    public double molarVolume(double temperatureKelvin, double pressurePascal) {
        check(temperatureKelvin, pressurePascal);
        return baseVolume(temperatureKelvin) - volume * compressibility * (pressurePascal - volumePressure);
    }

    /** h_s(T, P) - h_s(T_tp, P_tp), J/mol. */
    public double enthalpyChange(double temperatureKelvin, double pressurePascal) {
        check(temperatureKelvin, pressurePascal);
        double tt = anchor.temperature();
        return enthalpyFromStart(temperatureKelvin) - enthalpyFromStart(tt) + transitionSum(transitionEnthalpy, tt, temperatureKelvin, false)
                + pressureEnthalpy(temperatureKelvin, pressurePascal) - pressureEnthalpy(tt, anchor.pressure());
    }

    /** s_s(T, P) - s_s(T_tp, P_tp), J/(mol K). */
    public double entropyChange(double temperatureKelvin, double pressurePascal) {
        check(temperatureKelvin, pressurePascal);
        double tt = anchor.temperature();
        return entropyFromStart(temperatureKelvin) - entropyFromStart(tt) + transitionSum(transitionEnthalpy, tt, temperatureKelvin, true)
                + pressureEntropy(pressurePascal) - pressureEntropy(anchor.pressure());
    }

    /**
     * g_s(T, P) - g_s(T_tp, P_tp), J/mol, given the solid's absolute entropy at the anchor (see
     * {@link #anchorEntropy}): the difference depends on it through -(T - T_tp) s_s(T_tp, P_tp).
     */
    public double gibbsChange(double temperatureKelvin, double pressurePascal, double anchorEntropy) {
        return enthalpyChange(temperatureKelvin, pressurePascal) - temperatureKelvin * entropyChange(temperatureKelvin, pressurePascal)
                - (temperatureKelvin - anchor.temperature()) * anchorEntropy;
    }

    /**
     * The solid's entropy at the anchor from the fluid model's liquid there: s_s = s_L - dH_fus / T_tp with
     * s_L = (h_L - mu_L) / T_tp.
     */
    public double anchorEntropy(double liquidChemicalPotential, double liquidEnthalpy) {
        if (!missing.isEmpty()) throw new IllegalStateException(id + ": crystal record is not usable, missing " + missing);
        double t = anchor.temperature();
        return (liquidEnthalpy - liquidChemicalPotential) / t - anchor.fusionEnthalpy() / t;
    }

    /** Absolute solid Gibbs energy, J/mol, anchored at the triple point to the fluid model's liquid mu_L and h_L. */
    public double gibbs(double temperatureKelvin, double pressurePascal, double liquidChemicalPotential, double liquidEnthalpy) {
        return liquidChemicalPotential + gibbsChange(temperatureKelvin, pressurePascal, anchorEntropy(liquidChemicalPotential, liquidEnthalpy));
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** One heat-capacity segment; integrals are taken from the segment's own minimum. */
    private abstract static class CpSegment {
        final double minimum;
        final double maximum;

        CpSegment(JsonObject o) {
            minimum = MaterialCatalog.positive(o, "temperature_min_kelvin");
            maximum = MaterialCatalog.positive(o, "temperature_max_kelvin");
            if (!(maximum > minimum) || !Double.isFinite(maximum)) throw new IllegalArgumentException("temperature: invalid range");
            MaterialCatalog.string(o, "source");
            if (o.has("estimated")) MaterialCatalog.bool(o, "estimated");
        }

        static CpSegment read(JsonObject o) {
            String type = MaterialCatalog.string(o, "type");
            CpSegment segment = switch (type) {
                case "polynomial" -> new Polynomial(o);
                case "table" -> new Table(o);
                default -> throw new IllegalArgumentException("type: unsupported heat-capacity segment " + type + " (expected polynomial or table)");
            };
            for (double t : new double[] {segment.minimum, 0.5 * (segment.minimum + segment.maximum), segment.maximum})
                if (!(segment.heatCapacity(t) > 0)) throw new IllegalArgumentException("heat capacity at " + t + " K is not positive");
            return segment;
        }

        abstract double heatCapacity(double t);
        /** Integral of Cp from the segment minimum to t. */
        abstract double enthalpyIntegral(double t);
        /** Integral of Cp/T from the segment minimum to t. */
        abstract double entropyIntegral(double t);
        abstract Object science();
    }

    /** Cp = sum c_k T^k, k = 0.. (J/(mol K), T in K). */
    private static final class Polynomial extends CpSegment {
        private final double[] c;

        Polynomial(JsonObject o) {
            super(o);
            c = ReferenceSpine.doubles(o, "coefficients", 1, 8);
        }

        @Override double heatCapacity(double t) {
            double sum = 0;
            for (int k = c.length - 1; k >= 0; k--) sum = sum * t + c[k];
            return sum;
        }

        @Override double enthalpyIntegral(double t) {
            double sum = 0;
            for (int k = 0; k < c.length; k++) sum += c[k] * (Math.pow(t, k + 1) - Math.pow(minimum, k + 1)) / (k + 1);
            return sum;
        }

        @Override double entropyIntegral(double t) {
            double sum = c[0] * Math.log(t / minimum);
            for (int k = 1; k < c.length; k++) sum += c[k] * (Math.pow(t, k) - Math.pow(minimum, k)) / k;
            return sum;
        }

        @Override Object science() {
            var list = new ArrayList<Double>();
            for (double v : c) list.add(v);
            return List.of("polynomial", minimum, maximum, list);
        }
    }

    /** Tabulated Cp, linear between the points; the first and last points are the segment's bounds. */
    private static final class Table extends CpSegment {
        private final double[] t;
        private final double[] value;
        private final double[] enthalpyAtNode;
        private final double[] entropyAtNode;

        Table(JsonObject o) {
            super(o);
            t = ReferenceSpine.doubles(o, "temperatures_kelvin", 2, 256);
            value = ReferenceSpine.doubles(o, "values_j_per_mol_kelvin", t.length, t.length);
            if (t[0] != minimum || t[t.length - 1] != maximum)
                throw new IllegalArgumentException("temperatures_kelvin: must start at temperature_min_kelvin and end at temperature_max_kelvin");
            for (int i = 0; i < t.length; i++) {
                if (i > 0 && !(t[i] > t[i - 1])) throw new IllegalArgumentException("temperatures_kelvin: must increase");
                if (!(value[i] > 0)) throw new IllegalArgumentException("values_j_per_mol_kelvin: must be positive");
            }
            enthalpyAtNode = new double[t.length];
            entropyAtNode = new double[t.length];
            for (int i = 1; i < t.length; i++) {
                enthalpyAtNode[i] = enthalpyAtNode[i - 1] + piece(i - 1, t[i], false);
                entropyAtNode[i] = entropyAtNode[i - 1] + piece(i - 1, t[i], true);
            }
        }

        private int interval(double x) {
            for (int i = 0; i < t.length - 2; i++) if (x <= t[i + 1]) return i;
            return t.length - 2;
        }

        private double piece(int i, double x, boolean entropy) {
            double slope = (value[i + 1] - value[i]) / (t[i + 1] - t[i]);
            if (!entropy) return value[i] * (x - t[i]) + 0.5 * slope * (x - t[i]) * (x - t[i]);
            return (value[i] - slope * t[i]) * Math.log(x / t[i]) + slope * (x - t[i]);
        }

        @Override double heatCapacity(double x) {
            int i = interval(x);
            return value[i] + (value[i + 1] - value[i]) / (t[i + 1] - t[i]) * (x - t[i]);
        }

        @Override double enthalpyIntegral(double x) { int i = interval(x); return enthalpyAtNode[i] + piece(i, x, false); }

        @Override double entropyIntegral(double x) { int i = interval(x); return entropyAtNode[i] + piece(i, x, true); }

        @Override Object science() {
            var temperatures = new ArrayList<Double>();
            var values = new ArrayList<Double>();
            for (int i = 0; i < t.length; i++) { temperatures.add(t[i]); values.add(value[i]); }
            return List.of("table", minimum, maximum, temperatures, values);
        }
    }
}
