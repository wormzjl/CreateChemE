package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ThomasTridiagonalSolverTest {
    @Test
    void thomasMatchesDenseOracleOnDiagonallySafeSystems() {
        Random random = new Random(0xC0FFEE);
        for (int size = 2; size <= 12; size++) {
            double[] lower = new double[size];
            double[] diagonal = new double[size];
            double[] upper = new double[size];
            double[] rhs = new double[size];
            for (int row = 0; row < size; row++) {
                lower[row] = row == 0 ? 0.0 : 0.1 + random.nextDouble();
                upper[row] = row == size - 1 ? 0.0 : 0.1 + random.nextDouble();
                diagonal[row] = 2.0 + Math.abs(lower[row]) + Math.abs(upper[row]);
                rhs[row] = random.nextDouble() - 0.5;
            }
            double[] actual = new double[size];
            double error = ThomasTridiagonalSolver.solve(
                    lower, diagonal, upper, rhs, actual, new double[size], new double[size]);
            double[] expected = denseSolve(lower, diagonal, upper, rhs);
            for (int row = 0; row < size; row++) assertEquals(expected[row], actual[row], 1e-12);
            assertTrue(error <= 1e-12);
        }
    }

    private static double[] denseSolve(double[] lower, double[] diagonal, double[] upper, double[] rhs) {
        int n = diagonal.length;
        double[][] matrix = new double[n][n + 1];
        for (int row = 0; row < n; row++) {
            matrix[row][row] = diagonal[row];
            if (row > 0) matrix[row][row - 1] = lower[row];
            if (row + 1 < n) matrix[row][row + 1] = upper[row];
            matrix[row][n] = rhs[row];
        }
        for (int pivot = 0; pivot < n; pivot++) {
            for (int row = pivot + 1; row < n; row++) {
                double scale = matrix[row][pivot] / matrix[pivot][pivot];
                for (int column = pivot; column <= n; column++) matrix[row][column] -= scale * matrix[pivot][column];
            }
        }
        double[] solution = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double value = matrix[row][n];
            for (int column = row + 1; column < n; column++) value -= matrix[row][column] * solution[column];
            solution[row] = value / matrix[row][row];
        }
        return solution;
    }
}
