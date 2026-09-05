package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3ColdCoreBenchmarkWorkerTest {
    @Test
    void closureUsesTheAcceptedNativeTruncationMassDefectRatherThanDiscardingIt() {
        V3ComponentBasis basis = new V3ComponentBasis(List.of("a", "b"));
        V3ColumnInput input = new V3ColumnInput(1, "test:package", "test:assay", basis, new double[] {10.0, 0.0},
                400.0, 2, 1, 200_000.0, 1_000.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(350.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
        V3ColumnStreamProperties stream = new V3ColumnStreamProperties("product", "Product", "LIQUID", 9.0, 0.09,
                350.0, 200_000.0, 0.0, List.of(
                        new V3ColumnStreamProperties.ComponentFraction("a", 1.0, 1.0),
                        new V3ColumnStreamProperties.ComponentFraction("b", 0.0, 0.0)));
        V3AcceptanceAudit audit = new V3AcceptanceAudit(List.of(
                V3AcceptanceAudit.Check.pass("TRUNCATION_MASS_DEFECT", 0.1, 0.2, "fresh sink-edge defect")));

        JsonObject closure = V3ColdCoreBenchmarkWorker.closureJson(input, List.of(stream), 0.01, audit);

        assertEquals(1.0, closure.get("nativeSinkEdgeTruncationAuditMolPerSecond").getAsDouble(), 1.0e-12);
        assertTrue(closure.get("reconstructedHydrocarbonLossMatchesNativeAudit").getAsBoolean());
        assertEquals("ACCEPTANCE_AUDIT_TRUNCATION_MASS_DEFECT",
                closure.get("nativeSinkEdgeTruncationAuditAvailability").getAsString());
    }
}
