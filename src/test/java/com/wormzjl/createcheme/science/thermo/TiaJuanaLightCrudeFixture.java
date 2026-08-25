package com.wormzjl.createcheme.science.thermo;

import java.util.ArrayList;
import java.util.List;

/**
 * Numerical fixture for the Tia Juana Light 12-cut crude assay.
 *
 * <p>Fractions, normal boiling points, and molar masses come from the compatibility assay. Critical
 * and heat-capacity properties are explicit gameplay proxies used only to exercise the numerical
 * kernel; they are not a validated petroleum characterization.
 */
final class TiaJuanaLightCrudeFixture {
    private static final double[] NORMAL_BOILING_POINTS_KELVIN = {
        295.45, 348.65, 384.15, 422.15, 460.15, 496.95,
        548.05, 598.85, 648.95, 705.75, 809.45, 992.25
    };
    private static final double[] MOLAR_MASSES_KILOGRAM_PER_MOL = {
        0.060, 0.085, 0.100, 0.120, 0.145, 0.175,
        0.210, 0.250, 0.300, 0.370, 0.480, 0.650
    };
    private static final double[] ASSAY_MOLE_FRACTIONS = {
        0.0834, 0.1207, 0.0673, 0.1299, 0.0637, 0.1136,
        0.0933, 0.0795, 0.0686, 0.0648, 0.0634, 0.0514
    };

    private TiaJuanaLightCrudeFixture() {}

    static PengRobinson78 equationOfState() {
        return new PengRobinson78(components(), binaryInteractions());
    }

    static PengRobinsonCaloricModel caloricModel(PengRobinson78 equationOfState) {
        return new PengRobinsonCaloricModel(equationOfState, heatCapacities());
    }

    static double[] feedMoleFractions() {
        double[] fractions = ASSAY_MOLE_FRACTIONS.clone();
        double sum = 0.0;
        for (double fraction : fractions) {
            sum += fraction;
        }
        for (int i = 0; i < fractions.length; i++) {
            fractions[i] /= sum;
        }
        return fractions;
    }

    private static List<ThermoComponent> components() {
        List<ThermoComponent> components = new ArrayList<>(NORMAL_BOILING_POINTS_KELVIN.length);
        for (int i = 0; i < NORMAL_BOILING_POINTS_KELVIN.length; i++) {
            double cutPosition = i / (double) (NORMAL_BOILING_POINTS_KELVIN.length - 1);
            components.add(new ThermoComponent(
                    "tia_juana_light_cut_" + (i + 1),
                    NORMAL_BOILING_POINTS_KELVIN[i] * (1.48 - 0.13 * cutPosition),
                    4_600_000.0 * Math.exp(-1.28 * cutPosition),
                    0.10 + 0.72 * cutPosition,
                    MOLAR_MASSES_KILOGRAM_PER_MOL[i]));
        }
        return components;
    }

    private static List<IdealGasHeatCapacity> heatCapacities() {
        List<IdealGasHeatCapacity> heatCapacities =
                new ArrayList<>(NORMAL_BOILING_POINTS_KELVIN.length);
        for (int i = 0; i < NORMAL_BOILING_POINTS_KELVIN.length; i++) {
            double cutPosition = i / (double) (NORMAL_BOILING_POINTS_KELVIN.length - 1);
            heatCapacities.add(new IdealGasHeatCapacity(
                    298.15,
                    0.0,
                    80.0 + 330.0 * cutPosition,
                    0.10 + 0.22 * cutPosition,
                    0.0,
                    0.0));
        }
        return heatCapacities;
    }

    private static double[][] binaryInteractions() {
        int count = NORMAL_BOILING_POINTS_KELVIN.length;
        double[][] interactions = new double[count][count];
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                interactions[i][j] = i == j ? 0.0 : 0.012 * Math.abs(i - j) / count;
            }
        }
        return interactions;
    }
}
