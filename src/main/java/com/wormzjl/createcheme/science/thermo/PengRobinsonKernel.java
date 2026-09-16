package com.wormzjl.createcheme.science.thermo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Allocation-free PR78 hydrocarbon kernel. One {@link Workspace} belongs to one solve; liquid and vapor
 * evaluations at a common temperature share its temperature-dependent pure-component arithmetic.
 *
 * <p>Promoted unchanged from the V3 column's package so the fluid network can evaluate its phases on the
 * same arithmetic. The only difference from the column-private original is where the critical constants
 * come from: they are read into arrays by the constructor instead of through a property-package interface
 * on every use, so the class no longer depends on the column, and every expression below is the one the
 * column has been running.</p>
 */
public final class PengRobinsonKernel {
    public static final double GAS_CONSTANT = 8.31446261815324;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final double ROOT_EPSILON = 1.0e-12;
    private static final double COALESCENCE_DISCRIMINANT_TOLERANCE = 1.0e-16;
    private static final int MAXIMUM_ROOT_REFINEMENTS = 4;
    /**
     * How flat the cubic may be at the selected root before its derivative is refused.
     *
     * <p>Every state derivative divides by this slope, so it is the one quantity whose smallness makes the
     * whole bundle meaningless rather than merely inaccurate. The bound is relative to the size of the terms
     * the slope is assembled from, so it measures cancellation rather than magnitude, and it is deliberately
     * far below any separation a column state reaches: refusing costs the caller a fallback to differencing,
     * which is correct but slow, and a root this flat is one whose finite difference is no better.</p>
     */
    private static final double COALESCENCE_SLOPE_TOLERANCE = 1.0e-10;

    private final int count;
    private final double[] criticalTemperatures;
    private final double[] criticalPressures;
    private final double[] acentricFactors;
    private final double[] criticalA;
    private final double[] coVolumes;
    private final double[] kappas;
    private final double[][] binaryInteractions;
    private final boolean rankOneMixing;
    private final Mixing mixing;
    /** The {@code i < j} entries with {@code k_ij != 0}, for {@link Mixing#SPARSE_PAIRS}; empty otherwise. */
    private final int[] pairFirst;
    private final int[] pairSecond;
    private final double[] pairInteraction;
    private final double minimumTemperatureKelvin;
    private final double maximumTemperatureKelvin;
    private final double minimumPressurePascal;
    private final double maximumPressurePascal;

    /**
     * How the quadratic mixture sums are evaluated. Both plans compute the same classical rule
     * {@code a_ij = sqrt(a_i a_j)(1 - k_ij)}; they differ only in the arithmetic that reaches it, and
     * therefore only in roundoff.
     */
    public enum Mixing {
        /**
         * The column's plan: {@code O(n^2)} over the full interaction matrix, collapsing to a rank-one
         * product when every {@code k_ij} is zero. Every V3 state has been evaluated this way and stays
         * bitwise on it.
         */
        CLASSICAL,
        /**
         * {@code O(n + pairs)}: the row sums are the rank-one sum corrected by the few nonzero pairs,
         * {@code S_i = sqrt(a_i) (sum_j q_j - sum_{j: k_ij != 0} k_ij q_j)} with {@code q_i = x_i sqrt(a_i)}.
         * For a package whose interaction matrix is nearly empty - the fluid network's has 11 nonzero pairs
         * among 21 components - this is the same rule at a fraction of the work. The mixture sums are then
         * accumulated with the identical expressions {@link #CLASSICAL}'s dense branch uses, so the two
         * differ only in how each {@code S_i} was summed.
         */
        SPARSE_PAIRS
    }

