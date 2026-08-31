package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Independent rounded-coefficient oracle and physical-branch regressions for the PR cubic. */
class V3PengRobinsonRootPrecisionTest {
    private static final MathContext ORACLE_CONTEXT = new MathContext(90, RoundingMode.HALF_EVEN);
    private static final BigDecimal TWO = BigDecimal.valueOf(2L);
    private static final double[] CAPTURED_FEED = {
            0.0, 0.0012538307614088564, 0.01125827896184405, 0.030567148542406393,
            0.1378591287206576, 0.06609502137128358, 0.11425564814222493, 0.05540489460148969,
            0.10058448104420363, 0.08801950204022142, 0.07490279910729795, 0.06483752146890542,
            0.05504527455357128, 0.0621402109396859, 0.0961061758627012, 0.04167008388209813};

    @ParameterizedTest
    @CsvSource({"0.3649214093189222, 0.022388334423851432",
            "0.3649214101832813, 0.022388334454425896"})
    void capturedCancellationRootsAgreeWithAnIndependentNinetyDigitOracle(double a, double b) {
        Coefficients c = coefficients(a, b);
        double expected = oracle(c, b, 0.1);
        V3PengRobinsonKernel.RootSelection liquid = V3PengRobinsonKernel.selectRoot(a, b, V3PengRobinsonKernel.Root.LIQUID);
        V3PengRobinsonKernel.RootSelection vapor = V3PengRobinsonKernel.selectRoot(a, b, V3PengRobinsonKernel.Root.VAPOR);

        assertTrue(Math.abs(legacySingleRoot(c) - expected) > 1.0e-12, "fixture must expose the original cancellation");
        assertEquals(expected, liquid.selectedCompressibility(), 4.0 * Math.ulp(expected));
        assertEquals(liquid, vapor);
        assertEquals(1, liquid.physicalRootCount());
        assertEquals(0.0, liquid.rootSeparation());
        assertPhysicalAndSmallResidual(liquid, b, c);
    }

    @Test
    void alreadyBackwardAccurateAnalyticRootKeepsItsOriginalBits() {
        double a = 0.027424387844281327;
        double b = 0.00460549065356495;
        Coefficients c = coefficients(a, b);
        long originalBits = Double.doubleToLongBits(legacySingleRoot(c));
        for (V3PengRobinsonKernel.Root phase : V3PengRobinsonKernel.Root.values()) {
            V3PengRobinsonKernel.RootSelection root = V3PengRobinsonKernel.selectRoot(a, b, phase);
            assertEquals(originalBits, Double.doubleToLongBits(root.selectedCompressibility()));
            assertPhysicalAndSmallResidual(root, b, c);
        }
    }

    @Test
    void threePhysicalRootsRetainLiquidVaporOrderingAndSeparation() {
        double a = 0.1;
        double b = 0.01;
        Coefficients c = coefficients(a, b);
        double low = oracle(c, b, 0.02);
        double middle = oracle(c, 0.02, 0.1);
        double high = oracle(c, 0.1, 1.0);
        V3PengRobinsonKernel.RootSelection liquid = V3PengRobinsonKernel.selectRoot(a, b, V3PengRobinsonKernel.Root.LIQUID);
        V3PengRobinsonKernel.RootSelection vapor = V3PengRobinsonKernel.selectRoot(a, b, V3PengRobinsonKernel.Root.VAPOR);

        assertEquals(3, liquid.physicalRootCount());
        assertEquals(3, vapor.physicalRootCount());
        assertTrue(liquid.selectedCompressibility() < middle && middle < vapor.selectedCompressibility());
        assertEquals(low, liquid.selectedCompressibility(), 8.0 * Math.ulp(low));
        assertEquals(high, vapor.selectedCompressibility(), 8.0 * Math.ulp(high));
        assertEquals(high - low, liquid.rootSeparation(), 8.0 * Math.ulp(high));
        assertEquals(liquid.rootSeparation(), vapor.rootSeparation());
        assertPhysicalAndSmallResidual(liquid, b, c);
        assertPhysicalAndSmallResidual(vapor, b, c);
    }

