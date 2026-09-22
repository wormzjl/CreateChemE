package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.ParticleSize;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A configuration event on a running island, driven the way {@link FluidWorldAuthority} drives one.
 *
 * <p>{@link FilterBlockLineIslandTest} builds the player's filter line once and integrates it. That
 * is not what a player does to a line that is already running: editing a device submits a topology
 * event, and {@code FluidWorldAuthority.applyPending} then <em>rebuilds the whole island</em> -
 * {@code boundaries()} collects the live non-junction nodes, the edited boundary is replaced by a
 * fresh {@code spec.initialize(...)}, and {@link PhysicalFluidTopology#compile} mints every
 * zero-holdup junction again from scratch. A fixture that swaps a boundary state inside the existing
 * graph never sees any of that, which is why the step solver alone could not reproduce the hold this
 * file covers: measured, the in-place swap runs 20 intervals and the rebuild holds on interval 1.
 *
 * <p>The recorded defect, at 400 kPa with the tank already full at
 * {@code 400000.0002132296 Pa / 743.1612560090696 kg}, was
 * {@code HELD: Junction mass continuity does not close} on the very first interval after the
 * generator was set to a 5 % {@code createcheme:demo_particle} feed. See
 * {@code documentation/FULL_TANK_SOLIDS_EVENT.md}.
 */
class FullTankSolidsEventTest {
    private static final String DIMENSION="minecraft:overworld";
    private static final int Y=-59;
    /** The world's own defaults; see FluidWorldAuthority.place and SolidTransportSettings.defaults. */
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final double FILTER_CAPACITY=.01,FILTER_RESISTANCE=1e6;
    private static final long FILTER_ID=3;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private PhysicalFluidTopology.Device device(long id,int x,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,Y,0),kind,facing,BLOCK,new FlowControl.Passive());
    }
    private PhysicalFluidTopology.Device device(long id,int x,TopologyCompiler.Kind kind) {
        return device(id,x,kind,PhysicalFluidTopology.Direction.NORTH);
    }
    private double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[n.length-1]=1;return n;}
    private double[] nitrogen(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NITROGEN]=1;return n;}
    /** The generator panel's own settings: pressure, water, and a solids volume fraction. */
    private FluidDeviceSpec generatorSpec(double pressure,double solidFraction) {
        var feed=solidFraction<=0?SlurryFeed.NONE:new SlurryFeed(solidFraction,
                List.of(new SlurryFeed.Grade("createcheme:demo_particle",ParticleSize.micrometres("100"),1)));
        return new FluidDeviceSpec(1,298.15,pressure,water(),feed);
    }
    private FluidDeviceSpec vesselSpec(){return new FluidDeviceSpec(1,298.15,101325,nitrogen());}

    /** generator - pipe - filter - pipe - reservoir, the line the report reproduces. */
    private List<PhysicalFluidTopology.Device> line() {
        return List.of(device(1,0,TopologyCompiler.Kind.GENERATOR),device(2,1,TopologyCompiler.Kind.PIPE),
                device(FILTER_ID,2,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.EAST),
                device(4,3,TopologyCompiler.Kind.PIPE),device(5,4,TopologyCompiler.Kind.RESERVOIR));
    }
    /** The same line continued into a void, so the tank is drawn down and the filter has to work. */
    private List<PhysicalFluidTopology.Device> drainedLine() {
        var devices=new ArrayList<>(line());
        devices.add(device(6,5,TopologyCompiler.Kind.PIPE));
        devices.add(device(7,6,TopologyCompiler.Kind.VOID));
        return List.copyOf(devices);
    }
    /**
     * One island built exactly as {@code FluidWorldAuthority.applyPending} builds it: the live
     * boundary stock, the live filter cakes, and the world's own nitrogen idle seed - never the
     * generator's state, which is what the single-shot fixtures elsewhere pass.
     */
    private PassiveNetwork compile(List<PhysicalFluidTopology.Device> devices,Map<Long,PassiveNetwork.Reservoir> stock,Map<Long,InlineFilter> cakes) {
        var compiled=PhysicalFluidTopology.compile(devices,stock,cakes,model.initialNitrogenCharge(1,298.15,101325,()->{}));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"Expected one connected island, diagnostics="+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    /** {@code FluidWorldAuthority.boundaries()}: the live, non-junction, positive-identity nodes. */
    private Map<Long,PassiveNetwork.Reservoir> boundaries(PassiveNetwork graph) {
        var result=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(var node:graph.reservoirs())if(!node.junction()&&node.id()>0)result.put(node.id(),node);
        return result;
    }
    /** {@code FluidWorldAuthority.filterStock()}: live cakes by physical identity, empty otherwise. */
    private Map<Long,InlineFilter> cakes(PassiveNetwork graph) {
        var result=new HashMap<Long,InlineFilter>();
        for(var pipe:graph.pipes())if(pipe.filter()!=null)result.put(pipe.id()-Long.MIN_VALUE,pipe.filter());
        result.putIfAbsent(FILTER_ID,new InlineFilter(FILTER_CAPACITY,FILTER_RESISTANCE,SolidInventory.EMPTY,0));
        return result;
    }
    /** The island as the world first activates it: both boundaries freshly initialized. */
    private PassiveNetwork activate(double pressure,double solidFraction) {
        var devices=line();var stock=new HashMap<Long,PassiveNetwork.Reservoir>();
        stock.put(1L,generatorSpec(pressure,solidFraction).initialize(devices.get(0),model,()->{}));
        stock.put(5L,vesselSpec().initialize(devices.get(4),model,()->{}));
        return compile(devices,stock,Map.of(FILTER_ID,new InlineFilter(FILTER_CAPACITY,FILTER_RESISTANCE,SolidInventory.EMPTY,0)));
    }
    /**
     * A configuration event applied to a live island: the edited boundary is re-initialized from its
     * new spec, every other boundary keeps the state it has reached, and the island is recompiled.
     */
    private PassiveNetwork event(PassiveNetwork live,List<PhysicalFluidTopology.Device> devices,long edited,FluidDeviceSpec spec) {
        var stock=boundaries(live);
        for(var d:devices)if(d.id()==edited)stock.put(edited,spec.initialize(d,model,()->{}));
        for(var d:devices)if(d.boundary()&&!stock.containsKey(d.id()))stock.put(d.id(),vesselSpec().initialize(d,model,()->{}));
        return compile(devices,stock,cakes(live));
    }

    private record Run(PassiveNetwork graph,String detail) {
        boolean ok(){return detail.startsWith("OK");}
    }
    /** Consecutive five-second intervals on one retained solver, as the island worker runs them. */
    private Run run(PassiveNetwork graph,int count,String tag) {
        var solver=new PassiveIntervalSolver(model);double step=RetainedSolver.COLD_START_SECONDS;
        for(int i=0;i<count;i++) {
            try {
                var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,5));
                graph=result.graph();step=solver.nextStepEstimate();
            }catch(RuntimeException held) {
                return new Run(graph,"HELD at interval "+(i+1)+"/"+count+" ("+tag+"): "+held.getMessage()+shape(graph));
            }
        }
        return new Run(graph,"OK "+count+" intervals ("+tag+")");
    }
    private static String shape(PassiveNetwork graph) {
        var text=new StringBuilder();
        for(int n=0;n<graph.reservoirs().size();n++) {
            var node=graph.reservoirs().get(n);
            text.append("\n  node[").append(n).append("] id=").append(node.id()).append(' ').append(node.kind())
                    .append(" P=").append(node.state().pressure()).append(" mass=").append(node.state().mass())
                    .append(" solidKg=").append(node.state().solidMoments().mass())
                    .append(" storedSolidKg=").append(node.inventory().solids().massKg());
        }
        for(var pipe:graph.pipes())text.append("\n  pipe ").append(pipe.first()).append("->").append(pipe.second())
                .append(pipe.filter()==null?" plain":" filter captured="+pipe.filter().captured().massKg());
        return text.toString();
    }
    private static PassiveNetwork.Reservoir node(PassiveNetwork graph,long id) {
        for(var n:graph.reservoirs())if(n.id()==id)return n;
        throw new AssertionError("No node "+id+" in"+shape(graph));
    }
    private static InlineFilter cake(PassiveNetwork graph) {
        for(var pipe:graph.pipes())if(pipe.filter()!=null)return pipe.filter();
        throw new AssertionError("No filter edge in"+shape(graph));
    }
    /** The tank the line ends on, by kind: a filter mints its outlet junction after it. */
    private static FluidThermodynamics.State tank(PassiveNetwork graph) {
        for(int i=graph.reservoirs().size()-1;i>=0;i--)
            if(graph.reservoirs().get(i).kind()==PassiveNetwork.NodeKind.RESERVOIR)return graph.reservoirs().get(i).state();
        throw new AssertionError("No reservoir in"+shape(graph));
    }

    /**
     * What a compiled zero-holdup junction is allowed to be given. The compiler mints one from the
     * island's first boundary by identity - the generator, here - as a property guess: a
     * temperature, a pressure and a composition for the first solve to start from. Its solids are
     * not a property, they are that boundary's stock, and a junction owns no volume to hold stock
     * in. Carrying them over minted the generator's whole 125 kg charge on <em>both</em> of the
     * filter's junctions, including the one on the far side of the filter edge, where no connection
     * into it can ever deliver a particle; the drained line's Newton block was then singular at
     * active-set pass 0. This is the statement of the rule, so a later change to the compiler shows
     * up here and not as a hold three fixtures away.
     */
    @Test void aCompiledJunctionCarriesTheBoundarysPropertiesAndNoneOfItsStock() {
        var graph=activate(400000,.05);
        System.out.println("(i) freshly activated slurry filter line:"+shape(graph));
        assertEquals(125,node(graph,1).state().solidMoments().mass(),1e-9,"the generator's own charge");
        for(var node:graph.reservoirs())if(node.junction()) {
            assertEquals(0,node.state().solidMoments().mass(),"A junction owns no stock: node "+node.id());
            assertTrue(node.inventory().solids().empty(),"A junction owns no stock: node "+node.id());
            assertEquals(node(graph,1).state().temperature(),node.state().temperature(),1e-12,"but it does take the properties");
            assertEquals(node(graph,1).state().pressure(),node.state().pressure(),1e-12,"but it does take the properties");
        }
    }

    /**
     * The reproduction. A clear-water line at 400 kPa fills its tank and settles, the generator is
     * then set to a 5 % solids feed, and the world rebuilds the island around that edit.
     *
     * <p>Before the fixes this held on interval 1 with
     * {@code Junction mass continuity does not close}, thrown at {@code ConservativeTransport:191}
     * for the filter's <em>inlet</em> junction, which nothing was delivering into because the tank
     * was full and every flow was exactly zero. Its reconstructed fractions summed to
     * {@code 1.0001158038514524}: the fluid half came from the Newton candidate
     * ({@code 0.8833400880902277}, i.e. one minus its own solid fraction) and the solid half from
     * the graph's stored inventory ({@code 125.0 kg}) divided by the candidate's total mass
     * ({@code 1070.4280353596107 kg}), while the candidate itself carried {@code 124.876 kg} -
     * the same solid <em>fraction</em> to 1e-15, over a total the Newton had shrunk by 0.099 %,
     * because a junction's holdup is a free scale.
     *
     * <p>The in-place boundary swap is kept alongside as the control: it is what a step-solver
     * probe does, it never rebuilt the island, and it never reproduced any of this.
     */
    @Test void aSolidsFeedAppliedToAFullTankKeepsTheIslandIntegrating() {
        var filled=run(activate(400000,0),20,"clear water, fill the tank");
        assertTrue(filled.ok(),filled.detail());
        assertEquals(400000,tank(filled.graph()).pressure(),1e-6*400000,"the tank must be full before the event");

        var rebuilt=event(filled.graph(),line(),1,generatorSpec(400000,.05));
        System.out.println("(ii) island rebuilt by the configuration event:"+shape(rebuilt));
        var after=run(rebuilt,20,"5 % solids onto a full tank");
        System.out.println("(ii) "+after.detail());
        assertTrue(after.ok(),after.detail());
        assertEquals(125,node(after.graph(),1).state().solidMoments().mass(),1e-9,"the feed did take effect");

        var swapped=new ArrayList<>(filled.graph().reservoirs());
        swapped.set(0,generatorSpec(400000,.05).initialize(line().get(0),model,()->{}));
        var control=run(new PassiveNetwork(swapped,filled.graph().pipes()),20,"in-place boundary swap");
        System.out.println("(control) "+control.detail());
        assertTrue(control.ok(),control.detail());
    }

    /**
     * The same event, then the tank drawn down through a void, which is the only state in which the
     * filter has anything to do. The cake reaches its capacity at
     * {@code 24.999999999975 kg} - the clogging figure {@code documentation/JUNCTION_PHANTOM_TRACE.md}
     * section 9.1 recorded in game - the inlet junction carries the generator's solid fraction while
     * the line flows, and the outlet junction carries none, because the filter edge is between them.
     *
     * <p>Before the compiler rule this held on interval 1 with
     * {@code Singular Newton Jacobian: Sparse LU rejected a singular matrix; active-set pass=0}:
     * the outlet junction had been minted holding 125 kg of particles whose only root is exactly
     * zero, which is the {@code JUNCTION_PHANTOM_TRACE} shape one level up, on the solid moments
     * rather than on a component trace.
     */
    @Test void theFilterCapturesOnceTheDrainedTankLetsTheLineFlow() {
        var filled=run(activate(400000,0),20,"clear water, fill the tank");
        assertTrue(filled.ok(),filled.detail());
        var fed=run(event(filled.graph(),line(),1,generatorSpec(400000,.05)),20,"5 % solids onto a full tank");
        assertTrue(fed.ok(),fed.detail());

        var drained=event(fed.graph(),drainedLine(),1,generatorSpec(400000,.05));
        var flowing=run(drained,40,"tank drawn down through a void");
        System.out.println("(iii) "+flowing.detail()+shape(flowing.graph()));
        assertTrue(flowing.ok(),flowing.detail());
        assertTrue(cake(flowing.graph()).captured().massKg()>24,"The filter must capture: "+shape(flowing.graph()));
        assertTrue(cake(flowing.graph()).clogged(),"and reach its capacity");
        assertEquals(125,node(flowing.graph(),1).state().solidMoments().mass(),1e-9);
        assertTrue(node(flowing.graph(),1).state().solidMoments().mass()>0);
        for(var node:flowing.graph().reservoirs())if(node.junction()&&node.id()<0)
            assertEquals(0,node.state().solidMoments().mass(),"Nothing delivers a particle past a filter edge");
    }

    /**
     * The release. Putting the feed back to 0 % is another configuration event on the same island,
     * so it rebuilds it once more - this time from junctions that are carrying the slurry line's
     * own converged states - and the island has to keep integrating on clear water.
     *
     * <p>In game this was reported as never taking effect, with the device reading
     * {@code WAITING: configuration event / HELD: Junction mass continuity does not close}. That is
     * a consequence and not a second defect in the event path: a held island's committed tick never
     * advances ({@code IslandClock.completed} retains all of its debt on a refused slice), so it
     * never satisfies {@code IslandCoordinator.aligned}, and {@code FluidWorldAuthority.applyPending}
     * never reaches the edit. With the island no longer holding, the edit applies on the next
     * alignment; this fixture covers the part that is this file's own - that the rebuilt island
     * integrates.
     */
    @Test void asecondConfigurationEventPutsTheFeedBackToZero() {
        var filled=run(activate(400000,0),20,"clear water, fill the tank");
        assertTrue(filled.ok(),filled.detail());
        var fed=run(event(filled.graph(),line(),1,generatorSpec(400000,.05)),20,"5 % solids onto a full tank");
        assertTrue(fed.ok(),fed.detail());

        var released=run(event(fed.graph(),line(),1,generatorSpec(400000,0)),20,"feed back to 0 %");
        System.out.println("(iv) "+released.detail()+shape(released.graph()));
        assertTrue(released.ok(),released.detail());
        assertEquals(0,node(released.graph(),1).state().solidMoments().mass(),"the generator is clear again");
        assertEquals(400000,tank(released.graph()).pressure(),1e-6*400000,"and the tank is still on its generator");
    }
}
