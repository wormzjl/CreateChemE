package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.fluid.support.ConservationAssertions;
import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Physical qualification fixtures for finite source depletion and pump suction.
 *
 * <p>These tests deliberately use initialized nitrogen reservoirs. A failed
 * overdraw is an atomic refusal; it is never treated as successful sustained
 * depletion.
 */
class SharedSourceDepletionQualificationTest {
    private static final int NITROGEN = com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN;
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private final double[] molecularWeights = FluidTestSupport.molecularWeights(model);

    private record Observation(double elapsedSeconds, Map<Long, Double> flows,
            double[] sourceMoles, double sourceEnergy) {
        private Observation {
            flows = Map.copyOf(flows);
            sourceMoles = sourceMoles.clone();
        }

        @Override
        public double[] sourceMoles() {
            return sourceMoles.clone();
        }
    }

    private record Snapshot(List<double[]> inventories, List<double[]> states) {
        private Snapshot {
            inventories = inventories.stream().map(double[]::clone).toList();
            states = states.stream().map(double[]::clone).toList();
        }
    }

    private record BalanceMeasurement(double componentToleranceUnits, double energyToleranceUnits) {}

    @Test
    void competingBranchesNeverWithdrawMoreThanFiniteSourceAcrossInputPermutations() {
        var trajectories = new LinkedHashMap<Boolean, List<Observation>>();
        var branchReports = new ArrayList<Map<String, Object>>();
        for (boolean permutedInputs : new boolean[] {false, true}) {
            var graph = competingBranches(permutedInputs, .002);
            var initialSource = graph.reservoirs().getFirst().inventory();
            boolean observedConcurrentWithdrawal = false;
            boolean observedAcceptedStep = false;
            double initialMass = mass(initialSource.moles());
            double elapsedSeconds = 0;
            double minimumFractionRemaining = 1;
            double minimumTemperature = Double.POSITIVE_INFINITY;
            double minimumPressure = Double.POSITIVE_INFINITY;
            double maximumComponentToleranceUnits = 0;
            double maximumEnergyToleranceUnits = 0;
            String refusalMessage = null;
            var trajectory = new ArrayList<Observation>();

            for (int interval = 0; interval < 24; interval++) {
                var before = snapshot(graph);
                PassiveIntervalSolver.Result result;
                try {
                    result = new PassiveIntervalSolver(model).solve(
                            graph, .01,
                            new PassiveIntervalSolver.Settings(.005, .01, 128),
                            () -> {});
                } catch (SparseNewton.Nonconvergence refusal) {
                    // A supported finite source may reject a near-empty trial. The
                    // attempted interval must remain unapplied and is not a pass for
                    // sustained depletion.
                    assertSnapshotUnchanged(before, graph);
                    refusalMessage = refusal.getMessage();
                    assertNotNull(refusalMessage, "refusal must preserve its diagnostic message");
                    break;
                }

                observedAcceptedStep = true;
                var next = result.graph();
                var balance = auditFiniteLedger(graph, next, result);
                maximumComponentToleranceUnits = Math.max(maximumComponentToleranceUnits,
                        balance.componentToleranceUnits());
                maximumEnergyToleranceUnits = Math.max(maximumEnergyToleranceUnits,
                        balance.energyToleranceUnits());
                elapsedSeconds += result.advancedSeconds();
                var source = next.reservoirs().getFirst();
                minimumFractionRemaining = Math.min(minimumFractionRemaining,
                        mass(source.inventory().moles()) / initialMass);
                minimumTemperature = Math.min(minimumTemperature, source.state().temperature());
                minimumPressure = Math.min(minimumPressure, source.state().pressure());
                var flows = new LinkedHashMap<Long, Double>();
                for (int i = 0; i < result.pipeTransfers().size(); i++) {
                    flows.put(result.pipeTransfers().get(i).pipeId(), result.averageMassFlows()[i]);
                }
                trajectory.add(new Observation(elapsedSeconds, flows,
                        source.inventory().moles(), source.inventory().internalEnergy()));
                for (double amount : next.reservoirs().getFirst().inventory().moles()) {
                    assertTrue(amount >= 0, "negative finite-source inventory: " + amount);
                }
                if (result.averageMassFlows()[branchIndex(result, 11)] > 0
                        && result.averageMassFlows()[branchIndex(result, 12)] > 0) {
                    observedConcurrentWithdrawal = true;
                }
                assertTrue(mass(next.reservoirs().getFirst().inventory().moles()) <= initialMass * (1 + 1e-10));
                graph = next;
            }

            String measurements = "acceptedSeconds=" + elapsedSeconds
                    + ", minimumFractionRemaining=" + minimumFractionRemaining
                    + ", minimumTemperatureKelvin=" + minimumTemperature
                    + ", minimumPressurePascal=" + minimumPressure;
            assertTrue(observedAcceptedStep, "fixture did not exercise an accepted coupled interval; " + measurements);
            assertTrue(observedConcurrentWithdrawal, "both unequal branches must withdraw concurrently; " + measurements);
            assertTrue(mass(graph.reservoirs().getFirst().inventory().moles()) < initialMass,
                    "finite source was not depleted by any accepted transfer; " + measurements);
            assertTrue(mass(graph.reservoirs().getFirst().inventory().moles()) >= 0, measurements);
            assertTrue(minimumFractionRemaining < 1, "no accepted withdrawal was observed; " + measurements);
            assertTrue(Double.isFinite(minimumTemperature) && Double.isFinite(minimumPressure), measurements);
            trajectories.put(permutedInputs, List.copyOf(trajectory));
            var branchReport = new LinkedHashMap<String, Object>();
            branchReport.put("permutedInputs", permutedInputs);
            branchReport.put("acceptedIntervals", trajectory.size());
            branchReport.put("acceptedSeconds", elapsedSeconds);
            branchReport.put("minimumFractionRemaining", minimumFractionRemaining);
            branchReport.put("minimumTemperatureKelvin", minimumTemperature);
            branchReport.put("minimumPressurePascal", minimumPressure);
            branchReport.put("refusalMessage", refusalMessage);
            branchReport.put("refusalClassification", refusalMessage == null
                    ? null : "numerical-refusal; physical-domain-cause-not-proven");
            branchReport.put("maximumComponentBalanceToleranceUnits", maximumComponentToleranceUnits);
            branchReport.put("maximumEnergyBalanceToleranceUnits", maximumEnergyToleranceUnits);
            branchReports.add(branchReport);
        }

        var canonical = trajectories.get(false);
        var permuted = trajectories.get(true);
        double maximumPermutationComponentError = 0;
        double maximumPermutationEnergyError = 0;
        double maximumPermutationFlowError = 0;
        assertEquals(canonical.size(), permuted.size(), "input permutations must accept the same intervals");
        for (int i = 0; i < canonical.size(); i++) {
            var a = canonical.get(i);
            var b = permuted.get(i);
            assertEquals(a.elapsedSeconds(), b.elapsedSeconds(), 1e-12);
            var aMoles = a.sourceMoles();
            var bMoles = b.sourceMoles();
            for (int c = 0; c < aMoles.length; c++) {
                maximumPermutationComponentError = Math.max(maximumPermutationComponentError,
                        Math.abs(aMoles[c] - bMoles[c]));
            }
            assertArrayEquals(aMoles, bMoles, 1e-8);
            maximumPermutationEnergyError = Math.max(maximumPermutationEnergyError,
                    Math.abs(a.sourceEnergy() - b.sourceEnergy()));
            assertEquals(a.sourceEnergy(), b.sourceEnergy(), 1e-4 + 1e-8 * Math.abs(a.sourceEnergy()));
            for (long pipeId : new long[] {11, 12}) {
                maximumPermutationFlowError = Math.max(maximumPermutationFlowError,
                        Math.abs(a.flows().get(pipeId) - b.flows().get(pipeId)));
                assertEquals(a.flows().get(pipeId), b.flows().get(pipeId),
                        1e-8 + 1e-8 * Math.abs(a.flows().get(pipeId)));
            }
        }
        writeJson("build/reports/fluid/P08-shared-source-depletion.json", Map.of(
                "status", "PASS",
                "model", "createcheme:tjl20_methane",
                "compressibility", 1e-9,
                "components", model.hydrocarbon.componentCount() + 1,
                "permutations", branchReports,
                "maximumPermutationComponentErrorMole", maximumPermutationComponentError,
                "maximumPermutationEnergyErrorJoule", maximumPermutationEnergyError,
                "maximumPermutationFlowErrorKgPerSecond", maximumPermutationFlowError));
    }

