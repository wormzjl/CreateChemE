package com.wormzjl.createcheme.science.column.v3.linalg;

import java.util.Objects;

/**
 * Scaled scalar-LU solver with partial pivoting constrained to the matrix lower bandwidth.
 *
 * <p>The factorization works on one flat band array in the LAPACK {@code dgbtrf} layout: each row owns the
 * slots for its declared band plus the extra upper columns that pivoting inside the lower bandwidth can fill.
 * It never expands a stage-banded system into a dense production matrix and never mutates caller input.</p>
 *
 * <p>The band replaces an earlier sparse row envelope that stored only the entries a row actually had.  Both
 * visit the same rows in the same order and combine them with the same expressions; the band merely also
 * walks the structural zeros the envelope skipped, and subtracting an exact zero leaves a finite double
 * unchanged.  Corrections therefore stay bit-identical while the inner loop becomes contiguous array
 * arithmetic instead of a tree walk over boxed keys, which is where nearly all of a solve's time went.</p>
 */
public final class V3BandedPivotedSolver {
    private static final double PIVOT_TOLERANCE = 1.0e-13;
    private static final double ILL_CONDITIONED_PIVOT_RATIO = 1.0e-12;
    private static final double MAXIMUM_BACKWARD_ERROR = 1.0e-10;

    private V3BandedPivotedSolver() {}

    public static Result solve(V3BandedMatrix matrix, double[] rightHandSide) {
        return solve(matrix, rightHandSide, new Workspace());
    }

