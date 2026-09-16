package com.wormzjl.createcheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PhaseLayoutTest {
    @Test void coupledVolumeEnergyAndPhaseEquationsRecoverIndependentTpTargets() {
        String id="createcheme:tjl20_methane";var model=new FluidThermodynamics(MaterialCatalog.bundled(),id,1e-9);
        double[] crude=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),21);crude[20]=.2;
        double[] water=new double[21];water[20]=1;double[] methane=new double[21];methane[0]=1;
        for(double[] n:new double[][]{crude,water,methane}) {
            var seed=model.flashTP(350,101325,n,()->{});var target=model.flashTP(351,105000,n,()->{});
            var layout=new PhaseLayout(model,seed);
            var result=SparseNewton.solve(layout.fixedInventory(n,target.internalEnergy(),target.volume()),layout.encode(seed),new SparseNewton.Settings(30,1e-10,1e-6,24),()->{});
            var actual=layout.decode(result.variables(),0);
            assertEquals(target.temperature(),actual.temperature(),1e-5);assertEquals(target.pressure(),actual.pressure(),.1);
            assertArrayEquals(n,PhaseLayout.totalAmounts(actual),1e-10);
        }
    }
}
