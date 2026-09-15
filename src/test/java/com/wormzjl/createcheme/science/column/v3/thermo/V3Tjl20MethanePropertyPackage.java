package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.ArrayList;
import java.util.List;

/** Methane extension of the frozen TJL19 reconstruction; see tools/neural/methane-qualification.md. */
final class V3Tjl20MethanePropertyPackage implements V3PropertyPackage {
    static final String PACKAGE_ID = "createcheme:tjl20_methane";
    static final String DATASET_REVISION = "tjl20-methane-nist-r1";
    static final String ASSAY_ID = "createcheme:tia_juana_light_methane";
    static final double METHANE_MOLE_FRACTION = 0.005;
    private static final V3PropertyPackage BASE = V3Tjl19PropertyPackage.INSTANCE;
    // PR constants: CoolProp methane (Setzmann-Wagner). Cp: degree-five fit to NIST Shomate,
    // 298.15..900 K, J/(mol K), polynomial in T-298.15; H uses its analytic integral.
    // Density is the legacy CDU hypothetical-liquid convention, unused by this molar assay/PR solver.
    private static final V3PropertyComponent METHANE = new V3PropertyComponent(
            "Methane", "Methane", 0.0160428, 111.66, 190.564, 4_599_200.0, 0.01142, 356.07,
            35.605746481777615, 0.037000414354219455, 0.0001459299391720176,
            -4.216765855568231e-7, 5.351033897511078e-10, -2.715203964835217e-13, false);
    private static final V3ComponentBasis BASIS = methaneBasis();
    private static final V3CrudeFeed FEED = methaneFeed();
    static final V3Tjl20MethanePropertyPackage INSTANCE = new V3Tjl20MethanePropertyPackage();

    private V3Tjl20MethanePropertyPackage() {}

    private static V3ComponentBasis methaneBasis() {
        List<String> ids = new ArrayList<>();
        ids.add(METHANE.id());
        ids.addAll(BASE.componentBasis().componentIds());
        return new V3ComponentBasis(ids);
    }

    private static V3CrudeFeed methaneFeed() {
        double[] original = BASE.crudeFeed(V3Tjl19PropertyPackage.ASSAY_ID).moleFractions();
        double[] fractions = new double[original.length + 1];
        fractions[0] = METHANE_MOLE_FRACTION;
        for (int index = 0; index < original.length; index++) {
            fractions[index + 1] = (1.0 - METHANE_MOLE_FRACTION) * original[index];
        }
        return new V3CrudeFeed(PACKAGE_ID, ASSAY_ID, BASIS, fractions);
    }

    @Override public String packageId() { return PACKAGE_ID; }
    @Override public String datasetRevision() { return DATASET_REVISION; }
    @Override public V3ComponentBasis componentBasis() { return BASIS; }
    @Override public V3PropertyComponent component(int index) {
        return index == 0 ? METHANE : BASE.component(index - 1);
    }
    @Override public double minimumTemperatureKelvin() { return BASE.minimumTemperatureKelvin(); }
    @Override public double maximumTemperatureKelvin() { return BASE.maximumTemperatureKelvin(); }
    @Override public double minimumPressurePascal() { return BASE.minimumPressurePascal(); }
    @Override public double maximumPressurePascal() { return BASE.maximumPressurePascal(); }
    @Override public V3CrudeFeed crudeFeed(String assayId) {
        if (!ASSAY_ID.equals(assayId)) throw new IllegalArgumentException("Unsupported TJL20 assay: " + assayId);
        return FEED;
    }
    @Override public double[][] binaryInteractions() {
        double[][] original = BASE.binaryInteractions();
        double[][] extended = new double[original.length + 1][original.length + 1];
        for (int row = 0; row < original.length; row++) {
            System.arraycopy(original[row], 0, extended[row + 1], 1, original.length);
        }
        return extended;
    }
    @Override public List<String> advisoryEvidence() {
        List<String> evidence = new ArrayList<>(BASE.advisoryEvidence());
        evidence.add("METHANE_EXTENSION: NIST ideal-gas Cp fit; CoolProp PR constants; methane binary interactions assumed zero, not fitted");
        evidence.add("METHANE_ASSAY: synthetic 0.5 mol% methane blend; hypothetical liquid-density metadata is unused by the molar feed and PR equations");
        return List.copyOf(evidence);
    }
}
