package com.wormzjl.createcheme.science.column.nextgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, per-family acceptance evidence for a dry solve attempt.
 *
 * <p>Success is derived only after every check has passed.  Keeping the checks separate prevents
 * a small aggregate residual from concealing a failed material, energy, phase, or state check.</p>
 */
public record DryAcceptanceAudit(List<Check> checks) {
    public DryAcceptanceAudit {
        checks = List.copyOf(checks);
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("An acceptance audit must contain checks");
        }
    }

    public boolean accepted() {
        return checks.stream().allMatch(Check::passed);
    }

    public Optional<Check> firstFailure() {
        return checks.stream().filter(check -> !check.passed()).findFirst();
    }

    public Check require(DryResidualFamily family) {
        Objects.requireNonNull(family, "family");
        return checks.stream()
                .filter(check -> check.family() == family)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Audit has no " + family + " check"));
    }

    /** One limiting sample for one independently enforced tolerance family. */
    public record Check(
            DryResidualFamily family,
            double value,
            double limit,
            int node,
            int component,
            boolean passed,
            String detail) {
        public Check {
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(detail, "detail");
            if (!Double.isFinite(value) || !Double.isFinite(limit) || limit < 0.0) {
                throw new IllegalArgumentException("Acceptance check must have finite nonnegative values");
            }
        }

        public static Check pass(
                DryResidualFamily family, double value, double limit, int node, int component, String detail) {
            return new Check(family, value, limit, node, component, value <= limit, detail);
        }

        public static Check fail(
                DryResidualFamily family, double value, double limit, int node, int component, String detail) {
            return new Check(family, value, limit, node, component, false, detail);
        }
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private final List<Check> checks = new ArrayList<>();

        void add(DryResidualFamily family, double value, double limit, int node, int component, String detail) {
            checks.add(Check.pass(family, value, limit, node, component, detail));
        }

        void fail(DryResidualFamily family, double value, double limit, int node, int component, String detail) {
            checks.add(Check.fail(family, value, limit, node, component, detail));
        }

        DryAcceptanceAudit build() {
            return new DryAcceptanceAudit(checks);
        }
    }
}
