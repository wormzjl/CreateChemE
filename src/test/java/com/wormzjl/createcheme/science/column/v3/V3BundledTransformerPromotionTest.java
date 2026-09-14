package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * The bundled Transformer is the production initializer, and it is the qualified one.
 *
 * <p>The campaign that qualified it ran offline against the same weight bytes, the same decoder rule and
 * the same correction rule. These tests bind the shipped defaults to that campaign: the artifact's bytes,
 * the model's identity, the correction rule, and — case by case — the seeds the decoder actually produces
 * against the digests the study committed for its winning pipeline {@code F0-progress-phase-floor}.</p>
 */
class V3BundledTransformerPromotionTest {
    private static final String F0_SHA256 = "7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962";
    private static final String F0_MODEL_ID = "trace-followup/F-20260911-s4160";
    private static final String PACKAGE_ID = "createcheme:tjl20_methane";
    private static final Path INPUTS = Path.of("tools", "transformer-promotion", "inputs", "validation-inputs.jsonl");
    private static final Path DIGESTS =
            Path.of("tools", "neural-budget", "evidence", "decode-seed-digests-F0-progress-phase-floor.jsonl.gz");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    // (a) and (d): the artifact the mod ships is the registered one, it parses once, and the model it
    // yields is the registered model on the registered property dataset.
    @Test void theBundledArtifactIsTheRegisteredF0AndParsesIntoTheProductionModel() throws Exception {
        byte[] artifact;
        try (InputStream stream = V3NeuralModels.class.getResourceAsStream(V3NeuralModels.ARTIFACT)) {
            assertNotNull(stream, "the bundled Transformer artifact must be on the classpath");
            artifact = stream.readAllBytes();
        }
        assertEquals(F0_SHA256, sha256(artifact), "bundled weight bytes are not the registered F0 export");

        var model = V3NeuralModels.bundled();
        assertNotSame(V3NeuralInitializer.UNAVAILABLE, model, "the bundled model failed to parse");
        assertEquals(F0_MODEL_ID, model.modelId());
        var transformer = assertInstanceOf(V3AnchorTransformerInitializer.class, model);
        assertEquals(89_496L, transformer.parameterCount());
        assertEquals(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID).datasetRevision(),
                transformer.propertyRevision(),
                "a seed labelled against another dataset revision would be inadmissible, never rescaled");
        // The startup preload resolves this same holder; it is parsed once and safely published.
        assertSame(model, V3NeuralModels.bundled());
    }

    // (b) the shipped correction rule is exactly the one the study registered as PROGRESS_CORRECTION.
    @Test void theDefaultCorrectionRuleIsTheQualifiedProgressRule() {
        var qualified = new V3InitializationOptions.Correction(8, 48, 8, 0.5, 8, 0.9, 1e-6);
        assertEquals(qualified, V3InitializationOptions.Correction.PROGRESS);
        assertEquals(qualified, V3InitializationOptions.DEFAULT.correction());
        assertEquals(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.DEFAULT.mode());
        assertEquals(V3InitializationOptions.WetStart.AUTO, V3InitializationOptions.DEFAULT.wetStart());
        assertEquals(16, V3InitializationOptions.DEFAULT.maximumIterations(), "the base cap is unchanged");
        assertEquals(2_000, V3InitializationOptions.DEFAULT.budgetMilliseconds(), "the wall is unchanged");
        assertTrue(qualified.extendsAttempts());
        assertTrue(qualified.stopsEarly());

        // The classical route runs no learned correction, so no progress rule applies to it.
        assertEquals(V3InitializationOptions.Correction.WALLS, V3InitializationOptions.CURRENT.correction());
        assertFalse(V3InitializationOptions.CURRENT.correction().extendsAttempts());
        assertFalse(V3InitializationOptions.CURRENT.correction().stopsEarly());
    }

    // (c) per-case decoded-seed parity with the committed evidence of the qualified pipeline.
    @Test void decodedSeedsReproduceTheQualifiedPipelineDigests() throws Exception {
        var expected = expectedDigests();
        assertEquals(405, expected.size(), "the committed digest evidence must cover the frozen population");
        var requests = requests();
        assertEquals(405, requests.size());

        var model = V3NeuralModels.bundled();
        Map<String, String> observed = new ConcurrentHashMap<>();
        List<String> unsupported = java.util.Collections.synchronizedList(new ArrayList<>());
        requests.entrySet().parallelStream().forEach(entry -> {
            var seed = model.predict(entry.getValue(), V3SolveControl.UNBOUNDED).orElse(null);
            if (seed == null) unsupported.add(entry.getKey());
            else observed.put(entry.getKey(), seedDigest(seed));
        });

        var mismatched = new ArrayList<String>();
        for (var entry : expected.entrySet()) {
            String actual = observed.get(entry.getKey());
            if (!java.util.Objects.equals(entry.getValue(), actual)) mismatched.add(entry.getKey());
        }
        assertEquals(List.of(), mismatched,
                "decoded seeds diverged from the qualified pipeline for " + mismatched.size() + " cases");
        assertEquals(expected.entrySet().stream().filter(e -> e.getValue() == null).count(), unsupported.size(),
                "coverage moved: the model declined a different set of requests than the study recorded");
    }

    /** The digest routine of the study's sealing step, ported: Gson's tree, then Python's canonical dump. */
    static String seedDigest(V3NeuralSeed seed) {
        var text = new StringBuilder();
        canonicalize(JSON.toJsonTree(seed), text);
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code json.dumps(value, sort_keys=True, separators=(',', ':'))}.
     *
     * <p>The committed digests were taken over CPython's re-dump of this probe's own Gson output, so the
     * number formatting that matters is CPython's {@code repr}, not {@code Double.toString}. Both emit the
     * shortest decimal that round-trips; they disagree only about when to switch to exponent notation and
     * how to spell the exponent, which is what {@link #pythonRepr} fixes up.</p>
     */
    private static void canonicalize(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) { out.append("null"); return; }
        if (element instanceof JsonArray array) {
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) out.append(',');
                canonicalize(array.get(i), out);
            }
            out.append(']');
            return;
        }
        if (element instanceof JsonObject object) {
            out.append('{');
            boolean first = true;
            var sorted = new TreeMap<String, JsonElement>();
            for (var entry : object.entrySet()) sorted.put(entry.getKey(), entry.getValue());
            for (var entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(entry.getKey(), out);
                out.append(':');
                canonicalize(entry.getValue(), out);
            }
            out.append('}');
            return;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) { out.append(primitive.getAsBoolean() ? "true" : "false"); return; }
        if (primitive.isString()) { quote(primitive.getAsString(), out); return; }
        Number number = primitive.getAsNumber();
        // Gson's tree writer boxes a JSON integer as Long and a JSON float as Double; CPython's json keeps
        // that same distinction across a load/dump round trip, so the two are matched here rather than guessed.
        if (number instanceof Double value) out.append(pythonRepr(value));
        else out.append(number.toString());
    }

    /** CPython's {@code repr} of a finite double, which is what {@code json.dumps} emits for a float. */
    static String pythonRepr(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value))
            throw new IllegalArgumentException("A seed never carries a nonfinite value");
        if (value == 0.0) return 1 / value < 0 ? "-0.0" : "0.0";
        String sign = value < 0 ? "-" : "";
        String shortest = Double.toString(Math.abs(value));
        int e = shortest.indexOf('E');
        int exponent = e < 0 ? 0 : Integer.parseInt(shortest.substring(e + 1));
        String mantissa = e < 0 ? shortest : shortest.substring(0, e);
        int point = mantissa.indexOf('.');
        String digits = mantissa.substring(0, point) + mantissa.substring(point + 1);
        // decimal = the number of digits that precede the point when written plainly.
        int decimal = point + exponent;
        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') { lead++; decimal--; }
        digits = digits.substring(lead);
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        digits = digits.substring(0, end);
        int scientific = decimal - 1;
        if (scientific >= -4 && scientific < 16) {
            if (decimal <= 0) return sign + "0." + "0".repeat(-decimal) + digits;
            if (decimal >= digits.length()) return sign + digits + "0".repeat(decimal - digits.length()) + ".0";
            return sign + digits.substring(0, decimal) + "." + digits.substring(decimal);
        }
        String head = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + head + "e" + (scientific < 0 ? "-" : "+")
                + (Math.abs(scientific) < 10 ? "0" : "") + Math.abs(scientific);
    }

    /** A guard on the port itself: these are CPython's reprs, checked by hand. */
    @Test void thePortedNumberFormatterMatchesCPython() {
        assertEquals("0.0", pythonRepr(0.0));
        assertEquals("-0.0", pythonRepr(-0.0));
        assertEquals("1.0", pythonRepr(1.0));
        assertEquals("-2.5", pythonRepr(-2.5));
        assertEquals("101325.0", pythonRepr(101325.0));
        assertEquals("0.0001", pythonRepr(1e-4));
        assertEquals("1e-05", pythonRepr(1e-5));
        assertEquals("1e-10", pythonRepr(1e-10));
        assertEquals("1.5e-10", pythonRepr(1.5e-10));
        assertEquals("0.1", pythonRepr(0.1));
        assertEquals("1000000000000000.0", pythonRepr(1e15));
        assertEquals("1e+16", pythonRepr(1e16));
        assertEquals("1.2345e+20", pythonRepr(1.2345e20));
        assertEquals("300.15", pythonRepr(300.15));
        assertEquals("3.7e-05", pythonRepr(3.7e-5));
        assertEquals("1.7976931348623157e+308", pythonRepr(Double.MAX_VALUE));
        // Subnormals are left out on purpose: Double.toString does not shorten them and no seed carries one.
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // json.dumps defaults to ensure_ascii=True, so anything outside printable ASCII escapes.
                    if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** id -> committed seed digest, or null where the qualified pipeline produced no seed. */
    private static Map<String, String> expectedDigests() throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var stream = new GZIPInputStream(Files.newInputStream(DIGESTS));
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) continue;
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                JsonElement digest = row.get("seedSha256");
                result.put(row.get("id").getAsString(), digest == null || digest.isJsonNull() ? null : digest.getAsString());
            }
        }
        return result;
    }

    private static Map<String, V3ColumnInput> requests() throws Exception {
        var result = new LinkedHashMap<String, V3ColumnInput>();
        for (String line : Files.readAllLines(INPUTS, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonObject row = JsonParser.parseString(line).getAsJsonObject();
            result.put(row.get("id").getAsString(), input(row.getAsJsonObject("input")));
        }
        return result;
    }

    /** The frozen population's input schema, as every offline probe in this line reads it. */
    private static V3ColumnInput input(JsonObject json) {
        var specs = new ArrayList<V3ColumnSpecification>();
        for (JsonElement e : json.getAsJsonArray("specifications")) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("kelvin")) specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if (s.has("ratio")) specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(), json.get("packageId").getAsString(),
                json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"), String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"), double[].class),
                json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(), json.get("feedStageNumber").getAsInt(),
                json.get("topPressurePascal").getAsDouble(), json.get("stagePressureDropPascal").getAsDouble(), specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"), V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"), V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"), V3PumparoundSpec[].class)));
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
