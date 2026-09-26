package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wormzjl.createcheme.science.thermo.IdealGasFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reference spine of one species (a {@code materials/spine/<species>.json} record): its formation enthalpy,
 * standard entropy and bounded ideal-gas heat capacity, the E-PPR78 group decomposition and the declared coverage.
 * Plan section 4 and decision D6 of batch 2026-09-24-coolprop-low-temperature.
 *
 * <p>The heat capacity is a list of ordered, contiguous segments, each with its own source: CoolProp's ideal-gas
 * Helmholtz terms ({@code helmholtz_ideal_terms}), a NASA 9-coefficient polynomial ({@code nasa9}, the NASA CEA form)
 * or a NASA 7-coefficient polynomial ({@code nasa7}). Each segment's Cp is integrated in closed form; the integration
 * constants are fixed at load so that h(298.15 K) equals the formation enthalpy and s(298.15 K, 1e5 Pa) the standard
 * entropy in the segment holding 298.15 K, and are chained across the joins so h and s are continuous. The CEA
 * constants b1 and b2 are carried for provenance and not used. The Cp step at each join is measured at load and a
 * step above {@code maximum_cp_step_fraction} (default 0.005) refuses the record. A call outside the segments is
 * refused; the function never extrapolates.</p>
 *
 * <p>Evaluation is allocation-free and deterministic: a linear scan over at most 16 segments and closed-form sums over
 * primitive arrays. At a join the lower segment is used; the upper one agrees there by construction.</p>
 */
public final class ReferenceSpine implements IdealGasFunction {
    /** Temperature of the formation-enthalpy and standard-entropy anchor, K. */
    public static final double REFERENCE_TEMPERATURE = 298.15;
    /** Standard-state pressure of the entropy, Pa. */
    public static final double REFERENCE_PRESSURE = 100000;
    /**
     * Molar gas constant, CODATA 2018 (exact in SI), for the pressure term -R ln(P/P0) only, so the term is the same in
     * every segment. Each segment scales its own Cp with the gas constant its coefficients were fitted with.
     */
    public static final double GAS_CONSTANT = 8.31446261815324;
    public static final double DEFAULT_MAXIMUM_CP_STEP_FRACTION = 0.005;
    private static final int MAXIMUM_SEGMENTS = 16;

    /** A reference value with its stated uncertainty ({@code NaN} when the source states none) and its source. */
    public record ReferenceValue(double value, double uncertainty, String source) {
        public boolean uncertaintyStated() { return !Double.isNaN(uncertainty); }
    }

    /**
     * One declared segment, for reports and tests. {@code coverage} is the segment's own grade when the record declares
     * one (for example an extension of a reference equation's ideal part below its triple point), {@code null} when the
     * record's grade applies ({@link #coverageAt}).
     */
    public record SegmentInfo(String type, double minimumTemperature, double maximumTemperature, double gasConstant,
            String source, String revision, MaterialCoverage coverage) {}

    /** Both sides of a join, evaluated at load (entropies at 1e5 Pa). */
    public record Join(double temperature, double lowerHeatCapacity, double upperHeatCapacity, double lowerEnthalpy,
            double upperEnthalpy, double lowerEntropy, double upperEntropy) {
        /** |Cp_upper - Cp_lower| / Cp_lower at the join. */
        public double heatCapacityStepFraction() { return Math.abs(upperHeatCapacity - lowerHeatCapacity) / lowerHeatCapacity; }
    }

    private final String id;
    private final String component;
    private final String revision;
    private final String source;
    private final double molarMass;
    private final ReferenceValue formationEnthalpy;
    private final ReferenceValue standardEntropy;
    private final double maximumCpStepFraction;
    private final Segment[] segments;
    private final double[] referenceTemperature;
    private final double[] rawEnthalpyAtReference;
    private final double[] rawEntropyAtReference;
    private final double[] enthalpyBase;
    private final double[] entropyBase;
    private final double minimumTemperature;
    private final double maximumTemperature;
    private final List<Join> joins;
    private final List<SegmentInfo> segmentInfo;
    private final String groupScheme;
    private final Map<String, Integer> groups;
    private final MaterialCoverage coverage;
    private final Object science;

