package com.wormzjl.createcheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Cost probe of one TP, PH or UV call, run only with {@code CREATECHEME_PHASE_COST=1}. Measurement code of batch
 * 2026-09-24-coolprop-low-temperature (P2, rows added in P3 WP6a: a Newton finish and free water; in P3 WP6b: PH and UV
 * of the binary and the 20-component basis): it leaves the tracked tree under the tooling-cleanup rule before the batch
 * merges.
 */
@EnabledIfEnvironmentVariable(named = "CREATECHEME_PHASE_COST", matches = "1")
class FluidTpEquilibriumCostTest {
    @Test
    void measuresOneCallPerState() {
        var binaryEvaluator = PhaseTestSupport.methaneNitrogen();
        var binary = new FluidTpEquilibrium(PhaseTestSupport.openContract(binaryEvaluator), binaryEvaluator);
        var network = PhaseTestSupport.networkService();
        var wet = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(com.wormzjl.createcheme.fluid.support.FluidTestSupport.networkModel()));
        double[] wetCrude = PhaseTestSupport.crudeWithNitrogen(wet.contract());
        wetCrude[wet.contract().waterIndex()] = 1.0;
        double[] dampCrude = PhaseTestSupport.crudeWithNitrogen(wet.contract());
        dampCrude[wet.contract().waterIndex()] = 1.0e-3;
        List<String> pair = List.of("Methane", "Nitrogen");
        double[] crude = PhaseTestSupport.crudeWithNitrogen(network.contract());
        double[] nitrogen = new double[network.contract().components().size()];
        nitrogen[network.contract().index("Nitrogen")] = 1.0;
        record Case(String name, FluidTpEquilibrium service, EquilibriumRequest request) {}
        List<Case> cases = new java.util.ArrayList<>(List.of(
                new Case("2: 110 K 0.5 MPa x_N2 0.5", binary, EquilibriumRequest.tp(110.0, 0.5e6, pair, new double[] {0.5, 0.5}, PhaseCompetition.FLUID_ONLY)),
                new Case("2: 120 K 2.5 MPa x_N2 0.3", binary, EquilibriumRequest.tp(120.0, 2.5e6, pair, new double[] {0.7, 0.3}, PhaseCompetition.FLUID_ONLY)),
                new Case("2: 120 K 3 MPa x_N2 0.5", binary, EquilibriumRequest.tp(120.0, 3.0e6, pair, new double[] {0.5, 0.5}, PhaseCompetition.FLUID_ONLY)),
                new Case("1: N2 77.355 K 90 kPa", network, EquilibriumRequest.tp(77.355, 90_000.0, network.contract().components(), nitrogen, PhaseCompetition.FLUID_ONLY)),
                new Case("20: crude + N2 350 K 0.5 MPa", network, EquilibriumRequest.tp(350.0, 0.5e6, network.contract().components(), crude, PhaseCompetition.FLUID_ONLY)),
                new Case("20: crude + N2 600 K 2 MPa", network, EquilibriumRequest.tp(600.0, 2.0e6, network.contract().components(), crude, PhaseCompetition.FLUID_ONLY)),
                new Case("20: crude + N2 900 K 0.1 MPa", network, EquilibriumRequest.tp(900.0, 0.1e6, network.contract().components(), crude, PhaseCompetition.FLUID_ONLY)),
                new Case("2: 150 K 4.56 MPa x_N2 0.67 (P2 not converged)", binary, EquilibriumRequest.tp(150.0, 4.56e6, pair, new double[] {1.0 - 0.67, 0.67}, PhaseCompetition.FLUID_ONLY)),
                new Case("2: 165 K 4.98 MPa x_N2 0.47 (P2 not converged)", binary, EquilibriumRequest.tp(165.0, 4.98e6, pair, new double[] {1.0 - 0.47, 0.47}, PhaseCompetition.FLUID_ONLY)),
                new Case("21: crude + N2 + water 1 mol, 350 K 1 MPa", wet, EquilibriumRequest.tp(350.0, 1.0e6, wet.contract().components(), wetCrude, PhaseCompetition.FLUID_ONLY)),
                new Case("21: crude + N2 + water 1e-3 mol, 350 K 1 MPa", wet, EquilibriumRequest.tp(350.0, 1.0e6, wet.contract().components(), dampCrude, PhaseCompetition.FLUID_ONLY))));
        // P3 WP6b: PH and UV at the (H, U, V) of TP answers of the binary and the 20-component basis.
        for (int k : new int[] {0, 1, 4, 6}) {
            Case tp = cases.get(k);
            var answer = tp.service().tp(tp.request());
            List<String> species = tp.request().species();
            double[] amounts = tp.request().amounts();
            cases.add(new Case("PH " + tp.name(), tp.service(), EquilibriumRequest.ph(tp.request().pressure(), answer.enthalpy(), species, amounts, PhaseCompetition.FLUID_ONLY)));
            cases.add(new Case("UV " + tp.name(), tp.service(), EquilibriumRequest.uv(answer.internalEnergy(), answer.volume(), species, amounts, PhaseCompetition.FLUID_ONLY)));
        }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        StringBuilder table = new StringBuilder("P2/P3 TP, PH and UV cost (JDK " + Runtime.version() + ", "
                + Runtime.getRuntime().availableProcessors() + " processors)\n"
                + "| State | Result | TP calls | Flash iterations | Extrapolations | Newton steps | Kernel calls (derivative) | Mean us | p95 us | Bytes/call reused | Bytes/call new workspace |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Case c : cases) {
            var workspace = c.service().newWorkspace();
            boolean outer = c.request().specification() != EquilibriumRequest.Specification.TP;
            int warmup = outer ? 300 : 3000;
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < warmup; i++) solve(c.service(), c.request(), workspace);
            int calls = outer ? 100 : 200;
            long[] times = new long[calls];
            EquilibriumResult last = null;
            for (int i = 0; i < calls; i++) {
                long start = System.nanoTime();
                last = solve(c.service(), c.request(), workspace);
                times[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(times);
            double mean = java.util.Arrays.stream(times).average().orElse(0) / 1000.0;
            double p95 = times[(int) (0.95 * (calls - 1))] / 1000.0;
            long before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < calls; i++) solve(c.service(), c.request(), workspace);
            long reused = (threads.getThreadAllocatedBytes(thread) - before) / calls;
            before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < calls; i++) solve(c.service(), c.request(), c.service().newWorkspace());
            long fresh = (threads.getThreadAllocatedBytes(thread) - before) / calls;
            assertEquals(EquilibriumResult.Status.CONVERGED, last.status(), c.name());
            var d = last.diagnostics();
            table.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %d | %d (%d) | %.2f | %.2f | %d | %d |%n", c.name(),
                    last.classification() + (last.freeWater() == null ? "" : " " + last.phases().stream().map(p -> p.kind().toString()).toList()),
                    Math.max(1, d.outerIterations()), d.flashIterations(), d.extrapolations(), d.newtonIterations(), d.kernelEvaluations(),
                    d.derivativeEvaluations(), mean, p95, reused, fresh));
        }
        System.out.println(table);
    }

    private static EquilibriumResult solve(FluidTpEquilibrium service, EquilibriumRequest request, EquilibriumService.Workspace workspace) {
        return switch (request.specification()) {
            case TP -> service.tp(request, workspace);
            case PH -> service.ph(request, workspace);
            case UV -> service.uv(request, workspace);
        };
    }
}
