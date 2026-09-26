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
 * The block lines {@link FilterBlockLineIslandTest} builds, stood on end.
 *
 * <p>Every fluid fixture, block-line test and GameTest in the tree places its devices at one y, so
 * the static head {@code rho*GRAVITY*(z_b-z_a)} of
 * {@code PassiveStepSolver.Equations.edgeRows} is identically zero in all of them and the density
 * in it was never exercised. These are the first islands with a real elevation difference across a
 * connection, which is where that density stops being free: it used to be read off the upwind end
 * of the current iterate's flow, and a water generator against a nitrogen-charged tank differs by
 * most of water's density, so the row had a finite jump at exactly zero flow. Measured at 400 kPa
 * over four blocks the jump was 2.754e-2 of the row's own scale, the linear model predicted 1.08e-19
 * and the full step produced 2.76e-2; see documentation/ELEVATED_LINE_PROBE.md. The density is now
 * a column stated once per pass by {@code PassiveStepSolver.Equations.headDensities}.
 *
 * <p>They run the way the world runs a placed island - consecutive five-second intervals on one
 * retained solver from {@link RetainedSolver#COLD_START_SECONDS} - because an elevated line only
 * reaches the interesting point after it has filled its tank and settled against it.
 */
class ElevatedBlockLineIslandTest {
    private static final String DIMENSION="minecraft:overworld";
    /** The world floor the in-game confirmation builds on; see documentation/ELEVATED_LINE_PROBE.md. */
    private static final int BOTTOM=-59;
    /** The elevation a stack of pipe blocks puts between the generator and the tank. */
    private static final int LIFT=4;
    /** The world's own defaults; see FluidWorldAuthority.place and SolidTransportSettings.defaults. */
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final double FILTER_CAPACITY=.01,FILTER_RESISTANCE=1e6;
    /** The pump every pumped fixture in the tree uses; see FilterBlockLineIslandTest. */
    private static final double PUMP_HEAD=500000;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        var control=switch(kind) {
            case PUMP->new FlowControl.Pump(.01,PUMP_HEAD,1);
            case VALVE->new FlowControl.PressureValve(200000);
            default->new FlowControl.Passive();
        };
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),kind,facing,BLOCK,control);
    }
    private PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind) {
        return device(id,x,y,kind,PhysicalFluidTopology.Direction.NORTH);
    }
    private double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[n.length-1]=1;return n;}
    private double[] nitrogen(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NITROGEN]=1;return n;}
    private FluidDeviceSpec generatorSpec(double pressure){return new FluidDeviceSpec(1,298.15,pressure,water());}
    private FluidDeviceSpec tankSpec(double pressure){return new FluidDeviceSpec(1,298.15,pressure,nitrogen());}

    /** The compiled island the world would hand a worker for this line of blocks. */
    private PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs) {
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices)if(specs.containsKey(d.id()))boundaries.put(d.id(),specs.get(d.id()).initialize(d,model,()->{}));
        var stock=new LinkedHashMap<Long,InlineFilter>();
        for(var d:devices)if(d.kind()==TopologyCompiler.Kind.FILTER)
            stock.put(d.id(),new InlineFilter(FILTER_CAPACITY,FILTER_RESISTANCE,SolidInventory.EMPTY,0));
        var seed=boundaries.values().iterator().next().state();
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,stock,seed);
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"Expected exactly one connected island: "+compiled.diagnostics());
        return connected.getFirst().graph();
    }

    /** generator at the bottom - pipes straight up - tank {@link #LIFT} blocks above it. */
    private PassiveNetwork risingLine(double generatorPressure,double tankPressure) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR));
        for(int i=1;i<LIFT;i++)devices.add(device(1+i,0,BOTTOM+i,TopologyCompiler.Kind.PIPE));
        devices.add(device(1+LIFT,0,BOTTOM+LIFT,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure),(long)(1+LIFT),tankSpec(tankPressure)));
    }
    private PassiveNetwork risingLine(double generatorPressure){return risingLine(generatorPressure,101325);}
    /** The same stack upside down: the generator on top, the tank {@link #LIFT} blocks below it. */
    private PassiveNetwork fallingLine(double generatorPressure) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(device(1,0,BOTTOM+LIFT,TopologyCompiler.Kind.GENERATOR));
        for(int i=1;i<LIFT;i++)devices.add(device(1+i,0,BOTTOM+LIFT-i,TopologyCompiler.Kind.PIPE));
        devices.add(device(1+LIFT,0,BOTTOM,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure),(long)(1+LIFT),tankSpec(101325)));
    }
    /** generator - pipe - inline filter - pipe - tank, all of it vertical. */
    private PassiveNetwork risingFilterLine(double generatorPressure,double tankPressure) {
        var devices=List.of(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR),device(2,0,BOTTOM+1,TopologyCompiler.Kind.PIPE),
                device(3,0,BOTTOM+2,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.UP),
                device(4,0,BOTTOM+3,TopologyCompiler.Kind.PIPE),device(5,0,BOTTOM+LIFT,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure),5L,tankSpec(tankPressure)));
    }
    /** generator - pump - two pipes - tank, all of it vertical. The pump's outlet junction sits one
     * block above the generator, so its head is added there and only the remaining three blocks of
     * column stand between it and the tank. */
    private PassiveNetwork risingPumpLine(double generatorPressure) {
        var devices=List.of(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR),
                device(2,0,BOTTOM+1,TopologyCompiler.Kind.PUMP,PhysicalFluidTopology.Direction.UP),
                device(3,0,BOTTOM+2,TopologyCompiler.Kind.PIPE),device(4,0,BOTTOM+3,TopologyCompiler.Kind.PIPE),
                device(5,0,BOTTOM+LIFT,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure),5L,tankSpec(101325)));
    }
    /** The flat control: the same blocks in a row at one y. */
    private PassiveNetwork flatLine(double generatorPressure,double tankPressure) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR));
        for(int i=1;i<LIFT;i++)devices.add(device(1+i,i,BOTTOM,TopologyCompiler.Kind.PIPE));
        devices.add(device(1+LIFT,LIFT,BOTTOM,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure),(long)(1+LIFT),tankSpec(tankPressure)));
    }
    /** The flat filter control, for the same comparison. */
    private PassiveNetwork flatFilterLine(double generatorPressure,double tankPressure) {
        var devices=List.of(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR),device(2,1,BOTTOM,TopologyCompiler.Kind.PIPE),
                device(3,2,BOTTOM,TopologyCompiler.Kind.FILTER,PhysicalFluidTopology.Direction.EAST),
                device(4,3,BOTTOM,TopologyCompiler.Kind.PIPE),device(5,4,BOTTOM,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,generatorSpec(generatorPressure),5L,tankSpec(tankPressure)));
    }

    /** The density of the generator's own charge: the column a line fed from below stands in. */
    private double waterDensity(double pressure) {
        var state=model.flashTP(298.15,pressure,water(),()->{});
        return state.mass()/state.volume();
    }
    private double head(double pressure,int lift){return waterDensity(pressure)*PassiveStepSolver.GRAVITY*lift;}

    /** How far a line got, why it stopped if it did, and the graph it finished on. */
    private record Run(int survived,String detail,PassiveNetwork finished) {
        FluidThermodynamics.State tank() {
            for(int i=finished.reservoirs().size()-1;i>=0;i--) {
                var node=finished.reservoirs().get(i);
                if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)return node.state();
            }
            throw new AssertionError("No reservoir in this island");
        }
        boolean ok(){return detail.startsWith("OK");}
    }
    private Run run(PassiveNetwork graph,int count) {
        var solver=new PassiveIntervalSolver(model);double step=RetainedSolver.COLD_START_SECONDS;
        for(int i=0;i<count;i++) {
            try {
                var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,5));
                graph=result.graph();step=solver.nextStepEstimate();
                if(i==count-1)return new Run(count,"OK intervals="+count+" advanced="+result.advancedSeconds()
                        +" accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps(),graph);
            }catch(RuntimeException held) {
                return new Run(i,"HELD at interval "+(i+1)+"/"+count+" (t="+(5*i)+" s): "+held.getMessage()+shape(graph),graph);
            }
        }
        throw new AssertionError("unreachable");
    }
    private static String shape(PassiveNetwork graph) {
        var text=new StringBuilder();
        for(int n=0;n<graph.reservoirs().size();n++) {
            var node=graph.reservoirs().get(n);
            text.append("\n  node[").append(n).append("] id=").append(node.id()).append(' ').append(node.kind())
                    .append(" y=").append(node.elevation()).append(" P=").append(node.state().pressure())
                    .append(" rho=").append(node.state().mass()/node.state().volume())
                    .append(" liquid=").append(node.state().liquidVolume()+node.state().waterVolume())
                    .append(" vapour=").append(node.state().vaporVolume());
        }
        for(var pipe:graph.pipes())text.append("\n  pipe id=").append(pipe.id()).append(' ').append(pipe.first()).append("->").append(pipe.second())
                .append(" ").append(pipe.control().getClass().getSimpleName()).append(" filter=").append(pipe.filter()!=null)
                .append(" blocked=").append(pipe.blockedDirections());
        return text.toString();
    }

    /**
     * What a vertical line compiles to, which is the statement the rest of this file rests on: the
     * stack of pipe blocks collapses into one connection whose two endpoint elevations differ by
     * {@link #LIFT}, so the static head is on a real row and not merely on a node record.
     */
    @Test void aVerticalPipeStackCompilesToOneEdgeWithAnElevationDifference() {
        var graph=risingLine(101325);
        System.out.println("rising line:"+shape(graph));
        assertEquals(2,graph.reservoirs().size(),"generator and tank; the pipe run is collapsed");
        assertEquals(1,graph.pipes().size());
        var pipe=graph.pipes().getFirst();
        double dz=graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation();
        assertEquals(LIFT,Math.abs(dz),0,"The compiled connection must carry the block stack's elevation");
        assertEquals(BOTTOM,graph.reservoirs().stream().mapToDouble(PassiveNetwork.Reservoir::elevation).min().orElseThrow());
    }

    /**
     * The driven probe: every elevated shape against its flat control, at a generator pressure that
     * can actually lift the column.
     *
     * <p>The tank endpoint carries a hydrostatic offset the flat control does not, and that offset
     * is the physics rather than an error: a connection is at rest when
     * {@code P_generator - P_tank = rho*g*dz}, so a tank {@link #LIFT} m above a 400 kPa water
     * generator settles 39.08 kPa below it and one {@link #LIFT} m below settles that much above
     * it. Every line here lands within a pascal of that. The pumped line's outlet junction is one
     * block up, so its shutoff pressure is the generator's plus the pump's head less one block of
     * column, and only three blocks of column stand between that and the tank - which is the same
     * total {@link #LIFT} of head, because the column is continuous.
     */
    @Test void elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl() {
        double pressure=400000;
        var flat=run(flatLine(pressure,101325),40);
        System.out.println("\n=== generator at "+(long)pressure+" Pa, water column over "+LIFT+" m = "+head(pressure,LIFT)+" Pa ===");
        System.out.println("(flat control)  "+flat.detail()+"\n   tank P="+flat.tank().pressure()+" mass="+flat.tank().mass());
        assertTrue(flat.ok(),flat.detail());
        var lines=new LinkedHashMap<String,Run>();
        lines.put("rising  generator->3 pipes up->tank",run(risingLine(pressure),40));
        lines.put("falling generator->3 pipes down->tank",run(fallingLine(pressure),40));
        lines.put("rising  generator->pipe->filter->pipe->tank",run(risingFilterLine(pressure,101325),40));
        lines.put("rising  generator->pump->2 pipes->tank",run(risingPumpLine(pressure),40));
        lines.forEach((name,line)->System.out.println("("+name+") "+line.detail()
                +(line.ok()?"\n   tank P="+line.tank().pressure()+" mass="+line.tank().mass()
                +" offset vs flat="+(line.tank().pressure()-flat.tank().pressure())+" Pa":"")));
        lines.forEach((name,line)->assertTrue(line.ok(),name+": "+line.detail()));
        assertEquals(pressure-head(pressure,LIFT),lines.get("rising  generator->3 pipes up->tank").tank().pressure(),1e-5*pressure,
                "A tank above its generator must settle one water column below it");
        assertEquals(pressure+head(pressure,LIFT),lines.get("falling generator->3 pipes down->tank").tank().pressure(),1e-5*pressure,
                "A tank below its generator must settle one water column above it");
        assertEquals(pressure-head(pressure,LIFT),lines.get("rising  generator->pipe->filter->pipe->tank").tank().pressure(),1e-5*pressure,
                "A filter block may not change where the column settles");
        // The pump's shutoff is its rise limit, the setting scaled by its suction's density over the pump reference
        // density (the suction is the junction one block above the generator), less the column: the model's exact
        // shutoff. The earlier expectation took the rise as exactly PUMP_HEAD and was 144.5 Pa low; TR-BDF2 met it only
        // while its pump row stood on the discharge column (HANDOFF_REVIEW.md 8.9 (e) of the mixed-gas junction batch,
        // decision D6). A hundred pascals rather than the four the passive lines hold to, because the pump does work on
        // the water in the middle of the column.
        double suctionDensity=waterDensity(pressure-head(pressure,1));
        assertEquals(pressure+PUMP_HEAD*suctionDensity/model.pumpReferenceDensity()-head(pressure,LIFT),lines.get("rising  generator->pump->2 pipes->tank").tank().pressure(),
                1e-4*(pressure+PUMP_HEAD),"A pumped tank must settle one water column below its own shutoff pressure");
    }

    /**
     * The case designed to sit on the switch: the tank is charged to the hydrostatic balance of the
     * generator below it, so the line starts and stays at zero flow with water under nitrogen and
     * the connection's upwind end is undecided from the first substep. This is the shape that used
     * to hold at interval 1 with 511 accepted and 514 rejected substeps, on a residual of
     * 1.0000001544e-9 - the same tolerance floor the tank behind a valve used to stall on.
     */
    @Test void aLineChargedToItsOwnHydrostaticBalanceIntegratesAtZeroFlow() {
        double pressure=400000,balance=pressure-head(pressure,LIFT);
        var line=run(risingLine(pressure,balance),40);
        System.out.println("\n(balanced) generator at "+(long)pressure+" Pa, tank charged at "+balance+" Pa: "+line.detail()
                +(line.ok()?"\n   tank P="+line.tank().pressure()+" mass="+line.tank().mass():""));
        assertTrue(line.ok(),line.detail());
        assertEquals(balance,line.tank().pressure(),1e-6*pressure,"A balanced line must stay where it was charged");
    }

    /**
     * The boundary of what the static-head rule buys, stated as an invariant rather than as a
     * recorded failure.
     *
     * <p>A water generator with a nitrogen tank four blocks above it at the same pressure cannot
     * lift the column, and the only direction left - gas down into the generator - is one
     * {@code PassiveStepSolver.boundaryAllowed} forbids. That line still holds, and so does the
     * inline-filter version of it, but not for any reason involving elevation: the identical
     * dead-headed situation at one y, made by charging the tank above the generator instead of
     * lifting it, holds in exactly the same way and did so before the head was ever frozen -
     * measured at 1 Pa, 8.7 kPa, 39.1 kPa and 98.7 kPa of adverse pressure, all four stalling on
     * the friction kink at zero flow with a phantom water trace in a nitrogen tank pinning the step
     * length. See documentation/ELEVATED_LINE_PROBE.md section 6; the defect is recorded there and
     * is not this one.
     *
     * <p>So what this asserts is the equivalence, which is true now and stays true when that defect
     * is fixed: lifting a line four blocks does not change whether it can be integrated, and the
     * tank's endpoint is offset by exactly the column.
     */
    @Test void aDeadHeadedElevatedLineBehavesLikeItsFlatEquivalent() {
        double pressure=101325,column=head(pressure,LIFT);
        var rising=run(risingLine(pressure,pressure),40);
        var flat=run(flatLine(pressure,pressure+column),40);
        var risingFilter=run(risingFilterLine(pressure,pressure),40);
        var flatFilter=run(flatFilterLine(pressure,pressure+column),40);
        System.out.println("\n=== dead-headed at "+(long)pressure+" Pa, adverse column "+column+" Pa ===");
        System.out.println("(rising plain)  "+rising.detail());
        System.out.println("(flat  plain)   "+flat.detail());
        System.out.println("(rising filter) "+risingFilter.detail());
        System.out.println("(flat  filter)  "+flatFilter.detail());
        assertEquals(flat.ok(),rising.ok(),
                "A lifted line and a flat line against the same adverse pressure must integrate alike");
        assertEquals(flatFilter.ok(),risingFilter.ok(),
                "And so must the two filter lines");
        // The same line the other way up carries the column and runs: gravity is not the obstacle.
        var falling=run(fallingLine(pressure),40);
        System.out.println("(falling plain) "+falling.detail()
                +(falling.ok()?"\n   tank P="+falling.tank().pressure()+" mass="+falling.tank().mass():""));
        assertTrue(falling.ok(),falling.detail());
        assertEquals(pressure+column,falling.tank().pressure(),1e-5*pressure,
                "A tank four blocks under an atmospheric generator must settle one water column above it");
    }
}
