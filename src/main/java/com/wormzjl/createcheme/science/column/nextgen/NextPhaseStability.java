package com.wormzjl.createcheme.science.column.nextgen;

/**
 * Bounded, selective Michelsen tangent-plane-distance probe for ambiguous hydrocarbon roots.
 * It resolves only the phase regime; it never performs column stage calculations.
 */
final class NextPhaseStability {
    private static final int MAXIMUM_ITERATIONS = 24;
    private static final double CONVERGENCE_TOLERANCE = 1.0e-8;
    private static final double INSTABILITY_TOLERANCE = 1.0e-8;
    private static final double COMPOSITION_FLOOR = 1.0e-300;

    private NextPhaseStability() {}

    static Result assess(NextPengRobinsonKernel kernel, double temperatureKelvin, double pressurePascal,
                         double[] overallComposition, Workspace workspace) {
        int count = kernel.componentCount();
        if (overallComposition.length != count) throw new IllegalArgumentException("Stability composition dimension differs");
        kernel.evaluatePair(temperatureKelvin, pressurePascal, overallComposition, overallComposition, workspace.prWorkspace,
                workspace.referenceLiquid, workspace.referenceVapor);
        NextPengRobinsonKernel.Evaluation reference = phasePotential(overallComposition, workspace.referenceLiquid)
                <= phasePotential(overallComposition, workspace.referenceVapor)
                ? workspace.referenceLiquid : workspace.referenceVapor;
        kernel.wilsonK(temperatureKelvin, pressurePascal, workspace.wilsonK);
        initializeIncipientCompositions(overallComposition, workspace.wilsonK,
                workspace.liquidCandidate, workspace.vaporCandidate);
        Trial liquidTrial = minimizeTpd(kernel, temperatureKelvin, pressurePascal, overallComposition, reference,
                NextPengRobinsonKernel.Root.LIQUID, workspace.liquidCandidate, workspace.liquidNext,
                workspace.liquidTrialEvaluation, workspace);
        Trial vaporTrial = minimizeTpd(kernel, temperatureKelvin, pressurePascal, overallComposition, reference,
                NextPengRobinsonKernel.Root.VAPOR, workspace.vaporCandidate, workspace.vaporNext,
                workspace.vaporTrialEvaluation, workspace);
        double minimumTpd = Math.min(liquidTrial.tpd(), vaporTrial.tpd());
        int rootCount = Math.max(workspace.referenceLiquid.physicalRootCount(), workspace.referenceVapor.physicalRootCount());
        double rootSeparation = Math.max(workspace.referenceLiquid.rootSeparation(), workspace.referenceVapor.rootSeparation());
        boolean converged = liquidTrial.converged() || vaporTrial.converged();
        boolean unstable = (liquidTrial.converged() && liquidTrial.tpd() < -INSTABILITY_TOLERANCE)
                || (vaporTrial.converged() && vaporTrial.tpd() < -INSTABILITY_TOLERANCE);
        return new Result(converged, unstable, minimumTpd, rootCount, rootSeparation,
                2 + liquidTrial.evaluations() + vaporTrial.evaluations(),
                unstable ? "bounded Michelsen instability proof"
                        : (converged ? "bounded Michelsen stability probe" : "stability probe iteration cap"));
    }

