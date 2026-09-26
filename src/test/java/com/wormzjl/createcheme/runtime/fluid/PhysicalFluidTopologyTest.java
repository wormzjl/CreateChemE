package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalFluidTopologyTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind,PhysicalFluidTopology.Direction facing) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,z),kind,facing,new PipeResistance.Geometry(1,.05,.000045,0),
                kind==Kind.PUMP?new FlowControl.Pump(.00001,500000,1):kind==Kind.COMPRESSOR?new FlowControl.Compressor(.00001,3,1):kind==Kind.VALVE?new FlowControl.PressureValve(150000):new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir reservoir(long id){return new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,298.15,101325,()->{}));}
    @Test void tenPhysicalPipeBlocksCompileToOneTenMetreRunWithEveryDebugMapping() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(device(1,0,0,Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST));
        for(int x=1;x<=10;x++)devices.add(device(x+1,x,0,Kind.PIPE,PhysicalFluidTopology.Direction.EAST));devices.add(device(12,11,0,Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),12L,reservoir(12)));
        assertEquals(1,compiled.islands().size());var graph=compiled.islands().getFirst().graph();assertEquals(2,graph.reservoirs().size());assertEquals(1,graph.pipes().size());
        var pipe=graph.pipes().getFirst();assertEquals(10,pipe.sections().stream().mapToDouble(PipeResistance.Geometry::length).sum());assertEquals(10,compiled.pipeViews().size());
        for(var views:compiled.pipeViews().values()){assertEquals(1,views.size());assertEquals(pipe.id(),views.getFirst().pipeId());}
        assertEquals(PipeResistance.evaluate(new PipeResistance.Geometry(10,.05,.000045,0),.1,1000,.001).pressureDrop(),pipe.loss(.1,1000,.001).pressureDrop(),1e-12);
    }
    /** The mover between two nitrogen tanks is a compressor (decision D2; a liquid-only pump refuses the gas, D1), an actuator
     * that connects along its facing axis like the pump (plan 3.7); test name kept. */
    @Test void pumpControlUsesItsOutletDirectionAndItsSuctionJunctionInEitherOrientation() {
        for(var facing:List.of(PhysicalFluidTopology.Direction.EAST,PhysicalFluidTopology.Direction.WEST)) {
            var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.COMPRESSOR,facing),device(4,3,0,Kind.PIPE,facing),device(5,4,0,Kind.RESERVOIR,facing));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1),5L,reservoir(5)));assertTrue(compiled.islands().getFirst().error().isEmpty());
            var graph=compiled.islands().getFirst().graph();var pump=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Compressor).findFirst().orElseThrow();
            assertEquals(3,graph.reservoirs().get(pump.first()).id());assertEquals(facing==PhysicalFluidTopology.Direction.EAST?5:1,graph.reservoirs().get(pump.second()).id());
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertTrue(result.averageMassFlows()[graph.pipes().indexOf(pump)]>0);
        }
    }
    @Test void dimensionsDeadLegsAndUnsupportedPumpPortsCannotCreateHiddenConnections() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.PUMP,facing),device(4,2,1,Kind.PIPE,facing));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,reservoir(1)));
        assertTrue(compiled.diagnostics().get(3L).contains("ERROR"));assertTrue(compiled.diagnostics().containsKey(4L));
        var unanchored=PhysicalFluidTopology.compile(List.of(device(9,0,0,Kind.PIPE,facing),device(10,1,0,Kind.PIPE,facing)),Map.of());assertTrue(unanchored.islands().isEmpty());
        var a=device(20,0,0,Kind.RESERVOIR,facing);var b=new PhysicalFluidTopology.Device(21,new PhysicalFluidTopology.Position("minecraft:the_nether",1,0,0),Kind.RESERVOIR,facing,a.geometry(),new FlowControl.Passive());
        var separate=PhysicalFluidTopology.compile(List.of(a,b),Map.of(20L,reservoir(20),21L,reservoir(21)));assertEquals(2,separate.islands().size());
    }
    @Test void zeroStoragePumpBypassDisablesThePumpButStillAllowsPassiveEqualization() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.PUMP,facing),device(4,3,0,Kind.PIPE,facing),device(5,4,0,Kind.RESERVOIR,facing),
                device(6,1,1,Kind.PIPE,facing),device(7,2,1,Kind.PIPE,facing),device(8,3,1,Kind.PIPE,facing));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,150000,()->{})),5L,new PassiveNetwork.Reservoir(5,0,model.initialNitrogenCharge(1,298.15,149000,()->{}))));
        assertTrue(compiled.diagnostics().get(3L).contains("ERROR"));var graph=compiled.islands().getFirst().graph();
        var pump=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Pump).findFirst().orElseThrow();
        assertEquals(0,((FlowControl.Pump)pump.control()).targetVolumeFlow());
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,result.averageMassFlows()[graph.pipes().indexOf(pump)],1e-12);
        assertTrue(Arrays.stream(result.averageMassFlows()).anyMatch(q->Math.abs(q)>1e-6));
    }
    /** Plan 3.7: a compressor is an actuator like the pump. It connects only along its facing axis (a pipe at its side
     * stays unconnected), and a zero-storage bypass around it disables it by a zero target while passive equalisation runs. */
    @Test void aCompressorConnectsAlongItsAxisOnlyAndAZeroStorageBypassDisablesIt() {
        var facing=PhysicalFluidTopology.Direction.EAST;
        var side=PhysicalFluidTopology.compile(List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.COMPRESSOR,facing),device(4,2,1,Kind.PIPE,facing)),Map.of(1L,reservoir(1)));
        assertTrue(side.diagnostics().get(3L).contains("ERROR"),"one port only: "+side.diagnostics());assertTrue(side.diagnostics().containsKey(4L),"the side pipe is not connected");
        var devices=List.of(device(1,0,0,Kind.RESERVOIR,facing),device(2,1,0,Kind.PIPE,facing),device(3,2,0,Kind.COMPRESSOR,facing),device(4,3,0,Kind.PIPE,facing),device(5,4,0,Kind.RESERVOIR,facing),
                device(6,1,1,Kind.PIPE,facing),device(7,2,1,Kind.PIPE,facing),device(8,3,1,Kind.PIPE,facing));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,150000,()->{})),5L,new PassiveNetwork.Reservoir(5,0,model.initialNitrogenCharge(1,298.15,149000,()->{}))));
        assertTrue(compiled.diagnostics().get(3L).contains("ERROR"));var graph=compiled.islands().getFirst().graph();
        var compressor=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Compressor).findFirst().orElseThrow();
        assertEquals(0,((FlowControl.Compressor)compressor.control()).targetVolumeFlow());assertEquals(3,((FlowControl.Compressor)compressor.control()).maximumPressureRatio());
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,result.averageMassFlows()[graph.pipes().indexOf(compressor)],1e-12);
        assertTrue(Arrays.stream(result.averageMassFlows()).anyMatch(q->Math.abs(q)>1e-6));
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(9,new PhysicalFluidTopology.Position("minecraft:overworld",9,0,0),Kind.COMPRESSOR,facing,
                new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Pump(.01,500000,1)),"a compressor block takes a compressor's controls");
    }
}
