package com.wormzjl.createcheme.science.column.v3.thermo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic replay of V3FeedFlash, NOT a production fix or a timing benchmark.
 * Default arithmetic, update ordering, and asymmetric endpoint handling reproduce the original algorithm.
 * Captured normalizedOverall is copied without another normalization; public fugacity is never used.
 */
public final class V3FlashReplayProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final double LOG_K_TOLERANCE = 1.0e-10;
    private static final double PHASE_ENDPOINT_TOLERANCE = 1.0e-12;

    private V3FlashReplayProbe() {}

    public static void main(String[] args) throws IOException {
        Options options = Options.parse(args);
        JsonObject captured;
        try (Reader reader = Files.newBufferedReader(options.input(), StandardCharsets.UTF_8)) {
            JsonArray events = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("events");
            if (events == null || options.event() >= events.size()) throw new IllegalArgumentException("capture has no selected event");
            captured = events.get(options.event()).getAsJsonObject();
        }
        Replay replay = new Replay(options, captured);
        replay.run();
        Path report = options.report().toAbsolutePath().normalize();
        Files.createDirectories(report.getParent());
        try (Writer writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            JSON.toJson(replay.report, writer);
        }
        System.out.println("FLASH_REPLAY status=" + replay.report.get("status") + " report=" + report);
    }

    private record Options(Path input, int event, Path report, int maxIterations,
                           double relaxation, boolean skipInactiveConvergence, boolean polishRoots, Double pressurePascal) {
        private static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("Expected --name=value: " + arg);
                String[] pair = arg.substring(2).split("=", 2);
                if (!List.of("input", "event", "report", "maxIterations", "relaxation", "skipInactiveConvergence", "polishRoots", "pressurePascal").contains(pair[0])
                        || values.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalArgumentException("Unknown or duplicate option " + pair[0]);
            }
            if (!values.containsKey("input")) throw new IllegalArgumentException("--input=capture.json is required");
            int event = Integer.parseInt(values.getOrDefault("event", "0"));
            int iterations = Integer.parseInt(values.getOrDefault("maxIterations", "64"));
            double relaxation = Double.parseDouble(values.getOrDefault("relaxation", "0.5"));
            String skip = values.getOrDefault("skipInactiveConvergence", "false");
            String polish = values.getOrDefault("polishRoots", "false");
            Double pressure = values.containsKey("pressurePascal") ? Double.valueOf(values.get("pressurePascal")) : null;
            if (event < 0 || iterations < 1 || iterations > 512 || !Double.isFinite(relaxation)
                    || relaxation <= 0.0 || relaxation > 1.0 || (!skip.equals("true") && !skip.equals("false"))
                    || (!polish.equals("true") && !polish.equals("false"))
                    || (pressure != null && (!Double.isFinite(pressure) || pressure <= 0.0))) {
                throw new IllegalArgumentException("Require event>=0, 1<=maxIterations<=512, 0<relaxation<=1, boolean diagnostic flags, and a finite positive optional pressurePascal");
            }
            return new Options(Path.of(values.get("input")), event,
                    Path.of(values.getOrDefault("report", "build/reports/benchmarks/v3-flash-replay.json")),
                    iterations, relaxation, Boolean.parseBoolean(skip), Boolean.parseBoolean(polish), pressure);
        }
    }

    private static final class Replay {
        private final Options options;
        private final V3PengRobinsonThermo model;
        private final V3ThermoWorkspace workspace;
        private final RootDiagnostics rootDiagnostics;
        private final double temperature;
        private final double pressure;
        private final Map<String, Object> report = new LinkedHashMap<>();
        private final List<Map<String, Object>> trace = new ArrayList<>();
        private double[] lastLogKUsed;
        private double[] lastTargetLogK;
        private double[] lastLogPhiLiquid;
        private double[] lastLogPhiVapor;
        private double[] previousLiquid;
        private double[] previousVapor;

        private Replay(Options options, JsonObject captured) {
            this.options = options;
            String packageId = captured.has("packageId") && !captured.get("packageId").isJsonNull()
                    ? captured.get("packageId").getAsString() : "createcheme:cdu17_tjl_acs2018";
            model = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
            workspace = model.newWorkspace();
            rootDiagnostics = new RootDiagnostics(model, workspace, options.polishRoots());
            temperature = captured.get("temperatureKelvin").getAsDouble();
            double capturedPressure = captured.get("pressurePascal").getAsDouble();
            pressure = options.pressurePascal() == null ? capturedPressure : options.pressurePascal();
            if (!Double.isFinite(pressure) || pressure < model.minimumPressurePascal() || pressure > model.maximumPressurePascal()) {
                throw new IllegalArgumentException("Replay pressure must lie within the selected package's "
                        + model.minimumPressurePascal() + ".." + model.maximumPressurePascal() + " Pa envelope");
            }
            JsonArray overall = captured.getAsJsonArray("normalizedOverall");
            if (overall == null || overall.size() != workspace.componentCount()) throw new IllegalArgumentException("Captured z differs from the package basis");
            double total = 0.0;
            for (int component = 0; component < overall.size(); component++) {
                double value = overall.get(component).getAsDouble();
                if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Captured z must be finite and nonnegative");
                workspace.normalizedOverall[component] = value;
                total += value;
            }
            if (!Double.isFinite(total) || Math.abs(total - 1.0) > 1.0e-10) throw new IllegalArgumentException("Capture must contain already normalizedOverall");
            report.put("scope", "DIAGNOSTIC REPLAY ONLY, NOT A PRODUCTION FIX. Default reproduces V3FeedFlash arithmetic and liquid-only post-PR endpoint handling. Configurable pressure, iteration limit, damping, inactive-component stopping, and root polishing are explicit counterfactuals. Observation overhead is not a clean performance measurement.");
            report.put("rootCounterfactual", "polishRoots=false observes only. When true, up to eight bounded Newton refinements of the existing selected cubic root are followed immediately by recomputation of every log-fugacity coefficient and residual enthalpy using the kernel's unchanged mixture coefficients and arithmetic. Stable Cardano is an independent diagnostic comparison, not the applied root strategy. Original root count/separation classification is retained. This experimental path has not been production-qualified and makes no external thermodynamic-accuracy claim.");
            report.put("inputCapture", options.input().toAbsolutePath().normalize().toString());
            report.put("eventIndex", options.event());
            report.put("packageId", packageId);
            report.put("datasetRevision", model.datasetRevision());
            report.put("temperatureKelvin", temperature);
            report.put("capturedPressurePascal", capturedPressure);
            report.put("pressureOverridePascal", options.pressurePascal());
            report.put("pressureOverridden", options.pressurePascal() != null);
            report.put("pressurePascal", pressure);
            report.put("normalizedOverall", numbers(workspace.normalizedOverall));
            report.put("capturedCompositionSum", total);
            report.put("componentIds", model.componentBasis().componentIds());
            report.put("settings", Map.of("maxIterations", options.maxIterations(), "relaxation", options.relaxation(),
                    "skipInactiveConvergence", options.skipInactiveConvergence(), "logKTolerance", LOG_K_TOLERANCE,
                    "endpointResidualTolerance", PHASE_ENDPOINT_TOLERANCE, "rachfordRiceIterations", 100,
                    "polishRoots", options.polishRoots()));
            report.put("faithfulDefaultSettings", options.maxIterations() == 64 && options.relaxation() == 0.5
                    && !options.skipInactiveConvergence() && !options.polishRoots() && options.pressurePascal() == null);
            report.put("trace", trace);
        }

        private void run() {
            long started = System.nanoTime();
            try {
                V3FlashResult result = resolve();
                report.put("status", "SUCCESS");
                report.put("result", Map.of("phase", result.phase(), "iterations", result.iterations(),
                        "vaporFraction", result.vaporFraction(), "liquidComposition", numbers(result.liquidComposition()),
                        "vaporComposition", numbers(result.vaporComposition()), "molarEnthalpyJoulesPerMol", result.molarEnthalpyJoulesPerMol(),
                        "detail", result.detail()));
            } catch (V3ThermoException failure) {
                report.put("status", failure.code().toString());
                report.put("failure", failure.getMessage());
                report.put("failurePhase", failure.phase());
            } catch (RuntimeException failure) {
                report.put("status", "ERROR");
                report.put("failure", failure.toString());
            } finally {
                report.put("elapsedMilliseconds", (System.nanoTime() - started) / 1_000_000.0);
                Map<String, Object> last = new LinkedHashMap<>();
                last.put("arrayTiming", "lastLogKUsed, x/y and lastLogPhi describe the final evaluated iteration. workspaceLogKAtExit includes the last damped update on iteration-limit failure, but not on convergence. Endpoint publication may have refreshed the workspace liquid evaluation afterward.");
                last.put("lastLogKUsed", numbers(lastLogKUsed));
                last.put("lastTargetLogK", numbers(lastTargetLogK));
                last.put("lastLogPhiLiquid", numbers(lastLogPhiLiquid));
                last.put("lastLogPhiVapor", numbers(lastLogPhiVapor));
                last.put("workspaceLogKAtExit", numbers(workspace.logK));
                last.put("workspaceNextLogKAtExit", numbers(workspace.nextLogK));
                last.put("liquidComposition", numbers(workspace.liquidComposition));
                last.put("vaporComposition", numbers(workspace.vaporComposition));
                report.put("lastArrays", last);
                report.put("lastRootNumerics", rootDiagnostics.latest);
            }
        }

        private V3FlashResult resolve() {
            int count = workspace.componentCount();
            model.wilsonK(temperature, pressure, workspace.wilsonK, workspace);
            for (int component = 0; component < count; component++) workspace.logK[component] = Math.log(workspace.wilsonK[component]);
            double liquidEndpoint = rrResidual(workspace.normalizedOverall, workspace.logK, 0.0);
            double vaporEndpoint = rrResidual(workspace.normalizedOverall, workspace.logK, 1.0);
            Map<String, Object> initial = new LinkedHashMap<>();
            initial.put("wilsonK", numbers(workspace.wilsonK));
            initial.put("logK", numbers(workspace.logK));
            initial.put("liquidEndpointResidual", finite(liquidEndpoint));
            initial.put("vaporEndpointResidual", finite(vaporEndpoint));
            report.put("initial", initial);
            if (!Double.isFinite(liquidEndpoint) || !Double.isFinite(vaporEndpoint)) {
                throw failure("V3 feed flash could not establish a finite Rachford-Rice phase bracket");
            }
            if (liquidEndpoint <= PHASE_ENDPOINT_TOLERANCE) {
                evaluatePhase(workspace.normalizedOverall, V3Phase.LIQUID);
                return V3FlashResult.liquid(0, workspace.normalizedOverall,
                        model.phaseMolarEnthalpy(temperature, workspace.normalizedOverall, V3Phase.LIQUID, workspace),
                        "all-liquid Wilson endpoint classification");
            }
            if (vaporEndpoint >= -PHASE_ENDPOINT_TOLERANCE) {
                evaluatePhase(workspace.normalizedOverall, V3Phase.VAPOR);
                return V3FlashResult.vapor(0, workspace.normalizedOverall,
                        model.phaseMolarEnthalpy(temperature, workspace.normalizedOverall, V3Phase.VAPOR, workspace),
                        "all-vapor Wilson endpoint classification");
            }
            for (int iteration = 1; iteration <= options.maxIterations(); iteration++) {
                lastLogKUsed = workspace.logK.clone();
                double beta = rrRoot(workspace.normalizedOverall, workspace.logK);
                Map<String, Object> row = new LinkedHashMap<>();
                trace.add(row);
                row.put("iteration", iteration);
                row.put("vaporFraction", finite(beta));
                row.put("liquidEndpointResidual", finite(rrResidual(workspace.normalizedOverall, workspace.logK, 0.0)));
                row.put("vaporEndpointResidual", finite(rrResidual(workspace.normalizedOverall, workspace.logK, 1.0)));
                if (!Double.isFinite(beta) || beta <= 0.0 || beta >= 1.0) {
                    throw failure("V3 feed flash lost its two-phase Rachford-Rice root");
                }
                populate(workspace.normalizedOverall, workspace.logK, beta, workspace.liquidComposition, workspace.vaporComposition);
                evaluatePhase(workspace.liquidComposition, V3Phase.LIQUID);
                evaluatePhase(workspace.vaporComposition, V3Phase.VAPOR);
                lastLogPhiLiquid = workspace.prSession.logFugacityCoefficients(V3Phase.LIQUID);
                lastLogPhiVapor = workspace.prSession.logFugacityCoefficients(V3Phase.VAPOR);
                lastTargetLogK = new double[count];
                double maximumAll = 0.0;
                double maximumActive = 0.0;
                int dominantAll = -1;
                int dominantActive = -1;
                for (int component = 0; component < count; component++) {
                    double target = model.logFugacityCoefficient(V3Phase.LIQUID, component, workspace)
                            - model.logFugacityCoefficient(V3Phase.VAPOR, component, workspace);
                    lastTargetLogK[component] = target;
                    double error = Math.abs(target - workspace.logK[component]);
                    if (dominantAll < 0 || error > maximumAll) dominantAll = component;
                    maximumAll = Math.max(maximumAll, error);
                    if (workspace.normalizedOverall[component] > 0.0) {
                        if (dominantActive < 0 || error > maximumActive) dominantActive = component;
                        maximumActive = Math.max(maximumActive, error);
                    }
                    // Preserve the production expression exactly for the default setting.
                    workspace.nextLogK[component] = options.relaxation() == 0.5
                            ? 0.5 * (workspace.logK[component] + target)
                            : workspace.logK[component] + options.relaxation() * (target - workspace.logK[component]);
                }
                row.put("maximumLogKErrorAll", finite(maximumAll));
                row.put("maximumLogKErrorActive", finite(maximumActive));
                row.put("dominantAll", dominant(dominantAll));
                row.put("dominantActive", dominant(dominantActive));
                row.put("maximumLiquidCompositionChange", delta(previousLiquid, workspace.liquidComposition));
                row.put("maximumVaporCompositionChange", delta(previousVapor, workspace.vaporComposition));
                row.put("maximumLiquidVaporCompositionDifference", delta(workspace.liquidComposition, workspace.vaporComposition));
                row.put("liquidRoot", root(V3Phase.LIQUID));
                row.put("vaporRoot", root(V3Phase.VAPOR));
                row.put("liquidRootNumerics", rootDiagnostics.latest.get(V3Phase.LIQUID));
                row.put("vaporRootNumerics", rootDiagnostics.latest.get(V3Phase.VAPOR));
                row.put("nextLiquidEndpointResidual", finite(rrResidual(workspace.normalizedOverall, workspace.nextLogK, 0.0)));
                row.put("nextVaporEndpointResidual", finite(rrResidual(workspace.normalizedOverall, workspace.nextLogK, 1.0)));
                double stoppingError = options.skipInactiveConvergence() ? maximumActive : maximumAll;
                row.put("stoppingError", finite(stoppingError));
                previousLiquid = workspace.liquidComposition.clone();
                previousVapor = workspace.vaporComposition.clone();
                if (iteration == 1 || iteration % 10 == 0 || stoppingError <= LOG_K_TOLERANCE) {
                    System.out.printf(Locale.ROOT, "FLASH_ITER %d beta=%.12g errorAll=%.12g errorActive=%.12g dominant=%s z=%.12g%n",
                            iteration, beta, maximumAll, maximumActive, model.componentBasis().componentId(dominantAll),
                            workspace.normalizedOverall[dominantAll]);
                }
                if (stoppingError <= LOG_K_TOLERANCE) {
                    // Faithful baseline: retain the production liquid-only post-PR endpoint handling.
                    if (rrResidual(workspace.normalizedOverall, workspace.logK, 0.0) <= PHASE_ENDPOINT_TOLERANCE) {
                        evaluatePhase(workspace.normalizedOverall, V3Phase.LIQUID);
                        return V3FlashResult.liquid(iteration, workspace.normalizedOverall,
                                model.phaseMolarEnthalpy(temperature, workspace.normalizedOverall, V3Phase.LIQUID, workspace),
                                "all-liquid converged PR endpoint classification");
                    }
                    double liquidEnthalpy = model.phaseMolarEnthalpy(temperature, workspace.liquidComposition, V3Phase.LIQUID, workspace);
                    double vaporEnthalpy = model.phaseMolarEnthalpy(temperature, workspace.vaporComposition, V3Phase.VAPOR, workspace);
                    return V3FlashResult.twoPhase(iteration, beta, workspace.liquidComposition, workspace.vaporComposition,
                            (1.0 - beta) * liquidEnthalpy + beta * vaporEnthalpy, "rigorous two-phase Rachford-Rice flash");
                }
                System.arraycopy(workspace.nextLogK, 0, workspace.logK, 0, count);
            }
            throw failure("V3 feed flash did not converge within " + options.maxIterations() + " iterations");
        }

        private void evaluatePhase(double[] composition, V3Phase phase) {
            model.evaluateInto(temperature, pressure, composition, phase, workspace);
            rootDiagnostics.observe(temperature, pressure, phase);
        }

        private Map<String, Object> dominant(int index) {
            return Map.of("index", index, "component", model.componentBasis().componentId(index),
                    "overallMoleFraction", workspace.normalizedOverall[index]);
        }

        private Map<String, Object> root(V3Phase phase) {
            return Map.of("physicalRootCount", workspace.prSession.physicalRootCount(phase),
                    "compressibilityFactor", workspace.prSession.compressibilityFactor(phase),
                    "rootSeparation", workspace.prSession.rootSeparation(phase));
        }
    }

    /** Reflection is confined to this disposable diagnostic; production source is never modified. */
    private static final class RootDiagnostics {
        private static final double R = V3PengRobinsonKernel.GAS_CONSTANT;
        private static final double SQRT_TWO = Math.sqrt(2.0);
        private final boolean polishRoots;
        private final V3PengRobinsonKernel kernel;
        private final Object scratch;
        private final V3PengRobinsonKernel.Evaluation liquid;
        private final V3PengRobinsonKernel.Evaluation vapor;
        private final double[] coVolumes;
        private final Map<V3Phase, Map<String, Object>> latest = new LinkedHashMap<>();

        private RootDiagnostics(V3PengRobinsonThermo model, V3ThermoWorkspace workspace, boolean polishRoots) {
            this.polishRoots = polishRoots;
            Object session = read(model, "session");
            kernel = (V3PengRobinsonKernel) read(session, "kernel");
            scratch = read(workspace.prSession, "workspace");
            liquid = (V3PengRobinsonKernel.Evaluation) read(workspace.prSession, "liquid");
            vapor = (V3PengRobinsonKernel.Evaluation) read(workspace.prSession, "vapor");
            coVolumes = (double[]) read(kernel, "coVolumes");
        }

        private void observe(double temperature, double pressure, V3Phase phase) {
            V3PengRobinsonKernel.Evaluation output = phase == V3Phase.LIQUID ? liquid : vapor;
            double aMix = output.aMix();
            double bMix = output.bMix();
            double a = aMix * pressure / (R * R * temperature * temperature);
            double b = bMix * pressure / (R * temperature);
            double c2 = -(1.0 - b);
            double c1 = a - 3.0 * b * b - 2.0 * b;
            double c0 = -(a * b - b * b - b * b * b);
            double p = c1 - c2 * c2 / 3.0;
            double q = 2.0 * c2 * c2 * c2 / 27.0 - c2 * c1 / 3.0 + c0;
            double discriminant = q * q / 4.0 + p * p * p / 27.0;
            double rawZ = output.compressibility();
            Map<String, Object> metrics = new LinkedHashMap<>();
            latest.put(phase, metrics);
            metrics.put("reducedA", a);
            metrics.put("reducedB", b);
            metrics.put("c2", c2);
            metrics.put("c1", c1);
            metrics.put("c0", c0);
            metrics.put("depressedP", p);
            metrics.put("depressedQ", q);
            metrics.put("discriminant", discriminant);
            metrics.put("rawZ", rawZ);
            metrics.put("rawHornerResidual", polynomial(rawZ, c2, c1, c0));
            metrics.put("rawFmaResidual", polynomialFma(rawZ, c2, c1, c0));
            metrics.put("rawDerivative", derivative(rawZ, c2, c1));
            if (discriminant >= 0.0) {
                double squareRoot = Math.sqrt(discriminant);
                double plus = -q / 2.0 + squareRoot;
                double minus = -q / 2.0 - squareRoot;
                double selectedRadicand = Math.abs(plus) >= Math.abs(minus) ? plus : minus;
                double u = Math.cbrt(selectedRadicand);
                double stableZ = u == 0.0 ? -c2 / 3.0 : u - p / (3.0 * u) - c2 / 3.0;
                metrics.put("cardanoRadicandPlus", plus);
                metrics.put("cardanoRadicandMinus", minus);
                metrics.put("cardanoSmallLargeRadicandRatio", finite(Math.min(Math.abs(plus), Math.abs(minus))
                        / Math.max(Math.abs(plus), Math.abs(minus))));
                metrics.put("stableCardanoZ", finite(stableZ));
                metrics.put("stableCardanoFmaResidual", finite(polynomialFma(stableZ, c2, c1, c0)));
                metrics.put("stableMinusRawZ", finite(stableZ - rawZ));
            }
            double polishedZ = rawZ;
            int steps = 0;
            for (; steps < 8; steps++) {
                double residual = polynomialFma(polishedZ, c2, c1, c0);
                double slope = derivative(polishedZ, c2, c1);
                if (!Double.isFinite(slope) || slope == 0.0) break;
                double candidate = polishedZ - residual / slope;
                if (!Double.isFinite(candidate) || candidate <= b || candidate == polishedZ
                        || Math.abs(candidate - rawZ) > 1.0e-6 * Math.max(1.0, Math.abs(rawZ))
                        || Math.abs(polynomialFma(candidate, c2, c1, c0)) > Math.abs(residual)) break;
                polishedZ = candidate;
            }
            metrics.put("newtonPolishedZ", polishedZ);
            metrics.put("newtonPolishSteps", steps);
            metrics.put("polishedFmaResidual", polynomialFma(polishedZ, c2, c1, c0));
            metrics.put("polishedMinusRawZ", polishedZ - rawZ);
            double[] sumA = (double[]) read(scratch, "sumA");
            double logRatio = Math.log((polishedZ + (1.0 + SQRT_TWO) * b) / (polishedZ + (1.0 - SQRT_TWO) * b));
            double attraction = a / (2.0 * SQRT_TWO * Math.max(b, 1.0e-300));
            double[] logPhi = new double[coVolumes.length];
            double maximumPhiChange = 0.0;
            for (int component = 0; component < logPhi.length; component++) {
                double bRatio = coVolumes[component] / bMix;
                double attractionRatio = 2.0 * sumA[component] / aMix - bRatio;
                logPhi[component] = bRatio * (polishedZ - 1.0) - Math.log(polishedZ - b)
                        - attraction * attractionRatio * logRatio;
                maximumPhiChange = Math.max(maximumPhiChange, Math.abs(logPhi[component] - output.logFugacityCoefficient(component)));
            }
            double daMixDt = mixtureTemperatureDerivative();
            double enthalpy = R * temperature * (polishedZ - 1.0)
                    + (temperature * daMixDt - aMix) / (2.0 * SQRT_TWO * bMix) * logRatio;
            metrics.put("maximumLogPhiChangeFromPolish", finite(maximumPhiChange));
            metrics.put("residualEnthalpyChangeFromPolishJoulesPerMol", finite(enthalpy - output.residualEnthalpyJoulesPerMol()));
            metrics.put("applied", polishRoots);
            if (polishRoots) {
                for (double value : logPhi) if (!Double.isFinite(value)) throw new IllegalStateException("Diagnostic polished logPhi is nonfinite");
                if (!Double.isFinite(enthalpy)) throw new IllegalStateException("Diagnostic polished enthalpy is nonfinite");
                System.arraycopy(logPhi, 0, (double[]) read(output, "logFugacityCoefficients"), 0, logPhi.length);
                writeDouble(output, "compressibility", polishedZ);
                writeDouble(output, "residualEnthalpyJoulesPerMol", enthalpy);
            }
        }

        private double mixtureTemperatureDerivative() {
            double[] composition = (double[]) read(scratch, "composition");
            double[] daDt = (double[]) read(scratch, "daDt");
            double[] sqrtA = (double[]) read(scratch, "sqrtA");
            if (kernel.usesRankOneMixing()) {
                double[] qValues = (double[]) read(scratch, "q");
                double qSum = 0.0;
                double dqSum = 0.0;
                for (int component = 0; component < composition.length; component++) {
                    qSum += qValues[component];
                    dqSum += composition[component] * daDt[component] / (2.0 * sqrtA[component]);
                }
                return 2.0 * qSum * dqSum;
            }
            double[] g = (double[]) read(scratch, "g");
            double result = 0.0;
            for (int component = 0; component < composition.length; component++) {
                double dq = composition[component] * daDt[component] / (2.0 * sqrtA[component]);
                result += 2.0 * dq * g[component];
            }
            return result;
        }

        private static double polynomial(double z, double c2, double c1, double c0) {
            return ((z + c2) * z + c1) * z + c0;
        }

        private static double polynomialFma(double z, double c2, double c1, double c0) {
            return Math.fma(Math.fma(z + c2, z, c1), z, c0);
        }

        private static double derivative(double z, double c2, double c1) {
            return Math.fma(Math.fma(3.0, z, 2.0 * c2), z, c1);
        }

        private static Object read(Object owner, String name) {
            try {
                Field field = owner.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Diagnostic cannot inspect " + owner.getClass().getSimpleName() + "." + name, failure);
            }
        }

        private static void writeDouble(Object owner, String name, double value) {
            try {
                Field field = owner.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.setDouble(owner, value);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Diagnostic cannot update " + owner.getClass().getSimpleName() + "." + name, failure);
            }
        }
    }

    private static double rrRoot(double[] composition, double[] logK) {
        double lower = 0.0;
        double upper = 1.0;
        for (int iteration = 0; iteration < 100; iteration++) {
            double midpoint = 0.5 * (lower + upper);
            double residual = rrResidual(composition, logK, midpoint);
            if (!Double.isFinite(residual)) return Double.NaN;
            if (residual > 0.0) lower = midpoint;
            else upper = midpoint;
        }
        return 0.5 * (lower + upper);
    }

    private static double rrResidual(double[] composition, double[] logK, double beta) {
        double residual = 0.0;
        for (int component = 0; component < composition.length; component++) {
            if (composition[component] == 0.0) continue;
            double k = Math.exp(logK[component]);
            double kMinusOne = Math.expm1(logK[component]);
            double denominator = (1.0 - beta) + beta * k;
            if (!(denominator > 0.0) || !Double.isFinite(denominator)) return Double.NaN;
            residual += composition[component] * kMinusOne / denominator;
        }
        return residual;
    }

    private static void populate(double[] overall, double[] logK, double beta, double[] liquid, double[] vapor) {
        double liquidTotal = 0.0;
        double vaporTotal = 0.0;
        for (int component = 0; component < overall.length; component++) {
            if (overall[component] == 0.0) {
                liquid[component] = 0.0;
                vapor[component] = 0.0;
                continue;
            }
            double k = Math.exp(logK[component]);
            liquid[component] = overall[component] / ((1.0 - beta) + beta * k);
            vapor[component] = k * liquid[component];
            liquidTotal += liquid[component];
            vaporTotal += vapor[component];
        }
        if (!Double.isFinite(liquidTotal) || !Double.isFinite(vaporTotal) || liquidTotal <= 0.0 || vaporTotal <= 0.0) {
            throw failure("V3 feed flash generated a nonphysical phase composition");
        }
        for (int component = 0; component < overall.length; component++) {
            liquid[component] /= liquidTotal;
            vapor[component] /= vaporTotal;
        }
    }

    private static V3ThermoException failure(String detail) {
        return new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, detail);
    }

    private static Double finite(double value) { return Double.isFinite(value) ? value : null; }

    private static List<Double> numbers(double[] values) {
        if (values == null) return null;
        List<Double> result = new ArrayList<>(values.length);
        for (double value : values) result.add(finite(value));
        return result;
    }

    private static Double delta(double[] first, double[] second) {
        if (first == null) return null;
        double maximum = 0.0;
        for (int component = 0; component < first.length; component++) maximum = Math.max(maximum, Math.abs(first[component] - second[component]));
        return finite(maximum);
    }
}