    /** Same numerical solve using caller-confined scratch storage; inputs and returned results remain independent. */
    public static Result solve(V3BandedMatrix matrix, double[] rightHandSide, Workspace workspace) {
        matrix = Objects.requireNonNull(matrix, "matrix");
        rightHandSide = copiedFiniteRightHandSide(matrix, rightHandSide);
        double[] originalRightHandSide = rightHandSide.clone();
        BandRows work = Objects.requireNonNull(workspace, "workspace").factorization(matrix);
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

        double[] band = work.values;
        int size = work.size;
        int width = work.width;
        int lowerBandwidth = work.lowerBandwidth;
        int fillBandwidth = work.fillBandwidth;
        for (int pivot = 0; pivot < size; pivot++) {
            int lastCandidateRow = Math.min(size - 1, pivot + lowerBandwidth);
            int pivotBase = pivot * width + lowerBandwidth - pivot;
            int pivotRow = pivot;
            double pivotMagnitude = Math.abs(band[pivotBase + pivot]);
            for (int row = pivot + 1; row <= lastCandidateRow; row++) {
                double candidateMagnitude = Math.abs(band[row * width + lowerBandwidth - row + pivot]);
                if (candidateMagnitude > pivotMagnitude) {
                    pivotMagnitude = candidateMagnitude;
                    pivotRow = row;
                }
            }
            if (!Double.isFinite(pivotMagnitude) || pivotMagnitude <= PIVOT_TOLERANCE) {
                return new Result.Failure(FailureCode.SINGULAR, "V3 banded LU encountered a zero or tiny pivot",
                        finiteOrZero(minimumPivot), maximumPivot, pivotSwaps, pivotGrowth(maximumDuringFactorization, initialMaximum));
            }
            // Rows reaching this pivot hold nothing beyond this column, so it also bounds the fill they take on.
            int lastFilledColumn = Math.min(size - 1, pivot + fillBandwidth);
            if (pivotRow != pivot) {
                work.swapRowTails(pivot, pivotRow, pivot, lastFilledColumn);
                double temporary = rightHandSide[pivot];
                rightHandSide[pivot] = rightHandSide[pivotRow];
                rightHandSide[pivotRow] = temporary;
                pivotSwaps++;
            }
            double diagonal = band[pivotBase + pivot];
            double absoluteDiagonal = Math.abs(diagonal);
            minimumPivot = Math.min(minimumPivot, absoluteDiagonal);
            maximumPivot = Math.max(maximumPivot, absoluteDiagonal);
            for (int row = pivot + 1; row <= lastCandidateRow; row++) {
                int rowBase = row * width + lowerBandwidth - row;
                double multiplier = band[rowBase + pivot] / diagonal;
                if (multiplier == 0.0) continue;
                band[rowBase + pivot] = multiplier;
                // Each entry is written once per pivot, so these values survive to its end.
                // Including the L multiplier preserves the former whole-matrix growth scan.
                maximumDuringFactorization = Math.max(maximumDuringFactorization, Math.abs(multiplier));
                for (int column = pivot + 1; column <= lastFilledColumn; column++) {
                    double updated = band[rowBase + column] - multiplier * band[pivotBase + column];
                    band[rowBase + column] = updated;
                    maximumDuringFactorization = Math.max(maximumDuringFactorization, Math.abs(updated));
                }
                rightHandSide[row] -= multiplier * rightHandSide[pivot];
            }
            // The envelope rejected a non-finite entry as it stored one. The growth maximum absorbs every value
            // this pivot wrote, so it is non-finite exactly when one of those writes was, and the guard survives.
            if (!Double.isFinite(maximumDuringFactorization)) {
                throw new IllegalStateException("V3 banded LU produced a non-finite entry");
            }
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

    /**
     * Scratch storage for one serial Newton solve. Do not share it between concurrent or reentrant calls.
     * Only the current exact shape is retained; dropping the workspace releases all its arrays.
     */
    public static final class Workspace {
        private V3BandedMatrix matrix;
        private BandRows work;

        /**
         * Returns a zeroed matrix for immediate assembly and solving. A later call may overwrite it;
         * callers needing a persistent matrix must construct their own instead.
         */
        public V3BandedMatrix clearedMatrix(int size, int lowerBandwidth, int upperBandwidth) {
            if (matrix == null || matrix.size() != size || matrix.lowerBandwidth() != lowerBandwidth
                    || matrix.upperBandwidth() != upperBandwidth) {
                matrix = new V3BandedMatrix(size, lowerBandwidth, upperBandwidth);
            } else {
                matrix.clear();
            }
            return matrix;
        }

        private BandRows factorization(V3BandedMatrix input) {
            if (work == null || work.size != input.size() || work.lowerBandwidth != input.lowerBandwidth()
                    || work.upperBandwidth != input.upperBandwidth()) {
                work = new BandRows(input.size(), input.lowerBandwidth(), input.upperBandwidth());
            } else {
                java.util.Arrays.fill(work.values, 0.0);
                java.util.Arrays.fill(work.columnDivisors, 0.0);
            }
            work.copyFrom(input);
            return work;
        }
    }

    private static double[] copiedFiniteRightHandSide(V3BandedMatrix matrix, double[] rightHandSide) {
        rightHandSide = Objects.requireNonNull(rightHandSide, "rightHandSide").clone();
        if (rightHandSide.length != matrix.size()) throw new IllegalArgumentException("V3 RHS dimension differs from the matrix");
        for (double value : rightHandSide) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 RHS must be finite");
        }
        return rightHandSide;
    }

    private static boolean scaleRowsAndColumns(BandRows work, double[] rightHandSide) {
        for (int row = 0; row < work.size; row++) {
            double maximum = work.rowMaximum(row);
            if (maximum == 0.0) continue;
            double scale = 1.0 / maximum;
            work.divideRow(row, maximum);
            rightHandSide[row] = Double.isFinite(scale) ? rightHandSide[row] * scale : rightHandSide[row] / maximum;
            if (!Double.isFinite(rightHandSide[row])) return false;
        }
        work.scaleColumns();
        return true;
    }

    private static double[] backSubstitute(BandRows work, double[] rightHandSide) {
        double[] solution = new double[work.size];
        double[] band = work.values;
        for (int row = work.size - 1; row >= 0; row--) {
            double sum = rightHandSide[row];
            int rowBase = row * work.width + work.lowerBandwidth - row;
            int lastFilledColumn = Math.min(work.size - 1, row + work.fillBandwidth);
            for (int column = row + 1; column <= lastFilledColumn; column++) sum -= band[rowBase + column] * solution[column];
            double diagonal = band[rowBase + row];
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

    /**
     * Working band in the LAPACK {@code dgbtrf} row layout: row {@code r} keeps columns {@code r - kl} through
     * {@code r + kl + ku} at {@code values[r * width + column - r + kl]}, the declared band widened by the
     * {@code kl} upper columns that pivoting can fill.  Rows entering a pivot hold nothing past that pivot plus
     * {@code kl + ku}, so a row lifted from up to {@code kl} below always fits the slots of the row it replaces.
     *
     * <p>Only the part of a row from the pivot column onwards takes part in a swap.  The multipliers to its
     * left are finished - the elimination applies them to the right-hand side as it computes them and back
     * substitution reads only above the diagonal - so leaving them in the row that produced them, as LAPACK
     * does, changes nothing and keeps every row inside its own slots.</p>
     */
    private static final class BandRows {
        private final int size;
        private final int lowerBandwidth;
        private final int upperBandwidth;
        private final int fillBandwidth;
        private final int width;
        private final double[] values;
        private final double[] columnDivisors;
        private final double[] columnScales;

        private BandRows(int size, int lowerBandwidth, int upperBandwidth) {
            this.size = size;
            this.lowerBandwidth = lowerBandwidth;
            this.upperBandwidth = upperBandwidth;
            this.fillBandwidth = lowerBandwidth + upperBandwidth;
            this.width = 2 * lowerBandwidth + upperBandwidth + 1;
            this.values = new double[Math.multiplyExact(size, width)];
            this.columnDivisors = new double[size];
            this.columnScales = new double[size];
        }

        void copyFrom(V3BandedMatrix matrix) {
            for (int row = 0; row < matrix.size(); row++) {
                int rowBase = rowBase(row);
                for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                    // A negative zero has to arrive as the positive zero the envelope reported for the entry it
                    // dropped, so that fill later subtracts the same signed zero from it.
                    double value = matrix.get(row, column);
                    values[rowBase + column] = value == 0.0 ? 0.0 : value;
                }
            }
        }

        private int rowBase(int row) { return row * width + lowerBandwidth - row; }
        private int firstColumn(int row) { return Math.max(0, row - lowerBandwidth); }
        private int lastColumn(int row) { return Math.min(size - 1, row + upperBandwidth); }

        void swapRowTails(int first, int second, int firstColumn, int lastColumn) {
            int firstBase = rowBase(first);
            int secondBase = rowBase(second);
            for (int column = firstColumn; column <= lastColumn; column++) {
                double temporary = values[firstBase + column];
                values[firstBase + column] = values[secondBase + column];
                values[secondBase + column] = temporary;
            }
        }

        double rowMaximum(int row) {
            double maximum = 0.0;
            int rowBase = rowBase(row);
            for (int column = firstColumn(row); column <= lastColumn(row); column++) {
                maximum = Math.max(maximum, Math.abs(values[rowBase + column]));
            }
            return maximum;
        }

        void divideRow(int row, double divisor) {
            double scale = 1.0 / divisor;
            int rowBase = rowBase(row);
            for (int column = firstColumn(row); column <= lastColumn(row); column++) {
                // The quotient is bounded even when a subnormal divisor's reciprocal overflows.
                values[rowBase + column] = Double.isFinite(scale)
                        ? values[rowBase + column] * scale : values[rowBase + column] / divisor;
            }
        }

        void scaleColumns() {
            // Rows are still in their original order and fill is still empty, so visiting each declared band
            // once keeps equilibration linear in the number of band entries.
            for (int row = 0; row < size; row++) {
                int rowBase = rowBase(row);
                for (int column = firstColumn(row); column <= lastColumn(row); column++) {
                    columnDivisors[column] = Math.max(columnDivisors[column], Math.abs(values[rowBase + column]));
                }
            }
            double[] scales = columnScales;
            for (int column = 0; column < size; column++) {
                if (columnDivisors[column] == 0.0) columnDivisors[column] = 1.0;
                scales[column] = 1.0 / columnDivisors[column];
            }
            for (int row = 0; row < size; row++) {
                int rowBase = rowBase(row);
                for (int column = firstColumn(row); column <= lastColumn(row); column++) {
                    double scale = scales[column];
                    values[rowBase + column] = Double.isFinite(scale)
                            ? values[rowBase + column] * scale : values[rowBase + column] / columnDivisors[column];
                }
            }
        }

        double maximumAbsoluteValue() {
            double maximum = 0.0;
            for (int row = 0; row < size; row++) maximum = Math.max(maximum, rowMaximum(row));
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
