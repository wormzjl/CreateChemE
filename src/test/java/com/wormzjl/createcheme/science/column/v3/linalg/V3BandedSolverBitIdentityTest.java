package com.wormzjl.createcheme.science.column.v3.linalg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Guards the flat-array band factorization against any numerical drift from the sparse row envelope it
 * replaced.  {@link V3TreeMapBandedSolverReference} is the frozen old implementation; every fixture here runs
 * both solvers and compares the raw bits of the correction and of all reported evidence, because the
 * calculator's convergence path depends on the exact doubles a solve returns rather than on their accuracy.
 */
class V3BandedSolverBitIdentityTest {
    /** Set to the dump of a real 874-row literature-CDU Jacobian when one is available on this machine. */
    private static final String DUMPED_JACOBIAN_PROPERTY = "createcheme.v3.dumpedJacobian";

    private final TreeSet<String> observedOutcomes = new TreeSet<>();

    @Test
    void everyExistingSolverFixtureStaysBitIdentical() {
        V3BandedMatrix subnormalDiagonal = new V3BandedMatrix(2, 0, 0);
        subnormalDiagonal.set(0, 0, Double.MIN_VALUE);
        subnormalDiagonal.set(1, 1, 2.0 * Double.MIN_VALUE);
        assertBitIdentical("subnormal diagonal", subnormalDiagonal, new double[] {Double.MIN_VALUE, 2.0 * Double.MIN_VALUE});

        V3BandedMatrix subnormalColumn = new V3BandedMatrix(2, 1, 1);
        subnormalColumn.set(0, 0, 1.0); subnormalColumn.set(0, 1, Double.MIN_VALUE);
        subnormalColumn.set(1, 0, 1.0); subnormalColumn.set(1, 1, 2.0 * Double.MIN_VALUE);
        assertBitIdentical("subnormal column scaling", subnormalColumn, new double[] {1.0, 1.0});

        int[] rowExponents = {-300, 100, 0, -100, 300};
        int[] columnExponents = {250, -250, 150, -150, 0};
        double[] expected = {1.0, -2.0, 3.0, -4.0, 5.0};
        V3BandedMatrix scaled = new V3BandedMatrix(expected.length, expected.length - 1, expected.length - 1);
        double[] scaledRightHandSide = new double[expected.length];
        for (int row = 0; row < expected.length; row++) {
            double total = 0.0;
            for (int column = 0; column < expected.length; column++) {
                double coefficient = row == column ? 8.0 : -1.0;
                scaled.set(row, column, Math.scalb(coefficient, rowExponents[row] + columnExponents[column]));
                total += coefficient * expected[column];
            }
            scaledRightHandSide[row] = Math.scalb(total, rowExponents[row]);
        }
        assertBitIdentical("independent row and column scales", scaled, scaledRightHandSide);

        V3BandedMatrix pivoted = new V3BandedMatrix(3, 1, 1);
        pivoted.set(0, 0, 0.0); pivoted.set(0, 1, 2.0);
        pivoted.set(1, 0, 3.0); pivoted.set(1, 1, 4.0); pivoted.set(1, 2, 5.0);
        pivoted.set(2, 1, 6.0); pivoted.set(2, 2, 7.0);
        assertBitIdentical("pivoted tridiagonal", pivoted, new double[] {-4.0, 10.0, 9.0});

        Random random = new Random(0x5EEDBA5DL);
        for (int size = 4; size <= 32; size += 7) {
            V3BandedMatrix dominant = diagonallyDominantBand(size, 2, 3, random);
            double[] solution = new double[size];
            for (int index = 0; index < size; index++) solution[index] = random.nextDouble(-2.0, 2.0);
            assertBitIdentical("diagonally dominant band " + size, dominant, multiply(dominant, solution));
        }

        V3BandedMatrix wilkinsonGrowth = new V3BandedMatrix(4, 3, 3);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                wilkinsonGrowth.set(row, column, column == 3 ? 1.0 : column == row ? 1.0 : column < row ? -1.0 : 0.0);
            }
        }
        assertBitIdentical("elimination fill growth", wilkinsonGrowth, new double[4]);

        assertTrue(observedOutcomes.contains("SUCCESS"), () -> "fixtures never succeeded: " + observedOutcomes);
    }

    @Test
    void everyTypedFailureAndRejectionStaysBitIdentical() {
        assertEquals("FAILURE SINGULAR V3 band matrix contains no nonzero pivot",
                assertBitIdentical("empty band", new V3BandedMatrix(3, 1, 1), new double[3]));

        V3BandedMatrix structurallySingular = new V3BandedMatrix(3, 1, 1);
        structurallySingular.set(0, 0, 1.0);
        structurallySingular.set(2, 2, 1.0);
        assertEquals("FAILURE SINGULAR V3 banded LU encountered a zero or tiny pivot",
                assertBitIdentical("structurally singular band", structurallySingular, new double[] {1.0, 0.0, 1.0}));

        V3BandedMatrix unrepresentable = new V3BandedMatrix(1, 0, 0);
        unrepresentable.set(0, 0, Double.MIN_VALUE);
        assertEquals("FAILURE ILL_CONDITIONED V3 banded LU scaling exceeds the finite numerical range",
                assertBitIdentical("unrepresentable scaled RHS", unrepresentable, new double[] {1.0}));

        // Both rows equilibrate to the same leading entry, so the second pivot survives on cancellation alone
        // and lands just above the zero-pivot guard while the first stays at one.
        V3BandedMatrix pivotSpread = new V3BandedMatrix(2, 1, 1);
        pivotSpread.set(0, 0, 1.0); pivotSpread.set(0, 1, 1.0);
        pivotSpread.set(1, 0, 1.0); pivotSpread.set(1, 1, 1.0 + 5.0e-13);
        assertEquals("FAILURE ILL_CONDITIONED V3 banded LU pivot spread exceeds the configured conditioning guard",
                assertBitIdentical("pivot spread", pivotSpread, new double[] {1.0, 1.0}));

        // Wilkinson's growth matrix doubles the last column at every pivot, so a wide one loses the whole
        // mantissa and its correction can no longer satisfy the backward-error guard.
        int size = 34;
        V3BandedMatrix growth = new V3BandedMatrix(size, size - 1, size - 1);
        double[] growthRightHandSide = new double[size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                growth.set(row, column, column == size - 1 ? 1.0 : column == row ? 1.0 : column < row ? -1.0 : 0.0);
            }
            growthRightHandSide[row] = 1.0 / (row + 1.0);
        }
        assertEquals("FAILURE BACKWARD_ERROR_EXCEEDED V3 banded LU correction does not satisfy the backward-error guard",
                assertBitIdentical("catastrophic pivot growth", growth, growthRightHandSide));

        V3BandedMatrix identity = new V3BandedMatrix(2, 0, 0);
        identity.set(0, 0, 1.0);
        identity.set(1, 1, 1.0);
        assertEquals("THROWN java.lang.IllegalArgumentException: V3 RHS must be finite",
                assertBitIdentical("non-finite RHS", identity, new double[] {1.0, Double.NaN}));
        assertEquals("THROWN java.lang.IllegalArgumentException: V3 RHS dimension differs from the matrix",
                assertBitIdentical("mismatched RHS", identity, new double[] {1.0}));
    }

    @Test
    void deterministicRandomBandsStayBitIdentical() {
        Random random = new Random(0x0BA9DED1DL);
        for (int sample = 0; sample < 600; sample++) {
            int size = random.nextInt(1, 34);
            randomSample(random, sample, size, random.nextInt(0, size + 2), random.nextInt(0, size + 2));
        }
        assertTrue(observedOutcomes.contains("SUCCESS"), () -> "random sweep never succeeded: " + observedOutcomes);
        assertTrue(observedOutcomes.size() >= 3, () -> "random sweep exercised too few paths: " + observedOutcomes);
    }

    @Test
    void deterministicWideBandsStayBitIdentical() {
        // Small samples are effectively dense, so they never exercise the clamped fill span or a pivot lifted
        // from far below the diagonal.  These shapes are narrow bands in tall matrices, like the real Jacobians.
        Random random = new Random(0x0F1177EDL);
        for (int sample = 0; sample < 80; sample++) {
            int size = random.nextInt(40, 130);
            randomSample(random, sample, size, random.nextInt(1, 21), random.nextInt(1, 21));
        }
        assertTrue(observedOutcomes.contains("SUCCESS"), () -> "wide sweep never succeeded: " + observedOutcomes);
    }

    private void randomSample(Random random, int sample, int size, int lowerBandwidth, int upperBandwidth) {
        V3BandedMatrix matrix = new V3BandedMatrix(size, lowerBandwidth, upperBandwidth);
        // Half the samples keep every diagonal entry so the factorization runs to the end; the sparser half
        // dies on a structural zero instead, which is the other half of the contract.
        boolean full = sample % 2 == 0;
        double holes = full ? 0.2 * random.nextDouble() : random.nextDouble();
        int exponentSpread = random.nextInt(0, 41);
        boolean coarse = sample % 3 == 0;
        boolean tinyDiagonals = sample % 5 == 0;
        for (int row = 0; row < size; row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                boolean diagonal = column == row;
                if (!(full && diagonal) && random.nextDouble() < holes) continue;
                // Coarse samples repeat a handful of magnitudes so that pivot ties, exact cancellation and
                // structural zeros show up often; the others spread values over decades instead.
                double value = coarse ? random.nextInt(-4, 5) : random.nextDouble(-4.0, 4.0);
                if (full && diagonal && value == 0.0) value = 1.0;
                int exponent = random.nextInt(-exponentSpread, exponentSpread + 1);
                // A diagonal far below its row forces the search to pivot on a row underneath it.
                if (tinyDiagonals && diagonal) exponent -= 30;
                matrix.set(row, column, Math.scalb(value, exponent));
            }
        }
        double[] rightHandSide = new double[size];
        if (sample % 7 != 0) {
            for (int index = 0; index < size; index++) {
                rightHandSide[index] = Math.scalb(random.nextDouble(-2.0, 2.0), random.nextInt(-exponentSpread, exponentSpread + 1));
            }
        }
        assertBitIdentical("random band " + size + "x" + lowerBandwidth + "/" + upperBandwidth + " sample " + sample,
                matrix, rightHandSide);
    }

    @Test
    void dumpedLiteratureJacobianStaysBitIdentical() {
        Path dump = dumpedJacobian();
        Assumptions.assumeTrue(dump != null,
                "no dumped V3 Jacobian on this machine; set -D" + DUMPED_JACOBIAN_PROPERTY + "=<file> to run it");
        List<String> lines;
        try {
            lines = Files.readAllLines(dump);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        String[] header = lines.get(0).trim().split("\\s+");
        int size = Integer.parseInt(header[0]);
        V3BandedMatrix matrix = new V3BandedMatrix(size, Integer.parseInt(header[1]), Integer.parseInt(header[2]));
        for (int row = 0; row < size; row++) {
            String line = lines.get(row + 1).trim();
            if (line.isEmpty()) continue;
            String[] entries = line.split("\\s+");
            for (int index = 0; index < entries.length; index += 2) {
                matrix.set(row, (int) Double.parseDouble(entries[index]), Double.parseDouble(entries[index + 1]));
            }
        }
        String[] rightHandSideEntries = lines.get(size + 1).trim().split("\\s+");
        double[] rightHandSide = new double[size];
        for (int index = 0; index < size; index++) rightHandSide[index] = Double.parseDouble(rightHandSideEntries[index]);

        assertEquals("SUCCESS", assertBitIdentical("dumped literature Jacobian", matrix, rightHandSide));
    }

    private static Path dumpedJacobian() {
        List<String> candidates = new ArrayList<>();
        String configured = System.getProperty(DUMPED_JACOBIAN_PROPERTY);
        if (configured != null) candidates.add(configured);
        candidates.add("src/test/resources/column/v3/literature-cdu-jacobian.txt");
        candidates.add("D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/recursing-lamarr-6cb268/build/pkgcmp/matrix.txt");
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isReadable(path)) return path;
        }
        return null;
    }

    /** Runs both solvers on the same fixture and fails on the first bit of divergence, returning the outcome. */
    private String assertBitIdentical(String label, V3BandedMatrix matrix, double[] rightHandSide) {
        double[] band = snapshot(matrix);
        double[] referenceRightHandSide = rightHandSide.clone();
        Outcome expected = reference(matrix, referenceRightHandSide);
        assertArrayEquals(band, snapshot(matrix), label + ": the reference solve mutated the matrix");
        double[] bandedRightHandSide = rightHandSide.clone();
        Outcome actual = banded(matrix, bandedRightHandSide);
        assertArrayEquals(band, snapshot(matrix), label + ": the banded solve mutated the matrix");
        assertArrayEquals(rightHandSide, referenceRightHandSide, label + ": the reference solve mutated the RHS");
        assertArrayEquals(rightHandSide, bandedRightHandSide, label + ": the banded solve mutated the RHS");

        assertEquals(expected.kind(), actual.kind(), label + ": outcome");
        assertEquals(expected.pivotSwaps(), actual.pivotSwaps(), label + ": pivot swaps");
        assertBits(label + ": backward error", expected.backwardError(), actual.backwardError());
        assertBits(label + ": minimum pivot", expected.minimumPivot(), actual.minimumPivot());
        assertBits(label + ": maximum pivot", expected.maximumPivot(), actual.maximumPivot());
        assertBits(label + ": pivot growth", expected.pivotGrowth(), actual.pivotGrowth());
        assertEquals(expected.solution().length, actual.solution().length, label + ": solution length");
        for (int index = 0; index < expected.solution().length; index++) {
            assertBits(label + ": solution[" + index + "]", expected.solution()[index], actual.solution()[index]);
        }
        observedOutcomes.add(expected.kind());
        return expected.kind();
    }

    private static Outcome reference(V3BandedMatrix matrix, double[] rightHandSide) {
        try {
            V3TreeMapBandedSolverReference.Result result = V3TreeMapBandedSolverReference.solve(matrix, rightHandSide);
            if (result instanceof V3TreeMapBandedSolverReference.Result.Success success) {
                return new Outcome("SUCCESS", success.solution(), success.backwardError(), success.minimumPivotMagnitude(),
                        success.maximumPivotMagnitude(), success.pivotSwaps(), success.pivotGrowth());
            }
            V3TreeMapBandedSolverReference.Result.Failure failure = (V3TreeMapBandedSolverReference.Result.Failure) result;
            return new Outcome("FAILURE " + failure.code() + " " + failure.detail(), NO_SOLUTION, 0.0,
                    failure.minimumPivotMagnitude(), failure.maximumPivotMagnitude(), failure.pivotSwaps(), failure.pivotGrowth());
        } catch (RuntimeException thrown) {
            return thrown(thrown);
        }
    }

    private static Outcome banded(V3BandedMatrix matrix, double[] rightHandSide) {
        try {
            V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, rightHandSide);
            if (result instanceof V3BandedPivotedSolver.Result.Success success) {
                return new Outcome("SUCCESS", success.solution(), success.backwardError(), success.minimumPivotMagnitude(),
                        success.maximumPivotMagnitude(), success.pivotSwaps(), success.pivotGrowth());
            }
            V3BandedPivotedSolver.Result.Failure failure = (V3BandedPivotedSolver.Result.Failure) result;
            return new Outcome("FAILURE " + failure.code() + " " + failure.detail(), NO_SOLUTION, 0.0,
                    failure.minimumPivotMagnitude(), failure.maximumPivotMagnitude(), failure.pivotSwaps(), failure.pivotGrowth());
        } catch (RuntimeException thrown) {
            return thrown(thrown);
        }
    }

    private static Outcome thrown(RuntimeException thrown) {
        return new Outcome("THROWN " + thrown.getClass().getName() + ": " + thrown.getMessage(), NO_SOLUTION,
                0.0, 0.0, 0.0, 0, 0.0);
    }

    private static void assertBits(String label, double expected, double actual) {
        long expectedBits = Double.doubleToRawLongBits(expected);
        long actualBits = Double.doubleToRawLongBits(actual);
        if (expectedBits != actualBits) {
            fail(String.format(Locale.ROOT, "%s: expected %s (0x%016x) but was %s (0x%016x)",
                    label, expected, expectedBits, actual, actualBits));
        }
    }

    private static double[] snapshot(V3BandedMatrix matrix) {
        double[] entries = new double[matrix.size() * (matrix.lowerBandwidth() + matrix.upperBandwidth() + 1)];
        int index = 0;
        for (int row = 0; row < matrix.size(); row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                entries[index++] = matrix.get(row, column);
            }
        }
        return entries;
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

    private static double[] multiply(V3BandedMatrix matrix, double[] vector) {
        double[] result = new double[matrix.size()];
        for (int row = 0; row < matrix.size(); row++) {
            for (int column = matrix.firstStoredColumn(row); column <= matrix.lastStoredColumn(row); column++) {
                result[row] += matrix.get(row, column) * vector[column];
            }
        }
        return result;
    }

    private static final double[] NO_SOLUTION = new double[0];

    private record Outcome(
            String kind, double[] solution, double backwardError, double minimumPivot, double maximumPivot,
            int pivotSwaps, double pivotGrowth) {}
}
