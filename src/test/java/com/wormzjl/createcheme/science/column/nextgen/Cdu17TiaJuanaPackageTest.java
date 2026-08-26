package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Cdu17TiaJuanaPackageTest {
    @Test
    void publicAxisIsStableAndKeepsModelledZeroMethaneAndWater() {
        Cdu17TiaJuanaPackage pkg = Cdu17TiaJuanaPackage.INSTANCE;

        assertEquals(17, pkg.basis().components().size());
        assertEquals(16, pkg.basis().hydrocarbonCount());
        assertEquals("methane", pkg.basis().components().getFirst().id());
        assertEquals("C4_CDU", pkg.basis().components().get(3).id());
        assertEquals("PC12", pkg.basis().components().get(15).id());
        assertEquals("water", pkg.basis().components().get(16).id());
        assertEquals(0.0, pkg.feedForAssay(Cdu17TiaJuanaPackage.ASSAY_ID).moleFraction(0));
        assertEquals(0.0, pkg.feedForAssay(Cdu17TiaJuanaPackage.ASSAY_ID).moleFraction(16));
        assertEquals(1.0, java.util.Arrays.stream(pkg.feedForAssay(Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions()).sum(), 1e-14);
    }

    @Test
    void zeroBinaryInteractionMatrixIsHydrocarbonOnlyAndDefensivelyCopied() {
        double[][] first = Cdu17TiaJuanaPackage.INSTANCE.binaryInteractions();
        assertEquals(16, first.length);
        for (double[] row : first) {
            assertEquals(16, row.length);
            assertArrayEquals(new double[16], row);
        }
        first[0][1] = 1.0;
        assertEquals(0.0, Cdu17TiaJuanaPackage.INSTANCE.binaryInteractions()[0][1]);
    }

    @Test
    void heavyResidueAndUnknownMaterialFailClosedContractsAreExplicit() {
        ComponentDescriptor pc12 = Cdu17TiaJuanaPackage.INSTANCE.basis().components().get(15);
        assertTrue(pc12.estimatedHeavyResidue());
        assertEquals(Cdu17TiaJuanaPackage.PC12_CRITICAL_TEMPERATURE_KELVIN, pc12.criticalTemperatureKelvin());
        assertEquals(Cdu17TiaJuanaPackage.PC12_CRITICAL_PRESSURE_PASCAL, pc12.criticalPressurePascal());
        assertEquals("ESTIMATED_HEAVY_RESIDUE", Cdu17TiaJuanaPackage.heavyResidueWarning(899.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> ColumnModelRegistry.requireSupportedMaterial(Cdu17TiaJuanaPackage.PACKAGE_ID, "minecraft:lava"));
    }

    @Test
    void c4HiddenProvenanceClosesItsInternalSplit() {
        Cdu17TiaJuanaPackage.C4Metadata c4 = Cdu17TiaJuanaPackage.c4Metadata();
        assertEquals(1.0, c4.iButaneMoleFraction() + c4.nButaneMoleFraction(), 1e-10);
        assertEquals(0.0581222, c4.molecularWeightKgPerMol(), 1e-10);
    }

    @Test
    void pc12KeepsTheRequiredLastovkaShawCubicHeatCapacityTerm() {
        ComponentDescriptor pc12 = Cdu17TiaJuanaPackage.INSTANCE.basis().components().get(15);
        double delta = 900.0 - 298.15;
        double expected = 916.8521805673 + 3.089066427611 * delta
                - 0.002591126843297 * delta * delta + 9.048103532950e-7 * delta * delta * delta;

        assertEquals(expected, pc12.idealGasHeatCapacity(900.0), 1e-9);
    }

    @Test
    void componentBasisCanRepresentASeparateRegisteredAxisWithoutChangingCduOrder() {
        ComponentDescriptor hydrocarbon = new ComponentDescriptor(
                "test_hc", "Test HC", 0.050, 350.0, 500.0, 3_000_000.0, 0.2, 700.0,
                100.0, 0.0, 0.0, 0.0, true, false);
        ComponentDescriptor water = new ComponentDescriptor(
                "water", "Water", 0.018, 373.15, 647.096, 22_064_000.0, 0.344, 999.0,
                75.0, 0.0, 0.0, 0.0, false, false);
        ComponentBasis alternate = new ComponentBasis(java.util.List.of(hydrocarbon, water), 1, 1);

        assertEquals(java.util.List.of("test_hc", "water"), alternate.publicAxisIds());
        assertEquals("methane", Cdu17TiaJuanaPackage.INSTANCE.basis().publicAxisIds().getFirst());
    }

    @Test
    void standardLiquidReconstructionClosesTheAcsDensityConstraint() {
        assertEquals(867.6, Cdu17TiaJuanaPackage.acsReconstructedBulkDensityKgPerCubicMetre(), 1e-9);
        assertEquals(867.6 * 662.464, Cdu17TiaJuanaPackage.acsReconstructedMassFlowKilogramsPerHour(), 1e-6);
    }
}
