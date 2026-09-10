package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class V3Tjl20MethanePropertyPackageTest {
    @Test void extendsTheFrozenDatasetWithoutChangingItsPropertiesOrInteractions() {
        var base = V3Tjl19PropertyPackage.INSTANCE;
        var extended = V3Tjl20MethanePropertyPackage.INSTANCE;
        assertSame(extended, V3PropertyPackageRegistry.require(extended.packageId()));
        assertEquals(20, extended.componentBasis().componentCount());
        assertNotEquals(base.datasetRevision(), extended.datasetRevision());
        double[][] original = base.binaryInteractions();
        double[][] interactions = extended.binaryInteractions();
        for (int component = 0; component < 19; component++) {
            assertEquals(base.component(component), extended.component(component + 1));
            assertEquals(0, interactions[0][component + 1]);
            assertEquals(0, interactions[component + 1][0]);
            for (int other = 0; other < 19; other++) {
                assertEquals(original[component][other], interactions[component + 1][other + 1]);
            }
        }
        interactions[1][2] = 123;
        assertEquals(original[0][1], extended.binaryInteractions()[1][2]);
        double[] feed = extended.crudeFeed(V3Tjl20MethanePropertyPackage.ASSAY_ID).moleFractions();
        double[] oldFeed = base.crudeFeed(V3Tjl19PropertyPackage.ASSAY_ID).moleFractions();
        assertEquals(0.005, feed[0], 1e-15);
        for (int component = 0; component < 19; component++) assertEquals(oldFeed[component] * 0.995, feed[component + 1], 1e-15);
    }

    @Test void methaneThermalFitMatchesIndependentNistShomateAndDifferentiatesConsistently() {
        var methane = V3Tjl20MethanePropertyPackage.INSTANCE.component(0);
        assertEquals(190.564, methane.criticalTemperatureKelvin());
        assertEquals(4_599_200, methane.criticalPressurePascal());
        assertEquals(0.01142, methane.acentricFactor());
        assertEquals(0, methane.idealGasEnthalpy(298.15));
        // The reference is the published rational Shomate expression, not the fitted polynomial.
        for (int point = 0; point <= 1000; point++) {
            double temperature = 298.15 + (900 - 298.15) * point / 1000;
            double t = temperature / 1000;
            double cp = -0.703029 + 108.4773 * t - 42.52157 * t * t + 5.862788 * t * t * t + 0.678565 / (t * t);
            assertEquals(cp, methane.idealGasHeatCapacity(temperature), 0.05, "Cp at " + temperature);
            assertEquals(shomateIntegral(t) - shomateIntegral(0.29815), methane.idealGasEnthalpy(temperature), 0.5,
                    "H relative to 298.15 K at " + temperature);
            double derivative = (methane.idealGasEnthalpy(temperature + 0.001)
                    - methane.idealGasEnthalpy(temperature - 0.001)) / 0.002;
            assertEquals(methane.idealGasHeatCapacity(temperature), derivative, 1e-6);
        }
    }

    @Test void zeroMethaneRetainsTheOriginalFlashAndMethaneEntersTheVaporPhase() {
        var base = V3PengRobinsonThermo.fromRegisteredPackage(V3Tjl19PropertyPackage.PACKAGE_ID);
        var extended = V3PengRobinsonThermo.fromRegisteredPackage(V3Tjl20MethanePropertyPackage.PACKAGE_ID);
        double[] oldFeed = base.crudeFeed(V3Tjl19PropertyPackage.ASSAY_ID).moleFractions();
        double[] zeroMethane = new double[20];
        System.arraycopy(oldFeed, 0, zeroMethane, 1, 19);
        var expected = base.flashTP(638.15, 250_000, oldFeed, base.newWorkspace());
        var actual = extended.flashTP(638.15, 250_000, zeroMethane, extended.newWorkspace());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.vaporFraction(), actual.vaporFraction(), 1e-10);
        assertEquals(expected.molarEnthalpyJoulesPerMol(), actual.molarEnthalpyJoulesPerMol(), 1e-6);
        double[] feed = extended.crudeFeed(V3Tjl20MethanePropertyPackage.ASSAY_ID).moleFractions();
        var flash = extended.flashTP(638.15, 250_000, feed, extended.newWorkspace());
        assertEquals(V3FeedPhase.TWO_PHASE, flash.phase());
        double[] x = flash.liquidComposition();
        double[] y = flash.vaporComposition();
        assertTrue(y[0] > x[0]);
        for (int component = 0; component < 20; component++) {
            assertEquals(feed[component], (1 - flash.vaporFraction()) * x[component] + flash.vaporFraction() * y[component], 1e-10);
        }
    }

    private static double shomateIntegral(double t) {
        return 1000 * (-0.703029 * t + 108.4773 * t * t / 2 - 42.52157 * t * t * t / 3
                + 5.862788 * t * t * t * t / 4 - 0.678565 / t);
    }
}
