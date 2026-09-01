package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class V3ActiveComponentBasisTest {
    @Test
    void exactZeroPublicComponentsAreRemovedFromTheNumericalLedgerAndRetainStablePublicIndices() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:active", "test:three",
                new V3ComponentBasis(List.of("component-a", "component-b", "component-c")), new double[] {4.0, 0.0, 6.0},
                400.0, 4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(350.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));

        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3ActiveComponentBasis active = problem.activeComponentBasis();

        assertEquals(3, active.publicBasis().componentCount());
        assertEquals(2, active.componentCount());
        assertEquals(0, active.publicIndex(0));
        assertEquals(2, active.publicIndex(1));
        assertEquals(4.0, active.feedFlowMolPerSecond(0));
        assertEquals(6.0, active.feedFlowMolPerSecond(1));
        assertEquals(29, problem.degreeOfFreedomLedger().unknownCount());
        assertTrue(problem.degreeOfFreedomLedger().isValid());
    }
}
