package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Random;
import org.junit.jupiter.api.Test;

class V3FeedFlashEquivalenceTest {
    @Test
    void rootMatchesFrozenHundredStepAlgorithmIncludingEndpointsAndInvalidInputs() throws Exception {
        Method optimized = V3FeedFlash.class.getDeclaredMethod("rachfordRiceRoot",
                double[].class, double[].class, double[].class, double[].class);
        Method reference = V3FeedFlashReference.class.getDeclaredMethod("rachfordRiceRoot", double[].class, double[].class);
        optimized.setAccessible(true);
        reference.setAccessible(true);
        Random random = new Random(20260909);
        for (int sample = 0; sample < 1000; sample++) {
            double[] z = new double[19];
            double[] logK = new double[19];
            double total = 0;
            for (int i = 0; i < z.length; i++) {
                z[i] = random.nextBoolean() ? 0 : random.nextDouble();
                total += z[i];
                logK[i] = -700 + 1400 * random.nextDouble();
            }
            if (total == 0) z[0] = total = 1;
            for (int i = 0; i < z.length; i++) z[i] /= total;
            assertRoot(reference, optimized, z, logK);
        }
        for (double special : new double[]{-1000, -60, -1e-25, 0, 1e-25, 60, 1000,
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertRoot(reference, optimized, new double[]{0.5, 0.5}, new double[]{special, -special});
            assertRoot(reference, optimized, new double[]{1, 0}, new double[]{special, Double.NaN});
        }
    }

    private static void assertRoot(Method reference, Method optimized, double[] z, double[] logK) throws Exception {
        double expected = (double) reference.invoke(null, z, logK);
        double actual = (double) optimized.invoke(null, z, logK, new double[z.length], new double[z.length]);
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    @Test
    void completeFlashAndFailureOutcomesMatchFrozenMain() {
        int successes = 0;
        int failures = 0;
        for (String packageId : new String[]{"createcheme:cdu17_tjl_acs2018", "createcheme:tjl19_dwsim"}) {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
            double[] overall = thermo.crudeFeed("createcheme:tia_juana_light").moleFractions();
            for (double temperature : new double[]{298.15, 323.15, 500, 638.15, 900}) {
                for (double pressure : new double[]{50_000, 110_000, 267_250}) {
                    V3ThermoWorkspace oldWorkspace = thermo.newWorkspace();
                    V3ThermoWorkspace newWorkspace = thermo.newWorkspace();
                    System.arraycopy(overall, 0, oldWorkspace.normalizedOverall, 0, overall.length);
                    System.arraycopy(overall, 0, newWorkspace.normalizedOverall, 0, overall.length);
                    V3FlashResult expected;
                    try {
                        expected = V3FeedFlashReference.resolve(thermo, temperature, pressure, oldWorkspace);
                    } catch (V3ThermoException failure) {
                        V3ThermoException actual = assertThrows(V3ThermoException.class,
                                () -> V3FeedFlash.resolve(thermo, temperature, pressure, newWorkspace));
                        assertEquals(failure.getMessage(), actual.getMessage());
                        assertEquals(failure.code(), actual.code());
                        assertEquals(failure.phase(), actual.phase());
                        failures++;
                        continue;
                    }
                    V3FlashResult actual = V3FeedFlash.resolve(thermo, temperature, pressure, newWorkspace);
                    assertEquals(expected.phase(), actual.phase());
                    assertEquals(expected.iterations(), actual.iterations());
                    assertEquals(expected.detail(), actual.detail());
                    assertEquals(expected.vaporFraction(), actual.vaporFraction());
                    assertEquals(expected.molarEnthalpyJoulesPerMol(), actual.molarEnthalpyJoulesPerMol());
                    assertArrayEquals(expected.liquidComposition(), actual.liquidComposition());
                    assertArrayEquals(expected.vaporComposition(), actual.vaporComposition());
                    assertArrayEquals(oldWorkspace.nextLogK, newWorkspace.nextLogK);
                    successes++;
                }
            }
        }
        assertTrue(successes > 0);
        assertEquals(30, successes + failures);
    }
}
