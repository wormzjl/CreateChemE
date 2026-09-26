package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Server-runnable, fixed-input form of the scan-verified Holland (1981) Example 3-2 benchmark. */
public final class V3HollandExample32 {
    public static final String PACKAGE_ID = "createcheme:holland_b12";
    public static final String ASSAY_ID = "createcheme:holland_1981_example_3_2";
    public static final String DATASET_REVISION = "holland-1981-example-3-2-scan-r1";
    public static final String FORMULATION_REVISION = "v3-holland-example-3-2-r1";
    private static final double SPLIT_RATIO = 1.0e4;
    private static final int MAXIMUM_ORACLE_ITERATIONS = 12;
    private static final int MAXIMUM_V3_ITERATIONS = 64;
    private static final double ORACLE_TOLERANCE = 1.0e-10;
    private static final double V3_TOLERANCE = 1.0e-8;
    private static final double TEMPERATURE_ORACLE_LIMIT_KELVIN = 1.0e-6;
    private static final double FLOW_ORACLE_RELATIVE_LIMIT = 1.0e-7;
    private static final double PUBLISHED_TEMPERATURE_LIMIT_KELVIN = 0.3;
    private static final double PUBLISHED_CONDENSER_DUTY_RELATIVE_LIMIT = 0.01;
    private static final double[] MOLECULAR_WEIGHTS_KG_PER_MOL = {
            0.016043, 0.030070, 0.042081, 0.044097, 0.058124, 0.058124,
            0.072151, 0.086178, 0.100205, 0.114232, 0.400000
    };

    private V3HollandExample32() {}

