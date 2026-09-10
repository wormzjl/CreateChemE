package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic-only full-route profiling and controlled direct-solve seed experiments. */
public final class V3ContinuationInvestigationProbe {
    private V3ContinuationInvestigationProbe() {}
    private static final List<String> CASES = List.of("Default", "DefaultPressureLow5", "DefaultPressureLow10",
            "DefaultRefluxHigh10", "DefaultTemperatureLow5");

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--export-seed")) {
            var captures = new LinkedHashMap<String, V3ContinuationProfile.Saved>();
            full("Default", V3DiagnosticFixtures.shippedDefault(), captures);
            var state = captures.get("Default").state();
            double[][] liquid = new double[state.nodeCount()][state.componentCount()];
            double[][] vapor = new double[state.nodeCount()][state.componentCount()];
            double[] temperatures = new double[state.nodeCount()], water = new double[state.nodeCount()];
            for (int node = 0; node < state.nodeCount(); node++) {
                temperatures[node] = state.temperatureKelvin(node);
                water[node] = state.freeWaterFlow(node);
                for (int component = 0; component < state.componentCount(); component++) {
                    liquid[node][component] = state.liquidFlow(node, component);
                    vapor[node][component] = state.vaporFlow(node, component);
                }
            }
            Files.writeString(Path.of(args[1]), new GsonBuilder().create().toJson(new SeedData(liquid, vapor, temperatures, water)));
            return;
        }
        if (args[0].equals("--witness")) {
            SeedData data = new GsonBuilder().create().fromJson(Files.readString(Path.of(args[1])), SeedData.class);
            var problem = V3ColumnProblemResolver.resolve(V3DiagnosticFixtures.shippedDefault(), V3CondenserPhaseBranch.LIQUID_ONLY);
            var source = new V3ContinuationProfile.Saved(problem, new V3DryMeshState(problem.topology(),
                    problem.activeComponentBasis().componentCount(), data.liquid(), data.vapor(), data.temperatures(), data.water()), null);
            var rows = new ArrayList<Map<String, Object>>();
            for (var entry : V3DiagnosticFixtures.largeDefaultPerturbations().entrySet()) {
                rows.add(direct(entry.getKey(), entry.getValue(), source, null));
            }
            Files.writeString(Path.of(args[2]), new GsonBuilder().setPrettyPrinting().create().toJson(rows));
            return;
        }
        Path report = Path.of(args[0]);
        int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        var inputs = V3DiagnosticFixtures.largeDefaultPerturbations();
        var fullRuns = new ArrayList<Map<String, Object>>();
        var directRuns = new ArrayList<Map<String, Object>>();
        var references = new LinkedHashMap<String, V3ContinuationProfile.Saved>();
        full("warmup", inputs.get("Default"), references);
        references.clear();
        for (int repeat = 0; repeat < repetitions; repeat++) {
            for (String label : CASES) {
                var row = full(label, inputs.get(label), references);
                row.put("repeat", repeat);
                fullRuns.add(row);
                write(report, fullRuns, directRuns);
            }
        }
        var source = references.get("Default");
        if (source == null) throw new IllegalStateException("Default did not produce an accepted seed");
        for (int repeat = 0; repeat < repetitions; repeat++) {
            for (String label : CASES) {
                for (boolean nearby : new boolean[] {false, true}) {
                    var row = direct(label, inputs.get(label), nearby ? source : null, references.get(label));
                    row.put("repeat", repeat);
                    directRuns.add(row);
                    write(report, fullRuns, directRuns);
                }
            }
        }
    }

    private record SeedData(double[][] liquid, double[][] vapor, double[] temperatures, double[] water) {}

    private static Map<String, Object> full(String label, V3ColumnInput input,
            Map<String, V3ContinuationProfile.Saved> references) {
        var session = V3ContinuationProfile.start(label);
        V3ColumnOutcome outcome;
        long deadline = System.nanoTime() + 45_000_000_000L;
        try (var scope = V3ContinuationProfile.enter("root", null, label, 0, "")) {
            outcome = V3ColumnCalculator.calculate(input, deadline(deadline), 0.0, 0.0);
        }
        var result = session.finish();
        result.put("case", label);
        result.put("input", input);
        result.put("diagnostics", outcome.diagnostics());
        if (outcome instanceof V3ColumnOutcome.Success success) {
            result.put("kind", "SUCCESS");
            result.put("streams", success.result().streams());
            result.put("audit", success.result().acceptanceAudit());
            result.put("evidence", success.result().convergenceEvidence());
            result.put("duty", success.result().dutyLedger().orElse(null));
            if (session.saved == null) throw new IllegalStateException("Instrumented publication hook did not run");
            references.put(label, session.saved);
        } else {
            result.put("kind", "FAILURE");
            result.put("failure", outcome.toString());
        }
        System.out.println("full " + label + " " + result.get("kind"));
        return result;
    }

    private static Map<String, Object> direct(String label, V3ColumnInput input,
            V3ContinuationProfile.Saved source, V3ContinuationProfile.Saved reference) throws Exception {
        Class<?> policyType = Class.forName(V3ColumnCalculator.class.getName() + "$SolvePolicy");
        var off = policyType.getDeclaredField("OFF");
        off.setAccessible(true);
        Class<?> jacobianType = Class.forName(V3ColumnCalculator.class.getName() + "$ContinuationJacobianPolicy");
        Object localPolicy = java.util.Arrays.stream(jacobianType.getEnumConstants())
                .filter(value -> value.toString().equals("STAGE_LOCAL_BLOCKS")).findFirst().orElseThrow();
        Method solve = V3ColumnCalculator.class.getDeclaredMethod("solveSingleProblem", V3ColumnProblem.class,
                V3PengRobinsonThermo.class, V3DryMeshState.class, V3SolveControl.class, String.class,
                jacobianType, int.class, policyType, V3SimultaneousColumnSolver.RungBudget.class);
        solve.setAccessible(true);
        String mode = source == null ? "direct-cold" : "direct-nearby-default";
        var session = V3ContinuationProfile.start(label + "/" + mode);
        var result = new LinkedHashMap<String, Object>();
        result.put("case", label);
        result.put("mode", mode);
        result.put("input", input);
        long end = System.nanoTime() + 45_000_000_000L;
        try (var scope = V3ContinuationProfile.enter("root", null, label + "/" + mode, 128, "full")) {
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            var problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.LIQUID_ONLY);
            V3DryMeshState seed = source == null
                    ? V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(),
                            V3ColumnInitializer.Mode.MATERIAL_CLOSED).state()
                    : source.state();
            // Neither experiment receives a convergence certificate from the source state.
            Object pass = solve.invoke(null, problem, thermo, seed, deadline(end), mode, localPolicy,
                    V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS, off.get(null),
                    V3SimultaneousColumnSolver.RungBudget.DEFAULT);
            var attempt = (V3SimultaneousColumnSolver.Attempt) read(pass, "attempt");
            var audit = (V3AcceptanceAudit) read(pass, "audit");
            var finalProblem = (V3ColumnProblem) read(read(pass, "prepared"), "problem");
            double feedH = (double) read(pass, "feedMolarEnthalpyJoulesPerMol");
            result.put("audit", audit);
            result.put("evidence", attempt.evidence().convergenceEvidence());
            result.put("finalResidual", attempt.evidence().maximumScaledResidual());
            result.put("termination", attempt.evidence().termination());
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged && audit.accepted()
                    && attempt.evidence().convergenceEvidence().satisfiesGates(1e-8)) {
                String revision = V3ColumnCalculator.formulationRevision(input, 0, 1e-8);
                var digest = V3InputDigest.of(finalProblem, revision, thermo.datasetRevision(),
                        V3ColumnCalculator.assumptionsRevision(input), 0, 1e-8);
                var accepted = V3ColumnResult.accepted(finalProblem, digest, audit,
                        attempt.evidence().convergenceEvidence(), attempt.state(), thermo, revision,
                        V3ColumnDutyLedger.fromAccepted(finalProblem, attempt.state(), thermo, feedH));
                result.put("kind", "SUCCESS");
                result.put("streams", accepted.streams());
                result.put("duty", accepted.dutyLedger().orElse(null));
                if (reference != null) result.put("differenceFromColdResult", difference(reference.result(), accepted));
            } else {
                result.put("kind", "FAILURE");
                result.put("failureCode", attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure
                        ? failure.code() : "ACCEPTANCE_AUDIT_FAILURE");
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            result.put("kind", cause instanceof java.util.concurrent.CancellationException ? "DEADLINE" : "ERROR");
            result.put("error", cause.toString());
        } catch (RuntimeException failure) {
            result.put("kind", "ERROR");
            result.put("error", failure.toString());
        }
        result.putAll(session.finish());
        System.out.println(mode + " " + label + " " + result.get("kind"));
        return result;
    }

    private static Object read(Object record, String accessor) throws Exception {
        var method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static Map<String, Double> difference(V3ColumnResult baseline, V3ColumnResult candidate) {
        double maxTemperature = 0, maxFlowRelative = 0, maxComposition = 0;
        for (var a : baseline.streams()) {
            var b = candidate.streams().stream().filter(value -> value.streamId().equals(a.streamId())).findFirst().orElseThrow();
            maxTemperature = Math.max(maxTemperature, Math.abs(a.temperatureKelvin() - b.temperatureKelvin()));
            maxFlowRelative = Math.max(maxFlowRelative, Math.abs(a.molarFlowMolPerSecond() - b.molarFlowMolPerSecond())
                    / Math.max(1, Math.abs(a.molarFlowMolPerSecond())));
            for (int i = 0; i < a.moleFractions().size(); i++) {
                maxComposition = Math.max(maxComposition, Math.abs(a.moleFractions().get(i).moleFraction()
                        - b.moleFractions().get(i).moleFraction()));
            }
        }
        return Map.of("maximumTemperatureDifferenceK", maxTemperature, "maximumFlowRelativeDifference", maxFlowRelative,
                "maximumMoleFractionDifference", maxComposition);
    }

    private static V3SolveControl deadline(long end) {
        return () -> { if (System.nanoTime() > end) throw new java.util.concurrent.CancellationException("Diagnostic deadline"); };
    }

    private static void write(Path path, List<?> full, List<?> direct) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("full", full, "direct", direct)));
    }
}
