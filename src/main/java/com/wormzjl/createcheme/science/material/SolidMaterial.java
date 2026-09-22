package com.wormzjl.createcheme.science.material;

import java.util.Objects;

/** Inert incompressible solid; sensible energy is zero at 298.15 K, with no formation offset. */
public record SolidMaterial(String id, String revision, double density, double heatCapacity) {
    public static final double REFERENCE_TEMPERATURE = 298.15;
    public SolidMaterial {
        Objects.requireNonNull(id); Objects.requireNonNull(revision);
        if (!id.matches("[a-z][a-z0-9_.-]*:[a-z0-9_./-]+") || id.length()>128 || revision.isBlank() || revision.length()>128
                || !Double.isFinite(density) || density <= 0 || !Double.isFinite(heatCapacity) || heatCapacity <= 0)
            throw new IllegalArgumentException("Invalid solid material");
    }
    public double specificInternalEnergy(double temperature) {
        if (!Double.isFinite(temperature) || temperature <= 0) throw new IllegalArgumentException("Invalid solid temperature");
        return heatCapacity * (temperature - REFERENCE_TEMPERATURE);
    }
    public double specificEnthalpy(double temperature, double pressure) {
        if (!Double.isFinite(pressure) || pressure <= 0) throw new IllegalArgumentException("Invalid solid pressure");
        return specificInternalEnergy(temperature) + pressure / density;
    }
}