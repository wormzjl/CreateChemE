package com.wormzjl.createcheme.science.thermo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Binary interaction model of the PR78 kernel's quadratic mixing rule {@code a_ij = sqrt(a_i a_j)(1 - k_ij)}, per
 * unordered component pair: a constant {@code k_ij}, or the temperature-dependent group-contribution value of E-PPR78
 * (Jaubert et al.; the 2022 group decomposition). Pairs not declared have {@code k_ij = 0}.
 *
 * <p>For a group-contribution pair, with {@code alpha_ik} the fraction of molecule {@code i}'s groups that are of type
 * {@code k} and {@code A_kl}, {@code B_kl} the group-pair parameters (given in MPa, used in Pa):</p>
 *
 * <pre>
 *   E_ij(T) = -1/2 sum_k sum_l (alpha_ik - alpha_jk)(alpha_il - alpha_jl) A_kl (298.15/T)^(B_kl/A_kl - 1)
 *   k_ij(T) = [E_ij(T) - (sqrt(a_i)/b_i - sqrt(a_j)/b_j)^2] / [2 sqrt(a_i a_j)/(b_i b_j)]
 * </pre>
 *
 * <p>with {@code a_i(T)} and {@code b_i} the kernel's own Soave PR78 attraction and co-volume (so the rule is evaluated
 * self-consistently on the constants the kernel mixes; E-PPR78's own {@code Omega_a}, {@code Omega_b} differ from the
 * kernel's by 1e-5 and 5e-5 relative). It is evaluated in the cancellation-free rearrangement</p>
 *
 * <pre>
 *   k_ij = N/D,  N = E (b_i b_j)^2 - (s_i b_j - s_j b_i)^2,  D = 2 b_i b_j s_i s_j,  s = sqrt(a)
 *   k' = (N' - k D')/D,  k'' = (N'' - 2 k' D' - k D'')/D
 * </pre>
 *
 * <p>which is the formula above multiplied through by {@code (b_i b_j)^2}; the derivatives follow from {@code N = k D}
 * differentiated twice, on the kernel's {@code d sqrt(a)/dT} and {@code d^2 sqrt(a)/dT^2}. Each distinct group pair
 * {@code k < l} with {@code A_kl != 0} that the two molecules' group fractions both touch is one term, so a pair of
 * single-group molecules costs one {@code pow} per temperature.</p>
 *
 * <p>The object is immutable. {@link #evaluate} fills caller-owned arrays and allocates nothing.</p>
 */
public final class PairInteractions {
    /** The reference temperature of the E-PPR78 group terms. */
    public static final double REFERENCE_TEMPERATURE_KELVIN = 298.15;
    private static final double PASCAL_PER_MEGAPASCAL = 1.0e6;

    private final int componentCount;
    /** Declared pairs with {@code first < second}, sorted row-major (the order the kernel's sparse sums use). */
    private final int[] first;
    private final int[] second;
    private final double[] constantValue;
    private final boolean[] groupPair;
    /** Group terms of pair {@code p}: indices {@code termStart[p]} to {@code termStart[p + 1] - 1}. */
    private final int[] termStart;
    /** {@code -(alpha_ik - alpha_jk)(alpha_il - alpha_jl) A_kl} in Pa, both orders of {@code (k, l)} summed. */
    private final double[] termCoefficient;
    /** {@code B_kl/A_kl - 1}. */
    private final double[] termExponent;
    private final boolean temperatureDependent;

    private PairInteractions(int componentCount, int[] first, int[] second, double[] constantValue, boolean[] groupPair,
                             int[] termStart, double[] termCoefficient, double[] termExponent) {
        this.componentCount = componentCount;
        this.first = first;
        this.second = second;
        this.constantValue = constantValue;
        this.groupPair = groupPair;
        this.termStart = termStart;
        this.termCoefficient = termCoefficient;
        this.termExponent = termExponent;
        boolean dependent = false;
        for (boolean value : groupPair) dependent |= value;
        this.temperatureDependent = dependent;
    }

    /** No interaction at all: every {@code k_ij = 0}. */
    public static PairInteractions none(int componentCount) {
        return builder(componentCount).build();
    }

    /**
     * The constant model of a square, symmetric, zero-diagonal matrix: one pair per nonzero {@code i < j} entry, in the
     * order {@link PengRobinsonKernel.Mixing#SPARSE_PAIRS} sums them, so the kernel's temperature-dependent plan on
     * this model reproduces the sparse plan bit for bit.
     */
    public static PairInteractions constant(double[][] interactions) {
        Objects.requireNonNull(interactions, "interactions");
        int count = interactions.length;
        Builder builder = builder(count);
        for (int i = 0; i < count; i++) {
            if (interactions[i] == null || interactions[i].length != count) {
                throw new IllegalArgumentException("Interaction matrix must be square");
            }
        }
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                double value = interactions[i][j];
                if (!Double.isFinite(value) || value != interactions[j][i] || (i == j && value != 0.0)) {
                    throw new IllegalArgumentException("Interaction matrix must be finite, symmetric, and zero diagonal");
                }
                if (i < j && value != 0.0) builder.constant(i, j, value);
            }
        }
        return builder.build();
    }

    public static Builder builder(int componentCount) {
        return new Builder(componentCount);
    }

    public int componentCount() { return componentCount; }
    /** Declared pairs with a nonzero constant or a group-contribution rule. */
    public int pairCount() { return first.length; }
    public int first(int pair) { return first[pair]; }
    public int second(int pair) { return second[pair]; }
    public boolean isGroupContribution(int pair) { return groupPair[pair]; }
    /** The constant {@code k_ij} of a constant pair; zero for a group-contribution pair. */
    public double constantValue(int pair) { return constantValue[pair]; }
    /** True when at least one pair follows the group-contribution rule. */
    public boolean temperatureDependent() { return temperatureDependent; }

    /** The pair index of {@code (i, j)} in either order, or {@code -1} when the pair is not declared ({@code k_ij = 0}). */
    public int pairIndex(int i, int j) {
        int low = Math.min(i, j);
        int high = Math.max(i, j);
        for (int pair = 0; pair < first.length; pair++) if (first[pair] == low && second[pair] == high) return pair;
        return -1;
    }

    /**
     * Fills {@code k_ij(T)}, {@code dk_ij/dT} and {@code d^2k_ij/dT^2} per declared pair.
     *
     * @param sqrtA {@code sqrt(a_i(T))} of the kernel, per component
     * @param sqrtADt {@code d sqrt(a_i)/dT}; read only when {@link #temperatureDependent()}
     * @param sqrtADt2 {@code d^2 sqrt(a_i)/dT^2}; read only when {@link #temperatureDependent()}
     * @param coVolumes {@code b_i} of the kernel
     * @param value output, one entry per pair
     * @param firstDerivative output, one entry per pair
     * @param secondDerivative output, one entry per pair
     */
    public void evaluate(double temperatureKelvin, double[] sqrtA, double[] sqrtADt, double[] sqrtADt2,
                         double[] coVolumes, double[] value, double[] firstDerivative, double[] secondDerivative) {
        int pairs = first.length;
        if (value.length < pairs || firstDerivative.length < pairs || secondDerivative.length < pairs) {
            throw new IllegalArgumentException("Pair output arrays are too short");
        }
        double ratio = REFERENCE_TEMPERATURE_KELVIN / temperatureKelvin;
        double inverseT = 1.0 / temperatureKelvin;
        for (int pair = 0; pair < pairs; pair++) {
            if (!groupPair[pair]) {
                value[pair] = constantValue[pair];
                firstDerivative[pair] = 0.0;
                secondDerivative[pair] = 0.0;
                continue;
            }
            double energy = 0.0;
            double energyDt = 0.0;
            double energyDt2 = 0.0;
            for (int term = termStart[pair]; term < termStart[pair + 1]; term++) {
                double exponent = termExponent[term];
                double contribution = termCoefficient[term] * Math.pow(ratio, exponent);
                energy += contribution;
                energyDt -= exponent * contribution * inverseT;
                energyDt2 += exponent * (exponent + 1.0) * contribution * inverseT * inverseT;
            }
            int i = first[pair];
            int j = second[pair];
            double bi = coVolumes[i];
            double bj = coVolumes[j];
            double beta = bi * bj;
            double betaSquared = beta * beta;
            double si = sqrtA[i];
            double sj = sqrtA[j];
            double siDt = sqrtADt[i];
            double sjDt = sqrtADt[j];
            double siDt2 = sqrtADt2[i];
            double sjDt2 = sqrtADt2[j];
            double d = si * bj - sj * bi;
            double dDt = siDt * bj - sjDt * bi;
            double dDt2 = siDt2 * bj - sjDt2 * bi;
            double numerator = energy * betaSquared - d * d;
            double numeratorDt = energyDt * betaSquared - 2.0 * d * dDt;
            double numeratorDt2 = energyDt2 * betaSquared - 2.0 * (dDt * dDt + d * dDt2);
            double denominator = 2.0 * beta * si * sj;
            double denominatorDt = 2.0 * beta * (siDt * sj + si * sjDt);
            double denominatorDt2 = 2.0 * beta * (siDt2 * sj + 2.0 * siDt * sjDt + si * sjDt2);
            double k = numerator / denominator;
            double kDt = (numeratorDt - k * denominatorDt) / denominator;
            value[pair] = k;
            firstDerivative[pair] = kDt;
            secondDerivative[pair] = (numeratorDt2 - 2.0 * kDt * denominatorDt - k * denominatorDt2) / denominator;
        }
    }

    /**
     * E-PPR78 group-pair parameters over one group list: symmetric, zero diagonal, {@code A_kl} and {@code B_kl} in MPa
     * as the publications print them. A pair with {@code A_kl = 0} contributes nothing and must have {@code B_kl = 0}.
     */
    public record GroupParameters(double[][] aMegapascal, double[][] bMegapascal) {
        public GroupParameters {
            Objects.requireNonNull(aMegapascal, "aMegapascal");
            Objects.requireNonNull(bMegapascal, "bMegapascal");
            int groups = aMegapascal.length;
            if (groups == 0 || bMegapascal.length != groups) throw new IllegalArgumentException("Group parameter tables must be square and non-empty");
            double[][] a = new double[groups][];
            double[][] b = new double[groups][];
            for (int k = 0; k < groups; k++) {
                if (aMegapascal[k] == null || bMegapascal[k] == null
                        || aMegapascal[k].length != groups || bMegapascal[k].length != groups) {
                    throw new IllegalArgumentException("Group parameter tables must be square");
                }
                a[k] = aMegapascal[k].clone();
                b[k] = bMegapascal[k].clone();
            }
            for (int k = 0; k < groups; k++) {
                for (int l = 0; l < groups; l++) {
                    double valueA = a[k][l];
                    double valueB = b[k][l];
                    if (!Double.isFinite(valueA) || !Double.isFinite(valueB)) throw new IllegalArgumentException("Group parameters must be finite");
                    if (valueA != a[l][k] || valueB != b[l][k]) throw new IllegalArgumentException("Group parameters must be symmetric");
                    if (k == l && (valueA != 0.0 || valueB != 0.0)) throw new IllegalArgumentException("Group parameters must have a zero diagonal");
                    if (valueA == 0.0 && valueB != 0.0) throw new IllegalArgumentException("B_kl without A_kl has no E-PPR78 meaning");
                }
            }
            aMegapascal = a;
            bMegapascal = b;
        }

        public int groupCount() { return aMegapascal.length; }
        @Override public double[][] aMegapascal() { return deepCopy(aMegapascal); }
        @Override public double[][] bMegapascal() { return deepCopy(bMegapascal); }

        private static double[][] deepCopy(double[][] source) {
            double[][] copy = new double[source.length][];
            for (int k = 0; k < source.length; k++) copy[k] = source[k].clone();
            return copy;
        }
    }

    /** Declares pairs one at a time; each unordered pair at most once. */
    public static final class Builder {
        private final int componentCount;
        private final boolean[][] declared;
        private final double[][] constants;
        private final double[][][] groupTerms;

        private Builder(int componentCount) {
            if (componentCount <= 0) throw new IllegalArgumentException("At least one component is required");
            this.componentCount = componentCount;
            this.declared = new boolean[componentCount][componentCount];
            this.constants = new double[componentCount][componentCount];
            this.groupTerms = new double[componentCount][componentCount][];
        }

        /** A constant {@code k_ij}; zero is accepted and declares the pair as having no interaction. */
        public Builder constant(int i, int j, double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Constant interaction must be finite");
            int low = claim(i, j);
            int high = Math.max(i, j);
            constants[low][high] = value;
            return this;
        }

        /**
         * The E-PPR78 group-contribution rule for the pair {@code (i, j)}.
         *
         * @param groupCountsI how many groups of each type molecule {@code i} has, over {@code parameters}' group list;
         *                     the fractions {@code alpha_ik} are these counts over their sum
         * @param groupCountsJ the same for molecule {@code j}
         */
        public Builder groupContribution(int i, int j, int[] groupCountsI, int[] groupCountsJ, GroupParameters parameters) {
            Objects.requireNonNull(parameters, "parameters");
            int groups = parameters.groupCount();
            double[] alphaI = fractions(groupCountsI, groups);
            double[] alphaJ = fractions(groupCountsJ, groups);
            double[][] a = parameters.aMegapascal;
            double[][] b = parameters.bMegapascal;
            int terms = 0;
            double[] weights = new double[groups * groups];
            double[] aPascal = new double[groups * groups];
            double[] exponents = new double[groups * groups];
            for (int k = 0; k < groups; k++) {
                double dk = alphaI[k] - alphaJ[k];
                if (dk == 0.0) continue;
                for (int l = k + 1; l < groups; l++) {
                    double dl = alphaI[l] - alphaJ[l];
                    if (dl == 0.0 || a[k][l] == 0.0) continue;
                    // -1/2 sum over both orders (k, l) and (l, k) of the symmetric table: one term of weight -dk dl.
                    weights[terms] = -(dk * dl);
                    aPascal[terms] = a[k][l] * PASCAL_PER_MEGAPASCAL;
                    exponents[terms] = b[k][l] / a[k][l] - 1.0;
                    terms++;
                }
            }
            return groupTerms(i, j, Arrays.copyOf(weights, terms), Arrays.copyOf(aPascal, terms), Arrays.copyOf(exponents, terms));
        }

        /**
         * The E-PPR78 rule for the pair {@code (i, j)} from terms already resolved from the two group decompositions
         * (the form {@code science.material.GroupContributionInteractions.Pair} carries):
         * {@code E_ij(T) = sum_m weight_m aPascal_m (298.15/T)^exponent_m}, with
         * {@code weight_m = -(alpha_ik - alpha_jk)(alpha_il - alpha_jl)} and {@code exponent_m = B_kl/A_kl - 1} of the
         * group pair {@code k < l} of term {@code m}. No term at all is {@code E_ij = 0} (identical decompositions).
         */
        public Builder groupTerms(int i, int j, double[] weights, double[] aPascal, double[] exponents) {
            Objects.requireNonNull(weights, "weights");
            Objects.requireNonNull(aPascal, "aPascal");
            Objects.requireNonNull(exponents, "exponents");
            if (weights.length != aPascal.length || weights.length != exponents.length) {
                throw new IllegalArgumentException("Group terms need one weight, A and exponent each");
            }
            int terms = 0;
            for (int term = 0; term < weights.length; term++) {
                if (!Double.isFinite(weights[term]) || !Double.isFinite(aPascal[term]) || !Double.isFinite(exponents[term])) {
                    throw new IllegalArgumentException("Group terms must be finite");
                }
                if (weights[term] * aPascal[term] != 0.0) terms++;
            }
            int low = claim(i, j);
            int high = Math.max(i, j);
            double[] packed = new double[2 * terms];
            int at = 0;
            for (int term = 0; term < weights.length; term++) {
                double coefficient = weights[term] * aPascal[term];
                if (coefficient == 0.0) continue;
                packed[at++] = coefficient;
                packed[at++] = exponents[term];
            }
            groupTerms[low][high] = packed;
            return this;
        }

        public PairInteractions build() {
            int pairs = 0;
            int terms = 0;
            for (int i = 0; i < componentCount; i++) {
                for (int j = i + 1; j < componentCount; j++) {
                    if (groupTerms[i][j] != null) {
                        pairs++;
                        terms += groupTerms[i][j].length / 2;
                    } else if (constants[i][j] != 0.0) {
                        pairs++;
                    }
                }
            }
            int[] first = new int[pairs];
            int[] second = new int[pairs];
            double[] constantValue = new double[pairs];
            boolean[] groupPair = new boolean[pairs];
            int[] termStart = new int[pairs + 1];
            double[] termCoefficient = new double[terms];
            double[] termExponent = new double[terms];
            int pair = 0;
            int term = 0;
            for (int i = 0; i < componentCount; i++) {
                for (int j = i + 1; j < componentCount; j++) {
                    double[] packed = groupTerms[i][j];
                    if (packed == null && constants[i][j] == 0.0) continue;
                    first[pair] = i;
                    second[pair] = j;
                    termStart[pair] = term;
                    if (packed != null) {
                        groupPair[pair] = true;
                        for (int at = 0; at < packed.length; at += 2) {
                            termCoefficient[term] = packed[at];
                            termExponent[term] = packed[at + 1];
                            term++;
                        }
                    } else {
                        constantValue[pair] = constants[i][j];
                    }
                    pair++;
                }
            }
            termStart[pairs] = term;
            return new PairInteractions(componentCount, first, second, constantValue, groupPair,
                    termStart, termCoefficient, termExponent);
        }

        private int claim(int i, int j) {
            if (i < 0 || j < 0 || i >= componentCount || j >= componentCount) throw new IllegalArgumentException("Pair index out of range");
            if (i == j) throw new IllegalArgumentException("A component has no interaction with itself");
            int low = Math.min(i, j);
            int high = Math.max(i, j);
            if (declared[low][high]) throw new IllegalArgumentException("Pair (" + low + ", " + high + ") is declared twice");
            declared[low][high] = true;
            return low;
        }

        private static double[] fractions(int[] counts, int groups) {
            Objects.requireNonNull(counts, "group counts");
            if (counts.length != groups) throw new IllegalArgumentException("Group counts do not match the group list");
            long total = 0;
            for (int count : counts) {
                if (count < 0) throw new IllegalArgumentException("Group counts must be nonnegative");
                total += count;
            }
            if (total == 0) throw new IllegalArgumentException("A molecule needs at least one group");
            double[] alpha = new double[groups];
            for (int k = 0; k < groups; k++) alpha[k] = (double) counts[k] / (double) total;
            return alpha;
        }
    }

    @Override public String toString() {
        int groups = 0;
        for (boolean value : groupPair) if (value) groups++;
        return "PairInteractions[components=" + componentCount + ", pairs=" + first.length + ", groupPairs=" + groups + "]";
    }
}
