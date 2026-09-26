package com.wormzjl.createcheme.science.column.v3;

/** Shared side-draw arithmetic; callers decide whether an invalid split is fatal or diagnostic evidence. */
final class V3SideDraws {
    private V3SideDraws() {}

    static Withdrawal withdrawal(V3DryMeshState state, int node, double rateMolPerSecond) {
        if (!Double.isFinite(rateMolPerSecond) || rateMolPerSecond < 0.0) {
            throw new IllegalArgumentException("V3 side-draw rate must be finite and nonnegative");
        }
        double total = liquidTotal(state, node);
        if (!(total > 0.0) || !Double.isFinite(total)) {
            throw new IllegalArgumentException("V3 side draw on tray " + node + " has no finite positive liquid flow");
        }
        double fraction = rateMolPerSecond / total;
        if (!Double.isFinite(fraction)) {
            throw new IllegalArgumentException("V3 side draw on tray " + node + " has a nonfinite withdrawal fraction");
        }
        return new Withdrawal(total, fraction);
    }

    static double liquidTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += state.liquidFlow(node, component);
        }
        return total;
    }

    record Withdrawal(double liquidTotalMolPerSecond, double fraction) {}
}
