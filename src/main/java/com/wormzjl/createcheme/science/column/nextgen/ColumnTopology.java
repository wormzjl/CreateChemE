package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Arrays;

/** One source of truth for component-balance coefficients, physical residual layout, and stream connections. */
public final class ColumnTopology {
    private final int stageCount;
    private final int feedStage;
    private final boolean vaporOnlyOverhead;
    private final double[] molarSideDrawByStage;
    private final ColumnNextInput.SideDrawInput[] authoredSideDraws;

    private ColumnTopology(
            int stageCount, int feedStage, boolean vaporOnlyOverhead, double[] draws,
            ColumnNextInput.SideDrawInput[] authored) {
        this.stageCount = stageCount;
        this.feedStage = feedStage;
        this.vaporOnlyOverhead = vaporOnlyOverhead;
        this.molarSideDrawByStage = draws;
        this.authoredSideDraws = authored;
    }

    static ColumnTopology create(ColumnNextInput input) {
        int stages = input.stageCount();
        double[] draws = new double[stages + 1]; // external stage numbering
        ColumnNextInput.SideDrawInput[] authored = input.sideDraws().toArray(ColumnNextInput.SideDrawInput[]::new);
        for (ColumnNextInput.SideDrawInput draw : authored) {
            if (draw.basis() == ColumnNextInput.AuthoredBasis.MOLAR) draws[draw.stageNumber()] = draw.authoredRate();
        }
        return new ColumnTopology(stages, input.crudeFeedStageNumber(), input.organicRefluxRatio() == 0.0, draws, authored);
    }

    public int stageCount() { return stageCount; }
    public int nodeCount() { return stageCount + 2; }
    public int feedStage() { return feedStage; }
    public boolean vaporOnlyOverhead() { return vaporOnlyOverhead; }
    /** R=0 removes the 16 identically absent HC-condensate unknowns and their matching condenser equations. */
    public int equationCount() { return 35 * nodeCount() - (vaporOnlyOverhead ? 16 : 0); }
    public int unknownCount() { return 35 * nodeCount() - (vaporOnlyOverhead ? 16 : 0); }
    public boolean hasSquareDegreesOfFreedom() { return equationCount() == unknownCount(); }
    public double molarSideDrawAtStage(int stage) { return molarSideDrawByStage[stage]; }
    public ColumnNextInput.SideDrawInput[] authoredSideDraws() { return authoredSideDraws.clone(); }

    /**
     * Assembles exactly the Section-7 HC balance rows for one component. Arrays use q=[c,l1..lS,b].
     * The caller owns all arrays and may reuse them for all sixteen components and all inner iterations.
     */
    public void assembleHydrocarbonRows(
            double[] equilibriumRatios, double[] liquidTotals, double[] vaporTotals,
            double condensateTotal, double bottomsTotal, double refluxBeta,
            double[] sideDrawMolarRates, double feedComponentFlow,
            double[] lower, double[] diagonal, double[] upper, double[] rightHandSide) {
        int n = nodeCount();
        if (equilibriumRatios.length != n || liquidTotals.length != n || vaporTotals.length != n
                || lower.length != n || diagonal.length != n || upper.length != n || rightHandSide.length != n
                || sideDrawMolarRates.length != stageCount + 1) throw new IllegalArgumentException("Invalid topology workspace length");
        Arrays.fill(lower, 0.0); Arrays.fill(diagonal, 0.0); Arrays.fill(upper, 0.0); Arrays.fill(rightHandSide, 0.0);
        double[] a = equilibriumRatios;
        diagonal[0] = 1.0 + a[0] * vaporTotals[0] / positive(condensateTotal);
        upper[0] = -a[1] * vaporTotals[1] / positive(liquidTotals[1]);
        for (int stage = 1; stage <= stageCount; stage++) {
            int row = stage;
            lower[row] = stage == 1 ? -refluxBeta : -1.0;
            double drawCoefficient = sideDrawMolarRates[stage] / positive(liquidTotals[stage]);
            diagonal[row] = 1.0 + drawCoefficient + a[stage] * vaporTotals[stage] / positive(liquidTotals[stage]);
            if (stage < stageCount) {
                upper[row] = -a[stage + 1] * vaporTotals[stage + 1] / positive(liquidTotals[stage + 1]);
            } else {
                upper[row] = -a[n - 1] * vaporTotals[n - 1] / positive(bottomsTotal);
            }
            rightHandSide[row] = stage == feedStage ? feedComponentFlow : 0.0;
        }
        lower[n - 1] = -1.0;
        diagonal[n - 1] = 1.0 + a[n - 1] * vaporTotals[n - 1] / positive(bottomsTotal);
    }

