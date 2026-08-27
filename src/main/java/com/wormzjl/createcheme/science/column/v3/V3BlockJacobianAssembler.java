package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Extracts stage-local lower/diagonal/upper blocks from the whole-system finite-difference verification Jacobian. */
final class V3BlockJacobianAssembler {
    private static final double OFF_BAND_TOLERANCE = 1.0e-10;

    private V3BlockJacobianAssembler() {}

    static V3BlockJacobian assemble(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory) {
        V3StageBlockLayout layout = new V3StageBlockLayout(Objects.requireNonNull(problem, "problem"));
        V3FiniteDifferenceJacobian.Jacobian full = V3FiniteDifferenceJacobian.evaluate(
                Objects.requireNonNull(evaluator, "evaluator"), Objects.requireNonNull(coordinates, "coordinates"),
                Objects.requireNonNull(state, "state"), Objects.requireNonNull(workspaceFactory, "workspaceFactory"));
        double[][] values = full.values();
        double[][][] lower = new double[layout.nodeCount()][][];
        double[][][] diagonal = new double[layout.nodeCount()][][];
        double[][][] upper = new double[layout.nodeCount()][][];
        double maximumOffBandMagnitude = 0.0;
        for (int rowNode = 0; rowNode < layout.nodeCount(); rowNode++) {
            lower[rowNode] = block(values, layout, rowNode, rowNode - 1);
            diagonal[rowNode] = block(values, layout, rowNode, rowNode);
            upper[rowNode] = block(values, layout, rowNode, rowNode + 1);
            for (int columnNode = 0; columnNode < layout.nodeCount(); columnNode++) {
                if (Math.abs(columnNode - rowNode) <= 1) continue;
                maximumOffBandMagnitude = Math.max(maximumOffBandMagnitude, maximumAbsolute(block(values, layout, rowNode, columnNode)));
            }
        }
        if (maximumOffBandMagnitude > OFF_BAND_TOLERANCE) {
            throw new IllegalStateException("V3 MESH Jacobian contains an unexpected off-band coupling of "
                    + maximumOffBandMagnitude);
        }
        return new V3BlockJacobian(layout, lower, diagonal, upper, maximumOffBandMagnitude);
    }

    /**
     * Assembles a production tri-block Jacobian without whole-column property re-evaluation per coordinate.
     *
     * <p>Component-material derivatives are exact in the log-flow coordinates. VLE and energy derivatives use a
     * one-sided local PR probe at the changed node, reusing its base local terms for every column. The full coloured
     * finite-difference assembler above remains the independent verification/fallback oracle.</p>
     */
    static V3BlockJacobian assembleLocal(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale,
            V3SolveControl control) {
        problem = Objects.requireNonNull(problem, "problem");
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        state = Objects.requireNonNull(state, "state");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        differenceScale = Objects.requireNonNull(differenceScale, "differenceScale");
        control = Objects.requireNonNull(control, "control");
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        double[] baseCoordinates = coordinates.encode(state);
        V3MeshResidual baseResidual = evaluator.evaluate(state, workspaceFactory.newWorkspace());
        if (baseCoordinates.length != baseResidual.rows().size()) {
            throw new IllegalArgumentException("V3 local block Jacobian requires a square residual/coordinate map");
        }
        double[][][] lower = emptyBlocks(layout, -1);
        double[][][] diagonal = emptyBlocks(layout, 0);
        double[][][] upper = emptyBlocks(layout, 1);
        Map<V3DegreeOfFreedomLedger.UnknownId, Integer> coordinateIndexes = coordinateIndexes(coordinates);
        Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes = equationIndexes(baseResidual);
        assembleExactMaterialRows(problem, state, baseResidual, coordinates, coordinateIndexes,
                layout, lower, diagonal, upper);

        V3MeshResidualEvaluator.LocalNodeTerms[] baseTerms = new V3MeshResidualEvaluator.LocalNodeTerms[layout.nodeCount()];
        for (int node = 0; node < baseTerms.length; node++) {
            control.checkpoint();
            baseTerms[node] = evaluator.localTerms(state, node, workspaceFactory.newWorkspace());
        }
        for (int node = 0; node < layout.nodeCount(); node++) {
            for (int column = layout.start(node); column < layout.start(node) + layout.size(node); column++) {
                control.checkpoint();
                V3DegreeOfFreedomLedger.UnknownId unknown = coordinates.unknowns().get(column).id();
                LocalProbe probe = localProbe(evaluator, coordinates, baseCoordinates, column, unknown.node(),
                        workspaceFactory, differenceScale, control);
                assembleLocalThermodynamicColumn(problem, baseResidual, equationIndexes, layout,
                        lower, diagonal, upper, node, column, baseTerms[node], probe);
            }
        }
        return new V3BlockJacobian(layout, lower, diagonal, upper, 0.0);
    }

