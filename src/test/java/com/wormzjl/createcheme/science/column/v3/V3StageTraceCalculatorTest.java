package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3StageTraceCalculatorTest {
    @Test
    void zeroCutoffPreservesLegacyDigestStreamsAuditAndDiagnosticsExactly() {
        V3ColumnInput input = binaryInput();
        V3ColumnOutcome.Success original = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input));
        V3ColumnOutcome.Success zero = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0.0));
        assertEquals(original.result().inputDigest(), zero.result().inputDigest());
        assertEquals(original.result().streams(), zero.result().streams());
        assertEquals(original.diagnostics(), zero.diagnostics());
        assertEquals("v3-dry-mesh-r2", V3ColumnDisplayResult.fromAccepted(zero).formulationRevision());
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        assertEquals(V3InputDigest.of(zero.result().problem(), "v3-dry-mesh-r2", thermo.datasetRevision(),
                V3ColumnCalculator.ASSUMPTIONS_REVISION), zero.result().inputDigest());
        assertTrue(zero.result().problem().truncationSupport().isIdentity());
        assertTrue(zero.diagnostics().events().stream().noneMatch(event -> event.startsWith("stage-trace")));
    }

    @Test
    void enabledRequestWithIdentitySupportStillPublishesTheRequestedRevisionAndCutoffDigest() {
        V3ColumnInput input = binaryInput();
        double cutoff = 1.0e-300;
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, cutoff));
        assertTrue(success.result().problem().truncationSupport().isIdentity());
        assertEquals(V3ColumnCalculator.FORMULATION_REVISION, success.result().formulationRevision());
        assertEquals(success.result().formulationRevision(), V3ColumnDisplayResult.fromAccepted(success).formulationRevision());
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        assertEquals(V3InputDigest.of(success.result().problem(), V3ColumnCalculator.FORMULATION_REVISION,
                thermo.datasetRevision(), V3ColumnCalculator.ASSUMPTIONS_REVISION, cutoff), success.result().inputDigest());
    }

    @Test
    void thirtyStageTruncatedSolvePublishesExactZerosAndItsAuditedDefect() {
        double condenserTemperature = 323.15;
        V3ColumnInput input = crudeInput(condenserTemperature);
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 1.0e-6);
        System.out.println("stage trace " + condenserTemperature + ": " + outcome);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3TruncationSupport support = success.result().problem().truncationSupport();
        assertFalse(support.isIdentity(), () -> success.diagnostics().events().toString());
        assertTrue(success.result().acceptanceAudit().accepted());
        V3AcceptanceAudit.Check defect = success.result().acceptanceAudit().checks().stream()
                .filter(check -> check.family().equals("TRUNCATION_MASS_DEFECT")).findFirst().orElseThrow();
        double feed = success.result().problem().activeComponentBasis().totalFeedFlowMolPerSecond();
        double products = success.result().streams().stream().mapToDouble(V3ColumnStreamProperties::molarFlowMolPerSecond).sum();
        assertEquals(defect.value(), (feed - products) / feed, 1.0e-8);
        assertTrue(defect.value() <= 8.0e-6);
        assertTrue(success.diagnostics().events().getFirst().startsWith("stage-trace cutoff="));
        assertTrue(success.diagnostics().events().stream().noneMatch(event -> event.startsWith("stage-trace fallback:")));
        assertTrue(success.diagnostics().events().size() <= 32);
        assertTrue(success.diagnostics().events().stream().allMatch(event -> event.length() <= 256));
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        assertEquals(V3InputDigest.of(success.result().problem(), V3ColumnCalculator.FORMULATION_REVISION,
                thermo.datasetRevision(), V3ColumnCalculator.ASSUMPTIONS_REVISION, 1.0e-6), success.result().inputDigest());
        for (V3ColumnStreamProperties stream : success.result().streams()) {
            int node = stream.streamId().equals("bottoms_liquid") ? success.result().problem().topology().reboilerNode() : 0;
            for (int component = 0; component < success.result().problem().activeComponentBasis().componentCount(); component++) {
                if (support.retains(node, component)) continue;
                int publicIndex = success.result().problem().activeComponentBasis().publicIndex(component);
                assertEquals(0L, Double.doubleToLongBits(stream.moleFractions().get(publicIndex).moleFraction()));
            }
        }
    }

    @Test
    void hotCondenserPhasePathRetriesUntruncatedWhenTheFrozenMaskExceedsItsMassBudget() {
        V3ColumnInput input = crudeInput(400.0);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 1.0e-6));

        // Phase-aware continuation changes the deciding seed. A mask is not entitled to acceptance:
        // the same strict defect budget must reject it and preserve the authored feed on full retry.
        assertTrue(success.result().problem().truncationSupport().isIdentity());
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().events().stream().anyMatch(event -> event.startsWith("stage-trace fallback:")
                && event.contains("TRUNCATION_MASS_DEFECT")), () -> success.diagnostics().events().toString());
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        double feed = success.result().problem().activeComponentBasis().totalFeedFlowMolPerSecond();
        double products = success.result().streams().stream().mapToDouble(V3ColumnStreamProperties::molarFlowMolPerSecond).sum();
        assertEquals(feed, products, feed * 1.0e-8);
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        assertEquals(V3InputDigest.of(success.result().problem(), V3ColumnCalculator.FORMULATION_REVISION,
                thermo.datasetRevision(), V3ColumnCalculator.ASSUMPTIONS_REVISION, 1.0e-6), success.result().inputDigest());
    }

    @Test
    void publicCutoffValidationPrecedesAnyScientificAttempt() {
        for (double invalid : new double[] {-1.0, 0.01001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> V3ColumnCalculator.calculate(binaryInput(),
                    () -> fail("invalid cutoff must not reach an attempt"), invalid));
        }
    }

    static V3ColumnInput crudeInput(double condenserTemperature) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 2_610.7 * 1_000.0 / 3_600.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static V3ColumnInput binaryInput() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] flows = new double[thermo.componentBasis().componentCount()];
        flows[6] = 50;
        flows[13] = 50;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), flows, 550, 2, 1, 250_000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(300),
                new V3ColumnSpecification.OrganicRefluxRatio(2), new V3ColumnSpecification.ReboilerDuty(Double.MIN_NORMAL)));
    }
}
