package com.wormzjl.createcheme.science.column.v3;

/**
 * Cooperative cancellation boundary for one V3 calculation.
 *
 * <p>The caller owns the policy (deadline, server shutdown, or explicit cancellation). The dry numerical core owns
 * no executor, clock, or mutable cancellation state; it calls {@link #checkpoint()} only at bounded work boundaries.
 * An implementation may throw a runtime cancellation exception to stop the calculation immediately.</p>
 */
@FunctionalInterface
public interface V3SolveControl {
    V3SolveControl UNBOUNDED = () -> {};

    void checkpoint();
}
