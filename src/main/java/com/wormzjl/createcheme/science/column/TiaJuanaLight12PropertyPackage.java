package com.wormzjl.createcheme.science.column;

import com.wormzjl.createcheme.science.thermo.IdealGasHeatCapacity;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PengRobinsonCaloricModel;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.ArrayList;
import java.util.List;

/** Tia Juana Light 12-cut property package used by the first thermodynamic column model. */
public final class TiaJuanaLight12PropertyPackage {
    public static final String ASSAY_ID = "createcheme:tia_juana_light_12";
    public static final String DATASET_REVISION = "proxy:" + ASSAY_ID + "@v1";

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
    private static final PengRobinson78 EQUATION_OF_STATE = createEquationOfState();
    private static final PengRobinsonCaloricModel CALORIC_MODEL =
            new PengRobinsonCaloricModel(EQUATION_OF_STATE, createHeatCapacities());

    private TiaJuanaLight12PropertyPackage() {}

    public static PengRobinson78 equationOfState() {
        return EQUATION_OF_STATE;
    }

    public static PengRobinsonCaloricModel caloricModel() {
        return CALORIC_MODEL;
    }

    public static int componentCount() {
        return ASSAY_MOLE_FRACTIONS.length;
    }

    public static double[] feedMoleFractions() {
        return normalized(ASSAY_MOLE_FRACTIONS);
    }

    public static double molarMassKilogramPerMol(int component) {
        return MOLAR_MASSES_KILOGRAM_PER_MOL[component];
    }

    public static double normalBoilingPointKelvin(int component) {
        return NORMAL_BOILING_POINTS_KELVIN[component];
    }

    private static PengRobinson78 createEquationOfState() {
        int count = NORMAL_BOILING_POINTS_KELVIN.length;
        List<ThermoComponent> components = new ArrayList<>(count);
        double[][] interactions = new double[count][count];
        for (int i = 0; i < count; i++) {
            double cutPosition = i / (double) (count - 1);
            components.add(new ThermoComponent(
                    "tia_juana_light_cut_" + (i + 1),
                    NORMAL_BOILING_POINTS_KELVIN[i] * (1.48 - 0.13 * cutPosition),
                    4_600_000.0 * Math.exp(-1.28 * cutPosition),
                    0.10 + 0.72 * cutPosition,
                    MOLAR_MASSES_KILOGRAM_PER_MOL[i]));
            for (int j = 0; j < count; j++) {
                interactions[i][j] = i == j ? 0.0 : 0.012 * Math.abs(i - j) / count;
            }
        }
        return new PengRobinson78(components, interactions);
    }

    private static List<IdealGasHeatCapacity> createHeatCapacities() {
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
        return List.copyOf(heatCapacities);
    }

    private static double[] normalized(double[] values) {
        double[] result = values.clone();
        double sum = 0.0;
        for (double value : result) {
            sum += value;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] /= sum;
        }
        return result;
    }
}
