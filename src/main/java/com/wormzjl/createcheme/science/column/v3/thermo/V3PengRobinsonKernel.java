package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Allocation-free PR78 hydrocarbon kernel for a V3 property package. One {@link Workspace} belongs to one solve;
 * liquid and vapor evaluations at a common temperature share its temperature-dependent pure-component arithmetic.
 */
final class V3PengRobinsonKernel {
    static final double GAS_CONSTANT = 8.31446261815324;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final double ROOT_EPSILON = 1.0e-12;
    private static final double COALESCENCE_DISCRIMINANT_TOLERANCE = 1.0e-16;
    private static final int MAXIMUM_ROOT_REFINEMENTS = 4;

    private final V3PropertyPackage propertyPackage;
    private final int count;
    private final double[] criticalA;
    private final double[] coVolumes;
    private final double[] kappas;
    private final double[][] binaryInteractions;
    private final boolean rankOneMixing;
    private final double minimumTemperatureKelvin;
    private final double maximumTemperatureKelvin;
    private final double minimumPressurePascal;
    private final double maximumPressurePascal;

    V3PengRobinsonKernel(V3PropertyPackage propertyPackage) {
        this.propertyPackage = Objects.requireNonNull(propertyPackage, "propertyPackage");
        this.minimumTemperatureKelvin = propertyPackage.minimumTemperatureKelvin();
        this.maximumTemperatureKelvin = propertyPackage.maximumTemperatureKelvin();
        this.minimumPressurePascal = propertyPackage.minimumPressurePascal();
        this.maximumPressurePascal = propertyPackage.maximumPressurePascal();
        this.count = propertyPackage.componentBasis().componentCount();
        this.criticalA = new double[count];
        this.coVolumes = new double[count];
        this.kappas = new double[count];
        this.binaryInteractions = propertyPackage.binaryInteractions();
        if (binaryInteractions.length != count) throw new IllegalArgumentException("Invalid BIP matrix dimension");
        boolean allZero = true;
        for (int i = 0; i < count; i++) {
            if (binaryInteractions[i].length != count) throw new IllegalArgumentException("Invalid BIP matrix row");
            V3PropertyComponent component = propertyPackage.component(i);
            criticalA[i] = 0.45724 * GAS_CONSTANT * GAS_CONSTANT
                    * component.criticalTemperatureKelvin() * component.criticalTemperatureKelvin()
                    / component.criticalPressurePascal();
            coVolumes[i] = 0.07780 * GAS_CONSTANT * component.criticalTemperatureKelvin()
                    / component.criticalPressurePascal();
            kappas[i] = kappa(component.acentricFactor());
            for (int j = 0; j < count; j++) {
                double value = binaryInteractions[i][j];
                if (!Double.isFinite(value) || Math.abs(value - binaryInteractions[j][i]) > 1.0e-14
                        || (i == j && Math.abs(value) > 1.0e-14)) {
                    throw new IllegalArgumentException("BIP matrix must be finite, symmetric, and zero diagonal");
                }
                allZero &= value == 0.0;
            }
        }
        this.rankOneMixing = allZero;
    }

    int componentCount() { return count; }
    boolean usesRankOneMixing() { return rankOneMixing; }
    Workspace newWorkspace() { return new Workspace(count); }
    Evaluation newEvaluation() { return new Evaluation(count); }

    /** Wilson K initialisation only; accepted states use rigorous fugacity refreshes. */
    void wilsonK(double temperatureKelvin, double pressurePascal, double[] output) {
        requirePackageState(temperatureKelvin, pressurePascal);
        requireLength(output, "output");
        for (int i = 0; i < count; i++) {
            V3PropertyComponent component = propertyPackage.component(i);
            output[i] = Math.exp(Math.clamp(
                    Math.log(component.criticalPressurePascal() / pressurePascal)
                            + 5.373 * (1.0 + component.acentricFactor())
                            * (1.0 - component.criticalTemperatureKelvin() / temperatureKelvin),
                    -40.0, 40.0));
        }
    }

