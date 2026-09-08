package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/**
 * Shared prescribed-stage-heat arithmetic; a duty is a constant source term, never a MESH unknown.
 *
 * <p>This is the only place that knows the split rule, so the digest can pin it with a single revision
 * string.</p>
 */
final class V3Pumparounds {
    /** Bumped whenever the tray expansion below changes for an unchanged authored input. */
    static final String SPLIT_RULE_REVISION = "v3-pumparound-split-r1";

    private V3Pumparounds() {}

    /** Expands the authored pumparounds into signed per-node duties; overlapping zones add. */
    static double[] nodeDutyWatts(V3ColumnInput input, V3ColumnTopology topology) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(topology, "topology");
        double[] duties = new double[topology.nodeCount()];
        for (V3PumparoundSpec pumparound : input.pumparounds()) {
            if (pumparound.drawTray() > topology.trayCount()) {
                throw new IllegalArgumentException("V3 pumparound tray is outside the equilibrium-tray range");
            }
            for (int tray = pumparound.returnTray(); tray <= pumparound.drawTray(); tray++) {
                duties[tray] += pumparound.trayDutyWatts(tray);
            }
        }
        return duties;
    }

    /**
     * Gross authored cooling as a nonpositive value; heating duties are excluded.
     *
     * <p>This is one half of the signed total. Any energy bound built on it has to credit the other half —
     * {@link #totalHeatingWatts(V3ColumnInput)} — because heat added on a tray is heat the coolers below can
     * remove again; see {@link V3HeatFeasibility}.</p>
     */
    static double totalCoolingWatts(V3ColumnInput input) {
        return input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).filter(duty -> duty < 0.0).sum();
    }

    /** Gross authored heating as a nonnegative value; the credit the cooling bounds owe the heaters. */
    static double totalHeatingWatts(V3ColumnInput input) {
        return input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).filter(duty -> duty > 0.0).sum();
    }

    /** Signed total of every authored duty. */
    static double totalDutyWatts(V3ColumnInput input) {
        return input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum();
    }

    /** Scales every authored duty by a continuation fraction, preserving geometry and split. */
    static List<V3PumparoundSpec> scaled(V3ColumnInput input, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0) {
            throw new IllegalArgumentException("V3 pumparound continuation fraction must be within zero to one");
        }
        if (fraction == 0.0) return List.of();
        return input.pumparounds().stream().map(pumparound -> new V3PumparoundSpec(pumparound.returnTray(),
                pumparound.drawTray(), fraction * pumparound.dutyWatts(), pumparound.split())).toList();
    }
}
