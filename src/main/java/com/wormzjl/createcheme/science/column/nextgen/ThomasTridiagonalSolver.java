package com.wormzjl.createcheme.science.column.nextgen;

/** Bounded, caller-workspace Thomas solve with an independently reported backward-error check. */
public final class ThomasTridiagonalSolver {
    private ThomasTridiagonalSolver() {}

    public static double solve(
            double[] lower, double[] diagonal, double[] upper, double[] rightHandSide,
            double[] solution, double[] cPrime, double[] dPrime) {
        int n = diagonal.length;
        if (n < 1 || lower.length != n || upper.length != n || rightHandSide.length != n
                || solution.length != n || cPrime.length != n || dPrime.length != n) {
            throw new IllegalArgumentException("Tridiagonal workspace dimensions differ");
        }
        double pivot = diagonal[0];
        requirePivot(pivot);
        cPrime[0] = upper[0] / pivot;
        dPrime[0] = rightHandSide[0] / pivot;
        for (int index = 1; index < n; index++) {
            pivot = diagonal[index] - lower[index] * cPrime[index - 1];
            requirePivot(pivot);
            cPrime[index] = index == n - 1 ? 0.0 : upper[index] / pivot;
            dPrime[index] = (rightHandSide[index] - lower[index] * dPrime[index - 1]) / pivot;
        }
        solution[n - 1] = dPrime[n - 1];
        for (int index = n - 2; index >= 0; index--) solution[index] = dPrime[index] - cPrime[index] * solution[index + 1];
        return backwardError(lower, diagonal, upper, rightHandSide, solution);
    }

    public static double backwardError(
            double[] lower, double[] diagonal, double[] upper, double[] rightHandSide, double[] solution) {
        double maximum = 0.0;
        for (int index = 0; index < solution.length; index++) {
            double lhs = diagonal[index] * solution[index];
            if (index > 0) lhs += lower[index] * solution[index - 1];
            if (index + 1 < solution.length) lhs += upper[index] * solution[index + 1];
            double scale = Math.abs(rightHandSide[index]) + Math.abs(diagonal[index] * solution[index]);
            if (index > 0) scale += Math.abs(lower[index] * solution[index - 1]);
            if (index + 1 < solution.length) scale += Math.abs(upper[index] * solution[index + 1]);
            maximum = Math.max(maximum, Math.abs(lhs - rightHandSide[index]) / Math.max(scale, 1.0e-300));
        }
        return maximum;
    }

    private static void requirePivot(double pivot) {
        if (!Double.isFinite(pivot) || Math.abs(pivot) < 1.0e-14) {
            throw new IllegalStateException("TRIDIAGONAL_BREAKDOWN");
        }
    }
}