    @Test
    void excessivePumpRequestAllowsBoundedTransferOrAtomicRefusalAndControlTransfersMaterial() {
        var insufficient = pumpGraph(.001, 300000, 101325, .01);
        var beforeInventories = insufficient.reservoirs().stream()
                .map(PassiveNetwork.Reservoir::inventory).toList();
        var before = snapshot(insufficient);
        assertTrue(.01 > insufficient.reservoirs().getFirst().inventory().volume(),
                "requested pump volume exceeds the finite suction volume");
        String refusalMessage = null;
        String insufficientOutcome;
        Map<String, Object> insufficientReport;
        try {
            var bounded = new PassiveIntervalSolver(model).solve(
                    insufficient, 1, PassiveIntervalSolver.Settings.defaults(), () -> {});
            var afterBounded = bounded.graph();
            var balance = auditFiniteLedger(insufficient, afterBounded, bounded);
            assertTrue(afterBounded.reservoirs().getFirst().inventory().moles()[NITROGEN] >= 0);
            assertTrue(Math.abs(bounded.averageMassFlows()[0]) <= mass(beforeInventories.getFirst().moles()) + 1e-10);
            insufficientOutcome = "bounded-feasible-transfer";
            insufficientReport = new LinkedHashMap<>();
            insufficientReport.put("outcome", insufficientOutcome);
            insufficientReport.put("acceptedIntervals", bounded.acceptedSubsteps());
            insufficientReport.put("acceptedSeconds", bounded.advancedSeconds());
            insufficientReport.put("averageFlowKgPerSecond", bounded.averageMassFlows()[0]);
            insufficientReport.put("minimumFractionRemaining", mass(afterBounded.reservoirs().getFirst().inventory().moles())
                    / mass(beforeInventories.getFirst().moles()));
            insufficientReport.put("minimumTemperatureKelvin", afterBounded.reservoirs().getFirst().state().temperature());
            insufficientReport.put("minimumPressurePascal", afterBounded.reservoirs().getFirst().state().pressure());
            insufficientReport.put("refusalMessage", null);
            insufficientReport.put("maximumComponentBalanceToleranceUnits", balance.componentToleranceUnits());
            insufficientReport.put("maximumEnergyBalanceToleranceUnits", balance.energyToleranceUnits());
        } catch (SparseNewton.Nonconvergence refusal) {
            assertSnapshotUnchanged(before, insufficient);
            refusalMessage = refusal.getMessage();
            assertNotNull(refusalMessage, "refusal must preserve its diagnostic message");
            insufficientOutcome = refusalMessage.contains("overdraw")
                    ? "physical-inventory-overdraw-signaled; independent-domain-proof-not-established"
                    : "numerical-refusal; physical-domain-cause-not-proven";
            insufficientReport = new LinkedHashMap<>();
            insufficientReport.put("outcome", insufficientOutcome);
            insufficientReport.put("acceptedIntervals", 0);
            insufficientReport.put("acceptedSeconds", 0);
            insufficientReport.put("minimumFractionRemaining", 1.0);
            insufficientReport.put("minimumTemperatureKelvin", insufficient.reservoirs().getFirst().state().temperature());
            insufficientReport.put("minimumPressurePascal", insufficient.reservoirs().getFirst().state().pressure());
            insufficientReport.put("refusalMessage", refusalMessage);
            insufficientReport.put("refusalClassification", insufficientOutcome);
            insufficientReport.put("maximumComponentBalanceToleranceUnits", 0.0);
            insufficientReport.put("maximumEnergyBalanceToleranceUnits", 0.0);
        }

        var adequate = pumpGraph(1, 101325, 150000, .0001);
        var transferred = new PassiveIntervalSolver(model).solve(
                adequate, .1, PassiveIntervalSolver.Settings.defaults(), () -> {});
        var after = transferred.graph();
        assertTrue(transferred.averageMassFlows()[0] > 0, "adequate suction must transfer material");
        assertTrue(transferred.pumpWorkJoule() > 0, "accepted pump transfer must account for work");
        var adequateBalance = auditFiniteLedger(adequate, after, transferred);
        assertTrue(after.reservoirs().get(1).inventory().moles()[NITROGEN]
                > adequate.reservoirs().get(1).inventory().moles()[NITROGEN]);
        writeJson("build/reports/fluid/P24-finite-pump-suction.json", Map.of(
                "status", "PASS",
                "model", "createcheme:tjl20_methane",
                "compressibility", 1e-9,
                "components", model.hydrocarbon.componentCount() + 1,
                "requestedOutcome", insufficientOutcome,
                "insufficientSuction", insufficientReport,
                "adequateSuction", Map.of(
                        "acceptedIntervals", transferred.acceptedSubsteps(),
                        "acceptedSeconds", transferred.advancedSeconds(),
                        "averageFlowKgPerSecond", transferred.averageMassFlows()[0],
                        "pumpWorkJoule", transferred.pumpWorkJoule(),
                        "maximumComponentBalanceToleranceUnits", adequateBalance.componentToleranceUnits(),
                        "maximumEnergyBalanceToleranceUnits", adequateBalance.energyToleranceUnits())));
    }

