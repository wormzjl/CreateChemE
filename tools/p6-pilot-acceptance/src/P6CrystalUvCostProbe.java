package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.ScheduledTransfer;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * P6 (batch 2026-09-24-coolprop-low-temperature): cost of a pilot vessel's crystal UV answer, a plain main outside the
 * tracked tree. Three equilibrated pilot vessels are built through the network (the fixtures of CrystalDepositionIslandTest):
 * (VS) 10 % CO2 in N2 charged at 170 K, 1 MPa, 0.1 m3, deposited at its first interval; (VLS) one mole of 2 % CO2 in CH4
 * cooled by 1500 J onto the binary's three-phase line; (LS) 1 L of 3 % CO2 in liquid CH4 at 9 MPa cooled by 3000 J.
 * For each: the engine's UV answer of the vessel's inventory (cold: the first call on a fresh service and workspace,
 * median of 21; warm: a reused workspace after 2000 calls, mean and p95 of 2000) and its TP-equilibrium count; the
 * network's equilibration of the vessel (FluidThermodynamics.equilibrateCrystals) at rest (cold: a fresh model whose
 * engine is built, its first equilibration timed; warm: mean of 500) and after an energy step of -100 J (the substep
 * case), with the checkpoint count of one call (one per kernel evaluation). Run from the worktree root:
 * bash tools/p6-pilot-acceptance/run.sh main com.wormzjl.createcheme.science.fluid.thermo.P6CrystalUvCostProbe
 */
