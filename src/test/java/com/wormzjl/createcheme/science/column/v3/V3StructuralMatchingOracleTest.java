package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class V3StructuralMatchingOracleTest {
    @Test
    void matchingAgreesWithExhaustiveSubsetOracleForSparseAndDeficientGraphs() throws Exception {
        var matching = V3DegreeOfFreedomLedger.class.getDeclaredMethod("maximumBipartiteMatching", List.class, List.class);
        matching.setAccessible(true);
        Random random = new Random(20260909);
        for (int sample = 0; sample < 250; sample++) {
            int columns = 1 + random.nextInt(7);
            int rows = 1 + random.nextInt(7);
            int[] edges = new int[rows];
            List<V3DegreeOfFreedomLedger.Unknown> unknowns = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                unknowns.add(new V3DegreeOfFreedomLedger.Unknown(new V3DegreeOfFreedomLedger.UnknownId(
                        V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 0, column)));
            }
            List<V3DegreeOfFreedomLedger.Equation> equations = new ArrayList<>();
            for (int row = 0; row < rows; row++) {
                List<V3DegreeOfFreedomLedger.UnknownId> references = new ArrayList<>();
                for (int column = 0; column < columns; column++) {
                    if (random.nextInt(3) == 0) {
                        edges[row] |= 1 << column;
                        references.add(unknowns.get(column).id());
                    }
                }
                equations.add(new V3DegreeOfFreedomLedger.Equation(new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, row, 0), references));
            }
            assertEquals(exhaustive(edges, 0, 0), matching.invoke(null, equations, unknowns), "graph " + sample);
        }
    }

    private static int exhaustive(int[] edges, int row, int used) {
        if (row == edges.length) return 0;
        int best = exhaustive(edges, row + 1, used);
        for (int remaining = edges[row] & ~used; remaining != 0; remaining &= remaining - 1) {
            int column = Integer.lowestOneBit(remaining);
            best = Math.max(best, 1 + exhaustive(edges, row + 1, used | column));
        }
        return best;
    }
}
