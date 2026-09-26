package com.wormzjl.createcheme.science.column.v3;

/** One typed V3.0 specification; no product-flow or condenser-duty specification exists. */
public sealed interface V3ColumnSpecification permits V3ColumnSpecification.CondenserOutletTemperature,
        V3ColumnSpecification.OrganicRefluxRatio, V3ColumnSpecification.ReboilerDuty {
    V3ControlledQuantity controlledQuantity();

    /** Specified partial-condenser outlet temperature in kelvin. */
    record CondenserOutletTemperature(double kelvin) implements V3ColumnSpecification {
        public CondenserOutletTemperature {
            requirePositiveFinite(kelvin, "Condenser outlet temperature");
        }

        @Override
        public V3ControlledQuantity controlledQuantity() {
            return V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE;
        }
    }

    /** Organic liquid reflux divided by calculated condensed organic liquid. */
    record OrganicRefluxRatio(double ratio) implements V3ColumnSpecification {
        public OrganicRefluxRatio {
            requireNonNegativeFinite(ratio, "Organic reflux ratio");
        }

        @Override
        public V3ControlledQuantity controlledQuantity() {
            return V3ControlledQuantity.ORGANIC_REFLUX_RATIO;
        }
    }

    /** Specified heat input to the partial reboiler in watts. */
    record ReboilerDuty(double watts) implements V3ColumnSpecification {
        public ReboilerDuty {
            requireNonNegativeFinite(watts, "Reboiler duty");
        }

        @Override
        public V3ControlledQuantity controlledQuantity() {
            return V3ControlledQuantity.REBOILER_DUTY;
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }
}
