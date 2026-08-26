package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class V3PengRobinsonThermoTest {
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    private static final String ASSAY_ID = "createcheme:tia_juana_light";

    @Test
    void registeredSampleCrudeHasTheDeclaredSixteenHydrocarbonBasisAndDefensiveAssayVector() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
        double[] copy = crude.moleFractions();
        copy[0] = 1.0;

        assertEquals(PACKAGE_ID, thermo.packageId());
        assertEquals("cdu17-tjl-acs2018-r1", thermo.datasetRevision());
        assertEquals(16, thermo.componentBasis().componentCount());
        assertEquals("methane", thermo.componentBasis().componentId(0));
        assertEquals("PC12", thermo.componentBasis().componentId(15));
        assertEquals(1.0, sum(crude.moleFractions()), 1.0e-12);
        assertTrue(crude.moleFractions()[0] < 1.0);
    }

    @Test
    void sampleCrudeFlashesAtTheDeclaredColumnFeedConditionAsTwoPhase() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
        V3ThermoWorkspace workspace = thermo.newWorkspace();

        V3FlashResult result = thermo.flashTP(638.15, 267_250.0, crude.moleFractions(), workspace);

        assertEquals(V3FeedPhase.TWO_PHASE, result.phase(), result::detail);
        assertTrue(result.vaporFraction() > 0.0 && result.vaporFraction() < 1.0);
        assertEquals(1.0, sum(result.liquidComposition()), 1.0e-12);
        assertEquals(1.0, sum(result.vaporComposition()), 1.0e-12);
        assertTrue(Double.isFinite(result.molarEnthalpyJoulesPerMol()));
        for (int component = 0; component < crude.moleFractions().length; component++) {
            assertEquals(crude.moleFractions()[component], (1.0 - result.vaporFraction())
                    * result.liquidComposition()[component] + result.vaporFraction() * result.vaporComposition()[component],
                    1.0e-9);
        }
    }

    @Test
    void sampleCrudeTreatsBothSinglePhaseEndpointsAsNormalOutcomes() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);

        V3FlashResult liquid = thermo.flashTP(298.15, 250_000.0, crude.moleFractions(), thermo.newWorkspace());
        V3FlashResult vapor = thermo.flashTP(900.0, 50_000.0, crude.moleFractions(), thermo.newWorkspace());

        assertEquals(V3FeedPhase.LIQUID, liquid.phase(), liquid::detail);
        assertEquals(0.0, liquid.vaporFraction());
        assertEquals(16, liquid.liquidComposition().length);
        assertEquals(0, liquid.vaporComposition().length);
        assertEquals(V3FeedPhase.VAPOR, vapor.phase(), vapor::detail);
        assertEquals(1.0, vapor.vaporFraction());
        assertEquals(0, vapor.liquidComposition().length);
        assertEquals(16, vapor.vaporComposition().length);
    }

    @Test
    void fugacityAndEnthalpyUseTheSamePhaseEvaluationAndRejectOutOfDomainRequests() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3CrudeFeed crude = thermo.crudeFeed(ASSAY_ID);
        V3ThermoWorkspace workspace = thermo.newWorkspace();

        V3FugacityResult fugacity = thermo.fugacity(500.0, 250_000.0, crude.moleFractions(), V3Phase.LIQUID, workspace);
        double enthalpy = thermo.molarEnthalpy(500.0, 250_000.0, crude.moleFractions(), V3Phase.LIQUID, workspace);
        double[] copy = fugacity.logFugacityCoefficients();
        copy[0] = Double.NaN;

        assertEquals(enthalpy, fugacity.molarEnthalpyJoulesPerMol(), 1.0e-9);
        assertTrue(Arrays.stream(fugacity.logFugacityCoefficients()).allMatch(Double::isFinite));
        assertThrows(V3ThermoException.class,
                () -> thermo.flashTP(901.0, 250_000.0, crude.moleFractions(), thermo.newWorkspace()));
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }
}