    private static Trial minimizeTpd(
            NextPengRobinsonKernel kernel, double temperatureKelvin, double pressurePascal, double[] overallComposition,
            NextPengRobinsonKernel.Evaluation reference, NextPengRobinsonKernel.Root root, double[] candidate,
            double[] next, NextPengRobinsonKernel.Evaluation trialEvaluation, Workspace workspace) {
        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            kernel.evaluate(temperatureKelvin, pressurePascal, candidate, root, workspace.prWorkspace, trialEvaluation);
            for (int component = 0; component < candidate.length; component++) {
                if (overallComposition[component] == 0.0) {
                    next[component] = 0.0;
                    continue;
                }
                double target = overallComposition[component] * Math.exp(
                        reference.logFugacityCoefficient(component) - trialEvaluation.logFugacityCoefficient(component));
                next[component] = target;
            }
            normalize(next);
            double maximumLogChange = 0.0;
            for (int component = 0; component < candidate.length; component++) {
                if (overallComposition[component] == 0.0) continue;
                maximumLogChange = Math.max(maximumLogChange,
                        Math.abs(Math.log(Math.max(next[component], COMPOSITION_FLOOR)
                                / Math.max(candidate[component], COMPOSITION_FLOOR))));
            }
            System.arraycopy(next, 0, candidate, 0, candidate.length);
            if (maximumLogChange <= CONVERGENCE_TOLERANCE) {
                kernel.evaluate(temperatureKelvin, pressurePascal, candidate, root, workspace.prWorkspace, trialEvaluation);
                return new Trial(true, iteration + 1, tangentPlaneDistance(overallComposition, candidate, reference, trialEvaluation));
            }
        }
        kernel.evaluate(temperatureKelvin, pressurePascal, candidate, root, workspace.prWorkspace, trialEvaluation);
        return new Trial(false, MAXIMUM_ITERATIONS + 1,
                tangentPlaneDistance(overallComposition, candidate, reference, trialEvaluation));
    }

    private static void initializeIncipientCompositions(double[] overall, double[] k, double[] liquid, double[] vapor) {
        for (int component = 0; component < overall.length; component++) {
            liquid[component] = overall[component] / k[component];
            vapor[component] = overall[component] * k[component];
        }
        normalize(liquid);
        normalize(vapor);
    }

    private static double phasePotential(double[] composition, NextPengRobinsonKernel.Evaluation evaluation) {
        double potential = 0.0;
        for (int component = 0; component < composition.length; component++) {
            potential += composition[component] * evaluation.logFugacityCoefficient(component);
        }
        return potential;
    }

    private static double tangentPlaneDistance(double[] overall, double[] candidate,
                                                NextPengRobinsonKernel.Evaluation reference,
                                                NextPengRobinsonKernel.Evaluation trial) {
        double tpd = 0.0;
        for (int component = 0; component < overall.length; component++) {
            if (candidate[component] == 0.0) continue;
            tpd += candidate[component] * (Math.log(candidate[component] / overall[component])
                    + trial.logFugacityCoefficient(component) - reference.logFugacityCoefficient(component));
        }
        return tpd;
    }

    private static void normalize(double[] composition) {
        double total = 0.0;
        for (double value : composition) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid stability composition");
            total += value;
        }
        if (!(total > 0.0) || !Double.isFinite(total)) throw new IllegalArgumentException("Empty stability composition");
        for (int component = 0; component < composition.length; component++) composition[component] /= total;
    }

    record Result(boolean converged, boolean unstable, double minimumTangentPlaneDistance, int physicalRootCount,
                  double rootSeparation, int phaseEvaluations, String detail) {}

    private record Trial(boolean converged, int evaluations, double tpd) {}

    static final class Workspace {
        final NextPengRobinsonKernel.Workspace prWorkspace;
        final NextPengRobinsonKernel.Evaluation referenceLiquid;
        final NextPengRobinsonKernel.Evaluation referenceVapor;
        final NextPengRobinsonKernel.Evaluation liquidTrialEvaluation;
        final NextPengRobinsonKernel.Evaluation vaporTrialEvaluation;
        final double[] wilsonK;
        final double[] liquidCandidate;
        final double[] vaporCandidate;
        final double[] liquidNext;
        final double[] vaporNext;

        Workspace(NextPengRobinsonKernel kernel) {
            int count = kernel.componentCount();
            prWorkspace = kernel.newWorkspace();
            referenceLiquid = kernel.newEvaluation();
            referenceVapor = kernel.newEvaluation();
            liquidTrialEvaluation = kernel.newEvaluation();
            vaporTrialEvaluation = kernel.newEvaluation();
            wilsonK = new double[count];
            liquidCandidate = new double[count];
            vaporCandidate = new double[count];
            liquidNext = new double[count];
            vaporNext = new double[count];
        }
    }
}
