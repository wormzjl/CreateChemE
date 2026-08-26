package com.wormzjl.createcheme.science.column.nextgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Canonical text authoring syntax shared by the next calculator UI and headless validation tests. */
public final class ColumnNextAuthoring {
    private ColumnNextAuthoring() {}

    public static List<ColumnNextInput.SideDrawInput> parseSideDraws(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<ColumnNextInput.SideDrawInput> draws = new ArrayList<>();
        for (String entry : value.split(";")) {
            String[] fields = entry.trim().split(",", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Side draws use stage,m|kg,rate");
            ColumnNextInput.AuthoredBasis basis = switch (fields[1].trim().toLowerCase(Locale.ROOT)) {
                case "m", "mol", "molar" -> ColumnNextInput.AuthoredBasis.MOLAR;
                case "kg", "mass" -> ColumnNextInput.AuthoredBasis.MASS;
                default -> throw new IllegalArgumentException("Side-draw basis must be m or kg");
            };
            draws.add(new ColumnNextInput.SideDrawInput(
                    Integer.parseInt(fields[0].trim()), basis, finite(fields[2].trim())));
        }
        return List.copyOf(draws);
    }

    public static List<ColumnNextInput.WaterSteamFeedInput> parseUtilities(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<ColumnNextInput.WaterSteamFeedInput> utilities = new ArrayList<>();
        for (String entry : value.split(";")) {
            String[] fields = entry.trim().split(",", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Utilities use water|steam,stage,mol/s,K,kPa");
            ColumnNextInput.UtilityFeedMode mode = switch (fields[0].trim().toLowerCase(Locale.ROOT)) {
                case "water" -> ColumnNextInput.UtilityFeedMode.WATER;
                case "steam" -> ColumnNextInput.UtilityFeedMode.STEAM;
                default -> throw new IllegalArgumentException("Utility mode must be water or steam");
            };
            utilities.add(new ColumnNextInput.WaterSteamFeedInput(mode, Integer.parseInt(fields[1].trim()),
                    finite(fields[2].trim()), finite(fields[3].trim()), finite(fields[4].trim()) * 1_000.0));
        }
        return List.copyOf(utilities);
    }

    private static double finite(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) throw new NumberFormatException("numeric values must be finite");
        return value;
    }
}
