package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3SteamFeedSpec;
import java.util.ArrayList;
import java.util.List;

/** Pure player-unit steam parser, kept independent of widgets and server-only pressure validation. */
final class V3SteamFeedDraft {
    private static final double KMOL_PER_HOUR_TO_MOL_PER_SECOND = 1_000.0 / 3_600.0;
    private static final double CELSIUS_TO_KELVIN = 273.15;

    private V3SteamFeedDraft() {}

    static List<V3SteamFeedSpec> parse(Row sump, Row tray, int stageCount, double feedMolPerSecond) {
        List<V3SteamFeedSpec> result = new ArrayList<>(V3ColumnInput.MAX_STEAM_FEEDS);
        add(result, stageCount + 1, sump.rateKmolPerHour(), sump.temperatureCelsius(), "Sump steam");
        if (!tray.rateKmolPerHour().isBlank() && Double.parseDouble(tray.rateKmolPerHour().trim()) != 0.0) {
            int stage = Integer.parseInt(tray.stage().trim());
            if (stage < 1 || stage > stageCount) throw new IllegalArgumentException("Tray steam stage must be within the tray range");
            add(result, stage, tray.rateKmolPerHour(), tray.temperatureCelsius(), "Tray steam");
        }
        if (result.size() > 1 && result.get(0).stageNumber() == result.get(1).stageNumber()) {
            throw new IllegalArgumentException("Steam feeds must address distinct stages");
        }
        double total = result.stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        if (!Double.isFinite(feedMolPerSecond) || feedMolPerSecond <= 0.0 || total > feedMolPerSecond) {
            throw new IllegalArgumentException("Total steam rate must not exceed the feed rate");
        }
        return List.copyOf(result);
    }

    private static void add(List<V3SteamFeedSpec> result, int stage, String rateText, String temperatureText, String label) {
        if (rateText.isBlank()) return;
        double rate = Double.parseDouble(rateText.trim()) * KMOL_PER_HOUR_TO_MOL_PER_SECOND;
        if (rate == 0.0) return;
        if (temperatureText.isBlank()) throw new IllegalArgumentException(label + " temperature is required when rate is set");
        result.add(new V3SteamFeedSpec(stage, rate, Double.parseDouble(temperatureText.trim()) + CELSIUS_TO_KELVIN));
    }

    record Row(String stage, String rateKmolPerHour, String temperatureCelsius) {}
}
