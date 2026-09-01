package com.wormzjl.createcheme.science.column.v3.linalg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class V3BandedPivotedSolverTest {
    @Test
    void subnormalRowsCanBeScaledWithoutOverflowingTheirReciprocals() {
        V3BandedMatrix matrix = new V3BandedMatrix(2, 0, 0);
        matrix.set(0, 0, Double.MIN_VALUE);
        matrix.set(1, 1, 2.0 * Double.MIN_VALUE);

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {Double.MIN_VALUE, 2.0 * Double.MIN_VALUE}));

        assertArrayEquals(new double[] {1.0, 1.0}, result.solution());
        assertEquals(0.0, result.backwardError());
        assertEquals(Double.MIN_VALUE, matrix.get(0, 0));
    }

    @Test
    void subnormalColumnScalingAndZeroSolutionUnscalingRemainFinite() {
        V3BandedMatrix matrix = new V3BandedMatrix(2, 1, 1);
        matrix.set(0, 0, 1.0); matrix.set(0, 1, Double.MIN_VALUE);
        matrix.set(1, 0, 1.0); matrix.set(1, 1, 2.0 * Double.MIN_VALUE);

        V3BandedPivotedSolver.Result.Success result = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {1.0, 1.0}));

        assertArrayEquals(new double[] {1.0, 0.0}, result.solution());
        assertEquals(0.0, result.backwardError());
    }

    @Test
    void unrepresentableScaledRightHandSideReturnsATypedFailure() {
        V3BandedMatrix matrix = new V3BandedMatrix(1, 0, 0);
        matrix.set(0, 0, Double.MIN_VALUE);

        V3BandedPivotedSolver.Result.Failure result = assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class,
                V3BandedPivotedSolver.solve(matrix, new double[] {1.0}));

        assertEquals(V3BandedPivotedSolver.FailureCode.ILL_CONDITIONED, result.code());
    }

    @Test
    void pivotedTridiagonalCorrectionMatchesAnIndependentDensePartialPivotSolve() {
        V3BandedMatrix matrix = new V3BandedMatrix(3, 1, 1);
        matrix.set(0, 0, 0.0); matrix.set(0, 1, 2.0);
        matrix.set(1, 0, 3.0); matrix.set(1, 1, 4.0); matrix.set(1, 2, 5.0);
        matrix.set(2, 1, 6.0); matrix.set(2, 2, 7.0);
        double[] rightHandSide = {-4.0, 10.0, 9.0};

        V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, rightHandSide);

        V3BandedPivotedSolver.Result.Success success = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class, result);
        assertTrue(success.pivotSwaps() > 0);
        assertArrayEquals(densePivotedSolve(toDense(matrix), rightHandSide), success.solution(), 1.0e-11);
        assertTrue(success.backwardError() <= 1.0e-12);
    }

    @Test
    void deterministicRandomDiagonallyDominantBandsMatchDenseOracleAndPreserveInputs() {
        Random random = new Random(0x5EEDBA5DL);
        for (int size = 4; size <= 32; size += 7) {
            V3BandedMatrix matrix = diagonallyDominantBand(size, 2, 3, random);
            double[] expected = vector(size, random);
            double[] rightHandSide = multiply(matrix, expected);
            double[][] originalMatrix = toDense(matrix);
            double[] originalRightHandSide = rightHandSide.clone();

            V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, rightHandSide);

            V3BandedPivotedSolver.Result.Success success = assertInstanceOf(V3BandedPivotedSolver.Result.Success.class, result);
            assertArrayEquals(densePivotedSolve(originalMatrix, originalRightHandSide), success.solution(), 1.0e-9);
            assertArrayEquals(expected, success.solution(), 1.0e-9);
            assertTrue(success.backwardError() <= 1.0e-12);
            assertArrayEquals(originalRightHandSide, rightHandSide);
            assertDenseEquals(originalMatrix, toDense(matrix));
        }
    }

    @Test
    void singularBandReturnsTypedFailureWithoutProducingANumericSolution() {
        V3BandedMatrix matrix = new V3BandedMatrix(3, 1, 1);
        matrix.set(0, 0, 1.0);
        matrix.set(2, 2, 1.0);

        V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, new double[] {1.0, 0.0, 1.0});

        V3BandedPivotedSolver.Result.Failure failure = assertInstanceOf(V3BandedPivotedSolver.Result.Failure.class, result);
        assertEquals(V3BandedPivotedSolver.FailureCode.SINGULAR, failure.code());
        assertFalse(result instanceof V3BandedPivotedSolver.Result.Success);
    }

    @Test
    void matrixRejectsWritesOutsideDeclaredBand() {
        V3BandedMatrix matrix = new V3BandedMatrix(4, 1, 1);

        assertThrows(IllegalArgumentException.class, () -> matrix.set(0, 2, 1.0));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(-1, 0));
    }

    private static V3BandedMatrix diagonallyDominantBand(int size, int lowerBandwidth, int upperBandwidth, Random random) {
        V3BandedMatrix matrix = new V3BandedMatrix(size, lowerBandwidth, upperBandwidth);
        for (int row = 0; row < size; row++) {
            double offDiagonalMagnitude = 0.0;
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                if (column == row) continue;
                double value = random.nextDouble(-1.0, 1.0);
                matrix.set(row, column, value);
                offDiagonalMagnitude += Math.abs(value);
            }
            matrix.set(row, row, offDiagonalMagnitude + 1.0 + random.nextDouble());
        }
        return matrix;
    }

    private static double[] vector(int length, Random random) {
        double[] values = new double[length];
        for (int index = 0; index < length; index++) values[index] = random.nextDouble(-2.0, 2.0);
        return values;
    }

    private static double[] multiply(V3BandedMatrix matrix, double[] vector) {
        double[] result = new double[matrix.size()];
        for (int row = 0; row < matrix.size(); row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                result[row] += matrix.get(row, column) * vector[column];
            }
        }
        return result;
    }

    private static double[][] toDense(V3BandedMatrix matrix) {
        double[][] dense = new double[matrix.size()][matrix.size()];
        for (int row = 0; row < matrix.size(); row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                dense[row][column] = matrix.get(row, column);
            }
        }
        return dense;
    }

    private static double[] densePivotedSolve(double[][] matrix, double[] rightHandSide) {
        int size = rightHandSide.length;
        double[][] work = new double[size][size];
        double[] right = rightHandSide.clone();
        for (int row = 0; row < size; row++) work[row] = matrix[row].clone();
        for (int pivot = 0; pivot < size; pivot++) {
            int pivotRow = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(work[row][pivot]) > Math.abs(work[pivotRow][pivot])) pivotRow = row;
            }
            if (Math.abs(work[pivotRow][pivot]) <= 1.0e-14) throw new AssertionError("Independent dense fixture is singular");
            if (pivotRow != pivot) {
                double[] temporaryRow = work[pivot]; work[pivot] = work[pivotRow]; work[pivotRow] = temporaryRow;
                double temporaryRight = right[pivot]; right[pivot] = right[pivotRow]; right[pivotRow] = temporaryRight;
            }
            for (int row = pivot + 1; row < size; row++) {
                double multiplier = work[row][pivot] / work[pivot][pivot];
                for (int column = pivot + 1; column < size; column++) work[row][column] -= multiplier * work[pivot][column];
                right[row] -= multiplier * right[pivot];
            }
        }
        double[] result = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double sum = right[row];
            for (int column = row + 1; column < size; column++) sum -= work[row][column] * result[column];
            result[row] = sum / work[row][row];
        }
        return result;
    }

    private static void assertDenseEquals(double[][] expected, double[][] actual) {
        for (int row = 0; row < expected.length; row++) assertArrayEquals(expected[row], actual[row]);
    }
}
