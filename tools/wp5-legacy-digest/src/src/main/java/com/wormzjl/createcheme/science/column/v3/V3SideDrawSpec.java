package com.wormzjl.createcheme.science.column.v3;

/** Specified liquid product rate on a one-based equilibrium tray (not a boundary node). */
public record V3SideDrawSpec(int trayNumber, double molarFlowMolPerSecond) {
    public V3SideDrawSpec {
        if (trayNumber < 1 || !Double.isFinite(molarFlowMolPerSecond) || molarFlowMolPerSecond <= 0.0) {
            throw new IllegalArgumentException("V3 side draws require a positive tray number and finite positive rate");
        }
    }
}
