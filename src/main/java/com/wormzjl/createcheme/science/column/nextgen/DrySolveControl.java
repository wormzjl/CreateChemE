package com.wormzjl.createcheme.science.column.nextgen;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Caller-owned cancellation/deadline policy polled only at solver safe points. */
public record DrySolveControl(BooleanSupplier cancellationRequested, long deadlineNanos) {
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    public DrySolveControl {
        cancellationRequested = Objects.requireNonNull(cancellationRequested, "cancellationRequested");
    }

    public static DrySolveControl unbounded() {
        return new DrySolveControl(NEVER_CANCELLED, Long.MAX_VALUE);
    }

    public static DrySolveControl withDeadline(Duration duration, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Deadline duration must be positive");
        }
        long now = System.nanoTime();
        long durationNanos;
        try {
            durationNanos = duration.toNanos();
        } catch (ArithmeticException exception) {
            durationNanos = Long.MAX_VALUE;
        }
        long deadline = durationNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
        return new DrySolveControl(cancellationRequested, deadline);
    }

    Signal checkpoint() {
        if (cancellationRequested.getAsBoolean()) {
            return Signal.CANCELLED;
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0L) {
            return Signal.DEADLINE_EXCEEDED;
        }
        return Signal.CONTINUE;
    }

    enum Signal {
        CONTINUE,
        CANCELLED,
        DEADLINE_EXCEEDED
    }
}
