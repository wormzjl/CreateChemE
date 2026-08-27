package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class V3BubblePointPreconditionerTest {
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";

    @Test
    void producesAFinitePreparedCandidateFromTheExistingMaterialClosedSeed() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState materialClosed = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.MATERIAL_CLOSED).state();

        V3PreconditionerResult.Prepared prepared = assertInstanceOf(V3PreconditionerResult.Prepared.class,
                V3BubblePointPreconditioner.INSTANCE.prepare(
                        new V3PreconditionerRequest(problem, materialClosed, V3SolveControl.UNBOUNDED),
                        thermo, thermo.newWorkspace()));

        assertEquals(V3PreconditionerId.BUBBLE_POINT, prepared.evidence().id());
        assertEquals(3, prepared.evidence().sweeps());
        assertFinitePositive(problem, prepared.state());
    }

    @Test
    void cancellationEscapesRatherThanBecomingAPreconditionerFailure() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState materialClosed = V3ColumnInitializer.initialize(
                problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.MATERIAL_CLOSED).state();

        assertThrows(CancellationException.class, () -> V3BubblePointPreconditioner.INSTANCE.prepare(
                new V3PreconditionerRequest(problem, materialClosed, () -> {
                    throw new CancellationException("test cancellation");
                }), thermo, thermo.newWorkspace()));
    }

    private static void assertFinitePositive(V3ColumnProblem problem, V3DryMeshState state) {
        for (int node = 0; node < state.nodeCount(); node++) {
            assertTrue(state.temperatureKelvin(node) > 0.0);
            for (int component = 0; component < state.componentCount(); component++) {
                assertTrue(state.vaporFlow(node, component) > 0.0);
                if (problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)) {
                    assertTrue(state.liquidFlow(node, component) > 0.0);
                } else {
                    assertEquals(0.0, state.liquidFlow(node, component));
                }
            }
        }
    }

    private static V3ColumnInput input() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[6] = 50.0;
        feed[13] = 50.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, PACKAGE_ID, "test:bubble-point",
                thermo.componentBasis(), feed, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }
}
