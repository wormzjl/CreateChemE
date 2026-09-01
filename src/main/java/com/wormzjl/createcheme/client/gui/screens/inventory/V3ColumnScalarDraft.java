package com.wormzjl.createcheme.client.gui.screens.inventory;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import java.util.List;

/** Pure parser for the nine scalar GUI fields, with field-specific diagnostics and SI conversion. */
final class V3ColumnScalarDraft {
    private static final double KMOL_PER_HOUR_TO_MOL_PER_SECOND = 1_000.0 / 3_600.0;
    private static final double CELSIUS_TO_KELVIN = 273.15;

    private V3ColumnScalarDraft() {}

    static Values parse(List<String> fields) {
        if (fields.size() != 9) throw new IllegalArgumentException("V3 scalar draft requires nine fields");
        double feed = number(fields, 0, "Feed flow") * KMOL_PER_HOUR_TO_MOL_PER_SECOND;
        double feedTemperature = number(fields, 1, "Feed temperature") + CELSIUS_TO_KELVIN;
        int stages = integer(fields, 2, "Stage count");
        int feedStage = integer(fields, 3, "Feed stage");
        double condenserTemperature = number(fields, 4, "Condenser temperature") + CELSIUS_TO_KELVIN;
        double duty = number(fields, 5, "Reboiler duty") * 1_000_000.0;
        double reflux = number(fields, 6, "Reflux ratio");
        double pressure = number(fields, 7, "Top pressure") * 100_000.0;
        double pressureDrop = number(fields, 8, "Pressure drop") * 1_000.0;
        if (!(feed > 0.0)) throw invalid("Feed flow", "must be positive");
        if (!(feedTemperature > 0.0)) throw invalid("Feed temperature", "must be above absolute zero");
        if (stages < V3ColumnInput.MIN_STAGE_COUNT || stages > V3ColumnInput.MAX_STAGE_COUNT) {
            throw invalid("Stage count", "is outside " + V3ColumnInput.MIN_STAGE_COUNT + ".." + V3ColumnInput.MAX_STAGE_COUNT);
        }
        if (feedStage < 1 || feedStage > stages) throw invalid("Feed stage", "must be within the column");
        if (!(condenserTemperature > 0.0)) throw invalid("Condenser temperature", "must be above absolute zero");
        if (duty < 0.0) throw invalid("Reboiler duty", "must be nonnegative");
        if (reflux < 0.0) throw invalid("Reflux ratio", "must be nonnegative");
        if (!(pressure > 0.0)) throw invalid("Top pressure", "must be positive");
        if (pressureDrop < 0.0) throw invalid("Pressure drop", "must be nonnegative");
        return new Values(feed, feedTemperature, stages, feedStage, condenserTemperature,
                duty, reflux, pressure, pressureDrop);
    }

    private static double number(List<String> fields, int index, String name) {
        try {
            double value = Double.parseDouble(fields.get(index));
            if (!Double.isFinite(value)) throw invalid(name, "must be finite");
            return value;
        } catch (NumberFormatException invalid) {
            throw invalid(name, "must be numeric");
        }
    }

    private static int integer(List<String> fields, int index, String name) {
        try {
            return Integer.parseInt(fields.get(index));
        } catch (NumberFormatException invalid) {
            throw invalid(name, "must be an integer");
        }
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException(name + " " + reason);
    }

    record Values(
            double feedMolPerSecond, double feedTemperatureKelvin, int stageCount, int feedStage,
            double condenserTemperatureKelvin, double reboilerDutyWatts, double refluxRatio,
            double topPressurePascal, double pressureDropPascal) {}
}