    private PassiveNetwork competingBranches(boolean permutedInputs, double sourceVolume) {
        var source = new PassiveNetwork.Reservoir(
                1, 7, warmNitrogen(sourceVolume, 400000), PassiveNetwork.NodeKind.RESERVOIR);
        var branchA = new PassiveNetwork.Reservoir(
                2, 0, nitrogen(1, 101325), PassiveNetwork.NodeKind.VOID);
        var branchB = new PassiveNetwork.Reservoir(
                3, 0, nitrogen(1, 101325), PassiveNetwork.NodeKind.VOID);
        var shortRun = new PipeResistance.Geometry(4, .01, .000045, 0);
        var longRun = new PipeResistance.Geometry(20, .01, .000045, 0);
        var shortPipe = new PassiveNetwork.Pipe(11, 0, permutedInputs ? 2 : 1, shortRun);
        var longPipe = new PassiveNetwork.Pipe(12, 0, permutedInputs ? 1 : 2, longRun);
        return permutedInputs
                ? new PassiveNetwork(List.of(source, branchB, branchA), List.of(longPipe, shortPipe))
                : new PassiveNetwork(List.of(source, branchA, branchB), List.of(shortPipe, longPipe));
    }

    private PassiveNetwork pumpGraph(double sourceVolume, double sourcePressure,
            double receivingPressure, double targetVolumeFlow) {
        var source = new PassiveNetwork.Reservoir(
                21, 2, warmNitrogen(sourceVolume, sourcePressure), PassiveNetwork.NodeKind.RESERVOIR);
        var receiving = new PassiveNetwork.Reservoir(
                22, 6, nitrogen(1, receivingPressure), PassiveNetwork.NodeKind.RESERVOIR);
        // A pump's setting is its rise for water (F4, P1): the setting that is 500 kPa on this nitrogen, as before.
        double setting = 500000 * model.pumpReferenceDensity() / (source.state().mass() / source.state().volume());
        return new PassiveNetwork(List.of(source, receiving), List.of(
                new PassiveNetwork.Pipe(23, 0, 1,
                        new PipeResistance.Geometry(1, .02, .000045, 0),
                        new FlowControl.Pump(targetVolumeFlow, setting, .8))));
    }