    private static double[][][] emptyBlocks(V3StageBlockLayout layout, int columnOffset) {
        double[][][] result = new double[layout.nodeCount()][][];
        for (int node = 0; node < result.length; node++) {
            int columns = node + columnOffset < 0 || node + columnOffset >= layout.nodeCount()
                    ? 0 : layout.size(node + columnOffset);
            result[node] = new double[layout.size(node)][columns];
        }
        return result;
    }

    private static Map<V3DegreeOfFreedomLedger.UnknownId, Integer> coordinateIndexes(
            V3DryMeshCoordinateMap coordinates) {
        Map<V3DegreeOfFreedomLedger.UnknownId, Integer> indexes = new HashMap<>();
        for (int index = 0; index < coordinates.unknowns().size(); index++) {
            if (indexes.put(coordinates.unknowns().get(index).id(), index) != null) {
                throw new IllegalArgumentException("V3 local block Jacobian has duplicate coordinate identities");
            }
        }
        return indexes;
    }

    private static Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes(V3MeshResidual residual) {
        Map<V3DegreeOfFreedomLedger.EquationId, Integer> indexes = new HashMap<>();
        for (int index = 0; index < residual.rows().size(); index++) {
            if (indexes.put(residual.rows().get(index).equation(), index) != null) {
                throw new IllegalArgumentException("V3 local block Jacobian has duplicate equation identities");
            }
        }
        return indexes;
    }

