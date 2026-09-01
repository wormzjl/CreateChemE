package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Objects;

/**
 * Reference-validated, phase-specific TP flash. This is an explicit approximation, not a
 * replacement for the unrestricted flash used by physical acceptance and phase selection.
 * All scratch and frozen support belong to this call; the property package is never changed.
 */
final class V3TruncatedFlash {
    private static final int MAXIMUM_ITERATIONS = 64;
    private static final int RR_ITERATIONS = 100;
    private static final double LOG_K_TOLERANCE = 1.0e-10;

    private V3TruncatedFlash() {}

    static V3FlashResult resolve(V3PengRobinsonThermo model, double temperature, double pressure,
                                 V3ThermoWorkspace workspace, V3TraceTruncationPolicy policy,
                                 Runnable checkpoint) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.run();
        // Never use truncation to rescue an unavailable rigorous reference. Cancellation and
        // reference failures propagate unchanged, outside the reduced-attempt recovery boundary.
        V3FlashResult reference = V3FeedFlash.resolve(model, temperature, pressure, workspace);
        checkpoint.run();
        if (!policy.enabled()) return reference;
        if (reference.phase() != V3FeedPhase.TWO_PHASE) {
            return reference.withTruncationEvidence(referenceOnly(reference, policy,
                    V3FlashTruncationEvidence.Status.SINGLE_PHASE, "Single-phase reference; no phase truncation"));
        }
        double[] overall = workspace.normalizedOverall.clone();
        V3FlashPhaseSupport support = V3FlashPhaseSupport.derive(overall, reference, policy);
        if (support.isIdentity()) {
            return reference.withTruncationEvidence(referenceOnly(reference, policy,
                    V3FlashTruncationEvidence.Status.NO_CANDIDATES, "No unambiguous phase-trace candidates"));
        }
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(workspace);
        Progress progress = new Progress();
        try {
            V3FlashResult candidate = reduced(model, temperature, pressure, overall, reference,
                    support, workspace, checkpoint, progress);
            checkpoint.run();
            double[] targetLogK = new double[overall.length];
            for (int component = 0; component < targetLogK.length; component++) {
                targetLogK[component] = model.logFugacityCoefficient(V3Phase.LIQUID, component, workspace)
                        - model.logFugacityCoefficient(V3Phase.VAPOR, component, workspace);
            }
            V3FlashTruncationEvidence evidence = assess(overall, reference, candidate, support, policy, targetLogK);
            if (evidence.status() == V3FlashTruncationEvidence.Status.APPLIED) {
                return candidate.withTruncationEvidence(evidence);
            }
            snapshot.restore(model, temperature, pressure, workspace);
            return reference.withTruncationEvidence(evidence);
        } catch (V3ThermoException reducedFailure) {
            // Only expected numerical/property failures are recoverable. In particular, do not
            // catch CancellationException (or an unrelated programming/callback exception).
            checkpoint.run();
            snapshot.restore(model, temperature, pressure, workspace);
            return reference.withTruncationEvidence(new V3FlashTruncationEvidence(
                    V3FlashTruncationEvidence.Status.FALLBACK, policy.cutoffMoleFraction(),
                    support.omittedLiquidCount(), support.omittedVaporCount(), reference.iterations(),
                    progress.iterations, false, 0.0, 0.0, 0.0, 0.0, 0.0,
                    reference.molarEnthalpyJoulesPerMol(),
                    "Untruncated reference restored after reduced flash " + reducedFailure.code()));
        }
    }

    private static V3FlashResult reduced(V3PengRobinsonThermo model, double temperature, double pressure,
                                         double[] overall, V3FlashResult reference, V3FlashPhaseSupport support,
                                         V3ThermoWorkspace workspace, Runnable checkpoint, Progress progress) {
        double[] referenceLiquid = reference.liquidComposition();
        double[] referenceVapor = reference.vaporComposition();
        for (int component = 0; component < overall.length; component++) {
            workspace.logK[component] = support.phaseSupport(component) == V3FlashPhaseSupport.PhaseSupport.BOTH
                    ? Math.log(referenceVapor[component]) - Math.log(referenceLiquid[component]) : 0.0;
        }
        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            checkpoint.run();
            progress.iterations = iteration;
            double beta = rachfordRiceRoot(overall, workspace.logK, support);
            if (!(beta > 0.0 && beta < 1.0)) throw failure("Reduced flash lost a required phase");
            split(overall, workspace.logK, beta, support, workspace.liquidComposition, workspace.vaporComposition);
            requireNormalized(workspace.liquidComposition);
            requireNormalized(workspace.vaporComposition);
            model.evaluateInto(temperature, pressure, workspace.liquidComposition, V3Phase.LIQUID, workspace);
            model.evaluateInto(temperature, pressure, workspace.vaporComposition, V3Phase.VAPOR, workspace);
            double maximumError = 0.0;
            for (int component = 0; component < overall.length; component++) {
                if (support.phaseSupport(component) != V3FlashPhaseSupport.PhaseSupport.BOTH) continue;
                double target = model.logFugacityCoefficient(V3Phase.LIQUID, component, workspace)
                        - model.logFugacityCoefficient(V3Phase.VAPOR, component, workspace);
                if (!Double.isFinite(target)) throw failure("Reduced flash has a nonfinite fugacity ratio");
                maximumError = Math.max(maximumError, Math.abs(target - workspace.logK[component]));
                workspace.nextLogK[component] = 0.5 * (workspace.logK[component] + target);
            }
            if (maximumError <= LOG_K_TOLERANCE) {
                double liquidEnthalpy = model.phaseMolarEnthalpy(temperature, workspace.liquidComposition,
                        V3Phase.LIQUID, workspace);
                double vaporEnthalpy = model.phaseMolarEnthalpy(temperature, workspace.vaporComposition,
                        V3Phase.VAPOR, workspace);
                return V3FlashResult.twoPhase(iteration, beta, workspace.liquidComposition, workspace.vaporComposition,
                        (1.0 - beta) * liquidEnthalpy + beta * vaporEnthalpy,
                        "Reference-validated phase-truncated TP flash");
            }
            for (int component = 0; component < overall.length; component++) {
                if (support.phaseSupport(component) == V3FlashPhaseSupport.PhaseSupport.BOTH) {
                    workspace.logK[component] = workspace.nextLogK[component];
                }
            }
        }
        throw failure("Reduced flash did not converge within 64 iterations");
    }

    /** A phase-only component contributes finite material without an infinite or zero log-K sentinel. */
    static double rachfordRiceRoot(double[] overall, double[] logK, V3FlashPhaseSupport support) {
        double lower = 0.0;
        double liquidOnly = 0.0;
        for (int component = 0; component < overall.length; component++) {
            switch (support.phaseSupport(component)) {
                case VAPOR_ONLY -> lower += overall[component];
                case LIQUID_ONLY -> liquidOnly += overall[component];
                default -> { }
            }
        }
        double upper = 1.0 - liquidOnly;
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower < 0.0 || upper > 1.0 || lower > upper) {
            throw failure("Reduced flash has inconsistent phase-only material bounds");
        }
        if (lower == upper) return lower;
        double lowerResidual = rachfordRiceResidual(overall, logK, lower, support);
        double upperResidual = rachfordRiceResidual(overall, logK, upper, support);
        if (Double.isNaN(lowerResidual) || Double.isNaN(upperResidual) || lowerResidual < 0.0 || upperResidual > 0.0) {
            throw failure("Reduced flash has no bracketed Rachford-Rice root");
        }
        if (lowerResidual == 0.0) return lower;
        if (upperResidual == 0.0) return upper;
        for (int iteration = 0; iteration < RR_ITERATIONS; iteration++) {
            double beta = 0.5 * (lower + upper);
            if (beta == lower || beta == upper) return beta;
            double residual = rachfordRiceResidual(overall, logK, beta, support);
            if (!Double.isFinite(residual)) throw failure("Reduced flash has a nonfinite interior RR residual");
            if (residual == 0.0) return beta;
            if (residual > 0.0) lower = beta;
            else upper = beta;
        }
        return 0.5 * (lower + upper);
    }

    static double rachfordRiceResidual(double[] overall, double[] logK, double beta, V3FlashPhaseSupport support) {
        double residual = 0.0;
        for (int component = 0; component < overall.length; component++) {
            if (overall[component] == 0.0) continue;
            residual += switch (support.phaseSupport(component)) {
                case ABSENT -> throw failure("Positive overall material cannot have absent support");
                case LIQUID_ONLY -> -overall[component] / (1.0 - beta);
                case VAPOR_ONLY -> overall[component] / beta;
                case BOTH -> {
                    double k = Math.exp(logK[component]);
                    double denominator = (1.0 - beta) + beta * k;
                    if (!Double.isFinite(k) || !Double.isFinite(denominator) || denominator <= 0.0) {
                        throw failure("Reduced flash has an invalid finite K or RR denominator");
                    }
                    yield overall[component] * Math.expm1(logK[component]) / denominator;
                }
            };
        }
        return residual;
    }

    /** Allocates each component first, then divides by phase totals. Never clips and renormalizes a finished split. */
    static void split(double[] overall, double[] logK, double beta, V3FlashPhaseSupport support,
                      double[] liquid, double[] vapor) {
        for (int component = 0; component < overall.length; component++) {
            double liquidAmount;
            double vaporAmount;
            switch (support.phaseSupport(component)) {
                case ABSENT -> {
                    if (overall[component] != 0.0) throw failure("Positive overall material cannot have absent support");
                    liquidAmount = 0.0;
                    vaporAmount = 0.0;
                }
                case LIQUID_ONLY -> {
                    liquidAmount = overall[component];
                    vaporAmount = 0.0;
                }
                case VAPOR_ONLY -> {
                    liquidAmount = 0.0;
                    vaporAmount = overall[component];
                }
                case BOTH -> {
                    double vaporWeight = beta * Math.exp(logK[component]);
                    double liquidWeight = 1.0 - beta;
                    double denominator = liquidWeight + vaporWeight;
                    if (!Double.isFinite(denominator) || denominator <= 0.0) throw failure("Invalid reduced phase weights");
                    if (vaporWeight <= liquidWeight) {
                        vaporAmount = overall[component] * (vaporWeight / denominator);
                        liquidAmount = overall[component] - vaporAmount;
                    } else {
                        liquidAmount = overall[component] * (liquidWeight / denominator);
                        vaporAmount = overall[component] - liquidAmount;
                    }
                    if (!(liquidAmount > 0.0 && vaporAmount > 0.0)) throw failure("Retained phase allocation underflowed");
                }
                default -> throw new AssertionError("Unknown phase support");
            }
            liquid[component] = liquidAmount / (1.0 - beta);
            vapor[component] = vaporAmount / beta;
        }
    }

    /** Fresh full-basis reference/error check, also exposed package-locally for adversarial numerical tests. */
    static V3FlashTruncationEvidence assess(double[] overall, V3FlashResult reference, V3FlashResult candidate,
                                           V3FlashPhaseSupport support, V3TraceTruncationPolicy policy,
                                           double[] targetLogK) {
        double[] referenceLiquid = reference.liquidComposition();
        double[] referenceVapor = reference.vaporComposition();
        double[] liquid = candidate.liquidComposition();
        double[] vapor = candidate.vaporComposition();
        boolean compatible = reference.phase() == V3FeedPhase.TWO_PHASE && candidate.phase() == reference.phase()
                && liquid.length == overall.length && vapor.length == overall.length;
        if (!compatible) {
            return rejectedWithoutMetrics(reference, support, policy, candidate.iterations(), "Phase classification or basis changed");
        }
        double beta = candidate.vaporFraction();
        double referenceBeta = reference.vaporFraction();
        double allocationError = 0.0;
        double compositionError = 0.0;
        double closureError = 0.0;
        boolean materialClosed = true;
        boolean retainedEquilibrium = true;
        boolean omittedStillTrace = true;
        double logCutoff = Math.log(policy.cutoffMoleFraction());
        for (int component = 0; component < overall.length; component++) {
            double liquidAmount = (1.0 - beta) * liquid[component];
            double vaporAmount = beta * vapor[component];
            double componentClosure = Math.abs(liquidAmount + vaporAmount - overall[component]);
            closureError = Math.max(closureError, componentClosure);
            // This guard is roundoff-only and independent of the requested trace cutoff.
            materialClosed &= componentClosure <= 64.0 * Math.ulp(overall[component]);
            allocationError += Math.abs(liquidAmount - (1.0 - referenceBeta) * referenceLiquid[component])
                    + Math.abs(vaporAmount - referenceBeta * referenceVapor[component]);
            compositionError = Math.max(compositionError, Math.max(Math.abs(liquid[component] - referenceLiquid[component]),
                    Math.abs(vapor[component] - referenceVapor[component])));
            switch (support.phaseSupport(component)) {
                case ABSENT -> materialClosed &= liquidAmount == 0.0 && vaporAmount == 0.0;
                case BOTH -> retainedEquilibrium &= liquid[component] > 0.0 && vapor[component] > 0.0
                        && Double.isFinite(targetLogK[component])
                        && Math.abs(Math.log(vapor[component]) - Math.log(liquid[component]) - targetLogK[component])
                        <= LOG_K_TOLERANCE;
                case LIQUID_ONLY -> omittedStillTrace &= vapor[component] == 0.0 && liquid[component] > 0.0
                        && Double.isFinite(targetLogK[component])
                        && Math.log(liquid[component]) + targetLogK[component] < logCutoff;
                case VAPOR_ONLY -> omittedStillTrace &= liquid[component] == 0.0 && vapor[component] > 0.0
                        && Double.isFinite(targetLogK[component])
                        && Math.log(vapor[component]) - targetLogK[component] < logCutoff;
            }
        }
        double betaError = Math.abs(beta - referenceBeta);
        double enthalpyError = Math.abs(candidate.molarEnthalpyJoulesPerMol() - reference.molarEnthalpyJoulesPerMol());
        boolean withinBudgets = allocationError <= policy.maximumPhaseAllocationError()
                && betaError <= policy.maximumVaporFractionError()
                && compositionError <= policy.maximumPhaseCompositionError()
                && enthalpyError <= policy.maximumEnthalpyErrorJoulesPerMol(reference.molarEnthalpyJoulesPerMol());
        boolean accepted = materialClosed && retainedEquilibrium && omittedStillTrace && withinBudgets;
        String detail = accepted ? "Frozen phase support verified against unrestricted reference"
                : !materialClosed ? "Reference restored: component material closure exceeded roundoff"
                : !retainedEquilibrium ? "Reference restored: retained-component equilibrium failed"
                : !omittedStillTrace ? "Reference restored: an omitted phase contribution requires reactivation"
                : "Reference restored: phase allocation or enthalpy error budget exceeded";
        return new V3FlashTruncationEvidence(accepted ? V3FlashTruncationEvidence.Status.APPLIED
                : V3FlashTruncationEvidence.Status.FALLBACK, policy.cutoffMoleFraction(), support.omittedLiquidCount(),
                support.omittedVaporCount(), reference.iterations(), candidate.iterations(), true,
                allocationError, betaError, compositionError, closureError, enthalpyError,
                reference.molarEnthalpyJoulesPerMol(), detail);
    }

    private static V3FlashTruncationEvidence referenceOnly(V3FlashResult reference, V3TraceTruncationPolicy policy,
                                                           V3FlashTruncationEvidence.Status status, String detail) {
        return new V3FlashTruncationEvidence(status, policy.cutoffMoleFraction(), 0, 0, reference.iterations(), 0,
                false, 0.0, 0.0, 0.0, 0.0, 0.0, reference.molarEnthalpyJoulesPerMol(), detail);
    }

    private static V3FlashTruncationEvidence rejectedWithoutMetrics(V3FlashResult reference, V3FlashPhaseSupport support,
                                                                    V3TraceTruncationPolicy policy, int iterations,
                                                                    String detail) {
        return new V3FlashTruncationEvidence(V3FlashTruncationEvidence.Status.FALLBACK, policy.cutoffMoleFraction(),
                support.omittedLiquidCount(), support.omittedVaporCount(), reference.iterations(), iterations,
                false, 0.0, 0.0, 0.0, 0.0, 0.0, reference.molarEnthalpyJoulesPerMol(), detail);
    }

    private static void requireNormalized(double[] composition) {
        double total = 0.0;
        for (double value : composition) {
            if (!Double.isFinite(value) || value < 0.0) throw failure("Reduced flash has an invalid phase composition");
            total += value;
        }
        if (Math.abs(total - 1.0) > 64.0 * composition.length * Math.ulp(1.0)) {
            throw failure("Reduced flash failed phase normalization without material deletion");
        }
    }

    private static V3ThermoException failure(String detail) {
        return new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, detail);
    }

    private static final class Progress {
        private int iterations;
    }

    private record WorkspaceSnapshot(double[] logK, double[] nextLogK, double[] liquid, double[] vapor) {
        private WorkspaceSnapshot(V3ThermoWorkspace workspace) {
            this(workspace.logK.clone(), workspace.nextLogK.clone(), workspace.liquidComposition.clone(),
                    workspace.vaporComposition.clone());
        }

        private void restore(V3PengRobinsonThermo model, double temperature, double pressure, V3ThermoWorkspace workspace) {
            System.arraycopy(logK, 0, workspace.logK, 0, logK.length);
            System.arraycopy(nextLogK, 0, workspace.nextLogK, 0, nextLogK.length);
            System.arraycopy(liquid, 0, workspace.liquidComposition, 0, liquid.length);
            System.arraycopy(vapor, 0, workspace.vaporComposition, 0, vapor.length);
            model.evaluateInto(temperature, pressure, liquid, V3Phase.LIQUID, workspace);
            model.evaluateInto(temperature, pressure, vapor, V3Phase.VAPOR, workspace);
        }
    }
}
