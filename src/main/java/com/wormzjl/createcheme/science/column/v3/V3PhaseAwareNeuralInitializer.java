package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Selects an expert by the requested column's coupled residuals, never by a condenser-temperature threshold. */
final class V3PhaseAwareNeuralInitializer implements V3NeuralInitializer {
    private final List<V3NeuralInitializer> experts;
    private final String id;

    V3PhaseAwareNeuralInitializer(String id, List<V3NeuralInitializer> experts) {
        if (id == null || !id.matches("[A-Za-z0-9._:/-]{1,96}") || experts.isEmpty() || experts.size() > 8)
            throw new IllegalArgumentException("Invalid neural expert bundle");
        this.id = id;
        this.experts = List.copyOf(experts);
    }

    @Override public String modelId() { return id; }

    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
        return candidates(input, control).stream().findFirst();
    }

    /** Ranked alternatives share one caller-owned inference/correction budget. */
    List<V3NeuralSeed> candidates(V3ColumnInput input, V3SolveControl control) {
        List<V3NeuralSeed> candidates = new ArrayList<>();
        for (var expert : experts) {
            control.checkpoint();
            expert.predict(input, control).ifPresent(candidates::add);
        }
        if (candidates.size() < 2) return List.copyOf(candidates);
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        List<Scored> ranked = new ArrayList<>();
        for (var candidate : candidates) {
            control.checkpoint();
            try {
                if (!candidate.propertyRevision().equals(thermo.datasetRevision())) continue;
                var full = V3ColumnProblemResolver.resolve(input, candidate.branch());
                var state = candidate.stateFor(full);
                var wet = candidate.wetSetFor(full, V3InitializationOptions.WetStart.PREDICTED_WET);
                var support = V3TruncationSupport.derive(full, 0, state);
                var problem = V3ColumnProblemResolver.withTruncation(full, support, wet);
                state = support.projectSeed(problem, state);
                var flash = thermo.flashTP(input.feedTemperatureKelvin(), full.nodePressurePascal(input.feedStageNumber()),
                        input.feedComponentMolarFlowsMolPerSecond(),
                        com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy.of(0),
                        thermo.newWorkspace(), control::checkpoint);
                var residual = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol())
                        .evaluate(state, thermo.newWorkspace());
                var water = V3WaterPhaseQualification.assess(candidate);
                double score = Math.max(residual.maximumAbsoluteScaledResidual(), Math.max(
                        Math.max(0, water.maximumDrySaturationRatio() - 1), water.maximumWetSaturationError()));
                if (Double.isFinite(score)) ranked.add(new Scored(candidate, score));
            } catch (V3ThermoException | IllegalArgumentException inadmissible) {
                // An inadmissible candidate does not hide a usable candidate from the other expert.
            }
        }
        return ranked.stream().sorted(java.util.Comparator.comparingDouble(Scored::score)).map(Scored::seed).toList();
    }

    private record Scored(V3NeuralSeed seed, double score) {}
}