    /**
     * Assembles the canonical {@code R=0} vapor-only-overhead branch.  The omitted condensate
     * variable is identically absent; this is still the Section-7 stage-neighbour balance model,
     * not an alternate column solver.  The unknown layout is {@code [l1,...,lS,b]}.
     */
    public void assembleVaporOnlyHydrocarbonRows(
            double[] equilibriumRatios, double[] liquidTotals, double[] vaporTotals,
            double bottomsTotal, double[] sideDrawMolarRates, double feedComponentFlow,
            double[] lower, double[] diagonal, double[] upper, double[] rightHandSide) {
        int n = stageCount + 1;
        if (equilibriumRatios.length != nodeCount() || liquidTotals.length != nodeCount()
                || vaporTotals.length != nodeCount() || lower.length != n || diagonal.length != n
                || upper.length != n || rightHandSide.length != n || sideDrawMolarRates.length != stageCount + 1) {
            throw new IllegalArgumentException("Invalid vapor-only topology workspace length");
        }
        Arrays.fill(lower, 0.0);
        Arrays.fill(diagonal, 0.0);
        Arrays.fill(upper, 0.0);
        Arrays.fill(rightHandSide, 0.0);
        for (int stage = 1; stage <= stageCount; stage++) {
            int row = stage - 1;
            double drawCoefficient = sideDrawMolarRates[stage] / positive(liquidTotals[stage]);
            diagonal[row] = 1.0 + drawCoefficient
                    + equilibriumRatios[stage] * vaporTotals[stage] / positive(liquidTotals[stage]);
            if (stage > 1) lower[row] = -1.0;
            if (stage < stageCount) {
                upper[row] = -equilibriumRatios[stage + 1] * vaporTotals[stage + 1]
                        / positive(liquidTotals[stage + 1]);
            } else {
                upper[row] = -equilibriumRatios[nodeCount() - 1] * vaporTotals[nodeCount() - 1]
                        / positive(bottomsTotal);
            }
            rightHandSide[row] = stage == feedStage ? feedComponentFlow : 0.0;
        }
        lower[n - 1] = -1.0;
        diagonal[n - 1] = 1.0 + equilibriumRatios[nodeCount() - 1] * vaporTotals[nodeCount() - 1]
                / positive(bottomsTotal);
    }

    /**
     * Evaluates the physical hydrocarbon balances corresponding to the rows assembled above.
     * Keeping this beside matrix assembly prevents the residual evaluator, topology digest, and
     * production Thomas path from drifting into independently maintained coefficient conventions.
     */
    public void evaluateHydrocarbonBalanceResiduals(
            double[] liquidComponentFlows, double[] vaporComponentFlows, double[] sideDrawComponentFlows,
            double refluxBeta, double feedComponentFlow, double[] residualOutput) {
        int n = nodeCount();
        if (liquidComponentFlows.length != n || vaporComponentFlows.length != n
                || sideDrawComponentFlows.length != stageCount + 1 || residualOutput.length != n) {
            throw new IllegalArgumentException("Invalid physical-balance workspace length");
        }
        residualOutput[0] = liquidComponentFlows[0] + vaporComponentFlows[0] - vaporComponentFlows[1];
        for (int stage = 1; stage <= stageCount; stage++) {
            double liquidIn = stage == 1 ? refluxBeta * liquidComponentFlows[0]
                    : liquidComponentFlows[stage - 1];
            double vaporIn = vaporComponentFlows[stage + 1];
            double feed = stage == feedStage ? feedComponentFlow : 0.0;
            residualOutput[stage] = liquidIn + vaporIn + feed - liquidComponentFlows[stage]
                    - sideDrawComponentFlows[stage] - vaporComponentFlows[stage];
        }
        residualOutput[n - 1] = liquidComponentFlows[stageCount] - liquidComponentFlows[n - 1]
                - vaporComponentFlows[n - 1];
    }

    /** Physical balances for the reduced {@code R=0} branch, projected into the usual node layout. */
    public void evaluateVaporOnlyHydrocarbonBalanceResiduals(
            double[] liquidComponentFlows, double[] vaporComponentFlows, double[] sideDrawComponentFlows,
            double feedComponentFlow, double[] residualOutput) {
        int n = nodeCount();
        if (liquidComponentFlows.length != n || vaporComponentFlows.length != n
                || sideDrawComponentFlows.length != stageCount + 1 || residualOutput.length != n) {
            throw new IllegalArgumentException("Invalid vapor-only physical-balance workspace length");
        }
        residualOutput[0] = vaporComponentFlows[0] - vaporComponentFlows[1];
        for (int stage = 1; stage <= stageCount; stage++) {
            double liquidIn = stage == 1 ? 0.0 : liquidComponentFlows[stage - 1];
            double vaporIn = vaporComponentFlows[stage + 1];
            double feed = stage == feedStage ? feedComponentFlow : 0.0;
            residualOutput[stage] = liquidIn + vaporIn + feed - liquidComponentFlows[stage]
                    - sideDrawComponentFlows[stage] - vaporComponentFlows[stage];
        }
        residualOutput[n - 1] = liquidComponentFlows[stageCount] - liquidComponentFlows[n - 1]
                - vaporComponentFlows[n - 1];
    }

    private static double positive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException("Column trial total must be positive");
        return value;
    }
}
