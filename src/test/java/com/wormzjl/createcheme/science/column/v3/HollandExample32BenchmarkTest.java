package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HollandExample32BenchmarkTest {
    private static final double[] CONDENSER_SPLIT_RATIOS = {1.0e3, 1.0e4, 1.0e5};
    private static final double FLOW_RELATIVE_TOLERANCE = 0.005;
    private static final double TEMPERATURE_TOLERANCE_KELVIN = 0.3;
    private static final double MAJOR_PRODUCT_RELATIVE_TOLERANCE = 0.01;
    private static final double TRACE_PRODUCT_LOG10_TOLERANCE = 0.2;
    private static final double REFLUX_RATIO_ABSOLUTE_TOLERANCE = 0.002;
    private static final double CONDENSER_DUTY_RELATIVE_TOLERANCE = 0.01;

    @Test
    void inGamePresetRunsTheOracleSeededV3PathAndPublishesStreams() {
        V3ColumnInput input = V3HollandExample32.input();
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3HollandExample32.calculate(input, V3SolveControl.UNBOUNDED));

        assertEquals(V3HollandExample32.PACKAGE_ID, success.result().problem().input().packageId());
        assertEquals(V3HollandExample32.FORMULATION_REVISION, success.result().formulationRevision());
        assertEquals(4, success.result().streams().size());
        assertTrue(success.diagnostics().events().stream()
                .anyMatch(event -> event.contains("seven known source-table conflicts")));
        assertTrue(success.result().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("HOLLAND_INDEPENDENT_FLOW") && check.passed()));
    }

    @Test
    void solverMatchesIndependentOracleAndReportsPublishedTableConflicts() throws IOException {
        HollandExample32Data data = HollandExample32Data.INSTANCE;
        assertEquals(List.of(95, 99, 100, 596, 597), Arrays.stream(data.source().verifiedPrintedPages()).boxed().toList());
        assertEquals(61.302, data.solution().reportedTable3LiquidLbMolPerHour()[1]);
        assertEquals(71.302, data.solution().v3LiquidStateLbMolPerHour()[1]);
        assertEquals(456.41, data.solution().reportedTable3TemperatureFahrenheit()[12]);
        assertEquals(446.41, data.solution().temperatureFahrenheit()[12]);

        List<CaseResult> cases = new ArrayList<>();
        for (double splitRatio : CONDENSER_SPLIT_RATIOS) cases.add(solveCase(data, splitRatio));
        CaseResult reference = cases.get(1);
        Accuracy accuracy = compareWithPublished(data, reference);
        List<Sensitivity> sensitivity = cases.stream().map(candidate -> sensitivity(reference, candidate)).toList();

        assertTrue(reference.audit().accepted(), reference.audit()::toString);
        assertTrue(reference.converged().evidence().convergenceEvidence().satisfiesGates());
        assertTrue(reference.converged().evidence().iterations() > 0);
        assertTrue(reference.independent().maximumScaledResidual() <= 1.0e-10);
        assertTrue(reference.maximumV3ToIndependentTemperatureDeltaKelvin() <= 1.0e-6);
        assertTrue(reference.maximumV3ToIndependentRelativeFlowDelta() <= 1.0e-7);
        assertTrue(accuracy.maximumTemperatureDeltaKelvin() <= TEMPERATURE_TOLERANCE_KELVIN);
        assertTrue(accuracy.condenserDutyRelativeError() <= CONDENSER_DUTY_RELATIVE_TOLERANCE);
        assertFalse(accuracy.strictPublishedAccuracyAccepted(),
                "The scan's mutually inconsistent totals must not be reported as a strict accuracy pass");
        assertTrue(accuracy.failures().stream().anyMatch(failure -> failure.startsWith("liquid node 11")));
        assertTrue(accuracy.failures().stream().anyMatch(failure -> failure.startsWith("vapor node 12")));
        for (Sensitivity comparison : sensitivity) {
            assertTrue(comparison.maximumTemperatureDeltaKelvin() <= 0.1, comparison::toString);
            assertTrue(comparison.maximumProductRelativeDelta() <= 0.005, comparison::toString);
        }

        writeReport(data, cases, sensitivity, accuracy);
    }

    private static CaseResult solveCase(HollandExample32Data data, double splitRatio) {
        IndependentHollandMeshOracle oracle = new IndependentHollandMeshOracle(data, splitRatio);
        long oracleStarted = System.nanoTime();
        IndependentHollandMeshOracle.SolveResult independent = oracle.solve(12, 1.0e-10);
        double oracleSeconds = (System.nanoTime() - oracleStarted) / 1.0e9;

        HollandB12Thermo thermo = new HollandB12Thermo(data);
        double feedTemperature = thermo.bubblePointTemperatureKelvin(data.feedLbMolPerHour(), data.pressurePascal());
        assertEquals(HollandExample32Data.kelvinFromFahrenheit(oracle.feedTemperatureFahrenheit()), feedTemperature, 1.0e-10);
        V3ColumnInput input = new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, "test:holland_b12", "test:holland_1981_example_3_2",
                data.basis(), data.feedMolPerSecond(), feedTemperature, 11, data.feedTray(), data.pressurePascal(), 0.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(
                                HollandExample32Data.kelvinFromFahrenheit(data.solution().temperatureFahrenheit()[0])),
                        new V3ColumnSpecification.OrganicRefluxRatio(splitRatio),
                        new V3ColumnSpecification.ReboilerDuty(
                                data.solution().reboilerDutyBtuPerHour() * HollandExample32Data.BTU_PER_HOUR_TO_WATT)),
                List.of(new V3SideDrawSpec(data.sideDrawTray(), data.sideDrawMolPerSecond())));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(feedTemperature, data.pressurePascal(),
                data.feedMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3ColumnInitializer.Seed coldSeed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
        long coldStarted = System.nanoTime();
        V3SimultaneousColumnSolver.Attempt coldAttempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), coldSeed.state(),
                thermo::newWorkspace, 64, 1.0e-8);
        double coldSeconds = (System.nanoTime() - coldStarted) / 1.0e9;
        V3DryMeshState seed = perturbedV3State(problem, independent.state());
        long v3Started = System.nanoTime();
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed, thermo::newWorkspace, 64, 1.0e-8);
        double v3Seconds = (System.nanoTime() - v3Started) / 1.0e9;
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, attempt, attempt::toString);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .audit(converged.state(), thermo.newWorkspace());
        assertTrue(audit.accepted(), audit::toString);

        double maximumTemperatureDelta = 0.0;
        double maximumFlowDelta = 0.0;
        for (int node = 0; node < converged.state().nodeCount(); node++) {
            maximumTemperatureDelta = Math.max(maximumTemperatureDelta, Math.abs(
                    converged.state().temperatureKelvin(node)
                            - HollandExample32Data.kelvinFromFahrenheit(independent.state().temperaturesFahrenheit()[node])));
            for (int component = 0; component < converged.state().componentCount(); component++) {
                maximumFlowDelta = Math.max(maximumFlowDelta, relativeError(
                        converged.state().liquidFlow(node, component) / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND,
                        independent.state().liquidComponentFlows()[node][component]));
                maximumFlowDelta = Math.max(maximumFlowDelta, relativeError(
                        converged.state().vaporFlow(node, component) / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND,
                        independent.state().vaporComponentFlows()[node][component]));
            }
        }
        return new CaseResult(splitRatio, oracle, independent, oracleSeconds, problem, thermo,
                coldSeed.evidence(), coldAttempt, coldSeconds, converged, audit, v3Seconds,
                maximumTemperatureDelta, maximumFlowDelta);
    }

    private static V3DryMeshState perturbedV3State(
            V3ColumnProblem problem, IndependentHollandMeshOracle.State source) {
        int nodes = source.temperaturesFahrenheit().length;
        int components = source.liquidComponentFlows()[0].length;
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            temperatures[node] = HollandExample32Data.kelvinFromFahrenheit(source.temperaturesFahrenheit()[node]);
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

    private static Accuracy compareWithPublished(HollandExample32Data data, CaseResult result) {
        V3DryMeshState state = result.converged().state();
        double[] liquid = phaseTotalsLbMolPerHour(state, true);
        double[] vapor = phaseTotalsLbMolPerHour(state, false);
        double[] temperatures = new double[state.nodeCount()];
        double[] targetTemperatures = new double[state.nodeCount()];
        double[] temperatureDeltas = new double[state.nodeCount()];
        double[] liquidRelativeErrors = new double[state.nodeCount()];
        double[] vaporRelativeErrors = new double[state.nodeCount()];
        double maximumTemperatureDelta = 0.0;
        double maximumLiquidRelativeError = 0.0;
        double maximumVaporRelativeError = 0.0;
        List<String> failures = new ArrayList<>();
        for (int node = 0; node < state.nodeCount(); node++) {
            temperatures[node] = state.temperatureKelvin(node);
            double targetTemperature = HollandExample32Data.kelvinFromFahrenheit(
                    data.solution().temperatureFahrenheit()[node]);
            double temperatureDelta = Math.abs(temperatures[node] - targetTemperature);
            targetTemperatures[node] = targetTemperature;
            temperatureDeltas[node] = temperatureDelta;
            maximumTemperatureDelta = Math.max(maximumTemperatureDelta, temperatureDelta);
            if (temperatureDelta > TEMPERATURE_TOLERANCE_KELVIN) {
                failures.add("temperature node " + node + " delta=" + temperatureDelta + " K");
            }
            double liquidError = relativeError(liquid[node], data.solution().v3LiquidStateLbMolPerHour()[node]);
            double vaporError = relativeError(vapor[node], data.solution().vaporLbMolPerHour()[node]);
            liquidRelativeErrors[node] = liquidError;
            vaporRelativeErrors[node] = vaporError;
            maximumLiquidRelativeError = Math.max(maximumLiquidRelativeError, liquidError);
            maximumVaporRelativeError = Math.max(maximumVaporRelativeError, vaporError);
            if (liquidError > FLOW_RELATIVE_TOLERANCE) {
                failures.add("liquid node " + node + " relative=" + liquidError);
            }
            if (vaporError > FLOW_RELATIVE_TOLERANCE) {
                failures.add("vapor node " + node + " relative=" + vaporError);
            }
        }

        double[][] actualProducts = productFlowsLbMolPerHour(data, state);
        double[][] targetProducts = data.productTargetsLbMolPerHour();
        double maximumMajorProductRelativeError = 0.0;
        double maximumTraceLog10Error = 0.0;
        List<ProductComparison> productComparisons = new ArrayList<>();
        for (int product = 0; product < actualProducts.length; product++) {
            for (int component = 0; component < actualProducts[product].length; component++) {
                double target = targetProducts[product][component];
                double actual = actualProducts[product][component];
                if (target >= 0.01) {
                    double error = relativeError(actual, target);
                    maximumMajorProductRelativeError = Math.max(maximumMajorProductRelativeError, error);
                    productComparisons.add(new ProductComparison(product, data.component(component).id(), target,
                            actual, "RELATIVE", error, MAJOR_PRODUCT_RELATIVE_TOLERANCE,
                            error <= MAJOR_PRODUCT_RELATIVE_TOLERANCE));
                    if (error > MAJOR_PRODUCT_RELATIVE_TOLERANCE) {
                        failures.add("product " + product + " component " + data.component(component).id()
                                + " relative=" + error);
                    }
                } else {
                    double error = Math.abs(Math.log10(actual) - Math.log10(target));
                    maximumTraceLog10Error = Math.max(maximumTraceLog10Error, error);
                    productComparisons.add(new ProductComparison(product, data.component(component).id(), target,
                            actual, "LOG10_ABSOLUTE", error, TRACE_PRODUCT_LOG10_TOLERANCE,
                            error <= TRACE_PRODUCT_LOG10_TOLERANCE));
                    if (error > TRACE_PRODUCT_LOG10_TOLERANCE) {
                        failures.add("trace product " + product + " component " + data.component(component).id()
                                + " log10=" + error);
                    }
                }
            }
        }

        double emergentReflux = result.splitRatio() / (1.0 + result.splitRatio()) * liquid[0] / vapor[0];
        double refluxDelta = Math.abs(emergentReflux - data.publishedRefluxRatio());
        if (refluxDelta > REFLUX_RATIO_ABSOLUTE_TOLERANCE) failures.add("emergent reflux delta=" + refluxDelta);
        double condenserDuty = condenserDutyBtuPerHour(result);
        double condenserDutyError = relativeError(condenserDuty, data.solution().condenserDutyBtuPerHour());
        if (condenserDutyError > CONDENSER_DUTY_RELATIVE_TOLERANCE) {
            failures.add("condenser duty relative=" + condenserDutyError);
        }
        return new Accuracy(targetTemperatures, temperatures, temperatureDeltas,
                data.solution().v3LiquidStateLbMolPerHour(), liquid, liquidRelativeErrors,
                data.solution().vaporLbMolPerHour(), vapor, vaporRelativeErrors,
                targetProducts, actualProducts, List.copyOf(productComparisons), maximumTemperatureDelta,
                maximumLiquidRelativeError, maximumVaporRelativeError, maximumMajorProductRelativeError,
                maximumTraceLog10Error, emergentReflux, refluxDelta, condenserDuty, condenserDutyError,
                failures.isEmpty(), List.copyOf(failures));
    }

    private static Sensitivity sensitivity(CaseResult reference, CaseResult candidate) {
        V3DryMeshState base = reference.converged().state();
        V3DryMeshState compared = candidate.converged().state();
        double maximumTemperature = 0.0;
        for (int node = 0; node < base.nodeCount(); node++) {
            maximumTemperature = Math.max(maximumTemperature,
                    Math.abs(base.temperatureKelvin(node) - compared.temperatureKelvin(node)));
        }
        double[][] baseProducts = productFlowsLbMolPerHour(HollandExample32Data.INSTANCE, base);
        double[][] comparedProducts = productFlowsLbMolPerHour(HollandExample32Data.INSTANCE, compared);
        double maximumProduct = 0.0;
        for (int product = 0; product < baseProducts.length; product++) {
            for (int component = 0; component < baseProducts[product].length; component++) {
                maximumProduct = Math.max(maximumProduct,
                        relativeError(comparedProducts[product][component], baseProducts[product][component]));
            }
        }
        return new Sensitivity(candidate.splitRatio(), maximumTemperature, maximumProduct);
    }

    private static double[][] productFlowsLbMolPerHour(HollandExample32Data data, V3DryMeshState state) {
        double[][] products = new double[3][state.componentCount()];
        double sideLiquid = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            products[0][component] = state.vaporFlow(0, component)
                    / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND;
            sideLiquid += state.liquidFlow(data.sideDrawTray(), component);
            products[2][component] = state.liquidFlow(state.nodeCount() - 1, component)
                    / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND;
        }
        for (int component = 0; component < state.componentCount(); component++) {
            products[1][component] = 25.0 * state.liquidFlow(data.sideDrawTray(), component) / sideLiquid;
        }
        return products;
    }

    private static double[] phaseTotalsLbMolPerHour(V3DryMeshState state, boolean liquid) {
        double[] totals = new double[state.nodeCount()];
        for (int node = 0; node < state.nodeCount(); node++) {
            for (int component = 0; component < state.componentCount(); component++) {
                totals[node] += (liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component))
                        / HollandExample32Data.LB_MOL_PER_HOUR_TO_MOL_PER_SECOND;
            }
        }
        return totals;
    }

    private static double condenserDutyBtuPerHour(CaseResult result) {
        V3DryMeshState state = result.converged().state();
        double incoming = phaseEnergyWatts(result, 1, false);
        double liquidOutlet = phaseEnergyWatts(result, 0, true);
        double vaporOutlet = phaseEnergyWatts(result, 0, false);
        return (incoming - liquidOutlet - vaporOutlet) / HollandExample32Data.BTU_PER_HOUR_TO_WATT;
    }

    private static double phaseEnergyWatts(CaseResult result, int node, boolean liquid) {
        V3DryMeshState state = result.converged().state();
        double[] flows = new double[state.componentCount()];
        double total = 0.0;
        for (int component = 0; component < flows.length; component++) {
            flows[component] = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            total += flows[component];
        }
        for (int component = 0; component < flows.length; component++) flows[component] /= total;
        return total * result.thermo().molarEnthalpy(state.temperatureKelvin(node),
                result.problem().nodePressurePascal(node), flows, liquid ? V3Phase.LIQUID : V3Phase.VAPOR,
                result.thermo().newWorkspace());
    }

    private static void writeReport(
            HollandExample32Data data, List<CaseResult> cases, List<Sensitivity> sensitivity,
            Accuracy accuracy) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("fixtureId", "holland-1981-example-3-2");
        report.put("authority", "HOLLAND_1981_PAGE_SCAN_PLUS_INDEPENDENT_MESH");
        report.put("source", data.source());
        report.put("feedBubblePointKelvin", cases.get(1).problem().input().feedTemperatureKelvin());
        report.put("cases", cases.stream().map(CaseResult::report).toList());
        report.put("sensitivityAgainst1e4", sensitivity);
        Map<String, Object> tolerances = new LinkedHashMap<>();
        tolerances.put("temperatureAbsoluteKelvin", TEMPERATURE_TOLERANCE_KELVIN);
        tolerances.put("totalFlowRelative", FLOW_RELATIVE_TOLERANCE);
        tolerances.put("majorProductRelative", MAJOR_PRODUCT_RELATIVE_TOLERANCE);
        tolerances.put("traceProductLog10Absolute", TRACE_PRODUCT_LOG10_TOLERANCE);
        tolerances.put("emergentRefluxAbsolute", REFLUX_RATIO_ABSOLUTE_TOLERANCE);
        tolerances.put("condenserDutyRelative", CONDENSER_DUTY_RELATIVE_TOLERANCE);
        report.put("tolerances", tolerances);
        report.put("publishedAccuracy", accuracy);
        report.put("strictPublishedAccuracyAccepted", accuracy.strictPublishedAccuracyAccepted());
        report.put("facadeLimitation", "V3ColumnCalculator is hard-wired to registered Peng-Robinson packages; benchmark uses the internal solver/audit chain.");
        Path path = Path.of(System.getProperty("v3HollandReport",
                "build/reports/benchmarks/v3-holland-example-3-2.json"));
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report),
                StandardCharsets.UTF_8);
    }

    private static double relativeError(double actual, double expected) {
        return Math.abs(actual - expected) / Math.max(Math.abs(expected), 1.0e-300);
    }

    private record CaseResult(
            double splitRatio, IndependentHollandMeshOracle oracle,
            IndependentHollandMeshOracle.SolveResult independent, double independentSeconds,
            V3ColumnProblem problem, HollandB12Thermo thermo,
            V3ColumnInitializer.Evidence coldSeedEvidence, V3SimultaneousColumnSolver.Attempt coldAttempt,
            double coldSeconds,
            V3SimultaneousColumnSolver.Attempt.Converged converged, V3AcceptanceAudit audit,
            double v3Seconds, double maximumV3ToIndependentTemperatureDeltaKelvin,
            double maximumV3ToIndependentRelativeFlowDelta) {
        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("condenserSplitRatio", splitRatio);
            result.put("independentIterations", independent.iterations());
            result.put("independentMaximumScaledResidual", independent.maximumScaledResidual());
            result.put("independentSeconds", independentSeconds);
            result.put("coldSeed", coldSeedEvidence);
            result.put("coldOutcome", coldAttempt instanceof V3SimultaneousColumnSolver.Attempt.Converged
                    ? "CONVERGED" : ((V3SimultaneousColumnSolver.Attempt.Failure) coldAttempt).code());
            result.put("coldIterations", coldAttempt.evidence().iterations());
            result.put("coldMaximumScaledResidual", coldAttempt.evidence().maximumScaledResidual());
            result.put("coldSeconds", coldSeconds);
            result.put("v3Iterations", converged.evidence().iterations());
            result.put("v3MaximumScaledResidual", converged.evidence().maximumScaledResidual());
            result.put("v3Seconds", v3Seconds);
            result.put("auditAccepted", audit.accepted());
            result.put("maximumV3ToIndependentTemperatureDeltaKelvin", maximumV3ToIndependentTemperatureDeltaKelvin);
            result.put("maximumV3ToIndependentRelativeFlowDelta", maximumV3ToIndependentRelativeFlowDelta);
            return result;
        }
    }

    private record Sensitivity(
            double condenserSplitRatio, double maximumTemperatureDeltaKelvin,
            double maximumProductRelativeDelta) {}

    private record Accuracy(
            double[] targetTemperaturesKelvin, double[] actualTemperaturesKelvin,
            double[] temperatureAbsoluteDeltasKelvin,
            double[] targetLiquidLbMolPerHour, double[] actualLiquidLbMolPerHour,
            double[] liquidRelativeErrors,
            double[] targetVaporLbMolPerHour, double[] actualVaporLbMolPerHour,
            double[] vaporRelativeErrors,
            double[][] targetProductLbMolPerHour, double[][] actualProductLbMolPerHour,
            List<ProductComparison> productComparisons, double maximumTemperatureDeltaKelvin,
            double maximumLiquidRelativeError, double maximumVaporRelativeError,
            double maximumMajorProductRelativeError, double maximumTraceLog10Error,
            double emergentRefluxRatio, double emergentRefluxAbsoluteError,
            double condenserDutyBtuPerHour, double condenserDutyRelativeError,
            boolean strictPublishedAccuracyAccepted, List<String> failures) {}

    private record ProductComparison(
            int productIndex, String componentId, double targetLbMolPerHour, double actualLbMolPerHour,
            String metric, double error, double tolerance, boolean passed) {}
}
