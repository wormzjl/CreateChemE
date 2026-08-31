package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Serial numerical counterfactuals only: does not change production limits, physics, or acceptance. */
public final class V3LowPressureProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final Path output;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> experiments = new ArrayList<>();
    private final Deadline control = new Deadline();
    private final V3PressureContinuationAccess.Session session;
    private final String mode;

    private V3LowPressureProbe(Path output, String mode) {
        this.output = output;
        this.mode = mode;
        session = new V3PressureContinuationAccess.Session(V3TimeoutBenchmark.input(
                new V3TimeoutBenchmark.Scenario("50-diagnostic", 50, 2610.7, 8, 0)), control,
                mode.equals("--truncated") ? 1e-6 : 0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || !args[0].startsWith("--report=")
                || (args.length == 2 && !List.of("--smallSteps", "--adaptive", "--truncated").contains(args[1]))) throw new IllegalArgumentException("--report=path [--smallSteps|--adaptive|--truncated] required");
        Path output = Path.of(args[0].substring("--report=".length())).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite " + output);
        new V3LowPressureProbe(output, args.length == 2 ? args[1] : "--all").run();
    }

    private void run() throws Exception {
        report.put("startedUtc", Instant.now().toString());
        report.put("scope", "Diagnostic only; no client lifecycle control or background-workload isolation. No clean timing claims. All counterfactuals use unchanged production equations, EOS and strict acceptance, with truncation OFF. Prefix branch selected using original requested 50 kPa input.");
        if (mode.equals("--truncated")) report.put("scope", "Diagnostic-only actual positive-cutoff 1e-6 pressure chain, without the outer untruncated fallback. No production edits or clean timing claims; original requested 50 kPa preferred branch.");
        report.put("productionHashesBefore", hashes());
        report.put("experiments", experiments);
        try {
            if (mode.equals("--truncated")) {
                control.reset();
                var truncated = session.prefix(50000);
                capture("truncated-50-prefix-terminal", truncated);
                traced("replay-truncated-terminal", truncated, truncated.attempt().evidence().iterations(), Policy.PREDICTOR, true);
                traced("truncated-terminal-128", truncated, 128, Policy.PREDICTOR, false);
                report.put("status", "COMPLETE");
                return;
            }
            control.reset();
            var accepted60 = session.prefix(60000);
            capture("production-prefix-60", accepted60);
            if (!accepted60.accepted()) throw new IllegalStateException("60 kPa prefix not accepted");
            if (mode.equals("--adaptive")) {
                adaptive(accepted60);
                report.put("status", "COMPLETE");
                return;
            }
            if (mode.equals("--smallSteps")) {
                ladder(accepted60, 500, 64, 128);
                ladder(accepted60, 125, 64, 128);
                report.put("status", "COMPLETE");
                return;
            }
            control.reset();
            var predictor = session.predict(accepted60, 55000, 12);
            capture("production-55-predictor-12", predictor);
            control.reset();
            var recovery = session.recover(accepted60, 55000, accepted60.state(), 24);
            capture("production-55-projected-24", recovery);
            traced("replay-predictor-12", predictor, 12, Policy.PREDICTOR, true);
            traced("replay-projected-24", recovery, 24, Policy.PREDICTOR, true);
            traced("predictor-128", predictor, 128, Policy.PREDICTOR, false);
            traced("projected-128", recovery, 128, Policy.PREDICTOR, false);
            traced("full-fine-128", predictor, 128, Policy.FULL_FINE, false);
            traced("full-coarse-128", predictor, 128, Policy.FULL_COARSE, false);
            control.reset();
            var projectedFailed = session.recover(accepted60, 55000, predictor.state(), 128);
            capture("project-failed-predictor-128", projectedFailed);
            ladder(accepted60, 2500);
            ladder(accepted60, 1000);
            report.put("status", "COMPLETE");
        } catch (RuntimeException failure) {
            report.put("status", "ERROR");
            report.put("failure", failure.toString());
            report.put("failureStack", java.util.Arrays.stream(failure.getStackTrace()).limit(20).map(Object::toString).toList());
            throw failure;
        } finally {
            var after = hashes();
            report.put("productionHashesAfter", after);
            report.put("productionSourcesUnchanged", after.equals(report.get("productionHashesBefore")));
            report.put("finishedUtc", Instant.now().toString());
            save();
        }
    }

    private void adaptive(V3PressureContinuationAccess.CapturedPass accepted60) throws IOException {
        var previous = accepted60;
        double pressure = 60000, step = 500;
        int trials = 0;
        while (pressure > 50000 && trials < 80 && step >= 1) {
            trials++;
            double target = Math.max(50000, pressure - step);
            control.reset();
            var next = session.correct(session.predict(previous, target, 64));
            capture("adaptive-trial-" + trials + "-pressure-" + target, next);
            if (next.accepted()) {
                previous = next;
                pressure = target;
                if (next.attempt().evidence().iterations() < 10) step = Math.min(500, step * 1.5);
            } else {
                step *= 0.5;
            }
        }
        report.put("adaptiveReachedPressurePa", pressure);
        report.put("adaptiveNextStepPa", step);
        report.put("adaptiveTrials", trials);
    }

    private void ladder(V3PressureContinuationAccess.CapturedPass accepted60, double step) throws IOException {
        ladder(accepted60, step, 12, 24);
    }

    private void ladder(V3PressureContinuationAccess.CapturedPass accepted60, double step, int predictorLimit, int recoveryLimit) throws IOException {
        var previous = accepted60;
        for (double pressure = 60000 - step; pressure >= 50000; pressure -= step) {
            control.reset();
            var next = session.correct(session.predict(previous, pressure, predictorLimit));
            capture("step-" + step + "-pressure-" + pressure + "-predictor-" + predictorLimit, next);
            if (!next.accepted()) {
                next = session.correct(session.recover(previous, pressure, previous.state(), recoveryLimit));
                capture("step-" + step + "-pressure-" + pressure + "-projected-" + recoveryLimit, next);
            }
            if (!next.accepted()) return;
            previous = next;
        }
    }

    private void capture(String label, V3PressureContinuationAccess.CapturedPass pass) throws IOException {
        var result = summary(label, pass.problem(), pass.attempt(), pass.audit());
        result.put("path", pass.path());
        result.put("feedEnthalpy", pass.feedEnthalpy());
        result.put("seed", state(pass.seed()));
        result.put("terminalState", state(pass.state()));
        result.put("terminalNodes", nodes(pass.problem(), pass.state()));
        experiments.add(result);
        System.out.println(label + " accepted=" + pass.accepted() + " residual=" + pass.attempt().evidence().maximumScaledResidual());
        save();
    }

    private void traced(String label, V3PressureContinuationAccess.CapturedPass source, int limit, Policy policy,
                        boolean requireMatch) throws IOException {
        control.reset();
        V3ColumnProblem problem = source.problem();
        var thermo = session.thermo();
        var evaluator = new V3MeshResidualEvaluator(problem, thermo, source.feedEnthalpy());
        var coordinates = new V3DryMeshCoordinateMap(problem);
        var trace = new Trace(label, problem, thermo);
        V3SimultaneousColumnSolver.Attempt attempt = switch (policy) {
            case PREDICTOR -> V3SimultaneousColumnSolver.solveWithOneLocalBlockPredictor(problem, evaluator, coordinates,
                    source.seed(), thermo::newWorkspace, limit, 1e-8, control, trace);
            case FULL_FINE, FULL_COARSE -> V3SimultaneousColumnSolver.solve(problem, evaluator, coordinates,
                    source.seed(), thermo::newWorkspace, V3ConvergenceEvidence.unavailable(), limit, 1e-8,
                    policy == Policy.FULL_FINE ? V3FiniteDifferenceJacobian.DifferenceScale.FINE
                            : V3FiniteDifferenceJacobian.DifferenceScale.COARSE, control, trace);
        };
        var audit = new V3AcceptanceAuditor(problem, thermo, source.feedEnthalpy()).audit(attempt.state(), thermo.newWorkspace(), control);
        var result = summary(label, problem, attempt, audit);
        result.put("policy", policy);
        result.put("maximumIterations", limit);
        result.put("trace", trace.samples);
        result.put("terminalState", state(attempt.state()));
        result.put("terminalNodes", nodes(problem, attempt.state()));
        boolean exactMatch = state(attempt.state()).equals(state(source.state())) && attempt.evidence().equals(source.attempt().evidence());
        result.put("matchesProductionPassExactly", exactMatch);
        experiments.add(result);
        System.out.println(label + " accepted=" + result.get("accepted") + " residual=" + attempt.evidence().maximumScaledResidual() + " productionMatch=" + exactMatch);
        save();
        if (requireMatch && !exactMatch) throw new IllegalStateException("Replay differs from production: " + label);
    }

    private static Map<String, Object> summary(String label, V3ColumnProblem problem,
            V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit) {
        var result = new LinkedHashMap<String, Object>();
        result.put("label", label);
        result.put("pressurePa", problem.input().topPressurePascal());
        result.put("branch", problem.topology().condenserPhaseBranch());
        result.put("removedPoints", problem.truncationSupport().truncatedPointCount());
        result.put("solverStatus", attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure f ? f.code() : "CONVERGED");
        result.put("accepted", attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged && audit.accepted());
        result.put("evidence", attempt.evidence());
        result.put("audit", audit);
        return result;
    }

    private List<Map<String, Object>> nodes(V3ColumnProblem problem, V3DryMeshState state) {
        var nodes = new ArrayList<Map<String, Object>>();
        for (int node = 0; node < state.nodeCount(); node++) nodes.add(node(problem, state, session.thermo(), node));
        return nodes;
    }

    private static Map<String, Object> node(V3ColumnProblem problem, V3DryMeshState state, V3PengRobinsonThermo thermo, int node) {
        var result = new LinkedHashMap<String, Object>();
        result.put("node", node);
        result.put("temperatureK", state.temperatureKelvin(node));
        result.put("pressurePa", problem.nodePressurePascal(node));
        double[] x = new double[thermo.componentBasis().componentCount()];
        double[] y = new double[x.length];
        double sumL = 0, sumV = 0;
        for (int component = 0; component < state.componentCount(); component++) {
            int publicIndex = problem.activeComponentBasis().publicIndex(component);
            x[publicIndex] = state.liquidFlow(node, component);
            y[publicIndex] = state.vaporFlow(node, component);
            sumL += x[publicIndex]; sumV += y[publicIndex];
        }
        result.put("liquidTotal", sumL); result.put("vaporTotal", sumV);
        if (sumL > 0) for (int i = 0; i < x.length; i++) x[i] /= sumL;
        if (sumV > 0) for (int i = 0; i < y.length; i++) y[i] /= sumV;
        result.put("x", x); result.put("y", y);
        try {
            if (sumL > 0) result.put("liquidEOS", eos(thermo.fugacity(state.temperatureKelvin(node), problem.nodePressurePascal(node), x, V3Phase.LIQUID, thermo.newWorkspace())));
            if (sumV > 0) result.put("vaporEOS", eos(thermo.fugacity(state.temperatureKelvin(node), problem.nodePressurePascal(node), y, V3Phase.VAPOR, thermo.newWorkspace())));
        } catch (IllegalArgumentException | com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException unavailable) {
            result.put("EOSFailure", unavailable.toString());
        }
        return result;
    }

    private static Map<String, Object> eos(V3FugacityResult result) {
        return Map.of("Z", result.compressibilityFactor(), "roots", result.physicalRootCount(), "separation", result.rootSeparation());
    }

    private static List<List<Double>> state(V3DryMeshState state) {
        List<List<Double>> rows = new ArrayList<>();
        for (int node = 0; node < state.nodeCount(); node++) {
            List<Double> row = new ArrayList<>(); row.add(state.temperatureKelvin(node));
            for (int component = 0; component < state.componentCount(); component++) {
                row.add(state.liquidFlow(node, component)); row.add(state.vaporFlow(node, component));
            }
            rows.add(row);
        }
        return rows;
    }

    private final class Trace implements V3NewtonTrace {
        private final String label;
        private final V3ColumnProblem problem;
        private final V3PengRobinsonThermo thermo;
        private final List<Map<String, Object>> samples = new ArrayList<>();
        private int localAccepted, localRejected, fresh, reused;
        private Trace(String label, V3ColumnProblem problem, V3PengRobinsonThermo thermo) {
            this.label = label; this.problem = problem; this.thermo = thermo;
        }
        @Override public void sampledIteration(int iteration, V3MeshResidual residual, double merit) {}
        @Override public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double merit) {
            var sample = new LinkedHashMap<String, Object>();
            sample.put("iteration", iteration); sample.put("merit", merit);
            sample.put("maximumResidual", residual.maximumAbsoluteScaledResidual());
            var maxima = new LinkedHashMap<String, Double>();
            double nonPc12 = 0;
            for (var row : residual.rows()) {
                maxima.merge(row.equation().family().name(), Math.abs(row.scaledValue()), Math::max);
                int c = row.equation().component();
                if (c < 0 || !problem.input().componentBasis().componentId(problem.activeComponentBasis().publicIndex(c)).equals("PC12")) nonPc12 = Math.max(nonPc12, Math.abs(row.scaledValue()));
            }
            sample.put("familyMaxima", maxima); sample.put("nonPc12Maximum", nonPc12);
            sample.put("dominantRows", residual.rows().stream().sorted(Comparator.comparingDouble((V3MeshResidual.Row row) -> Math.abs(row.scaledValue())).reversed()).limit(5).toList());
            sample.put("node18", node(problem, state, thermo, 18));
            sample.put("directions", Map.of("localAccepted", localAccepted, "localRejected", localRejected, "freshFD", fresh, "reusedFD", reused));
            samples.add(sample);
            if (iteration % 20 == 0) System.out.println(label + " iteration=" + iteration + " residual=" + residual.maximumAbsoluteScaledResidual() + " merit=" + merit);
        }
        @Override public void localBlockDirection(int iteration, boolean accepted) { if (accepted) localAccepted++; else localRejected++; }
        @Override public void finiteDifferenceJacobian(int iteration, boolean reuse) { if (reuse) reused++; else fresh++; }
    }

    private static Map<String, String> hashes() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (var files = Files.walk(Path.of("src/main/java/com/wormzjl/createcheme/science/column/v3"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                values.put(path.toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return values;
    }
    private void save() throws IOException { Files.createDirectories(output.getParent()); Files.writeString(output, JSON.toJson(report)); }
    private enum Policy { PREDICTOR, FULL_FINE, FULL_COARSE }
    private static final class Deadline implements V3SolveControl {
        private long expires = System.nanoTime() + 180_000_000_000L;
        void reset() { expires = System.nanoTime() + 180_000_000_000L; }
        @Override public void checkpoint() { if (System.nanoTime() >= expires || Thread.currentThread().isInterrupted()) throw new CancellationException("Diagnostic attempt deadline"); }
    }
}
