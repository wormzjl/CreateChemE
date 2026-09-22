package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.transport.SlurryTransport;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a strict transport event lands in time. The event is no longer bracketed by replaying the
 * interval from its start at a tightened tolerance; the adaptive controller rejects the step that
 * would contain the transition and halves onto it, so the event is located on the same step grid,
 * at the same tolerance, as the integration that carries the rest of the interval. These fixtures
 * qualify that placement: that it does not move when the tolerance is tightened by three orders,
 * and that a prescribed constant feed meets a filter capacity at the time arithmetic says it does.
 */
@Tag("solid-feasibility")
class SolidClosureFeasibilityTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();

    /** The declared time of one closure, as the event integrator records it against its reason. */
    private static double closureTime(PassiveIntervalSolver.Result result, String reason) {
        for (String key : result.rejectionReasons().keySet())
            if (key.startsWith("blocked with solid: " + reason))
                return Double.parseDouble(key.substring(key.lastIndexOf("; t=") + 4));
        throw new AssertionError("No " + reason + " closure among " + result.rejectionReasons().keySet());
    }

    @Test void drainingCarrierStopsAtTheParticleThresholdWhereATightIntegrationPutsIt() {
        var pipe = new PassiveNetwork.Pipe(3, 0, 1, new PipeResistance.Geometry(100, 0.05, 0.000045, 0));
        var initial = new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, water(160000).withSolids(particles(100))),
                new PassiveNetwork.Reservoir(2, 0, water(150000))), List.of(pipe));
        var weights = model.molecularWeights();
        var before = FluidTestSupport.finiteLedger(initial, weights);
        var initialMoles = initial.reservoirs().getFirst().inventory().moles();
        double duration = 0.05;
        // The floor below which the controller stops refining and declares the event where it
        // stands: PassiveIntervalSolver's max(1e-6 s, 1e-9 * interval).
        double floor = Math.max(1e-6, 1e-9 * duration);

        var result = new PassiveIntervalSolver(model).solve(initial, duration,
                PassiveIntervalSolver.Settings.defaults(), () -> {});
        // Three orders tighter on the tolerance and four on the largest step, i.e. a completely
        // different step sequence reaching the same physical crossing.
        var reference = new PassiveIntervalSolver(model).solve(initial, duration,
                new PassiveIntervalSolver.Settings(1e-6, 1e-4, 1e-6, 1024), () -> {});

        double located = closureTime(result, "DEPOSITION"), exact = closureTime(reference, "DEPOSITION");
        assertEquals(exact, located, 5 * floor, "Located closure must sit within a few refinement floors of the tight one");
        // A mobility failure closes the direction that failed, which here is the draining one.
        assertEquals(1, result.graph().pipes().getFirst().blockedDirections());
        assertEquals(1, reference.graph().pipes().getFirst().blockedDirections());
        // No transport after the closure, so the endpoint inventory is the inventory at the event.
        assertEquals(reference.graph().reservoirs().getFirst().state().mass(),
                result.graph().reservoirs().getFirst().state().mass(), 2e-6);
        assertEquals(100, result.graph().reservoirs().stream().mapToDouble(n -> n.inventory().solids().massKg()).sum(), 1e-9);
        FluidTestSupport.assertClosed(before, FluidTestSupport.finiteLedger(result.graph(), weights));
        // A trial integration never edits the caller's graph.
        assertArrayEquals(initialMoles, initial.reservoirs().getFirst().inventory().moles());
        System.out.printf("solid closure: deposition located=%.9f s reference=%.9f s floor=%.9f s%n", located, exact, floor);
    }

    @Test void constantFeedMeetsTheFilterCapacityAtTheArithmeticFillTime() {
        var slurry = water(1000000).withSolids(particles(100));
        var bore = new PipeResistance.Geometry(1, 0.01, 0.000045, 0);
        // Both endpoints are fixed and the driving pressure is far above the loss the filter can
        // ever develop, so the inlet sits on its velocity clamp for the whole fill: a prescribed
        // constant feed with no pump. The clamp is rho*A*vmax, so the captured solid volume grows
        // at exactly A * vmax * (solid volume fraction of the donor) until capacity is met.
        double solidFraction = slurry.solidMoments().volume() / slurry.volume();
        double captureRate = bore.area() * model.velocityLimit(slurry) * solidFraction;
        // Deliberately off the step grid, so that meeting the capacity at a step boundary is a
        // statement about the saturated law and not about 1.63 happening to be a dyadic rational.
        double exactFillTime = 1.63, capacity = captureRate * exactFillTime;
        var graph = new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1, 0, slurry, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, water(100000), PassiveNetwork.NodeKind.VOID)), List.of(
                new PassiveNetwork.Pipe(3, 0, 1, List.of(bore), new FlowControl.Passive(), 0,
                        new InlineFilter(capacity, 1e4, SolidInventory.EMPTY, 0))));
        double longestStep = 0.25;
        var result = new PassiveIntervalSolver(model).solve(graph, 3,
                new PassiveIntervalSolver.Settings(0.05, longestStep, 1e-3, 1024), () -> {});

        var cake = result.graph().pipes().getFirst().filter();
        assertTrue(cake.clogged());
        assertEquals(3, result.graph().pipes().getFirst().blockedDirections());
        // The saturated inlet law stops the transfer exactly at capacity rather than past it.
        assertEquals(capacity, cake.captured().volume(), 1e-9 * capacity);
        assertTrue(cake.captured().volume() <= capacity);
        double located = closureTime(result, "FILTER_CLOGGED");
        // The inlet saturates the moment the cake would pass capacity, so the closure is declared
        // at the first step boundary at or after the arithmetic fill: it lags by at most the one
        // step that straddles it, and can never precede it.
        assertTrue(located >= exactFillTime, "Capacity cannot be met before the feed delivers it: " + located);
        assertTrue(located <= exactFillTime + longestStep, "Capacity met more than one step late: " + located);
        // Walking onto a saturated law costs a rejection or two, not a bisection.
        assertTrue(result.rejectionReasons().getOrDefault("Solid transport transition inside the step", 0) <= 4,
                "Saturation should not need refining onto: " + result.rejectionReasons());
        assertEquals(result.boundaries().stream().mapToDouble(b -> b.solidDirection() * b.solids().massKg()).sum(),
                cake.captured().massKg(), 1e-8);
        System.out.println("solid closure: capacity located=" + located + " s exact=" + exactFillTime
                + " s captured=" + cake.captured().volume() + " of " + capacity + " m3, accepted="
                + result.acceptedSubsteps() + " rejected=" + result.rejectedSubsteps()
                + " reasons=" + result.rejectionReasons());
    }

    @Test void heavyLiquidCoolingStopsAtTheActualViscosityTableCrossing() {
        int heavy = model.hydrocarbon.components().indexOf("crude_pc12");
        assertTrue(heavy >= 0);
        var amounts = new double[model.hydrocarbon.componentCount()];
        amounts[heavy] = 1;
        // Prescribed cooling is a probe fixture, not a new heater or reservoir thermal model.
        java.util.function.DoubleUnaryOperator viscosity = seconds -> model.viscosity.liquid(340 - seconds, amounts).pascalSeconds();
        double mobile = 0, immobile = 10;
        assertFalse(SlurryTransport.immobile(viscosity.applyAsDouble(mobile), 100));
        assertTrue(SlurryTransport.immobile(viscosity.applyAsDouble(immobile), 100));
        for (int probe = 0; probe < 40 && immobile - mobile > 1e-6; probe++) {
            double middle = 0.5 * (mobile + immobile);
            if (SlurryTransport.immobile(viscosity.applyAsDouble(middle), 100)) immobile = middle; else mobile = middle;
        }
        assertTrue(immobile - mobile <= 1e-6);
        assertTrue(viscosity.applyAsDouble(mobile) <= 100);
        assertTrue(viscosity.applyAsDouble(immobile) > 100);
        assertEquals(100, viscosity.applyAsDouble(mobile), 1e-4);
        System.out.printf("solid closure: viscosity crossing=[%.9f, %.9f] K%n", 340 - immobile, 340 - mobile);
    }

    private SolidInventory particles(double mass) {
        return new SolidInventory(List.of(new SolidInventory.Population(
                model.solids.require("createcheme:demo_particle"), ParticleSize.micrometres("100"), mass)));
    }
    private FluidThermodynamics.State water(double pressure) {
        double[] moles = new double[model.componentCount()];
        moles[moles.length - 1] = 1;
        moles[moles.length - 1] /= model.flashTP(350, pressure, moles, () -> {}).volume();
        return model.flashTP(350, pressure, moles, () -> {});
    }
}
