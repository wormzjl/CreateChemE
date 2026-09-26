package com.wormzjl.createcheme.science.material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The temperature-dependent binary interactions of one package, resolved at load from its interactions record's
 * {@code rule} ({@code {"type": "eppr78", "group_interactions": <group_interactions record id>}}), the matrix of
 * {@link GroupInteractionMatrix} and the group decomposition in each component's reference spine (P3 WP2, batch
 * 2026-09-24-coolprop-low-temperature, plan {@code P3_PILOT_ENGINE_PLAN.md} section 4.1). This is the data the kernel's
 * {@code PairInteractions} model consumes (WP1 builds that model, WP5 wires it); nothing here evaluates a mixture.
 *
 * <p>E-PPR78 (Jaubert et al. 2022), with alpha_ik the fraction of the groups of molecule i that are of type k:</p>
 * <pre>
 *   E_ij(T) = -1/2 sum_k sum_l (alpha_ik - alpha_jk)(alpha_il - alpha_jl) A_kl (298.15/T)^(B_kl/A_kl - 1)
 *   k_ij(T) = [E_ij(T) - (sqrt(a_i)/b_i - sqrt(a_j)/b_j)^2] / [2 sqrt(a_i a_j)/(b_i b_j)]
 * </pre>
 * <p>With A_kl = A_lk and A_kk = 0 the double sum is a sum over unordered group pairs k &lt; l of
 * {@code weight_kl A_kl f_kl(T)}, {@code weight_kl = -(alpha_ik - alpha_jk)(alpha_il - alpha_jl)} and
 * {@code f_kl(T) = (298.15/T)^(B_kl/A_kl - 1)}. {@link Pair} carries exactly those terms, precomputed from the group
 * counts; for two single-group molecules there is one term of weight 1 and {@code E_ij = A f}. A and B are in pascal
 * here (the record's MPa times 1e6); the exponent is formed from the record's own values, {@code B/A - 1}.</p>
 *
 * <p>Resolution rules (each a load refusal naming the package pair): a component pair either has an explicit constant
 * {@code kij} in the interactions record (kept as a constant, a declared estimate; it then has no {@link Pair} here)
 * or is resolved by the rule, which needs a package {@code spine}, the spine's {@code groups.scheme} equal to the
 * matrix's {@code scheme}, every group of both decompositions known to the matrix, and every group pair with a nonzero
 * weight present in the matrix ("unresolved group pair" otherwise). A group pair with A_kl = 0 and B_kl != 0, for which
 * the formula is undefined, is refused; A_kl = B_kl = 0 is a zero term and is dropped.</p>
 *
 * <p>Fingerprints: {@link #science} (rule, scheme, and per resolved pair the component ids and every term's groups,
 * A, B and weight, in MPa as the record carries them) is hashed into the package's {@code fingerprint} and
 * {@code physicsFingerprint}, only for a package that has a rule, so every package without one keeps its pins.</p>
 */
public final class GroupContributionInteractions {
    /** The one rule type in P3. */
    public static final String RULE_EPPR78 = "eppr78";
    /** The reference temperature of the E-PPR78 temperature function, K. */
    public static final double REFERENCE_TEMPERATURE = 298.15;
    private static final double PASCAL_PER_MEGAPASCAL = 1.0e6;

    /**
     * One term of E_ij: {@code weight * aPascal * (298.15/T)^exponent} with {@code exponent = B/A - 1}.
     *
     * @param firstGroup the lower-numbered group of the pair
     * @param secondGroup the higher-numbered group of the pair
     */
    public record Term(String firstGroup, String secondGroup, double aMegapascal, double bMegapascal, double weight) {
        public double aPascal() { return aMegapascal * PASCAL_PER_MEGAPASCAL; }
        public double bPascal() { return bMegapascal * PASCAL_PER_MEGAPASCAL; }
        /** {@code B/A - 1} from the record's values. */
        public double exponent() { return bMegapascal / aMegapascal - 1.0; }
    }

    /**
     * The rule-resolved interaction of components {@code first < second} (indices in the package's component order).
     * The array views are copies; a consumer copies them once at construction and evaluates allocation-free.
     */
    public static final class Pair {
        private final int first;
        private final int second;
        private final List<Term> terms;
        private final double[] a;
        private final double[] exponent;
        private final double[] weight;

        Pair(int first, int second, List<Term> terms) {
            this.first = first; this.second = second; this.terms = List.copyOf(terms);
            a = new double[terms.size()]; exponent = new double[terms.size()]; weight = new double[terms.size()];
            for (int t = 0; t < terms.size(); t++) {
                a[t] = terms.get(t).aPascal(); exponent[t] = terms.get(t).exponent(); weight[t] = terms.get(t).weight();
            }
        }

        public int first() { return first; }
        public int second() { return second; }
        public List<Term> terms() { return terms; }
        /** A_kl of each term, Pa. */
        public double[] aPascal() { return a.clone(); }
        /** B_kl/A_kl - 1 of each term. */
        public double[] exponents() { return exponent.clone(); }
        /** The term weights -(alpha_ik - alpha_jk)(alpha_il - alpha_jl). */
        public double[] weights() { return weight.clone(); }

        /** E_ij(T), Pa. */
        public double energy(double temperatureKelvin) {
            double ratio = REFERENCE_TEMPERATURE / requireTemperature(temperatureKelvin), sum = 0.0;
            for (int t = 0; t < a.length; t++) sum += weight[t] * a[t] * Math.pow(ratio, exponent[t]);
            return sum;
        }

        /** dE_ij/dT, Pa/K: each term's f' = -(B/A - 1) f / T. */
        public double energyDerivative(double temperatureKelvin) {
            double ratio = REFERENCE_TEMPERATURE / requireTemperature(temperatureKelvin), sum = 0.0;
            for (int t = 0; t < a.length; t++) sum -= weight[t] * a[t] * exponent[t] * Math.pow(ratio, exponent[t]);
            return sum / temperatureKelvin;
        }

        /** d2E_ij/dT2, Pa/K2: each term's f'' = (B/A - 1)(B/A) f / T^2. */
        public double energySecondDerivative(double temperatureKelvin) {
            double ratio = REFERENCE_TEMPERATURE / requireTemperature(temperatureKelvin), sum = 0.0;
            for (int t = 0; t < a.length; t++) sum += weight[t] * a[t] * exponent[t] * (exponent[t] + 1.0) * Math.pow(ratio, exponent[t]);
            return sum / (temperatureKelvin * temperatureKelvin);
        }
    }

    private final String rule;
    private final String scheme;
    private final String matrixId;
    private final String matrixRevision;
    private final List<Pair> pairs;
    private final Pair[][] byComponents;

    private GroupContributionInteractions(String rule, String scheme, String matrixId, String matrixRevision, int components, List<Pair> pairs) {
        this.rule = rule; this.scheme = scheme; this.matrixId = matrixId; this.matrixRevision = matrixRevision;
        this.pairs = List.copyOf(pairs);
        byComponents = new Pair[components][components];
        for (Pair pair : this.pairs) byComponents[pair.first][pair.second] = byComponents[pair.second][pair.first] = pair;
    }

    /** {@value #RULE_EPPR78}. */
    public String rule() { return rule; }
    /** The group scheme shared by the matrix and the spines, {@value Eppr78Groups#SCHEME} for the bundled record. */
    public String scheme() { return scheme; }
    /** Id of the {@code group_interactions} record (not hashed). */
    public String matrixId() { return matrixId; }
    /** Revision of the {@code group_interactions} record (not hashed). */
    public String matrixRevision() { return matrixRevision; }
    /** The rule-resolved pairs, ordered by (first, second). A component pair not listed carries its constant kij. */
    public List<Pair> pairs() { return pairs; }
    /** The rule-resolved pair of two package components (either order), empty for a constant pair. */
    public Optional<Pair> pair(int i, int j) {
        if (i < 0 || j < 0 || i >= byComponents.length || j >= byComponents.length) throw new IndexOutOfBoundsException("component index");
        return Optional.ofNullable(byComponents[i][j]);
    }

    /**
     * k_ij from E_ij and the pure-component attraction and co-volume, the formula's second line; a reference helper
     * for tests and tools (the kernel evaluates the equivalent cross term, plan section 4.1).
     */
    public static double kij(double energyPascal, double ai, double bi, double aj, double bj) {
        double difference = Math.sqrt(ai) / bi - Math.sqrt(aj) / bj;
        return (energyPascal - difference * difference) / (2.0 * Math.sqrt(ai * aj) / (bi * bj));
    }

    /** The hashed content restricted to pairs of {@code axis} (component ids), for the physics fingerprints. */
    Object science(List<String> components, Collection<String> axis) {
        var hashed = new ArrayList<Object>();
        for (Pair pair : pairs) {
            String a = components.get(pair.first), b = components.get(pair.second);
            if (!axis.contains(a) || !axis.contains(b)) continue;
            var terms = new ArrayList<Object>();
            for (Term term : pair.terms) terms.add(List.of(term.firstGroup(), term.secondGroup(), term.aMegapascal(), term.bMegapascal(), term.weight()));
            hashed.add(List.of(a, b, terms));
        }
        return List.of(rule, scheme, hashed);
    }

    private static double requireTemperature(double t) {
        if (!(t > 0.0) || !Double.isFinite(t)) throw new IllegalArgumentException("Group-contribution kij: temperature must be finite and positive, got " + t);
        return t;
    }

    /**
     * Resolves every component pair that has no explicit constant ({@code constantPairs[i][j]} false) by the rule.
     *
     * @param spines the package's reference spines in component order
     */
    static GroupContributionInteractions resolve(String rule, GroupInteractionMatrix matrix, List<String> components,
            List<ReferenceSpine> spines, boolean[][] constantPairs) {
        if (!RULE_EPPR78.equals(rule)) throw new IllegalArgumentException("rule.type: expected " + RULE_EPPR78 + ", got " + rule);
        int n = components.size();
        var fractions = new ArrayList<Map<String, Double>>();
        for (int i = 0; i < n; i++) {
            var spine = spines.get(i);
            if (!spine.groupScheme().equals(matrix.scheme()))
                throw new IllegalArgumentException("rule: scheme mismatch for " + components.get(i) + ": spine " + spine.id() + " uses groups.scheme "
                        + spine.groupScheme() + ", group_interactions " + matrix.id() + " is scheme " + matrix.scheme());
            int total = 0;
            for (int count : spine.groups().values()) total += count;
            var alpha = new TreeMap<String, Double>();
            for (var entry : spine.groups().entrySet()) {
                if (!matrix.groups().contains(entry.getKey()))
                    throw new IllegalArgumentException("rule: unknown group " + entry.getKey() + " of " + components.get(i) + " (spine " + spine.id()
                            + ") in group_interactions " + matrix.id());
                alpha.put(entry.getKey(), entry.getValue() / (double) total);
            }
            fractions.add(alpha);
        }
        var pairs = new ArrayList<Pair>();
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (constantPairs[i][j]) continue;
            // Groups of either molecule in the matrix's number order, so the term order is fixed by the record.
            var groups = new ArrayList<String>();
            for (String group : matrix.groups()) if (fractions.get(i).containsKey(group) || fractions.get(j).containsKey(group)) groups.add(group);
            var terms = new ArrayList<Term>();
            for (int k = 0; k < groups.size(); k++) for (int l = k + 1; l < groups.size(); l++) {
                String gk = groups.get(k), gl = groups.get(l);
                double dk = fractions.get(i).getOrDefault(gk, 0.0) - fractions.get(j).getOrDefault(gk, 0.0);
                double dl = fractions.get(i).getOrDefault(gl, 0.0) - fractions.get(j).getOrDefault(gl, 0.0);
                double weight = -(dk * dl);
                if (weight == 0.0) continue;
                var found = matrix.parameters(gk, gl);
                if (found.isEmpty()) throw new IllegalArgumentException("rule: unresolved group pair " + gk + " / " + gl
                        + " for " + components.get(i) + " / " + components.get(j) + ": absent from group_interactions " + matrix.id());
                var parameters = found.get();
                if (parameters.aMegapascal() == 0.0) {
                    if (parameters.bMegapascal() == 0.0) continue;
                    throw new IllegalArgumentException("rule: undefined term " + gk + " / " + gl + " for " + components.get(i) + " / " + components.get(j)
                            + ": A_kl = 0 with B_kl = " + parameters.bMegapascal() + " MPa in group_interactions " + matrix.id());
                }
                terms.add(new Term(gk, gl, parameters.aMegapascal(), parameters.bMegapascal(), weight));
            }
            pairs.add(new Pair(i, j, terms));
        }
        return new GroupContributionInteractions(rule, matrix.scheme(), matrix.id(), matrix.revision(), n, pairs);
    }

    @Override public String toString() { return "GroupContributionInteractions[" + rule + ", " + scheme + ", " + pairs.size() + " pairs]"; }
}
