package com.wormzjl.createcheme.science.column.v3.linalg;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Scaled scalar-LU solver with partial pivoting constrained to the matrix lower bandwidth.
 *
 * <p>The factorization keeps only existing band/fill entries in sparse row envelopes.  It never
 * expands a stage-banded system into a dense production matrix and never mutates caller input.</p>
 */
public final class V3BandedPivotedSolver {
    private static final double PIVOT_TOLERANCE = 1.0e-13;
    private static final double ILL_CONDITIONED_PIVOT_RATIO = 1.0e-12;
    private static final double MAXIMUM_BACKWARD_ERROR = 1.0e-10;

    private V3BandedPivotedSolver() {}

    public static Result solve(V3BandedMatrix matrix, double[] rightHandSide) {
        matrix = Objects.requireNonNull(matrix, "matrix");
        rightHandSide = copiedFiniteRightHandSide(matrix, rightHandSide);
        double[] originalRightHandSide = rightHandSide.clone();
        SparseRows work = SparseRows.copyOf(matrix);
        double originalMatrixInfinityNorm = infinityNorm(matrix);
        double originalRightHandSideInfinityNorm = infinityNorm(rightHandSide);
        if (originalMatrixInfinityNorm == 0.0) {
            return new Result.Failure(FailureCode.SINGULAR, "V3 band matrix contains no nonzero pivot", 0.0, 0.0, 0, 0.0);
        }
        if (!Double.isFinite(originalMatrixInfinityNorm) || !scaleRowsAndColumns(work, rightHandSide)) {
            return new Result.Failure(FailureCode.ILL_CONDITIONED,
                    "V3 banded LU scaling exceeds the finite numerical range", 0.0, 0.0, 0, 0.0);
        }
        double initialMaximum = work.maximumAbsoluteValue();
        double maximumDuringFactorization = initialMaximum;
        double minimumPivot = Double.POSITIVE_INFINITY;
        double maximumPivot = 0.0;
        int pivotSwaps = 0;

        for (int pivot = 0; pivot < matrix.size(); pivot++) {
            int pivotRow = pivot;
            double pivotMagnitude = Math.abs(work.get(pivot, pivot));
            for (int row = pivot + 1; row <= Math.min(matrix.size() - 1, pivot + matrix.lowerBandwidth()); row++) {
                double candidateMagnitude = Math.abs(work.get(row, pivot));
                if (candidateMagnitude > pivotMagnitude) {
                    pivotMagnitude = candidateMagnitude;
                    pivotRow = row;
                }
            }
            if (!Double.isFinite(pivotMagnitude) || pivotMagnitude <= PIVOT_TOLERANCE) {
                return new Result.Failure(FailureCode.SINGULAR, "V3 banded LU encountered a zero or tiny pivot",
                        finiteOrZero(minimumPivot), maximumPivot, pivotSwaps, pivotGrowth(maximumDuringFactorization, initialMaximum));
            }
            if (pivotRow != pivot) {
                work.swapRows(pivot, pivotRow);
                double temporary = rightHandSide[pivot];
                rightHandSide[pivot] = rightHandSide[pivotRow];
                rightHandSide[pivotRow] = temporary;
                pivotSwaps++;
            }
            double diagonal = work.get(pivot, pivot);
            double absoluteDiagonal = Math.abs(diagonal);
            minimumPivot = Math.min(minimumPivot, absoluteDiagonal);
            maximumPivot = Math.max(maximumPivot, absoluteDiagonal);
            for (int row = pivot + 1; row <= Math.min(matrix.size() - 1, pivot + matrix.lowerBandwidth()); row++) {
                double multiplier = work.get(row, pivot) / diagonal;
                if (multiplier == 0.0) continue;
                work.put(row, pivot, multiplier);
                for (Map.Entry<Integer, Double> entry : work.entriesAfter(pivot)) {
                    int column = entry.getKey();
                    work.put(row, column, work.get(row, column) - multiplier * entry.getValue());
                }
                rightHandSide[row] -= multiplier * rightHandSide[pivot];
            }
            maximumDuringFactorization = Math.max(maximumDuringFactorization, work.maximumAbsoluteValue());
        }
        if (minimumPivot / maximumPivot < ILL_CONDITIONED_PIVOT_RATIO) {
            return new Result.Failure(FailureCode.ILL_CONDITIONED,
                    "V3 banded LU pivot spread exceeds the configured conditioning guard", minimumPivot, maximumPivot,
                    pivotSwaps, pivotGrowth(maximumDuringFactorization, initialMaximum));
        }

        double[] scaledSolution = backSubstitute(work, rightHandSide);
        double[] solution = work.unscaleColumns(scaledSolution);
        double backwardError = backwardError(matrix, solution, originalRightHandSide, originalMatrixInfinityNorm,
                originalRightHandSideInfinityNorm);
        if (!Double.isFinite(backwardError) || backwardError > MAXIMUM_BACKWARD_ERROR) {
            return new Result.Failure(FailureCode.BACKWARD_ERROR_EXCEEDED,
                    "V3 banded LU correction does not satisfy the backward-error guard", minimumPivot, maximumPivot,
                    pivotSwaps, pivotGrowth(maximumDuringFactorization, initialMaximum));
        }
        return new Result.Success(solution, backwardError, minimumPivot, maximumPivot, pivotSwaps,
                pivotGrowth(maximumDuringFactorization, initialMaximum));
    }

