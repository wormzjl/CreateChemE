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

    // ---------------- faces to ports (phase-ports WP5, plan 4.1, default A1) ----------------

    private static final PhysicalFluidTopology.Direction N=PhysicalFluidTopology.Direction.NORTH;
    private PhysicalFluidTopology.Device at(long id,int x,int y,int z,Kind kind,PhysicalFluidTopology.Direction facing) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),kind,facing,new PipeResistance.Geometry(1,.05,.000045,0),
                kind==Kind.PUMP?new FlowControl.Pump(.001,500000,1):kind==Kind.COMPRESSOR?new FlowControl.Compressor(.00001,3,1):new FlowControl.Passive());
    }
    private PassiveNetwork.Reservoir nitrogenAt(long id,int y,PassiveNetwork.NodeKind kind,double pressure){return new PassiveNetwork.Reservoir(id,y,model.initialNitrogenCharge(1,298.15,pressure,()->{}),kind);}
    private int component(String name){int i=model.components().indexOf(name);assertTrue(i>=0,name);return i;}
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen (LevelHeadTest's). */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] mw=model.molecularWeights();double[] n=new double[mw.length];int water=component("Water");
        n[water]=waterVolume*997/mw[water];n[component("Nitrogen")]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,()->{});
    }
    private static int node(PassiveNetwork graph,long id){for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==id)return i;throw new AssertionError("no node "+id);}
    /** The compiled connection a pipe block belongs to. */
    private static PassiveNetwork.Pipe pipeOf(PhysicalFluidTopology.Compiled compiled,PassiveNetwork graph,long pipeBlock) {
        long id=compiled.pipeViews().get(pipeBlock).getFirst().pipeId();return graph.pipes().stream().filter(p->p.id()==id).findFirst().orElseThrow();
    }
    private static PassiveNetwork.Pipe between(PassiveNetwork graph,int a,int b) {
        return graph.pipes().stream().filter(p->p.first()==a&&p.second()==b||p.first()==b&&p.second()==a).findFirst().orElseThrow(()->new AssertionError("no connection "+a+"-"+b));
    }
    private static double gravityHead(FluidThermodynamics model,PassiveNetwork.Reservoir tank) {
        return PassiveStepSolver.GRAVITY*model.liquidMass(tank.state())*PassiveStepSolver.LEVEL_HEAD_HEIGHT/tank.inventory().volume();
    }

    /**
     * Every face of a tank: a pipe leaving by the UP face is the tank's top outlet (VAPOR), by the DOWN face its bottom
     * outlet (LIQUID), by a horizontal face a side outlet (BULK); the far ends (a generator above, voids elsewhere) stay
     * BULK whichever face they are reached by. The island solves with those ports.
     */
    @Test void everyFaceOfATankCompilesToItsPortAndOnlyTheTankEndCarriesOne() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(at(1,0,0,0,Kind.RESERVOIR,N));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();boundaries.put(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.RESERVOIR,101325));
        var expected=new LinkedHashMap<Long,PassiveNetwork.PhasePort>();var far=new HashMap<Long,Long>();long id=2;
        for(var face:PhysicalFluidTopology.Direction.values()) {
            devices.add(at(id,face.x,face.y,face.z,Kind.PIPE,N));long block=id++;
            var kind=face==PhysicalFluidTopology.Direction.UP?Kind.GENERATOR:Kind.VOID;
            devices.add(at(id,2*face.x,2*face.y,2*face.z,kind,N));
            boundaries.put(id,nitrogenAt(id,2*face.y,kind==Kind.GENERATOR?PassiveNetwork.NodeKind.GENERATOR:PassiveNetwork.NodeKind.VOID,101325));far.put(block,id++);
            expected.put(block,face==PhysicalFluidTopology.Direction.UP?PassiveNetwork.PhasePort.VAPOR:face==PhysicalFluidTopology.Direction.DOWN?PassiveNetwork.PhasePort.LIQUID:PassiveNetwork.PhasePort.BULK);
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);
        assertEquals(1,compiled.islands().size(),compiled.diagnostics().toString());var graph=compiled.islands().getFirst().graph();
        assertEquals(6,graph.pipes().size());int tank=node(graph,1);
        expected.forEach((block,port)->{
            var pipe=pipeOf(compiled,graph,block);
            assertEquals(port,pipe.portAt(tank),"pipe block "+block);
            assertEquals(PassiveNetwork.PhasePort.BULK,pipe.portAt(node(graph,far.get(block))),"the far boundary of pipe block "+block);
        });
        assertEquals(1,graph.pipes().stream().filter(p->p.portAt(tank)==PassiveNetwork.PhasePort.VAPOR).count());
        assertEquals(1,graph.pipes().stream().filter(p->p.portAt(tank)==PassiveNetwork.PhasePort.LIQUID).count());
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(.05,result.advancedSeconds(),0);
    }
    /** Two tanks stacked with a pipe between them: the upper tank's bottom outlet (LIQUID) feeds the lower tank's top (VAPOR). */
    @Test void aStackOfTwoTanksJoinsTheUpperTanksBottomToTheLowerTanksTop() {
        for(boolean upperFirst:new boolean[]{true,false}) {
            long upper=upperFirst?1:3,lower=upperFirst?3:1;
            var devices=List.of(at(upper,0,2,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.PIPE,N),at(lower,0,0,0,Kind.RESERVOIR,N));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(upper,nitrogenAt(upper,2,PassiveNetwork.NodeKind.RESERVOIR,101325),lower,nitrogenAt(lower,0,PassiveNetwork.NodeKind.RESERVOIR,101325)));
            var graph=compiled.islands().getFirst().graph();var pipe=pipeOf(compiled,graph,2);
            assertEquals(PassiveNetwork.PhasePort.LIQUID,pipe.portAt(node(graph,upper)),"the upper tank's end leaves by its DOWN face");
            assertEquals(PassiveNetwork.PhasePort.VAPOR,pipe.portAt(node(graph,lower)),"the lower tank's end leaves by its UP face");
        }
        // Two tanks directly on top of each other do not connect (boundaries never join directly), so no port is compiled.
        var touching=PhysicalFluidTopology.compile(List.of(at(1,0,1,0,Kind.RESERVOIR,N),at(2,0,0,0,Kind.RESERVOIR,N)),
                Map.of(1L,nitrogenAt(1,1,PassiveNetwork.NodeKind.RESERVOIR,101325),2L,nitrogenAt(2,0,PassiveNetwork.NodeKind.RESERVOIR,101325)));
        assertTrue(touching.islands().stream().allMatch(i->i.graph().pipes().isEmpty()));
    }
    /**
     * A pump on a tank's DOWN face. Facing down it draws from the tank's bottom: the passive suction run has the tank's
     * LIQUID port. Facing up it pumps into the tank's bottom: the compiled pump connection runs from the pump's junction to
     * the tank, and the port follows the tank's end through the {@code fromEnd ? b : a} swap, in either identity order
     * (which decides whether the run is stated from the tank or from the pump).
     */
    @Test void aPumpOnATanksBottomFaceTakesTheBottomPortInEitherOrientationAndIdentityOrder() {
        for(boolean tankFirst:new boolean[]{true,false}) {
            long tank=tankFirst?1:2,pump=tankFirst?2:1;
            // Suction from the bottom: tank at y=1, pump at y=0 facing DOWN, a pipe and a void below it.
            var drawing=PhysicalFluidTopology.compile(List.of(at(tank,0,1,0,Kind.RESERVOIR,N),at(pump,0,0,0,Kind.PUMP,PhysicalFluidTopology.Direction.DOWN),at(3,0,-1,0,Kind.PIPE,N),at(4,0,-2,0,Kind.VOID,N)),
                    Map.of(tank,nitrogenAt(tank,1,PassiveNetwork.NodeKind.RESERVOIR,101325),4L,nitrogenAt(4,-2,PassiveNetwork.NodeKind.VOID,101325)));
            assertTrue(drawing.islands().getFirst().error().isEmpty(),drawing.diagnostics().toString());
            var graph=drawing.islands().getFirst().graph();int t=node(graph,tank),u=node(graph,pump);
            var suction=between(graph,t,u);assertInstanceOf(FlowControl.Passive.class,suction.control());
            assertEquals(PassiveNetwork.PhasePort.LIQUID,suction.portAt(t));assertEquals(PassiveNetwork.PhasePort.BULK,suction.portAt(u));
            var moving=graph.pipes().stream().filter(p->p.control() instanceof FlowControl.Pump).findFirst().orElseThrow();
            assertEquals(u,moving.first());assertTrue(moving.bulk(),"the pump's own connection joins its junction and the void");
            // Discharge into the bottom: a generator and a pipe below the pump, the pump facing UP into the tank.
            var filling=PhysicalFluidTopology.compile(List.of(at(tank,0,1,0,Kind.RESERVOIR,N),at(pump,0,0,0,Kind.PUMP,PhysicalFluidTopology.Direction.UP),at(3,0,-1,0,Kind.PIPE,N),at(4,0,-2,0,Kind.GENERATOR,N)),
                    Map.of(tank,nitrogenAt(tank,1,PassiveNetwork.NodeKind.RESERVOIR,101325),4L,nitrogenAt(4,-2,PassiveNetwork.NodeKind.GENERATOR,101325)));
            assertTrue(filling.islands().getFirst().error().isEmpty(),filling.diagnostics().toString());
            graph=filling.islands().getFirst().graph();t=node(graph,tank);u=node(graph,pump);
            var discharge=between(graph,t,u);assertInstanceOf(FlowControl.Pump.class,discharge.control());
            assertEquals(u,discharge.first(),"a mover's connection is stated from its junction");assertEquals(t,discharge.second());
            assertEquals(PassiveNetwork.PhasePort.BULK,discharge.firstPort());assertEquals(PassiveNetwork.PhasePort.LIQUID,discharge.secondPort(),"tank first="+tankFirst);
        }
    }
    /**
     * An inline filter on a tank's UP face takes the rule too (its link is a boundary link), in both filter orientations:
     * facing UP the tank is on the filter's negative side; facing DOWN it is on the positive side, whose connection is
     * rebuilt onto the filter's positive junction and keeps its ports.
     */
    @Test void aFilterOnATanksTopFaceKeepsTheTopPortInBothOrientations() {
        for(var facing:List.of(PhysicalFluidTopology.Direction.UP,PhysicalFluidTopology.Direction.DOWN)) {
            var devices=List.of(at(1,0,0,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.FILTER,facing),at(3,0,2,0,Kind.PIPE,N),at(4,0,3,0,Kind.VOID,N));
            var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.RESERVOIR,120000),4L,nitrogenAt(4,3,PassiveNetwork.NodeKind.VOID,101325)),
                    Map.of(2L,InlineFilter.empty()),model.initialNitrogenCharge(1,298.15,101325,()->{}));
            var graph=compiled.islands().getFirst().graph();int tank=node(graph,1);
            var atTank=graph.pipes().stream().filter(p->p.first()==tank||p.second()==tank).toList();
            assertEquals(1,atTank.size());assertEquals(PassiveNetwork.PhasePort.VAPOR,atTank.getFirst().portAt(tank),"filter facing "+facing);
            assertEquals(1,graph.pipes().stream().filter(p->!p.bulk()).count(),"only the tank's end carries a port");
            var filter=graph.pipes().stream().filter(p->p.filter()!=null).findFirst().orElseThrow();assertTrue(filter.bulk());
            var result=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertTrue(result.averageMassFlows()[graph.pipes().indexOf(atTank.getFirst())]!=0,"the tank vents through its top and the filter");
        }
    }
    /**
     * Two tanks stacked on a pipe block, the upper one holding 0.3 m3 of water under nitrogen, both at 1 atm. The upper
     * tank's bottom outlet draws water and nothing else into the lower tank's top; as the lower tank's gas is compressed
     * its top outlet draws gas back up into the upper tank's bottom (inflow is not distinguished, decision D3), so the
     * water passes down and the gas up until the lower tank holds all of it, and the two headspaces stand one gas column
     * apart. Every component is conserved. The tank lines read the first interval: the upper tank's bottom outlet
     * "drawing water", the lower tank's top "receiving".
     */
    @Test void aCompiledStackPassesTheUpperTanksWaterDownAndItsGasUp() {
        var start=new PassiveNetwork.Reservoir(1,2,waterUnderNitrogen(.3,101325));
        var devices=List.of(at(1,0,2,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.PIPE,N),at(3,0,0,0,Kind.RESERVOIR,N));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,start,3L,nitrogenAt(3,0,PassiveNetwork.NodeKind.RESERVOIR,101325)));
        var graph=compiled.islands().getFirst().graph();int upper=node(graph,1),lower=node(graph,3);var line=between(graph,upper,lower);int edge=graph.pipes().indexOf(line);
        double[] before=totals(graph);
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result first=null;
        for(int slice=0;slice<40;slice++) {
            var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});graph=result.graph();if(first==null)first=result;
            assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance(),"slice "+slice);
        }
        double moved=(line.first()==upper?1:-1)*first.averageMassFlows()[edge];
        var out=first.pipeTransfers().stream().filter(t->t.pipeId()==line.id()).findFirst().orElseThrow();var drawn=line.first()==upper?out.forward():out.reverse();
        assertTrue(moved>0,"the water runs down: "+moved);
        assertEquals(0,Arrays.stream(drawn.phaseMoles()[2]).sum(),0,"a bottom outlet over water draws no gas");
        assertEquals(0,Arrays.stream(drawn.phaseMoles()[0]).sum(),0,"nor hydrocarbon liquid");
        var upperLines=TankOutlets.of(model,first.graph(),upper,first);var lowerLines=TankOutlets.of(model,first.graph(),lower,first);
        assertEquals(1,upperLines.size());assertEquals(PassiveNetwork.PhasePort.LIQUID,upperLines.getFirst().port());assertEquals(1,upperLines.getFirst().drawn(),"drawing water");
        assertEquals(moved,upperLines.getFirst().massFlow(),1e-9*Math.abs(moved));assertTrue(upperLines.getFirst().head()>0);
        assertEquals(PassiveNetwork.PhasePort.VAPOR,lowerLines.getFirst().port());assertEquals(-moved,lowerLines.getFirst().massFlow(),1e-9*Math.abs(moved),"the lower top receives");
        assertEquals(FluidView.Outlet.NONE,lowerLines.getFirst().drawn());
        double[] after=totals(graph);for(int c=0;c<before.length;c++)assertEquals(before[c],after[c],1e-10*Math.max(1,before[c]),"component "+c);
        var a=graph.reservoirs().get(upper);var b=graph.reservoirs().get(lower);
        System.out.println("STACK_PASS upperP="+a.state().pressure()+" lowerP="+b.state().pressure()+" upperLiquidKg="+model.liquidMass(a.state())+" lowerLiquidKg="+model.liquidMass(b.state()));
        assertTrue(model.liquidMass(a.state())<1e-6*model.liquidMass(start.state()),"the upper tank has passed down its water");
        assertTrue(b.state().pressure()>a.state().pressure()&&b.state().pressure()-a.state().pressure()<100,"the headspaces rest one gas column apart");
    }
    /**
     * {@code LEVEL_HEAD_REVIEW.md} fixture 6, face to port plus the level head on a compiled stack: a tank holding 0.3 m3
     * of water under nitrogen at 1 atm stands on a pipe block over a void held half the tank's level head above what the
     * two-metre water column alone would lift the tank's headspace to. Only the head (decision D9, at the tank's bottom port,
     * which its DOWN face compiles to) can drive the water down, and it does: the bottom outlet draws water only, and the
     * line comes to rest, still holding water, where the bottom-port pressure plus the column stands at the void's
     * pressure (within the reopen band of a closed one-way run).
     */
    @Test void aTankOverAVoidDrainsOnItsLevelHeadAloneAndRests() {
        var water=waterUnderNitrogen(.3,101325);var start=new PassiveNetwork.Reservoir(1,2,water);
        double rho=water.waterLiquid()*model.molecularWeights()[component("Water")]/water.waterVolume();
        double head=gravityHead(model,start),column=rho*PassiveStepSolver.GRAVITY*2,voidPressure=water.pressure()+column+head/2;
        var devices=List.of(at(1,0,2,0,Kind.RESERVOIR,N),at(2,0,1,0,Kind.PIPE,N),at(3,0,0,0,Kind.VOID,N));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,start,3L,nitrogenAt(3,0,PassiveNetwork.NodeKind.VOID,voidPressure)));
        var graph=compiled.islands().getFirst().graph();int tank=node(graph,1);var line=graph.pipes().getFirst();int edge=0;
        assertEquals(PassiveNetwork.PhasePort.LIQUID,line.portAt(tank));
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result first=null,last=null;double drained=0;
        for(int slice=0;slice<40;slice++) {
            last=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});graph=last.graph();if(first==null)first=last;
            assertEquals(PassiveStepSolver.Acceptance.FULL,last.acceptance(),"slice "+slice);
            drained+=(line.first()==tank?1:-1)*last.averageMassFlows()[edge]*5;
        }
        var out=first.pipeTransfers().getFirst();var drawn=line.first()==tank?out.forward():out.reverse();
        var a=graph.reservoirs().get(tank);double restRho=a.state().waterLiquid()*model.molecularWeights()[component("Water")]/a.state().waterVolume();
        double restHead=gravityHead(model,a),imbalance=a.state().pressure()+restHead+restRho*PassiveStepSolver.GRAVITY*2-voidPressure;
        System.out.println("STACK_HEAD head="+head+" column="+column+" void="+voidPressure+" firstFlow="+(line.first()==tank?1:-1)*first.averageMassFlows()[edge]
                +" drained="+drained+" kg restP="+a.state().pressure()+" restHead="+restHead+" imbalance="+imbalance+" lastFlow="+last.averageMassFlows()[edge]+" lastMode="+last.endpointModes().get(edge));
        assertTrue(water.pressure()+column<voidPressure,"the column alone cannot lift the headspace to the void");
        assertTrue(drained>1,"the head drains the tank: "+drained+" kg");
        assertEquals(0,Arrays.stream(drawn.phaseMoles()[2]).sum(),0,"the bottom outlet draws water only");
        assertTrue(restHead>0,"the tank still holds water at rest");
        assertEquals(0,last.averageMassFlows()[edge],1e-9,"and passes nothing");
        assertEquals(0,imbalance,Math.max(1,1e-6*voidPressure),"at rest the bottom port plus the column stands at the void");
    }
    private static double[] totals(PassiveNetwork graph) {
        double[] sum=null;
        for(var n:graph.reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR||n.junction()){var m=n.inventory().moles();if(sum==null)sum=new double[m.length];for(int c=0;c<m.length;c++)sum[c]+=m[c];}
        return sum;
    }
}
