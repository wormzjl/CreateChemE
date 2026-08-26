package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SampleCrudeInitializerTest {
    @Test
    void registeredTiaJuanaCrudeFlashesAndBuildsAnExactZeroAwareFiniteSeed() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feedFlows = crude.moleFractions();
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= 100.0;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), feedFlows, 638.15, 4, 2, 266_500.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);

        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());

        assertEquals("TWO_PHASE", seed.evidence().feedPhase());
        assertTrue(problem.activeComponentBasis().componentCount() < crude.componentBasis().componentCount());
        assertEquals(problem.activeComponentBasis().componentCount(), seed.state().componentCount());
        for (int node = 0; node < seed.state().nodeCount(); node++) {
            for (int component = 0; component < seed.state().componentCount(); component++) {
                assertTrue(seed.state().vaporFlow(node, component) > 0.0);
                assertTrue(seed.state().liquidFlow(node, component) > 0.0);
            }
        }
    }
}
