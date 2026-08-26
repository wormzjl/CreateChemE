package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Immutable acceptance evidence consumed by the sole future V3 success gate. */
public record V3AcceptanceAudit(List<Check> checks) {
    public V3AcceptanceAudit {
        checks = List.copyOf(checks);
        if (checks.isEmpty() || checks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A V3 acceptance audit must contain non-null checks");
        }
    }

    public boolean accepted() {
        return checks.stream().allMatch(Check::passed);
    }

    /** One independently recomputed acceptance condition. */
    public record Check(String family, double value, double limit, boolean passed, String detail) {
        public Check {
            family = bounded(family, "family", 96);
            detail = bounded(detail, "detail", 512);
            if (!Double.isFinite(value) || value < 0.0 || !Double.isFinite(limit) || limit < 0.0) {
                throw new IllegalArgumentException("V3 acceptance metrics must be finite and nonnegative");
            }
        }

        public static Check pass(String family, double value, double limit, String detail) {
            return new Check(family, value, limit, true, detail);
        }

        public static Check fail(String family, double value, double limit, String detail) {
            return new Check(family, value, limit, false, detail);
        }

        private static String bounded(String value, String name, int maximumLength) {
            value = Objects.requireNonNull(value, name);
            if (value.isBlank() || value.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is blank or exceeds the bounded contract");
            }
            return value;
        }
    }
}
