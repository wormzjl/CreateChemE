package com.wormzjl.createcheme.science.column.v3.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IndependentIdealMeshOracleTest {
    @Test
    void manufacturedTwoComponentFourTrayStateClosesEveryFullMeshFamily() {
        IndependentIdealMeshOracle oracle = new IndependentIdealMeshOracle(
                IndependentIdealMeshOracle.manufacturedFourTrayProblem());
        IndependentIdealMeshOracle.Evaluation evaluation = oracle.evaluate(
                oracle.coordinatesOf(IndependentIdealMeshOracle.manufacturedFourTrayState()));

        assertEquals(29, oracle.coordinateCount());
        assertEquals(29, evaluation.rawResiduals().length);
        assertEquals(12, Arrays.stream(evaluation.rowFamilies())
                .filter(family -> family == IndependentIdealMeshOracle.RowFamily.COMPONENT_MATERIAL_BALANCE).count());
        assertEquals(12, Arrays.stream(evaluation.rowFamilies())
                .filter(family -> family == IndependentIdealMeshOracle.RowFamily.VAPOR_LIQUID_EQUILIBRIUM).count());
        assertEquals(5, Arrays.stream(evaluation.rowFamilies())
                .filter(family -> family == IndependentIdealMeshOracle.RowFamily.ENERGY_BALANCE).count());
        assertTrue(evaluation.maximumAbsoluteScaledResidual() <= 1.0e-12,
                () -> "maximum scaled residual=" + evaluation.maximumAbsoluteScaledResidual());
        assertTrue(maximumAbsolute(evaluation.rawResiduals()) <= 1.0e-9,
                () -> "maximum physical residual=" + maximumAbsolute(evaluation.rawResiduals()));
    }

    @Test
    void finiteDifferenceJacobianDoesNotMutateCoordinatesAndPerturbedColdPathConverges() {
        IndependentIdealMeshOracle oracle = new IndependentIdealMeshOracle(
                IndependentIdealMeshOracle.manufacturedFourTrayProblem());
        double[] perturbed = IndependentIdealMeshOracle.deliberatelyPerturbedCoordinates(oracle);
        double[] preserved = perturbed.clone();

        double[][] jacobian = oracle.finiteDifferenceJacobian(perturbed);
        IndependentIdealMeshOracle.SolveResult result = oracle.solve(perturbed, 24, 1.0e-11);

        assertArrayEquals(preserved, perturbed);
        assertEquals(29, jacobian.length);
        for (double[] row : jacobian) {
            assertEquals(29, row.length);
            assertTrue(Arrays.stream(row).allMatch(Double::isFinite));
        }
        assertTrue(result.iterations() > 0);
        assertTrue(result.evaluation().maximumAbsoluteScaledResidual() <= 1.0e-11,
                () -> "maximum scaled residual=" + result.evaluation().maximumAbsoluteScaledResidual());
        assertStatesClose(IndependentIdealMeshOracle.manufacturedFourTrayState(), result.state(), 1.0e-6);
    }

    @Test
    void fixtureResourceRecordsItsManufacturedAuthorityAndNoDwsimClaim() throws IOException {
        try (var stream = getClass().getResourceAsStream("/column/v3/manufactured-two-component-four-tray.json")) {
            assertTrue(stream != null);
            String fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fixture.contains("MANUFACTURED_IDEAL_MESH"));
            assertTrue(fixture.contains("\"stageCount\": 4"));
            assertTrue(fixture.contains("it is not a DWSIM fixture"));
        }
    }

    private static void assertStatesClose(
            IndependentIdealMeshOracle.State expected, IndependentIdealMeshOracle.State actual, double tolerance) {
        for (int node = 0; node < expected.liquidComponentFlows().length; node++) {
            assertArrayEquals(expected.liquidComponentFlows()[node], actual.liquidComponentFlows()[node], tolerance);
            assertArrayEquals(expected.vaporComponentFlows()[node], actual.vaporComponentFlows()[node], tolerance);
        }
        assertArrayEquals(expected.temperaturesKelvin(), actual.temperaturesKelvin(), tolerance);
    }

    private static double maximumAbsolute(double[] values) {
        double maximum = 0.0;
        for (double value : values) maximum = Math.max(maximum, Math.abs(value));
        return maximum;
    }
}
