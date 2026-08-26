package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3ColumnInputTest {
    @Test
    void inputDefensivelyCopiesMutableFeedAndSpecificationRepresentations() {
        double[] feed = {40.0, 60.0};
        List<V3ColumnSpecification> specifications = new ArrayList<>(specifications(4.17));
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary", "test:baseline",
                basis(), feed, 450.0, 4, 2, 250_000.0, 750.0, specifications);

        feed[0] = 0.0;
        specifications.clear();
        double[] returnedFeed = input.feedComponentMolarFlowsMolPerSecond();
        returnedFeed[1] = 0.0;

        assertArrayEquals(new double[] {40.0, 60.0}, input.feedComponentMolarFlowsMolPerSecond());
        assertEquals(3, input.specifications().size());
        assertThrows(UnsupportedOperationException.class,
                () -> input.specifications().add(new V3ColumnSpecification.ReboilerDuty(1.0)));
    }

    @Test
    void resolutionGeneratesTheOnlyPermittedPressureProfileAndDigestIsStable() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(4.17), V3CondenserPhaseBranch.TWO_PHASE);
        V3InputDigest first = V3InputDigest.of(problem, "mesh-v1", "ideal-v1", "m0-v1");
        V3InputDigest same = V3InputDigest.of(problem, "mesh-v1", "ideal-v1", "m0-v1");
        V3InputDigest changed = V3InputDigest.of(problem, "mesh-v2", "ideal-v1", "m0-v1");

        assertArrayEquals(new double[] {250_000.0, 250_000.0, 250_750.0, 251_500.0, 252_250.0, 252_250.0},
                problem.nodePressuresPascal());
        assertEquals(first, same);
        assertNotEquals(first, changed);
        assertEquals(64, first.hexadecimalSha256().length());
    }

    @Test
    void resolverRejectsUnsupportedSchemaAndFeedOutsideTheEquilibriumTrayRange() {
        V3ColumnInput unsupportedSchema = new V3ColumnInput(2, "test:ideal_binary", "test:baseline", basis(),
                new double[] {40.0, 60.0}, 450.0, 4, 2, 250_000.0, 750.0, specifications(4.17));
        V3ColumnInput invalidFeedTray = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary",
                "test:baseline", basis(), new double[] {40.0, 60.0}, 450.0, 4, 5, 250_000.0, 750.0,
                specifications(4.17));

        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.resolve(unsupportedSchema, V3CondenserPhaseBranch.TWO_PHASE));
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.resolve(invalidFeedTray, V3CondenserPhaseBranch.TWO_PHASE));
    }

    @Test
    void fixtureSchemaIsVersionedAndExplicitAboutReferenceAuthority() throws IOException {
        try (var stream = getClass().getResourceAsStream("/com/wormzjl/createcheme/science/column/v3/dwsim-reference-fixture.schema.json")) {
            assertTrue(stream != null);
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(schema.contains("\"schemaVersion\": { \"const\": 1 }"));
            assertTrue(schema.contains("REFERENCE_ACCEPTED"));
            assertTrue(schema.contains("MODEL_MISMATCH"));
        }
    }

    @Test
    void outcomeContractNeverPermitsAFailedAcceptanceAuditToPublishSuccess() {
        V3AcceptanceAudit failed = new V3AcceptanceAudit(List.of(
                V3AcceptanceAudit.Check.fail("EQUILIBRIUM", 1.0, 1.0e-8, "fixture failure")));
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(0, 0, 1, 0, 1.0, 1.0, "fixture", List.of(), failed);

        assertFalse(failed.accepted());
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnResult.accepted(inputProblem(), new V3InputDigest("0".repeat(64)), failed));
        V3ColumnOutcome.Failure failure = new V3ColumnOutcome.Failure(
                V3SolverFailureCode.DOF_MISMATCH, "fixture failure", diagnostics);
        assertFalse(failure.isSuccess());
    }

    private static V3ColumnProblem inputProblem() {
        return V3ColumnProblemResolver.resolve(input(4.17), V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3ColumnInput input(double refluxRatio) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary", "test:baseline", basis(),
                new double[] {40.0, 60.0}, 450.0, 4, 2, 250_000.0, 750.0, specifications(refluxRatio));
    }

    private static V3ComponentBasis basis() {
        return new V3ComponentBasis(List.of("methane", "n-pentane"));
    }

    private static List<V3ColumnSpecification> specifications(double refluxRatio) {
        return List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(refluxRatio),
                new V3ColumnSpecification.ReboilerDuty(8_000_000.0));
    }
}
