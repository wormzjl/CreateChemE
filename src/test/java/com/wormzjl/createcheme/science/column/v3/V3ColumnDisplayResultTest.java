package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3ColumnDisplayResultTest {
    @Test
    void acceptedResultCreatesOnlyABoundedPresentationCertificate() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = 50.0;
        feedFlows[13] = 50.0;
        V3ColumnInput input = new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary", thermo.componentBasis(),
                feedFlows, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));

        V3ColumnOutcome.Success success = (V3ColumnOutcome.Success) V3ColumnCalculator.calculate(input);
        V3ColumnDisplayResult view = V3ColumnDisplayResult.fromAccepted(success);

        assertEquals(success.result().inputDigest().hexadecimalSha256(), view.inputDigest());
        assertEquals(success.diagnostics().newtonIterations(), view.newtonIterations());
        assertEquals(success.diagnostics().maximumScaledResidual(), view.maximumScaledResidual());
        assertTrue(view.acceptanceCheckCount() > 0);
        assertEquals(success.result().streams(), view.streams());
        assertEquals(3, view.streams().size());
        for (V3ColumnStreamProperties stream : view.streams()) {
            assertTrue(stream.molarFlowMolPerSecond() > 0.0);
            assertTrue(stream.massFlowKgPerSecond() > 0.0);
            assertEquals("VAPOR".equals(stream.phase()) ? 1.0 : 0.0, stream.vaporMoleFraction());
            assertEquals(1.0, stream.moleFractions().stream()
                    .mapToDouble(V3ColumnStreamProperties.ComponentFraction::moleFraction).sum(), 1.0e-8);
            assertEquals(1.0, stream.moleFractions().stream()
                    .mapToDouble(V3ColumnStreamProperties.ComponentFraction::massFraction).sum(), 1.0e-8);
        }
    }
}
