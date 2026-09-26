package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Critical properties required by the Peng-Robinson property package. */
public record ThermoComponent(
        String id,
        double criticalTemperatureKelvin,
        double criticalPressurePascal,
        double acentricFactor,
        double molarMassKilogramPerMol) {

    public ThermoComponent {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (!positiveFinite(criticalTemperatureKelvin)
                || !positiveFinite(criticalPressurePascal)
                || !Double.isFinite(acentricFactor)
                || !positiveFinite(molarMassKilogramPerMol)) {
            throw new IllegalArgumentException("Thermodynamic component properties must be finite and physical");
        }
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0;
    }
}
