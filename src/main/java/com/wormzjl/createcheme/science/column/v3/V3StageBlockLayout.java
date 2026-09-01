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
            int expected = topology.hasTemperatureUnknown(node) ? 1 : 0;
            for (int component = 0; component < problem.activeComponentBasis().componentCount(); component++) {
                if (!problem.truncationSupport().retains(node, component)) continue;
                if (topology.hasVaporPhase(node)) expected++;
                if (problem.condenserComponentPhases().hasLiquid(topology, node, component)) expected++;
            }
            if (expected <= 0 || offset + expected > unknowns.size()) {
                throw new IllegalArgumentException("V3 MESH ledger block size is invalid");
            }
            sizes[node] = expected;
            validateUnknownBlock(unknowns, problem, node, offset, expected);
            validateEquationBlock(equations, problem, node, offset, expected);
            offset += expected;
        }
        if (offset != unknowns.size()) throw new IllegalArgumentException("V3 MESH ledger has trailing non-stage variables or equations");
    }

    int nodeCount() { return starts.length; }
    int start(int node) { requireNode(node); return starts[node]; }
    int size(int node) { requireNode(node); return sizes[node]; }

    private static void validateUnknownBlock(
            List<V3DegreeOfFreedomLedger.Unknown> unknowns, V3ColumnProblem problem, int node, int start, int size) {
        V3ColumnTopology topology = problem.topology();
        for (int index = start; index < start + size; index++) {
            if (unknowns.get(index).id().node() != node) {
                throw new IllegalArgumentException("V3 MESH unknown ledger is not contiguous by node");
            }
        }
        for (int component = 0; component < problem.activeComponentBasis().componentCount(); component++) {
            int activeComponent = component;
            boolean retained = problem.truncationSupport().retains(node, component);
            boolean expectedLiquid = retained && problem.condenserComponentPhases().hasLiquid(topology, node, component);
            boolean expectedVapor = retained && topology.hasVaporPhase(node);
            boolean foundLiquid = unknowns.subList(start, start + size).stream().anyMatch(unknown -> unknown.id().family()
                    == V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW
                    && unknown.id().component() == activeComponent);
            boolean foundVapor = unknowns.subList(start, start + size).stream().anyMatch(unknown -> unknown.id().family()
                    == V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW
                    && unknown.id().component() == activeComponent);
            if (foundLiquid != expectedLiquid || foundVapor != expectedVapor) {
                throw new IllegalArgumentException("V3 MESH unknown ledger disagrees with component phase/support map");
            }
        }
    }

    private static void validateEquationBlock(
            List<V3DegreeOfFreedomLedger.Equation> equations, V3ColumnProblem problem, int node, int start, int size) {
        V3ColumnTopology topology = problem.topology();
        for (int index = start; index < start + size; index++) {
            if (equations.get(index).id().node() != node) {
                throw new IllegalArgumentException("V3 MESH equation ledger is not contiguous by node");
            }
        }
        for (int component = 0; component < problem.activeComponentBasis().componentCount(); component++) {
            int activeComponent = component;
            boolean retained = problem.truncationSupport().retains(node, component);
            boolean expectedVle = retained
                    && problem.condenserComponentPhases().hasVaporLiquidEquilibrium(topology, node, component);
            boolean foundMaterial = equations.subList(start, start + size).stream().anyMatch(equation -> equation.id().family()
                    == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE
                    && equation.id().component() == activeComponent);
            boolean foundVle = equations.subList(start, start + size).stream().anyMatch(equation -> equation.id().family()
                    == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM
                    && equation.id().component() == activeComponent);
            if (foundMaterial != retained || foundVle != expectedVle) {
                throw new IllegalArgumentException("V3 MESH equation ledger disagrees with component phase/support map");
            }
        }
    }

    private void requireNode(int node) {
        if (node < 0 || node >= starts.length) throw new IndexOutOfBoundsException("V3 MESH block node is outside the topology");
    }
}
