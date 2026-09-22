package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * Locates one bracketed allowed-to-blocked transition without committing a trial.
 * The caller must first isolate a single transition; endpoint signs alone cannot prove that
 * an interval with several crossings is safe. Every probe replays from the same accepted state.
 * Bracket width bounds the supplied trajectory only; the caller must also qualify the accuracy
 * of that trajectory. A tight time bracket cannot correct an inaccurate hydraulic solve.
 */
final class ThresholdEventLocator {
    private ThresholdEventLocator() {}

    record Settings(double absoluteTimeTolerance, double relativeTimeTolerance, int maximumProbes) {
        Settings {
            if (!Double.isFinite(absoluteTimeTolerance) || absoluteTimeTolerance <= 0
                    || !Double.isFinite(relativeTimeTolerance) || relativeTimeTolerance < 0
                    || relativeTimeTolerance >= 1 || maximumProbes < 1) {
                throw new IllegalArgumentException("Invalid event localization settings");
            }
        }
        static Settings defaults() { return new Settings(1e-6, 1e-8, 48); }
    }

    /** Time is relative to the unchanged start of the enclosing trial interval. */
    record Sample<T>(double seconds, boolean allowed, T value) {
        Sample {
            if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid event sample time");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Only safe.value is eligible for acceptance; blocked.value is a discarded diagnostic trial. */
    record Bracket<T>(Sample<T> safe, Sample<T> blocked, int probes) {
        Bracket {
            Objects.requireNonNull(safe, "safe");
            Objects.requireNonNull(blocked, "blocked");
            if (!safe.allowed() || blocked.allowed() || safe.seconds() >= blocked.seconds() || probes < 0) {
                throw new IllegalArgumentException("Invalid threshold bracket");
            }
        }
        double uncertaintySeconds() { return blocked.seconds() - safe.seconds(); }
    }

    static <T> Bracket<T> locate(Sample<T> safe, Sample<T> blocked,
                                 DoubleFunction<Sample<T>> probe, Settings settings, Runnable checkpoint) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(checkpoint, "checkpoint");
        var initial = new Bracket<>(safe, blocked, 0);
        // Fix tolerance to the original bracket width, not the shrinking bracket or absolute clock.
        double tolerance = Math.max(settings.absoluteTimeTolerance(),
                settings.relativeTimeTolerance() * initial.uncertaintySeconds());
        int probes = 0;
        while (blocked.seconds() - safe.seconds() > tolerance) {
            checkpoint.run();
            if (probes >= settings.maximumProbes()) {
                throw new SparseNewton.Nonconvergence("Threshold event localization budget exhausted");
            }
            double middle = safe.seconds() + (blocked.seconds() - safe.seconds()) * 0.5;
            if (middle <= safe.seconds() || middle >= blocked.seconds()) {
                throw new SparseNewton.Nonconvergence("Threshold event time cannot be represented");
            }
            var trial = Objects.requireNonNull(probe.apply(middle), "event probe");
            if (trial.seconds() != middle) throw new IllegalArgumentException("Event probe returned a different time");
            probes++;
            if (trial.allowed()) safe = trial; else blocked = trial;
        }
        checkpoint.run();
        return new Bracket<>(safe, blocked, probes);
    }
}