package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpGameplayRegressionTest {
    @Test void clampedDrainContinuesAfterTheTankBecomesAlmostLiquidFull() throws Exception {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        String json;try(var input=Objects.requireNonNull(getClass().getResourceAsStream("/fluid/mcp-clamped-liquid-full-checkpoint.json"))){json=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var graph=FluidCheckpointCodec.decode(json,key->model).islands().getFirst().snapshot().graph();
        var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(1,result.advancedSeconds());assertTrue(result.graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]>0);
        var refined=new PassiveIntervalSolver(model,PassiveIntervalSolver.ErrorControl.STEP_DOUBLING).solve(graph,1,new PassiveIntervalSolver.Settings(.005,.02,.0001,4096),()->{});
        for(int i=0;i<graph.reservoirs().size();i++) {
            var actual=result.graph().reservoirs().get(i);var expected=refined.graph().reservoirs().get(i);
            assertEquals(expected.state().pressure(),actual.state().pressure(),1+.001*expected.state().pressure());
            assertEquals(expected.state().temperature(),actual.state().temperature(),.1);
            assertArrayEquals(expected.inventory().moles(),actual.inventory().moles(),1e-8*Math.max(1,expected.inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]));
        }
        for(int i=0;i<result.averageMassFlows().length;i++)assertEquals(refined.averageMassFlows()[i],result.averageMassFlows()[i],1e-10+.002*Math.abs(refined.averageMassFlows()[i]));
        // Continue past the first replay interval: the in-game failure moved a few seconds
        // later as the nitrogen trace shrank. No trace cutoff or invented gas is permitted.
        var continuing=result.graph();for(int i=0;i<200;i++)continuing=new PassiveIntervalSolver(model).solve(continuing,5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        assertTrue(continuing.reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]>0);
    }
    @Test void savedSonicRefusalAdvancesWithVelocityClampingAndKeepsCanonicalBalances() throws Exception {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        String json;try(var input=Objects.requireNonNull(getClass().getResourceAsStream("/fluid/mcp-held-drain-checkpoint.json"))){json=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var saved=FluidCheckpointCodec.decode(json,key->model).islands().getFirst().snapshot();var graph=saved.graph();
        assertTrue(saved.status().contains("Unsupported sonic flow"));var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(5,result.advancedSeconds());assertTrue(Arrays.stream(result.averageMassFlows()).anyMatch(q->Math.abs(q)>1e-6));
        double[] before=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],after=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],external=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];double beforeEnergy=0,afterEnergy=0,externalEnergy=0;
        for(var boundary:result.boundaries()){var n=boundary.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)external[c]+=n[c];externalEnergy+=boundary.totalEnergyJoule();}
        for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).kind()==PassiveNetwork.NodeKind.RESERVOIR) {
            var a=graph.reservoirs().get(i);var b=result.graph().reservoirs().get(i);var na=a.inventory().moles();var nb=b.inventory().moles();
            double ma=0,mb=0;for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++){before[c]+=na[c];after[c]+=nb[c];double mw=c==com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);ma+=na[c]*mw;mb+=nb[c]*mw;}
            beforeEnergy+=a.inventory().internalEnergy()+ma*PassiveStepSolver.GRAVITY*a.elevation();afterEnergy+=b.inventory().internalEnergy()+mb*PassiveStepSolver.GRAVITY*b.elevation();
        }
        for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)assertEquals(before[c]+external[c],after[c],1e-10+1e-8*Math.max(before[c],after[c]));
        assertEquals(beforeEnergy+externalEnergy+result.pumpWorkJoule(),afterEnergy,1e-4+1e-6*(Math.abs(beforeEnergy)+Math.abs(externalEnergy)));
    }
    @Test void belowSeaLevelNitrogenTankAcceptsWaterAfterAnIdlePeriodAndPressureEdit() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        var nitrogen=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var spec=FluidDeviceSpec.water();
        var device=new PhysicalFluidTopology.Device(3,new PhysicalFluidTopology.Position("minecraft:overworld",0,-60,3),com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR,PhysicalFluidTopology.Direction.NORTH,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
        var water=spec.initialize(device,model,()->{}).state();
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,-60,nitrogen),
                new PassiveNetwork.Reservoir(3,-60,water,PassiveNetwork.NodeKind.GENERATOR)),
                List.of(new PassiveNetwork.Pipe(2,0,1,new PipeResistance.Geometry(1,.05,.000045,0))));
        var solver=new PassiveIntervalSolver(model);
        for(int i=0;i<20;i++)graph=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
        var higher=new FluidDeviceSpec(1,298.15,200000,spec.composition()).initialize(device,model,()->{}).state();
        graph=new PassiveNetwork(List.of(graph.reservoirs().getFirst(),new PassiveNetwork.Reservoir(3,-60,higher,PassiveNetwork.NodeKind.GENERATOR)),graph.pipes());
        var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertTrue(result.graph().reservoirs().getFirst().state().waterLiquid()>0);
        assertEquals(nitrogen.vapor()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN],result.graph().reservoirs().getFirst().inventory().moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN],1e-8);
        var refined=new PassiveIntervalSolver(model,PassiveIntervalSolver.ErrorControl.STEP_DOUBLING).solve(graph,5,new PassiveIntervalSolver.Settings(.00005,.02,.0001,4096),()->{});
        var actual=result.graph().reservoirs().getFirst().state();var expected=refined.graph().reservoirs().getFirst().state();
        assertEquals(expected.pressure(),actual.pressure(),.001*expected.pressure());
        assertEquals(expected.temperature(),actual.temperature(),.1);
        assertEquals(expected.mass(),actual.mass(),.001*expected.mass());
        assertEquals(refined.averageMassFlows()[0],result.averageMassFlows()[0],.002*Math.abs(refined.averageMassFlows()[0]));
    }
}
