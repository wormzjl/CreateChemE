package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3ColumnCalculatorTest {
    @Test
    void registeredPrBinaryPilotPublishesOnlyAFreshlyAuditedSuccess() {
        V3ColumnOutcome.Success success = assertInstanceOf(
                V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(registeredBinaryPilot()));

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertEquals(success.result().acceptanceAudit(), success.diagnostics().acceptanceAudit());
        assertEquals(success.result().convergenceEvidence(), success.diagnostics().convergenceEvidence());
        assertEquals(3, success.result().streams().size());
        assertTrue(success.result().streams().stream().allMatch(stream -> stream.molarFlowMolPerSecond() > 0.0));
    }

    @Test
    void unknownPropertyPackageIsAStableTypedFailure() {
        V3ColumnInput invalid = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:missing", "test:missing",
                new V3ComponentBasis(List.of("component-a")), new double[] {1.0}, 400.0, 2, 1, 250_000.0, 0.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));

        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class,
                V3ColumnCalculator.calculate(invalid));

        assertEquals(V3SolverFailureCode.INVALID_INPUT, failure.code());
        assertTrue(failure.diagnostics().acceptanceAudit().checks().stream().noneMatch(V3AcceptanceAudit.Check::passed));
    }

    private static V3ColumnInput registeredBinaryPilot() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = 50.0;
        feedFlows[13] = 50.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }
}
