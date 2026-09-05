package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * JSONL subprocess worker for the V3 cold-core benchmark.  This deliberately lives in test
 * sources: it is an experiment harness and must never become calculator state or production API.
 *
 * <p>Arguments are {@code manifestPath revisionLabel workerId mode}.  Every stdout line is one
 * JSON object so a supervisor can safely own process lifecycle and result persistence.</p>
 */
public final class V3ColdCoreBenchmarkWorker {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final JsonObject manifest;
    private final String manifestPath;
    private final String revision;
    private final String workerId;
    private final String mode;
    private final Map<String, JsonObject> cases;
    private final int defaultDeadlineSeconds;

    private V3ColdCoreBenchmarkWorker(Path manifestPath, String revision, String workerId, String mode) throws IOException {
        this.manifestPath = manifestPath.toAbsolutePath().normalize().toString();
        this.manifest = JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
        this.revision = requireLabel(revision, "revisionLabel");
        this.workerId = requireLabel(workerId, "workerId");
        this.mode = requireLabel(mode, "mode");
        this.cases = indexCases(manifest.getAsJsonArray("cases"));
        this.defaultDeadlineSeconds = manifest.getAsJsonObject("execution").get("calculatorDeadlineSeconds").getAsInt();
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            emit(error("BAD_ARGUMENTS", "Expected positional arguments: manifestPath revisionLabel workerId mode"));
            System.exit(2);
            return;
        }
        try {
            V3ColdCoreBenchmarkWorker worker = new V3ColdCoreBenchmarkWorker(Path.of(args[0]), args[1], args[2], args[3]);
            worker.run();
        } catch (Throwable failure) {
            emit(error("WORKER_STARTUP_FAILURE", describe(failure)));
            System.exit(3);
        }
    }

    private void run() throws IOException {
        emit(ready());
        try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    if ("shutdown".equals(line.trim())) {
                        stopped();
                        return;
                    }
                    JsonObject command = JsonParser.parseString(line).getAsJsonObject();
                    String name = string(command, "command");
                    if ("shutdown".equals(name)) {
                        stopped();
                        return;
                    }
                    if (!"solve".equals(name)) {
                        emit(error("UNKNOWN_COMMAND", "Unsupported command: " + name));
                        continue;
                    }
                    solve(command);
                } catch (Throwable malformed) {
                    emit(error("COMMAND_FAILURE", describe(malformed)));
                }
            }
        }
    }

    private void stopped() {
        JsonObject stopped = new JsonObject();
        stopped.addProperty("type", "STOPPED");
        stopped.addProperty("workerId", workerId);
        emit(stopped);
    }

    private JsonObject ready() {
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "READY");
        ready.addProperty("revision", revision);
        ready.addProperty("workerId", workerId);
        ready.addProperty("mode", mode);
        ready.addProperty("manifestPath", manifestPath);
        ready.addProperty("manifestSchemaVersion", manifest.get("schemaVersion").getAsInt());
        JsonObject dataset = manifest.getAsJsonObject("dataset");
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(string(dataset, "packageId"));
        V3CrudeFeed feed = thermo.crudeFeed(string(dataset, "assayId"));
        requireEquals(string(dataset, "expectedDatasetRevision"), thermo.datasetRevision(), "dataset revision");
        JsonObject data = new JsonObject();
        data.addProperty("packageId", thermo.packageId());
        data.addProperty("assayId", feed.assayId());
        data.addProperty("datasetRevision", thermo.datasetRevision());
        data.add("componentIds", strings(feed.componentBasis().componentIds()));
        ready.add("dataset", data);
        JsonObject jvm = new JsonObject();
        jvm.addProperty("javaVersion", System.getProperty("java.version"));
        jvm.addProperty("javaVendor", System.getProperty("java.vendor"));
        jvm.addProperty("vmName", System.getProperty("java.vm.name"));
        jvm.addProperty("vmVersion", System.getProperty("java.vm.version"));
        ready.add("jvm", jvm);
        JsonObject host = new JsonObject();
        host.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        java.lang.management.OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
            host.addProperty("totalPhysicalMemoryBytes", extended.getTotalMemorySize());
            host.addProperty("freePhysicalMemoryBytes", extended.getFreeMemorySize());
        } else {
            host.add("totalPhysicalMemoryBytes", JsonNull.INSTANCE);
            host.add("freePhysicalMemoryBytes", JsonNull.INSTANCE);
        }
        ready.add("host", host);
        MetricSnapshot metrics = MetricSnapshot.capture();
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("threadCpuTimeSupported", metrics.cpuSupported);
        capabilities.addProperty("threadAllocatedBytesSupported", metrics.allocationSupported);
        capabilities.addProperty("gcMetricsSupported", metrics.gcSupported);
        ready.add("metrics", capabilities);
        return ready;
    }

    private void solve(JsonObject command) {
        String requestId = string(command, "requestId");
        String caseId = string(command, "caseId");
        String phase = optionalString(command, "phase");
        int repetition = command.has("repetition") ? command.get("repetition").getAsInt() : 0;
        double deadlineSeconds = command.has("deadlineSeconds") ? command.get("deadlineSeconds").getAsDouble() : defaultDeadlineSeconds;
        if (!Double.isFinite(deadlineSeconds) || deadlineSeconds <= 0.0) throw new IllegalArgumentException("deadlineSeconds must be positive and finite");
        JsonObject definition = cases.get(caseId);
        if (definition == null) throw new IllegalArgumentException("Unknown benchmark case: " + caseId);

        V3ColumnInput input = input(definition.getAsJsonObject("input"));
        double cutoff = definition.get("requestedCutoffMoleFraction").getAsDouble();
        JsonObject started = new JsonObject();
        started.addProperty("type", "STARTED");
        started.addProperty("requestId", requestId);
        emit(started);

        MetricSnapshot before = MetricSnapshot.capture();
        long startedNanos = System.nanoTime();
        V3ColumnOutcome outcome = null;
        Throwable thrown = null;
        try {
            final long deadlineNanos = secondsToNanos(deadlineSeconds);
            outcome = V3ColumnCalculator.calculate(input, () -> {
                if (System.nanoTime() - startedNanos >= deadlineNanos) {
                    throw new CancellationException("benchmark public-call deadline exceeded");
                }
            }, cutoff);
        } catch (OutOfMemoryError resourceLimit) {
            thrown = resourceLimit;
        } catch (Throwable failure) {
            thrown = failure;
        }
        long elapsedNanos = System.nanoTime() - startedNanos;
        MetricSnapshot after = MetricSnapshot.capture();

        JsonObject sample = sample(definition, input, cutoff, phase, repetition, deadlineSeconds, outcome, thrown,
                elapsedNanos, before, after);
        JsonObject result = new JsonObject();
        result.addProperty("type", "RESULT");
        result.addProperty("requestId", requestId);
        result.add("sample", sample);
        emit(result);
    }

    private JsonObject sample(JsonObject definition, V3ColumnInput input, double cutoff, String phase, int repetition,
                              double deadlineSeconds, V3ColumnOutcome outcome, Throwable thrown, long elapsedNanos,
                              MetricSnapshot before, MetricSnapshot after) {
        JsonObject sample = new JsonObject();
        sample.addProperty("revision", revision);
        sample.addProperty("workerId", workerId);
        sample.addProperty("mode", mode);
        addNullableString(sample, "phase", phase);
        sample.addProperty("repetition", repetition);
        sample.addProperty("caseId", string(definition, "id"));
        addNullableString(sample, "panel", optionalString(definition, "panel"));
        sample.add("input", inputJson(input));
        sample.addProperty("requestedCutoffMoleFraction", cutoff);
        sample.addProperty("deadlineSeconds", deadlineSeconds);
        sample.addProperty("elapsedMs", elapsedNanos / 1_000_000.0);
        addNullableLong(sample, "threadCpuMs", delta(before.cpuNanos, after.cpuNanos, 1_000_000L));
        addNullableLong(sample, "threadAllocatedBytes", delta(before.allocatedBytes, after.allocatedBytes, 1L));
        addNullableLong(sample, "gcCollectionCountDelta", delta(before.gcCount, after.gcCount, 1L));
        addNullableLong(sample, "gcCollectionTimeMsDelta", delta(before.gcTimeMillis, after.gcTimeMillis, 1L));
        sample.add("rawNewtonCertificateVerified", JsonNull.INSTANCE);
        sample.add("requestInitializerIterations", JsonNull.INSTANCE);
        sample.add("requestResidualEvaluations", JsonNull.INSTANCE);
        sample.add("requestLinearSolves", JsonNull.INSTANCE);
        sample.add("outputFingerprintSha256", JsonNull.INSTANCE);

        boolean late = elapsedNanos > secondsToNanos(deadlineSeconds);
        if (thrown != null) {
            sample.addProperty("apiOutcome", "THREW");
            sample.addProperty("apiFailureCode", thrown instanceof OutOfMemoryError ? "RESOURCE_LIMIT"
                    : thrown instanceof CancellationException ? "DEADLINE_EXCEEDED" : "EXCEPTION");
            sample.addProperty("apiFailureSummary", describe(thrown));
            sample.addProperty("status", thrown instanceof OutOfMemoryError ? "RESOURCE_LIMIT"
                    : thrown instanceof CancellationException ? "DEADLINE_EXCEEDED" : "EXCEPTION");
            nullDiagnostics(sample);
            nullTerminal(sample);
            return sample;
        }
        if (outcome instanceof V3ColumnOutcome.Failure failure) {
            sample.addProperty("apiOutcome", "FAILURE");
            sample.addProperty("apiFailureCode", failure.code().name());
            sample.addProperty("apiFailureSummary", failure.summary());
            sample.addProperty("status", failure.code() == V3SolverFailureCode.DEADLINE_EXCEEDED ? "DEADLINE_EXCEEDED" : "API_FAILURE");
            terminalDiagnostics(sample, failure.diagnostics());
            nullTerminal(sample);
            return sample;
        }
        V3ColumnOutcome.Success success = (V3ColumnOutcome.Success) Objects.requireNonNull(outcome, "outcome");
        sample.addProperty("apiOutcome", "SUCCESS");
        sample.add("apiFailureCode", JsonNull.INSTANCE);
        sample.add("apiFailureSummary", JsonNull.INSTANCE);
        terminalDiagnostics(sample, success.diagnostics());
        V3ColumnResult result = success.result();
        V3TruncationSupport support = result.problem().truncationSupport();
        boolean fallback = cutoff > 0.0 && success.diagnostics().events().stream().anyMatch(event -> event.contains("stage-trace fallback"));
        String status = late ? "LATE_SUCCESS" : cutoff == 0.0 ? "SUCCESS_EXACT"
                : fallback ? "SUCCESS_FALLBACK" : support.isIdentity() ? "SUCCESS_IDENTITY" : "SUCCESS_REDUCED";
        sample.addProperty("status", status);
        sample.add("terminalSupport", supportJson(result, support, fallback));
        JsonArray streams = streamsJson(result.streams());
        sample.add("streams", streams);
        sample.addProperty("outputFingerprintSha256", sha256(JSON.toJson(streams)));
        sample.add("closure", closureJson(input, result.streams(), cutoff, result.acceptanceAudit()));
        return sample;
    }

    private static void nullTerminal(JsonObject sample) {
        sample.add("terminalSupport", JsonNull.INSTANCE);
        sample.add("streams", JsonNull.INSTANCE);
        sample.add("closure", JsonNull.INSTANCE);
    }

    private static void nullDiagnostics(JsonObject sample) {
        sample.add("terminalAttemptNewtonIterations", JsonNull.INSTANCE);
        sample.add("rawDiagnostics", JsonNull.INSTANCE);
        sample.add("convergenceEvidence", JsonNull.INSTANCE);
        sample.add("acceptanceAudit", JsonNull.INSTANCE);
    }

    private static void terminalDiagnostics(JsonObject sample, V3SolverDiagnostics diagnostics) {
        sample.addProperty("terminalAttemptNewtonIterations", diagnostics.newtonIterations());
        sample.add("rawDiagnostics", diagnosticsJson(diagnostics));
        sample.add("convergenceEvidence", convergenceJson(diagnostics.convergenceEvidence()));
        sample.add("acceptanceAudit", auditJson(diagnostics.acceptanceAudit()));
    }

    private static JsonObject supportJson(V3ColumnResult result, V3TruncationSupport support, boolean fallback) {
        JsonObject object = new JsonObject();
        object.addProperty("returnedCutoffMoleFraction", support.cutoffMoleFraction());
        object.addProperty("identity", support.isIdentity());
        object.addProperty("totalPointCount", support.totalPointCount());
        object.addProperty("removedPointCount", support.truncatedPointCount());
        object.addProperty("retainedPointCount", support.totalPointCount() - support.truncatedPointCount());
        object.addProperty("closurePrunedPointCount", support.closurePrunedCount());
        object.addProperty("note", support.note());
        object.addProperty("nativeReducedSuccess", !support.isIdentity() && !fallback);
        object.addProperty("fallbackToUntruncated", fallback);
        object.addProperty("terminalStageCount", result.problem().topology().trayCount());
        object.addProperty("terminalUnknownCount", result.problem().degreeOfFreedomLedger().unknownCount());
        object.addProperty("terminalEquationCount", result.problem().degreeOfFreedomLedger().equationCount());
        object.addProperty("terminalStructuralRank", result.problem().degreeOfFreedomLedger().structuralRank());
        object.addProperty("finalCondenserBranch", result.problem().topology().condenserPhaseBranch().name());
        return object;
    }

    static JsonObject closureJson(V3ColumnInput input, List<V3ColumnStreamProperties> streams, double cutoff,
                                  V3AcceptanceAudit acceptanceAudit) {
        JsonObject closure = new JsonObject();
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double totalFeed = 0.0;
        for (double value : feed) totalFeed += value;
        Map<String, Double> products = hydrocarbonProducts(streams);
        JsonObject components = new JsonObject();
        double totalProduct = 0.0;
        double totalExactBound = 0.0;
        boolean exactPassed = true;
        for (int i = 0; i < feed.length; i++) {
            String id = input.componentBasis().componentId(i);
            double product = products.getOrDefault(id, 0.0);
            double residual = product - feed[i];
            double materialBound = (input.stageCount() + 2.0) * 1.0e-8 * Math.max(feed[i], totalFeed * 1.0e-12);
            double summationAllowance = Math.max(16.0 * Math.ulp(Math.max(Math.abs(feed[i]), Math.abs(product))), 1.0e-15);
            double bound = materialBound + summationAllowance;
            JsonObject component = new JsonObject();
            component.addProperty("feedMolPerSecond", feed[i]);
            component.addProperty("productMolPerSecond", product);
            component.addProperty("residualMolPerSecond", residual);
            component.addProperty("exactResidualBoundMolPerSecond", bound);
            component.addProperty("exactClosurePassed", Math.abs(residual) <= bound);
            components.add(id, component);
            totalProduct += product;
            totalExactBound += bound;
            exactPassed &= Math.abs(residual) <= bound;
        }
        double waterFeed = input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        double waterProduct = waterProducts(streams);
        double waterAllowance = Math.max(16.0 * Math.ulp(Math.max(waterFeed, waterProduct)), 1.0e-15);
        closure.add("hydrocarbonComponents", components);
        closure.addProperty("hydrocarbonFeedTotalMolPerSecond", totalFeed);
        closure.addProperty("hydrocarbonProductTotalMolPerSecond", totalProduct);
        closure.addProperty("hydrocarbonLossMolPerSecond", totalFeed - totalProduct);
        closure.addProperty("exactHydrocarbonClosurePassed", exactPassed);
        closure.addProperty("waterFeedMolPerSecond", waterFeed);
        closure.addProperty("waterProductMolPerSecond", waterProduct);
        closure.addProperty("waterResidualMolPerSecond", waterProduct - waterFeed);
        closure.addProperty("waterClosureAllowanceMolPerSecond", waterAllowance);
        closure.addProperty("waterClosurePassed", Math.abs(waterProduct - waterFeed) <= waterAllowance);
        V3AcceptanceAudit.Check nativeCheck = acceptanceAudit.checks().stream()
                .filter(check -> "TRUNCATION_MASS_DEFECT".equals(check.family())).findFirst().orElse(null);
        if (nativeCheck == null) {
            closure.add("nativeSinkEdgeTruncationAuditMolPerSecond", JsonNull.INSTANCE);
            closure.add("reconstructedHydrocarbonLossMatchesNativeAudit", JsonNull.INSTANCE);
            closure.addProperty("nativeSinkEdgeTruncationAuditAvailability", "MISSING_FROM_ACCEPTANCE_AUDIT");
        } else {
            double nativeDefect = nativeCheck.value() * totalFeed;
            closure.addProperty("nativeSinkEdgeTruncationAuditMolPerSecond", nativeDefect);
            closure.addProperty("reconstructedHydrocarbonLossMatchesNativeAudit",
                    Math.abs((totalFeed - totalProduct) - nativeDefect) <= totalExactBound);
            closure.addProperty("nativeSinkEdgeTruncationAuditAvailability", "ACCEPTANCE_AUDIT_TRUNCATION_MASS_DEFECT");
        }
        if (cutoff > 0.0) {
            double budget = 8.0 * cutoff * totalFeed;
            closure.addProperty("truncationFeedMoleLossBudgetMolPerSecond", budget);
            closure.addProperty("externalHydrocarbonClosurePassed", Math.abs(totalFeed - totalProduct) <= budget + totalExactBound);
        } else {
            closure.add("truncationFeedMoleLossBudgetMolPerSecond", JsonNull.INSTANCE);
            closure.addProperty("externalHydrocarbonClosurePassed", exactPassed);
        }
        closure.addProperty("externalWaterClosurePassed", Math.abs(waterProduct - waterFeed) <= waterAllowance);
        closure.addProperty("hydrocarbonClosed", closure.get("externalHydrocarbonClosurePassed").getAsBoolean());
        closure.addProperty("waterClosed", closure.get("externalWaterClosurePassed").getAsBoolean());
        return closure;
    }

    private V3ColumnInput input(JsonObject spec) {
        String packageId = string(spec, "packageId");
        String assayId = string(spec, "assayId");
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        V3CrudeFeed crude = thermo.crudeFeed(assayId);
        requireEquals(string(manifest.getAsJsonObject("dataset"), "expectedDatasetRevision"), thermo.datasetRevision(), "dataset revision");
        double feedRate = number(spec, "feedMolarFlowMolPerSecond");
        double[] feed = crude.moleFractions();
        for (int index = 0; index < feed.length; index++) feed[index] *= feedRate;
        List<V3SideDrawSpec> draws = new ArrayList<>();
        for (JsonElement element : array(spec, "sideDraws")) {
            JsonObject draw = element.getAsJsonObject();
            draws.add(new V3SideDrawSpec(integer(draw, "trayNumber"), number(draw, "molarFlowMolPerSecond")));
        }
        List<V3SteamFeedSpec> steam = new ArrayList<>();
        for (JsonElement element : array(spec, "steamFeeds")) {
            JsonObject flow = element.getAsJsonObject();
            steam.add(new V3SteamFeedSpec(integer(flow, "stageNumber"), number(flow, "molarFlowMolPerSecond"), number(flow, "temperatureKelvin")));
        }
        return new V3ColumnInput(integer(spec, "schemaVersion"), packageId, assayId, crude.componentBasis(), feed,
                number(spec, "feedTemperatureKelvin"), integer(spec, "stageCount"), integer(spec, "feedStageNumber"),
                number(spec, "topPressurePascal"), number(spec, "stagePressureDropPascal"), List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(number(spec, "condenserTemperatureKelvin")),
                        new V3ColumnSpecification.OrganicRefluxRatio(number(spec, "organicRefluxRatio")),
                        new V3ColumnSpecification.ReboilerDuty(number(spec, "reboilerDutyWatts"))), draws, steam);
    }

    private static JsonObject inputJson(V3ColumnInput input) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", input.schemaVersion());
        result.addProperty("packageId", input.packageId());
        result.addProperty("assayId", input.assayId());
        result.add("componentIds", strings(input.componentBasis().componentIds()));
        result.add("feedComponentMolarFlowsMolPerSecond", numbers(input.feedComponentMolarFlowsMolPerSecond()));
        result.addProperty("feedTemperatureKelvin", input.feedTemperatureKelvin());
        result.addProperty("stageCount", input.stageCount());
        result.addProperty("feedStageNumber", input.feedStageNumber());
        result.addProperty("topPressurePascal", input.topPressurePascal());
        result.addProperty("stagePressureDropPascal", input.stagePressureDropPascal());
        result.add("sideDraws", JSON.toJsonTree(input.sideDraws()));
        result.add("steamFeeds", JSON.toJsonTree(input.steamFeeds()));
        result.add("specifications", JSON.toJsonTree(input.specifications()));
        return result;
    }

    private static JsonArray streamsJson(List<V3ColumnStreamProperties> streams) {
        JsonArray result = new JsonArray();
        for (V3ColumnStreamProperties stream : streams) {
            JsonObject value = new JsonObject();
            value.addProperty("id", stream.streamId());
            value.addProperty("streamId", stream.streamId());
            value.addProperty("displayName", stream.displayName());
            value.addProperty("phase", stream.phase());
            value.addProperty("molarFlowMolPerSecond", stream.molarFlowMolPerSecond());
            value.addProperty("massFlowKgPerSecond", stream.massFlowKgPerSecond());
            value.addProperty("temperatureKelvin", stream.temperatureKelvin());
            value.addProperty("pressurePascal", stream.pressurePascal());
            value.addProperty("vaporMoleFraction", stream.vaporMoleFraction());
            JsonObject flows = new JsonObject();
            JsonArray composition = new JsonArray();
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                double componentFlow = stream.molarFlowMolPerSecond() * fraction.moleFraction();
                flows.addProperty(fraction.componentId(), componentFlow);
                JsonObject row = new JsonObject();
                row.addProperty("componentId", fraction.componentId());
                row.addProperty("moleFraction", fraction.moleFraction());
                row.addProperty("massFraction", fraction.massFraction());
                row.addProperty("molarFlowMolPerSecond", componentFlow);
                composition.add(row);
            }
            value.add("componentMolarFlowsMolPerSecond", flows);
            value.add("componentMolarFlows", flows.deepCopy());
            value.add("composition", composition);
            result.add(value);
        }
        return result;
    }

    private static JsonObject diagnosticsJson(V3SolverDiagnostics diagnostics) {
        JsonObject value = new JsonObject();
        value.addProperty("initializerIterationsRaw", diagnostics.initializerIterations());
        value.addProperty("newtonIterationsRaw", diagnostics.newtonIterations());
        value.addProperty("residualEvaluationsRaw", diagnostics.residualEvaluations());
        value.addProperty("linearSolvesRaw", diagnostics.linearSolves());
        value.addProperty("maximumScaledResidual", diagnostics.maximumScaledResidual());
        value.addProperty("finalStepNorm", diagnostics.finalStepNorm());
        value.addProperty("solvePath", diagnostics.solvePath());
        value.add("events", strings(diagnostics.events()));
        return value;
    }

    private static JsonObject convergenceJson(V3ConvergenceEvidence evidence) {
        JsonObject value = new JsonObject();
        value.addProperty("hasFinalNewtonStep", evidence.hasFinalNewtonStep());
        value.addProperty("finalLinearBackwardError", evidence.finalLinearBackwardError());
        value.addProperty("maximumLogFlowChange", evidence.maximumLogFlowChange());
        value.addProperty("maximumTemperatureChangeKelvin", evidence.maximumTemperatureChangeKelvin());
        value.addProperty("maximumTemperatureStepRatio", evidence.maximumTemperatureStepRatio());
        value.addProperty("satisfiesGates", evidence.satisfiesGates());
        return value;
    }

    private static JsonObject auditJson(V3AcceptanceAudit audit) {
        JsonObject result = new JsonObject();
        result.addProperty("accepted", audit.accepted());
        JsonArray checks = new JsonArray();
        for (V3AcceptanceAudit.Check check : audit.checks()) {
            JsonObject row = new JsonObject();
            row.addProperty("family", check.family());
            row.addProperty("value", check.value());
            row.addProperty("limit", check.limit());
            row.addProperty("passed", check.passed());
            row.addProperty("detail", check.detail());
            checks.add(row);
        }
        result.add("checks", checks);
        result.add("advisoryEvidence", strings(audit.advisoryEvidence()));
        return result;
    }

    private static Map<String, Double> hydrocarbonProducts(List<V3ColumnStreamProperties> streams) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (V3ColumnStreamProperties stream : streams) {
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                if (!"h2o".equals(fraction.componentId())) values.merge(fraction.componentId(),
                        stream.molarFlowMolPerSecond() * fraction.moleFraction(), Double::sum);
            }
        }
        return values;
    }

    private static double waterProducts(List<V3ColumnStreamProperties> streams) {
        return streams.stream().flatMap(stream -> stream.moleFractions().stream()
                .filter(fraction -> "h2o".equals(fraction.componentId()))
                .map(fraction -> stream.molarFlowMolPerSecond() * fraction.moleFraction()))
                .mapToDouble(Double::doubleValue).sum();
    }

    private static Map<String, JsonObject> indexCases(JsonArray values) {
        Map<String, JsonObject> index = new LinkedHashMap<>();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            String id = string(value, "id");
            if (index.put(id, value) != null) throw new IllegalArgumentException("Duplicate manifest case: " + id);
        }
        return Map.copyOf(index);
    }

    private static JsonObject error(String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "ERROR");
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }

    private static synchronized void emit(JsonObject value) {
        System.out.println(JSON.toJson(value));
        System.out.flush();
    }

    private static JsonArray strings(List<String> values) { return JSON.toJsonTree(values).getAsJsonArray(); }
    private static JsonArray numbers(double[] values) { return JSON.toJsonTree(values).getAsJsonArray(); }
    private static JsonArray array(JsonObject value, String key) { return value.has(key) ? value.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject value, String key) { return value.get(key).getAsString(); }
    private static String optionalString(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : null; }
    private static int integer(JsonObject value, String key) { return value.get(key).getAsInt(); }
    private static double number(JsonObject value, String key) { return value.get(key).getAsDouble(); }
    private static void addNullableString(JsonObject value, String key, String nullable) { value.add(key, nullable == null ? JsonNull.INSTANCE : JSON.toJsonTree(nullable)); }
    private static void addNullableLong(JsonObject value, String key, Long nullable) { value.add(key, nullable == null ? JsonNull.INSTANCE : JSON.toJsonTree(nullable)); }
    private static Long delta(Long before, Long after, long divisor) { return before == null || after == null ? null : (after - before) / divisor; }
    private static long secondsToNanos(double seconds) { return (long) Math.min(Long.MAX_VALUE, seconds * 1_000_000_000.0); }
    private static String requireLabel(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank"); return value; }
    private static void requireEquals(String expected, String actual, String label) { if (!expected.equals(actual)) throw new IllegalArgumentException(label + " mismatch: expected " + expected + ", got " + actual); }
    private static String describe(Throwable failure) { String message = failure.getMessage(); return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message); }
    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class MetricSnapshot {
        private final Long cpuNanos;
        private final Long allocatedBytes;
        private final Long gcCount;
        private final Long gcTimeMillis;
        private final boolean cpuSupported;
        private final boolean allocationSupported;
        private final boolean gcSupported;

        private MetricSnapshot(Long cpuNanos, Long allocatedBytes, Long gcCount, Long gcTimeMillis,
                               boolean cpuSupported, boolean allocationSupported, boolean gcSupported) {
            this.cpuNanos = cpuNanos;
            this.allocatedBytes = allocatedBytes;
            this.gcCount = gcCount;
            this.gcTimeMillis = gcTimeMillis;
            this.cpuSupported = cpuSupported;
            this.allocationSupported = allocationSupported;
            this.gcSupported = gcSupported;
        }

        private static MetricSnapshot capture() {
            ThreadMXBean thread = ManagementFactory.getThreadMXBean();
            Long cpu = null;
            boolean cpuSupported = thread.isCurrentThreadCpuTimeSupported();
            if (cpuSupported) {
                try {
                    if (!thread.isThreadCpuTimeEnabled()) thread.setThreadCpuTimeEnabled(true);
                    long value = thread.getCurrentThreadCpuTime();
                    if (value >= 0) cpu = value;
                } catch (UnsupportedOperationException | SecurityException ignored) { cpuSupported = false; }
            }
            Long allocated = null;
            boolean allocationSupported = thread instanceof com.sun.management.ThreadMXBean extended
                    && extended.isThreadAllocatedMemorySupported();
            if (allocationSupported) {
                try {
                    com.sun.management.ThreadMXBean extended = (com.sun.management.ThreadMXBean) thread;
                    if (!extended.isThreadAllocatedMemoryEnabled()) extended.setThreadAllocatedMemoryEnabled(true);
                    long value = extended.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    if (value >= 0) allocated = value;
                } catch (UnsupportedOperationException | SecurityException ignored) { allocationSupported = false; }
            }
            long count = 0L;
            long time = 0L;
            boolean gcSupported = true;
            List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
            if (collectors.isEmpty()) gcSupported = false;
            for (GarbageCollectorMXBean collector : collectors) {
                long collectorCount = collector.getCollectionCount();
                long collectorTime = collector.getCollectionTime();
                if (collectorCount < 0 || collectorTime < 0) { gcSupported = false; break; }
                count += collectorCount;
                time += collectorTime;
            }
            return new MetricSnapshot(cpu, allocated, gcSupported ? count : null, gcSupported ? time : null,
                    cpuSupported && cpu != null, allocationSupported && allocated != null, gcSupported);
        }
    }
}
