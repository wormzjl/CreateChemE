package com.wormzjl.createcheme.science.column.v3.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class V3PivotGrowthTest {
    @Test
    void successiveEliminationFillPreservesExactGrowth() {
        V3BandedMatrix matrix = fullBand(new double[][] {
                {1.0, 0.0, 0.0, 1.0},
                {-1.0, 1.0, 0.0, 1.0},
                {-1.0, -1.0, 1.0, 1.0},
                {-1.0, -1.0, -1.0, 1.0}
        });

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[4]));

        assertEquals(8.0, result.pivotGrowth());
        assertEquals(1.0, result.minimumPivotMagnitude());
        assertEquals(8.0, result.maximumPivotMagnitude());
        assertEquals(0, result.pivotSwaps());
        assertMatchesFullMatrixScans(matrix);
    }

    @Test
    void lateSingularityRetainsGrowthEvenAfterAFormerMaximumCancelsToZero() {
        V3BandedMatrix matrix = fullBand(new double[][] {
                {1.0, 0.0, 0.0, 1.0},
                {-1.0, 1.0, 0.0, 1.0},
                {-1.0, -1.0, 1.0, 1.0},
                {-1.0, -1.0, 1.0, 1.0}
        });

        V3BandedPivotedSolver.Result.Failure result = assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class,
                V3BandedPivotedSolver.solve(matrix, new double[4]));

        assertEquals(V3BandedPivotedSolver.FailureCode.SINGULAR, result.code());
        assertEquals(4.0, result.pivotGrowth());
        assertEquals(1.0, result.minimumPivotMagnitude());
        assertEquals(1.0, result.maximumPivotMagnitude());
        assertMatchesFullMatrixScans(matrix);
    }

    @Test
    void pivotSwapsAndFillPreserveFullMatrixScanEvidence() {
        V3BandedMatrix matrix = new V3BandedMatrix(3, 1, 1);
        matrix.set(0, 1, 2.0);
        matrix.set(1, 0, 3.0);
        matrix.set(1, 1, 4.0);
        matrix.set(1, 2, 5.0);
        matrix.set(2, 1, 6.0);
        matrix.set(2, 2, 7.0);

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[3]));

        assertTrue(result.pivotSwaps() > 0);
        assertMatchesFullMatrixScans(matrix);
    }

    @Test
    void deterministicSparseScaledBandsMatchFullMatrixScansExactly() {
        Random random = new Random(0x5049564F544CL);
        for (int sample = 0; sample < 250; sample++) {
            int size = random.nextInt(2, 25);
            V3BandedMatrix matrix = new V3BandedMatrix(size, random.nextInt(size), random.nextInt(size));
            int[] columnExponents = new int[size];
            for (int column = 0; column < size; column++) columnExponents[column] = random.nextInt(-40, 41);
            for (int row = 0; row < size; row++) {
                int rowExponent = random.nextInt(-40, 41);
                for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                    matrix.set(row, column, Math.scalb((double) random.nextInt(-4, 5),
                            rowExponent + columnExponents[column]));
                }
            }
            assertMatchesFullMatrixScans(matrix);
        }
    }

    @Test
    void emptyAndSubnormalMatricesRetainInitialGrowthEvidence() {
        assertMatchesFullMatrixScans(new V3BandedMatrix(3, 1, 1));
        V3BandedMatrix diagonal = new V3BandedMatrix(2, 0, 0);
        diagonal.set(0, 0, Double.MIN_VALUE);
        diagonal.set(1, 1, 2.0 * Double.MIN_VALUE);
        assertMatchesFullMatrixScans(diagonal);
    }

    private static void assertMatchesFullMatrixScans(V3BandedMatrix matrix) {
        FactorizationEvidence expected = fullMatrixScanEvidence(matrix);
        // Zero RHS isolates factorization evidence from representability of a nonzero correction.
        V3BandedPivotedSolver.Result actual = V3BandedPivotedSolver.solve(matrix, new double[matrix.size()]);
        if (actual instanceof V3BandedPivotedSolver.Result.Success success) {
            assertEquals(Outcome.SUCCESS, expected.outcome());
            assertEquals(expected.minimumPivot(), success.minimumPivotMagnitude());
            assertEquals(expected.maximumPivot(), success.maximumPivotMagnitude());
            assertEquals(expected.swaps(), success.pivotSwaps());
            assertEquals(expected.growth(), success.pivotGrowth());
        } else {
            V3BandedPivotedSolver.Result.Failure failure = assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class, actual);
            assertEquals(expected.outcome().name(), failure.code().name());
            assertEquals(expected.minimumPivot(), failure.minimumPivotMagnitude());
            assertEquals(expected.maximumPivot(), failure.maximumPivotMagnitude());
            assertEquals(expected.swaps(), failure.pivotSwaps());
            assertEquals(expected.growth(), failure.pivotGrowth());
        }
    }

    /** Independent dense oracle retaining the old, complete scan after each pivot. */
    private static FactorizationEvidence fullMatrixScanEvidence(V3BandedMatrix matrix) {
        int size = matrix.size();
        double[][] work = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) work[row][column] = matrix.get(row, column);
            double maximum = maximumMagnitude(work[row]);
            if (maximum == 0.0) continue;
            double scale = 1.0 / maximum;
            for (int column = 0; column < size; column++) {
                work[row][column] = Double.isFinite(scale) ? work[row][column] * scale : work[row][column] / maximum;
            }
        }
        for (int column = 0; column < size; column++) {
            double maximum = 0.0;
            for (int row = 0; row < size; row++) maximum = Math.max(maximum, Math.abs(work[row][column]));
            if (maximum == 0.0) continue;
            double scale = 1.0 / maximum;
            for (int row = 0; row < size; row++) {
                work[row][column] = Double.isFinite(scale) ? work[row][column] * scale : work[row][column] / maximum;
            }
        }
        double initialMaximum = maximumMagnitude(work);
        if (initialMaximum == 0.0) return new FactorizationEvidence(Outcome.SINGULAR, 0.0, 0.0, 0, 0.0);
        double maximumDuringFactorization = initialMaximum;
        double minimumPivot = Double.POSITIVE_INFINITY;
        double maximumPivot = 0.0;
        int swaps = 0;
        for (int pivot = 0; pivot < size; pivot++) {
            int pivotRow = pivot;
            for (int row = pivot + 1; row <= Math.min(size - 1, pivot + matrix.lowerBandwidth()); row++) {
                if (Math.abs(work[row][pivot]) > Math.abs(work[pivotRow][pivot])) pivotRow = row;
            }
            if (Math.abs(work[pivotRow][pivot]) <= 1.0e-13) {
                return new FactorizationEvidence(Outcome.SINGULAR, Double.isFinite(minimumPivot) ? minimumPivot : 0.0,
                        maximumPivot, swaps, maximumDuringFactorization / initialMaximum);
            }
            if (pivotRow != pivot) {
                double[] temporary = work[pivot]; work[pivot] = work[pivotRow]; work[pivotRow] = temporary;
                swaps++;
            }
            double diagonal = work[pivot][pivot];
            minimumPivot = Math.min(minimumPivot, Math.abs(diagonal));
            maximumPivot = Math.max(maximumPivot, Math.abs(diagonal));
            for (int row = pivot + 1; row <= Math.min(size - 1, pivot + matrix.lowerBandwidth()); row++) {
                double multiplier = work[row][pivot] / diagonal;
                if (multiplier == 0.0) continue;
                work[row][pivot] = multiplier;
                for (int column = pivot + 1; column < size; column++) {
                    if (work[pivot][column] != 0.0) work[row][column] -= multiplier * work[pivot][column];
                }
            }
            maximumDuringFactorization = Math.max(maximumDuringFactorization, maximumMagnitude(work));
        }
        Outcome outcome = minimumPivot / maximumPivot < 1.0e-12 ? Outcome.ILL_CONDITIONED : Outcome.SUCCESS;
        return new FactorizationEvidence(outcome, minimumPivot, maximumPivot, swaps, maximumDuringFactorization / initialMaximum);
    }

    private static V3BandedMatrix fullBand(double[][] values) {
        V3BandedMatrix matrix = new V3BandedMatrix(values.length, values.length - 1, values.length - 1);
        for (int row = 0; row < values.length; row++) {
            for (int column = 0; column < values.length; column++) matrix.set(row, column, values[row][column]);
        }
        return matrix;
    }

    private static double maximumMagnitude(double[][] values) {
        double maximum = 0.0;
        for (double[] row : values) maximum = Math.max(maximum, maximumMagnitude(row));
        return maximum;
    }

    private static double maximumMagnitude(double[] values) {
        double maximum = 0.0;
        for (double value : values) maximum = Math.max(maximum, Math.abs(value));
        return maximum;
    }

    private enum Outcome { SUCCESS, SINGULAR, ILL_CONDITIONED }

    private record FactorizationEvidence(Outcome outcome, double minimumPivot, double maximumPivot, int swaps, double growth) {}
}
