package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Widget-independent draft. Untouched displayed numbers retain the exact server values. */
final class V3EditorDraft {
    private final V3ColumnInput base;
    private final Map<String, String> original = new LinkedHashMap<>();
    private final Map<String, String> fields = new LinkedHashMap<>();

    V3EditorDraft(V3ColumnInput base) {
        this.base = base;
        double total = java.util.Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum();
        double[] scalars = {total * 3.6, base.feedTemperatureKelvin() - 273.15, base.stageCount(),
                base.feedStageNumber(), spec(V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE) - 273.15,
                spec(V3ControlledQuantity.REBOILER_DUTY) / 1e6, spec(V3ControlledQuantity.ORGANIC_REFLUX_RATIO),
                base.topPressurePascal() / 1e5, base.stagePressureDropPascal() / 1000, base.columnDiameterMetres()};
        for (int i = 0; i < scalars.length; i++) put("s" + i, number(scalars[i]));
        for (int i = 0; i < 3; i++) {
            var d = i < base.sideDraws().size() ? base.sideDraws().get(i) : null;
            put("d" + i + "stage", d == null ? "" : Integer.toString(d.trayNumber()));
            put("d" + i + "rate", d == null ? "" : number(d.molarFlowMolPerSecond() * 3.6));
        }
        var sump = base.steamFeeds().stream().filter(s -> s.stageNumber() == base.stageCount() + 1).findFirst();
        var tray = base.steamFeeds().stream().filter(s -> s.stageNumber() <= base.stageCount()).findFirst();
        put("sumpRate", sump.map(s -> number(s.molarFlowMolPerSecond() * 3.6)).orElse(""));
        put("sumpT", sump.map(s -> number(s.temperatureKelvin() - 273.15)).orElse(""));
        put("steamStage", tray.map(s -> Integer.toString(s.stageNumber())).orElse(""));
        put("steamRate", tray.map(s -> number(s.molarFlowMolPerSecond() * 3.6)).orElse(""));
        put("steamT", tray.map(s -> number(s.temperatureKelvin() - 273.15)).orElse(""));
        for (int i = 0; i < V3ColumnInput.MAX_PUMPAROUNDS; i++) {
            var c = i < base.pumparounds().size() ? base.pumparounds().get(i) : null;
            put("c" + i + "draw", c == null ? "" : Integer.toString(c.drawTray()));
            put("c" + i + "return", c == null ? "" : Integer.toString(c.returnTray()));
            put("c" + i + "duty", c == null ? "" : number(-c.dutyWatts() / 1e6));
            put("c" + i + "split", c == null ? "UNIFORM" : c.split().name());
        }
    }

    V3ColumnInput base() { return base; }
    String get(String key) { return fields.get(key); }
    void set(String key, String value) {
        if (!fields.containsKey(key)) throw new IllegalArgumentException("Unknown input field");
        fields.put(key, value);
    }
    boolean dirty() { return !fields.equals(original); }
    private boolean unchanged(String key) { return fields.get(key).equals(original.get(key)); }
    private void put(String key, String value) { fields.put(key, value); original.put(key, value); }
    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    V3ColumnInput assemble() {
        if (!dirty() || V3HollandExample32.isPackage(base.packageId())) return base;
        List<String> scalar = new ArrayList<>();
        for (int i = 0; i < 10; i++) scalar.add(get("s" + i));
        List<V3SideDrawDraft.Row> draws = new ArrayList<>();
        for (int i = 0; i < 3; i++) draws.add(new V3SideDrawDraft.Row(get("d" + i + "stage"), get("d" + i + "rate")));
        List<V3PumparoundDraft.Row> coolers = new ArrayList<>();
        for (int i = 0; i < V3ColumnInput.MAX_PUMPAROUNDS; i++)
            coolers.add(new V3PumparoundDraft.Row(get("c" + i + "draw"), get("c" + i + "return"),
                    get("c" + i + "duty"), V3PumparoundSpec.Split.valueOf(get("c" + i + "split"))));
        var parsed = V3ColumnInputDraft.assemble(base, scalar, draws,
                new V3SteamFeedDraft.Row("", get("sumpRate"), get("sumpT")),
                new V3SteamFeedDraft.Row(get("steamStage"), get("steamRate"), get("steamT")), coolers);
        var preciseSteam = parsed.steamFeeds();
        if (groupUnchanged("sump") && groupUnchanged("steam")) {
            preciseSteam = base.steamFeeds().stream().map(feed -> new V3SteamFeedSpec(
                    feed.stageNumber() == base.stageCount() + 1 ? parsed.stageCount() + 1 : feed.stageNumber(),
                    feed.molarFlowMolPerSecond(), feed.temperatureKelvin())).toList();
        }
        var precise = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                unchanged("s0") ? base.feedComponentMolarFlowsMolPerSecond() : parsed.feedComponentMolarFlowsMolPerSecond(),
                unchanged("s1") ? base.feedTemperatureKelvin() : parsed.feedTemperatureKelvin(),
                parsed.stageCount(), parsed.feedStageNumber(), unchanged("s7") ? base.topPressurePascal() : parsed.topPressurePascal(),
                unchanged("s8") ? base.stagePressureDropPascal() : parsed.stagePressureDropPascal(),
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(unchanged("s4") ? spec(V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE) : value(parsed, V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)),
                        new V3ColumnSpecification.OrganicRefluxRatio(unchanged("s6") ? spec(V3ControlledQuantity.ORGANIC_REFLUX_RATIO) : value(parsed, V3ControlledQuantity.ORGANIC_REFLUX_RATIO)),
                        new V3ColumnSpecification.ReboilerDuty(unchanged("s5") ? spec(V3ControlledQuantity.REBOILER_DUTY) : value(parsed, V3ControlledQuantity.REBOILER_DUTY))),
                groupUnchanged("d") ? base.sideDraws() : parsed.sideDraws(),
                preciseSteam,
                groupUnchanged("c") ? base.pumparounds() : parsed.pumparounds(),
                unchanged("s9") ? base.columnDiameterMetres() : parsed.columnDiameterMetres());
        V3ColumnProblemResolver.validateInput(precise);
        return precise;
    }

    private boolean groupUnchanged(String prefix) {
        return fields.keySet().stream().filter(k -> k.startsWith(prefix)).allMatch(this::unchanged);
    }
    private double spec(V3ControlledQuantity quantity) { return value(base, quantity); }
    static double value(V3ColumnInput input, V3ControlledQuantity quantity) {
        return input.specifications().stream().filter(s -> s.controlledQuantity() == quantity).mapToDouble(s -> switch (s) {
            case V3ColumnSpecification.CondenserOutletTemperature t -> t.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio r -> r.ratio();
            case V3ColumnSpecification.ReboilerDuty d -> d.watts();
        }).findFirst().orElseThrow();
    }
}
