package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Whole-system finite-difference Jacobian with one-sided PR-domain handling for future block-Jacobian verification. */
final class V3FiniteDifferenceJacobian {
    private static final int STAGE_COLORED_MINIMUM_COORDINATES = 96;
    private static final int STAGE_COLOR_COUNT = 3;

    private V3FiniteDifferenceJacobian() {}

    static Jacobian evaluate(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory) {
        return evaluate(evaluator, coordinates, state, workspaceFactory, DifferenceScale.FINE);
    }

    static Jacobian evaluate(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory, DifferenceScale differenceScale) {
        return evaluate(evaluator, coordinates, state, workspaceFactory, differenceScale, V3SolveControl.UNBOUNDED);
    }

    static Jacobian evaluate(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale,
            V3SolveControl control) {
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        state = Objects.requireNonNull(state, "state");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        differenceScale = Objects.requireNonNull(differenceScale, "differenceScale");
        control = Objects.requireNonNull(control, "control");
        control.checkpoint();
        double[] base = coordinates.encode(state);
        V3MeshResidual baseResidual = evaluator.evaluate(state, workspaceFactory.newWorkspace());
        int rows = baseResidual.rows().size();
        if (rows != base.length) throw new IllegalArgumentException("V3 MESH Jacobian requires a square residual/coordinate map");
        if (base.length >= STAGE_COLORED_MINIMUM_COORDINATES) {
            return stageColoredJacobian(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale, control);
        }
        return centralJacobian(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale, control);
    }

    /** Package-private deterministic qualifier for the stage-colored high-dimensional path. */
    static Jacobian evaluateStageColored(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale) {
        double[] base = coordinates.encode(state);
        V3MeshResidual baseResidual = evaluator.evaluate(state, workspaceFactory.newWorkspace());
        return stageColoredJacobian(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale,
                V3SolveControl.UNBOUNDED);
    }

    private static Jacobian centralJacobian(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            double[] base,
            V3MeshResidual baseResidual,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale,
            V3SolveControl control) {
        int rows = baseResidual.rows().size();
        double[][] values = new double[rows][base.length];
        for (int column = 0; column < base.length; column++) {
            control.checkpoint();
            populateCentralColumn(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale, control,
                    values, column);
        }
        return jacobian(baseResidual, coordinates, values);
    }

    /**
     * Uses a distance-three stage coloring: residual rows touch only their own or adjacent stage, so equal local
     * unknown slots on same-colored stages have disjoint row support and may share one plus/minus evaluation.
     */
    private static Jacobian stageColoredJacobian(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            double[] base,
            V3MeshResidual baseResidual,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale,
            V3SolveControl control) {
        double[][] values = new double[base.length][base.length];
        Map<ColorSlot, List<Integer>> groups = new LinkedHashMap<>();
        for (int column = 0; column < base.length; column++) {
            V3DegreeOfFreedomLedger.UnknownId id = coordinates.unknowns().get(column).id();
            groups.computeIfAbsent(new ColorSlot(Math.floorMod(id.node(), STAGE_COLOR_COUNT), id.family(), id.component()),
                    ignored -> new ArrayList<>()).add(column);
        }
        for (List<Integer> group : groups.values()) {
            control.checkpoint();
            double[] higher = base.clone();
            double[] lower = base.clone();
            for (int column : group) {
                double step = step(base[column], coordinates.unknowns().get(column).id().family(), differenceScale);
                higher[column] += step;
                lower[column] -= step;
            }
            V3MeshResidual higherResidual = feasibleResidual(evaluator, coordinates, higher, workspaceFactory, control);
            V3MeshResidual lowerResidual = feasibleResidual(evaluator, coordinates, lower, workspaceFactory, control);
            if (higherResidual == null && lowerResidual == null) {
                for (int column : group) {
                    populateCentralColumn(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale,
                            control, values, column);
                }
                continue;
            }
            requireSameOrdering(baseResidual, higherResidual, lowerResidual);
            for (int column : group) {
                int columnNode = coordinates.unknowns().get(column).id().node();
                double step = step(base[column], coordinates.unknowns().get(column).id().family(), differenceScale);
                for (int row = 0; row < base.length; row++) {
                    if (Math.abs(baseResidual.rows().get(row).equation().node() - columnNode) > 1) continue;
                    values[row][column] = higherResidual != null && lowerResidual != null
                            ? (higherResidual.rows().get(row).scaledValue() - lowerResidual.rows().get(row).scaledValue())
                                    / (2.0 * step)
                            : higherResidual != null
                                    ? (higherResidual.rows().get(row).scaledValue()
                                    - baseResidual.rows().get(row).scaledValue()) / step
                                    : (baseResidual.rows().get(row).scaledValue()
                                    - lowerResidual.rows().get(row).scaledValue()) / step;
                    requireFinite(values[row][column]);
                }
            }
        }
        return jacobian(baseResidual, coordinates, values);
    }

