package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver;
import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Diagnostic first-path probe, NOT a replacement for the public calculator benchmark.
 *
 * <p>Stage trace truncation is always OFF. Uses the production preferred-condenser probe and
 * stage seed/interpolation/material-VLE handoff, but deliberately stops at the first failed rung:
 * no projected recovery, automatic material-closed fallback, coarse stencil, or alternate branch retry.
 * Material-closed mode separately probes the fresh 30-stage fallback; single-stage-local mode probes
 * a sequentially initialized 30-stage problem without the stage ladder. Branch selection can be overridden.
 * Pressure mode deliberately extends the production pressure path to targets above its 100 kPa gate.
 * Timing includes observation and console output, so use a facade/JFR benchmark for clean timings.</p>
 */
public final class V3ContinuationProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    private static final String ASSAY_ID = "createcheme:tia_juana_light";

    private V3ContinuationProbe() {}

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        Probe probe = new Probe(options);
        probe.run();
        Path report = options.report().toAbsolutePath().normalize();
        Files.createDirectories(report.getParent());
        try (Writer writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            JSON.toJson(probe.report, writer);
        }
        System.out.println("V3 continuation probe: status=" + probe.report.get("status") + "; report=" + report);
    }

    private enum Policy { STAGE_LOCAL, FULL_FINE, PRESSURE_PREDICTOR }

    private record Options(String mode, String branch, int linearDiagnostics, double deadlineSeconds, double feedKmolH,
                           double pressureKpa, double dutyMW, Path report) {
        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Expected --name=value, got " + arg);
                }
                String[] pair = arg.substring(2).split("=", 2);
                if (!List.of("mode", "branch", "linearDiagnostics", "deadlineSeconds", "feedKmolH", "pressureKpa", "dutyMW", "report").contains(pair[0])
                        || values.putIfAbsent(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("Unknown or duplicate option " + pair[0]);
                }
            }
            String mode = values.getOrDefault("mode", "stage-local");
            if (!List.of("stage-local", "stage-full", "pressure", "material-closed", "single-stage-local").contains(mode)) {
                throw new IllegalArgumentException("mode must be stage-local, stage-full, pressure, material-closed, or single-stage-local");
            }
            String branch = values.getOrDefault("branch", "preferred");
            if (!List.of("preferred", "liquid-only", "two-phase").contains(branch)) {
                throw new IllegalArgumentException("branch must be preferred, liquid-only, or two-phase");
            }
            int linearDiagnostics = Integer.parseInt(values.getOrDefault("linearDiagnostics", "0"));
            if (linearDiagnostics < 0 || linearDiagnostics > 3 || (linearDiagnostics > 0 && !mode.equals("material-closed"))) {
                throw new IllegalArgumentException("linearDiagnostics must be 0..3 and is supported only in material-closed mode");
            }
            double deadline = number(values, "deadlineSeconds", 120.0);
            double feed = number(values, "feedKmolH", 2610.7);
            double pressure = number(values, "pressureKpa", 110.0);
            double duty = number(values, "dutyMW", 8.0);
            if (deadline <= 0.0 || deadline > 86400.0 || feed <= 0.0 || pressure <= 0.0 || duty < 0.0
                    || (mode.equals("pressure") && pressure > 150.0)) {
                throw new IllegalArgumentException("Require 0<deadlineSeconds<=86400, positive feed/pressure, nonnegative duty; pressure mode target<=150 kPa");
            }
            return new Options(mode, branch, linearDiagnostics, deadline, feed, pressure, duty,
                    Path.of(values.getOrDefault("report", "build/reports/v3-continuation-probe.json")));
        }

        private static double number(Map<String, String> values, String key, double fallback) {
            double value = Double.parseDouble(values.getOrDefault(key, Double.toString(fallback)));
            if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
            return value;
        }
    }

    private static final class Probe {
        private final Options options;
        private final Deadline control;
        private final Map<String, Object> report = new LinkedHashMap<>();
        private final Map<String, Double> setupMilliseconds = new LinkedHashMap<>();
        private final List<Rung> rungs = new ArrayList<>();
        private V3PengRobinsonThermo thermo;
        private V3CondenserPhaseBranch branch;
        private Rung current;

        private Probe(Options options) {
            this.options = options;
            control = new Deadline(options.deadlineSeconds());
            report.put("startedUtc", Instant.now().toString());
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
            report.put("requestedBranch", options.branch());
            report.put("linearDiagnosticLimit", options.linearDiagnostics());
            report.put("linearDiagnosticSampling", "For linearDiagnostics=N, sample fresh-FD callbacks only at Newton iterations 0, 10, ... 10*(N-1); N<=3. No sample is synthesized if its iteration is not reached.");
            report.put("parameters", Map.of("mode", options.mode(), "deadlineSeconds", options.deadlineSeconds(),
                    "feedKmolH", options.feedKmolH(), "feedMolS", options.feedKmolH() * 1000.0 / 3600.0,
                    "pressureKpa", options.pressureKpa(), "dutyMW", options.dutyMW(), "stageTraceCutoff", 0.0,
                    "stages", 30, "feedStage", 24));
            report.put("fixedInput", Map.of("package", PACKAGE_ID, "assay", ASSAY_ID, "feedTemperatureK", 638.15,
                    "condenserTemperatureK", 323.15, "refluxRatio", 2.0, "stagePressureDropPa", 750.0));
            report.put("scope", "First-path diagnostic only; preferred branch unless explicitly overridden; no failed-rung recovery, automatic material-closed fallback, coarse stencil, or alternate condenser branch. Material-closed mode probes only the fresh 30-stage fallback. Single-stage-local bypasses the 4/8/15-stage ladder and solves a fresh sequentially initialized 30-stage problem with local blocks. Pressure mode overrides the <=100 kPa routing gate. Observer output is included in timing.");
            report.put("measurementCaveats", "stage-full disables both local-block steps AND one-step frozen-FD reuse; it is not a pure local-block ablation. FD counts come from the solver's callback and exclude final-Newton verification and optional diagnostic recomputations. An extra diagnostic condenser TP flash follows each audit. linearDiagnostics adds up to three duplicate FINE Jacobian/LU calculations inside the deadline; separate diagnostic timings are reported and such runs are not clean timing comparisons.");
            report.put("setupMilliseconds", setupMilliseconds);
            report.put("rungs", rungs);
            report.put("status", "RUNNING");
        }

        private void run() {
            System.out.println(report.get("scope"));
            System.out.println("parameters=" + report.get("parameters"));
            try {
                control.phase("load-package-and-feed", setupMilliseconds);
                thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
                V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
                V3ColumnInput requested = input(crude, options.pressureKpa() * 1000.0, 30);
                control.phase("condenser-branch-selection", setupMilliseconds);
                branch = switch (options.branch()) {
                    case "liquid-only" -> V3CondenserPhaseBranch.LIQUID_ONLY;
                    case "two-phase" -> V3CondenserPhaseBranch.TWO_PHASE;
                    default -> preferredBranch(requested);
                };
                report.put("condenserBranch", branch);
                System.out.println("condenser=" + branch + "; selection=" + options.branch());
                if (options.mode().equals("material-closed")) {
                    if (rung(requested, null, false, Policy.FULL_FINE, 128) != null) report.put("status", "SUCCESS");
                    return;
                }
                if (options.mode().equals("single-stage-local")) {
                    if (rung(requested, null, false, Policy.STAGE_LOCAL, 128) != null) report.put("status", "SUCCESS");
                    return;
                }
                V3DryMeshState state = null;
                double stagePressure = options.mode().equals("pressure") ? 150000.0 : requested.topPressurePascal();
                Policy stagePolicy = options.mode().equals("stage-full") ? Policy.FULL_FINE : Policy.STAGE_LOCAL;
                for (int stages : new int[] {4, 8, 15, 30}) {
                    state = rung(input(crude, stagePressure, stages), state, true, stagePolicy, 128);
                    if (state == null) return;
                }
                if (options.mode().equals("pressure")) {
                    double pressure = 150000.0;
                    while (pressure > requested.topPressurePascal()) {
                        pressure = Math.max(requested.topPressurePascal(), pressure - (pressure > 110000.0 ? 10000.0 : 5000.0));
                        state = rung(input(crude, pressure, 30), state, false, Policy.PRESSURE_PREDICTOR, 12);
                        if (state == null) return;
                    }
                }
                report.put("status", "SUCCESS");
            } catch (CancellationException timeout) {
                report.put("status", "TIMEOUT");
                report.put("failure", timeout.toString());
                if (current != null) current.status = "TIMEOUT";
            } catch (RuntimeException failure) {
                report.put("status", "ERROR");
                report.put("failure", failure.toString());
                report.put("failureStack", java.util.Arrays.stream(failure.getStackTrace()).limit(24).map(Object::toString).toList());
                if (current != null) current.status = "ERROR";
            } finally {
                control.finishPhase();
                if (current != null) current.finish(control);
                report.put("elapsedMilliseconds", control.elapsedMilliseconds());
                report.put("terminalPhase", control.phase);
                report.put("checkpointCount", control.checkpoints);
                report.put("maximumCheckpointGapMilliseconds", control.maximumCheckpointGapNanos / 1_000_000.0);
                if (current != null) System.out.println("terminal rung=" + JSON.toJson(current));
            }
        }

        private V3ColumnInput input(V3CrudeFeed crude, double pressurePa, int stages) {
            double[] flows = crude.moleFractions();
            double total = options.feedKmolH() * 1000.0 / 3600.0;
            for (int component = 0; component < flows.length; component++) flows[component] *= total;
            int feedStage = Math.clamp((int) Math.round(stages * 24.0 / 30.0), 1, stages);
            return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                    flows, 638.15, stages, feedStage, pressurePa, 750.0, List.of(
                            new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                            new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                            new V3ColumnSpecification.ReboilerDuty(options.dutyMW() * 1_000_000.0)));
        }

        private V3CondenserPhaseBranch preferredBranch(V3ColumnInput requested) {
            control.checkpoint();
            try {
                V3ColumnInput probeInput = new V3ColumnInput(requested.schemaVersion(), requested.packageId(), requested.assayId(),
                        requested.componentBasis(), requested.feedComponentMolarFlowsMolPerSecond(), requested.feedTemperatureKelvin(),
                        4, 3, requested.topPressurePascal(), requested.stagePressureDropPascal(), requested.specifications());
                V3ColumnProblem problem = V3ColumnProblemResolver.resolve(probeInput, V3CondenserPhaseBranch.TWO_PHASE);
                if (V3OperatingDomainValidator.assess(problem, thermo) instanceof V3OperatingDomainValidator.Assessment.Rejected) {
                    return V3CondenserPhaseBranch.TWO_PHASE;
                }
                V3DryMeshState seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(),
                        V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
                double[] overhead = new double[requested.componentBasis().componentCount()];
                for (int component = 0; component < seed.componentCount(); component++) {
                    overhead[problem.activeComponentBasis().publicIndex(component)] = seed.vaporFlow(1, component);
                }
                control.checkpoint();
                V3FlashResult flash = thermo.flashTP(seed.temperatureKelvin(0), problem.nodePressurePascal(0), overhead, thermo.newWorkspace());
                return flash.phase() == V3FeedPhase.LIQUID ? V3CondenserPhaseBranch.LIQUID_ONLY : V3CondenserPhaseBranch.TWO_PHASE;
            } catch (V3ThermoException | IllegalArgumentException unavailable) {
                report.put("preferredBranchProbeFallback", unavailable.toString());
                return V3CondenserPhaseBranch.TWO_PHASE;
            }
        }

        private V3DryMeshState rung(V3ColumnInput input, V3DryMeshState previous, boolean stageHandoff, Policy policy, int limit) {
            current = new Rung(input, policy, limit, control);
            rungs.add(current);
            System.out.println("START " + current.label);
            control.phase(current.label + "/resolve", current.phaseMilliseconds);
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, branch);
            current.problem = problem;
            current.admission = V3OperatingDomainValidator.assess(problem, thermo);
            if (current.admission instanceof V3OperatingDomainValidator.Assessment.Rejected rejected) {
                throw new IllegalArgumentException(rejected.detail());
            }
            V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
            current.coordinateCount = coordinates.coordinateCount();
            control.phase(current.label + "/seed", current.phaseMilliseconds);
            V3DryMeshState seed;
            if (previous == null) {
                boolean materialClosed = options.mode().equals("material-closed");
                current.seedSource = materialClosed ? "material-closed" : "sequential-material-vle";
                seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(),
                        materialClosed ? V3ColumnInitializer.Mode.MATERIAL_CLOSED : V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
            } else if (stageHandoff) {
                current.seedSource = "interpolated-plus-material-vle";
                seed = interpolate(previous, problem);
                V3SequentialPreconditioner.Result projection = V3BubblePointPreconditioner.INSTANCE.prepare(
                        new V3SequentialPreconditioner.Request(problem, seed, control), thermo, thermo.newWorkspace());
                current.projectionEvidence = projection.evidence();
                current.projectionResult = projection.getClass().getSimpleName();
                if (projection instanceof V3SequentialPreconditioner.Result.Prepared prepared && feasible(problem, prepared.state())) {
                    seed = prepared.state();
                    current.projectionUsed = true;
                }
            } else {
                current.seedSource = "previous-accepted-pressure-state";
                seed = previous;
            }
            control.phase(current.label + "/feed-flash", current.phaseMilliseconds);
            V3FlashResult feed = thermo.flashTP(input.feedTemperatureKelvin(), problem.nodePressurePascal(input.feedStageNumber()),
                    input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
            current.feedPhase = feed.phase().toString();
            current.feedVaporFraction = feed.vaporFraction();
            current.feedMolarEnthalpyJoulesPerMol = feed.molarEnthalpyJoulesPerMol();
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feed.molarEnthalpyJoulesPerMol());
            current.evaluator = evaluator;
            current.coordinates = coordinates;
            current.thermo = thermo;
            current.control = control;
            current.linearDiagnosticLimit = options.linearDiagnostics();
            control.phase(current.label + "/solve", current.phaseMilliseconds);
            V3SimultaneousColumnSolver.Attempt attempt = switch (policy) {
                case STAGE_LOCAL -> V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, coordinates,
                        seed, thermo::newWorkspace, limit, V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE, control, current);
                case FULL_FINE -> V3SimultaneousColumnSolver.solve(problem, evaluator, coordinates, seed, thermo::newWorkspace,
                        V3ConvergenceEvidence.unavailable(), limit, V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE,
                        V3FiniteDifferenceJacobian.DifferenceScale.FINE, control, current);
                case PRESSURE_PREDICTOR -> V3SimultaneousColumnSolver.solveWithOneLocalBlockPredictor(problem, evaluator, coordinates,
                        seed, thermo::newWorkspace, limit, V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE, control, current);
            };
            current.evidence = attempt.evidence();
            current.solverCode = attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure ? failure.code() : "CONVERGED";
            control.phase(current.label + "/audit", current.phaseMilliseconds);
            current.audit = new V3AcceptanceAuditor(problem, thermo, feed.molarEnthalpyJoulesPerMol())
                    .audit(attempt.state(), thermo.newWorkspace(), control);
            boolean accepted = attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged
                    && attempt.evidence().convergenceEvidence().satisfiesGates() && current.audit.accepted();
            current.status = accepted ? "SUCCESS" : "FAILED_RUNG";
            control.phase(current.label + "/extra-condenser-flash", current.phaseMilliseconds);
            captureCondenserFlash(problem, attempt.state());
            control.finishPhase();
            current.finish(control);
            System.out.println("END " + current.label + " status=" + current.status + " ms=" + current.elapsedMilliseconds
                    + " evidence=" + current.evidence + " local=" + current.localAccepted + "/" + current.localRejected
                    + " fd=" + current.freshFiniteDifference + "/" + current.reusedFiniteDifference + " audit=" + current.audit);
            if (!accepted) report.put("status", "FAILED_RUNG");
            return accepted ? attempt.state() : null;
        }

        private void captureCondenserFlash(V3ColumnProblem problem, V3DryMeshState state) {
            int node = problem.topology().condenserNode();
            boolean liquidOnly = problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.LIQUID_ONLY;
            Map<String, Object> evidence = new LinkedHashMap<>();
            current.condenserFlash = evidence;
            evidence.put("input", liquidOnly ? "node0 liquid only, exactly the acceptance-auditor input"
                    : "combined node0 liquid plus vapor component flows; diagnostic overall flash");
            evidence.put("temperatureKelvin", state.temperatureKelvin(node));
            evidence.put("pressurePascal", problem.nodePressurePascal(node));
            double[] flows = new double[problem.input().componentBasis().componentCount()];
            double liquidTotal = 0.0;
            double vaporTotal = 0.0;
            for (int component = 0; component < state.componentCount(); component++) {
                double liquid = state.liquidFlow(node, component);
                double vapor = state.vaporFlow(node, component);
                liquidTotal += liquid;
                vaporTotal += vapor;
                flows[problem.activeComponentBasis().publicIndex(component)] = liquid + (liquidOnly ? 0.0 : vapor);
            }
            evidence.put("nodeLiquidMolS", liquidTotal);
            evidence.put("nodeVaporMolS", vaporTotal);
            try {
                control.checkpoint();
                V3FlashResult flash = thermo.flashTP(state.temperatureKelvin(node), problem.nodePressurePascal(node), flows, thermo.newWorkspace());
                evidence.put("phase", flash.phase());
                evidence.put("vaporFraction", flash.vaporFraction());
                evidence.put("iterations", flash.iterations());
                evidence.put("detail", flash.detail());
                control.checkpoint();
                System.out.println("CONDENSER_FLASH " + current.label + " " + evidence);
            } catch (CancellationException timeout) {
                throw timeout;
            } catch (RuntimeException unavailable) {
                evidence.put("failure", unavailable.toString());
            }
        }
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
            temperatures[node] = source.temperatureKelvin(lower) + fraction * (source.temperatureKelvin(upper) - source.temperatureKelvin(lower));
            for (int component = 0; component < components; component++) {
                liquid[node][component] = source.liquidFlow(lower, component) + fraction * (source.liquidFlow(upper, component) - source.liquidFlow(lower, component));
                vapor[node][component] = source.vaporFlow(lower, component) + fraction * (source.vaporFlow(upper, component) - source.vaporFlow(lower, component));
            }
        }
        temperatures[target.topology().condenserNode()] = 323.15;
        return new V3DryMeshState(target.topology(), components, liquid, vapor, temperatures);
    }

    private static boolean feasible(V3ColumnProblem problem, V3DryMeshState state) {
        for (int node = 0; node < state.nodeCount(); node++) {
            if (!Double.isFinite(state.temperatureKelvin(node)) || state.temperatureKelvin(node) <= 0.0) return false;
            for (int component = 0; component < state.componentCount(); component++) {
                double vapor = state.vaporFlow(node, component);
                double liquid = state.liquidFlow(node, component);
                if (problem.topology().hasVaporPhase(node) ? !Double.isFinite(vapor) || vapor <= 0.0 : vapor != 0.0) return false;
                if (problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)
                        ? !Double.isFinite(liquid) || liquid <= 0.0 : liquid != 0.0) return false;
            }
        }
        return true;
    }

    private static final class Rung implements V3NewtonTrace {
        private final String label;
        private final int stages;
        private final int feedStage;
        private final double pressureKpa;
        private final Policy policy;
        private final int maximumIterations;
        private final Map<String, Double> phaseMilliseconds = new LinkedHashMap<>();
        private final List<Sample> traceEveryTenIterations = new ArrayList<>();
        private transient V3ColumnProblem problem;
        private transient V3MeshResidualEvaluator evaluator;
        private transient V3DryMeshCoordinateMap coordinates;
        private transient V3PengRobinsonThermo thermo;
        private transient Deadline control;
        private transient V3DryMeshState latestState;
        private transient V3MeshResidual latestResidual;
        private transient int linearDiagnosticLimit;
        private final List<Map<String, Object>> linearDiagnostics = new ArrayList<>();
        private final transient long startedNanos = System.nanoTime();
        private final transient long initialCheckpoints;
        private String status = "RUNNING";
        private String seedSource;
        private String projectionResult;
        private V3SequentialPreconditioner.Evidence projectionEvidence;
        private boolean projectionUsed;
        private V3OperatingDomainValidator.Assessment admission;
        private int coordinateCount;
        private String feedPhase;
        private double feedVaporFraction;
        private double feedMolarEnthalpyJoulesPerMol;
        private int localAccepted;
        private int localRejected;
        private int freshFiniteDifference;
        private int reusedFiniteDifference;
        private double elapsedMilliseconds;
        private long checkpointCount;
        private Sample latestTrace;
        private String solverCode;
        private V3SimultaneousColumnSolver.Evidence evidence;
        private V3AcceptanceAudit audit;
        private Map<String, Object> condenserFlash;

        private Rung(V3ColumnInput input, Policy policy, int maximumIterations, Deadline control) {
            stages = input.stageCount();
            feedStage = input.feedStageNumber();
            pressureKpa = input.topPressurePascal() / 1000.0;
            this.policy = policy;
            this.maximumIterations = maximumIterations;
            initialCheckpoints = control.checkpoints;
            label = "stages=" + stages + "/pressure=" + pressureKpa + "kPa/" + policy;
        }

        @Override public void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit) {}

        @Override public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double scaledMerit) {
            latestState = state;
            latestResidual = residual;
            V3MeshResidual.Row dominant = residual.rows().getFirst();
            for (V3MeshResidual.Row row : residual.rows()) {
                if (Math.abs(row.scaledValue()) > Math.abs(dominant.scaledValue())) dominant = row;
            }
            int node = dominant.equation().node();
            int component = dominant.equation().component();
            String componentId = component < 0 ? null
                    : problem.input().componentBasis().componentId(problem.activeComponentBasis().publicIndex(component));
            latestTrace = new Sample(iteration, (System.nanoTime() - startedNanos) / 1_000_000.0,
                    residual.maximumAbsoluteScaledResidual(), scaledMerit, dominant.equation().toString(),
                    dominant.physicalValue(), dominant.scaledValue(), componentId, state.temperatureKelvin(node),
                    component < 0 ? null : state.liquidFlow(node, component), component < 0 ? null : state.vaporFlow(node, component),
                    localAccepted, localRejected, freshFiniteDifference, reusedFiniteDifference);
            if (iteration % 10 == 0) {
                traceEveryTenIterations.add(latestTrace);
                System.out.printf(Locale.ROOT, "TRACE %s iteration=%d ms=%.3f residual=%.9g merit=%.9g local=%d/%d fd=%d/%d dominant=%s%n",
                        label, iteration, latestTrace.elapsedMilliseconds(), latestTrace.maximumScaledResidual(), scaledMerit,
                        localAccepted, localRejected, freshFiniteDifference, reusedFiniteDifference, latestTrace.dominantEquation());
            }
        }

        @Override public void localBlockDirection(int iteration, boolean accepted) {
            if (accepted) localAccepted++; else localRejected++;
        }

        @Override public void finiteDifferenceJacobian(int iteration, boolean reused) {
            if (reused) reusedFiniteDifference++; else freshFiniteDifference++;
            if (!reused && iteration % 10 == 0 && iteration / 10 < linearDiagnosticLimit
                    && linearDiagnostics.size() < linearDiagnosticLimit) diagnoseLinearSystem(iteration);
        }

        private void diagnoseLinearSystem(int iteration) {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("iteration", iteration);
            diagnostic.put("status", "RUNNING");
            linearDiagnostics.add(diagnostic);
            long diagnosticStarted = System.nanoTime();
            try {
                control.phase(label + "/extra-linear-diagnostic", phaseMilliseconds);
                V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluate(
                        evaluator, coordinates, latestState, thermo::newWorkspace,
                        V3FiniteDifferenceJacobian.DifferenceScale.FINE, control);
                double[][] values = jacobian.values();
                int lowerBandwidth = 0;
                int upperBandwidth = 0;
                double maximumOffBand = 0.0;
                double[] columnMaxima = new double[values.length];
                // Reproduce the production scalar-band conversion threshold, not a different linear solver.
                for (int row = 0; row < values.length; row++) {
                    control.checkpoint();
                    for (int column = 0; column < values.length; column++) {
                        double magnitude = Math.abs(values[row][column]);
                        columnMaxima[column] = Math.max(columnMaxima[column], magnitude);
                        if (Math.abs(jacobian.equations().get(row).node() - jacobian.unknowns().get(column).node()) > 1) {
                            maximumOffBand = Math.max(maximumOffBand, magnitude);
                        }
                        if (magnitude <= 1.0e-10) continue;
                        lowerBandwidth = Math.max(lowerBandwidth, row - column);
                        upperBandwidth = Math.max(upperBandwidth, column - row);
                    }
                }
                List<String> nearZeroColumns = new ArrayList<>();
                int nearZeroColumnCount = 0;
                double minimumColumnMaximum = Double.POSITIVE_INFINITY;
                for (int column = 0; column < columnMaxima.length; column++) {
                    minimumColumnMaximum = Math.min(minimumColumnMaximum, columnMaxima[column]);
                    if (columnMaxima[column] <= 1.0e-10) {
                        nearZeroColumnCount++;
                        if (nearZeroColumns.size() < 32) nearZeroColumns.add(jacobian.unknowns().get(column).toString());
                    }
                }
                diagnostic.put("maximumOffBandMagnitude", maximumOffBand);
                diagnostic.put("minimumColumnMaximumMagnitude", minimumColumnMaximum);
                diagnostic.put("nearZeroColumnCount", nearZeroColumnCount);
                diagnostic.put("firstNearZeroColumns", nearZeroColumns);
                if (maximumOffBand > 1.0e-10) {
                    diagnostic.put("status", "JACOBIAN_BAND_STRUCTURE");
                    return;
                }
                V3BandedMatrix matrix = new V3BandedMatrix(values.length, lowerBandwidth, upperBandwidth);
                double[] rhs = new double[values.length];
                for (int row = 0; row < values.length; row++) {
                    control.checkpoint();
                    rhs[row] = -latestResidual.rows().get(row).scaledValue();
                    for (int column = Math.max(0, row - lowerBandwidth); column <= Math.min(values.length - 1, row + upperBandwidth); column++) {
                        matrix.set(row, column, values[row][column]);
                    }
                }
                V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(matrix, rhs);
                if (result instanceof V3BandedPivotedSolver.Result.Failure failure) {
                    diagnostic.put("status", failure.code().toString());
                    diagnostic.put("linearFailure", failure);
                } else if (result instanceof V3BandedPivotedSolver.Result.Success success) {
                    diagnostic.put("status", "SUCCESS");
                    diagnostic.put("linearSuccess", Map.of("backwardError", success.backwardError(),
                            "minimumPivot", success.minimumPivotMagnitude(), "maximumPivot", success.maximumPivotMagnitude(),
                            "pivotSwaps", success.pivotSwaps(), "pivotGrowth", success.pivotGrowth()));
                }
            } catch (CancellationException timeout) {
                diagnostic.put("status", "TIMEOUT");
                throw timeout;
            } catch (RuntimeException unavailable) {
                diagnostic.put("status", "DIAGNOSTIC_UNAVAILABLE");
                diagnostic.put("failure", unavailable.toString());
            } finally {
                diagnostic.put("elapsedMilliseconds", (System.nanoTime() - diagnosticStarted) / 1_000_000.0);
                System.out.println("LINEAR_DIAGNOSTIC " + label + " " + diagnostic);
                // On timeout leave the exact diagnostic phase visible to the outer report.
                if (!"TIMEOUT".equals(diagnostic.get("status"))) control.phase(label + "/solve", phaseMilliseconds);
            }
        }

        private void finish(Deadline control) {
            elapsedMilliseconds = (System.nanoTime() - startedNanos) / 1_000_000.0;
            checkpointCount = control.checkpoints - initialCheckpoints;
        }
    }

    private record Sample(int iteration, double elapsedMilliseconds, double maximumScaledResidual, double scaledMerit,
                          String dominantEquation, double dominantPhysicalResidual, double dominantScaledResidual,
                          String componentId, double temperatureKelvin, Double liquidFlowMolS, Double vaporFlowMolS,
                          int localAccepted, int localRejected, int freshFiniteDifference, int reusedFiniteDifference) {}

    /** Same-thread cooperative deadline, including bounded checkpoint-gap and phase observations. */
    private static final class Deadline implements V3SolveControl {
        private final long startedNanos = System.nanoTime();
        private final long budgetNanos;
        private long lastCheckpointNanos = startedNanos;
        private long maximumCheckpointGapNanos;
        private long checkpoints;
        private String phase = "setup";
        private long phaseStartedNanos;
        private Map<String, Double> phaseSink;

        private Deadline(double seconds) { budgetNanos = (long) (seconds * 1_000_000_000.0); }

        @Override public void checkpoint() {
            long now = System.nanoTime();
            checkpoints++;
            maximumCheckpointGapNanos = Math.max(maximumCheckpointGapNanos, now - lastCheckpointNanos);
            lastCheckpointNanos = now;
            if (now - startedNanos >= budgetNanos) throw new CancellationException("deadline exceeded in " + phase);
        }

        private void phase(String next, Map<String, Double> sink) {
            finishPhase();
            phase = next;
            phaseSink = sink;
            phaseStartedNanos = System.nanoTime();
            checkpoint();
        }

        private void finishPhase() {
            if (phaseSink != null) {
                phaseSink.merge(phase, (System.nanoTime() - phaseStartedNanos) / 1_000_000.0, Double::sum);
                phaseSink = null;
            }
        }

        private double elapsedMilliseconds() { return (System.nanoTime() - startedNanos) / 1_000_000.0; }
    }
}
