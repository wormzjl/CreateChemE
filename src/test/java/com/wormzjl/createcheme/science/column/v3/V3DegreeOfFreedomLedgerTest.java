package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class V3DegreeOfFreedomLedgerTest {
    @Test
    void everySmallSupportedTopologyHasAnIndependentFullStructuralMatching() {
        for (int trayCount = V3ColumnInput.MIN_STAGE_COUNT; trayCount <= 8; trayCount++) {
            for (int componentCount = 1; componentCount <= 4; componentCount++) {
                V3DegreeOfFreedomLedger twoPhase = V3DegreeOfFreedomLedger.create(
                        V3ColumnTopology.twoPhase(trayCount, 1), componentCount, standardSpecifications(0.0));
                V3DegreeOfFreedomLedger vaporOnly = V3DegreeOfFreedomLedger.create(
                        V3ColumnTopology.vaporOnly(trayCount, trayCount), componentCount, standardSpecifications(0.0));

                assertTrue(twoPhase.isValid(), twoPhase::humanReadableDiagnostic);
                assertEquals(twoPhase.equationCount(), twoPhase.structuralRank(), twoPhase::humanReadableDiagnostic);
                assertTrue(vaporOnly.isValid(), vaporOnly::humanReadableDiagnostic);
                assertEquals(vaporOnly.equationCount(), vaporOnly.structuralRank(), vaporOnly::humanReadableDiagnostic);
            }
        }
    }

    @Test
    void handAuditedTwoComponentFourTrayTwoPhaseContractIsSquareAndFullRank() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(4.17, standardSpecifications(4.17)),
                V3CondenserPhaseBranch.TWO_PHASE);
        V3DegreeOfFreedomLedger ledger = problem.degreeOfFreedomLedger();

        assertEquals(6, problem.topology().nodeCount());
        assertEquals(29, ledger.unknownCount());
        assertEquals(29, ledger.equationCount());
        assertEquals(29, ledger.structuralRank());
        assertTrue(ledger.hasFullStructuralRank());
        assertTrue(ledger.isValid());
        assertTrue(ledger.unknowns().stream().anyMatch(unknown -> unknown.id().equals(
                new V3DegreeOfFreedomLedger.UnknownId(
                        V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 0, 0))));
        assertTrue(ledger.unknowns().stream().noneMatch(unknown -> unknown.id().equals(
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE, 0, -1))));
        assertEquals(List.of(V3CalculatedQuantity.CONDENSER_DUTY,
                V3CalculatedQuantity.EXTERNAL_OVERHEAD_COMPONENT_FLOWS,
                V3CalculatedQuantity.BOTTOMS_COMPONENT_FLOWS,
                V3CalculatedQuantity.STAGE_LIQUID_COMPONENT_FLOWS,
                V3CalculatedQuantity.STAGE_VAPOR_COMPONENT_FLOWS), ledger.calculatedQuantities());
        assertTrue(ledger.humanReadableDiagnostic().contains("unknowns=29"));
    }

    @Test
    void zeroRefluxTwoPhaseCondenserKeepsExternalLiquidAndTheFullEquationMap() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(0.0, standardSpecifications(0.0)),
                V3CondenserPhaseBranch.TWO_PHASE);

        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, problem.topology().condenserPhaseBranch());
        assertTrue(problem.topology().hasLiquidPhase(problem.topology().condenserNode()));
        assertEquals(29, problem.degreeOfFreedomLedger().unknownCount());
        assertEquals(29, problem.degreeOfFreedomLedger().equationCount());
        assertTrue(problem.degreeOfFreedomLedger().isValid());
    }

    @Test
    void vaporOnlyCondenserRemovesOnlyTheMatchingCondenserLiquidAndVleFamilies() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(0.0, standardSpecifications(0.0)),
                V3CondenserPhaseBranch.VAPOR_ONLY);
        V3DegreeOfFreedomLedger ledger = problem.degreeOfFreedomLedger();

        assertEquals(27, ledger.unknownCount());
        assertEquals(27, ledger.equationCount());
        assertEquals(27, ledger.structuralRank());
        assertTrue(ledger.isValid());
        assertTrue(ledger.unknowns().stream().noneMatch(unknown -> unknown.id().family()
                == V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW && unknown.id().node() == 0));
        assertTrue(ledger.equations().stream().noneMatch(equation -> equation.id().family()
                == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM && equation.id().node() == 0));
        assertTrue(ledger.equations().stream().flatMap(equation -> equation.referencedUnknowns().stream())
                .noneMatch(unknown -> unknown.family() == V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW
                        && unknown.node() == 0));
    }

    @Test
    void duplicateAndMissingControlsAreIndependentContractFailures() {
        V3ColumnTopology topology = V3ColumnTopology.twoPhase(4, 2);
        V3DegreeOfFreedomLedger duplicate = V3DegreeOfFreedomLedger.create(topology, 2, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
        V3DegreeOfFreedomLedger missing = V3DegreeOfFreedomLedger.create(topology, 2, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));

        assertFalse(duplicate.isValid());
        assertTrue(duplicate.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("DUPLICATE_CONTROL")));
        assertFalse(missing.isValid());
        assertTrue(missing.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MISSING_CONTROL")));
    }

    @Test
    void vaporOnlyCandidateRejectsPositiveRefluxInsteadOfCreatingDummyLiquidVariables() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.resolve(input(0.25, standardSpecifications(0.25)),
                        V3CondenserPhaseBranch.VAPOR_ONLY));

        assertTrue(exception.getMessage().contains("VAPOR_ONLY_WITH_POSITIVE_REFLUX"));
    }

    private static V3ColumnInput input(double refluxRatio, List<V3ColumnSpecification> specifications) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary", "test:baseline",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0,
                4, 2, 250_000.0, 750.0, specifications);
    }

    private static List<V3ColumnSpecification> standardSpecifications(double refluxRatio) {
        return List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(refluxRatio),
                new V3ColumnSpecification.ReboilerDuty(8_000_000.0));
    }
}
