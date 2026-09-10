package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Optional;

/** Offline inverse continuation: vary condenser temperature, then release and certify the wet coordinate. */
final class V3WetBoundaryTeacher {
    record Result(V3ColumnInput input, V3ColumnOutcome outcome, V3NeuralSeed profile, String detail) {}
    private V3WetBoundaryTeacher() {}

    static Result generate(V3ColumnInput base, V3SolveControl control) {
        V3NeuralSeed[] captured = {null};
        var coldInput = withTemperature(base, 338.15);
        var cold = V3ColumnCalculator.calculateWithAcceptedProfile(coldInput, control, s -> captured[0] = s);
        if (!cold.isSuccess()) {
            coldInput = withTemperature(base, 332.15);
            cold = V3ColumnCalculator.calculateWithAcceptedProfile(coldInput, control, s -> captured[0] = s);
        }
        if (!cold.isSuccess()) return new Result(coldInput, cold, null, "classical seed failed");
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(base.packageId());
        var original = V3ColumnProblemResolver.resolve(base, captured[0].branch());
        double hf = thermo.flashTP(base.feedTemperatureKelvin(), original.nodePressurePascal(base.feedStageNumber()),
                base.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        double low = Double.NaN, high = Double.NaN;
        double tc = V3NeuralFeatures.encode(coldInput)[5];
        V3NeuralSeed snapshot = captured[0], best = null;
        double bestError = Double.POSITIVE_INFINITY;
        int refinements = 0;
        for (int iteration = 0; iteration < 60; iteration++) {
            control.checkpoint();
            var input = withTemperature(base, tc);
            Parametric solved = parametric(input, snapshot, thermo, hf, control);
            if (solved == null) {
                captured[0] = null;
                V3ColumnCalculator.calculateWithAcceptedProfile(input, control, s -> captured[0] = s);
                if (captured[0] != null) solved = parametric(input, captured[0], thermo, hf, control);
                if (solved == null) return new Result(input, null, null, "parametric continuation failed");
            }
            double ratio = solved.ratio();
            snapshot = solved.profile();
            if (Math.abs(ratio - 1) < bestError) { bestError = Math.abs(ratio - 1); best = snapshot; }
            if (ratio > 1) low = tc; else high = tc;
            if (Double.isFinite(low) && Double.isFinite(high)) {
                if (++refinements >= 27) break;
                tc = (low + high) * .5;
            } else tc += ratio > 1 ? 2 : -2;
            if (tc < 313.15 || tc > 348.15) return new Result(input, null, null, "wet boundary outside declared condenser range");
        }
        if (!Double.isFinite(low) || !Double.isFinite(high)) return new Result(base, null, null, "wet boundary was not bracketed");
        var selected = best;
        V3NeuralInitializer fixed = new V3NeuralInitializer() {
            public String modelId() { return "offline-wet-boundary-teacher"; }
            public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl c) { c.checkpoint(); return Optional.of(selected); }
        };
        captured[0] = null;
        var result = V3ColumnCalculator.calculateWithAcceptedProfile(selected.input(), control,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.PREDICTED_WET, 128, 10_000),
                fixed, s -> captured[0] = s);
        if (captured[0] == null || V3WaterPhaseQualification.assess(captured[0]).grade() != V3WaterPhaseQualification.Grade.WET_EQUILIBRIUM)
            return new Result(selected.input(), result, null, "released wet-state qualification failed");
        return new Result(selected.input(), result, captured[0], "classical seed, condenser boundary continuation, released wet-state certificate");
    }

    private record Parametric(V3NeuralSeed profile, double ratio) {}

    private static Parametric parametric(V3ColumnInput input, V3NeuralSeed snapshot,
            V3PengRobinsonThermo thermo, double hf, V3SolveControl control) {
        double[] temperatures = snapshot.temperatures(); temperatures[0] = V3NeuralFeatures.encode(input)[5];
        double[] water = snapshot.freeWater(); water[1] = 1;
        boolean[] mask = snapshot.wetTrays(); mask[1] = true;
        var seed = new V3NeuralSeed(input, snapshot.propertyRevision(), snapshot.branch(), snapshot.liquid(),
                snapshot.vapor(), temperatures, water, mask);
        var full = V3ColumnProblemResolver.resolve(input, seed.branch());
        var set = V3WetTraySet.parametric(full.topology(), mask, water);
        var state = seed.stateFor(full);
        var problem = V3ColumnProblemResolver.withTruncation(full, V3TruncationSupport.derive(full, 0, state), set);
        var attempt = V3SimultaneousColumnSolver.solve(problem, new V3MeshResidualEvaluator(problem, thermo, hf),
                new V3DryMeshCoordinateMap(problem), set.seed(problem, state), thermo::newWorkspace,
                V3ConvergenceEvidence.unavailable(), 64, 1e-8, V3FiniteDifferenceJacobian.DifferenceScale.FINE, control);
        if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged solved)) return null;
        double ratio = V3WetTraySet.saturation(problem, solved.state(), 1, problem.waterVaporFlow(solved.state(), 1)).ratio();
        return new Parametric(V3NeuralSeed.capture(problem, solved.state(), seed.propertyRevision()), ratio);
    }

    private static V3ColumnInput withTemperature(V3ColumnInput base, double temperature) {
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                base.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                        ? (V3ColumnSpecification)new V3ColumnSpecification.CondenserOutletTemperature(temperature) : s).toList(),
                base.sideDraws(), base.steamFeeds(), base.pumparounds());
    }
}