    @Test
    void zeroCovolumeBoundaryKeepsTwoRootsAboveTheExistingPhysicalMargin() {
        // z*(z^2-z+0.1): the zero root is excluded, leaving two physical roots under the existing margin.
        // This is a cubic-guard fixture, not an EOS fugacity evaluation at zero covolume.
        Coefficients c = coefficients(0.1, 0.0);
        V3PengRobinsonKernel.RootSelection liquid = V3PengRobinsonKernel.selectRoot(0.1, 0.0, V3PengRobinsonKernel.Root.LIQUID);
        V3PengRobinsonKernel.RootSelection vapor = V3PengRobinsonKernel.selectRoot(0.1, 0.0, V3PengRobinsonKernel.Root.VAPOR);
        assertEquals(2, liquid.physicalRootCount());
        assertEquals(2, vapor.physicalRootCount());
        double low = oracle(c, 0.05, 0.2);
        double high = oracle(c, 0.8, 0.95);
        assertEquals(low, liquid.selectedCompressibility(), 8.0 * Math.ulp(low));
        assertEquals(high, vapor.selectedCompressibility(), 8.0 * Math.ulp(high));
        assertTrue(liquid.selectedCompressibility() < vapor.selectedCompressibility());
        assertPhysicalAndSmallResidual(liquid, 0.0, c);
        assertPhysicalAndSmallResidual(vapor, 0.0, c);
    }

    @Test
    void coalescenceBandPreservesLegacyClassificationWhileNearbyRootsCannotCrossExtrema() {
        // Exactly (z-3/16)^2*(z-9/16). The existing zero-discriminant policy selects the simple root.
        double baseA = 49.0 / 128.0;
        double b = 1.0 / 16.0;
        for (double displacement : new double[] {-Math.scalb(1.0, -40), -Math.scalb(1.0, -42),
                0.0, Math.scalb(1.0, -42), Math.scalb(1.0, -40)}) {
            double a = baseA + displacement;
            Coefficients c = coefficients(a, b);
            String context = "A displacement=" + displacement + ", discriminant=" + c.discriminant();
            V3PengRobinsonKernel.RootSelection liquid = V3PengRobinsonKernel.selectRoot(a, b, V3PengRobinsonKernel.Root.LIQUID);
            V3PengRobinsonKernel.RootSelection vapor = V3PengRobinsonKernel.selectRoot(a, b, V3PengRobinsonKernel.Root.VAPOR);
            assertTrue(liquid.selectedCompressibility() > b, context);
            assertTrue(vapor.selectedCompressibility() > b, context);
            if (Math.abs(c.discriminant()) <= 1.0e-16) {
                assertEquals(1, liquid.physicalRootCount(), context);
                assertEquals(liquid, vapor, context);
                assertEquals(Double.doubleToLongBits(legacySingleRoot(c)),
                        Double.doubleToLongBits(liquid.selectedCompressibility()), context);
                assertTrue(Math.abs(residual(liquid.selectedCompressibility(), c)) <= 1.0e-12, context);
            } else if (c.discriminant() < 0.0) {
                double gap = Math.sqrt(c.c2() * c.c2() - 3.0 * c.c1());
                double left = (-c.c2() - gap) / 3.0;
                double right = (-c.c2() + gap) / 3.0;
                assertEquals(3, liquid.physicalRootCount(), context);
                assertEquals(3, vapor.physicalRootCount(), context);
                assertTrue(liquid.selectedCompressibility() < left, context);
                assertTrue(vapor.selectedCompressibility() > right, context);
                // Near-multiple roots are forward-ill-conditioned: certify their branch and backward residual.
                assertPhysicalAndSmallResidual(liquid, b, c);
                assertPhysicalAndSmallResidual(vapor, b, c);
            } else {
                assertEquals(1, liquid.physicalRootCount(), context);
                assertEquals(liquid, vapor, context);
                assertPhysicalAndSmallResidual(liquid, b, c);
            }
        }
    }

    @Test
    void exactCapturedFeedFlashConvergesWithinTheUnchangedSixtyFourIterationBudget() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        // Capture contains z after normalization: replay these exact doubles without normalizing a second time.
        System.arraycopy(CAPTURED_FEED, 0, workspace.normalizedOverall, 0, CAPTURED_FEED.length);
        V3FlashResult flash = V3FeedFlash.resolve(thermo, 638.15, 137250.0, workspace);

