package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.List;

/**
 * Revisioned Tia Juana Light hydrocarbon package used by V3. Values are compiled SI constants from the local
 * characterization contract; runtime code never reshapes the public hydrocarbon axis.
 */
final class V3Cdu17TiaJuanaPackage implements V3PropertyPackage {
    static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    static final String DATASET_REVISION = "cdu17-tjl-acs2018-r1";
    static final String ASSAY_ID = "createcheme:tia_juana_light";
    static final V3Cdu17TiaJuanaPackage INSTANCE = new V3Cdu17TiaJuanaPackage();
    static final double STANDARD_TEMPERATURE_KELVIN = 288.7055555556;
    static final double STANDARD_PRESSURE_PASCAL = 101_325.0;
    static final double ACS_BULK_DENSITY_KG_PER_CUBIC_METRE = 867.6;
    static final double ACS_STANDARD_VOLUME_CUBIC_METRES_PER_HOUR = 662.464;
    static final double PC12_NBP_KELVIN = 1_035.975246548323;
    static final double PC12_MOLECULAR_WEIGHT_KG_PER_MOL = 0.650;
    static final double PC12_CRITICAL_TEMPERATURE_KELVIN = 1_400.0;
    static final double PC12_CRITICAL_PRESSURE_PASCAL = 1_280_000.0;
    static final double PC12_ACENTRIC_FACTOR = 0.82;
    /** Final positive density-fit correction after the real C2-C4 densities are held fixed. */
    static final double PSEUDOCOMPONENT_DENSITY_CLOSURE_SCALE = 0.999091096679249;

    private static final List<V3PropertyComponent> COMPONENTS = List.of(
            descriptor("methane", "Methane", 0.016043, 111.66, 190.564, 4_599_200, 0.01142, 356.07, 35.69, 0.050, 0.0, false),
            descriptor("ethane", "Ethane", 0.030070, 184.55, 305.32, 4_872_000, 0.0995, 356.073, 52.49, 0.120, 0.0, false),
            descriptor("propane", "Propane", 0.044097, 231.05, 369.83, 4_248_000, 0.1523, 506.881, 73.60, 0.165, 0.0, false),
            descriptor("C4_CDU", "C4 CDU lump", 0.0581222, 269.59, 424.10, 3_800_000, 0.2000, 578.583, 96.00, 0.210, 0.0, false),
            descriptor("PC01", "PC01", 0.0860, 315.82, 507.0, 3_780_000, 0.255, pseudocomponentDensity(728.921), 112.0, 0.245, 0.0, false),
            descriptor("PC02", "PC02", 0.1010, 345.55, 540.0, 3_420_000, 0.310, pseudocomponentDensity(749.0), 126.0, 0.270, 0.0, false),
            descriptor("PC03", "PC03", 0.1230, 383.49, 586.0, 3_060_000, 0.365, pseudocomponentDensity(769.0), 145.0, 0.295, 0.0, false),
            descriptor("PC04", "PC04", 0.1450, 419.35, 628.0, 2_720_000, 0.415, pseudocomponentDensity(789.0), 164.0, 0.320, 0.0, false),
            descriptor("PC05", "PC05", 0.1740, 456.08, 673.0, 2_420_000, 0.470, pseudocomponentDensity(809.0), 187.0, 0.345, 0.0, false),
            descriptor("PC06", "PC06", 0.2050, 504.50, 732.0, 2_120_000, 0.530, pseudocomponentDensity(829.0), 215.0, 0.370, 0.0, false),
            descriptor("PC07", "PC07", 0.2440, 553.53, 792.0, 1_850_000, 0.590, pseudocomponentDensity(850.0), 249.0, 0.395, 0.0, false),
            descriptor("PC08", "PC08", 0.2860, 602.38, 852.0, 1_640_000, 0.645, pseudocomponentDensity(871.0), 286.0, 0.420, 0.0, false),
            descriptor("PC09", "PC09", 0.3330, 650.99, 914.0, 1_470_000, 0.700, pseudocomponentDensity(892.0), 328.0, 0.445, 0.0, false),
            descriptor("PC10", "PC10", 0.3820, 709.04, 990.0, 1_340_000, 0.750, pseudocomponentDensity(914.0), 374.0, 0.470, 0.0, false),
            descriptor("PC11", "PC11", 0.4700, 823.04, 1_145.0, 1_230_000, 0.790, pseudocomponentDensity(958.974), 455.0, 0.500, 0.0, false),
            new V3PropertyComponent("PC12", "PC12 residue surrogate", PC12_MOLECULAR_WEIGHT_KG_PER_MOL,
                    PC12_NBP_KELVIN, PC12_CRITICAL_TEMPERATURE_KELVIN, PC12_CRITICAL_PRESSURE_PASCAL,
                    PC12_ACENTRIC_FACTOR, pseudocomponentDensity(1_010.0), 916.8521805673, 3.089066427611,
                    -0.002591126843297, 9.048103532950e-7, true));
    private static final V3ComponentBasis BASIS = new V3ComponentBasis(COMPONENTS.stream()
            .map(V3PropertyComponent::id).toList());
    private static final double[] STANDARD_VOLUME_PERCENT = {
            0.0, 0.04, 0.37, 1.16, 6.15, 3.37, 6.91, 3.85, 8.18, 8.23, 8.13, 8.05, 7.77, 9.82, 17.81, 10.14
    };
    private static final V3CrudeFeed FEED = feedFromStandardLiquidVolumes();
    private static final double[][] ZERO_BINARY_INTERACTIONS = new double[COMPONENTS.size()][COMPONENTS.size()];

