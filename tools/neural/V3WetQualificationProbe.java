package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.util.Locale;

/** Offline sensitivity check of the water continuation on the methane preset. */
public final class V3WetQualificationProbe {
    public static void main(String[] args) {
        if (args.length > 1 && args[1].equals("boundary")) {
            boundaryProbe();
            return;
        }
        if (args.length > 1 && (args[1].equals("grid") || args[1].equals("sweep"))) {
            var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
            double[] temperatures = args[1].equals("grid") ? new double[]{313.15, 332.15, 343.15}
                    : java.util.stream.IntStream.rangeClosed(5, 18).mapToDouble(i -> 273.15 + 5 * i).toArray();
            for (double temperature : temperatures) {
                for (double cooling : args[1].equals("grid") ? new double[]{1, 0.9, 0.8} : new double[]{1}) {
                    var input = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                            base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                            base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                            base.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                                    ? (V3ColumnSpecification)new V3ColumnSpecification.CondenserOutletTemperature(temperature) : s).toList(),
                            base.sideDraws(), base.steamFeeds(), base.pumparounds().stream().map(p -> p.drawTray() == 10
                                    ? new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * cooling, p.split()) : p).toList());
                    long start = System.nanoTime();
                    V3NeuralSeed[] captured = {null};
                    var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {
                        if (System.nanoTime() - start > 60_000_000_000L) throw new java.util.concurrent.CancellationException();
                    }, seed -> captured[0] = seed);
                    var dew = outcome.diagnostics().acceptanceAudit().checks().stream()
                            .filter(c -> c.family().equals("WATER_DEW_POINT")).findFirst();
                    int wetCount = captured[0] == null ? -1 : (int)java.util.stream.IntStream.range(0, captured[0].wetTrays().length)
                            .filter(i -> captured[0].wetTrays()[i]).count();
                    System.out.printf(Locale.ROOT, "Tc=%.2f topCooling=%.2f accepted=%s wetTrays=%d dew=%s%n", temperature, cooling,
                            outcome.isSuccess(), wetCount, dew.map(c -> c.value() + ": " + c.detail()).orElse("not available"));
                }
            }
            return;
        }
        var input = ColumnCalculatorV3BlockEntity.methaneCduInput();
        if (args.length > 0) {
            double condenserKelvin = Double.parseDouble(args[0]);
            input = new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                    input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                    input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                    input.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                            ? (V3ColumnSpecification)new V3ColumnSpecification.CondenserOutletTemperature(condenserKelvin) : s).toList(),
                    input.sideDraws(), input.steamFeeds(), input.pumparounds());
        }
        V3NeuralSeed[] capture = {null};
        var cold = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {}, s -> capture[0] = s);
        if (!cold.isSuccess()) throw new IllegalStateException(cold.toString());
        System.out.println(new com.google.gson.Gson().toJson(cold));
        var full = V3ColumnProblemResolver.resolve(input, capture[0].branch());
        if (args.length > 1 && args[1].equals("wet-start")) {
            for (int depth : new int[]{1, 2, 3, 5}) {
                for (double amount : new double[]{10, 100, 500}) {
                    var initial = capture[0];
                    double[] free = initial.freeWater(), temperatures = initial.temperatures();
                    boolean[] wetMask = initial.wetTrays();
                    for (int n = 1; n <= depth; n++) {
                        wetMask[n] = true; free[n] = amount;
                        double water = full.waterVaporFlowMolPerSecond(n) + (n > 1 ? free[n - 1] : 0);
                        double vapor = java.util.Arrays.stream(initial.vapor()[n]).sum();
                        temperatures[n] = com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties
                                .saturationTemperatureKelvin(full.nodePressurePascal(n) * water / (vapor + water));
                    }
                    var candidate = new V3NeuralSeed(input, initial.propertyRevision(), initial.branch(),
                            initial.liquid(), initial.vapor(), temperatures, free, wetMask);
                    var fixed = new V3NeuralInitializer() {
                        public String modelId() { return "diagnostic-wet-seed"; }
                        public java.util.Optional<V3NeuralSeed> predict(V3ColumnInput request, V3SolveControl control) {
                            return java.util.Optional.of(candidate);
                        }
                    };
                    long start = System.nanoTime();
                    var outcome = V3ColumnCalculator.calculate(input, () -> {}, 0, 0,
                            new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                                    V3InitializationOptions.WetStart.PREDICTED_WET, 128, 10_000), fixed);
                    System.out.printf(Locale.ROOT, "depth=%d water=%.1f success=%s residual=%.8g ms=%.0f events=%s%n",
                            depth, amount, outcome.isSuccess(), outcome.diagnostics().maximumScaledResidual(),
                            (System.nanoTime() - start) / 1e6, outcome.diagnostics().events());
                }
            }
            return;
        }
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        double hf = thermo.flashTP(input.feedTemperatureKelvin(), full.nodePressurePascal(input.feedStageNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        var state = capture[0].stateFor(full);
        var support = V3TruncationSupport.derive(full, 0, state);
        boolean[] wet = new boolean[state.nodeCount()]; wet[1] = true;
        double[] freeWater = new double[state.nodeCount()];
        for (double flow : new double[]{0, 0.1, 1, 10, 100}) {
            freeWater[1] = flow;
            var set = V3WetTraySet.parametric(full.topology(), wet, freeWater);
            var problem = V3ColumnProblemResolver.withTruncation(full, support, set);
            var evaluator = new V3MeshResidualEvaluator(problem, thermo, hf);
            var attempt = V3SimultaneousColumnSolver.solve(problem, evaluator, new V3DryMeshCoordinateMap(problem),
                    set.seed(problem, state), thermo::newWorkspace, V3ConvergenceEvidence.unavailable(),
                    64, 1e-8, V3FiniteDifferenceJacobian.DifferenceScale.FINE, () -> {});
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged solved) state = solved.state();
            double ratio = V3WetTraySet.saturation(problem, state, 1, problem.waterVaporFlow(state, 1)).ratio();
            System.out.printf(Locale.ROOT, "W=%.4f stateW=%.4f T1=%.10f T2=%.10f ratio=%.12f result=%s%n",
                    flow, state.freeWaterFlow(1), state.temperatureKelvin(1), state.temperatureKelvin(2), ratio,
                    attempt.getClass().getSimpleName());
        }
    }

    private static void boundaryProbe() {
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        V3NeuralSeed[] captured = {null};
        var cold = V3ColumnCalculator.calculateWithAcceptedProfile(base, () -> {}, s -> captured[0] = s);
        if (!cold.isSuccess()) throw new IllegalStateException("Cold baseline failed");
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(base.packageId());
        double hf = thermo.flashTP(base.feedTemperatureKelvin(), base.topPressurePascal(),
                base.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        for (double waterAmount : new double[]{0.1, 1, 10}) {
            double low = 333.15, high = 338.15;
            V3NeuralSeed snapshot = captured[0];
            for (int iteration = 0; iteration < 35; iteration++) {
                double tc = (low + high) * .5;
                var input = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                        base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                        base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                        base.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                                ? (V3ColumnSpecification)new V3ColumnSpecification.CondenserOutletTemperature(tc) : s).toList(),
                        base.sideDraws(), base.steamFeeds(), base.pumparounds());
                double[] temperatures = snapshot.temperatures(); temperatures[0] = tc;
                double[] water = snapshot.freeWater(); water[1] = waterAmount;
                boolean[] mask = snapshot.wetTrays(); mask[1] = true;
                var seed = new V3NeuralSeed(input, snapshot.propertyRevision(), snapshot.branch(), snapshot.liquid(),
                        snapshot.vapor(), temperatures, water, mask);
                var full = V3ColumnProblemResolver.resolve(input, seed.branch());
                var set = V3WetTraySet.parametric(full.topology(), mask, water);
                var state = seed.stateFor(full);
                var support = V3TruncationSupport.derive(full, 0, state);
                var problem = V3ColumnProblemResolver.withTruncation(full, support, set);
                var attempt = V3SimultaneousColumnSolver.solve(problem, new V3MeshResidualEvaluator(problem, thermo, hf),
                        new V3DryMeshCoordinateMap(problem), set.seed(problem, state), thermo::newWorkspace,
                        V3ConvergenceEvidence.unavailable(), 64, 1e-8, V3FiniteDifferenceJacobian.DifferenceScale.FINE, () -> {});
                if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged solved)) {
                    System.out.println("boundary parametric failure W=" + waterAmount + " Tc=" + tc); break;
                }
                double ratio = V3WetTraySet.saturation(problem, solved.state(), 1, problem.waterVaporFlow(solved.state(), 1)).ratio();
                snapshot = V3NeuralSeed.capture(problem, solved.state(), seed.propertyRevision());
                if (ratio > 1) low = tc; else high = tc;
                if (iteration < 34 && Math.abs(ratio - 1) > 1e-13) continue;
                var exact = snapshot;
                var fixed = new V3NeuralInitializer() {
                    public String modelId() { return "boundary-wet-seed"; }
                    public java.util.Optional<V3NeuralSeed> predict(V3ColumnInput requested, V3SolveControl c) {
                        return java.util.Optional.of(exact);
                    }
                };
                V3NeuralSeed[] finalProfile = {null};
                var result = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {},
                        new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                                V3InitializationOptions.WetStart.PREDICTED_WET, 128, 10_000), fixed, s -> finalProfile[0] = s);
                System.out.printf(Locale.ROOT, "boundary W=%.3f Tc=%.12f ratio=%.15f success=%s actualW=%.8g wet=%s residual=%.6g evidence=%s%n",
                        waterAmount, tc, ratio, result.isSuccess(), finalProfile[0] == null ? -1 : finalProfile[0].freeWater()[1],
                        finalProfile[0] != null && finalProfile[0].wetTrays()[1], result.diagnostics().maximumScaledResidual(), result.diagnostics().convergenceEvidence());
                if (finalProfile[0] != null) {
                    try {
                        var directory = java.nio.file.Path.of("build", "wet-boundary");
                        java.nio.file.Files.createDirectories(directory);
                        java.nio.file.Files.writeString(directory.resolve("water-" + waterAmount + ".json"), new com.google.gson.GsonBuilder()
                                .setPrettyPrinting().create().toJson(java.util.Map.of("input", input, "seed", finalProfile[0], "outcome", result)));
                    } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }
                break;
            }
        }
    }
}
