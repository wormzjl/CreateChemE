package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3TruncationStructureTest {
    @Test
    void traceConfinedToEachPossibleFeedTrayLeavesEverySmallBranchSquareAndFullRank() {
        for (V3CondenserPhaseBranch branch : V3CondenserPhaseBranch.values()) {
            for (int trays = V3ColumnInput.MIN_STAGE_COUNT; trays <= 8; trays++) {
                for (int feedTray = 1; feedTray <= trays; feedTray++) {
                    V3ColumnInput template = V3TruncationSupportTest.problem(branch, 0.0, 2).input();
                    V3ColumnInput input = new V3ColumnInput(template.schemaVersion(), template.packageId(),
                            template.assayId(), template.componentBasis(), template.feedComponentMolarFlowsMolPerSecond(),
                            template.feedTemperatureKelvin(), trays, feedTray, template.topPressurePascal(),
                            template.stagePressureDropPascal(), template.specifications());
                    V3ColumnProblem original = V3ColumnProblemResolver.resolve(input, branch);
                    int nodes = original.topology().nodeCount();
                    double[][] liquid = new double[nodes][2];
                    double[][] vapor = new double[nodes][2];
                    double[] temperatures = new double[nodes];
                    Arrays.fill(temperatures, 400.0);
                    for (int node = 0; node < nodes; node++) {
                        if (original.topology().hasLiquidPhase(node)) liquid[node] = new double[] {0.1, 99.9};
                        if (original.topology().hasVaporPhase(node)) vapor[node] = new double[] {0.1, 99.9};
                    }
                    V3TruncationSupport support = V3TruncationSupport.derive(original, 0.01,
                            new V3DryMeshState(original.topology(), 2, liquid, vapor, temperatures));
                    V3ColumnProblem masked = V3ColumnProblemResolver.withTruncation(original, support);
                    V3StageBlockLayout layout = new V3StageBlockLayout(masked);
                    assertEquals(nodes - 1, support.truncatedPointCount());
                    assertEquals(0, support.closurePrunedCount());
                    int expectedCount = branch == V3CondenserPhaseBranch.TWO_PHASE ? 2 : 1;
                    assertEquals(expectedCount, layout.size(0));
                    for (int node = 1; node < nodes; node++) {
                        int expectedSize = node == feedTray ? 5 : 3;
                        assertEquals(expectedSize, layout.size(node));
                        expectedCount += expectedSize;
                    }
                    V3DegreeOfFreedomLedger ledger = masked.degreeOfFreedomLedger();
                    assertEquals(expectedCount, ledger.unknownCount());
                    assertEquals(expectedCount, ledger.equationCount());
                    assertEquals(expectedCount, ledger.structuralRank());
                    assertTrue(ledger.isValid(), ledger::humanReadableDiagnostic);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void reducedLedgerIsSquareFullRankAndContainsOnlyRetainedPoints(V3CondenserPhaseBranch branch) {
        V3ColumnProblem original = V3TruncationSupportTest.problem(branch, 0.0, 2);
        V3TruncationSupport support = V3TruncationSupportTest.topTailSupport(original);
        V3ColumnProblem masked = V3ColumnProblemResolver.withTruncation(original, support);
        V3DegreeOfFreedomLedger ledger = masked.degreeOfFreedomLedger();

        assertNotSame(original, masked);
        assertSame(original.input(), masked.input());
        assertSame(original.topology(), masked.topology());
        assertSame(original.activeComponentBasis(), masked.activeComponentBasis());
        assertSame(original.condenserComponentPhases(), masked.condenserComponentPhases());
        assertArrayEquals(original.nodePressuresPascal(), masked.nodePressuresPascal());
        assertSame(support, masked.truncationSupport());
        assertSame(support, ledger.truncationSupport());
        assertTrue(original.truncationSupport().isIdentity());
        int removed = branch == V3CondenserPhaseBranch.TWO_PHASE ? 4 : 3;
        assertEquals(original.degreeOfFreedomLedger().unknownCount() - removed, ledger.unknownCount());
        assertEquals(ledger.unknownCount(), ledger.equationCount());
        assertEquals(ledger.equationCount(), ledger.structuralRank());
        assertTrue(ledger.isValid(), ledger::humanReadableDiagnostic);
        assertEquals(original.degreeOfFreedomLedger().unknowns().stream()
                .filter(unknown -> unknown.id().component() < 0 || support.retains(unknown.id().node(), unknown.id().component()))
                .toList(), ledger.unknowns());
        assertEquals(original.degreeOfFreedomLedger().equations().stream()
                .map(V3DegreeOfFreedomLedger.Equation::id)
                .filter(id -> id.component() < 0 || support.retains(id.node(), id.component())).toList(),
                ledger.equations().stream().map(V3DegreeOfFreedomLedger.Equation::id).toList());
        for (V3DegreeOfFreedomLedger.Equation equation : ledger.equations()) {
            for (V3DegreeOfFreedomLedger.UnknownId reference : equation.referencedUnknowns()) {
                assertTrue(reference.component() < 0 || support.retains(reference.node(), reference.component()));
            }
        }
        V3StageBlockLayout layout = new V3StageBlockLayout(masked);
        assertEquals(branch == V3CondenserPhaseBranch.TWO_PHASE ? 2 : 1, layout.size(0));
        assertEquals(3, layout.size(1));
        int offset = 0;
        for (int node = 0; node < layout.nodeCount(); node++) {
            assertEquals(offset, layout.start(node));
            if (node >= 2) assertEquals(5, layout.size(node));
            offset += layout.size(node);
        }
        assertEquals(ledger.unknownCount(), offset);
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void identityLedgerKeepsOrderingStructuralReferencesAndDiagnosticsExactly(V3CondenserPhaseBranch branch) {
        V3ColumnProblem problem = V3TruncationSupportTest.problem(branch, 0.0, 2);
        V3DegreeOfFreedomLedger original = V3DegreeOfFreedomLedger.create(problem.topology(), 2,
                problem.input().specifications(), problem.condenserComponentPhases());
        V3DegreeOfFreedomLedger explicitIdentity = V3DegreeOfFreedomLedger.create(problem.topology(), 2,
                problem.input().specifications(), problem.condenserComponentPhases(), problem.truncationSupport());

        assertEquals(original.unknowns(), explicitIdentity.unknowns());
        assertEquals(original.equations(), explicitIdentity.equations());
        assertEquals(original.diagnostics(), explicitIdentity.diagnostics());
        assertEquals(original.calculatedQuantities(), explicitIdentity.calculatedQuantities());
        assertEquals(original.humanReadableDiagnostic(), explicitIdentity.humanReadableDiagnostic());
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void decodeLeavesTruncatedPointsExactlyZeroWithoutChangingTheCoordinateMap(V3CondenserPhaseBranch branch) {
        V3ColumnProblem original = V3TruncationSupportTest.problem(branch, 0.0, 2);
        V3TruncationSupport support = V3TruncationSupportTest.topTailSupport(original);
        V3ColumnProblem masked = V3ColumnProblemResolver.withTruncation(original, support);
        V3DryMeshCoordinateMap map = new V3DryMeshCoordinateMap(masked);
        double[][] flows = V3TruncationSupportTest.uniformFlows(original);
        V3DryMeshState seed = V3TruncationSupportTest.state(original, flows, flows);
        V3DryMeshState decoded = map.decode(map.encode(seed));

        assertEquals(masked.degreeOfFreedomLedger().unknownCount(), map.coordinateCount());
        for (int node = 0; node < decoded.nodeCount(); node++) {
            assertEquals(seed.temperatureKelvin(node), decoded.temperatureKelvin(node));
            for (int component = 0; component < decoded.componentCount(); component++) {
                if (!support.retains(node, component)) {
                    assertEquals(0L, Double.doubleToLongBits(decoded.liquidFlow(node, component)));
                    assertEquals(0L, Double.doubleToLongBits(decoded.vaporFlow(node, component)));
                } else {
                    assertEquals(seed.liquidFlow(node, component), decoded.liquidFlow(node, component), 1.0e-12);
                    assertEquals(seed.vaporFlow(node, component), decoded.vaporFlow(node, component), 1.0e-12);
                }
            }
        }
    }

    @Test
    void rejectsSupportLedgerMismatchBeforeLayoutAndDoesNotAllowNestedMasks() {
        V3ColumnProblem original = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3TruncationSupport support = V3TruncationSupportTest.topTailSupport(original);
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnProblem(original.input(), original.topology(),
                original.activeComponentBasis(), original.condenserComponentPhases(), original.nodePressuresPascal(),
                original.degreeOfFreedomLedger(), support));
        V3ColumnProblem masked = V3ColumnProblemResolver.withTruncation(original, support);
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.withTruncation(masked, original.truncationSupport()));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(masked, 0.01, null));
        assertFalse(masked.truncationSupport().isIdentity());
    }

    @Test
    void rejectsIncompatibleTopologyAndComponentAxesEvenForIdentity() {
        V3ColumnProblem original = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3TruncationSupport wrongFeed = V3TruncationSupport.identity(V3ColumnTopology.twoPhase(4, 3), 2);
        V3TruncationSupport wrongBranch = V3TruncationSupport.identity(V3ColumnTopology.liquidOnly(4, 2), 2);
        V3TruncationSupport wrongAxis = V3TruncationSupport.identity(original.topology(), 1);
        for (V3TruncationSupport incompatible : new V3TruncationSupport[] {wrongFeed, wrongBranch, wrongAxis}) {
            assertThrows(IllegalArgumentException.class,
                    () -> V3ColumnProblemResolver.withTruncation(original, incompatible));
            assertThrows(IllegalArgumentException.class, () -> V3DegreeOfFreedomLedger.create(original.topology(), 2,
                    original.input().specifications(), original.condenserComponentPhases(), incompatible));
        }
    }
}
