package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exact client operating point: the smaller grids are liquid-only, but the 30-stage condenser needs vapor. */
class V3CondenserPhaseContinuationTest {
    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0e-6})
    void coldHighThroughputOnePointOneBarTransitionsAtTheRequestedGrid(double cutoff) {
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
                throw new CancellationException("110 kPa phase continuation exceeded the client deadline");
            }
        }, cutoff);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        V3ColumnProblem selected = success.result().problem();
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, selected.topology().condenserPhaseBranch());
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.result().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("CONDENSER_PHASE") && check.passed()));
        assertTrue(success.diagnostics().events().stream().anyMatch(event -> event.startsWith("condenser phase transition:")));
        assertFalse(success.diagnostics().solvePath().contains("material-closed-fallback"));
        assertFalse(success.diagnostics().solvePath().contains("coarse-fd-recovery"));
        assertFalse(success.diagnostics().solvePath().endsWith("liquid-only-condenser"));
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("overhead_vapor")
                && stream.molarFlowMolPerSecond() > 0.0));
        assertEquals(V3InputDigest.of(selected, V3ColumnCalculator.formulationRevision(cutoff), thermo.datasetRevision(),
                V3ColumnCalculator.ASSUMPTIONS_REVISION, cutoff), success.result().inputDigest());
        V3ColumnProblem obsoleteBranch = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.LIQUID_ONLY);
        assertNotEquals(V3InputDigest.of(obsoleteBranch, V3ColumnCalculator.formulationRevision(cutoff), thermo.datasetRevision(),
                V3ColumnCalculator.ASSUMPTIONS_REVISION, cutoff), success.result().inputDigest());
        assertTrue(System.nanoTime() - started < 45_000_000_000L);
    }
}
