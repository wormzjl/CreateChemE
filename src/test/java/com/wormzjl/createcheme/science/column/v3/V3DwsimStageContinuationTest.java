package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Qualifies deterministic internal stage continuation without using a persisted or cross-request warm state. */
class V3DwsimStageContinuationTest {
    private static final long TARGET_BUDGET_NANOS = 15_000_000_000L;
    private static final long FIFTEEN_STAGE_BUDGET_NANOS = 30_000_000_000L;
    private static final long THIRTY_STAGE_BUDGET_NANOS = 60_000_000_000L;

    @Test
    void acceptedFourStageRealCrudeProfileCanSeedAFreshEightStageCorrection() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem fourStage = V3ColumnProblemResolver.resolve(input(crude, 4, 3), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged acceptedFourStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, fourStage,
                        V3ColumnInitializer.initialize(fourStage, thermo, thermo.newWorkspace(),
                                V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state(), V3SolveControl.UNBOUNDED));
        V3FlashResult fourFeedFlash = feedFlash(thermo, fourStage);
        assertTrue(new V3AcceptanceAuditor(fourStage, thermo, fourFeedFlash.molarEnthalpyJoulesPerMol())
                .audit(acceptedFourStage.state(), thermo.newWorkspace()).accepted());

        V3ColumnProblem eightStage = V3ColumnProblemResolver.resolve(input(crude, 8, 6), V3CondenserPhaseBranch.TWO_PHASE);
        V3DryMeshState continuationSeed = interpolate(acceptedFourStage.state(), eightStage);
        long started = System.nanoTime();
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, eightStage, continuationSeed, () -> {
                    if (System.nanoTime() - started >= TARGET_BUDGET_NANOS) {
                        throw new AssertionError("eight-stage continuation exceeded its 15-second cold-test budget");
                    }
                }));
        V3FlashResult eightFeedFlash = feedFlash(thermo, eightStage);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(eightStage, thermo,
                eightFeedFlash.molarEnthalpyJoulesPerMol()).audit(converged.state(), thermo.newWorkspace());
        System.out.println("V3 DWSIM continuation 4->8: " + converged + "; audit=" + audit);
        assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(audit.accepted());
    }

    @Test
    void certifiedEightStageRealCrudeProfileCanSeedAFreshFifteenStageCorrection() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem fourStage = V3ColumnProblemResolver.resolve(input(crude, 4, 3), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFourStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, fourStage,
                        V3ColumnInitializer.initialize(fourStage, thermo, thermo.newWorkspace(),
                                V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state(), V3SolveControl.UNBOUNDED));

        V3ColumnProblem eightStage = V3ColumnProblemResolver.resolve(input(crude, 8, 6), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedEightStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, eightStage, interpolate(convergedFourStage.state(), eightStage), TARGET_BUDGET_NANOS));

        V3ColumnProblem fifteenStage = V3ColumnProblemResolver.resolve(input(crude, 15, 12), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFifteenStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, fifteenStage, interpolate(convergedEightStage.state(), fifteenStage),
                        FIFTEEN_STAGE_BUDGET_NANOS));
        V3FlashResult fifteenFeedFlash = feedFlash(thermo, fifteenStage);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fifteenStage, thermo,
                fifteenFeedFlash.molarEnthalpyJoulesPerMol()).audit(convergedFifteenStage.state(), thermo.newWorkspace());
        System.out.println("V3 DWSIM continuation 4->8->15: " + convergedFifteenStage + "; audit=" + audit);
        assertTrue(convergedFifteenStage.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(audit.accepted());
    }

    @Test
    void certifiedFifteenStageRealCrudeProfileCanSeedAFreshThirtyStageCorrection() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem fourStage = V3ColumnProblemResolver.resolve(input(crude, 4, 3), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFourStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, solve(thermo, fourStage,
                        V3ColumnInitializer.initialize(fourStage, thermo, thermo.newWorkspace(),
                                V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state(), V3SolveControl.UNBOUNDED));
        V3ColumnProblem eightStage = V3ColumnProblemResolver.resolve(input(crude, 8, 6), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedEightStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, eightStage, interpolate(convergedFourStage.state(), eightStage), TARGET_BUDGET_NANOS));
        V3ColumnProblem fifteenStage = V3ColumnProblemResolver.resolve(input(crude, 15, 12), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedFifteenStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, fifteenStage, interpolate(convergedEightStage.state(), fifteenStage),
                        FIFTEEN_STAGE_BUDGET_NANOS));

        V3ColumnProblem thirtyStage = V3ColumnProblemResolver.resolve(input(crude, 30, 24), V3CondenserPhaseBranch.TWO_PHASE);
        V3SimultaneousColumnSolver.Attempt.Converged convergedThirtyStage = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithin(thermo, thirtyStage, interpolate(convergedFifteenStage.state(), thirtyStage),
                        THIRTY_STAGE_BUDGET_NANOS));
        V3FlashResult thirtyFeedFlash = feedFlash(thermo, thirtyStage);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(thirtyStage, thermo,
                thirtyFeedFlash.molarEnthalpyJoulesPerMol()).audit(convergedThirtyStage.state(), thermo.newWorkspace());
        System.out.println("V3 DWSIM continuation 4->8->15->30: " + convergedThirtyStage + "; audit=" + audit);
        assertTrue(convergedThirtyStage.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(audit.accepted());
    }

    private static V3SimultaneousColumnSolver.Attempt solve(
            V3PengRobinsonThermo thermo, V3ColumnProblem problem, V3DryMeshState seed, V3SolveControl control) {
        V3FlashResult feedFlash = feedFlash(thermo, problem);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        return V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, new V3DryMeshCoordinateMap(problem), seed,
                thermo::newWorkspace, V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS,
                V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE, control);
    }

    private static V3SimultaneousColumnSolver.Attempt solveWithin(
            V3PengRobinsonThermo thermo, V3ColumnProblem problem, V3DryMeshState seed, long budgetNanos) {
        long started = System.nanoTime();
        return solve(thermo, problem, seed, () -> {
            if (System.nanoTime() - started >= budgetNanos) {
                throw new AssertionError("DWSIM stage continuation exceeded its cold-test budget");
            }
        });
    }

    private static V3FlashResult feedFlash(V3PengRobinsonThermo thermo, V3ColumnProblem problem) {
        return thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
    }

    private static V3DryMeshState interpolate(V3DryMeshState source, V3ColumnProblem target) {
        int nodes = target.topology().nodeCount();
        int components = source.componentCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            double position = node * (source.nodeCount() - 1.0) / (nodes - 1.0);
            int lower = (int) Math.floor(position);
            int upper = Math.min(source.nodeCount() - 1, lower + 1);
            double fraction = position - lower;
            temperatures[node] = source.temperatureKelvin(lower)
                    + fraction * (source.temperatureKelvin(upper) - source.temperatureKelvin(lower));
            for (int component = 0; component < components; component++) {
                liquid[node][component] = source.liquidFlow(lower, component)
                        + fraction * (source.liquidFlow(upper, component) - source.liquidFlow(lower, component));
                vapor[node][component] = source.vaporFlow(lower, component)
                        + fraction * (source.vaporFlow(upper, component) - source.vaporFlow(lower, component));
            }
        }
        temperatures[target.topology().condenserNode()] = specification(
                target.input(), V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        V3DryMeshState interpolated = new V3DryMeshState(target.topology(), components, liquid, vapor, temperatures);
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(target.input().packageId());
        V3SequentialPreconditioner.Result prepared = V3BubblePointPreconditioner.INSTANCE.prepare(
                new V3SequentialPreconditioner.Request(target, interpolated, V3SolveControl.UNBOUNDED),
                thermo, thermo.newWorkspace());
        return prepared instanceof V3SequentialPreconditioner.Result.Prepared result ? result.state() : interpolated;
    }

    private static <S extends V3ColumnSpecification> S specification(V3ColumnInput input, Class<S> type) {
        return input.specifications().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static V3ColumnInput input(V3CrudeFeed crude, int stages, int feedStage) {
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 638.15, stages, feedStage, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }
}
