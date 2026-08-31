package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Independent Kesler-Lee and Twu transcription checks for the committed characterization vectors. */
class V3CharacterizationOracleTest {
    private static final double RANKINE_PER_KELVIN = 1.8;
    private static final double PASCAL_PER_PSIA = 6_894.757;

    @Test
    void keslerLeeReproducesCommittedGoldenVectorsWithoutIntermediateRounding() throws IOException {
        for (V3CharacterizationVectors.Vector vector : V3CharacterizationVectors.load()) {
            double boilingPointRankine = vector.normalBoilingPointKelvin() * RANKINE_PER_KELVIN;
            double criticalTemperatureRankine = keslerLeeCriticalTemperatureRankine(
                    boilingPointRankine, vector.specificGravity());
            double criticalPressurePascal = keslerLeeCriticalPressurePsia(
                    boilingPointRankine, vector.specificGravity()) * PASCAL_PER_PSIA;

            assertRelative(vector.criticalTemperatureKelvin(), criticalTemperatureRankine / RANKINE_PER_KELVIN,
                    1.0e-9, vector.id() + " critical temperature");
            assertRelative(vector.criticalPressurePascal(), criticalPressurePascal, 1.0e-9,
                    vector.id() + " critical pressure");
            assertEquals(vector.acentricFactor(), keslerLeeAcentricFactor(
                    boilingPointRankine, vector.specificGravity(), criticalTemperatureRankine), 1.0e-9,
                    vector.id() + " acentric factor");
        }
    }

    @Test
    void twuCrossCheckReproducesItsNHexaneTranscriptionReference() {
        TwuResult hexane = twu(341.88 * RANKINE_PER_KELVIN, 0.6640);

        assertRelative(507.60, hexane.criticalTemperatureRankine() / RANKINE_PER_KELVIN, 0.02,
                "Twu n-hexane critical temperature");
        assertRelative(3_025_000.0, hexane.criticalPressurePsia() * PASCAL_PER_PSIA, 0.02,
                "Twu n-hexane critical pressure");
        assertEquals(0.6648, reference(341.88 * RANKINE_PER_KELVIN).specificGravity(), 0.001);
    }

    static double keslerLeeCriticalTemperatureRankine(double boilingPointRankine, double specificGravity) {
        return 341.7 + 811.0 * specificGravity + (0.4244 + 0.1174 * specificGravity) * boilingPointRankine
                + (0.4669 - 3.2623 * specificGravity) * 1.0e5 / boilingPointRankine;
    }

    static double keslerLeeCriticalPressurePsia(double boilingPointRankine, double specificGravity) {
        double inverseSpecificGravity = 1.0 / specificGravity;
        double logarithm = 8.3634 - 0.0566 * inverseSpecificGravity
                - (0.24244 + 2.2898 * inverseSpecificGravity + 0.11857 * inverseSpecificGravity * inverseSpecificGravity)
                        * 1.0e-3 * boilingPointRankine
                + (1.4685 + 3.648 * inverseSpecificGravity + 0.47227 * inverseSpecificGravity * inverseSpecificGravity)
                        * 1.0e-7 * boilingPointRankine * boilingPointRankine
                - (0.42019 + 1.6977 * inverseSpecificGravity * inverseSpecificGravity)
                        * 1.0e-10 * boilingPointRankine * boilingPointRankine * boilingPointRankine;
        return Math.exp(logarithm);
    }

    static double keslerLeeAcentricFactor(
            double boilingPointRankine, double specificGravity, double criticalTemperatureRankine) {
        double reducedBoilingTemperature = boilingPointRankine / criticalTemperatureRankine;
        double watsonFactor = Math.cbrt(boilingPointRankine) / specificGravity;
        if (reducedBoilingTemperature > 0.8) {
            return -7.904 + 0.1352 * watsonFactor - 0.007465 * watsonFactor * watsonFactor
                    + 8.359 * reducedBoilingTemperature + (1.408 - 0.01063 * watsonFactor) / reducedBoilingTemperature;
        }
        double criticalPressureAtmospheres = keslerLeeCriticalPressurePsia(boilingPointRankine, specificGravity) / 14.6959;
        double numerator = -Math.log(criticalPressureAtmospheres) - 5.92714 + 6.09648 / reducedBoilingTemperature
                + 1.28862 * Math.log(reducedBoilingTemperature) - 0.169347 * Math.pow(reducedBoilingTemperature, 6);
        double denominator = 15.2518 - 15.6875 / reducedBoilingTemperature
                - 13.4721 * Math.log(reducedBoilingTemperature) + 0.43577 * Math.pow(reducedBoilingTemperature, 6);
        return numerator / denominator;
    }

