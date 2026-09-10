package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable nonparametric profile transfer, using only qualified training references.
 * A transferred profile is an initial guess; the native corrector owns every acceptance decision.
 */
public final class V3NearestProfileInitializer implements V3NeuralInitializer {
    public static final String MODEL_TYPE = "nearest-profile";
    public static final String TRANSFER_REVISION = "feed-anchored-rectifying-clamp-1";
    private static final int MAXIMUM_BYTES = 32 * 1024 * 1024;
    private static final int MAXIMUM_REFERENCES = 2048;
    private static final Set<String> COHORT_POLICIES = Set.of("steam-equipment-counts", "steam-only");
    private final Document model;
    private final double totalFeatureWeight;
    private final long primitiveStorageBytes;

    private V3NearestProfileInitializer(Document model) {
        this.model = model;
        totalFeatureWeight = Arrays.stream(model.featureWeights).sum();
        long doubles = 5L * model.globalMean.length + 4; // Bounds, normalization, distance weights/limit and cached weight sum.
        long bytes = 3L * Integer.BYTES + 1 + Long.BYTES; // Manifest geometry/k, design steam flag and this stored byte count.
        for (Reference reference : model.references) {
            int nodes = reference.stageCount + 2;
            doubles += reference.normalizedGlobal.length + reference.componentFeed.length
                    + 2L * nodes * reference.componentFeed.length + 2L * nodes + 1;
            bytes += 4L * Integer.BYTES + nodes + 2; // Geometry/counts, wet mask, source flags.
        }
        primitiveStorageBytes = doubles * Double.BYTES + bytes;
    }

