package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmptyNetworkTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private PassiveNetwork.Reservoir empty() {
        // The retained guess cannot become inventory or be used to infer a vacuum temperature.
        var guess=model.initialNitrogenCharge(1,298.15,101325,()->{});
        return new PassiveNetwork.Reservoir(1,0,guess,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],0));
    }
    @Test void isolatedEvacuatedReservoirAdvancesWithoutEosEvaluationOrInventedGas() {
        var initial=new PassiveNetwork(List.of(empty()),List.of());assertTrue(initial.reservoirs().getFirst().empty());
        var result=new PassiveIntervalSolver(model).solve(initial,20,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(20,result.advancedSeconds());assertTrue(result.graph().reservoirs().getFirst().empty());
        assertArrayEquals(new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],result.graph().reservoirs().getFirst().inventory().moles());assertEquals(0,result.graph().reservoirs().getFirst().inventory().internalEnergy());
        assertEquals(0,result.averageMassFlows().length);assertTrue(result.boundaries().isEmpty());assertEquals(initial,result.graph());
    }
    @Test void unsupportedVacuumFillingIsExplicitAndCannotPublishOutflowFromTheRetainedGuess() {
        var empty=empty();var source=new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,200000,()->{}),PassiveNetwork.NodeKind.GENERATOR);
        var graph=new PassiveNetwork(List.of(empty,source),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(1,.05,0,0))));
        var failure=assertThrows(IllegalArgumentException.class,()->new PassiveStepSolver(model).solve(graph,1,()->{}));assertTrue(failure.getMessage().contains("no fluid temperature"));
        assertArrayEquals(new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],empty.inventory().moles());assertEquals(0,empty.inventory().internalEnergy());
    }
}