    private static TwuResult twu(double boilingPointRankine, double specificGravity) {
        Reference reference = reference(boilingPointRankine);
        double rootBoilingPoint = Math.sqrt(boilingPointRankine);
        double densityTemperature = Math.exp(5.0 * (reference.specificGravity() - specificGravity)) - 1.0;
        double temperatureFactor = densityTemperature * (-0.27016 / rootBoilingPoint
                + (0.0398285 - 0.706691 / rootBoilingPoint) * densityTemperature);
        double criticalTemperature = reference.criticalTemperatureRankine()
                * Math.pow((1.0 + 2.0 * temperatureFactor) / (1.0 - 2.0 * temperatureFactor), 2);
        double densityVolume = Math.exp(4.0 * (reference.specificGravity() * reference.specificGravity()
                - specificGravity * specificGravity)) - 1.0;
        double volumeFactor = densityVolume * (0.347776 / rootBoilingPoint
                + (-0.182421 + 2.248896 / rootBoilingPoint) * densityVolume);
        double criticalVolume = reference.criticalVolumeCubicFeetPerLbmol()
                * Math.pow((1.0 + 2.0 * volumeFactor) / (1.0 - 2.0 * volumeFactor), 2);
        double densityPressure = Math.exp(0.5 * (reference.specificGravity() - specificGravity)) - 1.0;
        double pressureFactor = densityPressure * ((2.53262 - 46.1955 / rootBoilingPoint - 0.00127885 * boilingPointRankine)
                + (-11.4277 + 252.140 / rootBoilingPoint + 0.00230535 * boilingPointRankine) * densityPressure);
        double criticalPressure = reference.criticalPressurePsia()
                * (criticalTemperature / reference.criticalTemperatureRankine())
                * (reference.criticalVolumeCubicFeetPerLbmol() / criticalVolume)
                * Math.pow((1.0 + 2.0 * pressureFactor) / (1.0 - 2.0 * pressureFactor), 2);
        return new TwuResult(criticalTemperature, criticalPressure);
    }

    private static Reference reference(double boilingPointRankine) {
        double criticalTemperature = boilingPointRankine / (0.533272 + 0.191017e-3 * boilingPointRankine
                + 0.779681e-7 * boilingPointRankine * boilingPointRankine
                - 0.284376e-10 * boilingPointRankine * boilingPointRankine * boilingPointRankine
                + 0.959468e28 / Math.pow(boilingPointRankine, 13));
        double alpha = 1.0 - boilingPointRankine / criticalTemperature;
        double criticalPressure = Math.pow(3.83354 + 1.19629 * Math.sqrt(alpha) + 34.8888 * alpha
                + 36.1952 * alpha * alpha + 104.193 * Math.pow(alpha, 4), 2);
        double criticalVolume = Math.pow(1.0 - (0.419869 - 0.505839 * alpha - 1.56436 * Math.pow(alpha, 3)
                - 9481.70 * Math.pow(alpha, 14)), -8);
        double specificGravity = 0.843593 - 0.128624 * alpha - 3.36159 * Math.pow(alpha, 3)
                - 13749.5 * Math.pow(alpha, 12);
        return new Reference(criticalTemperature, criticalPressure, criticalVolume, specificGravity);
    }

    private static void assertRelative(double expected, double actual, double tolerance, String label) {
        assertEquals(expected, actual, Math.abs(expected) * tolerance, label);
    }

    private record Reference(
            double criticalTemperatureRankine, double criticalPressurePsia, double criticalVolumeCubicFeetPerLbmol,
            double specificGravity) {}

    private record TwuResult(double criticalTemperatureRankine, double criticalPressurePsia) {}
}

/** Tiny strict reader for the committed, test-only vector resource; production never recomputes this dataset. */
final class V3CharacterizationVectors {
    private static final String RESOURCE = "/com/wormzjl/createcheme/science/column/v3/thermo/characterization-kesler-lee.json";
    private static final Pattern VECTOR = Pattern.compile(
            "\\{\\\"id\\\":\\\"(PC\\d{2})\\\",\\\"normalBoilingPointKelvin\\\":([^,]+),\\\"specificGravity\\\":([^,]+),"
                    + "\\\"criticalTemperatureKelvin\\\":([^,]+),\\\"criticalPressurePascal\\\":([^,]+),"
                    + "\\\"acentricFactor\\\":([^,]+),\\\"omegaBranch\\\":\\\"([A-Z_]+)\\\"\\}");

    private V3CharacterizationVectors() {}

    static List<Vector> load() throws IOException {
        try (InputStream stream = V3CharacterizationVectors.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing characterization vector resource: " + RESOURCE);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = VECTOR.matcher(json);
            List<Vector> vectors = new ArrayList<>();
            while (matcher.find()) {
                vectors.add(new Vector(matcher.group(1), Double.parseDouble(matcher.group(2)),
                        Double.parseDouble(matcher.group(3)), Double.parseDouble(matcher.group(4)),
                        Double.parseDouble(matcher.group(5)), Double.parseDouble(matcher.group(6)),
                        OmegaBranch.valueOf(matcher.group(7))));
            }
            if (vectors.size() != 12) throw new IOException("Expected 12 Kesler-Lee characterization vectors");
            return List.copyOf(vectors);
        }
    }

    enum OmegaBranch { VAPOR_PRESSURE, WATSON_FACTOR }

    record Vector(
            String id, double normalBoilingPointKelvin, double specificGravity, double criticalTemperatureKelvin,
            double criticalPressurePascal, double acentricFactor, OmegaBranch omegaBranch) {}
}
