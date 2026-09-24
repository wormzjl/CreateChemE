package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, immutable inspection of an accepted column. Contains physical values only, never solver workspaces.
 * Node zero is the condenser; N+1 is the sump. Compositions are hydrocarbon-only. A zero phase has zero fractions.
 * Free water at node zero is the decanted product, at trays is downward aqueous flow, and at the sump is zero.
 */
public record V3ColumnInspection(V3ColumnInput input, List<Node> nodes, V3AcceptanceAudit audit) {
    public V3ColumnInspection {
        Objects.requireNonNull(input, "input");
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(audit, "audit");
        if (nodes.size() != input.stageCount() + 2 || audit.checks().size() > 64 || !audit.accepted())
            throw new IllegalArgumentException("Inspection requires bounded accepted data");
        V3ColumnProblemResolver.validateInput(input, nodes.stream().mapToDouble(Node::pressurePascal).toArray());
        for (Node node : nodes)
            if (node.liquidFractions().size() != input.componentBasis().componentCount())
                throw new IllegalArgumentException("Inspection component axis differs from its input");
    }

    /** Called only with an accepted state at the scientific publication boundary. */
    static V3ColumnInspection fromAccepted(V3ColumnProblem problem, V3DryMeshState state, V3AcceptanceAudit audit) {
        List<Node> nodes = new ArrayList<>(state.nodeCount());
        for (int n = 0; n < state.nodeCount(); n++) {
            double liquid = 0, vapor = 0;
            for (int c = 0; c < state.componentCount(); c++) {
                liquid += state.liquidFlow(n, c);
                vapor += state.vaporFlow(n, c);
            }
            int publicCount = problem.input().componentBasis().componentCount();
            List<Double> x = new ArrayList<>(java.util.Collections.nCopies(publicCount, 0.0));
            List<Double> y = new ArrayList<>(java.util.Collections.nCopies(publicCount, 0.0));
            for (int c = 0; c < state.componentCount(); c++) {
                int publicIndex = problem.activeComponentBasis().publicIndex(c);
                x.set(publicIndex, liquid == 0 ? 0 : state.liquidFlow(n, c) / liquid);
                y.set(publicIndex, vapor == 0 ? 0 : state.vaporFlow(n, c) / vapor);
            }
            double water = n == 0 ? problem.waterCondenserSplit(state).freeWaterFlowMolPerSecond()
                    : problem.freeWaterFlowMolPerSecond(state, n);
            nodes.add(new Node(state.temperatureKelvin(n), problem.nodePressurePascal(n), liquid, vapor,
                    problem.waterVaporFlow(state, n), water, x, y));
        }
        return new V3ColumnInspection(problem.input(), nodes, audit);
    }

    public record Node(double temperatureKelvin, double pressurePascal, double liquidMolPerSecond,
                       double vaporMolPerSecond, double waterVaporMolPerSecond, double freeWaterMolPerSecond,
                       List<Double> liquidFractions, List<Double> vaporFractions) {
        public Node {
            positive(temperatureKelvin); positive(pressurePascal);
            nonnegative(liquidMolPerSecond); nonnegative(vaporMolPerSecond);
            nonnegative(waterVaporMolPerSecond); nonnegative(freeWaterMolPerSecond);
            liquidFractions = fractions(liquidFractions, liquidMolPerSecond);
            vaporFractions = fractions(vaporFractions, vaporMolPerSecond);
            if (liquidFractions.size() != vaporFractions.size())
                throw new IllegalArgumentException("Different phase component axes");
        }
        private static List<Double> fractions(List<Double> values, double flow) {
            values = List.copyOf(values);
            if (values.isEmpty() || values.size() > V3ComponentBasis.MAX_COMPONENTS)
                throw new IllegalArgumentException("Unbounded inspection composition");
            double total = 0;
            for (double value : values) {
                nonnegative(value);
                if (value > 1) throw new IllegalArgumentException("Invalid inspection fraction");
                total += value;
            }
            if (Math.abs(total - (flow == 0 ? 0 : 1)) > 1e-8)
                throw new IllegalArgumentException("Inspection phase fractions do not close");
            return values;
        }
        private static void positive(double value) {
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid physical value");
        }
        private static void nonnegative(double value) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid flow or fraction");
        }
    }
}