    /** Reads a bounded, privately owned reference library. The caller owns and closes the stream. */
    public static V3NearestProfileInitializer read(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        byte[] bytes = stream.readNBytes(MAXIMUM_BYTES + 1);
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Nearest profile library exceeds 32 MiB");
        Document model;
        try { model = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class); }
        catch (JsonParseException malformed) { throw new IllegalArgumentException("Malformed nearest profile artifact", malformed); }
        if (model == null || !MODEL_TYPE.equals(model.modelType)
                || !V3GeneralNeuralFeatures.REVISION.equals(model.featureRevision)
                || !TRANSFER_REVISION.equals(model.transferRevision)
                || model.modelId == null || !model.modelId.matches("[A-Za-z0-9._:/-]{1,96}")
                || model.packageId == null || model.packageId.isBlank()
                || model.propertyRevision == null || model.propertyRevision.isBlank() || model.propertyRevision.length() > 128
                || model.components == null || model.components.size() != 20
                || model.components.stream().anyMatch(value -> value == null || value.isBlank())
                || model.components.stream().distinct().count() != model.components.size()
                || model.formulationRevisions == null || model.formulationRevisions.isEmpty()
                || model.formulationRevisions.size() > 64
                || model.formulationRevisions.stream().anyMatch(value -> value == null || value.isBlank())
                || model.minimumStages < 2 || model.maximumStages > 64 || model.minimumStages > model.maximumStages
                || !(model.neighbors == 1 || model.neighbors == 3)
                || model.cohortPolicy == null || !COHORT_POLICIES.contains(model.cohortPolicy)
                || !Double.isFinite(model.maximumMeanSquareDistance) || model.maximumMeanSquareDistance <= 0
                || model.references == null || model.references.length < 1 || model.references.length > MAXIMUM_REFERENCES)
            throw new IllegalArgumentException("Invalid nearest profile manifest");
        int width = V3GeneralNeuralFeatures.globalWidth(model.components.size());
        vector(model.globalMean, width, false); vector(model.globalScale, width, true);
        vector(model.globalMin, width, false); vector(model.globalMax, width, false);
        vector(model.featureWeights, width, true);
        for (int index = 0; index < width; index++) {
            if (model.globalMin[index] > model.globalMax[index]) throw new IllegalArgumentException("Invalid nearest profile coverage bounds");
        }
        if (!Double.isFinite(Arrays.stream(model.featureWeights).sum())) throw new IllegalArgumentException("Unbounded distance weights");
        validateDesign(model.designConstraints);
        Set<String> ids = new HashSet<>(), hashes = new HashSet<>();
        for (Reference reference : model.references) {
            validateReference(reference, width, model.components.size());
            if (!ids.add(reference.id) || !hashes.add(reference.inputSha256))
                throw new IllegalArgumentException("Repeated nearest profile reference");
        }
        return new V3NearestProfileInitializer(model);
    }

    @Override public String modelId() { return model.modelId; }

    /** Primitive reference profiles and numeric index storage, excluding strings, object headers and inference scratch. */
    public long parameterStorageBytes() { return primitiveStorageBytes; }

    /** Number of qualified training columns stored in this immutable library. */
    public int referenceCount() { return model.references.length; }

    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(control, "control");
        control.checkpoint();
        if (!input.packageId().equals(model.packageId) || !input.componentBasis().componentIds().equals(model.components)
                || input.stageCount() < model.minimumStages || input.stageCount() > model.maximumStages
                || !model.formulationRevisions.contains(V3ColumnCalculator.formulationRevision(input, 0,
                        V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE))) return Optional.empty();
        try { V3ColumnProblemResolver.validateInput(input); }
        catch (IllegalArgumentException invalidInput) { return Optional.empty(); }
        if (!withinDesign(input, model.designConstraints)) return Optional.empty();
        double[] x = V3GeneralNeuralFeatures.global(input);
        for (int index = 0; index < x.length; index++) {
            double slack = 1e-9 * Math.max(1, Math.max(Math.abs(model.globalMin[index]), Math.abs(model.globalMax[index])));
            if (!Double.isFinite(x[index]) || x[index] < model.globalMin[index] - slack || x[index] > model.globalMax[index] + slack)
                return Optional.empty();
            x[index] = (x[index] - model.globalMean[index]) / model.globalScale[index];
        }
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        boolean refluxPositive = input.specifications().stream().anyMatch(spec ->
                spec instanceof V3ColumnSpecification.OrganicRefluxRatio reflux && reflux.ratio() > 0);
        List<Candidate> candidates = new ArrayList<>();
        for (Reference reference : model.references) {
            control.checkpoint();
            if (!compatible(input, feed, refluxPositive, reference)) continue;
            double sum = 0;
            for (int index = 0; index < x.length; index++) {
                double difference = x[index] - reference.normalizedGlobal[index];
                sum += model.featureWeights[index] * difference * difference;
            }
            double distance = sum / totalFeatureWeight;
            if (Double.isFinite(distance) && distance <= model.maximumMeanSquareDistance)
                candidates.add(new Candidate(reference, distance));
        }
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(candidate -> candidate.reference().id));
        Candidate nearest = candidates.getFirst();
        boolean[] wet = mappedWet(nearest.reference(), input);
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            control.checkpoint();
            if (candidate.reference().branch == nearest.reference().branch
                    && Arrays.equals(wet, mappedWet(candidate.reference(), input))) selected.add(candidate);
            // Exact matches preserve their original reference rather than averaging unrelated nearby columns.
            if (selected.size() == model.neighbors || nearest.distance() <= 1e-20) break;
        }
        return transfer(input, feed, selected, wet, control);
    }

    private boolean compatible(V3ColumnInput input, double[] feed, boolean refluxPositive, Reference reference) {
        if (reference.steamEnabled != !input.steamFeeds().isEmpty()) return false;
        if (model.cohortPolicy.equals("steam-equipment-counts")
                && (reference.paCount != input.pumparounds().size() || reference.sideDrawCount != input.sideDraws().size())) return false;
        if (refluxPositive && reference.branch == V3CondenserPhaseBranch.VAPOR_ONLY) return false;
        // A source fed on its first tray contains no upstream tray profile to stretch above a lower requested feed.
        if (input.feedStageNumber() > 1 && reference.feedStageNumber == 1) return false;
        for (int component = 0; component < feed.length; component++)
            if (feed[component] > 0 && reference.componentFeed[component] == 0) return false;
        return true;
    }

    private Optional<V3NeuralSeed> transfer(V3ColumnInput input, double[] feed, List<Candidate> selected,
            boolean[] wet, V3SolveControl control) {
        int nodes = input.stageCount() + 2;
        double[][] liquid = new double[nodes][feed.length], vapor = new double[nodes][feed.length];
        double[] temperatures = new double[nodes], water = new double[nodes];
        double[] weights = new double[selected.size()];
        double weightSum = 0;
        for (int index = 0; index < weights.length; index++) {
            weights[index] = selected.size() == 1 ? 1 : 1 / Math.max(1e-8, Math.sqrt(selected.get(index).distance()));
            weightSum += weights[index];
        }
        double requestedSteam = input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        for (int index = 0; index < selected.size(); index++) {
            Reference source = selected.get(index).reference();
            double weight = weights[index] / weightSum;
            double waterScale = source.totalSteamFlow > 0 ? requestedSteam / source.totalSteamFlow : 0;
            for (int node = 0; node < nodes; node++) {
                control.checkpoint();
                double position = sourcePosition(node, source.stageCount, source.feedStageNumber, input.stageCount(), input.feedStageNumber());
                int lower = (int) Math.floor(position), upper = Math.min(source.stageCount + 1, lower + 1);
                double fraction = position - lower;
                temperatures[node] += weight * interpolate(source.temperatures[lower], source.temperatures[upper], fraction);
                if (wet[node]) water[node] += weight * waterScale * interpolate(source.freeWater[lower], source.freeWater[upper], fraction);
                for (int component = 0; component < feed.length; component++) {
                    if (feed[component] == 0) continue;
                    double scale = feed[component] / source.componentFeed[component];
                    liquid[node][component] += weight * scale * interpolate(source.liquid[lower][component], source.liquid[upper][component], fraction);
                    vapor[node][component] += weight * scale * interpolate(source.vapor[lower][component], source.vapor[upper][component], fraction);
                }
            }
        }
        for (var spec : input.specifications()) if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature temperature)
            temperatures[0] = temperature.kelvin();
        V3CondenserPhaseBranch branch = selected.getFirst().reference().branch;
        if (branch == V3CondenserPhaseBranch.LIQUID_ONLY) Arrays.fill(vapor[0], 0);
        if (branch == V3CondenserPhaseBranch.VAPOR_ONLY) Arrays.fill(liquid[0], 0);
        double totalFeed = Arrays.stream(feed).sum();
        for (int node = 0; node < nodes; node++) {
            if (!Double.isFinite(temperatures[node]) || temperatures[node] < 100 || temperatures[node] > 1500
                    || !Double.isFinite(water[node]) || water[node] < 0 || water[node] > 1e8 * totalFeed
                    || wet[node] && water[node] <= 0) return Optional.empty();
            for (int component = 0; component < feed.length; component++)
                if (!boundedFlow(liquid[node][component], totalFeed) || !boundedFlow(vapor[node][component], totalFeed)) return Optional.empty();
        }
        control.checkpoint();
        return Optional.of(new V3NeuralSeed(input, model.propertyRevision, branch, liquid, vapor, temperatures, water, wet));
    }

    /** Piecewise endpoint/feed alignment; an upstream tray never interpolates the feed's bulk-flow discontinuity. */
    static double sourcePosition(int node, int sourceStages, int sourceFeed, int targetStages, int targetFeed) {
        if (node == 0) return 0;
        if (node == targetStages + 1) return sourceStages + 1;
        if (node == targetFeed) return sourceFeed;
        if (node < targetFeed) return Math.min(sourceFeed - 1, Math.max(1, node * (double) sourceFeed / targetFeed));
        return sourceFeed + (node - targetFeed) * (double) (sourceStages + 1 - sourceFeed) / (targetStages + 1 - targetFeed);
    }

    private static boolean[] mappedWet(Reference source, V3ColumnInput input) {
        boolean[] wet = new boolean[input.stageCount() + 2];
        if (input.steamFeeds().isEmpty()) return wet;
        for (int node = 1; node <= input.stageCount(); node++) {
            int sourceNode = (int) Math.round(sourcePosition(node, source.stageCount, source.feedStageNumber,
                    input.stageCount(), input.feedStageNumber()));
            wet[node] = source.wetTrays[sourceNode];
        }
        return wet;
    }

    private static double interpolate(double lower, double upper, double fraction) {
        return fraction == 0 ? lower : lower + fraction * (upper - lower);
    }

    private static boolean boundedFlow(double value, double totalFeed) { return Double.isFinite(value) && value >= 0 && value <= 1e8 * totalFeed; }

    private static void validateReference(Reference reference, int width, int components) {
        if (reference == null || reference.id == null || reference.id.isBlank() || reference.id.length() > 256
                || reference.inputSha256 == null || !reference.inputSha256.matches("[a-f0-9]{64}")
                || !"train".equals(reference.sourceSplit) || !reference.equilibriumQualified
                || reference.stageCount < 2 || reference.stageCount > 64
                || reference.feedStageNumber < 1 || reference.feedStageNumber > reference.stageCount
                || reference.paCount < 0 || reference.paCount > 4 || reference.sideDrawCount < 0 || reference.sideDrawCount > 3
                || reference.branch == null || !Double.isFinite(reference.totalSteamFlow)
                || reference.totalSteamFlow < 0 || reference.steamEnabled != (reference.totalSteamFlow > 0))
            throw new IllegalArgumentException("Reference must be a qualified, bounded training column");
        vector(reference.normalizedGlobal, width, false); vector(reference.componentFeed, components, false);
        double total = Arrays.stream(reference.componentFeed).sum();
        if (!Double.isFinite(total) || total <= 0 || Arrays.stream(reference.componentFeed).anyMatch(value -> value < 0))
            throw new IllegalArgumentException("Invalid reference component feed");
        int nodes = reference.stageCount + 2;
        vector(reference.temperatures, nodes, true); vector(reference.freeWater, nodes, false);
        if (reference.wetTrays == null || reference.wetTrays.length != nodes
                || reference.liquid == null || reference.liquid.length != nodes || reference.vapor == null || reference.vapor.length != nodes)
            throw new IllegalArgumentException("Invalid reference profile dimensions");
        for (int node = 0; node < nodes; node++) {
            vector(reference.liquid[node], components, false); vector(reference.vapor[node], components, false);
            if (reference.temperatures[node] < 100 || reference.temperatures[node] > 1500
                    || !boundedFlow(reference.freeWater[node], total)
                    || reference.wetTrays[node] && (!reference.steamEnabled || node == 0 || node == nodes - 1 || reference.freeWater[node] <= 0)
                    || !reference.wetTrays[node] && reference.freeWater[node] != 0)
                throw new IllegalArgumentException("Invalid reference water/temperature profile");
            for (int component = 0; component < components; component++) {
                double liquid = reference.liquid[node][component], vapor = reference.vapor[node][component];
                if (!boundedFlow(liquid, total) || !boundedFlow(vapor, total)
                        || reference.componentFeed[component] == 0 && (liquid != 0 || vapor != 0)
                        || node == 0 && reference.branch == V3CondenserPhaseBranch.LIQUID_ONLY && vapor != 0
                        || node == 0 && reference.branch == V3CondenserPhaseBranch.VAPOR_ONLY && liquid != 0)
                    throw new IllegalArgumentException("Invalid reference component/terminal flow");
            }
        }
    }

    private static void vector(double[] values, int width, boolean positive) {
        if (values == null || values.length != width || Arrays.stream(values).anyMatch(value -> !Double.isFinite(value) || positive && value <= 0))
            throw new IllegalArgumentException("Invalid nearest profile numeric vector");
    }

    private static void validateDesign(DesignConstraints limits) {
        if (limits == null || !Double.isFinite(limits.minimumNodePressurePascal) || limits.minimumNodePressurePascal <= 0
                || !Double.isFinite(limits.maximumNodePressurePascal) || limits.minimumNodePressurePascal > limits.maximumNodePressurePascal
                || limits.pumparoundSplits == null || limits.pumparoundSplits.size() > 2
                || limits.pumparoundSplits.stream().anyMatch(Objects::isNull)
                || limits.pumparoundSplits.stream().distinct().count() != limits.pumparoundSplits.size())
            throw new IllegalArgumentException("Invalid nearest profile design constraints");
    }

    private static boolean withinDesign(V3ColumnInput input, DesignConstraints limits) {
        double bottom = input.topPressurePascal() + (input.stageCount() - 1) * input.stagePressureDropPascal();
        double slack = 1e-9 * Math.max(1, limits.maximumNodePressurePascal);
        if (!Double.isFinite(bottom) || input.topPressurePascal() < limits.minimumNodePressurePascal - slack
                || bottom > limits.maximumNodePressurePascal + slack) return false;
        for (var steam : input.steamFeeds()) if (limits.steamAtSumpOnly && steam.stageNumber() != input.stageCount() + 1) return false;
        for (var pa : input.pumparounds()) if (!limits.pumparoundSplits.contains(pa.split())) return false;
        return true;
    }

    private record Candidate(Reference reference, double distance) {}
    private static final class Document {
        String modelType, featureRevision, transferRevision, modelId, packageId, propertyRevision, cohortPolicy;
        List<String> components, formulationRevisions;
        int minimumStages, maximumStages, neighbors;
        double maximumMeanSquareDistance;
        double[] globalMean, globalScale, globalMin, globalMax, featureWeights;
        Reference[] references;
        DesignConstraints designConstraints;
    }
    private static final class Reference {
        String id, inputSha256, sourceSplit;
        boolean equilibriumQualified, steamEnabled;
        int stageCount, feedStageNumber, paCount, sideDrawCount;
        double totalSteamFlow;
        V3CondenserPhaseBranch branch;
        double[] normalizedGlobal, componentFeed, temperatures, freeWater;
        double[][] liquid, vapor;
        boolean[] wetTrays;
    }
    private static final class DesignConstraints {
        double minimumNodePressurePascal, maximumNodePressurePascal;
        boolean steamAtSumpOnly;
        List<V3PumparoundSpec.Split> pumparoundSplits;
    }
}
