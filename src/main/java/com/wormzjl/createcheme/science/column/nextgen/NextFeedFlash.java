package com.wormzjl.createcheme.science.column.nextgen;

/**
 * Bounded TP feed flash used only to resolve the feed state before the column inner loop starts.
 * It is deliberately not a stage-flash or alternate column solver.
 */
final class NextFeedFlash {
    private static final int MAXIMUM_ITERATIONS = 32;
    private static final int RACHFORD_RICE_ITERATIONS = 80;
    private static final double LOG_K_TOLERANCE = 1.0e-8;

    private NextFeedFlash() {}

    static Result resolve(NextPengRobinsonKernel kernel, double temperatureKelvin, double pressurePascal,
                          double[] feedComposition, Workspace workspace) {
        int count = kernel.componentCount();
        if (feedComposition.length != count) throw new IllegalArgumentException("Feed composition dimension differs");
        kernel.wilsonK(temperatureKelvin, pressurePascal, workspace.k);
        for (int component = 0; component < count; component++) {
            workspace.logK[component] = Math.log(workspace.k[component]);
        }
        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            double vaporFraction = rachfordRice(feedComposition, workspace.logK);
            if (!Double.isFinite(vaporFraction)) {
                return Result.failure(iteration, "no two-phase Rachford-Rice bracket");
            }
            for (int component = 0; component < count; component++) {
                double k = Math.exp(workspace.logK[component]);
                double liquid = feedComposition[component] / (1.0 + vaporFraction * (k - 1.0));
                workspace.liquidComposition[component] = liquid;
                workspace.vaporComposition[component] = k * liquid;
            }
            kernel.evaluatePair(temperatureKelvin, pressurePascal, workspace.liquidComposition, workspace.vaporComposition,
                    workspace.prWorkspace, workspace.liquidEvaluation, workspace.vaporEvaluation);
            double maximumLogKChange = 0.0;
            for (int component = 0; component < count; component++) {
                double target = workspace.liquidEvaluation.logFugacityCoefficient(component)
                        - workspace.vaporEvaluation.logFugacityCoefficient(component);
                maximumLogKChange = Math.max(maximumLogKChange, Math.abs(target - workspace.logK[component]));
                workspace.nextLogK[component] = 0.5 * (workspace.logK[component] + target);
            }
            if (maximumLogKChange <= LOG_K_TOLERANCE) {
                return Result.success(iteration, vaporFraction, workspace.liquidEvaluation.residualEnthalpyJoulesPerMol(),
                        workspace.vaporEvaluation.residualEnthalpyJoulesPerMol(), maximumLogKChange);
            }
            System.arraycopy(workspace.nextLogK, 0, workspace.logK, 0, count);
        }
        return Result.failure(MAXIMUM_ITERATIONS, "TP feed flash did not converge within the fixed iteration cap");
    }

    private static double rachfordRice(double[] composition, double[] logK) {
        double atLiquidLimit = rachfordRiceResidual(composition, logK, 0.0);
        double atVaporLimit = rachfordRiceResidual(composition, logK, 1.0);
        if (!(atLiquidLimit > 0.0) || !(atVaporLimit < 0.0)) return Double.NaN;
        double lower = 0.0;
        double upper = 1.0;
        for (int iteration = 0; iteration < RACHFORD_RICE_ITERATIONS; iteration++) {
            double midpoint = 0.5 * (lower + upper);
            double residual = rachfordRiceResidual(composition, logK, midpoint);
            if (!Double.isFinite(residual)) return Double.NaN;
            if (residual > 0.0) lower = midpoint;
            else upper = midpoint;
        }
        return 0.5 * (lower + upper);
    }

    private static double rachfordRiceResidual(double[] composition, double[] logK, double vaporFraction) {
        double residual = 0.0;
        for (int component = 0; component < composition.length; component++) {
            double kMinusOne = Math.exp(logK[component]) - 1.0;
            double denominator = 1.0 + vaporFraction * kMinusOne;
            if (!(denominator > 0.0) || !Double.isFinite(denominator)) return Double.NaN;
            residual += composition[component] * kMinusOne / denominator;
        }
        return residual;
    }

    record Result(boolean converged, int iterations, double vaporFraction, double liquidResidualEnthalpy,
                  double vaporResidualEnthalpy, double maximumLogKChange, String detail) {
        static Result success(int iterations, double vaporFraction, double liquidResidualEnthalpy,
                              double vaporResidualEnthalpy, double maximumLogKChange) {
            return new Result(true, iterations, vaporFraction, liquidResidualEnthalpy, vaporResidualEnthalpy,
                    maximumLogKChange, "two-phase TP feed flash");
        }

        static Result failure(int iterations, String detail) {
            return new Result(false, iterations, Double.NaN, Double.NaN, Double.NaN, Double.NaN, detail);
        }
    }

    static final class Workspace {
        final NextPengRobinsonKernel.Workspace prWorkspace;
        final NextPengRobinsonKernel.Evaluation liquidEvaluation;
        final NextPengRobinsonKernel.Evaluation vaporEvaluation;
        final double[] k;
        final double[] logK;
        final double[] nextLogK;
        final double[] liquidComposition;
        final double[] vaporComposition;

        Workspace(NextPengRobinsonKernel kernel) {
            int count = kernel.componentCount();
            prWorkspace = kernel.newWorkspace();
            liquidEvaluation = kernel.newEvaluation();
            vaporEvaluation = kernel.newEvaluation();
            k = new double[count];
            logK = new double[count];
            nextLogK = new double[count];
            liquidComposition = new double[count];
            vaporComposition = new double[count];
        }
    }
}