    private ReferenceSpine(String id, String component, String revision, String source, double molarMass,
            ReferenceValue formationEnthalpy, ReferenceValue standardEntropy, double maximumCpStepFraction,
            Segment[] segments, String groupScheme, Map<String, Integer> groups, MaterialCoverage coverage) {
        this.id = id; this.component = component; this.revision = revision; this.source = source;
        this.molarMass = molarMass; this.formationEnthalpy = formationEnthalpy; this.standardEntropy = standardEntropy;
        this.maximumCpStepFraction = maximumCpStepFraction; this.segments = segments;
        this.groupScheme = groupScheme; this.groups = groups; this.coverage = coverage;
        int n = segments.length;
        minimumTemperature = segments[0].minimum;
        maximumTemperature = segments[n - 1].maximum;
        if (!(REFERENCE_TEMPERATURE >= minimumTemperature && REFERENCE_TEMPERATURE <= maximumTemperature))
            throw new IllegalArgumentException("ideal_gas.segments: must contain 298.15 K, the formation-enthalpy anchor; they cover "
                    + minimumTemperature + ".." + maximumTemperature + " K");
        referenceTemperature = new double[n]; rawEnthalpyAtReference = new double[n]; rawEntropyAtReference = new double[n];
        enthalpyBase = new double[n]; entropyBase = new double[n];
        int anchor = locate(REFERENCE_TEMPERATURE);
        referenceTemperature[anchor] = REFERENCE_TEMPERATURE;
        enthalpyBase[anchor] = formationEnthalpy.value();
        entropyBase[anchor] = standardEntropy.value();
        fixReference(anchor);
        for (int k = anchor + 1; k < n; k++) { // chain upward from the lower neighbour's value at the join
            double join = segments[k].minimum;
            referenceTemperature[k] = join;
            enthalpyBase[k] = segmentEnthalpy(k - 1, join);
            entropyBase[k] = segmentEntropy(k - 1, join);
            fixReference(k);
        }
        for (int k = anchor - 1; k >= 0; k--) { // chain downward from the upper neighbour's value at the join
            double join = segments[k].maximum;
            referenceTemperature[k] = join;
            enthalpyBase[k] = segmentEnthalpy(k + 1, join);
            entropyBase[k] = segmentEntropy(k + 1, join);
            fixReference(k);
        }
        var measured = new ArrayList<Join>();
        for (int k = 0; k + 1 < n; k++) {
            double join = segments[k].maximum;
            var j = new Join(join, segments[k].heatCapacity(join), segments[k + 1].heatCapacity(join),
                    segmentEnthalpy(k, join), segmentEnthalpy(k + 1, join), segmentEntropy(k, join), segmentEntropy(k + 1, join));
            if (!(j.heatCapacityStepFraction() <= maximumCpStepFraction))
                throw new IllegalArgumentException("ideal_gas: Cp step " + j.heatCapacityStepFraction() + " (" + j.lowerHeatCapacity() + " to "
                        + j.upperHeatCapacity() + " J/(mol K)) at the join at " + join + " K exceeds maximum_cp_step_fraction " + maximumCpStepFraction);
            measured.add(j);
        }
        joins = List.copyOf(measured);
        var info = new ArrayList<SegmentInfo>();
        for (Segment s : segments) info.add(new SegmentInfo(s.type, s.minimum, s.maximum, s.gasConstant, s.source, s.revision, s.coverage));
        segmentInfo = List.copyOf(info);
        var segmentScience = new ArrayList<Object>();
        for (Segment s : segments) segmentScience.add(s.science());
        science = List.of(component, molarMass, formationEnthalpy.value(), standardEntropy.value(), maximumCpStepFraction,
                segmentScience, groupScheme, new TreeMap<>(groups));
    }