    void evaluate(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Evaluation output) {
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, composition, root, workspace, output);
    }

    /** Shares temperature-dependent pure-component values between two phase evaluations. */
    void evaluatePair(
            double temperatureKelvin, double pressurePascal, double[] liquidComposition, double[] vaporComposition,
            Workspace workspace, Evaluation liquidOutput, Evaluation vaporOutput) {
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, liquidComposition, Root.LIQUID, workspace, liquidOutput);
        evaluatePrepared(temperatureKelvin, pressurePascal, vaporComposition, Root.VAPOR, workspace, vaporOutput);
    }

    void prepareTemperature(double temperatureKelvin, Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < minimumTemperatureKelvin
                || temperatureKelvin > maximumTemperatureKelvin) {
            throw new IllegalArgumentException("Temperature is outside package range");
        }
        if (workspace.preparedTemperature == Double.doubleToLongBits(temperatureKelvin)) return;
        for (int i = 0; i < count; i++) {
            V3PropertyComponent component = propertyPackage.component(i);
            double sqrtTr = Math.sqrt(temperatureKelvin / component.criticalTemperatureKelvin());
            double alphaTerm = 1.0 + kappas[i] * (1.0 - sqrtTr);
            workspace.a[i] = criticalA[i] * alphaTerm * alphaTerm;
            workspace.daDt[i] = -criticalA[i] * kappas[i] * alphaTerm
                    / Math.sqrt(temperatureKelvin * component.criticalTemperatureKelvin());
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
        if (rankOneMixing) {
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
        for (int i = 0; i < count; i++) {
            double bRatio = coVolumes[i] / bMix;
            double attractionRatio = 2.0 * workspace.sumA[i] / aMix - bRatio;
            output.logFugacityCoefficients[i] = bRatio * (z - 1.0) - Math.log(z - reducedB)
                    - attraction * attractionRatio * logRatio;
        }
        output.compressibility = z;
        output.residualEnthalpyJoulesPerMol = GAS_CONSTANT * temperatureKelvin * (z - 1.0)
                + (temperatureKelvin * daMixDt - aMix) / (2.0 * SQRT_TWO * bMix) * logRatio;
        output.aMix = aMix;
        output.bMix = bMix;
        output.physicalRootCount = rootSelection.physicalRootCount();
        output.rootSeparation = rootSelection.rootSeparation();
    }

    /** Package-local precision qualifier; the EOS owns phase selection and its coalescence policy. */
    static RootSelection selectRoot(double reducedA, double reducedB, Root root) {
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

    enum Root { LIQUID, VAPOR }

    static final class Workspace {
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

        void clear() {
            preparedTemperature = Long.MIN_VALUE;
            Arrays.fill(composition, 0.0);
        }
    }

    /** Caller-owned mutable output; public V3 results defensively copy the fugacity coefficients. */
    static final class Evaluation {
        private final double[] logFugacityCoefficients;
        private double compressibility;
        private double residualEnthalpyJoulesPerMol;
        private double aMix;
        private double bMix;
        private int physicalRootCount;
        private double rootSeparation;

        private Evaluation(int count) {
            logFugacityCoefficients = new double[count];
        }

        double[] logFugacityCoefficients() { return logFugacityCoefficients.clone(); }
        double logFugacityCoefficient(int component) { return logFugacityCoefficients[component]; }
        double compressibility() { return compressibility; }
        double residualEnthalpyJoulesPerMol() { return residualEnthalpyJoulesPerMol; }
        double aMix() { return aMix; }
        double bMix() { return bMix; }
        int physicalRootCount() { return physicalRootCount; }
        double rootSeparation() { return rootSeparation; }
    }

    record RootSelection(double selectedCompressibility, int physicalRootCount, double rootSeparation) {}
}
