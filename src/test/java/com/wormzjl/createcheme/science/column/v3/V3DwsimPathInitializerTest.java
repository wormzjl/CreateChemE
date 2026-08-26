package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Cold comparison of DWSIM-inspired V3 seed candidates; none of these paths is a published result. */
class V3DwsimPathInitializerTest {
    private static final long SOLVE_BUDGET_NANOS = 5_000_000_000L;

    @Test
    void realCrudeSeedCandidatesExposeTheirResidualAndCorrectionBehavior() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnInput input = v1ScaleInput(crude, 2, 1);
        StringBuilder report = new StringBuilder("V3 DWSIM-path seed comparison\n");

        for (V3ColumnInitializer.Mode mode : V3ColumnInitializer.Mode.values()) {
            Probe probe = probe(thermo, input, mode);
            report.append(mode).append(" seed=").append(probe.seedPolicy())
                    .append(" initial(material=").append(probe.materialResidual())
                    .append(",equilibrium=").append(probe.equilibriumResidual())
                    .append(",energy=").append(probe.energyResidual()).append(") ")
                    .append(probe.termination()).append(" final(material=").append(probe.finalMaterialResidual())
                    .append(",equilibrium=").append(probe.finalEquilibriumResidual())
                    .append(",energy=").append(probe.finalEnergyResidual()).append(") facade=")
                    .append(facadeProbe(input, mode)).append('\n');
        }

        System.out.println(report);
        assertTrue(report.length() > 80);
    }

    private static Probe probe(
            V3PengRobinsonThermo thermo, V3ColumnInput input, V3ColumnInitializer.Mode mode) {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(), mode);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3MeshResidual residual = evaluator.evaluate(seed.state(), thermo.newWorkspace());
        long started = System.nanoTime();
        try {
            V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                    problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
                    V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS, V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE,
                    () -> {
                        if (System.nanoTime() - started >= SOLVE_BUDGET_NANOS) {
                            throw new CancellationException("cold candidate budget exceeded");
                        }
                    });
            V3MeshResidual finalResidual = evaluator.evaluate(attempt.state(), thermo.newWorkspace());
            return new Probe(seed.evidence().trafficPolicy(), maximum(residual,
                    V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE), maximum(residual,
                    V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM), maximum(residual,
                    V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE), attempt.evidence().termination()
                    + " iter=" + attempt.evidence().iterations() + " residual=" + attempt.evidence().maximumScaledResidual(),
                    maximum(finalResidual, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE),
                    maximum(finalResidual, V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM),
                    maximum(finalResidual, V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE));
        } catch (CancellationException expected) {
            return new Probe(seed.evidence().trafficPolicy(), maximum(residual,
                    V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE), maximum(residual,
                    V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM), maximum(residual,
                    V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE), "BUDGET_EXCEEDED", Double.NaN, Double.NaN,
                    Double.NaN);
        }
    }

    private static String facadeProbe(V3ColumnInput input, V3ColumnInitializer.Mode mode) {
        long started = System.nanoTime();
        try {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
                if (System.nanoTime() - started >= SOLVE_BUDGET_NANOS) {
                    throw new CancellationException("cold facade candidate budget exceeded");
                }
            }, mode);
            return switch (outcome) {
                case V3ColumnOutcome.Success success -> "SUCCESS iter=" + success.diagnostics().newtonIterations()
                        + " residual=" + success.diagnostics().maximumScaledResidual();
                case V3ColumnOutcome.Failure failure -> failure.code() + " iter="
                        + failure.diagnostics().newtonIterations() + " residual="
                        + failure.diagnostics().maximumScaledResidual();
            };
        } catch (CancellationException expected) {
            return "BUDGET_EXCEEDED";
        }
    }

    private static double maximum(V3MeshResidual residual, V3DegreeOfFreedomLedger.EquationFamily family) {
        return residual.rows().stream().filter(row -> row.equation().family() == family)
                .mapToDouble(row -> Math.abs(row.scaledValue())).max().orElseThrow();
    }

    private static V3ColumnInput v1ScaleInput(V3CrudeFeed crude, int stages, int feedStage) {
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 365.0 + 273.15, stages, feedStage, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private record Probe(
            String seedPolicy,
            double materialResidual,
            double equilibriumResidual,
            double energyResidual,
            String termination,
            double finalMaterialResidual,
            double finalEquilibriumResidual,
            double finalEnergyResidual) {}
}
