package com.wormzjl.createcheme.science.material;

import java.util.Locale;
import java.util.Objects;

/** Immutable presentation data with no Minecraft or locale dependency. */
public record MaterialName(String id, String translationKey, String fallback, String kind,
        Double lowerKelvin, Double upperKelvin, boolean estimated) {
    public MaterialName {
        requireText(id, 64); requireText(translationKey, 128); requireText(fallback, 128);
        if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")) throw new IllegalArgumentException("Invalid material ID");
        if (!java.util.Set.of("chemical", "petroleum_fraction", "lump").contains(kind))
            throw new IllegalArgumentException("Unknown material kind: " + kind);
        if (lowerKelvin != null && (!Double.isFinite(lowerKelvin) || lowerKelvin < 0)
                || upperKelvin != null && (!Double.isFinite(upperKelvin) || upperKelvin <= 0)
                || lowerKelvin != null && upperKelvin != null && lowerKelvin >= upperKelvin)
            throw new IllegalArgumentException("Invalid material boiling range");
        if (kind.equals("petroleum_fraction") && lowerKelvin == null && upperKelvin == null)
            throw new IllegalArgumentException("Petroleum fraction requires a boiling bound");
        if (kind.equals("chemical") && !fallback.equals(id))
            throw new IllegalArgumentException("Chemical English name must equal ID");
    }
    private static void requireText(String s, int max) {
        Objects.requireNonNull(s);
        if (s.isBlank() || s.length() > max || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid material name text");
    }
    public String rangeKey() {
        return lowerKelvin == null ? "material.createcheme.nbp_below"
                : upperKelvin == null ? "material.createcheme.nbp_above" : "material.createcheme.nbp_range";
    }
    public String lowerCelsius() { return celsius(lowerKelvin); }
    public String upperCelsius() { return celsius(upperKelvin); }
    private static String celsius(Double kelvin) { return kelvin == null ? "" : String.format(Locale.ROOT, "%.1f", kelvin - 273.15); }
    public String english() {
        if (!kind.equals("petroleum_fraction")) return fallback;
        String range = lowerKelvin == null ? "below " + upperCelsius()
                : upperKelvin == null ? "above " + lowerCelsius() : lowerCelsius() + "–" + upperCelsius();
        return fallback + ", NBP " + range + "°C" + (estimated ? " (estimated)" : "");
    }
    public static MaterialName chemical(String id) {
        return new MaterialName(id, "material.createcheme." + id.toLowerCase(Locale.ROOT), id, "chemical", null, null, false);
    }
}
