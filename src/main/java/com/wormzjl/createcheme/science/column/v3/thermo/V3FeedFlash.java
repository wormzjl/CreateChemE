package com.wormzjl.createcheme.science.column.v3.thermo;

/** Bounded rigorous dry-hydrocarbon TP flash with normal all-liquid, two-phase, and all-vapor outcomes. */
final class V3FeedFlash {
    private static final int MAXIMUM_ITERATIONS = 64;
    private static final int RACHFORD_RICE_ITERATIONS = 100;
    private static final double LOG_K_TOLERANCE = 1.0e-10;
    private static final double PHASE_ENDPOINT_TOLERANCE = 1.0e-12;

    private V3FeedFlash() {}

    static V3FlashResult resolve(
            V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal, V3ThermoWorkspace workspace) {
        int componentCount = workspace.componentCount();
        model.wilsonK(temperatureKelvin, pressurePascal, workspace.wilsonK, workspace);
        for (int component = 0; component < componentCount; component++) workspace.logK[component] = Math.log(workspace.wilsonK[component]);

        double atLiquidEndpoint = rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 0.0);
        double atVaporEndpoint = rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 1.0);
        if (!Double.isFinite(atLiquidEndpoint) || !Double.isFinite(atVaporEndpoint)) {
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                    "V3 feed flash could not establish a finite Rachford-Rice phase bracket");
        }
        if (atLiquidEndpoint <= PHASE_ENDPOINT_TOLERANCE) {
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, V3Phase.LIQUID, workspace);
            return V3FlashResult.liquid(0, workspace.normalizedOverall,
                    model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, V3Phase.LIQUID, workspace),
                    "all-liquid Wilson endpoint classification");
        }
        if (atVaporEndpoint >= -PHASE_ENDPOINT_TOLERANCE) {
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, V3Phase.VAPOR, workspace);
            return V3FlashResult.vapor(0, workspace.normalizedOverall,
                    model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, V3Phase.VAPOR, workspace),
                    "all-vapor Wilson endpoint classification");
        }

        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            double vaporFraction = rachfordRiceRoot(workspace.normalizedOverall, workspace.logK);
            if (!Double.isFinite(vaporFraction) || vaporFraction <= 0.0 || vaporFraction >= 1.0) {
                throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                        "V3 feed flash lost its two-phase Rachford-Rice root");
            }
            populatePhaseCompositions(workspace.normalizedOverall, workspace.logK, vaporFraction,
                    workspace.liquidComposition, workspace.vaporComposition);
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.liquidComposition, V3Phase.LIQUID, workspace);
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.vaporComposition, V3Phase.VAPOR, workspace);
            double maximumLogKChange = 0.0;
            for (int component = 0; component < componentCount; component++) {
                double target = model.logFugacityCoefficient(V3Phase.LIQUID, component, workspace)
                        - model.logFugacityCoefficient(V3Phase.VAPOR, component, workspace);
                maximumLogKChange = Math.max(maximumLogKChange, Math.abs(target - workspace.logK[component]));
                workspace.nextLogK[component] = 0.5 * (workspace.logK[component] + target);
            }
            if (maximumLogKChange <= LOG_K_TOLERANCE) {
                double liquidEnthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.liquidComposition,
                        V3Phase.LIQUID, workspace);
                double vaporEnthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.vaporComposition,
                        V3Phase.VAPOR, workspace);
                return V3FlashResult.twoPhase(iteration, vaporFraction, workspace.liquidComposition,
                        workspace.vaporComposition, (1.0 - vaporFraction) * liquidEnthalpy + vaporFraction * vaporEnthalpy,
                        "rigorous two-phase Rachford-Rice flash");
            }
            System.arraycopy(workspace.nextLogK, 0, workspace.logK, 0, componentCount);
        }
        throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                "V3 feed flash did not converge within " + MAXIMUM_ITERATIONS + " iterations");
    }

    private static double rachfordRiceRoot(double[] composition, double[] logK) {
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

    private static void populatePhaseCompositions(
            double[] overall, double[] logK, double vaporFraction, double[] liquid, double[] vapor) {
        double liquidTotal = 0.0;
        double vaporTotal = 0.0;
        for (int component = 0; component < overall.length; component++) {
            double k = Math.exp(logK[component]);
            liquid[component] = overall[component] / (1.0 + vaporFraction * (k - 1.0));
            vapor[component] = k * liquid[component];
            liquidTotal += liquid[component];
            vaporTotal += vapor[component];
        }
        if (!Double.isFinite(liquidTotal) || !Double.isFinite(vaporTotal) || liquidTotal <= 0.0 || vaporTotal <= 0.0) {
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                    "V3 feed flash generated a nonphysical phase composition");
        }
        for (int component = 0; component < overall.length; component++) {
            liquid[component] /= liquidTotal;
            vapor[component] /= vaporTotal;
        }
    }
}
