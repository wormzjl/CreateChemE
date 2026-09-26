package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;

/** WP4 runtime diagnosis (scratch, not tracked): ElevatedBlockLineIslandTest's pumped rising line, interval by interval. */
public class DiagElevatedProbe {
    static final String DIMENSION="minecraft:overworld";
    static final int BOTTOM=-59,LIFT=4;
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    static final double PUMP_HEAD=500000;
    static final FluidThermodynamics model=FluidTestSupport.networkModel();
    static PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing) {
        var control=switch(kind){case PUMP->new FlowControl.Pump(.01,PUMP_HEAD,1);case VALVE->new FlowControl.PressureValve(200000);default->new FlowControl.Passive();};
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,y,0),kind,facing,BLOCK,control);
    }
    static PhysicalFluidTopology.Device device(long id,int x,int y,TopologyCompiler.Kind kind){return device(id,x,y,kind,PhysicalFluidTopology.Direction.NORTH);}
    static double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[n.length-1]=1;return n;}
    static double[] nitrogen(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NITROGEN]=1;return n;}
    static PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs) {
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices)if(specs.containsKey(d.id()))boundaries.put(d.id(),specs.get(d.id()).initialize(d,model,()->{}));
        var stock=new LinkedHashMap<Long,InlineFilter>();
        var seed=boundaries.values().iterator().next().state();
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,stock,seed);
        return compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList().getFirst().graph();
    }
    static PassiveNetwork risingPumpLine(double generatorPressure) {
        var devices=List.of(device(1,0,BOTTOM,TopologyCompiler.Kind.GENERATOR),
                device(2,0,BOTTOM+1,TopologyCompiler.Kind.PUMP,PhysicalFluidTopology.Direction.UP),
                device(3,0,BOTTOM+2,TopologyCompiler.Kind.PIPE),device(4,0,BOTTOM+3,TopologyCompiler.Kind.PIPE),
                device(5,0,BOTTOM+LIFT,TopologyCompiler.Kind.RESERVOIR));
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,generatorPressure,water()),5L,new FluidDeviceSpec(1,298.15,101325,nitrogen())));
    }
    static double rho(FluidThermodynamics.State s){return s.mass()/s.volume();}
    static double waterDensity(double pressure){var s=model.flashTP(298.15,pressure,water(),()->{});return rho(s);}
    public static void main(String[] args) {
        int count=args.length>0?Integer.parseInt(args[0]):40;
        var graph=risingPumpLine(400000);
        System.out.println("water="+System.getProperty("diag.water","region1")+" pumpRef="+model.pumpReferenceDensity()+" rho_w(400 kPa)="+waterDensity(400000)+" rho_w(860 kPa)="+waterDensity(860000)+" rho_w(900 kPa)="+waterDensity(900000));
        for(int i=0;i<graph.reservoirs().size();i++){var n=graph.reservoirs().get(i);System.out.printf("  node[%d] id=%d %s y=%.0f P=%.2f rho=%.4f%n",i,n.id(),n.kind(),n.elevation(),n.state().pressure(),rho(n.state()));}
        for(var p:graph.pipes())System.out.println("  pipe "+p.first()+"->"+p.second()+" "+p.control().getClass().getSimpleName()+" dz="+(graph.reservoirs().get(p.second()).elevation()-graph.reservoirs().get(p.first()).elevation()));
        double expected=400000+PUMP_HEAD-waterDensity(400000)*PassiveStepSolver.GRAVITY*LIFT;
        System.out.printf("test expectation %.2f Pa (tolerance 90 Pa)%n",expected);
        var solver=new PassiveIntervalSolver(model);double step=RetainedSolver.COLD_START_SECONDS;
        for(int i=0;i<count;i++) {
            try {
                var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,5));
                graph=result.graph();step=solver.nextStepEstimate();
                var g=graph.reservoirs().get(0).state();var j=graph.reservoirs().get(1).state();var t=graph.reservoirs().get(graph.reservoirs().size()-1).state();
                double[] flows=result.averageMassFlows();double[] heads=result.endpointHeads();
                // The pump edge is junction (suction, node 1) -> tank (node 2), dz = 3; the generator edge 0 -> 1 is passive, dz = 1.
                var pump=graph.pipes().get(1);double dz=graph.reservoirs().get(pump.second()).elevation()-graph.reservoirs().get(pump.first()).elevation();
                double limit=PUMP_HEAD*rho(j)/model.pumpReferenceDensity();
                double demandSuction=t.pressure()-j.pressure()+rho(j)*PassiveStepSolver.GRAVITY*dz;
                double demandTank=t.pressure()-j.pressure()+rho(t)*PassiveStepSolver.GRAVITY*dz;
                System.out.printf("int %2d acc=%d rej=%d modes=%s heads=%s flows=[%.4e, %.4e] | gen P=%.2f | jun P=%.2f rho=%.4f T=%.3f | tank P=%.2f T=%.3f rho=%.2f water=%.4f m3 | limit %.2f margin(suction column)=%.2f margin(tank column)=%.2f | tank-expected %.2f%n",
                        i+1,result.acceptedSubsteps(),result.rejectedSubsteps(),result.endpointModes(),Arrays.toString(Arrays.stream(heads).map(h->Math.round(h*100)/100.).toArray()),flows[0],flows[1],
                        g.pressure(),j.pressure(),rho(j),j.temperature(),t.pressure(),t.temperature(),rho(t),t.waterVolume(),limit,limit-demandSuction,limit-demandTank,t.pressure()-expected);
            }catch(RuntimeException held){System.out.println("HELD at interval "+(i+1)+": "+held.getMessage());break;}
        }
    }
}
