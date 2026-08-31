package com.wormzjl.createcheme.science.column.v3;

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

class V3MeshResidualEvaluatorTest {
    @Test
    void completeManufacturedDryMeshStateClosesMaterialVleAndEnergyInLedgerOrder() {
        V3ColumnProblem problem = problem();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, new ManufacturedIdealThermo(), 0.0);

        V3MeshResidual residual = evaluator.evaluate(manufacturedState(problem.topology()), evaluatorThermoWorkspace());

        assertEquals(problem.degreeOfFreedomLedger().equationCount(), residual.rows().size());
        assertTrue(residual.maximumAbsoluteScaledResidual() <= 1.0e-12,
                () -> "maximum scaled residual=" + residual.maximumAbsoluteScaledResidual());
        for (int index = 0; index < residual.rows().size(); index++) {
            assertEquals(problem.degreeOfFreedomLedger().equations().get(index).id(), residual.rows().get(index).equation());
        }
        assertEquals(12, residual.rows().stream().filter(row -> row.equation().family()
                == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE).count());
        assertEquals(12, residual.rows().stream().filter(row -> row.equation().family()
                == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM).count());
        assertEquals(5, residual.rows().stream().filter(row -> row.equation().family()
                == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE).count());
    }

    @Test
    void residualsExposeAConservedFlowCorruptionInsteadOfRepairingIt() {
        V3ColumnProblem problem = problem();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, new ManufacturedIdealThermo(), 0.0);
        double[][] liquid = liquidFlows();
        liquid[2][0] += 1.0;
        V3DryMeshState corrupted = new V3DryMeshState(problem.topology(), 2, liquid, vaporFlows(), temperatures());

        V3MeshResidual residual = evaluator.evaluate(corrupted, evaluatorThermoWorkspace());

        assertTrue(residual.maximumAbsoluteScaledResidual() > 1.0e-3);
        assertTrue(residual.rows().stream().anyMatch(row -> row.equation().family()
                == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE && Math.abs(row.physicalValue()) > 0.5));
    }

    @Test
    void affinePhaseEnthalpiesCloseEveryEnergyRowWhenComponentBalancesAreClosed() {
        V3ColumnProblem problem = problem();
        AffineEnthalpyThermo thermo = new AffineEnthalpyThermo();
        double feedEnthalpy = thermo.molarEnthalpy(400.0, problem.nodePressurePascal(2),
                new double[] {1.0 / 3.0, 2.0 / 3.0}, V3Phase.LIQUID, thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feedEnthalpy);
        V3DryMeshState isothermal = new V3DryMeshState(problem.topology(), 2, liquidFlows(), vaporFlows(),
                new double[] {400.0, 400.0, 400.0, 400.0, 400.0, 400.0});

        V3MeshResidual residual = evaluator.evaluate(isothermal, thermo.newWorkspace());

        assertTrue(residual.rows().stream().filter(row -> row.equation().family()
                        == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE)
                .allMatch(row -> Math.abs(row.physicalValue()) <= 1.0e-9));
    }

    @Test
    void evaluatorRejectsThermoBasisAndAbsentPhaseViolationsBeforeProducingResiduals() {
        V3ColumnProblem problem = problem();
        V3ThermoModel wrongBasis = new ManufacturedIdealThermo(new V3ComponentBasis(List.of("other-a", "other-b")));

        assertThrows(IllegalArgumentException.class, () -> new V3MeshResidualEvaluator(problem, wrongBasis, 0.0));
        V3ColumnTopology vaporOnly = V3ColumnTopology.vaporOnly(4, 2);
        double[][] illegalLiquid = liquidFlows();
        assertThrows(IllegalArgumentException.class,
                () -> new V3DryMeshState(vaporOnly, 2, illegalLiquid, vaporFlows(), temperatures()));
        assertThrows(IllegalArgumentException.class,
                () -> new V3DryMeshState(V3ColumnTopology.liquidOnly(4, 2), 2, liquidFlows(), vaporFlows(), temperatures()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 4})
    void sideDrawChangesOnlyTheMaterialAndEnergyRowsBelowItsTray(int tray) {
        V3ColumnProblem plain = problem();
        V3ColumnInput input = plain.input();
        V3ColumnProblem drawn = V3ColumnProblemResolver.resolve(new V3ColumnInput(input.schemaVersion(), input.packageId(),
                input.assayId(), input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), List.of(new V3SideDrawSpec(tray, 4.0))), V3CondenserPhaseBranch.TWO_PHASE);
        AffineEnthalpyThermo thermo = new AffineEnthalpyThermo();
        V3DryMeshState state = manufacturedState(plain.topology());
        V3MeshResidual baseline = new V3MeshResidualEvaluator(plain, thermo, 0).evaluate(state, thermo.newWorkspace());
        V3MeshResidual withDraw = new V3MeshResidualEvaluator(drawn, thermo, 0).evaluate(state, thermo.newWorkspace());
        double[] liquid = liquidFlows()[tray];
        double total = liquid[0] + liquid[1];
        for (int row = 0; row < baseline.rows().size(); row++) {
            var original = baseline.rows().get(row);
            double delta = 0.0;
            if (original.equation().node() == tray + 1) {
                delta = switch (original.equation().family()) {
                    case COMPONENT_MATERIAL_BALANCE -> -4.0 * liquid[original.equation().component()] / total;
                    case ENERGY_BALANCE -> -4.0 * temperatures()[tray] * (30 * liquid[0] + 40 * liquid[1]) / total;
                    default -> 0.0;
                };
            }
            org.junit.jupiter.api.Assertions.assertEquals(original.physicalValue() + delta,
                    withDraw.rows().get(row).physicalValue(), 1e-8);
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

    private static V3DryMeshState manufacturedState(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, liquidFlows(), vaporFlows(), temperatures());
    }

    private static V3ThermoWorkspace evaluatorThermoWorkspace() {
        return new V3ThermoWorkspace(2);
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

    private static final class ManufacturedIdealThermo implements V3ThermoModel {
        private static final double[][] K = {
                {1.6, 0.4}, {1.2, 0.8}, {12.0 / 7.0, 8.0 / 13.0},
                {12.0 / 7.0, 8.0 / 13.0}, {12.0 / 7.0, 8.0 / 13.0}, {42.0 / 17.0, 28.0 / 53.0}
        };
        private final V3ComponentBasis basis;

        private ManufacturedIdealThermo() {
            this(new V3ComponentBasis(List.of("component-a", "component-b")));
        }

        private ManufacturedIdealThermo(V3ComponentBasis basis) {
            this.basis = basis;
        }

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(basis.componentCount()); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            int node = (int) Math.round((temperatureKelvin - 400.0) / 10.0);
            if (node < 0 || node >= K.length || Math.abs(temperatureKelvin - (400.0 + node * 10.0)) > 1.0e-9) {
                throw new IllegalArgumentException("Manufactured VLE test temperature is unknown");
            }
            double[] logPhi = new double[basis.componentCount()];
            if (phase == V3Phase.LIQUID) {
                for (int component = 0; component < logPhi.length; component++) logPhi[component] = Math.log(K[node][component]);
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
            throw new UnsupportedOperationException("The manufactured residual model does not implement a flash");
        }
    }

    private static final class AffineEnthalpyThermo implements V3ThermoModel {
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return new V3FugacityResult(phase, new double[] {0.0, 0.0}, 1.0,
                    molarEnthalpy(temperatureKelvin, pressurePascal, composition, phase, workspace), 1, 0.0);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return temperatureKelvin * (30.0 * composition[0] + 40.0 * composition[1]);
        }

        @Override
        public V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("The affine enthalpy test model does not implement a flash");
        }
    }
}
