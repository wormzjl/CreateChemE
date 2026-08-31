package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cold qualification for the client-reported low-top-pressure condenser operating region. */
class V3LowPressureColdProbeTest {
    private static final long CASE_BUDGET_NANOS = 45_000_000_000L;

    @Test
    void onePointFiveBarTotalCondenserConvergesWithoutOutletGas() {
        V3ColumnOutcome.Success success = calculate(150_000.0);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().solvePath().contains("dwsim"));
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, success.result().problem().topology().condenserPhaseBranch());
        assertFalse(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")));
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("distillate_liquid")));
    }

    @Test
    void onePointOneBarTotalCondenserConvergesWithoutOutletGas() {
        V3ColumnOutcome.Success success = calculate(110_000.0);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().solvePath().contains("dwsim"));
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, success.result().problem().topology().condenserPhaseBranch());
        assertFalse(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")));
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("distillate_liquid")));
    }

    private static V3ColumnOutcome.Success calculate(double topPressurePascal) {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(realCrudeInput(topPressurePascal), () -> {
            if (System.nanoTime() - started >= CASE_BUDGET_NANOS) {
                throw new AssertionError("low-pressure partial-condenser cold solve exceeded its budget");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(System.nanoTime() - started < CASE_BUDGET_NANOS);
        return success;
    }

    private static V3ColumnInput realCrudeInput(double topPressurePascal) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_000.0 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlowMolPerSecond;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, topPressurePascal, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }
}
