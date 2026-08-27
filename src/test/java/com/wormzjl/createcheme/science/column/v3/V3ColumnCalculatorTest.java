package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void cooperativeCancellationEscapesInsteadOfBecomingAScientificFailure() {
        AtomicInteger checkpoints = new AtomicInteger();

        assertThrows(CancellationException.class, () -> V3ColumnCalculator.calculate(registeredBinaryPilot(), () -> {
            if (checkpoints.incrementAndGet() >= 6) {
                throw new CancellationException("test cancellation");
            }
        }));

        assertTrue(checkpoints.get() >= 6);
    }

    @Test
    void productionCalculatorUsesTheCertifiedDwsimStageContinuationForThirtyStageRealCrude() {
        long started = System.nanoTime();
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(registeredRealCrudeThirtyStagePilot(), () -> {
                    if (System.nanoTime() - started >= 60_000_000_000L) {
                        throw new AssertionError("production 30-stage DWSIM continuation exceeded its cold-test budget");
                    }
                }));

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().solvePath().contains("dwsim-sequential/4-8-15-30"));
    }

    @Test
    void lowCondenserContinuationFailureReturnsTheFourStageEvidenceWithoutSpendingTheFullServiceDeadline() {
        long started = System.nanoTime();
        V3ColumnOutcome.Failure failure = assertInstanceOf(V3ColumnOutcome.Failure.class,
                V3ColumnCalculator.calculate(lowCondenserRealCrudePilot(), () -> {
                    if (System.nanoTime() - started >= 45_000_000_000L) {
                        throw new AssertionError("low-condenser diagnostic did not return its intermediate-stage evidence");
                    }
                }));

        assertEquals(V3SolverFailureCode.NONCONVERGENCE, failure.code());
        assertTrue(failure.summary().contains("stalled at 4 stages"));
        assertTrue(failure.diagnostics().solvePath().contains("failed-stage-4"));
        assertTrue((System.nanoTime() - started) < 45_000_000_000L);
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

    private static V3ColumnInput registeredRealCrudeThirtyStagePilot() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feedFlows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                feedFlows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static V3ColumnInput lowCondenserRealCrudePilot() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feedFlows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                feedFlows, 673.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }
}
