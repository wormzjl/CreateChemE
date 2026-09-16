package com.wormzjl.createcheme.fluid.support;

import java.util.HashSet;
import java.util.List;

/** BAL/STATE assertions shared by fluid science and runtime tests; all quantities are SI. */
public final class ConservationAssertions {
    private ConservationAssertions() {}

    public static void components(double[] before, double[] after, double[] netExternal, double[] turnover) {
        if (before.length != after.length || before.length != netExternal.length || before.length != turnover.length) {
            throw new AssertionError("Component basis mismatch");
        }
        for (int i = 0; i < before.length; i++) {
            nonnegative(before[i], "initial inventory");
            nonnegative(after[i], "final inventory");
            nonnegative(turnover[i], "absolute turnover");
            finite(netExternal[i], "net external input");
            double scale = Math.max(Math.max(before[i], after[i]), turnover[i]);
            close(after[i] - before[i], netExternal[i], 1e-10 + 1e-8 * scale, "component " + i);
        }
    }

    /** Caller totals include gravitational energy, pending transfers, and module-owned material. */
    public static void energy(double before, double after, double netExternal, double absoluteTurnover) {
        finite(before, "initial energy");
        finite(after, "final energy");
        finite(netExternal, "net external energy");
        nonnegative(absoluteTurnover, "absolute energy turnover");
        double scale = Math.max(Math.max(Math.abs(before), Math.abs(after)), absoluteTurnover);
        close(after - before, netExternal, 1e-4 + 1e-6 * scale, "total energy");
    }

    /** Portions share a transfer identity, but cannot share the same committed delivery revision. */
    public static void uniqueDeliveries(List<DeliveryKey> deliveries) {
        var seen = new HashSet<DeliveryKey>();
        for (DeliveryKey delivery : deliveries) {
            if (delivery == null || !seen.add(delivery)) throw new AssertionError("Duplicate/null delivery: " + delivery);
        }
    }

    private static void close(double actual, double expected, double tolerance, String label) {
        finite(actual, label);
        finite(expected, label);
        double error = Math.abs(actual - expected);
        if (!Double.isFinite(error) || error > tolerance) {
            throw new AssertionError(label + " residual=" + error + " tolerance=" + tolerance);
        }
    }
    private static void nonnegative(double value, String label) {
        finite(value, label);
        if (value < 0) throw new AssertionError(label + " is negative");
    }
    private static void finite(double value, String label) {
        if (!Double.isFinite(value)) throw new AssertionError(label + " is not finite");
    }
    public record DeliveryKey(long transferId, int revision) {}
}
