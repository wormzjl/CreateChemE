package com.wormzjl.createcheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PhaseLayoutTest {
    @Test void sparseTwoPhaseEncodingKeepsPhasePropertySnapshotsIsolated() {
        var model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        double[] liquid=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE],vapor=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE];liquid[6]=1;vapor[0]=1;
        var state=model.state(350,101325,liquid,vapor,0,0,101325);var layout=new PhaseLayout(model,state);
        var expected=layout.encode(state);
        state.liquidProperties().logFugacity()[0]=Double.NaN;state.vaporProperties().logFugacity()[0]=Double.NaN;
        assertArrayEquals(expected,layout.encode(state),0);
        assertTrue(Arrays.stream(expected).allMatch(Double::isFinite));
    }
    @Test void coupledVolumeEnergyAndPhaseEquationsRecoverIndependentTpTargets() {
        String id="createcheme:tjl20_methane";var model=new FluidThermodynamics(MaterialCatalog.bundled(),id,1e-9);
        double[] crude=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1);crude[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE]=.2;
        double[] water=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1];water[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE]=1;double[] methane=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1];methane[0]=1;
        for(double[] n:new double[][]{crude,water,methane}) {
            var seed=model.flashTP(350,101325,n,()->{});var target=model.flashTP(351,105000,n,()->{});
            var layout=new PhaseLayout(model,seed);
            var equations=layout.fixedInventory(n,target.internalEnergy(),target.volume());var initial=layout.encode(seed);var initialResidual=equations.residual(initial);
            var result=SparseNewton.solve(equations,initial,new SparseNewton.Settings(30,1e-10,1e-6,24),()->{});
            var actual=layout.decode(result.variables(),0);
            assertArrayEquals(initialResidual,equations.residual(initial),0,"Exact-temperature coefficient cache must not retain a perturbed Newton temperature");
            assertEquals(target.temperature(),actual.temperature(),1e-5);assertEquals(target.pressure(),actual.pressure(),.1);
            assertArrayEquals(n,PhaseLayout.totalAmounts(actual),1e-10);
        }
    }
}
