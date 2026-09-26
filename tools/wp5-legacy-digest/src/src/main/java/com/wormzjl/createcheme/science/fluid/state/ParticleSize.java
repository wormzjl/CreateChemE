package com.wormzjl.createcheme.science.fluid.state;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact, unit-normalized population identity; physics consumes its double value, never its key. */
public record ParticleSize(String metres) implements Comparable<ParticleSize> {
    public ParticleSize {
        Objects.requireNonNull(metres);
        if (metres.length() > 96) throw new IllegalArgumentException("Particle diameter is too long");
        BigDecimal value;
        try { value = new BigDecimal(metres).stripTrailingZeros(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid particle diameter", invalid); }
        if (value.signum() <= 0 || !Double.isFinite(value.doubleValue()) || value.doubleValue() <= 0
                || Math.abs(value.scale()) > 300) throw new IllegalArgumentException("Positive finite particle diameter required");
        metres = value.toString();
    }
    public static ParticleSize micrometres(String value) {
        if (value == null || value.length() > 96) throw new IllegalArgumentException("Invalid particle diameter");
        return new ParticleSize(new BigDecimal(value).scaleByPowerOfTen(-6).toString());
    }
    public double diameterMetres() { return Double.parseDouble(metres); }
    @Override public int compareTo(ParticleSize other) { return new BigDecimal(metres).compareTo(new BigDecimal(other.metres)); }
}