package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.transport.SlurryTransport;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * First implementation milestone: replay the existing hydraulic solver up to a bracketed event.
 * The drain uses carrier hydraulics and a fixed representative particle threshold. The capacity
 * fixture uses a prescribed feed and a volume counter. These are not a coupled slurry/filter
 * implementation and do not qualify changing populations, cake resistance, or network topology.
 */
@Tag("solid-feasibility")
class SolidClosureFeasibilityTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private final PassiveIntervalSolver.Settings interval =
            new PassiveIntervalSolver.Settings(1e-5, 0.0005, 1e-6, 1024);

    @Test void drainingCarrierCanStopAtTheParticleThresholdWithoutCommittingTheBlockedTrial() {
        var pipe = new PassiveNetwork.Pipe(3, 0, 1, new PipeResistance.Geometry(100, 0.05, 0.000045, 0));
        var initial = new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, water(350, 160000)),
                new PassiveNetwork.Reservoir(2, 0, water(350, 150000))), List.of(pipe));
        var weights = model.molecularWeights();
        var before = FluidTestSupport.finiteLedger(initial, weights);
        var initialMoles = initial.reservoirs().getFirst().inventory().moles();
        double initialEnergy = initial.reservoirs().getFirst().inventory().internalEnergy();
        var rejected = new AtomicInteger();
        var accepted = new AtomicInteger();
        var solver = new PassiveIntervalSolver(model);
        java.util.function.DoubleFunction<ThresholdEventLocator.Sample<PassiveNetwork>> probe = seconds -> {
            var graph = initial;
            if (seconds > 0) {
                var result = solver.solve(initial, seconds, interval, () -> {});
                graph = result.graph();
                rejected.addAndGet(result.rejectedSubsteps());
                accepted.addAndGet(result.acceptedSubsteps());
            }
            return new ThresholdEventLocator.Sample<>(seconds, suspensionMargin(graph) > 0, graph);
        };
        var start = probe.apply(0);
        var end = probe.apply(0.05);
        assertTrue(start.allowed(), "Fixture must start above the suspension threshold");
        assertFalse(end.allowed(), "Fixture must cross the suspension threshold");
        var bracket = ThresholdEventLocator.locate(start, end, probe, ThresholdEventLocator.Settings.defaults(), () -> {});
        assertTrue(bracket.uncertaintySeconds() <= 1e-6);
        assertTrue(suspensionMargin(bracket.safe().value()) > 0);
        assertTrue(suspensionMargin(bracket.blocked().value()) <= 0);
        assertTrue(bracket.probes() <= 16);
        assertTrue(rejected.get() <= 128, "Event replay must not exhaust adaptive refinement");

        // Independent tighter integration checks event timing, not just bracket convergence.
        var referenceSolver = new PassiveIntervalSolver(model);
        var referenceSettings = new PassiveIntervalSolver.Settings(1e-6, 1e-4, 1e-8, 1024);
        java.util.function.DoubleFunction<ThresholdEventLocator.Sample<PassiveNetwork>> referenceProbe = seconds -> {
            var graph = seconds == 0 ? initial : referenceSolver.solve(initial, seconds, referenceSettings, () -> {}).graph();
            return new ThresholdEventLocator.Sample<>(seconds, suspensionMargin(graph) > 0, graph);
        };
        var reference = ThresholdEventLocator.locate(referenceProbe.apply(0), referenceProbe.apply(0.01),
                referenceProbe, new ThresholdEventLocator.Settings(1e-9, 0, 32), () -> {});
        assertEquals(reference.safe().seconds(), bracket.safe().seconds(), 2e-6);
        assertEquals(reference.safe().value().reservoirs().getFirst().state().mass(),
                bracket.safe().value().reservoirs().getFirst().state().mass(), 2e-6);
        FluidTestSupport.assertClosed(before, FluidTestSupport.finiteLedger(bracket.safe().value(), weights));
        assertArrayEquals(initialMoles, initial.reservoirs().getFirst().inventory().moles());
        assertEquals(initialEnergy, initial.reservoirs().getFirst().inventory().internalEnergy());

        // A closed connection cannot transport the remainder of the requested interval.
        var stopped = new PassiveNetwork(bracket.safe().value().reservoirs(), List.of());
        var rest = solver.solve(stopped, 0.05 - bracket.safe().seconds(), interval, () -> {});
        FluidTestSupport.assertClosed(before, FluidTestSupport.finiteLedger(rest.graph(), weights));
        assertArrayEquals(stopped.reservoirs().getFirst().inventory().moles(),
                rest.graph().reservoirs().getFirst().inventory().moles());
        System.out.printf("solid-spike drain: event=[%.9f, %.9f] s, probes=%d, accepted=%d, rejected=%d%n",
                bracket.safe().seconds(), bracket.blocked().seconds(), bracket.probes(), accepted.get(), rejected.get());
    }

    @Test void constantFeedCapacityEventAgreesWithTheIndependentFillTime() {
        double liquidFlow = 0.0009, solidToLiquidVolume = 1.0 / 9, capacity = 0.01;
        double exactFillTime = capacity / (liquidFlow * solidToLiquidVolume);
        var state = water(350, 150000);
        var geometry = new PipeResistance.Geometry(10, 0.05, 0.000045, 0);
        var initial = new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, state, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, state, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(3, 0, 1, geometry, new FlowControl.Pump(liquidFlow, 2e6, 1))));
        var settings = new PassiveIntervalSolver.Settings(20, 20, 0.001, 1024);
        var solver = new PassiveIntervalSolver(model);
        var rejected = new AtomicInteger();
        java.util.function.DoubleFunction<ThresholdEventLocator.Sample<Double>> probe = seconds -> {
            double captured = 0;
            if (seconds > 0) {
                var result = solver.solve(initial, seconds, settings, () -> {});
                rejected.addAndGet(result.rejectedSubsteps());
                captured = result.pipeTransfers().getFirst().forward().phaseVolumes()[1] * solidToLiquidVolume;
            }
            return new ThresholdEventLocator.Sample<>(seconds, captured < capacity, captured);
        };
        var bracket = ThresholdEventLocator.locate(probe.apply(0), probe.apply(2 * exactFillTime),
                probe, new ThresholdEventLocator.Settings(1e-6, 0, 32), () -> {});
        assertTrue(bracket.safe().value() < capacity);
        assertTrue(bracket.blocked().value() >= capacity);
        assertEquals(exactFillTime, bracket.safe().seconds(), 2e-5);
        assertTrue(bracket.uncertaintySeconds() <= 1e-6);
        assertTrue(bracket.probes() <= 28);
        assertEquals(0, rejected.get(), "A fixed feed needs no repeated adaptive rejection");
        assertTrue(capacity - bracket.safe().value() <= liquidFlow * solidToLiquidVolume * 2e-5);
        System.out.printf("solid-spike capacity: event=[%.9f, %.9f] s, exact=%.9f s, probes=%d, rejected=%d%n",
                bracket.safe().seconds(), bracket.blocked().seconds(), exactFillTime, bracket.probes(), rejected.get());
    }

    @Test void heavyLiquidCoolingStopsAtTheActualViscosityTableCrossing() {
        int heavy = model.hydrocarbon.components().indexOf("crude_pc12");
        assertTrue(heavy >= 0);
        var amounts = new double[model.hydrocarbon.componentCount()];
        amounts[heavy] = 1;
        // Prescribed cooling is a probe fixture, not a new heater or reservoir thermal model.
        java.util.function.DoubleFunction<ThresholdEventLocator.Sample<Double>> probe = seconds -> {
            double viscosity = model.viscosity.liquid(340 - seconds, amounts).pascalSeconds();
            return new ThresholdEventLocator.Sample<>(seconds, !SlurryTransport.immobile(viscosity, 100), viscosity);
        };
        var bracket = ThresholdEventLocator.locate(probe.apply(0), probe.apply(10), probe,
                ThresholdEventLocator.Settings.defaults(), () -> {});
        assertTrue(bracket.safe().value() <= 100);
        assertTrue(bracket.blocked().value() > 100);
        assertTrue(bracket.uncertaintySeconds() <= 1e-6);
        assertTrue(bracket.probes() <= 24);
        assertEquals(100, bracket.safe().value(), 1e-4);
        System.out.printf("solid-spike viscosity: crossing=[%.9f, %.9f] K, probes=%d%n",
                340 - bracket.blocked().seconds(), 340 - bracket.safe().seconds(), bracket.probes());
    }

    private FluidThermodynamics.State water(double temperature, double pressure) {
        double[] moles = new double[model.componentCount()];
        int water = model.components().indexOf("Water");
        moles[water] = 1;
        var unit = model.flashTP(temperature, pressure, moles, () -> {});
        moles[water] /= unit.volume();
        return model.flashTP(temperature, pressure, moles, () -> {});
    }

    private double suspensionMargin(PassiveNetwork graph) {
        var ports = graph.reservoirs().stream().map(node -> new PassiveNetwork.Reservoir(
                node.id(), node.elevation(), node.state(), PassiveNetwork.NodeKind.PORT, node.inventory())).toList();
        var rate = new PassiveStepSolver(model).solve(new PassiveNetwork(ports, graph.pipes()), 1, () -> {});
        double massFlow = rate.massFlows()[0];
        var donor = graph.reservoirs().get(massFlow >= 0 ? 0 : 1).state();
        double density = donor.mass() / donor.volume();
        double velocity = Math.abs(massFlow) / density / graph.pipes().getFirst().minimumArea();
        double deposition = SlurryTransport.depositionVelocity(100e-6, 2500, density,
                model.viscosity.waterLiquid(donor.temperature()), 10);
        return velocity - deposition;
    }
}