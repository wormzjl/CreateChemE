package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Immutable CPU-only baseline inference. Fixed basis/geometry and per-feature coverage are manifest-gated;
 * this model is not advertised as a general refinery graph network.
 */
public final class V3DenseNeuralInitializer implements V3NeuralInitializer {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Document model;
    private V3DenseNeuralInitializer(Document model) { this.model = model; }

    /** Parses and validates a bounded model artifact. The caller owns the stream. */
    public static V3DenseNeuralInitializer read(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Neural model exceeds size limit");
        Document m;
        try {
            m = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class);
        } catch (com.google.gson.JsonParseException malformed) {
            throw new IllegalArgumentException("Malformed neural model JSON", malformed);
        }
        if (m == null || !V3NeuralFeatures.REVISION.equals(m.featureRevision)
                || m.modelId == null || !m.modelId.matches("[A-Za-z0-9._:/-]{1,96}")
                || m.packageId == null || m.propertyRevision == null || m.formulationRevision == null
                || m.packageId.isBlank() || m.propertyRevision.isBlank() || m.formulationRevision.isBlank()
                || m.components == null || m.components.isEmpty() || m.components.size() > 256
                || m.components.stream().anyMatch(x -> x == null || x.isBlank())
                || m.components.stream().distinct().count() != m.components.size()
                || m.stages < 2 || m.stages > 64 || m.branch == null || m.layers == null
                || m.layers.length < 1 || m.layers.length > 8)
            throw new IllegalArgumentException("Invalid model manifest");
        int width = 8 + m.components.size() + 4*(m.stages + 2);
        vector(m.inputMean, width, false); vector(m.inputScale, width, true);
        vector(m.inputMin, width, false); vector(m.inputMax, width, false);
        for (int i = 0; i < width; i++) if (m.inputMin[i] > m.inputMax[i]) throw new IllegalArgumentException("Invalid coverage bounds");
        if (m.coverageGuard != null) {
            CoverageGuard g = m.coverageGuard;
            if (g.projection == null || g.projection.length < 1 || g.projection.length > width
                    || g.centers == null || g.centers.length < 1 || g.centers.length > 2048
                    || !Double.isFinite(g.maximumProjectionMeanSquare) || g.maximumProjectionMeanSquare <= 0
                    || !Double.isFinite(g.maximumNearestMeanSquare) || g.maximumNearestMeanSquare <= 0)
                throw new IllegalArgumentException("Invalid correlated coverage guard");
            for (double[] row : g.projection) vector(row, width, false);
            for (double[] row : g.centers) vector(row, width, false);
        }
        long parameters = 0;
        for (Layer layer : m.layers) {
            if (layer == null || layer.weights == null || layer.weights.length < 1 || layer.weights.length > 40_000
                    || !("tanh".equals(layer.activation) || "linear".equals(layer.activation)))
                throw new IllegalArgumentException("Invalid model layer");
            parameters += (long)width * layer.weights.length;
            if (parameters > 2_000_000) throw new IllegalArgumentException("Model parameter budget exceeded");
            for (double[] row : layer.weights) vector(row, width, false);
            width = layer.weights.length; vector(layer.bias, width, false);
        }
        if (width != (m.stages + 2) * (2*m.components.size() + 3)) throw new IllegalArgumentException("Invalid model output width");
        vector(m.outputMean, width, false); vector(m.outputScale, width, true);
        return new V3DenseNeuralInitializer(m); // Parser-created arrays are privately owned and never exposed.
    }

    @Override public String modelId() { return model.modelId; }

    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        if (!input.packageId().equals(model.packageId) || input.stageCount() != model.stages
                || !input.componentBasis().componentIds().equals(model.components)
                || !V3ColumnCalculator.formulationRevision(input, 0.0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE)
                        .equals(model.formulationRevision)) return Optional.empty();
        double[] x = V3NeuralFeatures.encode(input);
        for (int i = 0; i < x.length; i++) {
            double slack = 1e-10 * Math.max(1, Math.max(Math.abs(model.inputMin[i]), Math.abs(model.inputMax[i])));
            if (!Double.isFinite(x[i]) || x[i] < model.inputMin[i] - slack || x[i] > model.inputMax[i] + slack)
                return Optional.empty();
            x[i] = (x[i] - model.inputMean[i]) / model.inputScale[i];
        }
        if (model.coverageGuard != null && !withinCorrelatedCoverage(x, model.coverageGuard, control)) return Optional.empty();
        for (Layer layer : model.layers) {
            double[] next = new double[layer.bias.length];
            for (int o = 0; o < next.length; o++) {
                if ((o & 31) == 0) control.checkpoint();
                double sum = layer.bias[o];
                for (int i = 0; i < x.length; i++) sum += layer.weights[o][i] * x[i];
                next[o] = "tanh".equals(layer.activation) ? Math.tanh(sum) : sum;
            }
            x = next;
        }
        for (int o = 0; o < x.length; o++) x[o] = model.outputMean[o] + x[o]*model.outputScale[o];
        control.checkpoint();
        return Optional.of(V3NeuralFeatures.decode(input, model.propertyRevision, model.branch, x));
    }

    private static boolean withinCorrelatedCoverage(double[] x, CoverageGuard guard, V3SolveControl control) {
        double[] reconstructed = new double[x.length];
        for (double[] direction : guard.projection) {
            control.checkpoint();
            double dot = 0;
            for (int i = 0; i < x.length; i++) dot += direction[i] * x[i];
            for (int i = 0; i < x.length; i++) reconstructed[i] += dot * direction[i];
        }
        double residual = 0;
        for (int i = 0; i < x.length; i++) residual += square(x[i] - reconstructed[i]);
        if (!Double.isFinite(residual) || residual / x.length > guard.maximumProjectionMeanSquare) return false;
        for (double[] center : guard.centers) {
            control.checkpoint();
            double distance = 0;
            for (int i = 0; i < x.length; i++) distance += square(x[i] - center[i]);
            if (Double.isFinite(distance) && distance / x.length <= guard.maximumNearestMeanSquare) return true;
        }
        return false;
    }

    private static double square(double value) { return value * value; }

    private static void vector(double[] a, int size, boolean positive) {
        if (a == null || a.length != size || Arrays.stream(a).anyMatch(x -> !Double.isFinite(x) || positive && x <= 0))
            throw new IllegalArgumentException("Invalid model vector");
    }

    private static final class Document {
        String modelId, featureRevision, packageId, propertyRevision, formulationRevision;
        List<String> components;
        int stages;
        V3CondenserPhaseBranch branch;
        double[] inputMean, inputScale, inputMin, inputMax, outputMean, outputScale;
        Layer[] layers;
        CoverageGuard coverageGuard;
    }
    private static final class Layer { double[][] weights; double[] bias; String activation; }
    private static final class CoverageGuard {
        double[][] projection, centers;
        double maximumProjectionMeanSquare, maximumNearestMeanSquare;
    }
}