    /** The immutable literature input exposed by the in-game V3 benchmark preset. */
    public static V3ColumnInput input() {
        HollandExample32Data data = HollandExample32Data.INSTANCE;
        HollandB12Thermo thermo = new HollandB12Thermo(data);
        double feedTemperature = thermo.bubblePointTemperatureKelvin(
                data.feedLbMolPerHour(), data.pressurePascal());
        return new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, PACKAGE_ID, ASSAY_ID, data.basis(), data.feedMolPerSecond(),
                feedTemperature, 11, data.feedTray(), data.pressurePascal(), 0.0,
                List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(
                                HollandExample32Data.kelvinFromFahrenheit(
                                        data.solution().temperatureFahrenheit()[0])),
                        new V3ColumnSpecification.OrganicRefluxRatio(SPLIT_RATIO),
                        new V3ColumnSpecification.ReboilerDuty(
                                data.solution().reboilerDutyBtuPerHour()
                                        * HollandExample32Data.BTU_PER_HOUR_TO_WATT)),
                List.of(new V3SideDrawSpec(data.sideDrawTray(), data.sideDrawMolPerSecond())));
    }

    public static boolean isPackage(String packageId) {
        return PACKAGE_ID.equals(packageId);
    }

    /** Prevents an edited draft from being mistaken for the fixed, independently seeded literature benchmark. */
    public static void validateInput(V3ColumnInput input) {
        input = Objects.requireNonNull(input, "input");
        if (!input.equals(input())) {
            throw new IllegalArgumentException(
                    "Holland Example 3-2 is a fixed benchmark preset; reload the preset before running it");
        }
        V3ColumnProblemResolver.validateInput(input);
    }

    /** Runs the independent oracle, a deterministic perturbation, V3 Newton correction, and the fresh V3 audit. */
    public static V3ColumnOutcome calculate(V3ColumnInput input, V3SolveControl control) {
        control = Objects.requireNonNull(control, "control");
        try {
            validateInput(input);
            control.checkpoint();
            HollandExample32Data data = HollandExample32Data.INSTANCE;
            IndependentHollandMeshOracle oracle = new IndependentHollandMeshOracle(data, SPLIT_RATIO);
            IndependentHollandMeshOracle.SolveResult independent =
                    oracle.solve(MAXIMUM_ORACLE_ITERATIONS, ORACLE_TOLERANCE, control);

            HollandB12Thermo thermo = new HollandB12Thermo(data);
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
            V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(), data.pressurePascal(),
                    input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                    problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
            V3DryMeshState seed = perturbedV3State(problem, independent.state());
            V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                    problem, evaluator, new V3DryMeshCoordinateMap(problem), seed, thermo::newWorkspace,
                    MAXIMUM_V3_ITERATIONS, V3_TOLERANCE, control);
            control.checkpoint();

            V3AcceptanceAudit baseAudit = new V3AcceptanceAuditor(
                    problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                    .audit(attempt.state(), thermo.newWorkspace(), control);
            Comparison comparison = compare(data, problem, thermo, attempt.state(), independent.state());
            V3AcceptanceAudit audit = benchmarkAudit(baseAudit, comparison);
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, independent.iterations(), comparison);
            if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged)) {
                return new V3ColumnOutcome.Failure(V3SolverFailureCode.NONCONVERGENCE,
                        "Holland V3 correction did not converge: " + attempt.evidence().termination(), diagnostics);
            }
            if (!audit.accepted() || !converged.evidence().convergenceEvidence().satisfiesGates()) {
                return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                        "Holland benchmark failed its independent V3 acceptance checks", diagnostics);
            }
            V3InputDigest digest = V3InputDigest.of(
                    problem, FORMULATION_REVISION, DATASET_REVISION, V3ColumnCalculator.ASSUMPTIONS_REVISION);
            V3ColumnResult result = V3ColumnResult.accepted(
                    problem, digest, audit, converged.evidence().convergenceEvidence(), converged.state(),
                    MOLECULAR_WEIGHTS_KG_PER_MOL, FORMULATION_REVISION);
            return new V3ColumnOutcome.Success(result, diagnostics);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IllegalArgumentException invalid) {
            return failure(V3SolverFailureCode.INVALID_INPUT, bounded(invalid.getMessage()));
        } catch (RuntimeException failure) {
            return failure(V3SolverFailureCode.INTERNAL_ERROR, bounded(failure.getMessage()));
        }
    }

    private static V3DryMeshState perturbedV3State(
            V3ColumnProblem problem, IndependentHollandMeshOracle.State source) {
        int nodes = source.temperaturesFahrenheit().length;
        int components = source.liquidComponentFlows()[0].length;
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            temperatures[node] = HollandExample32Data.kelvinFromFahrenheit(
                    source.temperaturesFahrenheit()[node]);
            if (node > 0) temperatures[node] += node % 2 == 0 ? 0.15 : -0.15;
            for (int component = 0; component < components; component++) {
                liquid[node][component] = source.liquidComponentFlows()[node][component]
                        * HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND
                        * Math.exp((((node * 31 + component * 17) % 7) - 3) * 0.002);
                vapor[node][component] = source.vaporComponentFlows()[node][component]
                        * HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND
                        * Math.exp((((node * 19 + component * 23) % 7) - 3) * 0.002);
            }
        }
        return new V3DryMeshState(problem.topology(), components, liquid, vapor, temperatures);
    }

    private static Comparison compare(
            HollandExample32Data data, V3ColumnProblem problem, HollandB12Thermo thermo,
            V3DryMeshState actual, IndependentHollandMeshOracle.State independent) {
        double maximumTemperatureDelta = 0.0;
        double maximumFlowDelta = 0.0;
        double maximumPublishedTemperatureDelta = 0.0;
        for (int node = 0; node < actual.nodeCount(); node++) {
            maximumTemperatureDelta = Math.max(maximumTemperatureDelta, Math.abs(
                    actual.temperatureKelvin(node) - HollandExample32Data.kelvinFromFahrenheit(
                            independent.temperaturesFahrenheit()[node])));
            maximumPublishedTemperatureDelta = Math.max(maximumPublishedTemperatureDelta, Math.abs(
                    actual.temperatureKelvin(node) - HollandExample32Data.kelvinFromFahrenheit(
                            data.solution().temperatureFahrenheit()[node])));
            for (int component = 0; component < actual.componentCount(); component++) {
                maximumFlowDelta = Math.max(maximumFlowDelta, relativeError(
                        actual.liquidFlow(node, component)
                                / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND,
                        independent.liquidComponentFlows()[node][component]));
                maximumFlowDelta = Math.max(maximumFlowDelta, relativeError(
                        actual.vaporFlow(node, component)
                                / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND,
                        independent.vaporComponentFlows()[node][component]));
            }
        }
        double condenserDuty = condenserDutyBtuPerHour(problem, thermo, actual);
        double condenserDutyError = relativeError(
                condenserDuty, data.solution().condenserDutyBtuPerHour());
        return new Comparison(maximumTemperatureDelta, maximumFlowDelta,
                maximumPublishedTemperatureDelta, condenserDutyError);
    }

    private static V3AcceptanceAudit benchmarkAudit(V3AcceptanceAudit base, Comparison comparison) {
        List<V3AcceptanceAudit.Check> checks = new ArrayList<>(base.checks());
        checks.add(check("HOLLAND_INDEPENDENT_TEMPERATURE", comparison.oracleTemperatureDeltaKelvin(),
                TEMPERATURE_ORACLE_LIMIT_KELVIN, "maximum V3-to-independent temperature difference"));
        checks.add(check("HOLLAND_INDEPENDENT_FLOW", comparison.oracleRelativeFlowDelta(),
                FLOW_ORACLE_RELATIVE_LIMIT, "maximum V3-to-independent relative component-flow difference"));
        checks.add(check("HOLLAND_PUBLISHED_TEMPERATURE", comparison.publishedTemperatureDeltaKelvin(),
                PUBLISHED_TEMPERATURE_LIMIT_KELVIN, "maximum difference from the reconciled printed profile"));
        checks.add(check("HOLLAND_PUBLISHED_CONDENSER_DUTY", comparison.condenserDutyRelativeError(),
                PUBLISHED_CONDENSER_DUTY_RELATIVE_LIMIT, "relative difference from the printed condenser duty"));
        List<String> advisory = new ArrayList<>(base.advisoryEvidence());
        advisory.add("HOLLAND_SOURCE_TABLE_CONFLICTS: seven strict printed-number comparisons are inconsistent");
        advisory.add("HOLLAND_COLD_START_LIMITATION: this benchmark uses the independent near-root initializer");
        return new V3AcceptanceAudit(checks, advisory);
    }

    private static V3AcceptanceAudit.Check check(String family, double value, double limit, String detail) {
        return value <= limit ? V3AcceptanceAudit.Check.pass(family, value, limit, detail)
                : V3AcceptanceAudit.Check.fail(family, value, limit, detail);
    }

    private static V3SolverDiagnostics diagnostics(
            V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit,
            int oracleIterations, Comparison comparison) {
        V3SimultaneousColumnSolver.Evidence evidence = attempt.evidence();
        V3ConvergenceEvidence convergence = evidence.convergenceEvidence();
        double finalStepNorm = Math.max(
                convergence.maximumLogFlowChange(), convergence.maximumTemperatureChangeKelvin());
        List<String> events = List.of(
                "independent Holland oracle iterations=" + oracleIterations,
                "V3/oracle max temperature delta K=" + comparison.oracleTemperatureDeltaKelvin(),
                "V3/oracle max relative flow delta=" + comparison.oracleRelativeFlowDelta(),
                "seven known source-table conflicts remain advisory",
                evidence.termination());
        return new V3SolverDiagnostics(oracleIterations, evidence.iterations(), 0, 0,
                evidence.maximumScaledResidual(), finalStepNorm, "holland/oracle-seeded/fine-fd",
                events, audit, convergence);
    }

    private static double condenserDutyBtuPerHour(
            V3ColumnProblem problem, HollandB12Thermo thermo, V3DryMeshState state) {
        double incoming = phaseEnergyWatts(problem, thermo, state, 1, false);
        double liquidOutlet = phaseEnergyWatts(problem, thermo, state, 0, true);
        double vaporOutlet = phaseEnergyWatts(problem, thermo, state, 0, false);
        return (incoming - liquidOutlet - vaporOutlet) / HollandExample32Data.BTU_PER_HOUR_TO_WATT;
    }

    private static double phaseEnergyWatts(
            V3ColumnProblem problem, HollandB12Thermo thermo, V3DryMeshState state,
            int node, boolean liquid) {
        double[] composition = new double[state.componentCount()];
        double total = 0.0;
        for (int component = 0; component < composition.length; component++) {
            composition[component] = liquid
                    ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            total += composition[component];
        }
        for (int component = 0; component < composition.length; component++) composition[component] /= total;
        return total * thermo.molarEnthalpy(state.temperatureKelvin(node), problem.nodePressurePascal(node),
                composition, liquid ? V3Phase.LIQUID : V3Phase.VAPOR, thermo.newWorkspace());
    }

    private static V3ColumnOutcome.Failure failure(V3SolverFailureCode code, String summary) {
        V3AcceptanceAudit audit = new V3AcceptanceAudit(List.of(
                V3AcceptanceAudit.Check.fail("HOLLAND_BENCHMARK", 1.0, 0.0, summary)));
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(
                0, 0, 0, 0, 0.0, 0.0, "holland/admission", List.of(summary), audit,
                V3ConvergenceEvidence.unavailable());
        return new V3ColumnOutcome.Failure(code, summary, diagnostics);
    }

    private static double relativeError(double actual, double expected) {
        return Math.abs(actual - expected) / Math.max(Math.abs(expected), 1.0e-300);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "Holland benchmark failed without a detail";
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private record Comparison(
            double oracleTemperatureDeltaKelvin,
            double oracleRelativeFlowDelta,
            double publishedTemperatureDeltaKelvin,
            double condenserDutyRelativeError) {}
}
