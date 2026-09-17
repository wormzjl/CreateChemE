package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipeTransferTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    @Test void integratedThreePhaseHistoryMatchesTheConservativeTankInventoryChange() {
        var n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane_nitrogen").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=.1;n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.2;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<2;i++) {
            var amounts=n.clone();double pressure=150000-1000*i;
            var unit=model.flashTP(350,pressure,amounts,()->{});for(int c=0;c<n.length;c++)amounts[c]/=unit.volume();
            var state=model.flashTP(350,pressure,amounts,()->{});nodes.add(new PassiveNetwork.Reservoir(i+1,0,state));
        }
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(8,0,1,new PipeResistance.Geometry(100,.03,.000045,0))));
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(1,result.pipeTransfers().size());var transfer=result.pipeTransfers().getFirst();
        var forward=transfer.forward().componentMoles();var reverse=transfer.reverse().componentMoles();
        var before=nodes.get(1).inventory().moles();var after=result.graph().reservoirs().get(1).inventory().moles();
        for(int c=0;c<n.length;c++)assertEquals(after[c]-before[c],forward[c]-reverse[c],1e-10+1e-8*Math.max(before[c],after[c]));
        assertEquals(5*result.averageMassFlows()[0],transfer.forward().massKg()-transfer.reverse().massKg(),1e-12);
        for(double volume:transfer.forward().phaseVolumes())assertTrue(volume>0);
        var copy=transfer.forward().phaseMoles();copy[0][0]=-100;assertTrue(transfer.forward().phaseMoles()[0][0]>=0);
    }
    @Test void allCrudeGeneratorsSupplyFiniteFlowAtTheExactAmbientLimit() {
        var catalog=MaterialCatalog.bundled();
        for(var preset:com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset.values()) {
            if(preset==com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset.HOLLAND)continue;
            var input=preset.input(catalog);var feed=input.feedComponentMolarFlowsMolPerSecond();
            var amounts=new double[model.componentCount()];
            for(int i=0;i<feed.length;i++)amounts[model.components().indexOf(input.componentBasis().componentIds().get(i))]=feed[i];
            for(double temperature:new double[]{293.15,298.15,350}) {
                var source=new PassiveNetwork.Reservoir(1,0,model.flashTP(temperature,150000,amounts,()->{}),PassiveNetwork.NodeKind.GENERATOR);
                var initial=model.initialNitrogenCharge(1,298.15,149000,()->{});
                var graph=new PassiveNetwork(List.of(source,new PassiveNetwork.Reservoir(2,0,initial)),
                        List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.03,.000045,0))));
                var result=new PassiveStepSolver(model).solve(graph,.01,()->{});
                assertTrue(result.massFlows()[0]>0,preset.id()+" T="+temperature);
                var before=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(initial);
                var after=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(result.states().get(1));
                for(int i=0;i<before.length;i++)assertEquals(before[i]+result.externalMoles()[i],after[i],1e-8);
                assertEquals(initial.internalEnergy()+result.externalEnergyJoule(),result.states().get(1).internalEnergy(),.001);
            }
        }
    }
    // Finite vessels cool while draining; keep their initial state just inside the 293.15 K domain.
    @Test void allRegroupedCrudesAndMixedDonorsConserveInventoryAtAmbientAndHeatedStates() {
        var catalog=MaterialCatalog.bundled();
        var presets=Arrays.stream(com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset.values())
                .filter(p->p!=com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset.HOLLAND).toList();
        var weights=com.wormzjl.createcheme.fluid.support.FluidTestSupport.molecularWeights(model);
        var failures=new ArrayList<String>();
        for(int crude=0;crude<presets.size();crude++)for(double temperature:new double[]{293.2,298.15,350}) {
            try {
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();
            for(int donor=0;donor<2;donor++) {
                var input=presets.get((crude+donor)%presets.size()).input(catalog);
                var source=input.feedComponentMolarFlowsMolPerSecond();var amounts=new double[model.componentCount()];
                for(int i=0;i<source.length;i++)amounts[model.components().indexOf(input.componentBasis().componentIds().get(i))]=source[i];
                double pressure=150000-1000*donor;
                var unit=model.flashTP(temperature,pressure,amounts,()->{});
                for(int i=0;i<amounts.length;i++)amounts[i]/=unit.volume();
                nodes.add(new PassiveNetwork.Reservoir(donor+1,0,model.flashTP(temperature,pressure,amounts,()->{})));
            }
            var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(8,0,1,new PipeResistance.Geometry(100,.03,.000045,0))));
            var before=com.wormzjl.createcheme.fluid.support.FluidTestSupport.finiteLedger(graph,weights);
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            com.wormzjl.createcheme.fluid.support.FluidTestSupport.assertClosed(before,
                    com.wormzjl.createcheme.fluid.support.FluidTestSupport.finiteLedger(result.graph(),weights));
            assertTrue(Double.isFinite(result.averageMassFlows()[0]));
            assertTrue(result.averageMassFlows()[0]>0,"Finite-viscosity crude must not be treated as a solid blockage");
            } catch(RuntimeException failure) {failures.add(presets.get(crude).id()+" T="+temperature+": "+failure.getMessage());}
        }
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
    @Test void reversalKeepsBothDonorCompositionsAndBoundsTheHistorySize() {
        var a=model.initialNitrogenCharge(1,350,150000,()->{});double[] water=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];water[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=1;
        var b=model.flashTP(350,150000,water,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a),new PassiveNetwork.Reservoir(2,0,b)),List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(1,.05,0,0))));
        var accumulator=new PipeTransfer.Accumulator();
        for(int i=0;i<100;i++)accumulator.add(PipeTransfer.sample(graph,List.of(a,b),new double[]{i%2==0?.1:-.2},1),1);
        assertEquals(1,accumulator.snapshot().size());var transfer=accumulator.snapshot().getFirst();
        assertEquals(5,transfer.forward().massKg(),1e-12);assertEquals(10,transfer.reverse().massKg(),1e-12);
        assertTrue(transfer.forward().componentMoles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]>0);assertEquals(0,transfer.forward().componentMoles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]);
        assertTrue(transfer.reverse().componentMoles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]>0);assertEquals(0,transfer.reverse().componentMoles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]);
    }
}
