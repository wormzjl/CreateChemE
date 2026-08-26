package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Arrays;
import java.util.Objects;

/** Immutable direct projection of accepted dry stage and boundary states; no product scaler is applied. */
public final class DryColumnResult {
    private final int stageCount;
    private final double[] nodeTemperaturesKelvin;
    private final double[] nodePressuresPascal;
    private final double[][] hydrocarbonLiquidComponentFlows;
    private final double[][] hydrocarbonVaporComponentFlows;
    private final double[][] hydrocarbonSideDrawComponentFlows;
    private final double[] hydrocarbonRefluxComponentFlows;
    private final double[] externalOverheadComponentFlows;
    private final double[] hydrocarbonBottomsComponentFlows;
    private final double[] waterVaporFlows;
    private final double[] aqueousWaterFlows;
    private final boolean[] waterWetMask;
    private final double condenserDutyWatts;
    private final DryAcceptanceAudit acceptanceAudit;

    DryColumnResult(
            int stageCount,
            double[] nodeTemperaturesKelvin,
            double[] nodePressuresPascal,
            double[][] hydrocarbonLiquidComponentFlows,
            double[][] hydrocarbonVaporComponentFlows,
            double[][] hydrocarbonSideDrawComponentFlows,
            double[] hydrocarbonRefluxComponentFlows,
            double[] externalOverheadComponentFlows,
            double[] hydrocarbonBottomsComponentFlows,
            double[] waterVaporFlows,
            double[] aqueousWaterFlows,
            boolean[] waterWetMask,
            double condenserDutyWatts,
            DryAcceptanceAudit acceptanceAudit) {
        if (stageCount < 2 || !Double.isFinite(condenserDutyWatts)) {
            throw new IllegalArgumentException("Invalid accepted dry result dimensions or condenser duty");
        }
        int nodes = stageCount + 2;
        requireLength(nodeTemperaturesKelvin, nodes, "nodeTemperaturesKelvin");
        requireLength(nodePressuresPascal, nodes, "nodePressuresPascal");
        requireRows(hydrocarbonLiquidComponentFlows, nodes, "hydrocarbonLiquidComponentFlows");
        requireRows(hydrocarbonVaporComponentFlows, nodes, "hydrocarbonVaporComponentFlows");
        requireRows(hydrocarbonSideDrawComponentFlows, stageCount + 1, "hydrocarbonSideDrawComponentFlows");
        requireLength(hydrocarbonRefluxComponentFlows, 16, "hydrocarbonRefluxComponentFlows");
        requireLength(externalOverheadComponentFlows, 16, "externalOverheadComponentFlows");
        requireLength(hydrocarbonBottomsComponentFlows, 16, "hydrocarbonBottomsComponentFlows");
        requireLength(waterVaporFlows, nodes, "waterVaporFlows");
        requireLength(aqueousWaterFlows, nodes, "aqueousWaterFlows");
        if (waterWetMask == null || waterWetMask.length != nodes) {
            throw new IllegalArgumentException("waterWetMask has invalid dimensions");
        }
        this.stageCount = stageCount;
        this.nodeTemperaturesKelvin = nodeTemperaturesKelvin.clone();
        this.nodePressuresPascal = nodePressuresPascal.clone();
        this.hydrocarbonLiquidComponentFlows = copy(hydrocarbonLiquidComponentFlows);
        this.hydrocarbonVaporComponentFlows = copy(hydrocarbonVaporComponentFlows);
        this.hydrocarbonSideDrawComponentFlows = copy(hydrocarbonSideDrawComponentFlows);
        this.hydrocarbonRefluxComponentFlows = hydrocarbonRefluxComponentFlows.clone();
        this.externalOverheadComponentFlows = externalOverheadComponentFlows.clone();
        this.hydrocarbonBottomsComponentFlows = hydrocarbonBottomsComponentFlows.clone();
        this.waterVaporFlows = waterVaporFlows.clone();
        this.aqueousWaterFlows = aqueousWaterFlows.clone();
        this.waterWetMask = waterWetMask.clone();
        this.condenserDutyWatts = condenserDutyWatts;
        this.acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        if (!acceptanceAudit.accepted()) {
            throw new IllegalArgumentException("An accepted result requires a passing audit");
        }
    }

    public int stageCount() { return stageCount; }
    public double[] nodeTemperaturesKelvin() { return nodeTemperaturesKelvin.clone(); }
    public double[] nodePressuresPascal() { return nodePressuresPascal.clone(); }
    public double[][] hydrocarbonLiquidComponentFlows() { return copy(hydrocarbonLiquidComponentFlows); }
    public double[][] hydrocarbonVaporComponentFlows() { return copy(hydrocarbonVaporComponentFlows); }
    public double[][] hydrocarbonSideDrawComponentFlows() { return copy(hydrocarbonSideDrawComponentFlows); }
    public double[] hydrocarbonRefluxComponentFlows() { return hydrocarbonRefluxComponentFlows.clone(); }
    public double[] externalOverheadComponentFlows() { return externalOverheadComponentFlows.clone(); }
    public double[] hydrocarbonBottomsComponentFlows() { return hydrocarbonBottomsComponentFlows.clone(); }
    /** Pure steam/water-vapor flow on the condenser/tray/reboiler node axis. */
    public double[] waterVaporFlows() { return waterVaporFlows.clone(); }
    /** Pure immiscible aqueous-liquid flow on the condenser/tray/reboiler node axis. */
    public double[] aqueousWaterFlows() { return aqueousWaterFlows.clone(); }
    public boolean[] waterWetMask() { return waterWetMask.clone(); }
    public double condenserDutyWatts() { return condenserDutyWatts; }
    public DryAcceptanceAudit acceptanceAudit() { return acceptanceAudit; }

    public double totalExternalOverheadMolarFlow() { return sum(externalOverheadComponentFlows); }
    public double totalHydrocarbonBottomsMolarFlow() { return sum(hydrocarbonBottomsComponentFlows); }

    private static void requireLength(double[] values, int expected, String name) {
        if (values == null || values.length != expected || Arrays.stream(values).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException(name + " has invalid dimensions or values");
        }
    }

    private static void requireRows(double[][] values, int expectedRows, String name) {
        if (values == null || values.length != expectedRows) {
            throw new IllegalArgumentException(name + " has invalid row count");
        }
        for (double[] row : values) requireLength(row, 16, name);
    }

    private static double[][] copy(double[][] values) {
        double[][] copy = new double[values.length][];
        for (int index = 0; index < copy.length; index++) copy[index] = values[index].clone();
        return copy;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }
}