    private void fixReference(int k) {
        rawEnthalpyAtReference[k] = segments[k].enthalpy(referenceTemperature[k]);
        rawEntropyAtReference[k] = segments[k].entropy(referenceTemperature[k]);
    }

    private double segmentEnthalpy(int k, double t) { return enthalpyBase[k] + (segments[k].enthalpy(t) - rawEnthalpyAtReference[k]); }

    private double segmentEntropy(int k, double t) { return entropyBase[k] + (segments[k].entropy(t) - rawEntropyAtReference[k]); }

    private int locate(double t) {
        for (int k = 0; k < segments.length - 1; k++) if (t <= segments[k].maximum) return k;
        return segments.length - 1;
    }

    private int segment(double t) {
        if (!(t >= minimumTemperature && t <= maximumTemperature))
            throw new IllegalArgumentException(id + ": temperature " + t + " K outside the ideal-gas segments "
                    + minimumTemperature + ".." + maximumTemperature + " K");
        return locate(t);
    }

    @Override public double heatCapacity(double temperatureKelvin) {
        return segments[segment(temperatureKelvin)].heatCapacity(temperatureKelvin);
    }

    @Override public double enthalpy(double temperatureKelvin) {
        return segmentEnthalpy(segment(temperatureKelvin), temperatureKelvin);
    }

    @Override public double entropy(double temperatureKelvin, double pressurePascal) {
        int k = segment(temperatureKelvin);
        if (!(pressurePascal > 0) || !Double.isFinite(pressurePascal))
            throw new IllegalArgumentException(id + ": pressure " + pressurePascal + " Pa must be positive and finite");
        return segmentEntropy(k, temperatureKelvin) - GAS_CONSTANT * Math.log(pressurePascal / REFERENCE_PRESSURE);
    }

    @Override public double minimumTemperature() { return minimumTemperature; }
    @Override public double maximumTemperature() { return maximumTemperature; }
    @Override public String revision() { return revision; }

    public String id() { return id; }
    public String component() { return component; }
    public String source() { return source; }
    public double molarMass() { return molarMass; }
    public ReferenceValue formationEnthalpy() { return formationEnthalpy; }
    public ReferenceValue standardEntropy() { return standardEntropy; }
    public double maximumCpStepFraction() { return maximumCpStepFraction; }
    public List<SegmentInfo> segments() { return segmentInfo; }
    public List<Join> joins() { return joins; }
    public String groupScheme() { return groupScheme; }
    /** E-PPR78 group counts by group name, in record order. */
    public Map<String, Integer> groups() { return groups; }
    public MaterialCoverage coverage() { return coverage; }
    /**
     * The coverage at a temperature: the grade of the segment holding it when that segment declares its own, else the
     * record's. At a join the lower segment's applies, as for the evaluation.
     */
    public MaterialCoverage coverageAt(double temperatureKelvin) {
        var own = segments[segment(temperatureKelvin)].coverage;
        return own == null ? coverage : own;
    }
    /** Numeric content for the package spine fingerprint: no id, revision, source or coverage text. */
    Object science() { return science; }

    // ---------------------------------------------------------------------------------------------------------------
    // Loading

