package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/**
 * One prescribed internal heat duty authored as a pumparound between two one-based equilibrium trays.
 *
 * <p>Only the heat effect is modelled: liquid is withdrawn on {@code drawTray}, cooled, and returned on
 * {@code returnTray}, but no circulating stream is added to the internal traffic. {@code dutyWatts} uses the
 * existing {@link V3ColumnSpecification.ReboilerDuty} sign convention, so a positive value adds heat to the
 * column and a pumparound is negative. Boundary nodes are excluded: the condenser duty is an output and the
 * reboiler already carries its own specification.</p>
 */
public record V3PumparoundSpec(int returnTray, int drawTray, double dutyWatts, Split split) {
    /** Where the authored duty is placed inside the pumparound zone. */
    public enum Split {
        /** The whole duty is absorbed on the return tray, matching a single-stage reference energy stream. */
        RETURN_TRAY,
        /** The duty is divided equally over every tray from the return tray to the draw tray. */
        UNIFORM
    }

    public V3PumparoundSpec {
        split = Objects.requireNonNull(split, "split");
        if (returnTray < 1 || drawTray < returnTray) {
            throw new IllegalArgumentException(
                    "V3 pumparounds require 1 <= returnTray <= drawTray; received returnTray=" + returnTray
                            + ", drawTray=" + drawTray);
        }
        if (!Double.isFinite(dutyWatts) || dutyWatts == 0.0) {
            throw new IllegalArgumentException("V3 pumparound duty must be finite and nonzero");
        }
    }

    /** Number of trays over which this pumparound places its duty. */
    public int trayCount() {
        return split == Split.RETURN_TRAY ? 1 : drawTray - returnTray + 1;
    }

    /** Signed duty placed on one tray of the zone; zero outside it. */
    public double trayDutyWatts(int trayNumber) {
        if (trayNumber < returnTray || trayNumber > drawTray) return 0.0;
        return switch (split) {
            case RETURN_TRAY -> trayNumber == returnTray ? dutyWatts : 0.0;
            case UNIFORM -> dutyWatts / (drawTray - returnTray + 1);
        };
    }
}
