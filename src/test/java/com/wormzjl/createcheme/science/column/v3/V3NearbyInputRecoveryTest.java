package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A nearby cold input must pass the acceptance gates regardless of which numerical path succeeds. */
class V3NearbyInputRecoveryTest {
    @Test
    void nearby551KelvinColdInputPassesTheSameFreshAcceptanceGate() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] flows = new double[thermo.componentBasis().componentCount()];
        flows[6] = 50.0;
        flows[13] = 50.0;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(),
                "test:registered-pr-binary", thermo.componentBasis(), flows, 551.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));

        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertEquals(551.0, success.result().problem().input().feedTemperatureKelvin());
    }
}