    static ReferenceSpine read(JsonObject o) {
        String id = MaterialCatalog.string(o, "id"), component = MaterialCatalog.string(o, "component");
        String revision = revision(o, "revision"), source = MaterialCatalog.string(o, "source");
        double molarMass = MaterialCatalog.positive(o, "molar_mass_kg_per_mol");
        JsonObject formation = MaterialCatalog.object(o, "formation_enthalpy");
        if (MaterialCatalog.number(formation, "temperature_kelvin") != REFERENCE_TEMPERATURE)
            throw new IllegalArgumentException("formation_enthalpy.temperature_kelvin must be 298.15");
        var formationEnthalpy = new ReferenceValue(MaterialCatalog.number(formation, "value_j_per_mol"),
                nonNegative(formation, "uncertainty_j_per_mol"), MaterialCatalog.string(formation, "source"));
        JsonObject entropy = MaterialCatalog.object(o, "standard_entropy");
        if (MaterialCatalog.number(entropy, "pressure_pascal") != REFERENCE_PRESSURE)
            throw new IllegalArgumentException("standard_entropy.pressure_pascal must be 100000");
        var standardEntropy = new ReferenceValue(MaterialCatalog.positive(entropy, "value_j_per_mol_kelvin"),
                entropy.has("uncertainty_j_per_mol_kelvin") ? nonNegative(entropy, "uncertainty_j_per_mol_kelvin") : Double.NaN,
                MaterialCatalog.string(entropy, "source"));
        JsonObject idealGas = MaterialCatalog.object(o, "ideal_gas");
        double step = idealGas.has("maximum_cp_step_fraction") ? MaterialCatalog.number(idealGas, "maximum_cp_step_fraction") : DEFAULT_MAXIMUM_CP_STEP_FRACTION;
        if (!(step > 0 && step < 1)) throw new IllegalArgumentException("ideal_gas.maximum_cp_step_fraction must lie in (0, 1)");
        JsonArray list = MaterialCatalog.array(idealGas, "segments");
        if (list.isEmpty() || list.size() > MAXIMUM_SEGMENTS)
            throw new IllegalArgumentException("ideal_gas.segments: expected 1 to " + MAXIMUM_SEGMENTS + " segments");
        var segments = new Segment[list.size()];
        for (int k = 0; k < segments.length; k++) {
            try {
                JsonElement element = list.get(k);
                if (!element.isJsonObject()) throw new IllegalArgumentException("expected object");
                segments[k] = Segment.read(element.getAsJsonObject());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("ideal_gas.segments[" + k + "]: " + invalid.getMessage(), invalid);
            }
            if (k > 0 && segments[k].minimum < segments[k - 1].maximum)
                throw new IllegalArgumentException("ideal_gas.segments[" + k + "]: overlaps segment " + (k - 1) + " (starts at "
                        + segments[k].minimum + " K, before its end at " + segments[k - 1].maximum + " K)");
            if (k > 0 && segments[k].minimum > segments[k - 1].maximum)
                throw new IllegalArgumentException("ideal_gas.segments[" + k + "]: gap after segment " + (k - 1) + " ("
                        + segments[k - 1].maximum + " to " + segments[k].minimum + " K)");
        }
        JsonObject groups = MaterialCatalog.object(o, "groups");
        String scheme = MaterialCatalog.string(groups, "scheme");
        if (!scheme.equals(Eppr78Groups.SCHEME)) throw new IllegalArgumentException("groups.scheme: expected " + Eppr78Groups.SCHEME + ", got " + scheme);
        JsonObject counts = MaterialCatalog.object(groups, "counts");
        if (counts.isEmpty()) throw new IllegalArgumentException("groups.counts: at least one group required");
        var decomposition = new java.util.LinkedHashMap<String, Integer>();
        for (var entry : counts.entrySet()) {
            Eppr78Groups.require(entry.getKey());
            double count = MaterialCatalog.number(counts, entry.getKey());
            if (count != Math.rint(count) || count < 1 || count > 1000)
                throw new IllegalArgumentException("groups.counts." + entry.getKey() + ": expected a positive integer");
            decomposition.put(entry.getKey(), (int) count);
        }
        return new ReferenceSpine(id, component, revision, source, molarMass, formationEnthalpy, standardEntropy, step,
                segments, scheme, java.util.Collections.unmodifiableMap(decomposition), MaterialCoverage.read(o));
    }