    private static void assembleExactMaterialRows(
            V3ColumnProblem problem,
            V3DryMeshState state,
            V3MeshResidual baseResidual,
            V3DryMeshCoordinateMap coordinates,
            Map<V3DegreeOfFreedomLedger.UnknownId, Integer> coordinateIndexes,
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper) {
        V3ColumnTopology topology = problem.topology();
        List<V3MeshResidual.Row> rows = baseResidual.rows();
        for (int row = 0; row < rows.size(); row++) {
            V3DegreeOfFreedomLedger.EquationId equation = rows.get(row).equation();
            if (equation.family() != V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE) continue;
            int node = equation.node();
            int component = equation.component();
            if (node == topology.condenserNode()) {
                addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 1, component), 1.0);
                addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node, component), -1.0);
                if (problem.condenserComponentPhases().hasLiquid(topology, node, component)) {
                    addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                            lower, diagonal, upper, row,
                            new V3DegreeOfFreedomLedger.UnknownId(
                                    V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node, component), -1.0);
                }
                continue;
            }
            if (node <= topology.trayCount()) {
                if (node == 1) {
                    if (problem.condenserComponentPhases().hasLiquid(topology, 0, component)) {
                        addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                                lower, diagonal, upper, row,
                                new V3DegreeOfFreedomLedger.UnknownId(
                                        V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 0, component),
                                organicRefluxFraction(problem));
                    }
                } else {
                    addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                            lower, diagonal, upper, row,
                            new V3DegreeOfFreedomLedger.UnknownId(
                                    V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, component), 1.0);
                }
                addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node + 1, component), 1.0);
                addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node, component), -1.0);
                addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node, component), -1.0);
                continue;
            }
            addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                    lower, diagonal, upper, row,
                    new V3DegreeOfFreedomLedger.UnknownId(
                            V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, component), 1.0);
            addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                    lower, diagonal, upper, row,
                    new V3DegreeOfFreedomLedger.UnknownId(
                            V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node, component), -1.0);
            addLogFlowDerivative(problem, state, rows.get(row), coordinates, coordinateIndexes, layout,
                    lower, diagonal, upper, row,
                    new V3DegreeOfFreedomLedger.UnknownId(
                            V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node, component), -1.0);
        }
    }

    private static void addLogFlowDerivative(
            V3ColumnProblem problem,
            V3DryMeshState state,
            V3MeshResidual.Row row,
            V3DryMeshCoordinateMap coordinates,
            Map<V3DegreeOfFreedomLedger.UnknownId, Integer> coordinateIndexes,
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper,
            int rowIndex,
            V3DegreeOfFreedomLedger.UnknownId unknown,
            double coefficient) {
        Integer column = coordinateIndexes.get(unknown);
        if (column == null) return;
        double flow = switch (unknown.family()) {
            case LIQUID_COMPONENT_FLOW -> state.liquidFlow(unknown.node(), unknown.component());
            case VAPOR_COMPONENT_FLOW -> state.vaporFlow(unknown.node(), unknown.component());
            case TEMPERATURE -> throw new IllegalArgumentException("V3 material row cannot differentiate a temperature unknown");
        };
        addGlobal(layout, lower, diagonal, upper, rowIndex, column, coefficient * flow / row.scale());
    }

    private static LocalProbe localProbe(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            double[] baseCoordinates,
            int column,
            int node,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale,
            V3SolveControl control) {
        double step = V3FiniteDifferenceJacobian.step(
                baseCoordinates[column], coordinates.unknowns().get(column).id().family(), differenceScale);
        V3MeshResidualEvaluator.LocalNodeTerms higher = localTermsOrNull(
                evaluator, coordinates, baseCoordinates, column, node, step, workspaceFactory, control);
        V3MeshResidualEvaluator.LocalNodeTerms lower = localTermsOrNull(
                evaluator, coordinates, baseCoordinates, column, node, -step, workspaceFactory, control);
        if (higher == null && lower == null) {
            throw new IllegalArgumentException("V3 local block Jacobian has no admissible thermodynamic probe");
        }
        return new LocalProbe(higher, lower, step);
    }

    private static V3MeshResidualEvaluator.LocalNodeTerms localTermsOrNull(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            double[] baseCoordinates,
            int column,
            int node,
            double signedStep,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3SolveControl control) {
        try {
            double[] candidate = baseCoordinates.clone();
            candidate[column] += signedStep;
            control.checkpoint();
            return evaluator.localTerms(coordinates.decode(candidate), node, workspaceFactory.newWorkspace());
        } catch (IllegalArgumentException | V3ThermoException unavailable) {
            return null;
        }
    }

    private static void assembleLocalThermodynamicColumn(
            V3ColumnProblem problem,
            V3MeshResidual baseResidual,
            Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes,
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper,
            int node,
            int column,
            V3MeshResidualEvaluator.LocalNodeTerms base,
            LocalProbe probe) {
        for (int component = 0; component < problem.activeComponentBasis().componentCount(); component++) {
            Integer row = equationIndexes.get(new V3DegreeOfFreedomLedger.EquationId(
                    V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM, node, component));
            if (row == null) continue;
            double derivative = probe.equilibriumDerivative(base, component);
            if (!Double.isFinite(derivative)) {
                throw new IllegalArgumentException("V3 local block VLE derivative is not finite");
            }
            addGlobal(layout, lower, diagonal, upper, row, column, derivative / baseResidual.rows().get(row).scale());
        }
        double liquidDerivative = probe.liquidEnergyDerivative(base);
        double vaporDerivative = probe.vaporEnergyDerivative(base);
        if (!Double.isFinite(liquidDerivative) || !Double.isFinite(vaporDerivative)) {
            throw new IllegalArgumentException("V3 local block energy derivative is not finite");
        }
        addEnergyDerivative(problem, baseResidual, equationIndexes, layout, lower, diagonal, upper,
                node, column, node, -(liquidDerivative + vaporDerivative));
        if (node + 1 <= problem.topology().reboilerNode()) {
            double liquidInCoefficient = node == problem.topology().condenserNode()
                    ? organicRefluxFraction(problem) : 1.0;
            addEnergyDerivative(problem, baseResidual, equationIndexes, layout, lower, diagonal, upper,
                    node + 1, column, node, liquidInCoefficient * liquidDerivative);
        }
        if (node >= 2) {
            addEnergyDerivative(problem, baseResidual, equationIndexes, layout, lower, diagonal, upper,
                    node - 1, column, node, vaporDerivative);
        }
    }

    private static void addEnergyDerivative(
            V3ColumnProblem problem,
            V3MeshResidual baseResidual,
            Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes,
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper,
            int energyNode,
            int column,
            int columnNode,
            double physicalDerivative) {
        if (energyNode < 1 || energyNode > problem.topology().reboilerNode()) return;
        Integer row = equationIndexes.get(new V3DegreeOfFreedomLedger.EquationId(
                V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE, energyNode, -1));
        if (row == null) throw new IllegalArgumentException("V3 local block Jacobian is missing an energy row");
        if (Math.abs(energyNode - columnNode) > 1) {
            throw new IllegalArgumentException("V3 local block Jacobian has an invalid energy coupling");
        }
        addGlobal(layout, lower, diagonal, upper, row, column, physicalDerivative / baseResidual.rows().get(row).scale());
    }

    private static void addGlobal(
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper,
            int row,
            int column,
            double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 local block Jacobian entry is not finite");
        int rowNode = nodeFor(layout, row);
        int columnNode = nodeFor(layout, column);
        int rowLocal = row - layout.start(rowNode);
        int columnLocal = column - layout.start(columnNode);
        if (rowNode == columnNode) diagonal[rowNode][rowLocal][columnLocal] += value;
        else if (columnNode == rowNode - 1) lower[rowNode][rowLocal][columnLocal] += value;
        else if (columnNode == rowNode + 1) upper[rowNode][rowLocal][columnLocal] += value;
        else throw new IllegalArgumentException("V3 local block Jacobian has an off-band coupling");
    }

    private static int nodeFor(V3StageBlockLayout layout, int index) {
        for (int node = 0; node < layout.nodeCount(); node++) {
            if (index >= layout.start(node) && index < layout.start(node) + layout.size(node)) return node;
        }
        throw new IllegalArgumentException("V3 local block Jacobian index is outside the stage layout");
    }

    private static double organicRefluxFraction(V3ColumnProblem problem) {
        V3ColumnSpecification.OrganicRefluxRatio specification = problem.input().specifications().stream()
                .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 local block Jacobian is missing organic reflux"));
        return specification.ratio() / (1.0 + specification.ratio());
    }

    private record LocalProbe(
            V3MeshResidualEvaluator.LocalNodeTerms higher,
            V3MeshResidualEvaluator.LocalNodeTerms lower,
            double step) {
        private LocalProbe {
            if (higher == null && lower == null) {
                throw new IllegalArgumentException("V3 local block probe has no admissible state");
            }
            if (!Double.isFinite(step) || step <= 0.0) {
                throw new IllegalArgumentException("V3 local block probe step is invalid");
            }
        }

        double equilibriumDerivative(V3MeshResidualEvaluator.LocalNodeTerms base, int component) {
            return derivative(base.equilibriumResidual(component),
                    higher == null ? Double.NaN : higher.equilibriumResidual(component),
                    lower == null ? Double.NaN : lower.equilibriumResidual(component));
        }

        double liquidEnergyDerivative(V3MeshResidualEvaluator.LocalNodeTerms base) {
            return derivative(base.liquidPhaseEnergy(),
                    higher == null ? Double.NaN : higher.liquidPhaseEnergy(),
                    lower == null ? Double.NaN : lower.liquidPhaseEnergy());
        }

        double vaporEnergyDerivative(V3MeshResidualEvaluator.LocalNodeTerms base) {
            return derivative(base.vaporPhaseEnergy(),
                    higher == null ? Double.NaN : higher.vaporPhaseEnergy(),
                    lower == null ? Double.NaN : lower.vaporPhaseEnergy());
        }

        private double derivative(double base, double higherValue, double lowerValue) {
            if (Double.isFinite(higherValue) && Double.isFinite(lowerValue)) {
                return (higherValue - lowerValue) / (2.0 * step);
            }
            return Double.isFinite(higherValue)
                    ? (higherValue - base) / step
                    : (base - lowerValue) / step;
        }
    }

    private static double[][] block(double[][] values, V3StageBlockLayout layout, int rowNode, int columnNode) {
        int rows = layout.size(rowNode);
        if (columnNode < 0 || columnNode >= layout.nodeCount()) return new double[rows][0];
        int columns = layout.size(columnNode);
        double[][] block = new double[rows][columns];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(values[layout.start(rowNode) + row], layout.start(columnNode), block[row], 0, columns);
        }
        return block;
    }

    private static double maximumAbsolute(double[][] values) {
        double maximum = 0.0;
        for (double[] row : values) for (double value : row) maximum = Math.max(maximum, Math.abs(value));
        return maximum;
    }
}
