package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
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
     *
     * <p>One decoded base state and one thermodynamic workspace serve the whole assembly. Both are shared
     * rather than rebuilt per probe because a probe cannot observe the difference: it moves one coordinate, so
     * every other entry of its state decodes to the bits the base already holds, and the workspace it writes
     * through carries nothing between calls but a temperature-keyed cache of pure functions of that
     * temperature, which every evaluation either hits on the exact same bits or refills.</p>
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
        V3DryMeshState decodedBase = decodedBaseOrNull(coordinates, baseCoordinates);
        V3ThermoWorkspace workspace = workspaceFactory.newWorkspace();
        V3MeshResidual baseResidual = evaluator.evaluate(state, workspace);
        if (baseCoordinates.length != baseResidual.rows().size()) {
            throw new IllegalArgumentException("V3 local block Jacobian requires a square residual/coordinate map");
        }
        double[][][] lower = emptyBlocks(layout, -1);
        double[][][] diagonal = emptyBlocks(layout, 0);
        double[][][] upper = emptyBlocks(layout, 1);
        Map<V3DegreeOfFreedomLedger.UnknownId, Integer> coordinateIndexes = coordinateIndexes(coordinates);
        Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes = equationIndexes(baseResidual);
        assembleExactMaterialRows(problem, state, baseResidual, coordinateIndexes,
                layout, lower, diagonal, upper);
        assembleExactFreeWaterCouplings(problem, state, baseResidual, coordinateIndexes, equationIndexes,
                layout, lower, diagonal, upper);

        V3MeshResidualEvaluator.LocalNodeTerms[] baseTerms = new V3MeshResidualEvaluator.LocalNodeTerms[layout.nodeCount()];
        for (int node = 0; node < baseTerms.length; node++) {
            control.checkpoint();
            baseTerms[node] = evaluator.localTerms(state, node, workspace);
        }
        for (int node = 0; node < layout.nodeCount(); node++) {
            for (int column = layout.start(node); column < layout.start(node) + layout.size(node); column++) {
                control.checkpoint();
                V3DegreeOfFreedomLedger.UnknownId unknown = coordinates.unknowns().get(column).id();
                LocalProbe probe = localProbe(evaluator, coordinates, decodedBase, baseCoordinates, column,
                        unknown.node(), workspace, differenceScale, control);
                assembleLocalThermodynamicColumn(problem, state, unknown, baseResidual, equationIndexes, layout,
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

    /**
     * The exact derivatives of every row with respect to a free-water column of the tray above.
     *
     * <p>A free-water unknown {@code F_m} enters the rows of tray {@code m + 1} only through that tray's
     * water vapour {@code W_(m+1) = S_(m+1) + F_m}: it dilutes the hydrocarbon vapour in the equilibrium
     * rows, it is the whole content of the saturation row, and it carries {@code h_v} out of tray
     * {@code m + 1} and into tray {@code m}. None of those is a node-{@code (m+1)} coordinate, so the local
     * thermodynamic probe — which perturbs a node's own columns — cannot see them; they are written here in
     * closed form instead. Every one lands in the diagonal or the immediate lower block, which is why a wet
     * tray does not widen the band. The uncoloured finite-difference assembler above remains the oracle.</p>
     */
    private static void assembleExactFreeWaterCouplings(
            V3ColumnProblem problem,
            V3DryMeshState state,
            V3MeshResidual baseResidual,
            Map<V3DegreeOfFreedomLedger.UnknownId, Integer> coordinateIndexes,
            Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes,
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper) {
        if (!problem.hasWetTrays()) return;
        List<V3MeshResidual.Row> rows = baseResidual.rows();
        for (int source = 1; source <= problem.topology().trayCount(); source++) {
            if (!problem.hasFreeWaterUnknown(source)) continue;
            Integer column = coordinateIndexes.get(new V3DegreeOfFreedomLedger.UnknownId(
                    V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW, source, -1));
            if (column == null) continue;
            int node = source + 1;
            if (node > problem.topology().reboilerNode()) continue;
            // d(F)/d(log-flow coordinate) = F.
            double free = state.freeWaterFlow(source);
            double water = problem.waterVaporFlow(state, node);
            double hydrocarbon = V3WetTraySet.hydrocarbonVaporTotal(state, node);
            double vaporEnthalpy = com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties
                    .vaporMolarEnthalpy(state.temperatureKelvin(node));
            if (!(water > 0.0) || !Double.isFinite(hydrocarbon) || !Double.isFinite(vaporEnthalpy)) {
                throw new IllegalArgumentException("V3 local block free-water coupling has no physical water state");
            }
            for (int component = 0; component < problem.activeComponentBasis().componentCount(); component++) {
                Integer row = equationIndexes.get(new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM, node, component));
                if (row == null) continue;
                addGlobal(layout, lower, diagonal, upper, row, column,
                        -free / (hydrocarbon + water) / rows.get(row).scale());
            }
            Integer saturation = equationIndexes.get(new V3DegreeOfFreedomLedger.EquationId(
                    V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION, node, -1));
            if (saturation != null) {
                addGlobal(layout, lower, diagonal, upper, saturation, column,
                        free * (1.0 / water - 1.0 / (hydrocarbon + water)) / rows.get(saturation).scale());
            }
            // W_(m+1) leaves tray m+1 in its vapour outlet and enters tray m as the vapour from below.
            addFreeWaterEnergyCoupling(equationIndexes, rows, layout, lower, diagonal, upper,
                    node, column, -free * vaporEnthalpy);
            addFreeWaterEnergyCoupling(equationIndexes, rows, layout, lower, diagonal, upper,
                    source, column, free * vaporEnthalpy);
        }
    }

    private static void addFreeWaterEnergyCoupling(
            Map<V3DegreeOfFreedomLedger.EquationId, Integer> equationIndexes,
            List<V3MeshResidual.Row> rows,
            V3StageBlockLayout layout,
            double[][][] lower,
            double[][][] diagonal,
            double[][][] upper,
            int energyNode,
            int column,
            double physicalDerivative) {
        Integer row = equationIndexes.get(new V3DegreeOfFreedomLedger.EquationId(
                V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE, energyNode, -1));
        if (row == null) throw new IllegalArgumentException("V3 local block Jacobian is missing a wet energy row");
        addGlobal(layout, lower, diagonal, upper, row, column, physicalDerivative / rows.get(row).scale());
    }

    private static void assembleExactMaterialRows(
            V3ColumnProblem problem,
            V3DryMeshState state,
            V3MeshResidual baseResidual,
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
            if (node > 1 && problem.nodeSideDrawMolPerSecond(node - 1) > 0.0) {
                double withdrawal = problem.liquidWithdrawalFraction(state, node - 1);
                double total = V3SideDraws.liquidTotal(state, node - 1);
                for (int k = 0; k < state.componentCount(); k++) {
                    addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                            lower, diagonal, upper, row,
                            new V3DegreeOfFreedomLedger.UnknownId(
                                    V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, k),
                            withdrawal * state.liquidFlow(node - 1, component) / total);
                }
            }
            if (node == topology.condenserNode()) {
                addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 1, component), 1.0);
                addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node, component), -1.0);
                if (problem.condenserComponentPhases().hasLiquid(topology, node, component)) {
                    addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                            lower, diagonal, upper, row,
                            new V3DegreeOfFreedomLedger.UnknownId(
                                    V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node, component), -1.0);
                }
                continue;
            }
            if (node <= topology.trayCount()) {
                if (node == 1) {
                    if (problem.condenserComponentPhases().hasLiquid(topology, 0, component)) {
                        addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                                lower, diagonal, upper, row,
                                new V3DegreeOfFreedomLedger.UnknownId(
                                        V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 0, component),
                                organicRefluxFraction(problem));
                    }
                } else {
                    addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                            lower, diagonal, upper, row,
                            new V3DegreeOfFreedomLedger.UnknownId(
                                    V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, component),
                            1.0 - problem.liquidWithdrawalFraction(state, node - 1));
                }
                addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node + 1, component), 1.0);
                addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node, component), -1.0);
                addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                        lower, diagonal, upper, row,
                        new V3DegreeOfFreedomLedger.UnknownId(
                                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node, component), -1.0);
                continue;
            }
            addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                    lower, diagonal, upper, row,
                    new V3DegreeOfFreedomLedger.UnknownId(
                            V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, component),
                    1.0 - problem.liquidWithdrawalFraction(state, node - 1));
            addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                    lower, diagonal, upper, row,
                    new V3DegreeOfFreedomLedger.UnknownId(
                            V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, node, component), -1.0);
            addLogFlowDerivative(problem, state, rows.get(row), coordinateIndexes, layout,
                    lower, diagonal, upper, row,
                    new V3DegreeOfFreedomLedger.UnknownId(
                            V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, node, component), -1.0);
        }
    }

    private static void addLogFlowDerivative(
            V3ColumnProblem problem,
            V3DryMeshState state,
            V3MeshResidual.Row row,
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
            case TEMPERATURE, FREE_WATER_FLOW ->
                    throw new IllegalArgumentException("V3 material row cannot differentiate a non-component unknown");
        };
        addGlobal(layout, lower, diagonal, upper, rowIndex, column, coefficient * flow / row.scale());
    }

    /**
     * The one decoded state every probe of this assembly shares, or {@code null} when the base does not decode.
     *
     * <p>Each probe needs the state that differs from this one in a single entry, so decoding the base once
     * replaces one whole-column decode per probe with a single-entry one. The null is the honest answer for a
     * base vector that has no decoded state at all: those assemblies keep decoding each candidate in full, so
     * a probe that would have been admissible on its own still is.</p>
     */
    private static V3DryMeshState decodedBaseOrNull(V3DryMeshCoordinateMap coordinates, double[] baseCoordinates) {
        try {
            return coordinates.decode(baseCoordinates);
        } catch (IllegalArgumentException undecodable) {
            return null;
        }
    }

    private static LocalProbe localProbe(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState decodedBase,
            double[] baseCoordinates,
            int column,
            int node,
            V3ThermoWorkspace workspace,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale,
            V3SolveControl control) {
        double step = V3FiniteDifferenceJacobian.step(
                baseCoordinates[column], coordinates.unknowns().get(column).id().family(), differenceScale);
        V3MeshResidualEvaluator.LocalNodeTerms higher = localTermsOrNull(
                evaluator, coordinates, decodedBase, baseCoordinates, column, node, step, workspace, control);
        V3MeshResidualEvaluator.LocalNodeTerms lower = localTermsOrNull(
                evaluator, coordinates, decodedBase, baseCoordinates, column, node, -step, workspace, control);
        if (higher == null && lower == null) {
            throw new IllegalArgumentException("V3 local block Jacobian has no admissible thermodynamic probe");
        }
        return new LocalProbe(higher, lower, step);
    }

    private static V3MeshResidualEvaluator.LocalNodeTerms localTermsOrNull(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState decodedBase,
            double[] baseCoordinates,
            int column,
            int node,
            double signedStep,
            V3ThermoWorkspace workspace,
            V3SolveControl control) {
        try {
            control.checkpoint();
            return evaluator.localTerms(perturbedState(coordinates, decodedBase, baseCoordinates, column, signedStep),
                    node, workspace);
        } catch (IllegalArgumentException | V3ThermoException unavailable) {
            return null;
        }
    }

    private static V3DryMeshState perturbedState(
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState decodedBase,
            double[] baseCoordinates,
            int column,
            double signedStep) {
        if (decodedBase != null) {
            return coordinates.decodePerturbed(decodedBase, baseCoordinates, column, signedStep);
        }
        double[] candidate = baseCoordinates.clone();
        candidate[column] += signedStep;
        return coordinates.decode(candidate);
    }

    private static void assembleLocalThermodynamicColumn(
            V3ColumnProblem problem,
            V3DryMeshState state,
            V3DegreeOfFreedomLedger.UnknownId unknown,
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
        Integer saturationRow = equationIndexes.get(new V3DegreeOfFreedomLedger.EquationId(
                V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION, node, -1));
        if (saturationRow != null) {
            double derivative = probe.waterSaturationDerivative(base);
            if (!Double.isFinite(derivative)) {
                throw new IllegalArgumentException("V3 local block water-saturation derivative is not finite");
            }
            addGlobal(layout, lower, diagonal, upper, saturationRow, column,
                    derivative / baseResidual.rows().get(saturationRow).scale());
        }
        double liquidDerivative = probe.liquidEnergyDerivative(base);
        double vaporDerivative = probe.vaporEnergyDerivative(base);
        double freeWaterDerivative = probe.freeWaterEnergyDerivative(base);
        if (!Double.isFinite(liquidDerivative) || !Double.isFinite(vaporDerivative)
                || !Double.isFinite(freeWaterDerivative)) {
            throw new IllegalArgumentException("V3 local block energy derivative is not finite");
        }
        addEnergyDerivative(problem, baseResidual, equationIndexes, layout, lower, diagonal, upper,
                node, column, node, -(liquidDerivative + vaporDerivative + freeWaterDerivative));
        if (node + 1 <= problem.topology().reboilerNode()) {
            // The immiscible aqueous liquid is not withdrawn by a side draw, so it falls with coefficient one.
            addEnergyDerivative(problem, baseResidual, equationIndexes, layout, lower, diagonal, upper,
                    node + 1, column, node, freeWaterDerivative);
        }
        if (node + 1 <= problem.topology().reboilerNode()) {
            double liquidInCoefficient = node == problem.topology().condenserNode()
                    ? organicRefluxFraction(problem) : 1.0 - problem.liquidWithdrawalFraction(state, node);
            double splitDerivative = problem.nodeSideDrawMolPerSecond(node) > 0.0
                    && unknown.family() == V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW
                    ? problem.liquidWithdrawalFraction(state, node) * state.liquidFlow(node, unknown.component())
                            / V3SideDraws.liquidTotal(state, node) * base.liquidPhaseEnergy() : 0.0;
            addEnergyDerivative(problem, baseResidual, equationIndexes, layout, lower, diagonal, upper,
                    node + 1, column, node, liquidInCoefficient * liquidDerivative + splitDerivative);
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

        double freeWaterEnergyDerivative(V3MeshResidualEvaluator.LocalNodeTerms base) {
            return derivative(base.freeWaterPhaseEnergy(),
                    higher == null ? Double.NaN : higher.freeWaterPhaseEnergy(),
                    lower == null ? Double.NaN : lower.freeWaterPhaseEnergy());
        }

        double waterSaturationDerivative(V3MeshResidualEvaluator.LocalNodeTerms base) {
            return derivative(base.waterSaturationResidual(),
                    higher == null ? Double.NaN : higher.waterSaturationResidual(),
                    lower == null ? Double.NaN : lower.waterSaturationResidual());
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
