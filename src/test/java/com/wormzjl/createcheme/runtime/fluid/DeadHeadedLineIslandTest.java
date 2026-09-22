package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The line a player builds that the generator cannot drive: a water generator against a tank that
 * already stands above it, either because the tank is charged higher or because it sits four blocks
 * up and the generator cannot lift the column.
 *
 * <p>There is no flow such a line can carry. The only direction with a root is the tank pushing its
 * gas back down into the generator, which {@code PassiveStepSolver.boundaryAllowed} forbids, so the
 * model answer is a flow of exactly zero reached through the boundary closure. The solve used not to
 * get there: the reverse root lies across zero flow, where the friction slope changes by three
 * orders between a liquid and a gas, and the tank carried a phantom water trace at
 * {@code total*1e-12} - the entry trace {@code initialPhaseSeeds} plants for every reachable
 * component - whose only root is exactly zero and which nothing delivers, so
 * {@code Equations.maximumStep} pinned the step length at {@code 1.6e-8} down to {@code 4.0e-18} and
 * the line search reported a stall at a residual its own direction would have removed in one step.
 * The island then held for good: it took no configuration or topology event, survived block removal,
 * a save and a client restart, and the player's only way out was to build elsewhere.
 *
 * <p>The closure is now taken before the pass, from the point the pass starts at, by
 * {@code PassiveStepSolver.closeDeadHeads}. See {@code documentation/DEAD_HEADED_LINE.md}; the
 * residuals quoted below are the ones this file measured before that rule existed, and
 * {@code documentation/ELEVATED_LINE_PROBE.md} section 6 recorded the same digits on the unmodified
 * solver, so they are the defect's fingerprint rather than one branch's accident.
 */
