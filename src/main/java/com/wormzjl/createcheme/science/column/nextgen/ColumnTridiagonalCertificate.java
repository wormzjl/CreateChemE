package com.wormzjl.createcheme.science.column.nextgen;

/**
 * Column-specific M-matrix certificate.  The generic Thomas implementation intentionally accepts
 * either pivot sign; this guard is used only before a hydrocarbon-column solution can overwrite
 * the current physical flow profile.
 */
final class ColumnTridiagonalCertificate {
    private ColumnTridiagonalCertificate() {}

    static Result certify(double[] lower, double[] diagonal, double[] upper, double[] rightHandSide) {
        int size = diagonal.length;
        if (size == 0 || lower.length != size || upper.length != size || rightHandSide.length != size) {
            throw new IllegalArgumentException("Tridiagonal certificate dimensions differ");
        }
        double minimumPositivePivot = Double.POSITIVE_INFINITY;
        int minimumPositivePivotRow = -1;
        double previousCPrime = 0.0;
        for (int row = 0; row < size; row++) {
            if (!Double.isFinite(diagonal[row]) || !Double.isFinite(lower[row]) || !Double.isFinite(upper[row])
                    || !Double.isFinite(rightHandSide[row])) {
                return Result.failure(row, Double.NaN, minimumPositivePivot, minimumPositivePivotRow, "non-finite coefficient");
            }
            if (!(diagonal[row] > 0.0)) {
                return Result.failure(row, diagonal[row], minimumPositivePivot, minimumPositivePivotRow, "non-positive diagonal");
            }
            if ((row > 0 && lower[row] > 0.0) || (row + 1 < size && upper[row] > 0.0)) {
                return Result.failure(row, diagonal[row], minimumPositivePivot, minimumPositivePivotRow, "positive off-diagonal");
            }
            if (rightHandSide[row] < 0.0) {
                return Result.failure(row, diagonal[row], minimumPositivePivot, minimumPositivePivotRow, "negative right-hand side");
            }
            double pivot = row == 0 ? diagonal[row] : diagonal[row] - lower[row] * previousCPrime;
            if (!(pivot > 0.0) || !Double.isFinite(pivot)) {
                return Result.failure(row, pivot, minimumPositivePivot, minimumPositivePivotRow, "non-positive elimination pivot");
            }
            if (pivot < minimumPositivePivot) {
                minimumPositivePivot = pivot;
                minimumPositivePivotRow = row;
            }
            previousCPrime = row + 1 == size ? 0.0 : upper[row] / pivot;
        }
        return Result.success(minimumPositivePivot, minimumPositivePivotRow);
    }

    record Result(boolean accepted, int row, double pivot, double minimumPositivePivot, int minimumPositivePivotRow,
                  String detail) {
        static Result success(double minimumPositivePivot, int minimumPositivePivotRow) {
            return new Result(true, -1, Double.NaN, minimumPositivePivot, minimumPositivePivotRow,
                    "positive elimination pivots");
        }

        static Result failure(int row, double pivot, double minimumPositivePivot, int minimumPositivePivotRow,
                              String detail) {
            return new Result(false, row, pivot, minimumPositivePivot, minimumPositivePivotRow, detail);
        }
    }
}
