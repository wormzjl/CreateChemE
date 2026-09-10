package com.wormzjl.createcheme.science.column.v3;

/** Shared deterministic fixture scaling for standalone timing and memory probes. */
final class V3DiagnosticFixtures {
    private V3DiagnosticFixtures() {}

    static V3ColumnInput shippedDefault() {
        return com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.literatureCduInput();
    }

    /** One factor at a time: rates/pressure/reflux/duties +/-2%, feed temperature +/-2 K. */
    static java.util.Map<String, V3ColumnInput> defaultPerturbations() {
        return defaultPerturbations(new double[] {0.02}, false);
    }

    static java.util.Map<String, V3ColumnInput> largeDefaultPerturbations() {
        return defaultPerturbations(new double[] {0.05, 0.10}, true);
    }

    private static java.util.Map<String, V3ColumnInput> defaultPerturbations(double[] fractions, boolean relativeTemperature) {
        var inputs = new java.util.LinkedHashMap<String, V3ColumnInput>();
        V3ColumnInput base = shippedDefault();
        inputs.put("Default", base);
        for (String factor : java.util.List.of("Feed", "Temperature", "Pressure", "Reflux", "Steam", "Cooling")) {
            for (double fraction : fractions) {
            for (int sign : new int[] {-1, 1}) {
                double multiplier = 1.0 + sign * fraction;
                double[] feed = base.feedComponentMolarFlowsMolPerSecond();
                if (factor.equals("Feed")) for (int i = 0; i < feed.length; i++) feed[i] *= multiplier;
                var specifications = base.specifications().stream().map(spec ->
                        factor.equals("Reflux") && spec instanceof V3ColumnSpecification.OrganicRefluxRatio reflux
                                ? new V3ColumnSpecification.OrganicRefluxRatio(reflux.ratio() * multiplier) : spec).toList();
                var steam = base.steamFeeds().stream().map(value -> factor.equals("Steam")
                        ? new V3SteamFeedSpec(value.stageNumber(), value.molarFlowMolPerSecond() * multiplier,
                                value.temperatureKelvin()) : value).toList();
                var cooling = base.pumparounds().stream().map(value -> factor.equals("Cooling")
                        ? new V3PumparoundSpec(value.returnTray(), value.drawTray(), value.dutyWatts() * multiplier,
                                value.split()) : value).toList();
                String suffix = relativeTemperature ? Integer.toString((int) Math.round(fraction * 100)) : "";
                inputs.put("Default" + factor + (sign < 0 ? "Low" : "High") + suffix, new V3ColumnInput(
                        base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), feed,
                        base.feedTemperatureKelvin() + (factor.equals("Temperature")
                                ? sign * (relativeTemperature ? fraction * base.feedTemperatureKelvin() : 2.0) : 0.0),
                        base.stageCount(), base.feedStageNumber(),
                        base.topPressurePascal() * (factor.equals("Pressure") ? multiplier : 1.0),
                        base.stagePressureDropPascal(), specifications, base.sideDraws(), steam, cooling));
            }
            }
        }
        return inputs;
    }

    static V3ColumnInput with64Trays(V3ColumnInput base) {
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), 64,
                mapTray(base.feedStageNumber()), base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(),
                base.sideDraws().stream().map(draw -> new V3SideDrawSpec(mapTray(draw.trayNumber()), draw.molarFlowMolPerSecond())).toList(),
                base.steamFeeds().stream().map(steam -> new V3SteamFeedSpec(steam.stageNumber() == 31 ? 65 : mapTray(steam.stageNumber()),
                        steam.molarFlowMolPerSecond(), steam.temperatureKelvin())).toList(),
                base.pumparounds().stream().map(heat -> new V3PumparoundSpec(mapTray(heat.returnTray()), mapTray(heat.drawTray()),
                        heat.dutyWatts(), heat.split())).toList());
    }

    private static int mapTray(int tray) { return (int) Math.round(tray * 64.0 / 30.0); }
}