    private V3Cdu17TiaJuanaPackage() {}

    @Override public String packageId() { return PACKAGE_ID; }
    @Override public String datasetRevision() { return DATASET_REVISION; }
    @Override public V3ComponentBasis componentBasis() { return BASIS; }
    @Override public V3PropertyComponent component(int publicComponent) { return COMPONENTS.get(publicComponent); }
    @Override public double minimumTemperatureKelvin() { return 298.15; }
    @Override public double maximumTemperatureKelvin() { return 900.0; }
    @Override public double minimumPressurePascal() { return 50_000.0; }
    @Override public double maximumPressurePascal() { return 2_000_000.0; }

    @Override
    public V3CrudeFeed crudeFeed(String assayId) {
        if (!ASSAY_ID.equals(assayId)) throw new IllegalArgumentException(
                "Assay " + assayId + " is unsupported by package " + PACKAGE_ID);
        return FEED;
    }

    @Override
    public double[][] binaryInteractions() {
        double[][] copy = new double[ZERO_BINARY_INTERACTIONS.length][];
        for (int index = 0; index < copy.length; index++) copy[index] = ZERO_BINARY_INTERACTIONS[index].clone();
        return copy;
    }

    static C4Metadata c4Metadata() {
        return new C4Metadata(0.2262432764, 0.7737567236, 0.0581222, 0.579162);
    }

    static String heavyResidueWarning(double temperatureKelvin, double vaporMoleFraction) {
        return temperatureKelvin >= 880.0 || vaporMoleFraction > 1.0e-6 ? "ESTIMATED_HEAVY_RESIDUE" : "";
    }

    /** Audit-only standard-liquid reconstruction; source volume rows are never used as PR mole fractions. */
    static double acsReconstructedMassFlowKilogramsPerHour() {
        double mass = 0.0;
        for (int index = 0; index < COMPONENTS.size(); index++) {
            double volume = ACS_STANDARD_VOLUME_CUBIC_METRES_PER_HOUR * STANDARD_VOLUME_PERCENT[index] / 99.98;
            mass += volume * COMPONENTS.get(index).standardLiquidDensityKgPerCubicMetre();
        }
        return mass;
    }

    static double acsReconstructedBulkDensityKgPerCubicMetre() {
        return acsReconstructedMassFlowKilogramsPerHour() / ACS_STANDARD_VOLUME_CUBIC_METRES_PER_HOUR;
    }

    private static V3PropertyComponent descriptor(
            String id, String label, double mw, double nbp, double tc, double pc, double omega, double density,
            double cpA, double cpB, double cpC, boolean residue) {
        return new V3PropertyComponent(id, label, mw, nbp, tc, pc, omega, density, cpA, cpB, cpC, 0.0, residue);
    }

    private static double pseudocomponentDensity(double unscaledDensityKgPerCubicMetre) {
        return unscaledDensityKgPerCubicMetre * PSEUDOCOMPONENT_DENSITY_CLOSURE_SCALE;
    }

    private static V3CrudeFeed feedFromStandardLiquidVolumes() {
        double[] moles = new double[COMPONENTS.size()];
        for (int index = 0; index < COMPONENTS.size(); index++) {
            V3PropertyComponent component = COMPONENTS.get(index);
            double volume = ACS_STANDARD_VOLUME_CUBIC_METRES_PER_HOUR * STANDARD_VOLUME_PERCENT[index] / 99.98;
            moles[index] = volume * component.standardLiquidDensityKgPerCubicMetre() / component.molecularWeightKgPerMol();
        }
        return new V3CrudeFeed(PACKAGE_ID, ASSAY_ID, BASIS, moles);
    }

    record C4Metadata(double iButaneMoleFraction, double nButaneMoleFraction,
                      double molecularWeightKgPerMol, double standardLiquidSpecificGravity) {}
}