    /**
     * @param criticalTemperaturesKelvin per component, in the caller's basis order
     * @param binaryInteractions square, symmetric, zero diagonal; copied
     */
    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            double[][] binaryInteractions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        this(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors, binaryInteractions,
                minimumTemperatureKelvin, maximumTemperatureKelvin,
                minimumPressurePascal, maximumPressurePascal, Mixing.CLASSICAL);
    }

    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            double[][] binaryInteractions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal, Mixing mixing) {
        this.mixing = Objects.requireNonNull(mixing, "mixing");
        Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin");
        Objects.requireNonNull(criticalPressuresPascal, "criticalPressuresPascal");
        Objects.requireNonNull(acentricFactors, "acentricFactors");
        Objects.requireNonNull(binaryInteractions, "binaryInteractions");
        this.minimumTemperatureKelvin = minimumTemperatureKelvin;
        this.maximumTemperatureKelvin = maximumTemperatureKelvin;
        this.minimumPressurePascal = minimumPressurePascal;
        this.maximumPressurePascal = maximumPressurePascal;
        this.count = criticalTemperaturesKelvin.length;
        if (criticalPressuresPascal.length != count || acentricFactors.length != count) {
            throw new IllegalArgumentException("Invalid critical-property dimension");
        }
        this.criticalTemperatures = criticalTemperaturesKelvin.clone();
        this.criticalPressures = criticalPressuresPascal.clone();
        this.acentricFactors = acentricFactors.clone();
        this.criticalA = new double[count];
        this.coVolumes = new double[count];
        this.kappas = new double[count];
        this.binaryInteractions = new double[binaryInteractions.length][];
        for (int i = 0; i < binaryInteractions.length; i++) this.binaryInteractions[i] = binaryInteractions[i].clone();
        if (this.binaryInteractions.length != count) throw new IllegalArgumentException("Invalid BIP matrix dimension");
        boolean allZero = true;
        for (int i = 0; i < count; i++) {
            if (this.binaryInteractions[i].length != count) throw new IllegalArgumentException("Invalid BIP matrix row");
            criticalA[i] = 0.45724 * GAS_CONSTANT * GAS_CONSTANT
                    * criticalTemperatures[i] * criticalTemperatures[i]
                    / criticalPressures[i];
            coVolumes[i] = 0.07780 * GAS_CONSTANT * criticalTemperatures[i]
                    / criticalPressures[i];
            kappas[i] = kappa(this.acentricFactors[i]);
            for (int j = 0; j < count; j++) {
                double value = this.binaryInteractions[i][j];
                if (!Double.isFinite(value) || Math.abs(value - this.binaryInteractions[j][i]) > 1.0e-14
                        || (i == j && Math.abs(value) > 1.0e-14)) {
                    throw new IllegalArgumentException("BIP matrix must be finite, symmetric, and zero diagonal");
                }
                allZero &= value == 0.0;
            }
        }
        this.rankOneMixing = allZero;
        int pairs = 0;
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) if (this.binaryInteractions[i][j] != 0.0) pairs++;
        this.pairFirst = new int[pairs];
        this.pairSecond = new int[pairs];
        this.pairInteraction = new double[pairs];
        int at = 0;
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
            if (this.binaryInteractions[i][j] == 0.0) continue;
            pairFirst[at] = i;
            pairSecond[at] = j;
            pairInteraction[at++] = this.binaryInteractions[i][j];
        }
    }

    public int componentCount() { return count; }
    /** True when the package has no nonzero interaction at all, whatever plan is selected. */
    public boolean usesRankOneMixing() { return rankOneMixing; }
    public Mixing mixing() { return mixing; }
    /** How many {@code i < j} interactions are nonzero; the work {@link Mixing#SPARSE_PAIRS} pays per evaluation. */
    public int interactionPairCount() { return pairFirst.length; }
    public double binaryInteraction(int first, int second) { return binaryInteractions[first][second]; }
    /** {@code 0.07780 R Tc / Pc} for one component; the co-volume the mixture rule sums. */
    public double coVolume(int component) { return coVolumes[component]; }
    public Workspace newWorkspace() { return new Workspace(count); }
    public Evaluation newEvaluation() { return new Evaluation(count); }
    public Derivatives newDerivatives() { return new Derivatives(count); }

    /** Wilson K initialisation only; accepted states use rigorous fugacity refreshes. */
    public void wilsonK(double temperatureKelvin, double pressurePascal, double[] output) {
        requirePackageState(temperatureKelvin, pressurePascal);
        requireLength(output, "output");
        for (int i = 0; i < count; i++) {
            output[i] = Math.exp(Math.clamp(
                    Math.log(criticalPressures[i] / pressurePascal)
                            + 5.373 * (1.0 + acentricFactors[i])
                            * (1.0 - criticalTemperatures[i] / temperatureKelvin),
                    -40.0, 40.0));
        }
    }

    public void evaluate(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Evaluation output) {
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, composition, root, workspace, output);
    }

    /** Shares temperature-dependent pure-component values between two phase evaluations. */
    public void evaluatePair(
            double temperatureKelvin, double pressurePascal, double[] liquidComposition, double[] vaporComposition,
            Workspace workspace, Evaluation liquidOutput, Evaluation vaporOutput) {
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, liquidComposition, Root.LIQUID, workspace, liquidOutput);
        evaluatePrepared(temperatureKelvin, pressurePascal, vaporComposition, Root.VAPOR, workspace, vaporOutput);
    }

    public void prepareTemperature(double temperatureKelvin, Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < minimumTemperatureKelvin
                || temperatureKelvin > maximumTemperatureKelvin) {
            throw new IllegalArgumentException("Temperature is outside package range");
        }
        if (workspace.preparedTemperature == Double.doubleToLongBits(temperatureKelvin)) return;
        for (int i = 0; i < count; i++) {
            double sqrtTr = Math.sqrt(temperatureKelvin / criticalTemperatures[i]);
            double alphaTerm = 1.0 + kappas[i] * (1.0 - sqrtTr);
            workspace.a[i] = criticalA[i] * alphaTerm * alphaTerm;
            workspace.daDt[i] = -criticalA[i] * kappas[i] * alphaTerm
                    / Math.sqrt(temperatureKelvin * criticalTemperatures[i]);
            workspace.sqrtA[i] = Math.sqrt(workspace.a[i]);
        }
        workspace.preparedTemperature = Double.doubleToLongBits(temperatureKelvin);
    }

    private void evaluatePrepared(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Evaluation output) {
        requirePackageState(temperatureKelvin, pressurePascal);
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(output, "output");
        normalizeInto(composition, workspace.composition);
        double bMix = 0.0;
        for (int i = 0; i < count; i++) {
            bMix += workspace.composition[i] * coVolumes[i];
            workspace.q[i] = workspace.sqrtA[i] * workspace.composition[i];
        }
        double aMix;
        double daMixDt;
        if (mixing == Mixing.SPARSE_PAIRS) {
            // g_i = sum_j (1 - k_ij) q_j, reached as the rank-one sum minus the few pairs that are not zero.
            double qSum = 0.0;
            for (int i = 0; i < count; i++) qSum += workspace.q[i];
            for (int i = 0; i < count; i++) workspace.g[i] = qSum;
            for (int pair = 0; pair < pairFirst.length; pair++) {
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double interaction = pairInteraction[pair];
                workspace.g[i] -= interaction * workspace.q[j];
                workspace.g[j] -= interaction * workspace.q[i];
            }
            // From here the dense branch's own expressions, so the two plans differ only in how g_i was summed.
            aMix = 0.0;
            daMixDt = 0.0;
            for (int i = 0; i < count; i++) {
                workspace.sumA[i] = workspace.sqrtA[i] * workspace.g[i];
                aMix += workspace.q[i] * workspace.g[i];
                double dq = workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
                daMixDt += 2.0 * dq * workspace.g[i];
            }
        } else if (rankOneMixing) {
            double qSum = 0.0;
            double dqSum = 0.0;
            for (int i = 0; i < count; i++) {
                qSum += workspace.q[i];
                dqSum += workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
            }
            aMix = qSum * qSum;
            daMixDt = 2.0 * qSum * dqSum;
            for (int i = 0; i < count; i++) workspace.sumA[i] = workspace.sqrtA[i] * qSum;
        } else {
            for (int i = 0; i < count; i++) {
                double g = 0.0;
                for (int j = 0; j < count; j++) g += (1.0 - binaryInteractions[i][j]) * workspace.q[j];
                workspace.g[i] = g;
                workspace.sumA[i] = workspace.sqrtA[i] * g;
            }
            aMix = 0.0;
            daMixDt = 0.0;
            for (int i = 0; i < count; i++) {
                aMix += workspace.q[i] * workspace.g[i];
                double dq = workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
                daMixDt += 2.0 * dq * workspace.g[i];
            }
        }
        double reducedA = aMix * pressurePascal / (GAS_CONSTANT * GAS_CONSTANT * temperatureKelvin * temperatureKelvin);
        double reducedB = bMix * pressurePascal / (GAS_CONSTANT * temperatureKelvin);
        RootSelection rootSelection = selectRoot(reducedA, reducedB, root);
        double z = rootSelection.selectedCompressibility();
        double logRatio = Math.log((z + (1.0 + SQRT_TWO) * reducedB) / (z + (1.0 - SQRT_TWO) * reducedB));
        double attraction = reducedA / (2.0 * SQRT_TWO * Math.max(reducedB, 1.0e-300));
        // Hoisted, not changed: the argument does not depend on i, so this is the same double the loop
        // used to recompute n times, and one logarithm instead of one per component.
        double freeVolumeLog = Math.log(z - reducedB);
        for (int i = 0; i < count; i++) {
            double bRatio = coVolumes[i] / bMix;
            double attractionRatio = 2.0 * workspace.sumA[i] / aMix - bRatio;
            output.logFugacityCoefficients[i] = bRatio * (z - 1.0) - freeVolumeLog
                    - attraction * attractionRatio * logRatio;
        }
        output.compressibility = z;
        output.residualEnthalpyJoulesPerMol = GAS_CONSTANT * temperatureKelvin * (z - 1.0)
                + (temperatureKelvin * daMixDt - aMix) / (2.0 * SQRT_TWO * bMix) * logRatio;
        output.aMix = aMix;
        output.bMix = bMix;
        // Recorded, not recomputed: the derivative evaluation below needs exactly this mixture da/dT, and
        // recomputing it there would be a second arithmetic path for a number this one already has.
        output.daMixDt = daMixDt;
        output.physicalRootCount = rootSelection.physicalRootCount();
        output.rootSeparation = rootSelection.rootSeparation();
    }

    /**
     * Fills {@code output} with the value evaluation and its first derivatives on the very same root.
     *
     * <p>The values come from {@link #evaluatePrepared} itself rather than from a parallel expression, so a
     * caller mixing values from one path with derivatives from the other is differentiating the function it is
     * actually evaluating. Everything after that reads the mixture terms that evaluation left in the
     * workspace.</p>
     *
     * <p>The route is the direct one. {@code Z} is a root of the fixed cubic
     * {@code Z^3 + c2 Z^2 + c1 Z + c0} whose coefficients are polynomials in {@code (A, B)}, so the implicit
     * function theorem gives {@code dZ/dA} and {@code dZ/dB} from one derivative of that cubic, and every
     * remaining term of {@code ln phi_i} and of {@code H^R} is an explicit algebraic function of
     * {@code (Z, A, B, a, b, S_i, b_i)}. That derivative, {@code 3Z^2 + 2 c2 Z + c1}, is also exactly the
     * quantity that vanishes when two roots coalesce, which is where the derivative genuinely does not exist:
     * the guard below refuses such a state instead of returning the enormous number the division would give.
     * The partial molar residual enthalpies are not derived a second time; they are
     * {@code -R T^2 (d ln phi_i / dT)}, which is the definition of the residual enthalpy differentiated once,
     * and its mixture sum reproducing {@code H^R} is a genuine check of the temperature derivative against the
     * closed-form residual enthalpy, because the two are computed from different expressions.</p>
     */
    public void evaluateDerivatives(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Derivatives output) {
        Objects.requireNonNull(output, "output");
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, composition, root, workspace, output.evaluation);
        double[] x = workspace.composition;
        double a = output.evaluation.aMix;
        double b = output.evaluation.bMix;
        double daDt = output.evaluation.daMixDt;
        double z = output.evaluation.compressibility;
        double reducedA = a * pressurePascal / (GAS_CONSTANT * GAS_CONSTANT * temperatureKelvin * temperatureKelvin);
        double reducedB = b * pressurePascal / (GAS_CONSTANT * temperatureKelvin);
        double c2 = -(1.0 - reducedB);
        double c1 = reducedA - 3.0 * reducedB * reducedB - 2.0 * reducedB;
        double slope = Math.fma(Math.fma(3.0, z, 2.0 * c2), z, c1);
        double slopeScale = 3.0 * z * z + 2.0 * Math.abs(c2) * Math.abs(z) + Math.abs(c1);
        if (!Double.isFinite(slope) || Math.abs(slope) <= COALESCENCE_SLOPE_TOLERANCE * Math.max(1.0, slopeScale)) {
            throw new IllegalStateException("Peng-Robinson " + root + " root is too close to coalescence to differentiate");
        }
        double zByReducedA = -(z - reducedB) / slope;
        double zByReducedB = -(z * z - (6.0 * reducedB + 2.0) * z - reducedA + 2.0 * reducedB
                + 3.0 * reducedB * reducedB) / slope;
        double lower = z + (1.0 + SQRT_TWO) * reducedB;
        double upper = z + (1.0 - SQRT_TWO) * reducedB;
        double logRatio = Math.log(lower / upper);
        double attraction = reducedA / (2.0 * SQRT_TWO * Math.max(reducedB, 1.0e-300));
        double secondDaDt = secondTemperatureDerivative(
                temperatureKelvin, workspace, output.rootDt, output.rootDt2, output.crossDt);
        double reducedADt = reducedA * (daDt / a - 2.0 / temperatureKelvin);
        double reducedBDt = -reducedB / temperatureKelvin;
        double zDt = zByReducedA * reducedADt + zByReducedB * reducedBDt;
        double logRatioDt = (zDt + (1.0 + SQRT_TWO) * reducedBDt) / lower - (zDt + (1.0 - SQRT_TWO) * reducedBDt) / upper;
        double attractionDt = attraction * (reducedADt / reducedA - reducedBDt / reducedB);
        for (int i = 0; i < count; i++) {
            output.coVolumeRatio[i] = coVolumes[i] / b;
            output.attractionRatio[i] = 2.0 * workspace.sumA[i] / a - output.coVolumeRatio[i];
            double sumADt = output.rootDt[i] * (workspace.sumA[i] / workspace.sqrtA[i])
                    + workspace.sqrtA[i] * output.crossDt[i];
            double attractionRatioDt = 2.0 * (sumADt * a - workspace.sumA[i] * daDt) / (a * a);
            output.dLogPhiDt[i] = output.coVolumeRatio[i] * zDt - (zDt - reducedBDt) / (z - reducedB)
                    - (attractionDt * output.attractionRatio[i] * logRatio
                    + attraction * attractionRatioDt * logRatio
                    + attraction * output.attractionRatio[i] * logRatioDt);
        }
        for (int j = 0; j < count; j++) {
            double reducedAdn = 2.0 * reducedA * (workspace.sumA[j] - a) / a;
            double coVolumeShift = (coVolumes[j] - b) / b;
            double reducedBdn = reducedB * coVolumeShift;
            double zDn = zByReducedA * reducedAdn + zByReducedB * reducedBdn;
            double logRatioDn = (zDn + (1.0 + SQRT_TWO) * reducedBdn) / lower - (zDn + (1.0 - SQRT_TWO) * reducedBdn) / upper;
            double attractionDn = attraction * (reducedAdn / reducedA - reducedBdn / reducedB);
            double sharedTerms = -(zDn - reducedBdn) / (z - reducedB);
            double attractionShift = 2.0 * (workspace.sumA[j] - a);
            double rootAj = workspace.sqrtA[j];
            for (int i = 0; i < count; i++) {
                double bRatioDn = -output.coVolumeRatio[i] * coVolumeShift;
                double crossA = (rankOneMixing ? 1.0 : 1.0 - binaryInteractions[i][j])
                        * workspace.sqrtA[i] * rootAj;
                double attractionRatioDn = 2.0 * ((crossA - workspace.sumA[i]) * a
                        - workspace.sumA[i] * attractionShift) / (a * a) - bRatioDn;
                output.dLogPhiDn[i][j] = bRatioDn * (z - 1.0) + output.coVolumeRatio[i] * zDn + sharedTerms
                        - (attractionDn * output.attractionRatio[i] * logRatio
                        + attraction * attractionRatioDn * logRatio
                        + attraction * output.attractionRatio[i] * logRatioDn);
            }
        }
        output.dResidualEnthalpyDt = GAS_CONSTANT * (z - 1.0) + GAS_CONSTANT * temperatureKelvin * zDt
                + temperatureKelvin * secondDaDt / (2.0 * SQRT_TWO * b) * logRatio
                + (temperatureKelvin * daDt - a) / (2.0 * SQRT_TWO * b) * logRatioDt;
        for (int i = 0; i < count; i++) {
            output.partialMolarResidualEnthalpy[i] =
                    -GAS_CONSTANT * temperatureKelvin * temperatureKelvin * output.dLogPhiDt[i];
        }
    }

    /**
     * {@code d^2 a_mix / dT^2} of the mixture the preceding evaluation left in {@code workspace}, filling the
     * three caller-owned scratch vectors it needs on the way: {@code d sqrt(a_i)/dT}, its own derivative, and
     * the interaction-weighted composition sum {@code sum_j (1-k_ij) x_j d sqrt(a_j)/dT}.
     *
     * <p>One expression, two callers: {@link #evaluateDerivatives} needs the two root vectors and the cross
     * sum again for {@code d ln phi_i/dT}, and the network's volumetric block needs only the scalar. Splitting
     * it would give the same number two arithmetic paths.</p>
     */
    public double secondTemperatureDerivative(
            double temperatureKelvin, Workspace workspace, double[] rootDt, double[] rootDt2, double[] crossDt) {
        prepareRootDerivatives(temperatureKelvin, workspace, rootDt, rootDt2);
        return mixtureSecondTemperatureDerivative(workspace, rootDt, rootDt2, crossDt);
    }

    /**
     * The temperature-only half of {@link #secondTemperatureDerivative}: {@code d sqrt(a_i)/dT} and its own
     * derivative, from which every mixture temperature derivative follows. A caller that holds a prepared
     * temperature fills these once with it and calls
     * {@link #mixtureSecondTemperatureDerivative} per composition.
     */
    public void prepareRootDerivatives(
            double temperatureKelvin, Workspace workspace, double[] rootDt, double[] rootDt2) {
        for (int i = 0; i < count; i++) {
            double secondADt = criticalA[i] * kappas[i] * (1.0 + kappas[i])
                    / (2.0 * Math.sqrt(temperatureKelvin * temperatureKelvin * temperatureKelvin
                    * criticalTemperatures[i]));
            rootDt[i] = workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
            rootDt2[i] = secondADt / (2.0 * workspace.sqrtA[i])
                    - workspace.daDt[i] * workspace.daDt[i]
                    / (4.0 * workspace.sqrtA[i] * workspace.sqrtA[i] * workspace.sqrtA[i]);
        }
    }

    /** The composition-dependent half, on the root derivatives {@link #prepareRootDerivatives} filled. */
    public double mixtureSecondTemperatureDerivative(
            Workspace workspace, double[] rootDt, double[] rootDt2, double[] crossDt) {
        double[] x = workspace.composition;
        if (mixing == Mixing.SPARSE_PAIRS) {
            double interactionSum = 0.0;
            for (int i = 0; i < count; i++) interactionSum += x[i] * rootDt[i];
            for (int i = 0; i < count; i++) crossDt[i] = interactionSum;
            for (int pair = 0; pair < pairFirst.length; pair++) {
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double interaction = pairInteraction[pair];
                crossDt[i] -= interaction * x[j] * rootDt[j];
                crossDt[j] -= interaction * x[i] * rootDt[i];
            }
        } else if (rankOneMixing) {
            double interactionSum = 0.0;
            for (int i = 0; i < count; i++) interactionSum += x[i] * rootDt[i];
            for (int i = 0; i < count; i++) crossDt[i] = interactionSum;
        } else {
            for (int i = 0; i < count; i++) {
                double sum = 0.0;
                for (int j = 0; j < count; j++) sum += (1.0 - binaryInteractions[i][j]) * x[j] * rootDt[j];
                crossDt[i] = sum;
            }
        }
        double secondDaDt = 0.0;
        for (int i = 0; i < count; i++) {
            secondDaDt += 2.0 * x[i] * (rootDt2[i] * (workspace.sumA[i] / workspace.sqrtA[i])
                    + rootDt[i] * crossDt[i]);
        }
        return secondDaDt;
    }

    /** Precision qualifier; the EOS owns phase selection and its coalescence policy. */
    public static RootSelection selectRoot(double reducedA, double reducedB, Root root) {
        Objects.requireNonNull(root, "root");
        if (!Double.isFinite(reducedA) || reducedA < 0.0 || !Double.isFinite(reducedB) || reducedB < 0.0) {
            throw new IllegalArgumentException("PR reduced parameters must be finite and nonnegative");
        }
        double c2 = -(1.0 - reducedB);
        double c1 = reducedA - 3.0 * reducedB * reducedB - 2.0 * reducedB;
        double c0 = -(reducedA * reducedB - reducedB * reducedB - reducedB * reducedB * reducedB);
        double p = c1 - c2 * c2 / 3.0;
        double q = 2.0 * c2 * c2 * c2 / 27.0 - c2 * c1 / 3.0 + c0;
        double discriminant = q * q / 4.0 + p * p * p / 27.0;
        double offset = c2 / 3.0;
        double largest;
        double smallestPhysical;
        int physicalRootCount = 1;
        if (discriminant > COALESCENCE_DISCRIMINANT_TOLERANCE) {
            double sqrt = Math.sqrt(discriminant);
            largest = Math.cbrt(-q / 2.0 + sqrt) + Math.cbrt(-q / 2.0 - sqrt) - offset;
            // Compute the non-cancelling Cardano term, then use uv=-p/3 for its partner.
            // Subtracting nearly equal -q/2 and sqrt(D) before taking a cube root amplifies
            // roundoff enough to prevent the unchanged flash fugacity criterion from converging.
            // Preserve the original value when it is already backward accurate: gratuitous
            // last-bit changes to well-behaved roots can perturb finite-difference cold solves.
            if (!(largest > reducedB) || !backwardAccurate(largest, c2, c1, c0)) {
                double dominant = -q / 2.0 - Math.copySign(sqrt, q);
                double u = Math.cbrt(dominant);
                double repaired = refineRoot(u - p / (3.0 * u) - offset, c2, c1, c0, reducedB);
                if (Double.isFinite(repaired) && repaired > reducedB
                        && (!(largest > reducedB) || Math.abs(cubicResidual(repaired, c2, c1, c0))
                        < Math.abs(cubicResidual(largest, c2, c1, c0)))) largest = repaired;
            }
            smallestPhysical = largest;
        } else if (discriminant >= -COALESCENCE_DISCRIMINANT_TOLERANCE) {
            // Preserve the existing classification and arithmetic at coalescence. A multiple
            // root is ill-conditioned; unconstrained Newton refinement could change its branch.
            double sqrt = Math.sqrt(Math.max(0.0, discriminant));
            largest = Math.cbrt(-q / 2.0 + sqrt) + Math.cbrt(-q / 2.0 - sqrt) - offset;
            smallestPhysical = largest;
        } else {
            double radius = 2.0 * Math.sqrt(-p / 3.0);
            double angle = Math.acos(Math.clamp((3.0 * q / (2.0 * p)) * Math.sqrt(-3.0 / p), -1.0, 1.0)) / 3.0;
            double r0 = radius * Math.cos(angle) - offset;
            double r1 = radius * Math.cos(angle - 2.0 * Math.PI / 3.0) - offset;
            double r2 = radius * Math.cos(angle - 4.0 * Math.PI / 3.0) - offset;
            double physicalBoundary = reducedB + ROOT_EPSILON;
            // Freeze membership before refinement. Each Newton interval is derived from the
            // same immutable polynomial, not from previously refined neighboring roots.
            boolean physical0 = r0 > physicalBoundary;
            boolean physical1 = r1 > physicalBoundary;
            boolean physical2 = r2 > physicalBoundary;
            if (physical0 && !backwardAccurate(r0, c2, c1, c0)) r0 = refineRoot(r0, c2, c1, c0, physicalBoundary);
            if (physical1 && !backwardAccurate(r1, c2, c1, c0)) r1 = refineRoot(r1, c2, c1, c0, physicalBoundary);
            if (physical2 && !backwardAccurate(r2, c2, c1, c0)) r2 = refineRoot(r2, c2, c1, c0, physicalBoundary);
            largest = Math.max(r0, Math.max(r1, r2));
            smallestPhysical = Double.POSITIVE_INFINITY;
            physicalRootCount = 0;
            if (physical0) {
                smallestPhysical = r0;
                physicalRootCount++;
            }
            if (physical1) {
                smallestPhysical = Math.min(smallestPhysical, r1);
                physicalRootCount++;
            }
            if (physical2) {
                smallestPhysical = Math.min(smallestPhysical, r2);
                physicalRootCount++;
            }
            if (!Double.isFinite(smallestPhysical)) {
                smallestPhysical = largest;
                physicalRootCount = 1;
            }
        }
        double selected = root == Root.VAPOR ? largest : smallestPhysical;
        if (!Double.isFinite(selected) || selected <= reducedB) {
            throw new IllegalStateException("Peng-Robinson equation has no physical " + root + " root");
        }
        return new RootSelection(selected, physicalRootCount, Math.max(0.0, largest - smallestPhysical));
    }

    /**
     * Improves an analytic root only inside its derivative-monotonic interval. The derivative
     * extrema separate all three real roots, so an accepted step cannot move to a neighboring root.
     * Physical membership, coalescence classification, and the original seed survive any rejection.
     */
    private static double refineRoot(double seed, double c2, double c1, double c0, double physicalBoundary) {
        if (!Double.isFinite(seed) || seed <= physicalBoundary) return seed;
        double lower = physicalBoundary;
        double upper = Double.POSITIVE_INFINITY;
        double derivativeDiscriminant = Math.fma(c2, c2, -3.0 * c1);
        if (derivativeDiscriminant > 0.0) {
            double gap = Math.sqrt(derivativeDiscriminant);
            double left = (-c2 - gap) / 3.0;
            double right = (-c2 + gap) / 3.0;
            if (seed < left) {
                upper = left;
            } else if (seed > right) {
                lower = Math.max(lower, right);
            } else if (seed > left && seed < right) {
                lower = Math.max(lower, left);
                upper = right;
            } else {
                return seed;
            }
        }
        if (!(seed > lower && seed < upper)) return seed;
        double z = seed;
        double residual = cubicResidual(z, c2, c1, c0);
        for (int iteration = 0; iteration < MAXIMUM_ROOT_REFINEMENTS; iteration++) {
            double derivative = Math.fma(Math.fma(3.0, z, 2.0 * c2), z, c1);
            double derivativeScale = (3.0 * Math.abs(z) + 2.0 * Math.abs(c2)) * Math.abs(z) + Math.abs(c1);
            if (!Double.isFinite(derivative) || !Double.isFinite(residual)
                    || Math.abs(derivative) <= 32.0 * Math.ulp(Math.max(1.0, derivativeScale))) break;
            double candidate = z - residual / derivative;
            if (!Double.isFinite(candidate) || candidate == z || candidate <= lower || candidate >= upper) break;
            double candidateResidual = cubicResidual(candidate, c2, c1, c0);
            if (!Double.isFinite(candidateResidual) || Math.abs(candidateResidual) >= Math.abs(residual)) break;
            z = candidate;
            residual = candidateResidual;
        }
        return z;
    }

    private static double cubicResidual(double z, double c2, double c1, double c0) {
        return Math.fma(Math.fma(z + c2, z, c1), z, c0);
    }

    private static boolean backwardAccurate(double z, double c2, double c1, double c0) {
        double scale = Math.abs(z * z * z) + Math.abs(c2 * z * z) + Math.abs(c1 * z) + Math.abs(c0);
        double residual = cubicResidual(z, c2, c1, c0);
        return Double.isFinite(scale) && Double.isFinite(residual)
                && Math.abs(residual) <= 8.0 * Math.ulp(scale);
    }

    private static void normalizeInto(double[] source, double[] target) {
        if (source == null || source.length != target.length) throw new IllegalArgumentException("Invalid composition length");
        double total = 0.0;
        for (double value : source) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid composition");
            total += value;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("Composition has no material");
        for (int i = 0; i < target.length; i++) target[i] = source[i] / total;
    }

    private static void requireState(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || !Double.isFinite(pressurePascal) || pressurePascal <= 0.0) {
            throw new IllegalArgumentException("Invalid temperature or pressure");
        }
    }

    private void requirePackageState(double temperatureKelvin, double pressurePascal) {
        requireState(temperatureKelvin, pressurePascal);
        if (temperatureKelvin < minimumTemperatureKelvin || temperatureKelvin > maximumTemperatureKelvin
                || pressurePascal < minimumPressurePascal || pressurePascal > maximumPressurePascal) {
            throw new IllegalArgumentException("State is outside the injected property-package range");
        }
    }

    private void requireLength(double[] value, String name) {
        if (value == null || value.length != count) throw new IllegalArgumentException(name + " has invalid length");
    }

    private static double kappa(double omega) {
        return omega <= 0.491
                ? 0.37464 + 1.54226 * omega - 0.26992 * omega * omega
                : 0.379642 + 1.48503 * omega - 0.164423 * omega * omega + 0.016666 * omega * omega * omega;
    }

    public enum Root { LIQUID, VAPOR }

    public static final class Workspace {
        private final double[] composition;
        private final double[] a;
        private final double[] daDt;
        private final double[] sqrtA;
        private final double[] q;
        private final double[] g;
        private final double[] sumA;
        private long preparedTemperature = Long.MIN_VALUE;

        private Workspace(int count) {
            composition = new double[count];
            a = new double[count];
            daDt = new double[count];
            sqrtA = new double[count];
            q = new double[count];
            g = new double[count];
            sumA = new double[count];
        }

        /** The normalized composition of the last evaluation. The caller must not mutate it. */
        public double[] compositionView() { return composition; }
        /** {@code sqrt(a_i)} of the prepared temperature; zero exactly where the PR alpha term vanishes. */
        public double[] attractionRootsView() { return sqrtA; }
        /** {@code S_i = sum_j x_j a_ij} of the last evaluation. The caller must not mutate it. */
        public double[] attractionRowsView() { return sumA; }
        public void clear() {
            preparedTemperature = Long.MIN_VALUE;
            Arrays.fill(composition, 0.0);
        }
    }

    /** Caller-owned mutable output; public V3 results defensively copy the fugacity coefficients. */
    public static final class Evaluation {
        private final double[] logFugacityCoefficients;
        private double compressibility;
        private double residualEnthalpyJoulesPerMol;
        private double aMix;
        private double bMix;
        private double daMixDt;
        private int physicalRootCount;
        private double rootSeparation;

        private Evaluation(int count) {
            logFugacityCoefficients = new double[count];
        }

        public double[] logFugacityCoefficients() { return logFugacityCoefficients.clone(); }
        /** The coefficients themselves, for a caller that copies them itself. Must not be mutated. */
        public double[] logFugacityCoefficientsView() { return logFugacityCoefficients; }
        public double logFugacityCoefficient(int component) { return logFugacityCoefficients[component]; }
        public double compressibility() { return compressibility; }
        public double residualEnthalpyJoulesPerMol() { return residualEnthalpyJoulesPerMol; }
        public double aMix() { return aMix; }
        public double bMix() { return bMix; }
        /** {@code d a_mix / dT}, recorded by the evaluation that produced {@link #aMix()}. */
        public double daMixDt() { return daMixDt; }
        public int physicalRootCount() { return physicalRootCount; }
        public double rootSeparation() { return rootSeparation; }
    }

    /**
     * Caller-owned mutable derivative output; it carries the value evaluation it was differentiated from.
     *
     * <p>The residual-enthalpy composition derivatives are absent on purpose: they are
     * {@code -R T^2 (d ln phi_i / dT)}, which is already here, and a second copy of the same numbers under a
     * different name is a second thing to keep correct.</p>
     */
    public static final class Derivatives {
        private final Evaluation evaluation;
        private final double[] dLogPhiDt;
        private final double[][] dLogPhiDn;
        private final double[] partialMolarResidualEnthalpy;
        private final double[] rootDt;
        private final double[] rootDt2;
        private final double[] crossDt;
        private final double[] coVolumeRatio;
        private final double[] attractionRatio;
        private double dResidualEnthalpyDt;

        private Derivatives(int count) {
            evaluation = new Evaluation(count);
            dLogPhiDt = new double[count];
            dLogPhiDn = new double[count][count];
            partialMolarResidualEnthalpy = new double[count];
            rootDt = new double[count];
            rootDt2 = new double[count];
            crossDt = new double[count];
            coVolumeRatio = new double[count];
            attractionRatio = new double[count];
        }

        public Evaluation evaluation() { return evaluation; }
        public double[] dLogPhiDt() { return dLogPhiDt.clone(); }
        public double[][] dLogPhiDn() {
            double[][] copy = new double[dLogPhiDn.length][];
            for (int row = 0; row < copy.length; row++) copy[row] = dLogPhiDn[row].clone();
            return copy;
        }
        /** The row itself, for a caller assembling a Jacobian block. Must not be mutated. */
        public double[] dLogPhiDnRowView(int component) { return dLogPhiDn[component]; }
        public double[] dLogPhiDtView() { return dLogPhiDt; }
        public double[] partialMolarResidualEnthalpy() { return partialMolarResidualEnthalpy.clone(); }
        public double[] partialMolarResidualEnthalpyView() { return partialMolarResidualEnthalpy; }
        public double dResidualEnthalpyDt() { return dResidualEnthalpyDt; }
    }

    public record RootSelection(double selectedCompressibility, int physicalRootCount, double rootSeparation) {}
}
