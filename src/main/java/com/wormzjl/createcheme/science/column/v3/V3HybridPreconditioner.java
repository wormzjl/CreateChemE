package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/** Executes the selector's request-local sequential strategies and returns the first prepared candidate. */
final class V3HybridPreconditioner {
    private V3HybridPreconditioner() {}

    static V3PreconditionerResult prepare(
            V3PreconditionerRequest request, V3ThermoModel thermo, V3ThermoWorkspace workspace) {
        request = Objects.requireNonNull(request, "request");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        V3PreconditionerSelection selection = V3PreconditionerSelector.select(
                request.problem(), thermo, workspace, request.seed());
        V3PreconditionerResult last = null;
        for (V3PreconditionerId id : selection.order()) {
            request.control().checkpoint();
            V3PreconditionerResult candidate = strategy(id).prepare(request, thermo, workspace);
            if (candidate instanceof V3PreconditionerResult.Prepared) return candidate;
            last = candidate;
        }
        if (last == null) throw new IllegalStateException("V3 hybrid preconditioner executed no strategy");
        return last;
    }

    private static V3SequentialPreconditioner strategy(V3PreconditionerId id) {
        return switch (id) {
            case BUBBLE_POINT -> V3BubblePointPreconditioner.INSTANCE;
            case SUM_RATES -> V3SumRatesPreconditioner.INSTANCE;
        };
    }
}
