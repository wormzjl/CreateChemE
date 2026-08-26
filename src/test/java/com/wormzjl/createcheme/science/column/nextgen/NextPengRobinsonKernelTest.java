package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseProperties;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;
import org.junit.jupiter.api.Test;

class NextPengRobinsonKernelTest {
    @Test
    void rankOneKernelMatchesIndependentClassicalPrReference() {
        Cdu17TiaJuanaPackage pkg = Cdu17TiaJuanaPackage.INSTANCE;
        NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(pkg);
        double[] feed = new double[16];
        System.arraycopy(pkg.feedForAssay(Cdu17TiaJuanaPackage.ASSAY_ID).moleFractions(), 0, feed, 0, 16);
        NextPengRobinsonKernel.Workspace workspace = kernel.newWorkspace();
        NextPengRobinsonKernel.Evaluation liquid = kernel.newEvaluation();
        NextPengRobinsonKernel.Evaluation vapor = kernel.newEvaluation();
        kernel.evaluatePair(500.0, 250_000.0, feed, feed, workspace, liquid, vapor);

        PengRobinson78 reference = PengRobinson78.withoutBinaryInteractions(pkg.basis().components().subList(0, 16)
                .stream().map(component -> new ThermoComponent(component.id(), component.criticalTemperatureKelvin(),
                        component.criticalPressurePascal(), component.acentricFactor(), component.molecularWeightKgPerMol()))
                .toList());
        PhaseProperties expectedLiquid = reference.evaluate(500.0, 250_000.0, feed, PhaseRoot.LIQUID);
        PhaseProperties expectedVapor = reference.evaluate(500.0, 250_000.0, feed, PhaseRoot.VAPOR);

        assertTrue(kernel.usesRankOneMixing());
        assertEquals(expectedLiquid.compressibilityFactor(), liquid.compressibility(), 1e-11);
        assertEquals(expectedVapor.compressibilityFactor(), vapor.compressibility(), 1e-11);
        for (int index = 0; index < 16; index++) {
            assertEquals(expectedLiquid.logFugacityCoefficients()[index], liquid.logFugacityCoefficient(index), 1e-10);
            assertEquals(expectedVapor.logFugacityCoefficients()[index], vapor.logFugacityCoefficient(index), 1e-10);
        }
    }

    @Test
    void sharedPairWorkspaceDoesNotNeedANewEosObjectPerPhase() {
        NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(Cdu17TiaJuanaPackage.INSTANCE);
        double[] composition = new double[16];
        composition[1] = 0.2;
        composition[5] = 0.3;
        composition[10] = 0.5;
        NextPengRobinsonKernel.Workspace workspace = kernel.newWorkspace();
        NextPengRobinsonKernel.Evaluation liquid = kernel.newEvaluation();
        NextPengRobinsonKernel.Evaluation vapor = kernel.newEvaluation();

        kernel.evaluatePair(450.0, 300_000.0, composition, composition, workspace, liquid, vapor);
        assertTrue(Double.isFinite(liquid.residualEnthalpyJoulesPerMol()));
        assertTrue(Double.isFinite(vapor.residualEnthalpyJoulesPerMol()));
    }

    @Test
    void packageEnvelopeBoundsBothWilsonAndRigorousPaths() {
        NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(Cdu17TiaJuanaPackage.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> kernel.wilsonK(901.0, 250_000.0, new double[16]));
        assertThrows(IllegalArgumentException.class, () -> kernel.wilsonK(500.0, 2_100_000.0, new double[16]));
    }

    @Test
    void evaluationDoesNotExposeMutableFugacityWorkspace() {
        NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(Cdu17TiaJuanaPackage.INSTANCE);
        double[] composition = new double[16];
        composition[1] = 0.4;
        composition[10] = 0.6;
        NextPengRobinsonKernel.Evaluation evaluation = kernel.newEvaluation();
        kernel.evaluate(450.0, 300_000.0, composition, NextPengRobinsonKernel.Root.LIQUID,
                kernel.newWorkspace(), evaluation);

        double original = evaluation.logFugacityCoefficient(1);
        double[] copy = evaluation.logFugacityCoefficients();
        copy[1] = Double.NaN;
        assertEquals(original, evaluation.logFugacityCoefficient(1), 0.0);
    }
}
