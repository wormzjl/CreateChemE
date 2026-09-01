package com.wormzjl.createcheme.science.column.v3;

/** Superheated steam injected into one node; {@code stageCount + 1} addresses the sump. */
public record V3SteamFeedSpec(int stageNumber, double molarFlowMolPerSecond, double temperatureKelvin) {
    public V3SteamFeedSpec {
        if (stageNumber < 1 || !Double.isFinite(molarFlowMolPerSecond) || molarFlowMolPerSecond <= 0.0
                || !Double.isFinite(temperatureKelvin) || temperatureKelvin <= 0.0) {
            throw new IllegalArgumentException("V3 steam feeds require a positive stage and finite positive rate and temperature");
        }
    }
}
