package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Immutable bounded CPU inference with a global phase classifier and a shared stage-conditioned MLP. */
public final class V3GeneralNeuralInitializer implements V3NeuralInitializer {
    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private static final V3CondenserPhaseBranch[] BRANCHES = {
            V3CondenserPhaseBranch.LIQUID_ONLY, V3CondenserPhaseBranch.TWO_PHASE, V3CondenserPhaseBranch.VAPOR_ONLY};
    private final Document model;
    private V3GeneralNeuralInitializer(Document model) { this.model = model; }

    /** Parses a bounded model artifact. Arrays are parser-owned and never exposed. Caller owns the stream. */
    public static V3GeneralNeuralInitializer read(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("General neural model exceeds size budget");
        Document m;
        try { m = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class); }
        catch (JsonParseException malformed) { throw new IllegalArgumentException("Malformed general neural model", malformed); }
        if (m == null || !V3GeneralNeuralFeatures.REVISION.equals(m.featureRevision)
                || m.modelId == null || !m.modelId.matches("[A-Za-z0-9._:/-]{1,96}")
                || m.packageId == null || m.packageId.isBlank() || m.propertyRevision == null || m.propertyRevision.isBlank()
                || m.components == null || m.components.isEmpty() || m.components.size() > 64
                || m.components.stream().anyMatch(x -> x == null || x.isBlank())
                || m.components.stream().distinct().count() != m.components.size()
                || m.formulationRevisions == null || m.formulationRevisions.isEmpty() || m.formulationRevisions.size() > 64
                || m.formulationRevisions.stream().anyMatch(x -> x == null || x.isBlank())
                || m.minimumStages < 2 || m.maximumStages > 64 || m.minimumStages > m.maximumStages
                || m.branchesSeen == null || m.branchesSeen.length != 3)
            throw new IllegalArgumentException("Invalid general neural manifest");
        boolean anyBranch = false;
        for (boolean seen : m.branchesSeen) anyBranch |= seen;
        if (!anyBranch) throw new IllegalArgumentException("General neural model has no trained phase branch");
        int global = V3GeneralNeuralFeatures.globalWidth(m.components.size());
        int node = V3GeneralNeuralFeatures.nodeWidth(m.components.size());
        int output = V3GeneralNeuralFeatures.outputWidth(m.components.size());
        vector(m.globalMean, global, false); vector(m.globalScale, global, true);
        vector(m.globalMin, global, false); vector(m.globalMax, global, false);
        for (int i = 0; i < global; i++) if (m.globalMin[i] > m.globalMax[i]) throw new IllegalArgumentException("Invalid neural coverage bounds");
        vector(m.nodeMean, node, false); vector(m.nodeScale, node, true);
        vector(m.outputMean, output, false); vector(m.outputScale, output, true);
        long weights = network(m.branchLayers, global, 3) + network(m.nodeLayers, node, output);
        if (weights > 2_000_000) throw new IllegalArgumentException("General neural parameter budget exceeded");
        if (m.coverageGuard != null) {
            if (m.coverageGuard.centers == null || m.coverageGuard.centers.length == 0 || m.coverageGuard.centers.length > 4096
                    || !Double.isFinite(m.coverageGuard.maximumNearestMeanSquare) || m.coverageGuard.maximumNearestMeanSquare <= 0)
                throw new IllegalArgumentException("Invalid general correlated coverage guard");
            for (double[] center : m.coverageGuard.centers) vector(center, global, false);
        }
        if (m.designConstraints != null) {
            DesignConstraints limits = m.designConstraints;
            if (!Double.isFinite(limits.minimumNodePressurePascal) || limits.minimumNodePressurePascal <= 0
                    || !Double.isFinite(limits.maximumNodePressurePascal)
                    || limits.minimumNodePressurePascal > limits.maximumNodePressurePascal
                    || limits.pumparoundSplits == null || limits.pumparoundSplits.size() > 2
                    || limits.pumparoundSplits.stream().anyMatch(x -> x == null)
                    || limits.pumparoundSplits.stream().distinct().count() != limits.pumparoundSplits.size())
                throw new IllegalArgumentException("Invalid general neural design constraints");
        }
        return new V3GeneralNeuralInitializer(m);
    }

    @Override public String modelId() { return model.modelId; }

    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        if (!input.packageId().equals(model.packageId) || !input.componentBasis().componentIds().equals(model.components)
                || input.stageCount() < model.minimumStages || input.stageCount() > model.maximumStages
                || input.feedStageNumber() < 1 || input.feedStageNumber() > input.stageCount()
                || input.pumparounds().size() > 4 || input.sideDraws().size() > 3 || input.steamFeeds().size() > 2
                || !model.formulationRevisions.contains(V3ColumnCalculator.formulationRevision(input, 0,
                        V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE))) return Optional.empty();
        if (model.designConstraints != null && !withinDesign(input, model.designConstraints)) return Optional.empty();
        double[] global = V3GeneralNeuralFeatures.global(input);
        for (int i = 0; i < global.length; i++) {
            double slack = 1e-9 * Math.max(1, Math.max(Math.abs(model.globalMin[i]), Math.abs(model.globalMax[i])));
            if (!Double.isFinite(global[i]) || global[i] < model.globalMin[i] - slack || global[i] > model.globalMax[i] + slack)
                return Optional.empty();
            global[i] = (global[i] - model.globalMean[i]) / model.globalScale[i];
        }
        if (model.coverageGuard != null && !withinCoverage(global, control)) return Optional.empty();
        double[] logits = evaluate(model.branchLayers, global, control);
        boolean refluxPositive = input.specifications().stream()
                .anyMatch(spec -> spec instanceof V3ColumnSpecification.OrganicRefluxRatio ratio && ratio.ratio() > 0);
        int best = -1;
        for (int b = 0; b < logits.length; b++)
            if (model.branchesSeen[b] && !(b == 2 && refluxPositive) && (best < 0 || logits[b] > logits[best])) best = b;
        if (best < 0 || !Double.isFinite(logits[best])) return Optional.empty();
        V3CondenserPhaseBranch branch = BRANCHES[best];
        double[][] nodes = V3GeneralNeuralFeatures.nodes(input, branch);
        double[][] output = new double[nodes.length][];
        for (int n = 0; n < nodes.length; n++) {
            control.checkpoint();
            for (int i = 0; i < nodes[n].length; i++) nodes[n][i] = (nodes[n][i] - model.nodeMean[i]) / model.nodeScale[i];
            output[n] = evaluate(model.nodeLayers, nodes[n], control);
            for (int i = 0; i < output[n].length; i++) output[n][i] = model.outputMean[i] + model.outputScale[i] * output[n][i];
        }
        control.checkpoint();
        try { return Optional.of(V3GeneralNeuralFeatures.decode(input, model.propertyRevision, branch, output)); }
        catch (IllegalArgumentException invalidPrediction) { return Optional.empty(); }
    }

    /** Primitive model storage, excluding Java object headers and request-local scratch arrays. */
    public long parameterStorageBytes() {
        long count = 0;
        for (Layer[] network : new Layer[][] { model.branchLayers, model.nodeLayers }) for (Layer layer : network) {
            count += layer.bias.length;
            for (double[] row : layer.weights) count += row.length;
        }
        return count * Double.BYTES;
    }

    private boolean withinCoverage(double[] x, V3SolveControl control) {
        for (double[] center : model.coverageGuard.centers) {
            control.checkpoint(); double squared = 0;
            for (int i = 0; i < x.length; i++) { double difference = x[i] - center[i]; squared += difference * difference; }
            if (Double.isFinite(squared) && squared / x.length <= model.coverageGuard.maximumNearestMeanSquare) return true;
        }
        return false;
    }

    private static boolean withinDesign(V3ColumnInput input, DesignConstraints limits) {
        double bottomPressure = input.topPressurePascal() + (input.stageCount() - 1) * input.stagePressureDropPascal();
        double slack = 1e-9 * Math.max(1, limits.maximumNodePressurePascal);
        if (input.topPressurePascal() < limits.minimumNodePressurePascal - slack
                || !Double.isFinite(bottomPressure) || bottomPressure > limits.maximumNodePressurePascal + slack) return false;
        for (V3SteamFeedSpec steam : input.steamFeeds())
            if (limits.steamAtSumpOnly && steam.stageNumber() != input.stageCount() + 1) return false;
        for (V3PumparoundSpec pa : input.pumparounds()) if (!limits.pumparoundSplits.contains(pa.split())) return false;
        return true;
    }

    private static double[] evaluate(Layer[] layers, double[] input, V3SolveControl control) {
        double[] x = input;
        for (Layer layer : layers) {
            double[] next = new double[layer.bias.length];
            for (int o = 0; o < next.length; o++) {
                if ((o & 31) == 0) control.checkpoint();
                double sum = layer.bias[o];
                for (int i = 0; i < x.length; i++) sum += layer.weights[o][i] * x[i];
                next[o] = "tanh".equals(layer.activation) ? Math.tanh(sum) : sum;
            }
            x = next;
        }
        return x;
    }

    private static long network(Layer[] layers, int width, int expectedOutput) {
        if (layers == null || layers.length < 1 || layers.length > 6) throw new IllegalArgumentException("Invalid general neural layers");
        long parameters = 0;
        for (Layer layer : layers) {
            if (layer == null || layer.weights == null || layer.weights.length < 1 || layer.weights.length > 1024
                    || !("tanh".equals(layer.activation) || "linear".equals(layer.activation)))
                throw new IllegalArgumentException("Invalid general neural layer");
            parameters += (long) width * layer.weights.length + layer.weights.length;
            if (parameters > 2_000_000) throw new IllegalArgumentException("General neural parameter budget exceeded");
            for (double[] row : layer.weights) vector(row, width, false);
            width = layer.weights.length; vector(layer.bias, width, false);
        }
        if (width != expectedOutput) throw new IllegalArgumentException("Invalid general neural output dimension");
        return parameters;
    }

    private static void vector(double[] values, int width, boolean positive) {
        if (values == null || values.length != width || Arrays.stream(values).anyMatch(x -> !Double.isFinite(x) || positive && x <= 0))
            throw new IllegalArgumentException("Invalid general neural vector");
    }
    private static final class Document {
        String modelId, featureRevision, packageId, propertyRevision;
        List<String> components, formulationRevisions;
        int minimumStages, maximumStages;
        boolean[] branchesSeen;
        double[] globalMean, globalScale, globalMin, globalMax, nodeMean, nodeScale, outputMean, outputScale;
        Layer[] branchLayers, nodeLayers;
        CoverageGuard coverageGuard;
        DesignConstraints designConstraints;
    }
    private static final class Layer { double[][] weights; double[] bias; String activation; }
    private static final class CoverageGuard { double[][] centers; double maximumNearestMeanSquare; }
    private static final class DesignConstraints {
        double minimumNodePressurePascal, maximumNodePressurePascal;
        boolean steamAtSumpOnly;
        List<V3PumparoundSpec.Split> pumparoundSplits;
    }
}
