package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Whole-system finite-difference Jacobian with one-sided PR-domain handling for future block-Jacobian verification. */
final class V3FiniteDifferenceJacobian {
    private V3FiniteDifferenceJacobian() {}

    static Jacobian evaluate(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, V3DryMeshState state,
            V3ThermoWorkspaceFactory workspaceFactory) {
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        state = Objects.requireNonNull(state, "state");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        double[] base = coordinates.encode(state);
        V3MeshResidual baseResidual = evaluator.evaluate(state, workspaceFactory.newWorkspace());
        int rows = baseResidual.rows().size();
        if (rows != base.length) throw new IllegalArgumentException("V3 MESH Jacobian requires a square residual/coordinate map");
        double[][] values = new double[rows][base.length];
        for (int column = 0; column < base.length; column++) {
            double step = step(base[column], coordinates.unknowns().get(column).id().family());
            double[] higher = base.clone();
            double[] lower = base.clone();
            higher[column] += step;
            lower[column] -= step;
            V3MeshResidual higherResidual = feasibleResidual(evaluator, coordinates, higher, workspaceFactory);
            V3MeshResidual lowerResidual = feasibleResidual(evaluator, coordinates, lower, workspaceFactory);
            if (higherResidual == null && lowerResidual == null) {
                throw new IllegalStateException("V3 MESH finite difference has no admissible perturbation");
            }
            for (int row = 0; row < rows; row++) {
                if ((higherResidual != null && !baseResidual.rows().get(row).equation().equals(higherResidual.rows().get(row).equation()))
                        || (lowerResidual != null && !baseResidual.rows().get(row).equation().equals(lowerResidual.rows().get(row).equation()))) {
                    throw new IllegalStateException("V3 MESH residual ordering changed during finite differentiation");
                }
                values[row][column] = higherResidual != null && lowerResidual != null
                        ? (higherResidual.rows().get(row).scaledValue() - lowerResidual.rows().get(row).scaledValue()) / (2.0 * step)
                        : higherResidual != null
                                ? (higherResidual.rows().get(row).scaledValue() - baseResidual.rows().get(row).scaledValue()) / step
                                : (baseResidual.rows().get(row).scaledValue() - lowerResidual.rows().get(row).scaledValue()) / step;
                if (!Double.isFinite(values[row][column])) {
                    throw new IllegalStateException("V3 MESH finite-difference Jacobian contains a non-finite entry");
                }
            }
        }
        List<V3DegreeOfFreedomLedger.EquationId> equations = new ArrayList<>(rows);
        for (V3MeshResidual.Row row : baseResidual.rows()) equations.add(row.equation());
        return new Jacobian(equations, coordinates.unknowns().stream().map(V3DegreeOfFreedomLedger.Unknown::id).toList(), values);
    }

    private static V3MeshResidual feasibleResidual(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, double[] candidate,
            V3ThermoWorkspaceFactory workspaceFactory) {
        try {
            return evaluator.evaluate(coordinates.decode(candidate), workspaceFactory.newWorkspace());
        } catch (IllegalArgumentException | V3ThermoException ignored) {
            return null;
        }
    }

    private static double step(double coordinate, V3DegreeOfFreedomLedger.UnknownFamily family) {
        return family == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE
                ? Math.max(1.0e-4, Math.abs(coordinate) * 1.0e-6) : 1.0e-6;
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
