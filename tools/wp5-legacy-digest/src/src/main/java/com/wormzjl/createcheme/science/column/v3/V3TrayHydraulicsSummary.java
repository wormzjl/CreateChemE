package com.wormzjl.createcheme.science.column.v3;

/**
 * Bounded presentation summary of the tray hydraulics of an accepted result.
 *
 * <p>Only requests that author a column diameter carry one. The per-tray profile it describes is the one the
 * published state was solved on ({@code result.problem().nodePressuresPascal()}) whenever a correction ran, and
 * the marched estimate otherwise; {@code residualMismatchFraction} is exactly the disagreement between the two.</p>
 */
public record V3TrayHydraulicsSummary(
        double columnDiameterMetres,
        double totalPressureDropPascal,
        double meanTrayPressureDropPascal,
        double maximumFloodFraction,
        int maximumFloodTray,
        boolean correctionApplied,
        double residualMismatchFraction,
        double worstTrayDryPascal,
        double worstTrayLiquidPascal) {
    public V3TrayHydraulicsSummary {
        if (!Double.isFinite(columnDiameterMetres) || columnDiameterMetres <= 0.0
                || !Double.isFinite(totalPressureDropPascal) || totalPressureDropPascal < 0.0
                || !Double.isFinite(meanTrayPressureDropPascal) || meanTrayPressureDropPascal < 0.0
                || !Double.isFinite(maximumFloodFraction) || maximumFloodFraction < 0.0
                || !Double.isFinite(residualMismatchFraction) || residualMismatchFraction < 0.0
                || !Double.isFinite(worstTrayDryPascal) || worstTrayDryPascal < 0.0
                || !Double.isFinite(worstTrayLiquidPascal) || worstTrayLiquidPascal < 0.0
                || maximumFloodTray < 1 || maximumFloodTray > V3ColumnInput.MAX_STAGE_COUNT) {
            throw new IllegalArgumentException("V3 tray hydraulics summary is outside the bounded contract");
        }
    }

    /** Whether the worst tray is past the Fair flooding limit and the result therefore carries a warning. */
    public boolean floods() {
        return maximumFloodFraction > 1.0;
    }

    /**
     * Whether the worst tray's drop is dominated by the orifice term rather than by the froth head.
     *
     * <p>That is the whole of the flooding diagnosis: a dry drop above the liquid head is vapour the
     * perforations cannot pass, and a dominant weir-crest head is liquid the weir and downcomer cannot pass.
     * The remedies differ, so the screen and the warning both say which one it is.</p>
     */
    public boolean vaporLimited() {
        return worstTrayDryPascal >= worstTrayLiquidPascal;
    }
}
