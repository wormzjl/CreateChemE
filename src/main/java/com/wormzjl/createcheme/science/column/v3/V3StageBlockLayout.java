package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Validates and exposes the ledger's contiguous per-node residual/unknown block layout. */
final class V3StageBlockLayout {
    private final int[] starts;
    private final int[] sizes;

    V3StageBlockLayout(V3ColumnProblem problem) {
        problem = Objects.requireNonNull(problem, "problem");
        V3ColumnTopology topology = problem.topology();
        List<V3DegreeOfFreedomLedger.Unknown> unknowns = problem.degreeOfFreedomLedger().unknowns();
        List<V3DegreeOfFreedomLedger.Equation> equations = problem.degreeOfFreedomLedger().equations();
        if (unknowns.size() != equations.size()) throw new IllegalArgumentException("V3 MESH block layout requires square DOF");
        starts = new int[topology.nodeCount()];
        sizes = new int[topology.nodeCount()];
        int offset = 0;
        for (int node = 0; node < topology.nodeCount(); node++) {
            starts[node] = offset;
            int expected = 2 * problem.input().componentBasis().componentCount() + (topology.hasTemperatureUnknown(node) ? 1 : 0)
                    - (topology.hasLiquidPhase(node) ? 0 : problem.input().componentBasis().componentCount());
            if (expected <= 0 || offset + expected > unknowns.size()) {
                throw new IllegalArgumentException("V3 MESH ledger block size is invalid");
            }
            sizes[node] = expected;
            validateUnknownBlock(unknowns, topology, node, offset, expected);
            validateEquationBlock(equations, topology, node, offset, expected);
            offset += expected;
        }
        if (offset != unknowns.size()) throw new IllegalArgumentException("V3 MESH ledger has trailing non-stage variables or equations");
    }

    int nodeCount() { return starts.length; }
    int start(int node) { requireNode(node); return starts[node]; }
    int size(int node) { requireNode(node); return sizes[node]; }

    private static void validateUnknownBlock(
            List<V3DegreeOfFreedomLedger.Unknown> unknowns, V3ColumnTopology topology, int node, int start, int size) {
        for (int index = start; index < start + size; index++) {
            if (unknowns.get(index).id().node() != node) {
                throw new IllegalArgumentException("V3 MESH unknown ledger is not contiguous by node");
            }
        }
        boolean expectedLiquid = topology.hasLiquidPhase(node);
        long liquids = unknowns.subList(start, start + size).stream().filter(unknown -> unknown.id().family()
                == V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW).count();
        if ((liquids > 0) != expectedLiquid) throw new IllegalArgumentException("V3 MESH unknown ledger phase map disagrees with topology");
    }

    private static void validateEquationBlock(
            List<V3DegreeOfFreedomLedger.Equation> equations, V3ColumnTopology topology, int node, int start, int size) {
        for (int index = start; index < start + size; index++) {
            if (equations.get(index).id().node() != node) {
                throw new IllegalArgumentException("V3 MESH equation ledger is not contiguous by node");
            }
        }
        boolean expectedVle = topology.hasVaporLiquidEquilibriumEquation(node);
        long equilibrium = equations.subList(start, start + size).stream().filter(equation -> equation.id().family()
                == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM).count();
        if ((equilibrium > 0) != expectedVle) throw new IllegalArgumentException("V3 MESH equation ledger phase map disagrees with topology");
    }

    private void requireNode(int node) {
        if (node < 0 || node >= starts.length) throw new IndexOutOfBoundsException("V3 MESH block node is outside the topology");
    }
}
