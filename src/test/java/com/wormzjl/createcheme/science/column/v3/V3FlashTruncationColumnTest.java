package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class V3FlashTruncationColumnTest {
    @Test
    void positiveCutoffFeedFlashIsObservableWithoutRelaxingTheColumnAudit() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 2610.7 * 1000.0 / 3600.0;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 110000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8000000.0)));
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= 45_000_000_000L) {
                throw new CancellationException("flash-trace column integration exceeded the client deadline");
            }
        }, 1.0e-6);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.result().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("CONDENSER_PHASE") && check.passed()));
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        List<String> flashEvents = success.diagnostics().events().stream()
                .filter(event -> event.startsWith("flash-trace ")).toList();
        assertFalse(flashEvents.isEmpty(), () -> success.diagnostics().events().toString());
        assertTrue(flashEvents.stream().allMatch(event -> event.contains("status=") && event.contains("omitted=")
                && event.contains("feed-H=reference")));
        assertTrue(success.diagnostics().events().size() <= V3SolverDiagnostics.MAX_EVENTS);
        assertTrue(success.diagnostics().events().stream().allMatch(event -> event.length() <= 256));
        assertEquals("v3-dry-mesh-r4-flash-trace", success.result().formulationRevision());
        assertEquals(V3InputDigest.of(success.result().problem(), V3ColumnCalculator.FORMULATION_REVISION,
                thermo.datasetRevision(), V3ColumnCalculator.ASSUMPTIONS_REVISION, 1.0e-6), success.result().inputDigest());
        assertArrayEquals(flows, input.feedComponentMolarFlowsMolPerSecond(), 0.0);
    }

    @Test
    void zeroCutoffKeepsTheLegacyCallResultsAndHasNoFlashTraceEvents() {
        V3ColumnInput input = binaryInput();
        V3ColumnOutcome.Success strict = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input));
        V3ColumnOutcome.Success zero = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0.0));
        assertEquals(strict.result().inputDigest(), zero.result().inputDigest());
        assertEquals(strict.result().streams(), zero.result().streams());
        assertEquals(strict.diagnostics(), zero.diagnostics());
        assertEquals("v3-dry-mesh-r2", zero.result().formulationRevision());
        assertTrue(zero.diagnostics().events().stream().noneMatch(event -> event.startsWith("flash-trace ")));
    }

    @Test
    void stageSupportAndFlashPolicyShareExactlyTheSameCutoffDomain() {
        assertEquals(V3TraceTruncationPolicy.MAX_CUTOFF_MOLE_FRACTION, V3TruncationSupport.MAX_CUTOFF_MOLE_FRACTION);
        for (double cutoff : new double[] {0.0, -0.0, 1.0e-6, V3TraceTruncationPolicy.MAX_CUTOFF_MOLE_FRACTION}) {
            assertDoesNotThrow(() -> V3TruncationSupport.requireCutoff(cutoff));
            assertDoesNotThrow(() -> V3TraceTruncationPolicy.requireCutoff(cutoff));
        }
        for (double cutoff : new double[] {-1.0e-6, 0.010001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.requireCutoff(cutoff));
            assertThrows(IllegalArgumentException.class, () -> V3TraceTruncationPolicy.requireCutoff(cutoff));
        }
    }

    private static V3ColumnInput binaryInput() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] flows = new double[thermo.componentBasis().componentCount()];
        flows[6] = 50.0;
        flows[13] = 50.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), flows, 550.0, 2, 1, 250000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(Double.MIN_NORMAL)));
    }
}