    static String revision(JsonObject o, String key) {
        String value = MaterialCatalog.string(o, key);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:+-]{0,62}"))
            throw new IllegalArgumentException(key + ": expected a stable identifier of at most 63 characters");
        return value;
    }

    static double nonNegative(JsonObject o, String key) {
        double value = MaterialCatalog.number(o, key);
        if (value < 0) throw new IllegalArgumentException(key + ": must not be negative");
        return value;
    }

    static double[] doubles(JsonObject o, String key, int minimum, int maximum) {
        JsonArray values = MaterialCatalog.array(o, key);
        if (values.size() < minimum || values.size() > maximum)
            throw new IllegalArgumentException(key + ": expected " + minimum + " to " + maximum + " numbers");
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            JsonElement e = values.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + ": expected numbers");
            result[i] = e.getAsDouble();
            if (!Double.isFinite(result[i])) throw new IllegalArgumentException(key + ": nonfinite");
        }
        return result;
    }

    private static List<Double> boxed(double[] values) {
        var list = new ArrayList<Double>(values.length);
        for (double v : values) list.add(v);
        return list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Segments: Cp and the closed-form antiderivatives of Cp (enthalpy) and Cp/T (entropy at 1e5 Pa), each up to a
    // constant the loader replaces.

    private abstract static class Segment {
        final String type;
        final double minimum;
        final double maximum;
        final double gasConstant;
        final String source;
        final String revision;
        /** The segment's own coverage (optional {@code coverage} object), {@code null} when the record's applies. */
        final MaterialCoverage coverage;

        Segment(String type, JsonObject o) {
            this.type = type;
            minimum = MaterialCatalog.positive(o, "temperature_min_kelvin");
            maximum = MaterialCatalog.positive(o, "temperature_max_kelvin");
            if (!(maximum > minimum) || !Double.isFinite(maximum)) throw new IllegalArgumentException("temperature: invalid range");
            gasConstant = MaterialCatalog.positive(o, "gas_constant_j_per_mol_kelvin");
            if (Math.abs(gasConstant / GAS_CONSTANT - 1) > 1e-5)
                throw new IllegalArgumentException("gas_constant_j_per_mol_kelvin: " + gasConstant + " is not a molar gas constant in J/(mol K)");
            source = MaterialCatalog.string(o, "source");
            revision = ReferenceSpine.revision(o, "revision");
            coverage = o.has("coverage") ? MaterialCoverage.read(o) : null;
        }

        static Segment read(JsonObject o) {
            String type = MaterialCatalog.string(o, "type");
            Segment segment = switch (type) {
                case "helmholtz_ideal_terms" -> new HelmholtzTerms(o);
                case "nasa9" -> new Nasa9(o);
                case "nasa7" -> new Nasa7(o);
                default -> throw new IllegalArgumentException("type: unsupported ideal-gas segment " + type
                        + " (expected helmholtz_ideal_terms, nasa9 or nasa7)");
            };
            for (double t : new double[] {segment.minimum, 0.5 * (segment.minimum + segment.maximum), segment.maximum}) {
                double cp = segment.heatCapacity(t);
                if (!(cp > 0) || !Double.isFinite(cp)) throw new IllegalArgumentException("heat capacity " + cp + " J/(mol K) at " + t + " K is not positive");
            }
            return segment;
        }

        abstract double heatCapacity(double t);
        abstract double enthalpy(double t);
        abstract double entropy(double t);
        abstract Object science();
    }

    /**
     * CoolProp's ideal-gas Helmholtz families with tau = T_r / T: {@code log_tau} a ln(tau), {@code power} sum n tau^t,
     * {@code planck_einstein} sum n ln(1 - exp(-t tau)) and {@code planck_einstein_function_t} sum n ln(1 - exp(-v tau
     * / T_c)) with the term's own T_c, as CoolProp's parser converts it. The lead and enthalpy-entropy offset terms only
     * shift h and s by constants and are not carried. With x = theta tau:
     * Cp/R = 1 + a - sum n t (t - 1) tau^t + sum n x^2 e^-x / (1 - e^-x)^2;
     * H/(R T) = 1 + a + sum n t tau^t + sum n x e^-x / (1 - e^-x);
     * S/R = (1 + a) ln T + sum n (t - 1) tau^t + sum n [x e^-x / (1 - e^-x) - ln(1 - e^-x)].
     */
    private static final class HelmholtzTerms extends Segment {
        private final double reducingTemperature;
        private double logTau;
        private double[] powerN = new double[0];
        private double[] powerT = new double[0];
        private double[] planckN = new double[0];
        private double[] planckTheta = new double[0];
        private final List<Object> termScience = new ArrayList<>();

        HelmholtzTerms(JsonObject o) {
            super("helmholtz_ideal_terms", o);
            reducingTemperature = MaterialCatalog.positive(o, "reducing_temperature_kelvin");
            JsonArray terms = MaterialCatalog.array(o, "terms");
            if (terms.isEmpty() || terms.size() > 16) throw new IllegalArgumentException("terms: expected 1 to 16 terms");
            for (int i = 0; i < terms.size(); i++) {
                try {
                    if (!terms.get(i).isJsonObject()) throw new IllegalArgumentException("expected object");
                    readTerm(terms.get(i).getAsJsonObject());
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("terms[" + i + "]: " + invalid.getMessage(), invalid);
                }
            }
        }

        private void readTerm(JsonObject term) {
            String kind = MaterialCatalog.string(term, "type");
            switch (kind) {
                case "log_tau" -> {
                    double a = MaterialCatalog.number(term, "a");
                    logTau += a;
                    termScience.add(List.of(kind, a));
                }
                case "power" -> {
                    double[] n = doubles(term, "n", 1, 32), t = doubles(term, "t", 1, 32);
                    if (n.length != t.length) throw new IllegalArgumentException("power: n and t differ in length");
                    powerN = concat(powerN, n); powerT = concat(powerT, t);
                    termScience.add(List.of(kind, boxed(n), boxed(t)));
                }
                case "planck_einstein" -> {
                    double[] n = doubles(term, "n", 1, 32), t = doubles(term, "t", 1, 32);
                    if (n.length != t.length) throw new IllegalArgumentException("planck_einstein: n and t differ in length");
                    for (double theta : t) if (!(theta > 0)) throw new IllegalArgumentException("planck_einstein: t must be positive");
                    planckN = concat(planckN, n); planckTheta = concat(planckTheta, t);
                    termScience.add(List.of(kind, boxed(n), boxed(t)));
                }
                case "planck_einstein_function_t" -> {
                    double[] n = doubles(term, "n", 1, 32), v = doubles(term, "v", 1, 32);
                    double critical = MaterialCatalog.positive(term, "critical_temperature_kelvin");
                    if (n.length != v.length) throw new IllegalArgumentException("planck_einstein_function_t: n and v differ in length");
                    double[] theta = new double[v.length];
                    for (int i = 0; i < v.length; i++) {
                        if (!(v[i] > 0)) throw new IllegalArgumentException("planck_einstein_function_t: v must be positive");
                        theta[i] = v[i] / critical;
                    }
                    planckN = concat(planckN, n); planckTheta = concat(planckTheta, theta);
                    termScience.add(List.of(kind, boxed(n), boxed(v), critical));
                }
                default -> throw new IllegalArgumentException("type: unsupported Helmholtz term " + kind
                        + " (expected log_tau, power, planck_einstein or planck_einstein_function_t)");
            }
        }

        private static double[] concat(double[] a, double[] b) {
            double[] joined = java.util.Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, joined, a.length, b.length);
            return joined;
        }

        @Override double heatCapacity(double t) {
            double tau = reducingTemperature / t, sum = 1 + logTau;
            for (int i = 0; i < powerN.length; i++) sum -= powerN[i] * powerT[i] * (powerT[i] - 1) * Math.pow(tau, powerT[i]);
            for (int i = 0; i < planckN.length; i++) {
                double x = planckTheta[i] * tau, e = Math.exp(-x), d = -Math.expm1(-x);
                sum += planckN[i] * x * x * e / (d * d);
            }
            return gasConstant * sum;
        }

        @Override double enthalpy(double t) {
            double tau = reducingTemperature / t, sum = 1 + logTau;
            for (int i = 0; i < powerN.length; i++) sum += powerN[i] * powerT[i] * Math.pow(tau, powerT[i]);
            for (int i = 0; i < planckN.length; i++) {
                double x = planckTheta[i] * tau;
                sum += planckN[i] * x * Math.exp(-x) / -Math.expm1(-x);
            }
            return gasConstant * t * sum;
        }

        @Override double entropy(double t) {
            double tau = reducingTemperature / t, sum = (1 + logTau) * Math.log(t);
            for (int i = 0; i < powerN.length; i++) sum += powerN[i] * (powerT[i] - 1) * Math.pow(tau, powerT[i]);
            for (int i = 0; i < planckN.length; i++) {
                double x = planckTheta[i] * tau, d = -Math.expm1(-x);
                sum += planckN[i] * (x * Math.exp(-x) / d - Math.log(d));
            }
            return gasConstant * sum;
        }

        @Override Object science() { return List.of(type, minimum, maximum, gasConstant, reducingTemperature, termScience); }
    }

    /**
     * NASA 9-coefficient form (McBride, Zehe and Gordon 2002, NASA/TP-2002-211556): Cp/R = a1 T^-2 + a2 T^-1 + a3 +
     * a4 T + a5 T^2 + a6 T^3 + a7 T^4; H/R = -a1/T + a2 ln T + a3 T + a4 T^2/2 + a5 T^3/3 + a6 T^4/4 + a7 T^5/5;
     * S/R = -a1/(2 T^2) - a2/T + a3 ln T + a4 T + a5 T^2/2 + a6 T^3/3 + a7 T^4/4. The record carries all nine
     * coefficients (b1, b2 last) as the source prints them.
     */
    private static final class Nasa9 extends Segment {
        private final double[] a;
        private final double[] all;

        Nasa9(JsonObject o) {
            super("nasa9", o);
            all = doubles(o, "coefficients", 9, 9);
            a = java.util.Arrays.copyOf(all, 7);
        }

        @Override double heatCapacity(double t) {
            return gasConstant * (a[0] / (t * t) + a[1] / t + a[2] + t * (a[3] + t * (a[4] + t * (a[5] + t * a[6]))));
        }

        @Override double enthalpy(double t) {
            return gasConstant * (-a[0] / t + a[1] * Math.log(t) + t * (a[2] + t * (a[3] / 2 + t * (a[4] / 3 + t * (a[5] / 4 + t * a[6] / 5)))));
        }

        @Override double entropy(double t) {
            return gasConstant * (-a[0] / (2 * t * t) - a[1] / t + a[2] * Math.log(t) + t * (a[3] + t * (a[4] / 2 + t * (a[5] / 3 + t * a[6] / 4))));
        }

        @Override Object science() { return List.of(type, minimum, maximum, gasConstant, boxed(all)); }
    }

    /**
     * NASA 7-coefficient form (one temperature range of the two-range form; declare each range as its own segment):
     * Cp/R = a1 + a2 T + a3 T^2 + a4 T^3 + a5 T^4; H/R = a1 T + a2 T^2/2 + a3 T^3/3 + a4 T^4/4 + a5 T^5/5;
     * S/R = a1 ln T + a2 T + a3 T^2/2 + a4 T^3/3 + a5 T^4/4. Coefficients a6, a7 are carried and not used.
     */
    private static final class Nasa7 extends Segment {
        private final double[] a;
        private final double[] all;

        Nasa7(JsonObject o) {
            super("nasa7", o);
            all = doubles(o, "coefficients", 7, 7);
            a = java.util.Arrays.copyOf(all, 5);
        }

        @Override double heatCapacity(double t) {
            return gasConstant * (a[0] + t * (a[1] + t * (a[2] + t * (a[3] + t * a[4]))));
        }

        @Override double enthalpy(double t) {
            return gasConstant * t * (a[0] + t * (a[1] / 2 + t * (a[2] / 3 + t * (a[3] / 4 + t * a[4] / 5))));
        }

        @Override double entropy(double t) {
            return gasConstant * (a[0] * Math.log(t) + t * (a[1] + t * (a[2] / 2 + t * (a[3] / 3 + t * a[4] / 4))));
        }

        @Override Object science() { return List.of(type, minimum, maximum, gasConstant, boxed(all)); }
    }
}