        assertEquals(V3FeedPhase.TWO_PHASE, flash.phase());
        assertTrue(flash.iterations() > 0 && flash.iterations() <= 64, flash::detail);
        assertEquals(0.7855276025697608, flash.vaporFraction(), 1.0e-9);
        double[] x = flash.liquidComposition();
        double[] y = flash.vaporComposition();
        V3FugacityResult liquid = thermo.fugacity(638.15, 137250.0, x, V3Phase.LIQUID, thermo.newWorkspace());
        V3FugacityResult vapor = thermo.fugacity(638.15, 137250.0, y, V3Phase.VAPOR, thermo.newWorkspace());
        for (int component = 0; component < CAPTURED_FEED.length; component++) {
            assertEquals(CAPTURED_FEED[component], (1.0 - flash.vaporFraction()) * x[component]
                    + flash.vaporFraction() * y[component], 1.0e-12);
            if (CAPTURED_FEED[component] == 0.0) continue;
            double equilibrium = Math.log(y[component]) + vapor.logFugacityCoefficient(component)
                    - Math.log(x[component]) - liquid.logFugacityCoefficient(component);
            assertTrue(Math.abs(equilibrium) <= 1.0e-10, "component " + component + ": " + equilibrium);
        }
    }

    private static void assertPhysicalAndSmallResidual(V3PengRobinsonKernel.RootSelection root, double b, Coefficients c) {
        double z = root.selectedCompressibility();
        assertTrue(Double.isFinite(z) && z > b);
        double scale = Math.abs(z * z * z) + Math.abs(c.c2() * z * z) + Math.abs(c.c1() * z) + Math.abs(c.c0());
        assertTrue(Math.abs(residual(z, c)) <= 32.0 * Math.ulp(scale),
                () -> "cubic residual=" + residual(z, c) + ", scale=" + scale + ", z=" + z);
    }

    private static Coefficients coefficients(double a, double b) {
        double c2 = -(1.0 - b);
        double c1 = a - 3.0 * b * b - 2.0 * b;
        double c0 = -(a * b - b * b - b * b * b);
        double p = c1 - c2 * c2 / 3.0;
        double q = 2.0 * c2 * c2 * c2 / 27.0 - c2 * c1 / 3.0 + c0;
        return new Coefficients(c2, c1, c0, p, q, q * q / 4.0 + p * p * p / 27.0);
    }

    private static double legacySingleRoot(Coefficients c) {
        double squareRoot = Math.sqrt(Math.max(0.0, c.discriminant()));
        return Math.cbrt(-c.q() / 2.0 + squareRoot) + Math.cbrt(-c.q() / 2.0 - squareRoot) - c.c2() / 3.0;
    }

    private static double residual(double z, Coefficients c) {
        return Math.fma(Math.fma(z + c.c2(), z, c.c1()), z, c.c0());
    }

    /** Bisection uses exact binary-double coefficients, not a second double-precision root formula. */
    private static double oracle(Coefficients c, double lower, double upper) {
        BigDecimal c2 = new BigDecimal(c.c2());
        BigDecimal c1 = new BigDecimal(c.c1());
        BigDecimal c0 = new BigDecimal(c.c0());
        BigDecimal lo = new BigDecimal(lower);
        BigDecimal hi = new BigDecimal(upper);
        int lowerSign = oracleResidual(lo, c2, c1, c0).signum();
        assertNotEquals(0, lowerSign);
        assertEquals(-lowerSign, oracleResidual(hi, c2, c1, c0).signum(), "oracle needs a sign-changing bracket");
        for (int iteration = 0; iteration < 350; iteration++) {
            BigDecimal midpoint = lo.add(hi, ORACLE_CONTEXT).divide(TWO, ORACLE_CONTEXT);
            if (midpoint.compareTo(lo) == 0 || midpoint.compareTo(hi) == 0) return midpoint.doubleValue();
            int sign = oracleResidual(midpoint, c2, c1, c0).signum();
            if (sign == 0) return midpoint.doubleValue();
            if (sign == lowerSign) lo = midpoint;
            else hi = midpoint;
        }
        return lo.add(hi, ORACLE_CONTEXT).divide(TWO, ORACLE_CONTEXT).doubleValue();
    }

    private static BigDecimal oracleResidual(BigDecimal z, BigDecimal c2, BigDecimal c1, BigDecimal c0) {
        return z.add(c2, ORACLE_CONTEXT).multiply(z, ORACLE_CONTEXT).add(c1, ORACLE_CONTEXT)
                .multiply(z, ORACLE_CONTEXT).add(c0, ORACLE_CONTEXT);
    }

    private record Coefficients(double c2, double c1, double c0, double p, double q, double discriminant) {}
}
