package com.wormzjl.createcheme.science.column.nextgen;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, block-local numerical seed built only from an accepted result.
 *
 * <p>This is intentionally not an exact-result cache entry, not serializable, and not shareable between block
 * positions. It carries only raw profiles and the water mask; temperature/pressure-dependent PR local models are
 * refreshed by the receiving solve before an inner iteration.
 */
public final class NextWarmState {
    private final String compatibilitySignature;
    private final double[] temperaturesKelvin;
    private final double[][] liquidFlows;
    private final double[][] vaporFlows;
    private final boolean[] wetWaterMask;

    private NextWarmState(
            String compatibilitySignature, double[] temperaturesKelvin, double[][] liquidFlows,
            double[][] vaporFlows, boolean[] wetWaterMask) {
        this.compatibilitySignature = compatibilitySignature;
        this.temperaturesKelvin = temperaturesKelvin;
        this.liquidFlows = liquidFlows;
        this.vaporFlows = vaporFlows;
        this.wetWaterMask = wetWaterMask;
    }

    public static NextWarmState fromCommitted(ColumnProblem problem, DryColumnOutcome.Success success) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(success, "success");
        DryColumnResult result = success.result();
        return new NextWarmState(signature(problem), result.nodeTemperaturesKelvin(),
                result.hydrocarbonLiquidComponentFlows(), result.hydrocarbonVaporComponentFlows(), result.waterWetMask());
    }

    public boolean isCompatibleWith(ColumnProblem problem) {
        return compatibilitySignature.equals(signature(problem));
    }

    public double[] temperaturesKelvin() { return temperaturesKelvin.clone(); }
    public double[][] liquidFlows() { return copy(liquidFlows); }
    public double[][] vaporFlows() { return copy(vaporFlows); }
    public boolean[] wetWaterMask() { return wetWaterMask.clone(); }

    private static String signature(ColumnProblem problem) {
        ColumnNextInput input = problem.input();
        StringBuilder signature = new StringBuilder(256)
                .append(problem.propertyPackage().packageId()).append('|')
                .append(problem.propertyPackage().datasetRevision()).append('|')
                .append(DryInsideOutColumnSolver.SOLVER_REVISION).append('|')
                .append(problem.propertyPackage().basis().publicAxisIds()).append('|')
                .append(input.stageCount()).append('|').append(input.crudeFeedStageNumber()).append('|')
                .append(input.organicRefluxRatio() == 0.0).append('|');
        appendSideDrawLayout(signature, input.sideDraws());
        appendUtilityLayout(signature, input.utilityFeeds());
        return signature.toString();
    }

    private static void appendSideDrawLayout(StringBuilder target, List<ColumnNextInput.SideDrawInput> draws) {
        for (ColumnNextInput.SideDrawInput draw : draws) {
            target.append(draw.stageNumber()).append(':').append(draw.basis().serializedName()).append(';');
        }
    }

    private static void appendUtilityLayout(StringBuilder target, List<ColumnNextInput.WaterSteamFeedInput> utilities) {
        target.append('|');
        for (ColumnNextInput.WaterSteamFeedInput utility : utilities) {
            target.append(utility.stageNumber()).append(':').append(utility.mode().serializedName()).append(';');
        }
    }

    private static double[][] copy(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int row = 0; row < source.length; row++) copy[row] = source[row].clone();
        return copy;
    }
}
