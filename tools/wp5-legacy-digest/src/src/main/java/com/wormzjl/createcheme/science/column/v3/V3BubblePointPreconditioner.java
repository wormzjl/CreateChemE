package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/** Bounded component-material/bubble-point seed strategy extracted from the existing recovery projection. */
final class V3BubblePointPreconditioner implements V3SequentialPreconditioner {
    static final V3BubblePointPreconditioner INSTANCE = new V3BubblePointPreconditioner();

    private V3BubblePointPreconditioner() {}

    @Override
    public V3SequentialPreconditioner.Id id() {
        return V3SequentialPreconditioner.Id.BUBBLE_POINT;
    }

    @Override
    public V3SequentialPreconditioner.Result prepare(
            V3SequentialPreconditioner.Request request, V3ThermoModel thermo, V3ThermoWorkspace workspace) {
        request = Objects.requireNonNull(request, "request");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        request.control().checkpoint();
        try {
            V3DryMeshState state = V3ColumnInitializer.projectMaterialBalancesAtFixedTemperature(
                    request.problem(), thermo, workspace, request.seed());
            request.control().checkpoint();
            return new V3SequentialPreconditioner.Result.Prepared(state,
                    new V3SequentialPreconditioner.Evidence(id(), 3, "bounded material/bubble-point projection"));
        } catch (V3ThermoException failure) {
            return new V3SequentialPreconditioner.Result.Failed(V3SequentialPreconditioner.Failure.PROPERTY_DOMAIN,
                    new V3SequentialPreconditioner.Evidence(id(), 0, boundedDetail(failure.getMessage())));
        } catch (IllegalArgumentException failure) {
            return new V3SequentialPreconditioner.Result.Failed(V3SequentialPreconditioner.Failure.INVALID_STATE,
                    new V3SequentialPreconditioner.Evidence(id(), 0, boundedDetail(failure.getMessage())));
        }
    }

    private static String boundedDetail(String detail) {
        if (detail == null || detail.isBlank()) return "bubble-point projection did not provide a detail";
        return detail.length() <= 256 ? detail : detail.substring(0, 256);
    }
}
