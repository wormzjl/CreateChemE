package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import java.util.Objects;

/** Selects a sequential seed order from the local PR volatility spread, never from an arbitrary pressure threshold. */
final class V3PreconditionerSelector {
    static final double WIDE_VOLATILITY_LOG_K_SPREAD = Math.log(10_000.0);

    private V3PreconditionerSelector() {}

    static V3PreconditionerSelection select(
            V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace, V3DryMeshState seed) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        seed = Objects.requireNonNull(seed, "seed");
        double[][] ratios = V3ColumnInitializer.phaseRatios(problem, thermo, workspace,
                V3ColumnInitializer.flows(seed, true), V3ColumnInitializer.flows(seed, false),
                V3ColumnInitializer.temperatures(seed));
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double[] nodeRatios : ratios) {
            for (double ratio : nodeRatios) {
                if (!(ratio > 0.0) || !Double.isFinite(ratio)) {
                    throw new IllegalArgumentException("V3 preconditioner selector has an invalid phase ratio");
                }
                double logRatio = Math.log(ratio);
                minimum = Math.min(minimum, logRatio);
                maximum = Math.max(maximum, logRatio);
            }
        }
        return select(maximum - minimum);
    }

    static V3PreconditionerSelection select(double logKSpread) {
        if (!Double.isFinite(logKSpread) || logKSpread < 0.0) {
            throw new IllegalArgumentException("V3 preconditioner selector log-K spread is invalid");
        }
        List<V3PreconditionerId> order = logKSpread > WIDE_VOLATILITY_LOG_K_SPREAD
                ? List.of(V3PreconditionerId.SUM_RATES)
                : List.of(V3PreconditionerId.BUBBLE_POINT, V3PreconditionerId.SUM_RATES);
        return new V3PreconditionerSelection(logKSpread, order);
    }
}
