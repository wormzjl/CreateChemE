package com.wormzjl.createcheme.science.column.v3.thermo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/** Old tables are independent test fixtures; every loaded number is compared before testing the solver. */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.createcheme.science.material.Cdu17FixtureExtension.class)
class V3CatalogParityTest {
    @Test void unchangedChemicalsAndFirstSevenCutsMatchIndependentOriginalTables() {
        for(var original:List.of(V3Cdu17TiaJuanaPackage.INSTANCE,V3Tjl19PropertyPackage.INSTANCE,V3Tjl20MethanePropertyPackage.INSTANCE)) {
            var loaded=V3PropertyPackageRegistry.require(original.packageId());
            int retained=original instanceof V3Cdu17TiaJuanaPackage?16:original instanceof V3Tjl20MethanePropertyPackage?14:13;
            for(int i=0;i<retained;i++) {
                var a=original.component(i);var b=loaded.component(i);
                assertEquals(a.molecularWeightKgPerMol(),b.molecularWeightKgPerMol());
                assertEquals(a.normalBoilingPointKelvin(),b.normalBoilingPointKelvin());
                assertEquals(a.standardLiquidDensityKgPerCubicMetre(),b.standardLiquidDensityKgPerCubicMetre());
                assertEquals(a.criticalTemperatureKelvin(),b.criticalTemperatureKelvin());
                assertEquals(a.criticalPressurePascal(),b.criticalPressurePascal());
                assertEquals(a.acentricFactor(),b.acentricFactor());
                assertEquals(a.estimatedHeavyResidue(),b.estimatedHeavyResidue());
                assertArrayEquals(new double[]{a.cpA(),a.cpB(),a.cpC(),a.cpD(),a.cpE(),a.cpF()},new double[]{b.cpA(),b.cpB(),b.cpC(),b.cpD(),b.cpE(),b.cpF()});
                for(int j=0;j<retained;j++)assertEquals(original.binaryInteractions()[i][j],loaded.binaryInteractions()[i][j]);
                for(double t:new double[]{298.15,500,638.15,900}) {
                    assertEquals(a.idealGasHeatCapacity(t),b.idealGasHeatCapacity(t));
                    assertEquals(a.idealGasEnthalpy(t),b.idealGasEnthalpy(t));
                }
            }
            String assay=original instanceof V3Tjl20MethanePropertyPackage?"createcheme:tia_juana_light_methane":"createcheme:tia_juana_light";
            if(original instanceof V3Cdu17TiaJuanaPackage)assertArrayEquals(original.crudeFeed(assay).moleFractions(),loaded.crudeFeed(assay).moleFractions(),1e-16);
        }
    }
}
