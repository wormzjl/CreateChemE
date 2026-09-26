package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Cost of the P4 stage 2a gas-solid engine on the pilot package (batch 2026-09-24-coolprop-low-temperature), a plain
 * main outside the tracked tree: microseconds per call, cold (the first call on a fresh workspace of a fresh service,
 * each of {@code COLD} repetitions in a new service, median) and warm (a reused workspace after a warm-up, mean and p95 of
 * {@code WARM} timed calls), plus the service construction (the crystal's anchor and constants solved once) and the
 * anchored crystal's chemical potential alone. Run from the worktree root:
 * {@code bash tools/gas-solid-equilibrium-scans/run.sh main com.wormzjl.createcheme.science.thermo.phase.GasSolidCostProbe}
 */
public final class GasSolidCostProbe {
    static final String PILOT = "createcheme:pilot_cryogenic";
    static final int COLD = 21;
    static final int WARMUP = 2000;
    static final int WARM = 2000;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        MaterialCatalog catalog = MaterialCatalog.bundled();
        var reference = FluidTpEquilibrium.forPackage(catalog, PILOT);
        List<String> basis = reference.contract().components();
        var crystals = PhaseCompetition.FLUID_AND_CRYSTALS;
        double[] tenPercent = {0.9, 0, 0, 0.1, 0};
        double[] onePercent = {0.99, 0, 0, 0.01, 0};
        double[] vapour = tenPercent;
        var deposit = EquilibriumRequest.tp(160.0, 1.0e6, basis, tenPercent, crystals);
        var depositFromVapour = EquilibriumRequest.tp(150.0, 1.0e5, basis, onePercent, crystals);
        var noSolid = EquilibriumRequest.tp(200.0, 1.0e6, basis, vapour, crystals);
        var fluidOnly = EquilibriumRequest.tp(250.0, 1.0e6, basis, vapour, PhaseCompetition.FLUID_ONLY);
        var crystalAt250 = EquilibriumRequest.tp(250.0, 1.0e6, basis, vapour, crystals);
        var pure = EquilibriumRequest.tp(180.0, 1.0e5, basis, new double[] {0, 0, 0, 1, 0}, crystals);
        var depositAnswer = reference.tp(deposit);
        var ph = EquilibriumRequest.ph(1.0e6, depositAnswer.enthalpy(), basis, tenPercent, crystals);
        var uv = EquilibriumRequest.uv(depositAnswer.internalEnergy(), depositAnswer.volume(), basis, tenPercent, crystals);
        System.out.println("JDK " + System.getProperty("java.version") + ", " + Runtime.getRuntime().availableProcessors() + " processors");

        // Construction: the contract, the evaluator and the crystal's anchor (g0, g1 solved once per service).
        long[] build = new long[COLD];
        for (int k = 0; k < COLD; k++) {
            long start = System.nanoTime();
            FluidTpEquilibrium.forPackage(catalog, PILOT);
            build[k] = System.nanoTime() - start;
        }
        var crystal = CrystalPhaseEvaluator.forPackage(catalog, PILOT, "createcheme:crystal_carbon_dioxide_i");
        var anchor = crystal.anchor(CubicPhaseEvaluator.forPackage(catalog, PILOT));
        long[] anchoring = new long[COLD];
        for (int k = 0; k < COLD; k++) {
            long start = System.nanoTime();
            crystal.anchored(anchor);
            anchoring[k] = System.nanoTime() - start;
        }
        System.out.printf("service construction (forPackage): median %.1f us; anchored view (g0, g1): median %.2f us%n",
                median(build) / 1e3, median(anchoring) / 1e3);
        var view = crystal.anchored(anchor);
        double sink = 0;
        for (int k = 0; k < 200000; k++) sink += view.chemicalPotential(150.0 + (k & 63), 1.0e6);
        long start = System.nanoTime();
        for (int k = 0; k < 1000000; k++) sink += view.chemicalPotential(150.0 + (k & 63), 1.0e6);
        System.out.printf("anchored crystal chemical potential: %.1f ns per call (sink %.3g)%n", (System.nanoTime() - start) / 1e6, sink);

        System.out.println("request | answer | cold median us | warm mean us | warm p95 us | fluid equilibria or TP calls");
        row("TP 10 % CO2 in N2, 160 K, 1 MPa (deposit, fluid-only answer VL)", () -> fresh(catalog).tp(deposit), reference, deposit);
        row("TP 1 % CO2 in N2, 150 K, 0.1 MPa (deposit from one vapour)", () -> fresh(catalog).tp(depositFromVapour), reference, depositFromVapour);
        row("TP 10 % CO2 in N2, 200 K, 1 MPa (no solid)", () -> fresh(catalog).tp(noSolid), reference, noSolid);
        row("TP pure CO2, 180 K, 0.1 MPa (complete deposition)", () -> fresh(catalog).tp(pure), reference, pure);
        row("TP 10 % CO2 in N2, 250 K, 1 MPa, crystal competition", () -> fresh(catalog).tp(crystalAt250), reference, crystalAt250);
        row("TP same state, fluid-only (baseline)", () -> fresh(catalog).tp(fluidOnly), reference, fluidOnly);
        row("PH at the 160 K deposit", () -> fresh(catalog).ph(ph), reference, ph);
        row("UV at the 160 K deposit", () -> fresh(catalog).uv(uv), reference, uv);
        onset("depositionTemperature 10 % CO2 in N2, 1 MPa", catalog, basis, true, 1.0e6, tenPercent, reference);
        onset("depositionTemperature 1 % CO2 in N2, 0.1 MPa", catalog, basis, true, 1.0e5, onePercent, reference);
        onset("depositionPressure 10 % CO2 in N2, 180 K", catalog, basis, false, 180.0, tenPercent, reference);
        onset("depositionPressure 1 % CO2 in CH4, 170 K", catalog, basis, false, 170.0, new double[] {0, 0.99, 0, 0.01, 0}, reference);
    }

    static FluidTpEquilibrium fresh(MaterialCatalog catalog) { return FluidTpEquilibrium.forPackage(catalog, PILOT); }

    static void row(String label, Supplier<EquilibriumResult> cold, FluidTpEquilibrium service, EquilibriumRequest request) {
        // Cold: a fresh service each time, its first call timed alone (construction excluded).
        long[] colds = new long[COLD];
        EquilibriumResult answer = null;
        for (int k = 0; k < COLD; k++) {
            var s = FluidTpEquilibrium.forPackage(MaterialCatalog.bundled(), PILOT);
            var ws = s.newWorkspace();
            long t0 = System.nanoTime();
            answer = call(s, request, ws);
            colds[k] = System.nanoTime() - t0;
        }
        var ws = service.newWorkspace();
        for (int k = 0; k < WARMUP; k++) call(service, request, ws);
        long[] warm = new long[WARM];
        for (int k = 0; k < WARM; k++) {
            long t0 = System.nanoTime();
            call(service, request, ws);
            warm[k] = System.nanoTime() - t0;
        }
        int calls = request.specification() == EquilibriumRequest.Specification.TP
                ? (answer.deposition() != null ? answer.deposition().fluidEquilibria() : 1) : answer.diagnostics().outerIterations();
        System.out.printf("%s | %s | %.1f | %.2f | %.2f | %d%n", label, answer.classification(), median(colds) / 1e3, mean(warm) / 1e3,
                percentile(warm, 0.95) / 1e3, calls);
    }

    static EquilibriumResult call(FluidTpEquilibrium s, EquilibriumRequest request, EquilibriumService.Workspace ws) {
        return switch (request.specification()) {
            case TP -> s.tp(request, ws);
            case PH -> s.ph(request, ws);
            case UV -> s.uv(request, ws);
        };
    }

    static void onset(String label, MaterialCatalog catalog, List<String> basis, boolean temperature, double fixed, double[] z,
                      FluidTpEquilibrium service) {
        long[] colds = new long[COLD];
        DepositionOnset answer = null;
        for (int k = 0; k < COLD; k++) {
            var s = FluidTpEquilibrium.forPackage(catalog, PILOT);
            var ws = s.newWorkspace();
            long t0 = System.nanoTime();
            answer = temperature ? s.depositionTemperature(null, fixed, basis, z, ws) : s.depositionPressure(null, fixed, basis, z, ws);
            colds[k] = System.nanoTime() - t0;
        }
        var ws = service.newWorkspace();
        for (int k = 0; k < WARMUP; k++) {
            if (temperature) service.depositionTemperature(null, fixed, basis, z, ws); else service.depositionPressure(null, fixed, basis, z, ws);
        }
        long[] warm = new long[WARM];
        for (int k = 0; k < WARM; k++) {
            long t0 = System.nanoTime();
            if (temperature) service.depositionTemperature(null, fixed, basis, z, ws); else service.depositionPressure(null, fixed, basis, z, ws);
            warm[k] = System.nanoTime() - t0;
        }
        System.out.printf("%s | %s %s | %.1f | %.2f | %.2f | %d%n", label, answer.status(),
                answer.converged() ? String.format("%.4f %s", temperature ? answer.temperature() : answer.pressure(), temperature ? "K" : "Pa") : "",
                median(colds) / 1e3, mean(warm) / 1e3, percentile(warm, 0.95) / 1e3, answer.fluidEquilibria());
    }

    static double median(long[] values) { return percentile(values, 0.5); }

    static double mean(long[] values) { return Arrays.stream(values).average().orElse(Double.NaN); }

    static double percentile(long[] values, double q) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1)))];
    }
}
