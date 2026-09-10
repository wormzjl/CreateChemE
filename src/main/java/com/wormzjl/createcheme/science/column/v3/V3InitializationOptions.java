package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Immutable admission snapshot. Neural budgets never replace the caller's overall deadline. */
public record V3InitializationOptions(Mode mode, WetStart wetStart, int maximumIterations, int budgetMilliseconds) {
    public enum Mode { LNN_FIRST, LNN_ONLY, CURRENT_ONLY }
    public enum WetStart { AUTO, DRY_START, PREDICTED_WET }

    public static final V3InitializationOptions DEFAULT =
            new V3InitializationOptions(Mode.LNN_FIRST, WetStart.AUTO, 16, 2_000);
    public static final V3InitializationOptions CURRENT =
            new V3InitializationOptions(Mode.CURRENT_ONLY, WetStart.DRY_START, 16, 2_000);

    public V3InitializationOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(wetStart, "wetStart");
        if (maximumIterations < 1 || maximumIterations > V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS
                || budgetMilliseconds < 1 || budgetMilliseconds > 60_000) {
            throw new IllegalArgumentException("Invalid neural initialization budget");
        }
    }
}
