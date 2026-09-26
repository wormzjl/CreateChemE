package com.wormzjl.createcheme.science.material;

import java.util.List;
import java.util.Objects;

/** Immutable pure-phase dynamic-viscosity correlation; parameters and domains are supplied by a data pack. */
public record ViscosityCorrelation(Model model, double minimumTemperatureKelvin, double maximumTemperatureKelvin,
        double minimumPressurePascal, double maximumPressurePascal, double referenceTemperatureKelvin,
        List<Double> coefficients, List<Double> exponents, String revision, String source, boolean estimated,
        List<Double> temperaturesKelvin) {
    public enum Phase { LIQUID, VAPOR }
    public enum Model { ANDRADE, SUTHERLAND, POWER_SUM, LOG_TABLE }

    public ViscosityCorrelation(Model model, double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal, double referenceTemperatureKelvin,
            List<Double> coefficients, List<Double> exponents, String revision, String source, boolean estimated) {
        this(model,minimumTemperatureKelvin,maximumTemperatureKelvin,minimumPressurePascal,maximumPressurePascal,
                referenceTemperatureKelvin,coefficients,exponents,revision,source,estimated,List.of());
    }

    public ViscosityCorrelation {
        Objects.requireNonNull(model, "model");
        coefficients = List.copyOf(coefficients);
        exponents = List.copyOf(exponents);
        temperaturesKelvin = List.copyOf(temperaturesKelvin);
        requirePositive(minimumTemperatureKelvin, "temperature_min_kelvin");
        requirePositive(maximumTemperatureKelvin, "temperature_max_kelvin");
        requirePositive(minimumPressurePascal, "pressure_min_pascal");
        requirePositive(maximumPressurePascal, "pressure_max_pascal");
        requirePositive(referenceTemperatureKelvin, "reference_temperature_kelvin");
        if (maximumTemperatureKelvin <= minimumTemperatureKelvin || maximumPressurePascal < minimumPressurePascal)
            throw new IllegalArgumentException("viscosity: invalid temperature or pressure interval");
        if (revision == null || revision.isBlank() || revision.length() > 128
                || source == null || source.isBlank() || source.length() > 2048)
            throw new IllegalArgumentException("viscosity: revision and source are required");
        if (coefficients.isEmpty() || coefficients.size() > (model == Model.LOG_TABLE ? 2048 : 16)
                || coefficients.stream().anyMatch(v -> !Double.isFinite(v))
                || exponents.stream().anyMatch(v -> !Double.isFinite(v)))
            throw new IllegalArgumentException("viscosity: coefficients/exponents must be finite and bounded");
        if (model == Model.LOG_TABLE) {
            if (coefficients.size() < 2 || temperaturesKelvin.size() != coefficients.size() || !exponents.isEmpty()
                    || temperaturesKelvin.getFirst() != minimumTemperatureKelvin || temperaturesKelvin.getLast() != maximumTemperatureKelvin)
                throw new IllegalArgumentException("viscosity.log_table: temperature nodes must span the declared domain and match values");
            for (int i = 0; i < coefficients.size(); i++) {
                requirePositive(coefficients.get(i), "viscosity.log_table value");
                requirePositive(temperaturesKelvin.get(i), "viscosity.log_table temperature");
                if (i > 0 && temperaturesKelvin.get(i) <= temperaturesKelvin.get(i - 1))
                    throw new IllegalArgumentException("viscosity.log_table: temperatures must be strictly increasing");
            }
        } else if (!temperaturesKelvin.isEmpty()) {
            throw new IllegalArgumentException("viscosity: temperature nodes are only valid for log_table");
        } else if (model == Model.POWER_SUM) {
            if (exponents.size() != coefficients.size() || coefficients.stream().anyMatch(v -> v <= 0))
                throw new IllegalArgumentException("viscosity.power_sum: positive coefficients and matching exponents required");
        } else {
            if (coefficients.size() != 2 || !exponents.isEmpty() || coefficients.getFirst() <= 0)
                throw new IllegalArgumentException("viscosity: expected two coefficients, positive scale, and no exponents");
            if (model == Model.SUTHERLAND && (coefficients.get(1) < 0
                    || referenceTemperatureKelvin < minimumTemperatureKelvin || referenceTemperatureKelvin > maximumTemperatureKelvin))
                throw new IllegalArgumentException("viscosity.sutherland: S must be nonnegative and reference temperature in range");
        }
        // Positive power sums are convex as functions of ln(T); their maximum is at an endpoint.
        // Andrade and the admitted Sutherland form are monotone. Reject overflow before publication.
        requirePositive(evaluate(model, minimumTemperatureKelvin, referenceTemperatureKelvin, coefficients, exponents, temperaturesKelvin), "viscosity at minimum temperature");
        requirePositive(evaluate(model, maximumTemperatureKelvin, referenceTemperatureKelvin, coefficients, exponents, temperaturesKelvin), "viscosity at maximum temperature");
    }

    /** Returns Pa·s. Pressure selects the validated interval; these models have no pressure correction. */
    public double dynamicViscosityPascalSeconds(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < minimumTemperatureKelvin || temperatureKelvin > maximumTemperatureKelvin
                || !Double.isFinite(pressurePascal) || pressurePascal < minimumPressurePascal || pressurePascal > maximumPressurePascal)
            throw new IllegalArgumentException("Viscosity state is outside the correlation's temperature/pressure domain");
        double value = evaluate(model, temperatureKelvin, referenceTemperatureKelvin, coefficients, exponents, temperaturesKelvin);
        requirePositive(value, "dynamic viscosity");
        return value;
    }

    /** Returns m²/s using the supplied density at the same state, never the catalog's standard-liquid density. */
    public double kinematicViscositySquareMetresPerSecond(double temperatureKelvin, double pressurePascal, double densityKgPerCubicMetre) {
        requirePositive(densityKgPerCubicMetre, "densityKgPerCubicMetre");
        double value = dynamicViscosityPascalSeconds(temperatureKelvin, pressurePascal) / densityKgPerCubicMetre;
        requirePositive(value, "kinematic viscosity");
        return value;
    }

    private static double evaluate(Model model, double t, double reference, List<Double> c, List<Double> powers, List<Double> nodes) {
        return switch (model) {
            case ANDRADE -> Math.exp(Math.log(c.get(0)) + c.get(1) / t);
            case SUTHERLAND -> c.get(0) * Math.pow(t / reference, 1.5) * (reference + c.get(1)) / (t + c.get(1));
            case LOG_TABLE -> {
                int index = java.util.Collections.binarySearch(nodes, t);
                if (index >= 0) yield c.get(index);
                int high = -index - 1, low = high - 1;
                double fraction = (t - nodes.get(low)) / (nodes.get(high) - nodes.get(low));
                yield Math.exp(Math.log(c.get(low)) + fraction * (Math.log(c.get(high)) - Math.log(c.get(low))));
            }
            case POWER_SUM -> {
                double total = 0;
                for (int i = 0; i < c.size(); i++) total += c.get(i) * Math.pow(t / reference, powers.get(i));
                yield total;
            }
        };
    }

    private static void requirePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + ": must be finite and positive");
    }
}
