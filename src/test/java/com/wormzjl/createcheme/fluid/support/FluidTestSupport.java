package com.wormzjl.createcheme.fluid.support;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Shared, independent fixture-basis and finite-ledger calculations for fluid tests. */
public final class FluidTestSupport {
    private static final String NETWORK_PACKAGE = "createcheme:tjl20_methane_nitrogen";
    private static final double DEFAULT_COMPRESSIBILITY = 1e-9;
    private static final int NITROGEN = com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN;
    private static final int WATER = com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK;

    /** Exact gameplay-basis compositions shared by the physical qualification fixtures. */
    public enum Mixture { NITROGEN, WATER, WET_CRUDE }

    private FluidTestSupport() {}

    /** Creates the bundled gameplay network model at the qualified compressibility. */
    public static FluidThermodynamics networkModel() {
        return FluidThermodynamics.forNetwork(
                MaterialCatalog.bundled(), NETWORK_PACKAGE, DEFAULT_COMPRESSIBILITY);
    }

    /** Returns a fresh gameplay-basis composition for the named qualification fixture. */
    private static double[] composition(Mixture mixture) {
        Objects.requireNonNull(mixture, "mixture");
        double[] amounts = new double[WATER+1];
        switch (mixture) {
            case NITROGEN -> amounts[NITROGEN] = 1;
            case WATER -> amounts[WATER] = 1;
            case WET_CRUDE -> {
                amounts = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(NETWORK_PACKAGE)
                        .crudeFeed("createcheme:tia_juana_light_methane").moleFractions(), WATER+1);
                amounts[NITROGEN] = .1;
                amounts[WATER] = .2;
            }
        }
        return amounts;
    }

    /** Builds the existing one-cubic-metre fixture without bypassing EOS/flash closure. */
    public static FluidThermodynamics.State oneCubicMetreState(
            FluidThermodynamics model, Mixture mixture, double temperature, double pressure) {
        Objects.requireNonNull(model, "model");
        double[] amounts = composition(mixture);
        var unit = model.flashTP(temperature, pressure, amounts, () -> {});
        for (int component = 0; component < amounts.length; component++) amounts[component] /= unit.volume();
        return model.flashTP(temperature, pressure, amounts, () -> {});
    }

    /** Retains the production nitrogen-initialization path used by world fixtures. */
    public static FluidThermodynamics.State nitrogenCharge(
            FluidThermodynamics model, double volume, double temperature, double pressure) {
        Objects.requireNonNull(model, "model");
        return model.initialNitrogenCharge(volume, temperature, pressure, () -> {});
    }

    /** Returns independent gameplay-basis molecular weights; does not call a production mass helper. */
    public static double[] molecularWeights(FluidThermodynamics model) {
        Objects.requireNonNull(model, "model");
        int components = model.hydrocarbon.componentCount() + 1;
        double[] weights = new double[components];
        for (int component = 0; component < components; component++) {
            weights[component] = component == components - 1
                    ? model.waterMolecularWeight : model.hydrocarbon.molecularWeight(component);
        }
        return weights;
    }

    /** Calculates mass directly from the supplied mole and molecular-weight bases. */
    public static double mass(double[] moles, double[] molecularWeights) {
        Objects.requireNonNull(moles, "moles");
        Objects.requireNonNull(molecularWeights, "molecularWeights");
        if (moles.length != molecularWeights.length) throw new IllegalArgumentException("Component basis mismatch");
        double total = 0;
        for (int component = 0; component < moles.length; component++) {
            total += moles[component] * molecularWeights[component];
        }
        return total;
    }

    /** Totals one graph's finite component and gravity-inclusive energy ownership. */
    public static Ledger finiteLedger(PassiveNetwork graph, double[] molecularWeights) {
        return finiteLedger(List.of(Objects.requireNonNull(graph, "graph")), molecularWeights);
    }

    /** Totals only finite RESERVOIR ownership; generators, voids, ports and junctions own no finite ledger here. */
    public static Ledger finiteLedger(Iterable<PassiveNetwork> graphs, double[] molecularWeights) {
        Objects.requireNonNull(graphs, "graphs");
        double[] weights = Objects.requireNonNull(molecularWeights, "molecularWeights").clone();
        double[] components = new double[weights.length];
        double energy = 0;
        for (var graph : graphs) {
            for (var reservoir : graph.reservoirs()) {
                if (reservoir.kind() != PassiveNetwork.NodeKind.RESERVOIR) continue;
                var inventory = reservoir.inventory();
                double[] moles = inventory.moles();
                if (moles.length != components.length) throw new IllegalArgumentException("Component basis mismatch");
                for (int component = 0; component < components.length; component++) {
                    if (!Double.isFinite(moles[component]) || moles[component] < 0) {
                        throw new AssertionError("Invalid finite inventory at reservoir " + reservoir.id()
                                + " component " + component + ": " + moles[component]);
                    }
                    components[component] += moles[component];
                }
                energy += inventory.internalEnergy()
                        + mass(moles, weights) * PassiveStepSolver.GRAVITY * reservoir.elevation();
            }
        }
        return new Ledger(components, energy);
    }

    /** Asserts closed component and energy balance with the shared BAL tolerances. */
    public static void assertClosed(Ledger before, Ledger after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        double[] beforeComponents = before.components();
        ConservationAssertions.components(
                beforeComponents, after.components(), new double[beforeComponents.length], beforeComponents);
        ConservationAssertions.energy(before.energy(), after.energy(), 0, Math.abs(before.energy()));
    }

    /** Immutable totals for finite component and gravity-inclusive energy ownership. */
    public record Ledger(double[] components, double energy) {
        public Ledger {
            components = Objects.requireNonNull(components, "components").clone();
            if (!Double.isFinite(energy)) throw new IllegalArgumentException("Nonfinite ledger energy");
            for (double amount : components) {
                if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid ledger inventory");
            }
        }

        @Override public double[] components() { return components.clone(); }
    }

    /** Immutable SI inventory fixture; supplies no invented thermodynamic closure. */
    public record ReservoirFixture(double volumeCubicMetres, double[] componentMoles,
                                   double internalEnergyJoules, double elevationMetres) {
        public ReservoirFixture {
            if (!Double.isFinite(volumeCubicMetres) || volumeCubicMetres <= 0
                    || !Double.isFinite(internalEnergyJoules) || !Double.isFinite(elevationMetres)) {
                throw new IllegalArgumentException("Invalid SI fixture values");
            }
            componentMoles = componentMoles.clone();
            for (double amount : componentMoles) {
                if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid mole inventory");
            }
        }

        @Override public double[] componentMoles() { return componentMoles.clone(); }
    }

    /** Controlled monotonic clock for deterministic deadline tests. */
    public static final class ManualNanoClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong(1);

        @Override public long getAsLong() { return now.get(); }

        public long advance(long nanos) {
            if (nanos < 0) throw new IllegalArgumentException("Clock cannot move backward");
            return now.updateAndGet(previous -> Math.addExact(previous, nanos));
        }
    }
}
