package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wormzjl.createcheme.science.column.nextgen.Cdu17TiaJuanaPackage;
import com.wormzjl.createcheme.science.column.nextgen.ComponentDescriptor;
import com.wormzjl.createcheme.science.column.nextgen.NextPengRobinsonKernel;
import org.junit.jupiter.api.Test;

/** Temporary extraction guard: proves V3 owns numerically identical property and EOS data before V2 is removed. */
class V3PropertySpineParityTest {
    @Test
    void rehomedPropertyPackageAndPengRobinsonKernelMatchTheV2Baseline() {
        Cdu17TiaJuanaPackage baseline = Cdu17TiaJuanaPackage.INSTANCE;
        V3Cdu17TiaJuanaPackage extracted = V3Cdu17TiaJuanaPackage.INSTANCE;

        assertEquals(baseline.packageId(), extracted.packageId());
        assertEquals(baseline.datasetRevision(), extracted.datasetRevision());
        assertEquals(baseline.basis().hydrocarbonCount(), extracted.componentBasis().componentCount());
        for (int component = 0; component < extracted.componentBasis().componentCount(); component++) {
            ComponentDescriptor oldComponent = baseline.basis().hydrocarbon(component);
            V3PropertyComponent newComponent = extracted.component(component);
            assertEquals(oldComponent.id(), newComponent.id());
            assertEquals(oldComponent.molecularWeightKgPerMol(), newComponent.molecularWeightKgPerMol(), 0.0);
            assertEquals(oldComponent.normalBoilingPointKelvin(), newComponent.normalBoilingPointKelvin(), 0.0);
            assertEquals(oldComponent.criticalTemperatureKelvin(), newComponent.criticalTemperatureKelvin(), 0.0);
            assertEquals(oldComponent.criticalPressurePascal(), newComponent.criticalPressurePascal(), 0.0);
            assertEquals(oldComponent.acentricFactor(), newComponent.acentricFactor(), 0.0);
            assertEquals(oldComponent.standardLiquidDensityKgPerCubicMetre(),
                    newComponent.standardLiquidDensityKgPerCubicMetre(), 0.0);
            assertEquals(oldComponent.idealGasEnthalpy(500.0), newComponent.idealGasEnthalpy(500.0), 0.0);
        }

        double[] baselineFeed = new double[16];
        System.arraycopy(baseline.feedForAssay(Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions(), 0, baselineFeed, 0, 16);
        double[] extractedFeed = extracted.crudeFeed(V3Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions();
        assertArrayEquals(baselineFeed, extractedFeed, 0.0);

        NextPengRobinsonKernel oldKernel = new NextPengRobinsonKernel(baseline);
        V3PengRobinsonKernel newKernel = new V3PengRobinsonKernel(extracted);
        double[] oldWilsonK = new double[16];
        double[] newWilsonK = new double[16];
        oldKernel.wilsonK(500.0, 250_000.0, oldWilsonK);
        newKernel.wilsonK(500.0, 250_000.0, newWilsonK);
        assertArrayEquals(oldWilsonK, newWilsonK, 0.0);

        NextPengRobinsonKernel.Workspace oldWorkspace = oldKernel.newWorkspace();
        NextPengRobinsonKernel.Evaluation oldLiquid = oldKernel.newEvaluation();
        NextPengRobinsonKernel.Evaluation oldVapor = oldKernel.newEvaluation();
        oldKernel.evaluatePair(500.0, 250_000.0, baselineFeed, baselineFeed, oldWorkspace, oldLiquid, oldVapor);
        V3PengRobinsonKernel.Workspace newWorkspace = newKernel.newWorkspace();
        V3PengRobinsonKernel.Evaluation newLiquid = newKernel.newEvaluation();
        V3PengRobinsonKernel.Evaluation newVapor = newKernel.newEvaluation();
        newKernel.evaluatePair(500.0, 250_000.0, extractedFeed, extractedFeed, newWorkspace, newLiquid, newVapor);

        assertEvaluationEquals(oldLiquid, newLiquid);
        assertEvaluationEquals(oldVapor, newVapor);
    }

    private static void assertEvaluationEquals(
            NextPengRobinsonKernel.Evaluation baseline, V3PengRobinsonKernel.Evaluation extracted) {
        assertEquals(baseline.compressibility(), extracted.compressibility(), 0.0);
        assertEquals(baseline.residualEnthalpyJoulesPerMol(), extracted.residualEnthalpyJoulesPerMol(), 0.0);
        assertEquals(baseline.physicalRootCount(), extracted.physicalRootCount());
        assertEquals(baseline.rootSeparation(), extracted.rootSeparation(), 0.0);
        assertArrayEquals(baseline.logFugacityCoefficients(), extracted.logFugacityCoefficients(), 0.0);
    }
}
