package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, physical stream summary extracted only after an accepted V3 MESH solve.
 *
 * <p>This is deliberately a bounded presentation boundary. It contains calculated stream conditions and the public
 * component-axis composition, but never exposes a mutable solver state as a result or a warm-start candidate.</p>
 */
public record V3ColumnStreamProperties(
        String streamId,
        String displayName,
        String phase,
        double molarFlowMolPerSecond,
        double temperatureKelvin,
        double pressurePascal,
        List<ComponentFraction> moleFractions) {
    public static final int MAX_STREAMS = 3;
    public static final int MAX_COMPONENTS = V3ComponentBasis.MAX_COMPONENTS;

    public V3ColumnStreamProperties {
        streamId = identifier(streamId, "streamId");
        displayName = boundedText(displayName, "displayName", 48);
        phase = boundedText(phase, "phase", 16);
        if (!Double.isFinite(molarFlowMolPerSecond) || molarFlowMolPerSecond <= 0.0
                || !Double.isFinite(temperatureKelvin) || temperatureKelvin <= 0.0
                || !Double.isFinite(pressurePascal) || pressurePascal <= 0.0) {
            throw new IllegalArgumentException("V3 stream properties must be finite and physically positive");
        }
        moleFractions = List.copyOf(Objects.requireNonNull(moleFractions, "moleFractions"));
        if (moleFractions.isEmpty() || moleFractions.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("V3 stream composition exceeds the bounded public axis");
        }
        double sum = 0.0;
        for (ComponentFraction fraction : moleFractions) {
            sum += Objects.requireNonNull(fraction, "moleFraction").moleFraction();
        }
        if (!Double.isFinite(sum) || Math.abs(sum - 1.0) > 1.0e-8) {
            throw new IllegalArgumentException("V3 stream mole fractions must sum to one");
        }
    }

    /** One public-axis mole fraction, including retained exact-zero components where applicable. */
    public record ComponentFraction(String componentId, double moleFraction) {
        public ComponentFraction {
            componentId = identifier(componentId, "componentId");
            if (!Double.isFinite(moleFraction) || moleFraction < 0.0 || moleFraction > 1.0) {
                throw new IllegalArgumentException("V3 component mole fraction must be finite and within zero to one");
            }
        }
    }

    static List<V3ColumnStreamProperties> fromAccepted(V3ColumnProblem problem, V3DryMeshState state) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        V3ColumnTopology topology = problem.topology();
        if (!topology.hasLiquidPhase(topology.condenserNode())) {
            throw new IllegalArgumentException("V3 presentation streams require the accepted two-phase condenser branch");
        }
        double reflux = specification(problem.input(), V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        double liquidProductScale = 1.0 / (1.0 + reflux);
        if (!Double.isFinite(liquidProductScale) || liquidProductScale <= 0.0) {
            throw new IllegalArgumentException("V3 reflux ratio cannot form a finite liquid product scale");
        }
        List<V3ColumnStreamProperties> streams = new ArrayList<>(MAX_STREAMS);
        streams.add(stream(problem, state, topology.condenserNode(), false, 1.0,
                "overhead_vapor", "Overhead vapor", "VAPOR"));
        streams.add(stream(problem, state, topology.condenserNode(), true, liquidProductScale,
                "distillate_liquid", "Liquid distillate", "LIQUID"));
        streams.add(stream(problem, state, topology.reboilerNode(), true, 1.0,
                "bottoms_liquid", "Bottoms liquid", "LIQUID"));
        return List.copyOf(streams);
    }

    private static V3ColumnStreamProperties stream(
            V3ColumnProblem problem,
            V3DryMeshState state,
            int node,
            boolean liquid,
            double flowScale,
            String streamId,
            String displayName,
            String phase) {
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[] publicFlows = new double[active.publicBasis().componentCount()];
        for (int component = 0; component < active.componentCount(); component++) {
            publicFlows[active.publicIndex(component)] = flowScale * (liquid
                    ? state.liquidFlow(node, component) : state.vaporFlow(node, component));
        }
        double total = 0.0;
        for (double flow : publicFlows) total += flow;
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("Accepted V3 stream has no positive physical flow");
        }
        List<ComponentFraction> fractions = new ArrayList<>(publicFlows.length);
        for (int component = 0; component < publicFlows.length; component++) {
            fractions.add(new ComponentFraction(active.publicBasis().componentId(component), publicFlows[component] / total));
        }
        return new V3ColumnStreamProperties(streamId, displayName, phase, total, state.temperatureKelvin(node),
                problem.nodePressurePascal(node), fractions);
    }

    private static <S extends V3ColumnSpecification> S specification(
            V3ColumnInput input, Class<S> type) {
        for (V3ColumnSpecification specification : input.specifications()) {
            if (type.isInstance(specification)) return type.cast(specification);
        }
        throw new IllegalArgumentException("Missing V3 specification " + type.getSimpleName());
    }

    private static String identifier(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a stable identifier");
        }
        return value;
    }

    private static String boundedText(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is outside the bounded stream contract");
        }
        return value;
    }
}
