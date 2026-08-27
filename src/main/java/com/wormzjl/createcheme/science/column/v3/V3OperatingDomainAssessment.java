package com.wormzjl.createcheme.science.column.v3;

/** Immutable result of admitting one resolved dry V3 problem to the registered property package. */
sealed interface V3OperatingDomainAssessment
        permits V3OperatingDomainAssessment.Eligible, V3OperatingDomainAssessment.Rejected {
    /** The minimum absolute pressure across every resolved column node. */
    double minimumNodePressurePascal();

    /** The maximum absolute pressure across every resolved column node. */
    double maximumNodePressurePascal();

    /** The inclusive lower pressure bound declared by the selected package. */
    double packageMinimumPressurePascal();

    /** The inclusive upper pressure bound declared by the selected package. */
    double packageMaximumPressurePascal();

    /** A resolved profile that is entirely inside the selected package's pressure envelope. */
    record Eligible(
            double minimumNodePressurePascal,
            double maximumNodePressurePascal,
            double packageMinimumPressurePascal,
            double packageMaximumPressurePascal) implements V3OperatingDomainAssessment {
        public Eligible {
            requireFiniteRange(minimumNodePressurePascal, maximumNodePressurePascal,
                    packageMinimumPressurePascal, packageMaximumPressurePascal);
            if (minimumNodePressurePascal < packageMinimumPressurePascal
                    || maximumNodePressurePascal > packageMaximumPressurePascal) {
                throw new IllegalArgumentException("Eligible V3 property-domain assessment exceeds its package envelope");
            }
        }
    }

    /** A profile outside the selected package's pressure envelope, before any numerical solve is attempted. */
    record Rejected(
            Reason reason,
            double minimumNodePressurePascal,
            double maximumNodePressurePascal,
            double packageMinimumPressurePascal,
            double packageMaximumPressurePascal) implements V3OperatingDomainAssessment {
        public Rejected {
            reason = java.util.Objects.requireNonNull(reason, "reason");
            requireFiniteRange(minimumNodePressurePascal, maximumNodePressurePascal,
                    packageMinimumPressurePascal, packageMaximumPressurePascal);
            boolean below = minimumNodePressurePascal < packageMinimumPressurePascal;
            boolean above = maximumNodePressurePascal > packageMaximumPressurePascal;
            if (reason == Reason.BELOW_PACKAGE_PRESSURE && (!below || above)) {
                throw new IllegalArgumentException("Low-pressure V3 property-domain rejection has invalid bounds");
            }
            if (reason == Reason.ABOVE_PACKAGE_PRESSURE && (!above || below)) {
                throw new IllegalArgumentException("High-pressure V3 property-domain rejection has invalid bounds");
            }
            if (reason == Reason.OUTSIDE_PACKAGE_PRESSURE && (!below || !above)) {
                throw new IllegalArgumentException("Combined V3 property-domain rejection has invalid bounds");
            }
        }

        String detail() {
            return switch (reason) {
                case BELOW_PACKAGE_PRESSURE -> "VDU_REQUIRED: resolved minimum pressure "
                        + minimumNodePressurePascal + " Pa(a) is below this dry package's minimum of "
                        + packageMinimumPressurePascal + " Pa(a)";
                case ABOVE_PACKAGE_PRESSURE -> "Resolved maximum pressure " + maximumNodePressurePascal
                        + " Pa(a) exceeds this dry package's maximum of " + packageMaximumPressurePascal + " Pa(a)";
                case OUTSIDE_PACKAGE_PRESSURE -> "Resolved pressure profile " + minimumNodePressurePascal + ".."
                        + maximumNodePressurePascal + " Pa(a) exceeds this dry package's "
                        + packageMinimumPressurePascal + ".." + packageMaximumPressurePascal + " Pa(a) envelope";
            };
        }
    }

    enum Reason {
        BELOW_PACKAGE_PRESSURE,
        ABOVE_PACKAGE_PRESSURE,
        OUTSIDE_PACKAGE_PRESSURE
    }

    private static void requireFiniteRange(
            double minimumNodePressurePascal,
            double maximumNodePressurePascal,
            double packageMinimumPressurePascal,
            double packageMaximumPressurePascal) {
        if (!Double.isFinite(minimumNodePressurePascal) || !Double.isFinite(maximumNodePressurePascal)
                || !Double.isFinite(packageMinimumPressurePascal) || !Double.isFinite(packageMaximumPressurePascal)
                || minimumNodePressurePascal <= 0.0 || packageMinimumPressurePascal <= 0.0
                || maximumNodePressurePascal < minimumNodePressurePascal
                || packageMaximumPressurePascal < packageMinimumPressurePascal) {
            throw new IllegalArgumentException("V3 property-domain assessment has invalid pressure bounds");
        }
    }
}
