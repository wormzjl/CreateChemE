package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import java.util.Objects;

/** Selects and executes request-local sequential strategies, returning the first prepared candidate. */
final class V3HybridPreconditioner {
    static final double WIDE_VOLATILITY_LOG_K_SPREAD = Math.log(10_000.0);

    private V3HybridPreconditioner() {}

    static V3SequentialPreconditioner.Result prepare(
            V3SequentialPreconditioner.Request request, V3ThermoModel thermo, V3ThermoWorkspace workspace) {
        request = Objects.requireNonNull(request, "request");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (request.problem().hasSideDraws()) {
            return new V3SequentialPreconditioner.Result.NotApplicable(V3SequentialPreconditioner.Failure.INVALID_STATE,
                    new V3SequentialPreconditioner.Evidence(V3SequentialPreconditioner.Id.SUM_RATES, 0, "side draws"));
        }
        Selection selection = select(request.problem(), thermo, workspace, request.seed());
        V3SequentialPreconditioner.Result last = null;
        for (V3SequentialPreconditioner.Id id : selection.order()) {
            request.control().checkpoint();
            V3SequentialPreconditioner.Result candidate = strategy(id).prepare(request, thermo, workspace);
            if (candidate instanceof V3SequentialPreconditioner.Result.Prepared) return candidate;
            last = candidate;
        }
        if (last == null) throw new IllegalStateException("V3 hybrid preconditioner executed no strategy");
        return last;
    }

    static Selection select(
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
                    throw new IllegalArgumentException("V3 hybrid preconditioner has an invalid phase ratio");
                }
                double logRatio = Math.log(ratio);
                minimum = Math.min(minimum, logRatio);
                maximum = Math.max(maximum, logRatio);
            }
        }
        return select(maximum - minimum);
    }

    static Selection select(double logKSpread) {
        if (!Double.isFinite(logKSpread) || logKSpread < 0.0) {
            throw new IllegalArgumentException("V3 hybrid preconditioner log-K spread is invalid");
        }
        List<V3SequentialPreconditioner.Id> order = logKSpread > WIDE_VOLATILITY_LOG_K_SPREAD
                ? List.of(V3SequentialPreconditioner.Id.SUM_RATES)
                : List.of(V3SequentialPreconditioner.Id.BUBBLE_POINT, V3SequentialPreconditioner.Id.SUM_RATES);
        return new Selection(logKSpread, order);
    }

    private static V3SequentialPreconditioner strategy(V3SequentialPreconditioner.Id id) {
        return switch (id) {
            case BUBBLE_POINT -> V3BubblePointPreconditioner.INSTANCE;
            case SUM_RATES -> V3SumRatesPreconditioner.INSTANCE;
        };
    }

    /** Immutable ordered strategy plan chosen from a fresh local thermodynamic profile. */
    record Selection(double logKSpread, List<V3SequentialPreconditioner.Id> order) {
        Selection {
            if (!Double.isFinite(logKSpread) || logKSpread < 0.0) {
                throw new IllegalArgumentException("V3 preconditioner log-K spread is invalid");
            }
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            if (order.isEmpty() || order.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("V3 preconditioner selection has no usable strategy order");
            }
        }
    }
}
