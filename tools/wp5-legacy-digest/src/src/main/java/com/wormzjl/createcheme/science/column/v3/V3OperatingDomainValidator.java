package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Objects;

/** Checks the complete resolved dry-column pressure profile before any thermodynamic or numerical work begins. */
final class V3OperatingDomainValidator {
    private static final double LOW_PRESSURE_HYBRID_LIMIT_PASCAL = 100_000.0;

    private V3OperatingDomainValidator() {}

    static Assessment assess(V3ColumnProblem problem, V3PengRobinsonThermo thermo) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double pressure : problem.nodePressuresPascal()) {
            minimum = Math.min(minimum, pressure);
            maximum = Math.max(maximum, pressure);
        }
        double packageMinimum = thermo.minimumPressurePascal();
        double packageMaximum = thermo.maximumPressurePascal();
        boolean below = minimum < packageMinimum;
        boolean above = maximum > packageMaximum;
        if (below && above) {
            return new Assessment.Rejected(
                    Assessment.Reason.OUTSIDE_PACKAGE_PRESSURE,
                    minimum, maximum, packageMinimum, packageMaximum);
        }
        if (below) {
            return new Assessment.Rejected(
                    Assessment.Reason.BELOW_PACKAGE_PRESSURE,
                    minimum, maximum, packageMinimum, packageMaximum);
        }
        if (above) {
            return new Assessment.Rejected(
                    Assessment.Reason.ABOVE_PACKAGE_PRESSURE,
                    minimum, maximum, packageMinimum, packageMaximum);
        }
        Assessment.Lane lane = minimum < LOW_PRESSURE_HYBRID_LIMIT_PASCAL
                ? Assessment.Lane.LOW_PRESSURE_HYBRID : Assessment.Lane.NORMAL_CDU;
        return new Assessment.Eligible(lane, minimum, maximum, packageMinimum, packageMaximum);
    }

    /** Immutable admission result for one resolved dry V3 problem and its registered property package. */
    sealed interface Assessment permits Assessment.Eligible, Assessment.Rejected {
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
                Lane lane,
                double minimumNodePressurePascal,
                double maximumNodePressurePascal,
                double packageMinimumPressurePascal,
                double packageMaximumPressurePascal) implements Assessment {
            public Eligible {
                lane = java.util.Objects.requireNonNull(lane, "lane");
                V3OperatingDomainValidator.requireFiniteRange(minimumNodePressurePascal, maximumNodePressurePascal,
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
                double packageMaximumPressurePascal) implements Assessment {
            public Rejected {
                reason = java.util.Objects.requireNonNull(reason, "reason");
                V3OperatingDomainValidator.requireFiniteRange(minimumNodePressurePascal, maximumNodePressurePascal,
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

        /** Internal dry-V3 qualification lane; admission metadata, not a scientific success classification. */
        enum Lane {
            NORMAL_CDU,
            LOW_PRESSURE_HYBRID
        }

        enum Reason {
            BELOW_PACKAGE_PRESSURE,
            ABOVE_PACKAGE_PRESSURE,
            OUTSIDE_PACKAGE_PRESSURE
        }
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
