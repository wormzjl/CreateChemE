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
        return evaluate(evaluator, coordinates, state, workspaceFactory, differenceScale, control, false);
    }

    /** Production storage: stage-local row windows, retaining unexpected off-stage entries without thresholding. */
    static Jacobian evaluateCompact(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory, DifferenceScale differenceScale, V3SolveControl control) {
        return evaluate(evaluator, coordinates, state, workspaceFactory, differenceScale, control, true);
    }

    private static Jacobian evaluate(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory, DifferenceScale differenceScale, V3SolveControl control,
            boolean compact) {
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
            return stageColoredJacobian(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale, control, compact);
        }
        return centralJacobian(evaluator, coordinates, base, baseResidual, workspaceFactory, differenceScale, control, compact);
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
                V3SolveControl.UNBOUNDED, false);
    }

    private static Jacobian centralJacobian(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            double[] base,
            V3MeshResidual baseResidual,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale,
            V3SolveControl control, boolean compact) {
        MatrixValues values = new MatrixValues(baseResidual, coordinates, compact);
        double[] frozenScales = baseResidual.scales();
        for (int column = 0; column < base.length; column++) {
            control.checkpoint();
            populateCentralColumn(evaluator, coordinates, base, baseResidual, frozenScales, workspaceFactory,
                    differenceScale, control, values, column);
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
            V3SolveControl control, boolean compact) {
        MatrixValues values = new MatrixValues(baseResidual, coordinates, compact);
        double[] frozenScales = baseResidual.scales();
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
                    populateCentralColumn(evaluator, coordinates, base, baseResidual, frozenScales, workspaceFactory,
                            differenceScale, control, values, column);
                }
                continue;
            }
            requireSameOrdering(baseResidual, higherResidual, lowerResidual);
            for (int column : group) {
                int columnNode = coordinates.unknowns().get(column).id().node();
                double step = step(base[column], coordinates.unknowns().get(column).id().family(), differenceScale);
                for (int row = 0; row < base.length; row++) {
                    if (Math.abs(baseResidual.rows().get(row).equation().node() - columnNode) > 1) continue;
                    double value = higherResidual != null && lowerResidual != null
                            ? (frozen(higherResidual, row, frozenScales) - frozen(lowerResidual, row, frozenScales))
                                    / (2.0 * step)
                            : higherResidual != null
                                    ? (frozen(higherResidual, row, frozenScales)
                                    - frozen(baseResidual, row, frozenScales)) / step
                                    : (frozen(baseResidual, row, frozenScales)
                                    - frozen(lowerResidual, row, frozenScales)) / step;
                    requireFinite(value);
                    values.set(row, column, value);
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
            double[] frozenScales,
            V3ThermoWorkspaceFactory workspaceFactory,
            DifferenceScale differenceScale,
            V3SolveControl control,
            MatrixValues values,
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
            double value = higherResidual != null && lowerResidual != null
                    ? (frozen(higherResidual, row, frozenScales) - frozen(lowerResidual, row, frozenScales)) / (2.0 * step)
                    : higherResidual != null
                            ? (frozen(higherResidual, row, frozenScales) - frozen(baseResidual, row, frozenScales)) / step
                            : (frozen(baseResidual, row, frozenScales) - frozen(lowerResidual, row, frozenScales)) / step;
            requireFinite(value);
            values.set(row, column, value);
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

    /**
     * Scales a probe row by the base state's scale rather than by its own.
     *
     * <p>This Jacobian differentiates {@code r(x) / s(x0)} with the row scale frozen at the base state, so a
     * row scale that is itself a function of the state cannot leak into it. Freezing keeps every row scaling a pure
     * left preconditioner of the same Newton system, keeps the exact log-flow material derivatives assembled
     * in {@link V3BlockJacobianAssembler} identical to this oracle, and drops only the {@code -r ds/dx / s}
     * term, which is zero at a solution and finite-difference noise away from one.</p>
     */
    private static double frozen(V3MeshResidual residual, int row, double[] frozenScales) {
        return residual.rows().get(row).physicalValue() / frozenScales[row];
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("V3 MESH finite-difference Jacobian contains a non-finite entry");
    }

    private static Jacobian jacobian(
            V3MeshResidual baseResidual, V3DryMeshCoordinateMap coordinates, MatrixValues values) {
        List<V3DegreeOfFreedomLedger.EquationId> equations = new ArrayList<>(baseResidual.rows().size());
        for (V3MeshResidual.Row row : baseResidual.rows()) equations.add(row.equation());
        // Both finite-difference builders relinquish their fresh matrix here. No caller can retain it.
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

    static final class Jacobian {
        private final List<V3DegreeOfFreedomLedger.EquationId> equations;
        private final List<V3DegreeOfFreedomLedger.UnknownId> unknowns;
        private final MatrixValues values;

        Jacobian(List<V3DegreeOfFreedomLedger.EquationId> equations,
                List<V3DegreeOfFreedomLedger.UnknownId> unknowns, double[][] values) {
            this(equations, unknowns, new MatrixValues(values));
        }

        /** The enclosing builders alone may transfer a fresh matrix instead of copying it. */
        private Jacobian(List<V3DegreeOfFreedomLedger.EquationId> equations,
                List<V3DegreeOfFreedomLedger.UnknownId> unknowns, MatrixValues values) {
            this.equations = List.copyOf(equations);
            this.unknowns = List.copyOf(unknowns);
            this.values = Objects.requireNonNull(values, "values");
            if (this.equations.isEmpty() || this.equations.size() != this.unknowns.size() || values.size != this.equations.size()) {
                throw new IllegalArgumentException("V3 finite-difference Jacobian shape is invalid");
            }
            for (double[] row : values.rows) {
                for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 finite-difference Jacobian must be finite");
            }
        }

        List<V3DegreeOfFreedomLedger.EquationId> equations() { return equations; }
        List<V3DegreeOfFreedomLedger.UnknownId> unknowns() { return unknowns; }
        double[][] values() { return values.snapshot(); }

        /** Read-only scalar access for numerical kernels that must not allocate a dense defensive copy. */
        double value(int row, int column) { return values.get(row, column); }

        /** Entries outside this stored window are exactly positive zero, never discarded FD noise. */
        int firstStoredColumn(int row) { return values.starts[row]; }
        int storedColumnEnd(int row) { return values.starts[row] + values.rows[row].length; }

        /** Diagnostic storage count, excluding object headers and the small row-offset array. */
        long storedValueCount() {
            long count = 0;
            for (double[] row : values.rows) count += row.length;
            return count;
        }
    }

    /** Mutable only while being assembled; the finished Jacobian exclusively owns it. */
    private static final class MatrixValues {
        private final int size;
        private final double[][] rows;
        private final int[] starts;

        private MatrixValues(double[][] input) {
            size = Objects.requireNonNull(input, "values").length;
            rows = new double[size][];
            starts = new int[size];
            for (int row = 0; row < size; row++) {
                rows[row] = Objects.requireNonNull(input[row], "row").clone();
                if (rows[row].length != size) throw new IllegalArgumentException("V3 finite-difference Jacobian is not square");
            }
        }

        private MatrixValues(V3MeshResidual residual, V3DryMeshCoordinateMap coordinates, boolean compact) {
            size = coordinates.coordinateCount();
            rows = new double[size][];
            starts = new int[size];
            if (!compact) {
                for (int row = 0; row < size; row++) rows[row] = new double[size];
                return;
            }
            int maximumNode = 0;
            for (var unknown : coordinates.unknowns()) maximumNode = Math.max(maximumNode, unknown.id().node());
            int[] first = new int[maximumNode + 1];
            int[] end = new int[maximumNode + 1];
            java.util.Arrays.fill(first, size);
            for (int column = 0; column < size; column++) {
                int node = coordinates.unknowns().get(column).id().node();
                first[node] = Math.min(first[node], column);
                end[node] = column + 1;
            }
            for (int row = 0; row < size; row++) {
                int rowNode = residual.rows().get(row).equation().node();
                int from = size;
                int to = 0;
                for (int node = Math.max(0, rowNode - 1); node <= Math.min(maximumNode, rowNode + 1); node++) {
                    from = Math.min(from, first[node]);
                    to = Math.max(to, end[node]);
                }
                starts[row] = to > from ? from : 0;
                rows[row] = new double[Math.max(0, to - from)];
            }
        }

        private void set(int row, int column, double value) {
            int offset = column - starts[row];
            if (offset >= 0 && offset < rows[row].length) {
                rows[row][offset] = value;
            } else if (Double.doubleToRawLongBits(value) != 0L) {
                // Central fallback can expose unexpected off-stage values. Keep even subthreshold values
                // and negative zero so the existing band guard, normal products and gradients see every bit.
                double[] expanded = new double[size];
                System.arraycopy(rows[row], 0, expanded, starts[row], rows[row].length);
                rows[row] = expanded;
                starts[row] = 0;
                expanded[column] = value;
            }
        }

        private double get(int row, int column) {
            if (column < 0 || column >= size) throw new ArrayIndexOutOfBoundsException(column);
            int offset = column - starts[row];
            return offset >= 0 && offset < rows[row].length ? rows[row][offset] : 0.0;
        }

        private double[][] snapshot() {
            double[][] result = new double[size][size];
            for (int row = 0; row < size; row++) {
                System.arraycopy(rows[row], 0, result[row], starts[row], rows[row].length);
            }
            return result;
        }
    }
}