    private static double[] copiedFiniteRightHandSide(V3BandedMatrix matrix, double[] rightHandSide) {
        rightHandSide = Objects.requireNonNull(rightHandSide, "rightHandSide").clone();
        if (rightHandSide.length != matrix.size()) throw new IllegalArgumentException("V3 RHS dimension differs from the matrix");
        for (double value : rightHandSide) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 RHS must be finite");
        }
        return rightHandSide;
    }

    private static boolean scaleRowsAndColumns(SparseRows work, double[] rightHandSide) {
        for (int row = 0; row < work.size(); row++) {
            double maximum = work.rowMaximum(row);
            if (maximum == 0.0) continue;
            double scale = 1.0 / maximum;
            work.divideRow(row, maximum);
            rightHandSide[row] = Double.isFinite(scale) ? rightHandSide[row] * scale : rightHandSide[row] / maximum;
            if (!Double.isFinite(rightHandSide[row])) return false;
        }
        for (int column = 0; column < work.size(); column++) {
            double maximum = work.columnMaximum(column);
            if (maximum == 0.0) continue;
            work.divideColumn(column, maximum);
        }
        return true;
    }

    private static double[] backSubstitute(SparseRows work, double[] rightHandSide) {
        double[] solution = new double[work.size()];
        for (int row = work.size() - 1; row >= 0; row--) {
            double sum = rightHandSide[row];
            for (Map.Entry<Integer, Double> entry : work.entriesAfter(row)) sum -= entry.getValue() * solution[entry.getKey()];
            double diagonal = work.get(row, row);
            if (!Double.isFinite(diagonal) || Math.abs(diagonal) <= PIVOT_TOLERANCE) {
                throw new IllegalStateException("V3 banded LU lost a usable back-substitution pivot");
            }
            solution[row] = sum / diagonal;
        }
        return solution;
    }

    private static double backwardError(
            V3BandedMatrix matrix, double[] solution, double[] originalRightHandSide, double matrixInfinityNorm,
            double rightHandSideInfinityNorm) {
        double residualInfinityNorm = 0.0;
        for (int row = 0; row < matrix.size(); row++) {
            double value = 0.0;
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                value += matrix.get(row, column) * solution[column];
            }
            residualInfinityNorm = Math.max(residualInfinityNorm, Math.abs(value - originalRightHandSide[row]));
        }
        double denominator = matrixInfinityNorm * infinityNorm(solution) + rightHandSideInfinityNorm;
        return denominator == 0.0 ? 0.0 : residualInfinityNorm / denominator;
    }

    private static double infinityNorm(V3BandedMatrix matrix) {
        double maximum = 0.0;
        for (int row = 0; row < matrix.size(); row++) {
            double sum = 0.0;
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                sum += Math.abs(matrix.get(row, column));
            }
            maximum = Math.max(maximum, sum);
        }
        return maximum;
    }

    private static double infinityNorm(double[] values) {
        double maximum = 0.0;
        for (double value : values) maximum = Math.max(maximum, Math.abs(value));
        return maximum;
    }

    private static double pivotGrowth(double maximumDuringFactorization, double initialMaximum) {
        return initialMaximum == 0.0 ? 0.0 : maximumDuringFactorization / initialMaximum;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    public sealed interface Result permits Result.Success, Result.Failure {
        record Success(
                double[] solution, double backwardError, double minimumPivotMagnitude, double maximumPivotMagnitude,
                int pivotSwaps, double pivotGrowth) implements Result {
            public Success {
                solution = Objects.requireNonNull(solution, "solution").clone();
                if (solution.length == 0 || !Double.isFinite(backwardError) || backwardError < 0.0
                        || !Double.isFinite(minimumPivotMagnitude) || minimumPivotMagnitude <= 0.0
                        || !Double.isFinite(maximumPivotMagnitude) || maximumPivotMagnitude < minimumPivotMagnitude
                        || pivotSwaps < 0 || !Double.isFinite(pivotGrowth) || pivotGrowth < 1.0) {
                    throw new IllegalArgumentException("V3 successful banded solve evidence is invalid");
                }
                for (double value : solution) if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 solution must be finite");
            }

            @Override public double[] solution() { return solution.clone(); }
        }

        record Failure(
                FailureCode code, String detail, double minimumPivotMagnitude, double maximumPivotMagnitude,
                int pivotSwaps, double pivotGrowth) implements Result {
            public Failure {
                code = Objects.requireNonNull(code, "code");
                detail = Objects.requireNonNull(detail, "detail");
                if (detail.isBlank() || detail.length() > 256 || !Double.isFinite(minimumPivotMagnitude)
                        || minimumPivotMagnitude < 0.0 || !Double.isFinite(maximumPivotMagnitude)
                        || maximumPivotMagnitude < 0.0 || pivotSwaps < 0 || !Double.isFinite(pivotGrowth)
                        || pivotGrowth < 0.0) {
                    throw new IllegalArgumentException("V3 failed banded solve evidence is invalid");
                }
            }
        }
    }

    public enum FailureCode {
        SINGULAR,
        ILL_CONDITIONED,
        BACKWARD_ERROR_EXCEEDED
    }

    private static final class SparseRows {
        private final TreeMap<Integer, Double>[] rows;
        private final double[] columnDivisors;

        @SuppressWarnings("unchecked")
        private SparseRows(int size) {
            rows = new TreeMap[size];
            for (int row = 0; row < size; row++) rows[row] = new TreeMap<>();
            columnDivisors = new double[size];
            java.util.Arrays.fill(columnDivisors, 1.0);
        }

        static SparseRows copyOf(V3BandedMatrix matrix) {
            SparseRows copy = new SparseRows(matrix.size());
            for (int row = 0; row < matrix.size(); row++) {
                for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                    double value = matrix.get(row, column);
                    if (value != 0.0) copy.rows[row].put(column, value);
                }
            }
            return copy;
        }

        int size() { return rows.length; }
        double get(int row, int column) { return rows[row].getOrDefault(column, 0.0); }
        void put(int row, int column, double value) {
            if (!Double.isFinite(value)) throw new IllegalStateException("V3 banded LU produced a non-finite entry");
            if (value == 0.0) rows[row].remove(column);
            else rows[row].put(column, value);
        }
        void swapRows(int first, int second) {
            TreeMap<Integer, Double> temporary = rows[first]; rows[first] = rows[second]; rows[second] = temporary;
        }
        Iterable<Map.Entry<Integer, Double>> entriesAfter(int column) {
            return rows[column].tailMap(column, false).entrySet();
        }
        double rowMaximum(int row) {
            double maximum = 0.0;
            for (double value : rows[row].values()) maximum = Math.max(maximum, Math.abs(value));
            return maximum;
        }
        double columnMaximum(int column) {
            double maximum = 0.0;
            for (TreeMap<Integer, Double> row : rows) maximum = Math.max(maximum, Math.abs(row.getOrDefault(column, 0.0)));
            return maximum;
        }
        void divideRow(int row, double divisor) {
            double scale = 1.0 / divisor;
            for (Map.Entry<Integer, Double> entry : rows[row].entrySet()) {
                // The quotient is bounded even when a subnormal divisor's reciprocal overflows.
                entry.setValue(Double.isFinite(scale) ? entry.getValue() * scale : entry.getValue() / divisor);
            }
        }
        void divideColumn(int column, double divisor) {
            columnDivisors[column] = divisor;
            double scale = 1.0 / divisor;
            for (TreeMap<Integer, Double> row : rows) {
                Double value = row.get(column);
                if (value != null) row.put(column, Double.isFinite(scale) ? value * scale : value / divisor);
            }
        }
        double maximumAbsoluteValue() {
            double maximum = 0.0;
            for (TreeMap<Integer, Double> row : rows) for (double value : row.values()) maximum = Math.max(maximum, Math.abs(value));
            return maximum;
        }
        double[] unscaleColumns(double[] scaledSolution) {
            double[] solution = scaledSolution.clone();
            for (int column = 0; column < solution.length; column++) {
                double scale = 1.0 / columnDivisors[column];
                solution[column] = Double.isFinite(scale) ? solution[column] * scale : solution[column] / columnDivisors[column];
            }
            return solution;
        }
    }
}
