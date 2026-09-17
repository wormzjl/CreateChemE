package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The V3 half of the PR cubic's root-precision regression: a captured two-phase feed whose flash only
 * converges inside its iteration budget when the kernel's Cardano repair is in place.
 *
 * <p>The independent ninety-digit oracle and the physical-branch regressions for the cubic itself moved to
 * {@code science.thermo.PengRobinsonKernelRootPrecisionTest} with the kernel. This case stayed here because
 * it drives {@code V3FeedFlash}, which is the column's own flash, not the kernel's.</p>
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.createcheme.science.material.Cdu17FixtureExtension.class)
class V3PengRobinsonRootPrecisionTest {
    private static final double[] CAPTURED_FEED = {
            0.0, 0.0012538307614088564, 0.01125827896184405, 0.030567148542406393,
            0.1378591287206576, 0.06609502137128358, 0.11425564814222493, 0.05540489460148969,
            0.10058448104420363, 0.08801950204022142, 0.07490279910729795, 0.06483752146890542,
            0.05504527455357128, 0.0621402109396859, 0.0961061758627012, 0.04167008388209813};

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
}
