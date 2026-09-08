package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The trays that carry a free-water phase, frozen for the length of one Newton solve.
 *
 * <p>A tray whose vapour would be supersaturated in water condenses part of that water into an immiscible
 * aqueous liquid, which leaves the tray downward with the hydrocarbon liquid and re-evaporates on a hotter
 * tray below. Each wet tray gains one unknown, {@code F_n} (the free water leaving it), and one equation,
 * the water saturation row.</p>
 *
 * <p><strong>The water balance is local.</strong> Writing the tray water balance as
 * {@code W_n = W_(n+1) + F_(n-1) + S_n - F_n} and substituting downward, every intermediate {@code F}
 * telescopes away and what remains is</p>
 *
 * <pre>W_n = (steam fed at or below n) + F_(n-1) - F_R</pre>
 *
 * <p>where {@code R} is the sump. Whatever condenses on a tray reaches the tray below and comes straight back
 * up, so it changes only that one neighbour's vapour water; the net water passing a tray is the authored steam
 * minus whatever leaves the bottom. That identity is what keeps the Jacobian banded, and it is why
 * {@link V3ColumnProblem#waterVaporFlow} needs no sweep: {@code W_n} is the authored upward profile plus the
 * free water of the tray directly above.</p>
 *
 * <p><strong>The sump is never wet</strong> (documented deviation from the plan). {@code F_R} is the one term
 * of that identity that every node above would see, so making it an unknown puts a dense column into an
 * otherwise tri-block system and breaks the off-band guard of {@link V3BlockJacobianAssembler} and the banded
 * solver. A sump below its own water dew point is instead reported by the {@code WATER_DEW_POINT} audit exactly
 * as a dry tray is; in a crude column it cannot happen, because the sump is the hottest node of the column.</p>
 */
final class V3WetTraySet {
    /** A dry tray becomes wet only when its saturation ratio clears one by more than this. */
    static final double WET_ENTRY_HYSTERESIS = 1.0e-6;
    /** A wet tray becomes dry when its solved free water falls below this fraction of the total steam. */
    static final double DRY_EXIT_FRACTION = 1.0e-6;
    /** Positivity floor of the log-flow coordinate, as a fraction of the total steam. */
    static final double MINIMUM_FREE_WATER_FRACTION = 1.0e-9;

    private final V3ColumnTopology topology;
    private final boolean[] wet;
    private final int wetCount;

    private V3WetTraySet(V3ColumnTopology topology, boolean[] wet) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.wet = wet.clone();
        if (this.wet.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 wet-tray set does not match the resolved topology");
        }
        int count = 0;
        for (int node = 0; node < this.wet.length; node++) {
            if (!this.wet[node]) continue;
            if (node < 1 || node > topology.trayCount()) {
                throw new IllegalArgumentException("V3 free water is only carried by equilibrium trays");
            }
            count++;
        }
        this.wetCount = count;
    }

    static V3WetTraySet dry(V3ColumnTopology topology) {
        return new V3WetTraySet(topology, new boolean[topology.nodeCount()]);
    }

    static V3WetTraySet of(V3ColumnTopology topology, boolean[] wet) {
        return new V3WetTraySet(topology, wet);
    }

    V3ColumnTopology topology() {
        return topology;
    }

    boolean isWet(int node) {
        return node >= 0 && node < wet.length && wet[node];
    }

    boolean hasWetTrays() {
        return wetCount > 0;
    }

    int wetTrayCount() {
        return wetCount;
    }

    List<Integer> wetTrays() {
        List<Integer> trays = new ArrayList<>(wetCount);
        for (int node = 1; node <= topology.trayCount(); node++) if (wet[node]) trays.add(node);
        return List.copyOf(trays);
    }

    boolean sameSet(V3WetTraySet other) {
        return other != null && topology.equals(other.topology) && Arrays.equals(wet, other.wet);
    }

    /**
     * Re-derives the wet set from a candidate state, top down, with the plan's hysteresis.
     *
     * <p>The pass carries its own {@code F} downward so the set it produces is self-consistent: a tray that
     * turns wet immediately raises the water its neighbour below sees, which is exactly what makes a wet
     * region spread over several trays instead of one per refresh. A tray that is wet in {@code problem}'s
     * current set keeps its solved free water; a tray that is not becomes wet only when its saturation ratio
     * clears one by {@link #WET_ENTRY_HYSTERESIS}, and a wet tray goes dry when its solved free water falls
     * below {@link #DRY_EXIT_FRACTION} of the total steam.</p>
     */
    static V3WetTraySet derive(V3ColumnProblem problem, V3DryMeshState state, V3WetTraySet previous) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(previous, "previous");
        V3ColumnTopology topology = problem.topology();
        if (!problem.hasSteamFeeds()) return dry(topology);
        double steamScale = problem.freeWaterFlowScaleMolPerSecond();
        boolean[] wet = new boolean[topology.nodeCount()];
        double carried = 0.0;
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            double water = problem.waterVaporFlowMolPerSecond(tray) + carried;
            Saturation saturation = saturation(problem, state, tray, water);
            boolean previouslyWet = previous.isWet(tray);
            double solved = previouslyWet ? state.freeWaterFlow(tray) : 0.0;
            boolean nowWet;
            if (saturation == null) {
                nowWet = false;
            } else if (previouslyWet) {
                nowWet = solved >= DRY_EXIT_FRACTION * steamScale;
            } else {
                nowWet = saturation.ratio() > 1.0 + WET_ENTRY_HYSTERESIS;
            }
            wet[tray] = nowWet;
            carried = nowWet ? freeWaterSeed(saturation, water, solved, steamScale) : 0.0;
        }
        return new V3WetTraySet(topology, wet);
    }

    /**
     * Writes this set's free-water flows into a candidate, zeroing every dry tray.
     *
     * <p>Called on the deciding seed of an attempt, after the truncation support has projected it. A tray that
     * was already wet and carries a usable flow keeps it, so a refreshed attempt restarts where the previous
     * one stopped; a newly wet tray is seeded with the water it holds above saturation, which is the flow that
     * makes its own saturation row read zero at the seed.</p>
     */
    V3DryMeshState seed(V3ColumnProblem problem, V3DryMeshState state) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(state, "state");
        if (!sameSet(problem.wetTraySet())) {
            throw new IllegalArgumentException("V3 free-water seed does not belong to its resolved problem");
        }
        if (!hasWetTrays()) {
            return V3ColumnInitializer.withFreeWater(state, topology, new double[topology.nodeCount()]);
        }
        double steamScale = problem.freeWaterFlowScaleMolPerSecond();
        double[] freeWater = new double[topology.nodeCount()];
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            if (!wet[tray]) continue;
            double water = problem.waterVaporFlowMolPerSecond(tray) + (tray >= 2 ? freeWater[tray - 1] : 0.0);
            freeWater[tray] = freeWaterSeed(saturation(problem, state, tray, water), water,
                    state.freeWaterFlow(tray), steamScale);
        }
        return V3ColumnInitializer.withFreeWater(state, topology, freeWater);
    }

    /** The free water a tray must shed for its vapour to sit exactly on the water saturation line. */
    private static double freeWaterSeed(Saturation saturation, double water, double solved, double steamScale) {
        double floor = Math.max(Double.MIN_NORMAL, MINIMUM_FREE_WATER_FRACTION * steamScale);
        if (solved > floor && Double.isFinite(solved)) return solved;
        double excess = saturation == null ? 0.0 : water - saturation.saturatedWaterMolPerSecond();
        return Double.isFinite(excess) && excess > floor ? excess : floor;
    }

    /**
     * The saturation ratio of one node's vapour and the water flow that would saturate it, or {@code null}
     * when water cannot condense there at all: outside the correlation envelope, or with a saturation
     * pressure at or above the node pressure, where no vapour composition is supersaturated.
     */
    private static Saturation saturation(V3ColumnProblem problem, V3DryMeshState state, int node, double water) {
        double temperature = state.temperatureKelvin(node);
        if (!(water > 0.0) || !Double.isFinite(water)
                || temperature < V3WaterProperties.TRIPLE_POINT_KELVIN
                || temperature >= V3WaterProperties.CRITICAL_TEMPERATURE_KELVIN) {
            return null;
        }
        double saturationPressure;
        try {
            saturationPressure = V3WaterProperties.saturationPressurePascal(temperature);
        } catch (IllegalArgumentException outsideEnvelope) {
            return null;
        }
        double pressure = problem.nodePressurePascal(node);
        if (!(saturationPressure < pressure)) return null;
        double hydrocarbon = hydrocarbonVaporTotal(state, node);
        if (!(hydrocarbon >= 0.0) || !Double.isFinite(hydrocarbon)) return null;
        double ratio = pressure * water / ((hydrocarbon + water) * saturationPressure);
        double saturated = hydrocarbon * saturationPressure / (pressure - saturationPressure);
        return Double.isFinite(ratio) && Double.isFinite(saturated) ? new Saturation(ratio, saturated) : null;
    }

    static double hydrocarbonVaporTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += state.vaporFlow(node, component);
        }
        return total;
    }

    /** Bounded event line: the wet set and the free water it carries, in the units the operator reads. */
    String event(V3DryMeshState state) {
        StringBuilder trays = new StringBuilder();
        StringBuilder flows = new StringBuilder();
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            if (!wet[tray]) continue;
            if (trays.length() > 0) {
                trays.append(", ");
                flows.append('/');
            }
            trays.append(tray);
            flows.append(String.format(java.util.Locale.ROOT, "%.1f", state.freeWaterFlow(tray) * 3.6));
        }
        String event = trays.length() == 0 ? "wet trays: none"
                : "wet trays: [" + trays + "]; free water: " + flows + " kmol/h";
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    @Override
    public String toString() {
        return "V3WetTraySet" + wetTrays();
    }

    private record Saturation(double ratio, double saturatedWaterMolPerSecond) {}
}
