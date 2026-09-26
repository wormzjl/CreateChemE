package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Diagnostic probe (temporary): the WP5 in-game layouts with the placement defaults, off-line. */
public class PumpedFillProbeTest {
    private static final String DIMENSION="minecraft:overworld";
    private static final int Y=64;
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[n.length-1]=1;return n;}
    private double[] nitrogen(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NITROGEN]=1;return n;}

    /** A line of blocks along +x, every block facing east, the WP5 rig's letters: G generator, U pump, P pipe, R tank, V void. */
    public PassiveNetwork line(String layout) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'G'->TopologyCompiler.Kind.GENERATOR;case 'U'->TopologyCompiler.Kind.PUMP;case 'P'->TopologyCompiler.Kind.PIPE;
                case 'R'->TopologyCompiler.Kind.RESERVOIR;case 'V'->TopologyCompiler.Kind.VOID;default->throw new IllegalArgumentException(layout);};
            var control=kind==TopologyCompiler.Kind.PUMP?new FlowControl.Pump(.01,500000,1):new FlowControl.Passive();
            var device=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position(DIMENSION,i,Y,0),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,control);
            devices.add(device);
            if(kind==TopologyCompiler.Kind.GENERATOR)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,water()).initialize(device,model,()->{}));
            else if(kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.VOID)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,nitrogen()).initialize(device,model,()->{}));
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        if(connected.size()!=1)throw new AssertionError("Expected one island for "+layout+": "+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    public static String shape(PassiveNetwork graph) {
        var text=new StringBuilder();
        for(int n=0;n<graph.reservoirs().size();n++) {
            var node=graph.reservoirs().get(n);var s=node.state();
            text.append("\n  node[").append(n).append("] id=").append(node.id()).append(' ').append(node.kind()).append(" y=").append(node.elevation())
                    .append(String.format(" P=%.3f T=%.4f mass=%.6f liq=%.6g water=%.6g vap=%.6g",s.pressure(),s.temperature(),s.mass(),s.liquidVolume(),s.waterVolume(),s.vaporVolume()));
        }
        for(var pipe:graph.pipes())text.append("\n  pipe id=").append(pipe.id()).append(' ').append(pipe.first()).append("->").append(pipe.second())
                .append(' ').append(pipe.control()).append(" sections=").append(pipe.sections().size());
        return text.toString();
    }
    /** Consecutive intervals on one retained solver; on a failure the same interval is retried from the cold step, as the coordinator does. */
    String run(String layout,int intervals,double seconds,int retries) {
        var graph=line(layout);var out=new StringBuilder("=== "+layout+shape(graph));
        var solver=new PassiveIntervalSolver(model);double step=RetainedSolver.COLD_START_SECONDS;int holds=0;
        for(int i=0;i<intervals;i++) {
            long t0=System.nanoTime();
            try {
                var result=solver.solve(graph,seconds,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(step,seconds));
                graph=result.graph();step=solver.nextStepEstimate();
                out.append(String.format("%n interval %d OK %.1f ms accepted=%d rejected=%d modes=%s flows=%s reasons=%s",i+1,(System.nanoTime()-t0)/1e6,result.acceptedSubsteps(),result.rejectedSubsteps(),result.endpointModes(),Arrays.toString(result.averageMassFlows()),result.rejectionReasons()));
                var tanks=new StringBuilder();for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)tanks.append(String.format(" [P=%.1f liq=%.4f]",node.state().pressure(),(node.state().liquidVolume()+node.state().waterVolume())/node.state().volume()));
                out.append("\n   tanks").append(tanks);
            }catch(RuntimeException held) {
                holds++;step=RetainedSolver.COLD_START_SECONDS;
                out.append(String.format("%n interval %d HELD %.1f ms: %s",i+1,(System.nanoTime()-t0)/1e6,held.getMessage()));
                if(holds>retries){out.append(shape(graph));break;}
                i--;
            }
        }
        return out.toString();
    }
    /** Solver counters for the first seconds of each fill, fresh solver, from the cold step. */
    @Test void counters() {
        var d=com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.class;
        for(String layout:new String[]{"GUPRPRPR","GUPRPRPRPRPRPR"})for(double seconds:new double[]{.05,.5,5}) {
            // warm up the JIT once per layout
            if(seconds==.05)new PassiveIntervalSolver(model).solve(line(layout),.05,PassiveIntervalSolver.Settings.defaults(),()->{},RetainedSolver.COLD_START_SECONDS);
            com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.reset();com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=true;
            long t0=System.nanoTime();String outcome;
            try{var r=new PassiveIntervalSolver(model).solve(line(layout),seconds,PassiveIntervalSolver.Settings.defaults(),()->{},Math.min(seconds,RetainedSolver.COLD_START_SECONDS));outcome="OK accepted="+r.acceptedSubsteps()+" rejected="+r.rejectedSubsteps();}
            catch(RuntimeException e){outcome="HELD "+e.getMessage();}
            long ns=System.nanoTime()-t0;com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.ENABLED=false;
            var sample=com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.sample();
            var text=new StringBuilder(String.format("=== %s %.2f s: %s in %.1f ms%n",layout,seconds,outcome,ns/1e6));
            sample.counters().forEach((k,v)->{if(v!=0)text.append("  ").append(k).append('=').append(v).append('\n');});
            System.out.println(text);
        }
    }
    @Test void probe() {
        String only=System.getProperty("probe.layout","");
        int intervals=Integer.getInteger("probe.intervals",12);
        for(String layout:only.isEmpty()?new String[]{"GUPRPRPR","GUPRPRPRPRPRPR","RUPR","RUPRPR","RPRUPRPR","GUPRPRPRPV","GUPRPRPRPRPRPRPV"}:only.split(","))
            System.out.println(run(layout,intervals,5,2));
    }
}
