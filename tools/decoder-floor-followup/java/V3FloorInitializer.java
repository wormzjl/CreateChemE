package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Offline absolute-output Transformer with a pipeline-selected decoder presence-floor rule. Parser-owned weights are immutable after validation.
 * Every prediction owns all scratch arrays; cooperative cancellation is checked inside matrix work.
 */
final class V3FloorInitializer implements V3NeuralInitializer {
    private static final String REVISION = "v3-anchor-augmented-1";
    private static final V3CondenserPhaseBranch[] BRANCHES = {V3CondenserPhaseBranch.LIQUID_ONLY,
            V3CondenserPhaseBranch.TWO_PHASE, V3CondenserPhaseBranch.VAPOR_ONLY};
    private final Document model;
    /** Decoder variant selected by the pipeline manifest, never by the model bytes. */
    private final V3FactorizedNeuralFeatures.DecodeOptions decode;
    private V3FloorInitializer(Document model, V3FactorizedNeuralFeatures.DecodeOptions decode) {
        this.model = model; this.decode = decode;
    }

    static V3FloorInitializer read(InputStream stream) throws IOException {
        return read(stream, V3FactorizedNeuralFeatures.DecodeOptions.NONE);
    }

    static V3FloorInitializer read(InputStream stream, V3FactorizedNeuralFeatures.DecodeOptions decode) throws IOException {
        if (decode == null) throw new IllegalArgumentException("Missing pipeline decode options");
        byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
        if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Transformer artifact exceeds size limit");
        Document m = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class);
        if (m == null || !REVISION.equals(m.featureRevision) || !"anchor-augmented".equals(m.modelType)
                || !("compact".equals(m.anchorLayout) || "full".equals(m.anchorLayout))
                || m.modelId == null || !m.modelId.matches("[A-Za-z0-9._:/-]{1,96}")
                || m.packageId == null || m.propertyRevision == null || m.components == null || m.components.size() != 20
                || m.components.stream().anyMatch(s -> s == null || s.isBlank()) || m.components.stream().distinct().count() != 20
                || m.formulationRevisions == null || m.formulationRevisions.isEmpty()
                || m.branchesSeen == null || m.branchesSeen.length != 3 || !(m.branchesSeen[0] || m.branchesSeen[1] || m.branchesSeen[2])
                || m.presenceThreshold != .02 || m.traceFloorFraction != V3TruncationSupport.TRACE_FLOOR_FRACTION
                || m.normalization == null || m.weights == null || m.designConstraints == null)
            throw new IllegalArgumentException("Invalid transformer manifest");
        for (String name : List.of("xm", "xscale", "ym", "yscale", "gm", "gscale", "bm", "bscale")) {
            int count = name.startsWith("x") ? 96 : name.startsWith("g") ? 74 : 85;
            vector(m.normalization.get(name), count, name.endsWith("scale"));
        }
        vector(m.globalMin, 74, false); vector(m.globalMax, 74, false);
        for (int i = 0; i < 74; i++) if (m.globalMin[i] > m.globalMax[i]) throw new IllegalArgumentException("Invalid bounds");
        Design d = m.designConstraints;
        if (!Double.isFinite(d.minimumNodePressurePascal) || !Double.isFinite(d.maximumNodePressurePascal)
                || d.minimumNodePressurePascal <= 0 || d.maximumNodePressurePascal < d.minimumNodePressurePascal
                || d.pumparoundSplits == null || d.pumparoundSplits.stream().anyMatch(s -> s == null))
            throw new IllegalArgumentException("Invalid design constraints");
        var result = new V3FloorInitializer(m, decode);
        result.checkLinear("embed", inputs(m), 64); result.checkNorm("output.0"); result.checkLinear("output.1", 64, 85);
        result.checkLinear("branch.0", 74, 64); result.checkLinear("branch.2", 64, 3);
        for (int i = 0; i < 2; i++) {
            String p = "blocks." + i + ".";
            if (m.modelType.equals("anchor-augmented")) {
                result.checkNorm(p + "norm1"); result.checkNorm(p + "norm2");
                result.check(p + "self_attn.in_proj_weight", 192, 64); result.check(p + "self_attn.in_proj_bias", 192);
                result.checkLinear(p + "self_attn.out_proj", 64, 64);
                result.checkLinear(p + "linear1", 64, 128); result.checkLinear(p + "linear2", 128, 64);
            } else {
                result.checkNorm(p + "0"); result.checkLinear(p + "1", 64, 256); result.checkLinear(p + "3", 256, 64);
            }
        }
        long expected = 83800 + (inputs(m) - 96) * 64L;
        if (result.parameterStorageBytes() != expected * Double.BYTES) throw new IllegalArgumentException("Unexpected parameter storage");
        return result;
    }

    private static int inputs(Document document) { return "compact".equals(document.anchorLayout) ? 103 : 185; }

    @Override public String modelId() { return model.modelId; }

    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        if (!supported(input)) return Optional.empty();
        Raw raw = raw(input, control);
        int best = -1;
        boolean reflux = input.specifications().stream().anyMatch(s -> s instanceof V3ColumnSpecification.OrganicRefluxRatio r && r.ratio() > 0);
        for (int i = 0; i < 3; i++) if (model.branchesSeen[i] && !(i == 2 && reflux)
                && (best < 0 || raw.branchLogits[i] > raw.branchLogits[best])) best = i;
        if (best < 0 || !Double.isFinite(raw.branchLogits[best])) return Optional.empty();
        try { return Optional.of(V3FactorizedNeuralFeatures.decode(input, model.propertyRevision, BRANCHES[best], raw.values, model.presenceThreshold, decode)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private boolean supported(V3ColumnInput input) {
        if (!input.packageId().equals(model.packageId) || !input.componentBasis().componentIds().equals(model.components)
                || input.stageCount() < 2 || input.stageCount() > 64 || input.feedStageNumber() < 1 || input.feedStageNumber() > input.stageCount()
                || input.pumparounds().size() > 4 || input.sideDraws().size() > 3 || input.steamFeeds().size() > 2
                || !model.formulationRevisions.contains(V3ColumnCalculator.formulationRevision(input, 0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE))) return false;
        Design d = model.designConstraints;
        double bottom = input.topPressurePascal() + (input.stageCount() - 1) * input.stagePressureDropPascal();
        double slack = 1e-9 * Math.max(1, d.maximumNodePressurePascal);
        if (input.topPressurePascal() < d.minimumNodePressurePascal - slack || !Double.isFinite(bottom) || bottom > d.maximumNodePressurePascal + slack) return false;
        for (var pa : input.pumparounds()) if (!d.pumparoundSplits.contains(pa.split())) return false;
        for (var steam : input.steamFeeds()) if (d.steamAtSumpOnly && steam.stageNumber() != input.stageCount() + 1) return false;
        double[] g = V3GeneralNeuralFeatures.global(input);
        for (int i = 0; i < g.length; i++) {
            slack = 1e-9 * Math.max(1, Math.max(Math.abs(model.globalMin[i]), Math.abs(model.globalMax[i])));
            if (!Double.isFinite(g[i]) || g[i] < model.globalMin[i] - slack || g[i] > model.globalMax[i] + slack) return false;
        }
        return true;
    }

    /** Request-owned diagnostic values, only exposed within the offline tool package. */
    record Raw(double[][] values, double[] branchLogits) {}

    Raw raw(V3ColumnInput input, V3SolveControl control) {
        double[] global = normalize(V3GeneralNeuralFeatures.global(input), "g");
        double[] branch = linear(gelu(linear(global, "branch.0", control)), "branch.2", control);
        int best = -1;
        boolean reflux = input.specifications().stream().anyMatch(s -> s instanceof V3ColumnSpecification.OrganicRefluxRatio r && r.ratio() > 0);
        for (int i = 0; i < 3; i++) if (model.branchesSeen[i] && !(i == 2 && reflux)
                && (best < 0 || branch[i] > branch[best])) best = i;
        if (best < 0 || !Double.isFinite(branch[best])) throw new IllegalArgumentException("No legal branch");
        var anchor = V3HybridBaseline.build(input, BRANCHES[best], control);
        double[][] features = V3GeneralNeuralFeatures.nodes(input, V3CondenserPhaseBranch.TWO_PHASE);
        double[][] hidden = new double[features.length][];
        for (int i = 0; i < features.length; i++) {
            double[] joined = new double[inputs(model)];
            System.arraycopy(normalize(Arrays.copyOf(features[i], 96), "x"), 0, joined, 0, 96);
            double[] encoded = normalize(anchor.values()[i], "b");
            int at = 96;
            for (int coordinate = 0; coordinate < 85; coordinate++)
                if ("full".equals(model.anchorLayout) || coordinate < 3)
                    joined[at++] = encoded[coordinate];
            joined[at + best] = 1;
            joined[at + 3] = anchor.available() ? 1 : 0;
            hidden[i] = linear(joined, "embed", control);
        }
        for (int block = 0; block < 2; block++) {
            control.checkpoint(); String p = "blocks." + block + ".";
            if (model.modelType.equals("anchor-augmented")) {
                double[][] normalized = new double[hidden.length][];
                for (int i = 0; i < hidden.length; i++) normalized[i] = norm(hidden[i], p + "norm1");
                double[][] attended = attention(normalized, p + "self_attn.", control);
                for (int i = 0; i < hidden.length; i++) {
                    add(hidden[i], attended[i]);
                    add(hidden[i], linear(gelu(linear(norm(hidden[i], p + "norm2"), p + "linear1", control)), p + "linear2", control));
                }
            } else for (double[] h : hidden) add(h, linear(gelu(linear(norm(h, p + "0"), p + "1", control)), p + "3", control));
        }
        double[][] values = new double[hidden.length][];
        for (int n = 0; n < hidden.length; n++) {
            values[n] = linear(norm(hidden[n], "output.0"), "output.1", control);
            for (int i = 0; i < 85; i++) values[n][i] = values[n][i] * model.normalization.get("yscale")[i] + model.normalization.get("ym")[i];
        }
        return new Raw(values, branch);
    }

    private double[][] attention(double[][] input, String p, V3SolveControl control) {
        int n = input.length;
        double[][] qkv = new double[n][], output = new double[n][];
        for (int i = 0; i < n; i++) qkv[i] = dense(input[i], p + "in_proj_weight", p + "in_proj_bias", control);
        for (int i = 0; i < n; i++) {
            control.checkpoint(); double[] joined = new double[64];
            for (int head = 0; head < 4; head++) {
                double[] scores = new double[n]; double max = -Double.MAX_VALUE, sum = 0;
                for (int j = 0; j < n; j++) {
                    for (int k = 0; k < 16; k++) scores[j] += qkv[i][head * 16 + k] * qkv[j][64 + head * 16 + k];
                    scores[j] *= .25; max = Math.max(max, scores[j]);
                }
                for (int j = 0; j < n; j++) { scores[j] = Math.exp(scores[j] - max); sum += scores[j]; }
                for (int j = 0; j < n; j++) for (int k = 0; k < 16; k++) joined[head * 16 + k] += scores[j] / sum * qkv[j][128 + head * 16 + k];
            }
            output[i] = linear(joined, p + "out_proj", control);
        }
        return output;
    }

    private double[] normalize(double[] x, String p) {
        double[] result = new double[x.length], mean = model.normalization.get(p + "m"), scale = model.normalization.get(p + "scale");
        for (int i = 0; i < x.length; i++) result[i] = (x[i] - mean[i]) / scale[i];
        return result;
    }
    private double[] norm(double[] x, String p) {
        double mean = Arrays.stream(x).average().orElseThrow(), variance = 0;
        for (double v : x) variance += (v - mean) * (v - mean);
        double scale = 1 / Math.sqrt(variance / x.length + 1e-5);
        double[] result = new double[x.length], weight = model.weights.get(p + ".weight").values, bias = model.weights.get(p + ".bias").values;
        for (int i = 0; i < x.length; i++) result[i] = (x[i] - mean) * scale * weight[i] + bias[i];
        return result;
    }
    private double[] linear(double[] x, String p, V3SolveControl control) { return dense(x, p + ".weight", p + ".bias", control); }
    private double[] dense(double[] x, String w, String b, V3SolveControl control) {
        double[] weights = model.weights.get(w).values, bias = model.weights.get(b).values, result = new double[bias.length];
        for (int o = 0; o < result.length; o++) {
            if ((o & 15) == 0) control.checkpoint(); double value = bias[o];
            for (int i = 0; i < x.length; i++) value += weights[o * x.length + i] * x[i];
            result[o] = value;
        }
        return result;
    }
    private static void add(double[] target, double[] source) { for (int i = 0; i < target.length; i++) target[i] += source[i]; }
    private static double[] gelu(double[] x) {
        // A&S erf approximation (maximum absolute error about 1.5e-7).
        // Its effect on complete model outputs is bounded by the parity gate.
        for (int i = 0; i < x.length; i++) {
            double v = x[i] / Math.sqrt(2), a = Math.abs(v), t = 1 / (1 + .3275911 * a);
            double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - .284496736) * t + .254829592) * t * Math.exp(-a * a);
            x[i] *= .5 * (1 + Math.copySign(erf, v));
        }
        return x;
    }
    long parameterStorageBytes() { return model.weights.values().stream().mapToLong(t -> t.values.length * (long) Double.BYTES).sum(); }
    private void checkLinear(String p, int in, int out) { check(p + ".weight", out, in); check(p + ".bias", out); }
    private void checkNorm(String p) { check(p + ".weight", 64); check(p + ".bias", 64); }
    private void check(String name, int... shape) {
        Tensor t = model.weights.get(name);
        if (t == null || !Arrays.equals(t.shape, shape)) throw new IllegalArgumentException("Invalid tensor: " + name);
        int size = 1; for (int d : shape) size *= d; vector(t.values, size, false);
    }
    private static void vector(double[] x, int size, boolean positive) {
        if (x == null || x.length != size || Arrays.stream(x).anyMatch(v -> !Double.isFinite(v) || positive && v <= 0)) throw new IllegalArgumentException("Invalid model vector");
    }
    private static final class Tensor { int[] shape; double[] values; }
    private static final class Design { double minimumNodePressurePascal, maximumNodePressurePascal; boolean steamAtSumpOnly; List<V3PumparoundSpec.Split> pumparoundSplits; }
    private static final class Document {
        String featureRevision, modelType, modelId, packageId, propertyRevision, anchorLayout;
        List<String> components, formulationRevisions; boolean[] branchesSeen;
        double presenceThreshold, traceFloorFraction; double[] globalMin, globalMax;
        Map<String, double[]> normalization; Map<String, Tensor> weights; Design designConstraints;
    }
}
