package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Objects;

/** Fully-resolved immutable next-column problem; all topology and pressure derivation occurs before worker admission. */
public final class ColumnProblem {
    private final ColumnNextInput input;
    private final ColumnThermoPackage propertyPackage;
    private final CharacterizedFeed feed;
    private final ColumnTopology topology;
    private final double[] nodePressuresPascal;

    private ColumnProblem(ColumnNextInput input, ColumnThermoPackage propertyPackage, CharacterizedFeed feed,
                          ColumnTopology topology, double[] nodePressuresPascal) {
        this.input = input;
        this.propertyPackage = propertyPackage;
        this.feed = feed;
        this.topology = topology;
        this.nodePressuresPascal = nodePressuresPascal;
    }

    public static ColumnProblem resolve(ColumnNextInput input) {
        ColumnNextValidation.Result validation = ColumnNextValidation.validate(input);
        if (!validation.isValid()) throw new IllegalArgumentException("Invalid next-column input: " + validation.diagnostics());
        ColumnThermoPackage propertyPackage = ColumnModelRegistry.require(input.packageId());
        CharacterizedFeed feed = propertyPackage.feedForAssay(input.assayId());
        if (input.crudeFeed().temperatureKelvin() < propertyPackage.minimumTemperatureKelvin()
                || input.crudeFeed().temperatureKelvin() > propertyPackage.maximumTemperatureKelvin()
                || input.condenserOutletTemperatureKelvin() < propertyPackage.minimumTemperatureKelvin()
                || input.condenserOutletTemperatureKelvin() > propertyPackage.maximumTemperatureKelvin()) {
            throw new IllegalArgumentException("Selected package does not support an accepted input temperature");
        }
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            if (utility.temperatureKelvin() < propertyPackage.minimumTemperatureKelvin()
                    || utility.temperatureKelvin() > propertyPackage.maximumTemperatureKelvin()) {
                throw new IllegalArgumentException("Selected package does not support a utility-feed temperature");
            }
        }
        ColumnTopology topology = ColumnTopology.create(input);
        if (!topology.hasSquareDegreesOfFreedom()) {
            throw new IllegalArgumentException("Selected topology and specifications do not close degrees of freedom");
        }
        int nodes = input.stageCount() + 2;
        double[] pressures = new double[nodes];
        pressures[0] = input.topPressurePascal();
        for (int stage = 1; stage <= input.stageCount(); stage++) pressures[stage] = input.pressureAtStageNumber(stage);
        pressures[nodes - 1] = pressures[input.stageCount()];
        for (double pressure : pressures) {
            if (!Double.isFinite(pressure) || pressure < propertyPackage.minimumPressurePascal()
                    || pressure > propertyPackage.maximumPressurePascal()) {
                throw new IllegalArgumentException("A resolved stage pressure is outside the selected package range");
            }
        }
        // Validate utility phase/enthalpy and isenthalpic throttling before admission to the shared CPU executor.
        WaterFeedProfile.resolve(input, propertyPackage.basis(), feed, pressures);
        return new ColumnProblem(input, propertyPackage, feed, topology, pressures);
    }

    public ColumnNextInput input() { return input; }
    public ColumnThermoPackage propertyPackage() { return propertyPackage; }
    public CharacterizedFeed feed() { return feed; }
    public ColumnTopology topology() { return topology; }
    public double[] nodePressuresPascal() { return nodePressuresPascal.clone(); }
    public double nodePressurePascal(int node) { return nodePressuresPascal[node]; }
}