    private static void populateCentralColumn(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            double[] base,
            V3MeshResidual baseResidual,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale,
            V3SolveControl control,
            double[][] values,
            int column) {
        double step = step(base[column], coordinates.unknowns().get(column).id().family(), differenceScale);
        double[] higher = base.clone();
        double[] lower = base.clone();
        higher[column] += step;
        lower[column] -= step;
        V3MeshResidual higherResidual = feasibleResidual(evaluator, coordinates, higher, workspaceFactory, control);
        V3MeshResidual lowerResidual = feasibleResidual(evaluator, coordinates, lower, workspaceFactory, control);
        if (higherResidual == null && lowerResidual == null) {
            throw new IllegalStateException("V3 MESH finite difference has no admissible perturbation");
        }
        requireSameOrdering(baseResidual, higherResidual, lowerResidual);
        for (int row = 0; row < baseResidual.rows().size(); row++) {
            values[row][column] = higherResidual != null && lowerResidual != null
                    ? (higherResidual.rows().get(row).scaledValue() - lowerResidual.rows().get(row).scaledValue()) / (2.0 * step)
                    : higherResidual != null
                            ? (higherResidual.rows().get(row).scaledValue() - baseResidual.rows().get(row).scaledValue()) / step
                            : (baseResidual.rows().get(row).scaledValue() - lowerResidual.rows().get(row).scaledValue()) / step;
            requireFinite(values[row][column]);
        }
    }

    private static void requireSameOrdering(
            V3MeshResidual baseResidual, V3MeshResidual higherResidual, V3MeshResidual lowerResidual) {
        for (int row = 0; row < baseResidual.rows().size(); row++) {
            if ((higherResidual != null && !baseResidual.rows().get(row).equation().equals(higherResidual.rows().get(row).equation()))
                    || (lowerResidual != null && !baseResidual.rows().get(row).equation().equals(lowerResidual.rows().get(row).equation()))) {
                throw new IllegalStateException("V3 MESH residual ordering changed during finite differentiation");
            }
        }
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("V3 MESH finite-difference Jacobian contains a non-finite entry");
    }

    private static Jacobian jacobian(
            V3MeshResidual baseResidual, V3DryMeshCoordinateMap coordinates, double[][] values) {
        List<V3DegreeOfFreedomLedger.EquationId> equations = new ArrayList<>(baseResidual.rows().size());
        for (V3MeshResidual.Row row : baseResidual.rows()) equations.add(row.equation());
        return new Jacobian(equations, coordinates.unknowns().stream().map(V3DegreeOfFreedomLedger.Unknown::id).toList(), values);
    }

    private record ColorSlot(
            int color, V3DegreeOfFreedomLedger.UnknownFamily family, int component) {}

    private static V3MeshResidual feasibleResidual(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, double[] candidate,
            V3ThermoWorkspaceFactory workspaceFactory, V3SolveControl control) {
        try {
            control.checkpoint();
            return evaluator.evaluate(coordinates.decode(candidate), workspaceFactory.newWorkspace());
        } catch (IllegalArgumentException | V3ThermoException ignored) {
            return null;
        }
    }

    static double step(
            double coordinate, V3DegreeOfFreedomLedger.UnknownFamily family, DifferenceScale differenceScale) {
        return family == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE
                ? Math.max(differenceScale.minimumTemperatureStepKelvin,
                        Math.abs(coordinate) * differenceScale.relativeTemperatureStep)
                : differenceScale.logFlowStep;
    }

    /** Bounded central-difference resolutions used only as distinct cold-start Newton recovery attempts. */
    enum DifferenceScale {
        FINE(1.0e-4, 1.0e-6, 1.0e-6),
        COARSE(1.0e-3, 1.0e-5, 1.0e-5);

        private final double minimumTemperatureStepKelvin;
        private final double relativeTemperatureStep;
        private final double logFlowStep;

        DifferenceScale(double minimumTemperatureStepKelvin, double relativeTemperatureStep, double logFlowStep) {
            this.minimumTemperatureStepKelvin = minimumTemperatureStepKelvin;
            this.relativeTemperatureStep = relativeTemperatureStep;
            this.logFlowStep = logFlowStep;
        }
    }

    interface V3ThermoWorkspaceFactory {
        com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace newWorkspace();
    }

    record Jacobian(
            List<V3DegreeOfFreedomLedger.EquationId> equations,
            List<V3DegreeOfFreedomLedger.UnknownId> unknowns, double[][] values) {
        Jacobian {
            equations = List.copyOf(equations);
            unknowns = List.copyOf(unknowns);
            values = copy(values);
            if (equations.isEmpty() || equations.size() != unknowns.size() || values.length != equations.size()) {
                throw new IllegalArgumentException("V3 finite-difference Jacobian shape is invalid");
            }
            for (double[] row : values) {
                if (row.length != unknowns.size()) throw new IllegalArgumentException("V3 finite-difference Jacobian is not square");
                for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 finite-difference Jacobian must be finite");
            }
        }

        @Override public double[][] values() { return copy(values); }

        private static double[][] copy(double[][] values) {
            values = Objects.requireNonNull(values, "values");
            double[][] copy = new double[values.length][];
            for (int row = 0; row < values.length; row++) copy[row] = Objects.requireNonNull(values[row], "row").clone();
            return copy;
        }
    }
}
