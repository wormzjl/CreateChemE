package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3DryMeshCoordinateMapTest {
    @Test
    void ledgerOrderedLogCoordinatesRoundTripAllActiveFlowsAndTemperaturesWithoutAFloor() {
        V3ColumnProblem problem = problem();
        V3DryMeshCoordinateMap map = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState state = state(problem.topology());

        double[] coordinates = map.encode(state);
        V3DryMeshState decoded = map.decode(coordinates);

        assertEquals(29, map.coordinateCount());
        for (int node = 0; node < state.nodeCount(); node++) {
            for (int component = 0; component < state.componentCount(); component++) {
                assertEquals(state.liquidFlow(node, component), decoded.liquidFlow(node, component), 1.0e-12);
                assertEquals(state.vaporFlow(node, component), decoded.vaporFlow(node, component), 1.0e-12);
            }
            assertEquals(state.temperatureKelvin(node), decoded.temperatureKelvin(node), 1.0e-12);
        }
    }

    @Test
    void coordinateMapRejectsZeroActiveFlowRatherThanAddingABalanceFloor() {
        V3ColumnProblem problem = problem();
        double[][] liquid = liquidFlows();
        liquid[0][0] = 0.0;
        V3DryMeshState zeroActiveFlow = new V3DryMeshState(problem.topology(), 2, liquid, vaporFlows(), temperatures());

        assertThrows(IllegalArgumentException.class, () -> new V3DryMeshCoordinateMap(problem).encode(zeroActiveFlow));
    }

    @Test
    void wholeSystemFiniteDifferenceJacobianIsFiniteAndMatchesAnAnalyticMaterialDerivative() {
        V3ColumnProblem problem = problem();
        SmoothManufacturedThermo thermo = new SmoothManufacturedThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap map = new V3DryMeshCoordinateMap(problem);

        V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluate(
                evaluator, map, state(problem.topology()), thermo::newWorkspace);

        assertEquals(29, jacobian.equations().size());
        assertEquals(29, jacobian.unknowns().size());
        for (double[] row : jacobian.values()) {
            assertEquals(29, row.length);
            for (double value : row) assertTrue(Double.isFinite(value));
        }
        int condenserMaterial = jacobian.equations().indexOf(new V3DegreeOfFreedomLedger.EquationId(
                V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, 0, 0));
        int trayOneVapor = jacobian.unknowns().indexOf(new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 1, 0));
        assertEquals(18.0 / 30.0, jacobian.values()[condenserMaterial][trayOneVapor], 1.0e-6);
    }

    private static V3ColumnProblem problem() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(Double.MIN_NORMAL)));
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3DryMeshState state(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, liquidFlows(), vaporFlows(), temperatures());
    }

    private static double[][] liquidFlows() {
        return new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        };
    }

    private static double[][] vaporFlows() {
        return new double[][] {
                {8.0, 2.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        };
    }

    private static double[] temperatures() {
        return new double[] {400.0, 410.0, 420.0, 430.0, 440.0, 450.0};
    }

    private static final class SmoothManufacturedThermo implements V3ThermoModel {
        private static final double[][] K = {
                {1.6, 0.4}, {1.2, 0.8}, {12.0 / 7.0, 8.0 / 13.0},
                {12.0 / 7.0, 8.0 / 13.0}, {12.0 / 7.0, 8.0 / 13.0}, {42.0 / 17.0, 28.0 / 53.0}
        };
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            int node = (int) Math.round((temperatureKelvin - 400.0) / 10.0);
            if (node < 0 || node >= K.length) throw new IllegalArgumentException("Manufactured VLE temperature is outside its grid");
            double[] logPhi = new double[2];
            if (phase == V3Phase.LIQUID) {
                double referenceTemperature = 400.0 + 10.0 * node;
                for (int component = 0; component < 2; component++) {
                    logPhi[component] = Math.log(K[node][component])
                            + (component + 1) * 1.0e-3 * (temperatureKelvin - referenceTemperature);
                }
            }
            return new V3FugacityResult(phase, logPhi, 1.0, 0.0, 1, 0.0);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return 0.0;
        }

        @Override
        public V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("The smooth manufactured model does not implement a flash");
        }
    }
}
