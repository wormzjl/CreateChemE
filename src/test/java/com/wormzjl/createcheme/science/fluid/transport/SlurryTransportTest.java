package com.wormzjl.createcheme.science.fluid.transport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlurryTransportTest {
    @Test void noSolidsRetainsTheExactCarrierAndPackingIsNotAFlowingState() {
        double water = 0.001;
        assertEquals(water, SlurryTransport.effectiveViscosity(water, 0));
        double previous = water;
        for (double fraction : new double[]{0.01, 0.1, 0.3, 0.5, 0.6}) {
            double next = SlurryTransport.effectiveViscosity(water, fraction);
            assertTrue(next > previous);
            previous = next;
        }
        assertEquals(0.205, previous, 0.005);
        assertThrows(IllegalArgumentException.class, () -> SlurryTransport.effectiveViscosity(water, 0.62));
        assertThrows(IllegalArgumentException.class, () -> SlurryTransport.effectiveViscosity(water, 0.7));
    }

    @Test void demoParticleSpeedsMatchTheReviewedPhysicalMagnitudes() {
        // Independent rounded reference values recorded in the plan review, water at 1 mPa.s.
        assertEquals(0.0073, SlurryTransport.settlingSpeed(100e-6, 2500, 1000, 0.001), 0.0001);
        assertEquals(0.174, SlurryTransport.settlingSpeed(1e-3, 2500, 1000, 0.001), 0.001);
        assertEquals(0.073, SlurryTransport.depositionVelocity(100e-6, 2500, 1000, 0.001, 10), 0.001);
        assertTrue(SlurryTransport.settlingSpeed(100e-6, 2500, 1000, 0.01)
                < SlurryTransport.settlingSpeed(100e-6, 2500, 1000, 0.001));
    }

    @Test void NeutralAndBuoyantParticlesFollowTheDeclaredContrastRule() {
        assertEquals(0, SlurryTransport.settlingSpeed(100e-6, 1000, 1000, 0.001));
        assertEquals(SlurryTransport.settlingSpeed(100e-6, 500, 1000, 0.001),
                SlurryTransport.settlingSpeed(100e-6, 1500, 1000, 0.001));
    }

    @Test void BulkSolidMarkerNeverBecomesAZeroSettlingSpeedParticle() {
        assertThrows(IllegalArgumentException.class, () -> SlurryTransport.settlingSpeed(0, 2500, 1000, 0.001));
        assertFalse(SlurryTransport.immobile(99, 100));
        assertFalse(SlurryTransport.immobile(100, 100));
        assertTrue(SlurryTransport.immobile(Math.nextUp(100.0), 100));
        // Slurry thickening can cross 100 Pa.s without reclassifying the carrier itself.
        assertTrue(SlurryTransport.effectiveViscosity(1, 0.6) > 100);
        assertFalse(SlurryTransport.immobile(1, 100));
    }

    @Test void TraceCutoffIsStrictAndCanBeDisabledWithoutTreatingZeroAsPresent() {
        assertFalse(SlurryTransport.activeForBlockage(0, 1, 0));
        assertFalse(SlurryTransport.activeForBlockage(Math.nextDown(1e-8), 1, 1e-8));
        assertTrue(SlurryTransport.activeForBlockage(1e-8, 1, 1e-8));
        assertTrue(SlurryTransport.activeForBlockage(1e-20, 1, 0));
        assertFalse(SlurryTransport.activeForBlockage(1e-8, 2, 1e-8));
    }

    @Test void InvalidTransportInputsNeverCreateUsableProperties() {
        for (double invalid : new double[]{-1, 0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> SlurryTransport.settlingSpeed(1e-4, invalid, 1000, 0.001));
            assertThrows(IllegalArgumentException.class, () -> SlurryTransport.effectiveViscosity(invalid, 0.1));
            assertThrows(IllegalArgumentException.class, () -> SlurryTransport.immobile(1, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> SlurryTransport.activeForBlockage(2, 1, 1e-8));
        assertThrows(IllegalArgumentException.class, () -> SlurryTransport.activeForBlockage(1, 1, Double.NaN));
    }
}