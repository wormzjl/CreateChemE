package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThresholdEventLocatorTest {
    @Test void ConvergesOnTheSafeSideOfAnOffGridCrossing() {
        double crossing = Math.sqrt(2);
        var settings = new ThresholdEventLocator.Settings(1e-9, 0, 40);
        var bracket = ThresholdEventLocator.locate(sample(0, crossing), sample(10, crossing),
                t -> sample(t, crossing), settings, () -> {});
        assertTrue(bracket.safe().seconds() < crossing);
        assertTrue(bracket.blocked().seconds() >= crossing);
        assertTrue(bracket.uncertaintySeconds() <= settings.absoluteTimeTolerance());
        assertTrue(bracket.probes() <= 34);
        assertEquals(bracket.safe().seconds(), bracket.safe().value());
    }

    @Test void EqualityCanBeEitherMobileOrBlockedWithoutMovingTheEvent() {
        for (boolean equalityAllowed : new boolean[]{true, false}) {
            var bracket = ThresholdEventLocator.locate(new ThresholdEventLocator.Sample<>(0, true, 0.0),
                    new ThresholdEventLocator.Sample<>(2, false, 2.0),
                    t -> new ThresholdEventLocator.Sample<>(t, equalityAllowed ? t <= 1 : t < 1, t),
                    new ThresholdEventLocator.Settings(1e-8, 0, 32), () -> {});
            assertTrue(bracket.safe().seconds() <= 1);
            assertTrue(bracket.blocked().seconds() >= 1);
            assertTrue(bracket.uncertaintySeconds() <= 1e-8);
        }
    }

    @Test void BudgetExhaustionAndCancellationNeverReturnATrialAsAccepted() {
        var calls = new AtomicInteger();
        assertThrows(SparseNewton.Nonconvergence.class, () -> ThresholdEventLocator.locate(
                sample(0, 0.3), sample(1, 0.3), t -> { calls.incrementAndGet(); return sample(t, 0.3); },
                new ThresholdEventLocator.Settings(1e-12, 0, 3), () -> {}));
        assertEquals(3, calls.get());
        assertThrows(CancellationException.class, () -> ThresholdEventLocator.locate(
                sample(0, 0.3), sample(1, 0.3), t -> fail("Cancelled work must not probe"),
                ThresholdEventLocator.Settings.defaults(), () -> { throw new CancellationException(); }));
    }

    @Test void RejectsAnUnbracketedEventAndAProbeOfTheWrongTime() {
        assertThrows(IllegalArgumentException.class, () -> ThresholdEventLocator.locate(
                sample(0, 2), sample(1, 2), t -> sample(t, 2), ThresholdEventLocator.Settings.defaults(), () -> {}));
        assertThrows(IllegalArgumentException.class, () -> ThresholdEventLocator.locate(
                sample(0, 0.3), sample(1, 0.3), t -> sample(t + 0.01, 0.3),
                ThresholdEventLocator.Settings.defaults(), () -> {}));
    }

    private static ThresholdEventLocator.Sample<Double> sample(double seconds, double crossing) {
        return new ThresholdEventLocator.Sample<>(seconds, seconds < crossing, seconds);
    }
}