package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.createcheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;

/**
 * F4 probe: the WP5 rig's pump lines with the placement defaults (FluidPumpedFillLineTest.line), integrated off-line
 * in consecutive intervals on one PassiveIntervalSolver (the retained solver's warm step estimate carried across).
 * Prints, per interval, the pump's mode and head and every tank's pressure and temperature at full precision.
 * Usage: PumpLineProbe <layout> <interval seconds|cold> <intervals> [trace-from seconds]; "cold" starts at one tick and
 * doubles to 5 s, as the coordinator starts an island a topology change created. e.g. RUPR 0.05 200, GUPRPRPR cold 90
 */
public final class PumpLineProbe {
    public static void main(String[] args) {
        String layout=args[0];boolean cold=args[1].equals("cold");double interval=cold?.05:Double.parseDouble(args[1]);int count=Integer.parseInt(args[2]);
        // -Df4.nitrogenMinimum=<K> narrows the nitrogen record's fluid_domain, to force a thermo-domain violation.
        var catalog=MaterialCatalog.bundled();String narrowed=System.getProperty("f4.nitrogenMinimum");
        if(narrowed!=null){var resources=new HashMap<>(catalog.resources());String key="data/createcheme/materials/properties/nitrogen.json";
            var o=com.google.gson.JsonParser.parseString(resources.get(key)).getAsJsonObject();o.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",Double.parseDouble(narrowed));
            resources.put(key,o.toString());catalog=MaterialCatalog.parse(resources);}
        var model=FluidThermodynamics.forNetwork(catalog,"createcheme:tjl20_methane_nitrogen",1e-9);
        var graph=line(model,layout);var solver=new PassiveIntervalSolver(model);double step=.05;double time=0;
        int pump=-1;for(int i=0;i<graph.pipes().size();i++)if(graph.pipes().get(i).control() instanceof FlowControl.Pump)pump=i;
        System.out.println("layout "+layout+" nodes "+graph.reservoirs().size()+" pipes "+graph.pipes().size()+" pump edge "+pump+" first node kind "+graph.reservoirs().get(graph.pipes().get(pump).first()).kind());
        double traceFrom=args.length>3?Double.parseDouble(args[3]):Double.POSITIVE_INFINITY;
        for(int k=0;k<count;k++) {
            if(time>=traceFrom-1e-9)System.setProperty("f4.trace","true");
            PassiveIntervalSolver.Result result;
            try{result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),()->{},step);}
            catch(RuntimeException failure){System.out.println(String.format(Locale.ROOT,"t=%.3f FAILED %s",time,failure.getMessage()));
                var domain=com.wormzjl.createcheme.science.fluid.solver.SparseNewton.domainViolation(failure);
                System.out.println("  domain="+(domain==null?"none":domain.getMessage()+" key="+domain.reasonKey()+" code="+domain.code()));break;}
            graph=result.graph();time+=interval;step=solver.nextStepEstimate();
            double advanced=interval;if(cold&&interval<5)interval=Math.min(5,interval*2);
            var line=new StringBuilder(String.format(Locale.ROOT,"t=%.3f dt=%.2f mode=%s head=%.6f Pa flow=%.6e kg/s acc=%d rej=%d reasons=%s",
                    time,advanced,result.endpointModes().get(pump),result.endpointHeads()[pump],result.averageMassFlows()[pump],result.acceptedSubsteps(),result.rejectedSubsteps(),result.rejectionReasons()));
            for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)
                line.append(String.format(Locale.ROOT," [%.6f Pa %.6f K]",node.state().pressure(),node.state().temperature()));
            var suction=graph.reservoirs().get(graph.pipes().get(pump).first()).state();
            line.append(String.format(Locale.ROOT," suction-density=%.6f",suction.mass()/suction.volume()));
            System.out.println(line);
        }
    }
    static PassiveNetwork line(FluidThermodynamics model,String layout) {
        var block=new PipeResistance.Geometry(1,.05,.000045,0);int n=model.componentCount();
        double[] water=new double[n];water[n-1]=1;double[] nitrogen=new double[n];nitrogen[model.components().indexOf("Nitrogen")]=1;
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'G'->TopologyCompiler.Kind.GENERATOR;case 'U'->TopologyCompiler.Kind.PUMP;case 'P'->TopologyCompiler.Kind.PIPE;
                case 'R'->TopologyCompiler.Kind.RESERVOIR;case 'V'->TopologyCompiler.Kind.VOID;default->throw new IllegalArgumentException(layout);};
            var device=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,64,0),kind,PhysicalFluidTopology.Direction.EAST,block,
                    kind==TopologyCompiler.Kind.PUMP?new FlowControl.Pump(.01,500000,1):new FlowControl.Passive());
            devices.add(device);
            if(kind==TopologyCompiler.Kind.GENERATOR)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,water).initialize(device,model,()->{}));
            else if(kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.VOID)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,nitrogen).initialize(device,model,()->{}));
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);
        return compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).findFirst().orElseThrow().graph();
    }
}
