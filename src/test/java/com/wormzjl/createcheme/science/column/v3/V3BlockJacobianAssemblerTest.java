package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3BlockJacobianAssemblerTest {
    @Test
    void stageBlocksExactlyProjectTheWholeSystemFiniteDifferenceJacobianAndHaveNoOffBandCoupling() {
        V3ColumnProblem problem = problem();
        SmoothThermo thermo = new SmoothThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState state = state(problem.topology());

        V3FiniteDifferenceJacobian.Jacobian full = V3FiniteDifferenceJacobian.evaluate(
                evaluator, coordinates, state, thermo::newWorkspace);
        V3BlockJacobian blocks = V3BlockJacobianAssembler.assemble(
                problem, evaluator, coordinates, state, thermo::newWorkspace);

        assertEquals(6, blocks.layout().nodeCount());
        assertEquals(4, blocks.layout().size(0));
        assertEquals(5, blocks.layout().size(1));
        assertTrue(blocks.maximumOffBandMagnitude() <= 1.0e-10);
        for (int node = 0; node < blocks.layout().nodeCount(); node++) {
            assertBlockEquals(full.values(), blocks.layout(), node, node, blocks.diagonal(node));
            if (node > 0) assertBlockEquals(full.values(), blocks.layout(), node, node - 1, blocks.lower(node));
            else assertEquals(0, blocks.lower(node)[0].length);
            if (node + 1 < blocks.layout().nodeCount()) assertBlockEquals(full.values(), blocks.layout(), node, node + 1, blocks.upper(node));
            else assertEquals(0, blocks.upper(node)[0].length);
        }
    }

    private static void assertBlockEquals(
            double[][] full, V3StageBlockLayout layout, int rowNode, int columnNode, double[][] block) {
        assertEquals(layout.size(rowNode), block.length);
        assertEquals(layout.size(columnNode), block[0].length);
        for (int row = 0; row < block.length; row++) {
            for (int column = 0; column < block[row].length; column++) {
                assertEquals(full[layout.start(rowNode) + row][layout.start(columnNode) + column], block[row][column], 0.0);
            }
        }
    }

    private static V3ColumnProblem problem() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3DryMeshState state(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        }, new double[][] {
                {8.0, 2.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        }, new double[] {400.0, 410.0, 420.0, 430.0, 440.0, 450.0});
    }

    private static final class SmoothThermo implements V3ThermoModel {
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
                for (int component = 0; component < 2; component++) logPhi[component] = Math.log(K[node][component])
                        + (component + 1) * 1.0e-3 * (temperatureKelvin - referenceTemperature);
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
