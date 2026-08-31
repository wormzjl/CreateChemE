package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3Cdu17TiaJuanaPackageTest {
    @Test
    void publicAxisIsStableAndContainsOnlyTheSixteenV3Hydrocarbons() {
        V3Cdu17TiaJuanaPackage propertyPackage = V3Cdu17TiaJuanaPackage.INSTANCE;

        assertEquals(16, propertyPackage.componentBasis().componentCount());
        assertEquals("methane", propertyPackage.componentBasis().componentId(0));
        assertEquals("C4_CDU", propertyPackage.componentBasis().componentId(3));
        assertEquals("PC12", propertyPackage.componentBasis().componentId(15));
        assertEquals(0.0, propertyPackage.crudeFeed(V3Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions()[0]);
        assertEquals(1.0, java.util.Arrays.stream(
                propertyPackage.crudeFeed(V3Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions()).sum(), 1.0e-14);
    }

    @Test
    void zeroBinaryInteractionMatrixIsHydrocarbonOnlyAndDefensivelyCopied() {
        double[][] first = V3Cdu17TiaJuanaPackage.INSTANCE.binaryInteractions();
        assertEquals(16, first.length);
        for (double[] row : first) {
            assertEquals(16, row.length);
            assertArrayEquals(new double[16], row);
        }
        first[0][1] = 1.0;
        assertEquals(0.0, V3Cdu17TiaJuanaPackage.INSTANCE.binaryInteractions()[0][1]);
    }

    @Test
    void heavyResidueAndUnknownPackageContractsFailClosed() {
        V3PropertyComponent pc12 = V3Cdu17TiaJuanaPackage.INSTANCE.component(15);

        assertTrue(pc12.estimatedHeavyResidue());
        assertTrue(!pc12.vaporEligible());
        assertEquals(V3Cdu17TiaJuanaPackage.PC12_CRITICAL_TEMPERATURE_KELVIN, pc12.criticalTemperatureKelvin());
        assertEquals(V3Cdu17TiaJuanaPackage.PC12_CRITICAL_PRESSURE_PASCAL, pc12.criticalPressurePascal());
        assertEquals("ESTIMATED_HEAVY_RESIDUE", V3Cdu17TiaJuanaPackage.heavyResidueWarning(
                V3Cdu17TiaJuanaPackage.HEAVY_RESIDUE_WARNING_TEMPERATURE_KELVIN, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> V3PropertyPackageRegistry.require("createcheme:unsupported_property_package"));
    }

    @Test
    void characterizationUsesTheCommittedKeslerLeeVectorsAndDeclaredVaporEligibility() throws IOException {
        V3Cdu17TiaJuanaPackage propertyPackage = V3Cdu17TiaJuanaPackage.INSTANCE;
        List<V3CharacterizationVectors.Vector> vectors = V3CharacterizationVectors.load();
        double previousCriticalTemperature = 0.0;
        double previousCriticalPressure = Double.POSITIVE_INFINITY;

        for (int offset = 0; offset < vectors.size(); offset++) {
            V3CharacterizationVectors.Vector vector = vectors.get(offset);
            V3PropertyComponent component = propertyPackage.component(offset + 4);
            assertEquals(vector.id(), component.id());
            assertEquals(vector.criticalTemperatureKelvin(), component.criticalTemperatureKelvin(),
                    vector.criticalTemperatureKelvin() * 1.0e-9);
            assertEquals(vector.criticalPressurePascal(), component.criticalPressurePascal(),
                    vector.criticalPressurePascal() * 1.0e-9);
            assertEquals(vector.acentricFactor(), component.acentricFactor(), 1.0e-9);
            assertTrue(component.criticalTemperatureKelvin() > previousCriticalTemperature);
            assertTrue(component.criticalPressurePascal() < previousCriticalPressure);
            assertEquals(offset != vectors.size() - 1, component.vaporEligible());
            previousCriticalTemperature = component.criticalTemperatureKelvin();
            previousCriticalPressure = component.criticalPressurePascal();
        }

        assertEquals(V3CharacterizationVectors.OmegaBranch.VAPOR_PRESSURE, vectors.get(8).omegaBranch());
        assertEquals(V3CharacterizationVectors.OmegaBranch.WATSON_FACTOR, vectors.get(9).omegaBranch());
        assertEquals("cdu17-tjl-kl1976-r2", propertyPackage.datasetRevision());
        assertEquals(List.of("ESTIMATED_HEAVY_RESIDUE: PC12 is extrapolated and vapor-ineligible"),
                V3PengRobinsonThermo.fromRegisteredPackage(V3Cdu17TiaJuanaPackage.PACKAGE_ID).advisoryEvidence());
    }

    @Test
    void c4HiddenProvenanceClosesItsInternalSplit() {
        V3Cdu17TiaJuanaPackage.C4Metadata c4 = V3Cdu17TiaJuanaPackage.c4Metadata();
        assertEquals(1.0, c4.iButaneMoleFraction() + c4.nButaneMoleFraction(), 1e-10);
        assertEquals(0.0581222, c4.molecularWeightKgPerMol(), 1e-10);
    }

    @Test
    void pc12KeepsTheRequiredLastovkaShawCubicHeatCapacityTerm() {
        V3PropertyComponent pc12 = V3Cdu17TiaJuanaPackage.INSTANCE.component(15);
        double delta = 900.0 - 298.15;
        double expected = 916.8521805673 + 3.089066427611 * delta
                - 0.002591126843297 * delta * delta + 9.048103532950e-7 * delta * delta * delta;

        assertEquals(expected, pc12.idealGasHeatCapacity(900.0), 1e-9);
    }

    @Test
    void standardLiquidReconstructionClosesTheAcsDensityConstraint() {
        assertEquals(867.6, V3Cdu17TiaJuanaPackage.acsReconstructedBulkDensityKgPerCubicMetre(), 1e-9);
        assertEquals(867.6 * 662.464, V3Cdu17TiaJuanaPackage.acsReconstructedMassFlowKilogramsPerHour(), 1e-6);
    }
}