    private FluidThermodynamics.State nitrogen(double volume, double pressure) {
        return FluidTestSupport.nitrogenCharge(model, volume, 298.15, pressure);
    }

    private FluidThermodynamics.State warmNitrogen(double volume, double pressure) {
        return FluidTestSupport.nitrogenCharge(model, volume, 350, pressure);
    }

    private int branchIndex(PassiveIntervalSolver.Result result, long pipeId) {
        for (int i = 0; i < result.pipeTransfers().size(); i++) {
            if (result.pipeTransfers().get(i).pipeId() == pipeId) return i;
        }
        throw new AssertionError("missing pipe transfer " + pipeId);
    }

    private BalanceMeasurement auditFiniteLedger(PassiveNetwork before, PassiveNetwork after,
            PassiveIntervalSolver.Result result) {
        return auditFiniteLedger(before, after, boundaryMoles(result.boundaries()), result.boundaries(),
                result.pipeTransfers(), result.pumpWorkJoule());
    }

    private BalanceMeasurement auditFiniteLedger(PassiveNetwork before, PassiveNetwork after,
            double[] externalMoles, List<ConservativeTransport.BoundaryTransfer> boundaries,
            List<PipeTransfer> pipeTransfers, double pumpWorkJoule) {
        var beforeLedger = FluidTestSupport.finiteLedger(before, molecularWeights);
        var afterLedger = FluidTestSupport.finiteLedger(after, molecularWeights);
        double[] beforeComponents = beforeLedger.components();
        double[] afterComponents = afterLedger.components();
        double[] turnover = componentTurnover(pipeTransfers);
        double maximumComponentUnits = 0;
        for (int c = 0; c < beforeComponents.length; c++) {
            double tolerance = 1e-10 + 1e-8 * Math.max(Math.max(beforeComponents[c], afterComponents[c]), turnover[c]);
            maximumComponentUnits = Math.max(maximumComponentUnits,
                    Math.abs(afterComponents[c] - beforeComponents[c] - externalMoles[c]) / tolerance);
        }
        ConservationAssertions.components(beforeComponents, afterComponents, externalMoles, turnover);
        double beforeEnergy = beforeLedger.energy();
        double afterEnergy = afterLedger.energy();
        double externalEnergy = boundaries.stream()
                .mapToDouble(ConservativeTransport.BoundaryTransfer::totalEnergyJoule).sum() + pumpWorkJoule;
        double absoluteEnergyTurnover = boundaries.stream()
                .mapToDouble(boundary -> Math.abs(boundary.totalEnergyJoule())).sum() + Math.abs(pumpWorkJoule);
        double energyTolerance = 1e-4 + 1e-6
                * Math.max(Math.max(Math.abs(beforeEnergy), Math.abs(afterEnergy)), absoluteEnergyTurnover);
        double maximumEnergyUnits = Math.abs(afterEnergy - beforeEnergy - externalEnergy) / energyTolerance;
        ConservationAssertions.energy(beforeEnergy, afterEnergy, externalEnergy, absoluteEnergyTurnover);
        for (var reservoir : after.reservoirs()) {
            if (reservoir.kind() == PassiveNetwork.NodeKind.RESERVOIR) {
                assertArrayEquals(PhaseLayout.totalAmounts(reservoir.state()), reservoir.inventory().moles(),
                        1e-8);
            }
        }
        return new BalanceMeasurement(maximumComponentUnits, maximumEnergyUnits);
    }