public final class P6CrystalUvCostProbe {
    static final String PILOT = "createcheme:pilot_cryogenic";
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static final int COLD = 21, WARMUP = 2000, WARM = 2000;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("JDK " + System.getProperty("java.version") + ", " + Runtime.getRuntime().availableProcessors() + " processors");
        var model = new FluidThermodynamics(CATALOG, PILOT);
        var vs = interval(model, closed(charge(model, 170, 1e6, new double[] {.9, 0, 0, .1, 0}, .1)), 0, 0);
        var start = closed(model.flashTP(172, 2.45e6, new double[] {0, .98, 0, .02, 0}, () -> {}));
        var vls = interval(model, interval(model, start, -150, 1), -150, 1);
        var full = closed(charge(model, 170, 9e6, new double[] {0, .97, 0, .03, 0}, 1e-3));
        var ls = interval(model, interval(model, interval(model, full, -200, 1), -200, 1), -200, 1);
        System.out.println("case | classification | T K | P Pa | crystal mol | engine UV cold median us | warm mean us | warm p95 us | TP equilibria"
                + " | network rest cold us | rest warm mean us | rest checkpoints | step -100 J warm mean us | step checkpoints");
        report("VS  10 % CO2 in N2", model, vs);
        report("VLS 2 % CO2 in CH4", model, vls);
        report("LS  3 % CO2 in liquid CH4", model, ls);
    }

    static void report(String label, FluidThermodynamics model, PassiveNetwork graph) {
        var node = graph.reservoirs().getFirst();
        var inventory = node.inventory();
        double[] totals = inventory.speciesMoles();
        double energy = inventory.internalEnergy();
        var formation = model.hydrocarbon.formationReference().orElseThrow();
        for (int i = 0; i < totals.length - 1; i++) energy += totals[i] * formation.offsetJoulesPerMole(i);
        var reference = FluidTpEquilibrium.forPackage(CATALOG, PILOT);
        var request = EquilibriumRequest.uv(energy, inventory.volume(), reference.contract().components(), totals, PhaseCompetition.FLUID_AND_CRYSTALS);
        long[] colds = new long[COLD];
        EquilibriumResult answer = null;
        for (int k = 0; k < COLD; k++) {
            var s = FluidTpEquilibrium.forPackage(CATALOG, PILOT);
            var ws = s.newWorkspace();
            long t0 = System.nanoTime();
            answer = s.uv(request, ws);
            colds[k] = System.nanoTime() - t0;
        }
        var ws = reference.newWorkspace();
        for (int k = 0; k < WARMUP; k++) reference.uv(request, ws);
        long[] warm = new long[WARM];
        for (int k = 0; k < WARM; k++) {
            long t0 = System.nanoTime();
            reference.uv(request, ws);
            warm[k] = System.nanoTime() - t0;
        }
        // The network's equilibration at rest (the deadband keeps the split) and after -100 J (a substep's change).
        var state = node.state();
        int[] count = new int[1];
        Runnable counting = () -> count[0]++;
        long[] restCold = new long[COLD];
        for (int k = 0; k < COLD; k++) {
            var fresh = new FluidThermodynamics(CATALOG, PILOT);
            fresh.phaseEngine();
            long t0 = System.nanoTime();
            fresh.equilibrateCrystals(state, inventory.moles(), inventory.crystals(), inventory.internalEnergy(), inventory.volume(), inventory.solids(), () -> {});
            restCold[k] = System.nanoTime() - t0;
        }
        Supplier<Object> rest = () -> model.equilibrateCrystals(state, inventory.moles(), inventory.crystals(), inventory.internalEnergy(),
                inventory.volume(), inventory.solids(), counting);
        Supplier<Object> step = () -> model.equilibrateCrystals(state, inventory.moles(), inventory.crystals(), inventory.internalEnergy() - 100,
                inventory.volume(), inventory.solids(), counting);
        double[] restTimes = warm(rest), stepTimes = warm(step);
        count[0] = 0;
        Object restAnswer = rest.get();
        int restCount = count[0];
        count[0] = 0;
        Object stepAnswer = step.get();
        int stepCount = count[0];
        System.out.printf("%s | %s | %.6f | %.1f | %.9f | %.1f | %.1f | %.1f | %d | %.1f | %.1f | %d%s | %.1f | %d%s%n", label, answer.classification(),
                state.temperature(), state.pressure(), inventory.crystals().stocks().stream().mapToDouble(s -> s.moles()).sum(),
                median(colds) / 1e3, mean(warm) / 1e3, percentile(warm, .95) / 1e3, answer.diagnostics().outerIterations(),
                median(restCold) / 1e3, restTimes[0] / 1e3, restCount, restAnswer == null ? " (split stands)" : " (moved)",
                stepTimes[0] / 1e3, stepCount, stepAnswer == null ? " (split stands)" : " (moved)");
    }

    static double[] warm(Supplier<Object> call) {
        for (int k = 0; k < WARMUP / 4; k++) call.get();
        long[] times = new long[WARM / 4];
        for (int k = 0; k < times.length; k++) {
            long t0 = System.nanoTime();
            call.get();
            times[k] = System.nanoTime() - t0;
        }
        return new double[] {mean(times), percentile(times, .95)};
    }

    static FluidThermodynamics.State charge(FluidThermodynamics model, double t, double p, double[] z, double volume) {
        var unit = model.flashTP(t, p, z, () -> {});
        double[] n = z.clone();
        for (int i = 0; i < n.length; i++) n[i] *= volume / unit.volume();
        return model.flashTP(t, p, n, () -> {});
    }

    static PassiveNetwork closed(FluidThermodynamics.State state) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, state)), List.of());
    }

    static PassiveNetwork interval(FluidThermodynamics model, PassiveNetwork graph, double watts, int carrier) {
        List<ScheduledTransfer> transfers = watts == 0 ? List.of() : List.of(new ScheduledTransfer.Injection(100, 0, trace(carrier), watts));
        var result = new PassiveIntervalSolver(model).solve(new PassiveNetwork(graph.reservoirs(), graph.pipes(), transfers), 5,
                PassiveIntervalSolver.Settings.defaults(), () -> {}, 1);
        return new PassiveNetwork(result.graph().reservoirs(), result.graph().pipes());
    }

    static double[] trace(int carrier) {
        double[] rate = new double[5];
        rate[carrier] = 1e-12;
        return rate;
    }

    static double median(long[] values) { return percentile(values, .5); }

    static double mean(long[] values) { return Arrays.stream(values).average().orElse(Double.NaN); }

    static double percentile(long[] values, double q) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1)))];
    }
}
