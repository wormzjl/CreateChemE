package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3OperatingDomainValidatorTest {
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";

    @Test
    void admitsTheInclusiveFiftyKilopascalPackageBoundaryBeforeNumericalWork() {
        V3OperatingDomainAssessment.Eligible eligible = assertInstanceOf(
                V3OperatingDomainAssessment.Eligible.class, assess(50_000.0, 750.0));

        assertEquals(V3HybridOperatingLane.LOW_PRESSURE_HYBRID, eligible.lane());
        assertEquals(50_000.0, eligible.minimumNodePressurePascal());
        assertEquals(71_750.0, eligible.maximumNodePressurePascal());
        assertEquals(50_000.0, eligible.packageMinimumPressurePascal());
        assertEquals(2_000_000.0, eligible.packageMaximumPressurePascal());
    }

    @Test
    void rejectsTheOneKilopascalVacuumRequestWithAStableVduAdvisory() {
        V3OperatingDomainAssessment.Rejected rejected = assertInstanceOf(
                V3OperatingDomainAssessment.Rejected.class, assess(1_000.0, 750.0));

        assertEquals(V3OperatingDomainAssessment.Reason.BELOW_PACKAGE_PRESSURE, rejected.reason());
        assertTrue(rejected.detail().contains("VDU_REQUIRED"));
        assertEquals(1_000.0, rejected.minimumNodePressurePascal());
    }

    @Test
    void rejectsAProfileThatExceedsThePackageCeilingBeforeThermodynamics() {
        V3OperatingDomainAssessment.Rejected rejected = assertInstanceOf(
                V3OperatingDomainAssessment.Rejected.class, assess(1_999_000.0, 1_000.0));

        assertEquals(V3OperatingDomainAssessment.Reason.ABOVE_PACKAGE_PRESSURE, rejected.reason());
        assertEquals(2_028_000.0, rejected.maximumNodePressurePascal());
    }

    @Test
    void calculatorReturnsTheTypedPreflightFailureWithoutEnteringTheSolver() {
        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class,
                V3ColumnCalculator.calculate(input(49_999.0, 0.0)));

        assertEquals(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, failure.code());
        assertEquals("admission", failure.diagnostics().solvePath());
        assertTrue(failure.summary().contains("VDU_REQUIRED"));
    }

    @Test
    void hundredKilopascalAndAboveRemainInTheNormalDryCduLane() {
        V3OperatingDomainAssessment.Eligible eligible = assertInstanceOf(
                V3OperatingDomainAssessment.Eligible.class, assess(100_000.0, 750.0));

        assertEquals(V3HybridOperatingLane.NORMAL_CDU, eligible.lane());
    }

    private static V3OperatingDomainAssessment assess(double topPressurePascal, double stageDropPascal) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(
                input(topPressurePascal, stageDropPascal), V3CondenserPhaseBranch.TWO_PHASE);
        return V3OperatingDomainValidator.assess(problem, thermo);
    }

    private static V3ColumnInput input(double topPressurePascal, double stageDropPascal) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[6] = 1.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, PACKAGE_ID, "test:hybrid-admission",
                thermo.componentBasis(), feed, 550.0, 30, 24, topPressurePascal, stageDropPascal, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }
}