    private double[] componentTurnover(List<PipeTransfer> transfers) {
        double[] turnover = new double[model.hydrocarbon.componentCount() + 1];
        for (var transfer : transfers) {
            for (double[] stream : new double[][] {transfer.forward().componentMoles(), transfer.reverse().componentMoles()}) {
                for (int c = 0; c < turnover.length; c++) turnover[c] += stream[c];
            }
        }
        return turnover;
    }

    private double[] boundaryMoles(List<ConservativeTransport.BoundaryTransfer> boundaries) {
        double[] total = new double[model.hydrocarbon.componentCount() + 1];
        for (var boundary : boundaries) {
            double[] moles = boundary.moles();
            for (int c = 0; c < total.length; c++) total[c] += moles[c];
        }
        return total;
    }

    private void writeJson(String file, Object value) {
        try {
            Files.createDirectories(Path.of(file).getParent());
            Files.writeString(Path.of(file), new GsonBuilder().setPrettyPrinting().create().toJson(value));
        } catch (Exception failure) {
            throw new AssertionError("Could not write physical evidence " + file, failure);
        }
    }

    private double mass(double[] moles) {
        return FluidTestSupport.mass(moles, molecularWeights);
    }

    private Snapshot snapshot(PassiveNetwork graph) {
        var inventories = new ArrayList<double[]>();
        var states = new ArrayList<double[]>();
        for (var reservoir : graph.reservoirs()) {
            inventories.add(new double[] {reservoir.inventory().internalEnergy(),
                    reservoir.inventory().volume(), mass(reservoir.inventory().moles())});
            var state = reservoir.state();
            states.add(new double[] {state.temperature(), state.pressure(), state.internalEnergy(),
                    state.liquidVolume(), state.vaporVolume(), state.waterLiquid(), state.waterVapor()});
        }
        return new Snapshot(inventories, states);
    }

    private void assertSnapshotUnchanged(Snapshot before, PassiveNetwork after) {
        for (int i = 0; i < after.reservoirs().size(); i++) {
            var inventory = after.reservoirs().get(i).inventory();
            assertArrayEquals(before.inventories().get(i),
                    new double[] {inventory.internalEnergy(), inventory.volume(), mass(inventory.moles())}, 0);
            var state = after.reservoirs().get(i).state();
            assertArrayEquals(before.states().get(i), new double[] {state.temperature(), state.pressure(),
                    state.internalEnergy(), state.liquidVolume(), state.vaporVolume(), state.waterLiquid(),
                    state.waterVapor()}, 0);
        }
    }
}