class DeadHeadedLineIslandTest {
    private static final String DIMENSION="minecraft:overworld";
    /** The world floor the in-game confirmation builds on. */
    private static final int BOTTOM=-59;
    /** The elevation a stack of pipe blocks puts between the generator and the tank. */
    private static final int LIFT=4;
    /** The world's own defaults; see FluidWorldAuthority.place. */
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final double FILTER_CAPACITY=.01,FILTER_RESISTANCE=1e6;
    private static final double GENERATOR=101325;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),kind,facing,BLOCK,new FlowControl.Passive());
    }
    private PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind) {
        return device(id,x,y,kind,PhysicalFluidTopology.Direction.NORTH);
    }
    private double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[n.length-1]=1;return n;}
    private double[] nitrogen(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NITROGEN]=1;return n;}
    private FluidDeviceSpec generatorSpec(double pressure){return new FluidDeviceSpec(1,298.15,pressure,water());}
    private FluidDeviceSpec tankSpec(double pressure){return new FluidDeviceSpec(1,298.15,pressure,nitrogen());}

    /* ---------------- the block lines ---------------- */

    private List<PhysicalFluidTopology.Device> flatBlocks() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR));
        for(int i=1;i<LIFT;i++)devices.add(device(1+i,i,BOTTOM,TopologyCompiler.Kind.PIPE));
        devices.add(device(1+LIFT,LIFT,BOTTOM,TopologyCompiler.Kind.RESERVOIR));
        return List.copyOf(devices);
    }
    /** The bricking scenario itself: generator at the bottom, three pipes up, tank four blocks above. */
    private List<PhysicalFluidTopology.Device> risingBlocks() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR));
        for(int i=1;i<LIFT;i++)devices.add(device(1+i,0,BOTTOM+i,TopologyCompiler.Kind.PIPE));
        devices.add(device(1+LIFT,0,BOTTOM+LIFT,TopologyCompiler.Kind.RESERVOIR));
        return List.copyOf(devices);
    }
    private List<PhysicalFluidTopology.Device> flatFilterBlocks() {
        return List.of(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR),device(2,1,BOTTOM,TopologyCompiler.Kind.PIPE),
                device(3,2,BOTTOM,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.EAST),
                device(4,3,BOTTOM,TopologyCompiler.Kind.PIPE),device(5,4,BOTTOM,TopologyCompiler.Kind.RESERVOIR));
    }
    private List<PhysicalFluidTopology.Device> risingFilterBlocks() {
        return List.of(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR),device(2,0,BOTTOM+1,TopologyCompiler.Kind.PIPE),
                device(3,0,BOTTOM+2,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.UP),
                device(4,0,BOTTOM+3,TopologyCompiler.Kind.PIPE),device(5,0,BOTTOM+LIFT,TopologyCompiler.Kind.RESERVOIR));
    }

    /** The compiled island the world would hand a worker for this line of blocks. */
    private PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,PassiveNetwork.Reservoir> stock) {
        var cakes=new LinkedHashMap<Long,InlineFilter>();
        for(var d:devices)if(d.kind()==TopologyCompiler.Kind.FILTER)cakes.put(d.id(),new InlineFilter(FILTER_CAPACITY,FILTER_RESISTANCE,SolidInventory.EMPTY,0));
        var compiled=PhysicalFluidTopology.compile(devices,stock,cakes,model.initialNitrogenCharge(1,298.15,101325,()->{}));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"Expected exactly one connected island: "+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    /** The island as the world first activates it, both boundaries freshly initialized. */
    private PassiveNetwork line(List<PhysicalFluidTopology.Device> devices,double generator,double tank) {
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        long last=devices.getLast().id();
        stock.put(1L,generatorSpec(generator).initialize(devices.getFirst(),model,()->{}));
        stock.put(last,tankSpec(tank).initialize(devices.getLast(),model,()->{}));
        return island(devices,stock);
    }
    /**
     * A configuration event on a live island, driven the way {@link FullTankSolidsEventTest} drives
     * one: the edited boundary is re-initialized from its new setting, every other boundary keeps
     * the state it has reached, and the island is recompiled.
     */
    private PassiveNetwork raiseGenerator(PassiveNetwork live,List<PhysicalFluidTopology.Device> devices,double pressure) {
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var node:live.reservoirs())if(!node.junction()&&node.id()>0)stock.put(node.id(),node);
        stock.put(1L,generatorSpec(pressure).initialize(devices.getFirst(),model,()->{}));
        return island(devices,stock);
    }

    /* ---------------- running them ---------------- */

    private static PassiveNetwork.Reservoir tankOf(PassiveNetwork graph) {
        for(int i=graph.reservoirs().size()-1;i>=0;i--) {
            var node=graph.reservoirs().get(i);
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)return node;
        }
        throw new AssertionError("No reservoir in this island");
    }
    private record Run(int survived,String detail,PassiveNetwork finished,List<FlowControl.Mode> modes,double[] flows) {
        FluidThermodynamics.State tank(){return tankOf(finished).state();}
        boolean ok(){return detail.startsWith("OK");}
    }
    /** Consecutive five-second intervals on one retained solver, as the island worker runs them. */
    private Run run(PassiveNetwork graph,int count) {
        var solver=new PassiveIntervalSolver(model);double step=RetainedSolver.COLD_START_SECONDS;
        List<FlowControl.Mode> modes=List.of();double[] flows=new double[0];
        for(int i=0;i<count;i++) {
            try {
                var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,5));
                graph=result.graph();step=solver.nextStepEstimate();modes=result.endpointModes();flows=result.averageMassFlows();
                if(i==count-1)return new Run(count,"OK intervals="+count+" accepted="+result.acceptedSubsteps()
                        +" rejected="+result.rejectedSubsteps(),graph,modes,flows);
            }catch(RuntimeException held) {
                return new Run(i,"HELD at interval "+(i+1)+"/"+count+": "+held.getMessage(),graph,modes,flows);
            }
        }
        throw new AssertionError("unreachable");
    }
    /** The density of the generator's own charge: the column a line fed from below stands in. */
    private double waterDensity(double pressure) {
        var state=model.flashTP(298.15,pressure,water(),()->{});
        return state.mass()/state.volume();
    }
    private double column(double pressure){return waterDensity(pressure)*PassiveStepSolver.GRAVITY*LIFT;}

    /**
     * Every connection of a dead-headed line is closed and carries <em>exactly</em> zero; the tank
     * then comes out of forty intervals with the inventory it went in with, to within the
     * reconstruction's own roundoff - measured at one unit in the last place of its nitrogen over
     * the forty, against the {@code 1e-12} relative bound asserted here, and a nanopascal of
     * pressure. Nothing is transferred, because there is nothing for a zero flow to transfer; what
     * moves is the last bit of a state that is re-decoded from that inventory each substep.
     */
    private void assertAtRest(Run run,PassiveNetwork.Reservoir before,String what) {
        assertTrue(run.ok(),what+": "+run.detail());
        for(int edge=0;edge<run.modes().size();edge++)assertEquals(FlowControl.Mode.CLOSED,run.modes().get(edge),
                what+": connection "+edge+" must be closed in the direction its boundary forbids, modes="+run.modes());
        for(double flow:run.flows())assertEquals(0,flow,0,what+": a closed line carries exactly zero");
        var after=tankOf(run.finished());
        double[] was=before.inventory().moles(),now=after.inventory().moles();
        for(int c=0;c<was.length;c++)assertEquals(was[c],now[c],1e-12*Math.max(1e-30,was[c]),
                what+": a closed line may not move component "+c+" into or out of the tank");
        assertEquals(before.inventory().internalEnergy(),after.inventory().internalEnergy(),
                1e-12*Math.abs(before.inventory().internalEnergy()),what+": nor any energy");
        assertEquals(before.state().pressure(),after.state().pressure(),1e-9*before.state().pressure(),
                what+": the tank's pressure must not move");
    }

    /* ---------------- the fixtures ---------------- */

    /**
     * The flat equivalent at the four adverse pressures {@code ELEVATED_LINE_PROBE.md} section 6
     * measured on the unmodified solver, where they held at residuals
     * {@code 1.9355024035429073E-5}, {@code 0.0788636363636365}, {@code 0.27828626375583176} and
     * {@code 0.493375} - the last of which is {@code 98675/200000}, the whole adverse pressure
     * showing on a row whose flow cannot move at all.
     */
    @Test void aTankChargedAboveItsGeneratorRestsInsteadOfHolding() {
        for(double charge:new double[]{101326,110000,140395,200000}) {
            var graph=line(flatBlocks(),GENERATOR,charge);
            var before=tankOf(graph);
            var run=run(graph,40);
            System.out.println("(flat, tank charged at "+(long)charge+" Pa, adverse "+(long)(charge-GENERATOR)+" Pa) "+run.detail());
            assertAtRest(run,before,"charged at "+(long)charge);
        }
    }

    /**
     * The line the player actually builds, and the one the in-game confirmation places with
     * {@code /setblock}: a water generator at its default pressure with a tank four blocks above it,
     * which held at {@code Newton line search stalled at residual 5.066656785680298E-4} - the same
     * digits the dev client's own log printed for the placed blocks.
     */
    @Test void aDeadHeadedElevatedLineRestsInsteadOfHolding() {
        var graph=line(risingBlocks(),GENERATOR,GENERATOR);
        var before=tankOf(graph);
        var run=run(graph,40);
        System.out.println("(rising, "+(long)GENERATOR+" Pa against "+column(GENERATOR)+" Pa of column) "+run.detail());
        assertAtRest(run,before,"dead-headed elevated line");
    }

    /**
     * The same two shapes with an inline filter in them, which held at
     * {@code Newton iteration limit at residual 0.29469706587678757} flat and at
     * {@code Empty fluid initialization} rising - a junction mixture of what nothing delivers. The
     * closure reaches them because it is stated across the whole passive run through the filter's
     * two degree-two junctions rather than across one connection.
     */
    @Test void aDeadHeadedFilterLineRestsInsteadOfHolding() {
        var flat=line(flatFilterBlocks(),GENERATOR,GENERATOR+column(GENERATOR));
        var flatBefore=tankOf(flat);
        var flatRun=run(flat,40);
        System.out.println("(flat filter)   "+flatRun.detail());
        assertAtRest(flatRun,flatBefore,"flat filter line");

        var rising=line(risingFilterBlocks(),GENERATOR,GENERATOR);
        var risingBefore=tankOf(rising);
        var risingRun=run(rising,40);
        System.out.println("(rising filter) "+risingRun.detail());
        assertAtRest(risingRun,risingBefore,"rising filter line");
    }

    /**
     * The way out the player never had. The closure is a statement of one solve, so a generator
     * turned up afterwards is decided again from the new starting point: the line opens, fills, and
     * settles one water column below the generator.
     */
    @Test void raisingTheGeneratorReopensADeadHeadedLineAndFillsTheTank() {
        var blocks=risingBlocks();
        var held=run(line(blocks,GENERATOR,GENERATOR),10);
        assertTrue(held.ok(),"the dead-headed line must rest first: "+held.detail());

        var raised=raiseGenerator(held.finished(),blocks,400000);
        var filled=run(raised,40);
        System.out.println("(reopened at 400 kPa) "+filled.detail()+" tank P="+filled.tank().pressure()+" mass="+filled.tank().mass());
        assertTrue(filled.ok(),"raising the generator must re-open the line: "+filled.detail());
        double expected=400000-column(400000);
        assertEquals(expected,filled.tank().pressure(),1e-5*expected,
                "a tank four blocks above a 400 kPa generator must settle one water column below it");
        assertTrue(filled.tank().mass()>700,"the tank must actually have filled with water: "+filled.tank().mass()+" kg");
        // And once it is full it is dead-headed again, at its own hydrostatic balance rather than
        // against an adverse charge, so the same closure holds it there. That is the end state a
        // filled line is supposed to have, and it is the one the in-game tank shows as FULL.

        // And back down again: the same line at the old setting is at rest, not held.
        var lowered=raiseGenerator(filled.finished(),blocks,GENERATOR);
        var resting=run(lowered,40);
        System.out.println("(lowered back to 101.325 kPa) "+resting.detail()+" tank P="+resting.tank().pressure());
        assertTrue(resting.ok(),"putting the generator back must leave the line at rest: "+resting.detail());
    }
}
