package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Fresh-Jacobian and line-search diagnostics from saved states; never changes a production solve or its limits. */
public final class V3LowPressureLinearProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final Method TO_BAND = method(V3SimultaneousColumnSolver.class, "toBandedMatrix",
            V3FiniteDifferenceJacobian.Jacobian.class, V3StageBlockLayout.class);
    private static final Method MERIT = method(V3SimultaneousColumnSolver.class, "scaledSquaredNorm", V3MeshResidual.class);
    private static final Method ADD_SCALED = method(V3SimultaneousColumnSolver.class, "addScaled",
            double[].class, double[].class, double.class);
    private static final double ARMIJO = ((Number) field(V3SimultaneousColumnSolver.class, "ARMIJO_COEFFICIENT")).doubleValue();
    private static final double BAND_THRESHOLD = ((Number) field(V3SimultaneousColumnSolver.class, "OFF_BAND_TOLERANCE")).doubleValue();
    private static final int FINE_STEPS = ((Number) field(V3SimultaneousColumnSolver.class, "FINE_MAXIMUM_LINE_SEARCH_STEPS")).intValue();
    private static final int COARSE_STEPS = ((Number) field(V3SimultaneousColumnSolver.class, "COARSE_RECOVERY_MAXIMUM_LINE_SEARCH_STEPS")).intValue();
    private static final Method EMPTY_BLOCKS = method(V3BlockJacobianAssembler.class, "emptyBlocks", V3StageBlockLayout.class, int.class);
    private static final Method COORDINATE_INDEXES = method(V3BlockJacobianAssembler.class, "coordinateIndexes", V3DryMeshCoordinateMap.class);
    private static final Method EXACT_MATERIAL = method(V3BlockJacobianAssembler.class, "assembleExactMaterialRows",
            V3ColumnProblem.class, V3DryMeshState.class, V3MeshResidual.class, V3DryMeshCoordinateMap.class, Map.class,
            V3StageBlockLayout.class, double[][][].class, double[][][].class, double[][][].class);
    private static final int LAST_HALVING = 30;

    private V3LowPressureLinearProbe() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            String[] pair = arg.split("=", 2);
            if (pair.length != 2 || !List.of("--input", "--report").contains(pair[0]) || pair[1].isBlank()
                    || options.putIfAbsent(pair[0], pair[1]) != null) {
                throw new IllegalArgumentException("Require exactly --input=saved-probe.json --report=new-output.json");
            }
        }
        if (options.size() != 2) throw new IllegalArgumentException("Both --input and --report are required");
        Path inputPath = Path.of(options.get("--input")).toAbsolutePath().normalize();
        Path outputPath = Path.of(options.get("--report")).toAbsolutePath().normalize();
        if (Files.exists(outputPath)) throw new IllegalArgumentException("Refusing to overwrite " + outputPath);
        byte[] inputBytes = Files.readAllBytes(inputPath);
        JsonObject input = JsonParser.parseString(new String(inputBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, JsonObject> experiments = new LinkedHashMap<>();
        for (JsonElement element : input.getAsJsonArray("experiments")) {
            JsonObject experiment = element.getAsJsonObject();
            String label = experiment.get("label").getAsString();
            if (experiments.putIfAbsent(label, experiment) != null) throw new IllegalArgumentException("Duplicate experiment " + label);
        }
        JsonObject predictor = require(experiments, "production-55-predictor-12");
        double feedEnthalpy = predictor.get("feedEnthalpy").getAsDouble();
        if (!Double.isFinite(feedEnthalpy)) throw new IllegalArgumentException("Stored feed enthalpy must be finite");
        List<Target> targets = new ArrayList<>();
        targets.add(new Target("production-55-predictor-12", "seed", predictor));
        targets.add(new Target("production-55-predictor-12", "terminalState", predictor));
        targets.add(new Target("production-55-projected-24", "terminalState", require(experiments, "production-55-projected-24")));
        List<String> missingOptionalTargets = new ArrayList<>();
        for (String optional : List.of("predictor-128", "projected-128")) {
            if (experiments.containsKey(optional)) targets.add(new Target(optional, "terminalState", experiments.get(optional)));
            else missingOptionalTargets.add(optional);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> states = new ArrayList<>();
        report.put("scope", "Diagnostic only; no timing claims. Fresh FINE/COARSE Newton systems from saved truncation-OFF states, "
                + "not the production local/frozen/regularized direction selection. Exact-material variants replace ONLY component-material "
                + "rows using the current private analytic assembler. All trials use actual coordinate decoding, MESH evaluation, "
                + "merit, addition order and Armijo coefficient. The staircase continues after acceptance for observation.");
        report.put("input", inputPath.toString());
        report.put("inputSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inputBytes)));
        report.put("inputProductionSourcesUnchanged", input.get("productionSourcesUnchanged"));
        report.put("reconstructedInput", "V3TimeoutBenchmark input: TJL, 2610.7 kmol/h, 638.15 K, 30 trays/feed 24, "
                + "750 Pa/tray, 323.15 K condenser, reflux 2, 8 MW, truncation OFF, TWO_PHASE condenser");
        report.put("feedEnthalpySource", "production-55-predictor-12.feedEnthalpy");
        report.put("feedMolarEnthalpyJoulesPerMol", feedEnthalpy);
        report.put("armijoCoefficient", ARMIJO);
        report.put("fineProductionLineSearchSteps", FINE_STEPS);
        report.put("coarseProductionLineSearchSteps", COARSE_STEPS);
        report.put("lastObservedHalvingIndex", LAST_HALVING);
        report.put("nonfiniteNumberEncoding", "Nonfinite diagnostic numbers are strings, never invalid JSON numeric tokens.");
        report.put("missingOptionalTargets", missingOptionalTargets);
        report.put("states", states);
        report.put("status", "RUNNING");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, JSON.toJson(report), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            for (Target target : targets) {
                Map<String, Object> stateReport = new LinkedHashMap<>();
                states.add(stateReport);
                analyze(target, feedEnthalpy, stateReport);
                save(outputPath, report);
            }
            report.put("status", "COMPLETE");
        } catch (RuntimeException failure) {
            report.put("status", "ABORTED");
            report.put("failure", failure(failure));
            throw failure;
        } finally {
            save(outputPath, report);
        }
        System.out.println("LOW_PRESSURE_LINEAR status=" + report.get("status") + " states=" + states.size() + " report=" + outputPath);
    }

    private static void analyze(Target target, double feedEnthalpy, Map<String, Object> output) {
        output.put("experiment", target.label());
        output.put("stateField", target.stateField());
        output.put("sourceSolverStatus", target.experiment().get("solverStatus"));
        if (!"TWO_PHASE".equals(target.experiment().get("branch").getAsString())) {
            throw new IllegalArgumentException("Expected the captured TWO_PHASE condenser: " + target.label());
        }
        double pressure = target.experiment().get("pressurePa").getAsDouble();
        if (pressure != 55_000.0) throw new IllegalArgumentException("Expected a 55 kPa state: " + target.label());
        if (target.experiment().has("feedEnthalpy") && target.experiment().get("feedEnthalpy").getAsDouble() != feedEnthalpy) {
            throw new IllegalArgumentException("Stored 55 kPa feed enthalpies disagree");
        }
        V3ColumnInput input = V3TimeoutBenchmark.input(new V3TimeoutBenchmark.Scenario("linear-diagnostic", pressure / 1000.0, 2610.7, 8, 0));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        V3DryMeshState state = readState(target.experiment().getAsJsonArray(target.stateField()), problem);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feedEnthalpy);
        V3MeshResidual residual = evaluator.evaluate(state, thermo.newWorkspace());
        double merit = merit(residual);
        output.put("coordinateCount", coordinates.coordinateCount());
        output.put("pressurePa", pressure);
        output.put("feedPressurePa", problem.nodePressurePascal(input.feedStageNumber()));
        output.put("temperatureRange", temperatureRange(state));
        output.put("maximumScaledResidual", residual.maximumAbsoluteScaledResidual());
        output.put("merit", number(merit));
        output.put("familyMaxima", familyMaxima(residual));
        if (target.stateField().equals("terminalState")) {
            double stored = target.experiment().getAsJsonObject("evidence").get("maximumScaledResidual").getAsDouble();
            output.put("storedMaximumScaledResidual", stored);
            output.put("recomputedResidualMinusStored", residual.maximumAbsoluteScaledResidual() - stored);
        }
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        V3BandedMatrix exactMaterial = exactMaterialRows(problem, state, residual, coordinates, layout);
        List<Map<String, Object>> variants = new ArrayList<>();
        output.put("variants", variants);
        for (V3FiniteDifferenceJacobian.DifferenceScale scale : V3FiniteDifferenceJacobian.DifferenceScale.values()) {
            Map<String, Object> fresh = new LinkedHashMap<>();
            fresh.put("name", scale.name());
            variants.add(fresh);
            Map<String, Object> currentVariant = fresh;
            try {
                V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluate(
                        evaluator, coordinates, state, thermo::newWorkspace, scale, V3SolveControl.UNBOUNDED);
                fresh.put("analyticMaterialComparison", materialComparison(problem, jacobian, exactMaterial));
                analyzeLinear(problem, state, evaluator, coordinates, thermo, residual, merit, jacobian, scale, fresh);

                double[][] replacement = jacobian.values();
                for (int row = 0; row < replacement.length; row++) {
                    if (jacobian.equations().get(row).family() != V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE) continue;
                    for (int column = 0; column < replacement.length; column++) replacement[row][column] = exactMaterial.get(row, column);
                }
                V3FiniteDifferenceJacobian.Jacobian counterfactual = new V3FiniteDifferenceJacobian.Jacobian(
                        jacobian.equations(), jacobian.unknowns(), replacement);
                Map<String, Object> exact = new LinkedHashMap<>();
                exact.put("name", scale.name() + "_EXACT_MATERIAL_ROWS_ONLY");
                variants.add(exact);
                currentVariant = exact;
                analyzeLinear(problem, state, evaluator, coordinates, thermo, residual, merit, counterfactual, scale, exact);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (IllegalArgumentException | IllegalStateException | V3ThermoException unavailable) {
                currentVariant.put("status", "DIAGNOSTIC_FAILURE");
                currentVariant.put("failure", failure(unavailable));
            }
        }
        System.out.println("LOW_PRESSURE_LINEAR state=" + target.label() + "/" + target.stateField()
                + " residual=" + residual.maximumAbsoluteScaledResidual());
    }

    private static void analyzeLinear(V3ColumnProblem problem, V3DryMeshState state, V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates, V3PengRobinsonThermo thermo, V3MeshResidual residual, double merit,
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3FiniteDifferenceJacobian.DifferenceScale scale, Map<String, Object> output) {
        output.put("columnNorms", columnNorms(problem, jacobian));
        V3BandedMatrix band;
        try {
            band = (V3BandedMatrix) invoke(TO_BAND, jacobian, new V3StageBlockLayout(problem));
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            output.put("status", "BAND_CONVERSION_FAILURE");
            output.put("failure", failure(unavailable));
            return;
        }
        output.put("bandLoss", bandLoss(jacobian, band));
        double[] rhs = new double[residual.rows().size()];
        for (int row = 0; row < rhs.length; row++) rhs[row] = -residual.rows().get(row).scaledValue();
        V3BandedPivotedSolver.Result linear = V3BandedPivotedSolver.solve(band, rhs);
        if (linear instanceof V3BandedPivotedSolver.Result.Failure failure) {
            output.put("status", "LINEAR_FAILURE");
            output.put("linearEvidence", failure);
            output.put("armijoNotEvaluated", "No usable Newton direction was returned by the actual banded solver.");
            return;
        }
        V3BandedPivotedSolver.Result.Success success = (V3BandedPivotedSolver.Result.Success) linear;
        output.put("status", "LINEAR_SUCCESS");
        output.put("linearEvidence", Map.of("backwardError", success.backwardError(),
                "minimumPivotMagnitude", success.minimumPivotMagnitude(), "maximumPivotMagnitude", success.maximumPivotMagnitude(),
                "pivotRatio", success.minimumPivotMagnitude() / success.maximumPivotMagnitude(),
                "pivotSwaps", success.pivotSwaps(), "pivotGrowth", success.pivotGrowth()));
        double[] direction = success.solution();
        double[] base = coordinates.encode(state);
        output.put("largestDirections", largestDirections(problem, coordinates, base, direction, "all"));
        output.put("largestLogFlowDirections", largestDirections(problem, coordinates, base, direction, "flow"));
        output.put("largestTemperatureDirections", largestDirections(problem, coordinates, base, direction, "temperature"));
        output.put("armijo", staircase(evaluator, coordinates, thermo, base, direction, merit,
                scale == V3FiniteDifferenceJacobian.DifferenceScale.FINE ? FINE_STEPS : COARSE_STEPS));
    }

    private static Map<String, Object> staircase(V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3PengRobinsonThermo thermo, double[] base, double[] direction, double merit, int productionSteps) {
        Map<String, Object> output = new LinkedHashMap<>();
        List<Map<String, Object>> trials = new ArrayList<>();
        output.put("productionStepCount", productionSteps);
        output.put("productionSearchFullyObserved", LAST_HALVING + 1 >= productionSteps);
        output.put("trials", trials);
        Integer firstAccepted = null;
        Integer firstAcceptedInProductionBudget = null;
        Map<String, Object> lastDecodedTemperatures = null;
        Map<String, Object> lastEvaluatedTemperatures = null;
        Map<String, Object> lastFailure = null;
        for (int halving = 0; halving <= LAST_HALVING; halving++) {
            double step = Math.scalb(1.0, -halving);
            double[] candidateCoordinates = (double[]) invoke(ADD_SCALED, base, direction, step);
            Map<String, Object> trial = new LinkedHashMap<>();
            trials.add(trial);
            trial.put("halvingIndex", halving);
            trial.put("step", step);
            trial.put("insideProductionBudget", halving < productionSteps);
            trial.put("proposedTemperatureRange", coordinateTemperatureRange(coordinates, candidateCoordinates));
            double threshold = merit * (1.0 - ARMIJO * step);
            trial.put("armijoMeritThreshold", number(threshold));
            String phase = "decode";
            try {
                V3DryMeshState candidate = coordinates.decode(candidateCoordinates);
                lastDecodedTemperatures = temperatureRange(candidate);
                trial.put("decodedTemperatureRange", lastDecodedTemperatures);
                phase = "evaluate";
                V3MeshResidual candidateResidual = evaluator.evaluate(candidate, thermo.newWorkspace());
                double candidateMerit = merit(candidateResidual);
                lastEvaluatedTemperatures = lastDecodedTemperatures;
                boolean accepted = candidateMerit <= threshold;
                trial.put("status", accepted ? "ARMIJO_ACCEPTED" : "MERIT_REJECTED");
                trial.put("candidateMerit", number(candidateMerit));
                trial.put("candidateMinusBaseMerit", number(candidateMerit - merit));
                trial.put("candidateMaximumScaledResidual", candidateResidual.maximumAbsoluteScaledResidual());
                trial.put("familyMaxima", familyMaxima(candidateResidual));
                if (accepted && firstAccepted == null) firstAccepted = halving;
                if (accepted && halving < productionSteps && firstAcceptedInProductionBudget == null) firstAcceptedInProductionBudget = halving;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (IllegalArgumentException | V3ThermoException rejected) {
                trial.put("status", phase.equals("decode") ? "DECODE_REJECTED" : "EVALUATION_REJECTED");
                lastFailure = failure(rejected);
                trial.put("failure", lastFailure);
            }
        }
        output.put("firstAcceptedHalvingIndex", firstAccepted);
        output.put("firstAcceptedWithinProductionBudget", firstAcceptedInProductionBudget);
        output.put("lastDecodedTemperatureRange", lastDecodedTemperatures);
        output.put("lastEvaluatedTemperatureRange", lastEvaluatedTemperatures);
        output.put("lastFailure", lastFailure);
        return output;
    }

    private static V3BandedMatrix exactMaterialRows(V3ColumnProblem problem, V3DryMeshState state,
            V3MeshResidual residual, V3DryMeshCoordinateMap coordinates, V3StageBlockLayout layout) {
        double[][][] lower = (double[][][]) invoke(EMPTY_BLOCKS, layout, -1);
        double[][][] diagonal = (double[][][]) invoke(EMPTY_BLOCKS, layout, 0);
        double[][][] upper = (double[][][]) invoke(EMPTY_BLOCKS, layout, 1);
        Object indexes = invoke(COORDINATE_INDEXES, coordinates);
        invoke(EXACT_MATERIAL, problem, state, residual, coordinates, indexes, layout, lower, diagonal, upper);
        return new V3BlockJacobian(layout, lower, diagonal, upper, 0.0).toBandedMatrix();
    }

    private static Map<String, Object> materialComparison(V3ColumnProblem problem,
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3BandedMatrix exact) {
        long nonzeroExact = 0, lostToZero = 0;
        double maximumDifference = 0.0, maximumRelativeDifference = 0.0;
        List<Map<String, Object>> lost = new ArrayList<>();
        for (int row = 0; row < jacobian.equations().size(); row++) {
            if (jacobian.equations().get(row).family() != V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE) continue;
            for (int column = 0; column < jacobian.unknowns().size(); column++) {
                double expected = exact.get(row, column), actual = jacobian.value(row, column);
                maximumDifference = Math.max(maximumDifference, Math.abs(actual - expected));
                if (expected == 0.0) continue;
                nonzeroExact++;
                maximumRelativeDifference = Math.max(maximumRelativeDifference, Math.abs((actual - expected) / expected));
                if (actual == 0.0) {
                    lostToZero++;
                    Map<String, Object> entry = coordinate(problem, jacobian.unknowns().get(column), column);
                    entry.put("row", row);
                    entry.put("equation", jacobian.equations().get(row));
                    entry.put("exactDerivative", expected);
                    lost.add(entry);
                }
            }
        }
        lost.sort(Comparator.comparingDouble((Map<String, Object> entry) -> Math.abs((double) entry.get("exactDerivative"))).reversed());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exactNonzeroEntries", nonzeroExact);
        result.put("fdZeroWhereExactNonzeroCount", lostToZero);
        result.put("maximumAbsoluteDifference", maximumDifference);
        result.put("maximumRelativeDifferenceOnNonzeroExact", number(maximumRelativeDifference));
        result.put("largestMissingDerivatives", lost.stream().limit(10).toList());
        return result;
    }

    private static Map<String, Object> bandLoss(V3FiniteDifferenceJacobian.Jacobian jacobian, V3BandedMatrix band) {
        long lost = 0, changed = 0;
        double maximumLost = 0.0, maximumDifference = 0.0;
        for (int row = 0; row < band.size(); row++) {
            for (int column = 0; column < band.size(); column++) {
                double before = jacobian.value(row, column), after = band.get(row, column);
                if (before != after) changed++;
                maximumDifference = Math.max(maximumDifference, Math.abs(before - after));
                if (before != 0.0 && after == 0.0) { lost++; maximumLost = Math.max(maximumLost, Math.abs(before)); }
            }
        }
        return Map.of("lowerBandwidth", band.lowerBandwidth(), "upperBandwidth", band.upperBandwidth(),
                "discoveryThreshold", BAND_THRESHOLD, "nonzeroEntriesLost", lost, "maximumLostMagnitude", maximumLost,
                "changedEntryCount", changed, "maximumAbsoluteDifference", maximumDifference);
    }

    private static Map<String, Object> columnNorms(V3ColumnProblem problem, V3FiniteDifferenceJacobian.Jacobian jacobian) {
        List<Map<String, Object>> columns = new ArrayList<>();
        long zeros = 0, belowBandThreshold = 0;
        for (int column = 0; column < jacobian.unknowns().size(); column++) {
            double norm = 0.0;
            for (int row = 0; row < jacobian.equations().size(); row++) norm = Math.max(norm, Math.abs(jacobian.value(row, column)));
            if (norm == 0.0) zeros++;
            if (norm <= BAND_THRESHOLD) belowBandThreshold++;
            Map<String, Object> entry = coordinate(problem, jacobian.unknowns().get(column), column);
            entry.put("infinityNorm", norm);
            columns.add(entry);
        }
        columns.sort(Comparator.comparingDouble(entry -> (double) entry.get("infinityNorm")));
        return Map.of("definition", "max absolute scaled Jacobian entry in each column", "minimumInfinityNorm", columns.getFirst().get("infinityNorm"),
                "zeroColumnCount", zeros, "columnsAtOrBelowBandDiscoveryThreshold", belowBandThreshold,
                "smallestColumns", columns.stream().limit(10).toList());
    }

    private static List<Map<String, Object>> largestDirections(V3ColumnProblem problem, V3DryMeshCoordinateMap coordinates,
            double[] base, double[] direction, String filter) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int column = 0; column < direction.length; column++) {
            V3DegreeOfFreedomLedger.UnknownId id = coordinates.unknowns().get(column).id();
            boolean temperature = id.family() == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE;
            if ((filter.equals("flow") && temperature) || (filter.equals("temperature") && !temperature)) continue;
            Map<String, Object> entry = coordinate(problem, id, column);
            entry.put("baseCoordinate", base[column]);
            entry.put("correction", direction[column]);
            entry.put("units", temperature ? "K" : "log(flow/scale)");
            if (!temperature) entry.put("unitStepFlowMultiplier", number(Math.exp(direction[column])));
            entries.add(entry);
        }
        entries.sort(Comparator.comparingDouble((Map<String, Object> entry) -> Math.abs((double) entry.get("correction"))).reversed());
        return entries.stream().limit(10).toList();
    }

    private static Map<String, Object> coordinate(V3ColumnProblem problem, V3DegreeOfFreedomLedger.UnknownId id, int column) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate", column); result.put("family", id.family()); result.put("node", id.node());
        result.put("activeComponent", id.component());
        result.put("componentId", id.component() < 0 ? null
                : problem.input().componentBasis().componentId(problem.activeComponentBasis().publicIndex(id.component())));
        return result;
    }

    private static V3DryMeshState readState(JsonArray rows, V3ColumnProblem problem) {
        int count = problem.activeComponentBasis().componentCount();
        if (rows == null || rows.size() != problem.topology().nodeCount()) throw new IllegalArgumentException("Stored node count differs");
        double[][] liquid = new double[rows.size()][count], vapor = new double[rows.size()][count];
        double[] temperatures = new double[rows.size()];
        for (int node = 0; node < rows.size(); node++) {
            JsonArray row = rows.get(node).getAsJsonArray();
            if (row.size() != 1 + 2 * count) throw new IllegalArgumentException("Stored component axis differs at node " + node);
            temperatures[node] = row.get(0).getAsDouble();
            for (int component = 0; component < count; component++) {
                liquid[node][component] = row.get(1 + 2 * component).getAsDouble();
                vapor[node][component] = row.get(2 + 2 * component).getAsDouble();
            }
        }
        return new V3DryMeshState(problem.topology(), count, liquid, vapor, temperatures);
    }

    private static Map<String, Object> temperatureRange(V3DryMeshState state) {
        double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
        for (int node = 0; node < state.nodeCount(); node++) {
            minimum = Math.min(minimum, state.temperatureKelvin(node)); maximum = Math.max(maximum, state.temperatureKelvin(node));
        }
        return Map.of("minimumKelvin", number(minimum), "maximumKelvin", number(maximum));
    }

    private static Map<String, Object> coordinateTemperatureRange(V3DryMeshCoordinateMap coordinates, double[] values) {
        double minimum = 323.15, maximum = 323.15;
        for (int index = 0; index < values.length; index++) {
            if (coordinates.unknowns().get(index).id().family() != V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE) continue;
            minimum = Math.min(minimum, values[index]); maximum = Math.max(maximum, values[index]);
        }
        return Map.of("minimumKelvin", number(minimum), "maximumKelvin", number(maximum));
    }

    private static Map<String, Double> familyMaxima(V3MeshResidual residual) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (V3MeshResidual.Row row : residual.rows()) result.merge(row.equation().family().name(), Math.abs(row.scaledValue()), Math::max);
        return result;
    }

    private static Map<String, Object> failure(RuntimeException failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", failure.getClass().getName()); result.put("message", failure.getMessage());
        if (failure instanceof V3ThermoException thermo) { result.put("thermoCode", thermo.code()); result.put("phase", thermo.phase()); }
        if (failure.getCause() != null) result.put("cause", failure.getCause().toString());
        return result;
    }

    private static double merit(V3MeshResidual residual) { return (double) invoke(MERIT, residual); }
    private static Object number(double value) { return Double.isFinite(value) ? value : Double.toString(value); }
    private static JsonObject require(Map<String, JsonObject> experiments, String label) {
        JsonObject result = experiments.get(label);
        if (result == null) throw new IllegalArgumentException("Missing required experiment " + label);
        return result;
    }
    private static void save(Path path, Map<String, Object> report) throws IOException {
        Files.writeString(path, JSON.toJson(report), StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try { Method result = type.getDeclaredMethod(name, parameters); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Diagnostic method no longer matches: " + name, failure); }
    }
    private static Object field(Class<?> type, String name) {
        try { Field result = type.getDeclaredField(name); result.setAccessible(true); return result.get(null); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Diagnostic field no longer matches: " + name, failure); }
    }
    private static Object invoke(Method method, Object... arguments) {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unexpected checked diagnostic invocation failure", cause);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Diagnostic invocation failed", failure); }
    }
    private record Target(String label, String stateField, JsonObject experiment) {}
}
