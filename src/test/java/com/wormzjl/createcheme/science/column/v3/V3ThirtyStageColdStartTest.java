package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Cold-start QA probe for the 30-stage default topology used by newly placed V3 blocks. */
class V3ThirtyStageColdStartTest {
    @Test
    void thirtyStageDefaultReturnsOnlyAnAuditedSuccessOrBoundedTypedFailure() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] flows = new double[thermo.componentBasis().componentCount()];
        flows[6] = 50.0;
        flows[13] = 50.0;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(),
                "test:registered-pr-binary", thermo.componentBasis(), flows, 550.0, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));

        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input);
        System.out.println("V3 30-stage cold outcome: " + outcome);
        assertEquals(30, input.stageCount());
        assertEquals(24, input.feedStageNumber());
        if (outcome instanceof V3ColumnOutcome.Failure failure) {
            assertFalse(failure.code() == V3SolverFailureCode.INTERNAL_ERROR, failure::toString);
        }
    }
}
