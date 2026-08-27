package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Objects;

/** Checks the complete resolved dry-column pressure profile before any thermodynamic or numerical work begins. */
final class V3OperatingDomainValidator {
    private V3OperatingDomainValidator() {}

    static V3OperatingDomainAssessment assess(V3ColumnProblem problem, V3PengRobinsonThermo thermo) {
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
            return new V3OperatingDomainAssessment.Rejected(
                    V3OperatingDomainAssessment.Reason.OUTSIDE_PACKAGE_PRESSURE,
                    minimum, maximum, packageMinimum, packageMaximum);
        }
        if (below) {
            return new V3OperatingDomainAssessment.Rejected(
                    V3OperatingDomainAssessment.Reason.BELOW_PACKAGE_PRESSURE,
                    minimum, maximum, packageMinimum, packageMaximum);
        }
        if (above) {
            return new V3OperatingDomainAssessment.Rejected(
                    V3OperatingDomainAssessment.Reason.ABOVE_PACKAGE_PRESSURE,
                    minimum, maximum, packageMinimum, packageMaximum);
        }
        return new V3OperatingDomainAssessment.Eligible(minimum, maximum, packageMinimum, packageMaximum);
    }
}
