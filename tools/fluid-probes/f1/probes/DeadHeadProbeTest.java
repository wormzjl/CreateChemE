package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Diagnostic probe (temporary): IslandCertificateTest's dead-headed pump, traced. */
class DeadHeadProbeTest {
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    @Test void trace() throws Exception {
        var n=new double[model.components().size()];n[MaterialTestBasis.NITROGEN]=1;
        var kinds=new Kind[]{Kind.RESERVOIR,Kind.PIPE,Kind.PUMP,Kind.PIPE,Kind.RESERVOIR};var devices=new ArrayList<PhysicalFluidTopology.Device>();
        for(int i=0;i<kinds.length;i++)devices.add(new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,0,0),kinds[i],PhysicalFluidTopology.Direction.EAST,BLOCK,kinds[i]==Kind.PUMP?new FlowControl.Pump(.001,100000,1):new FlowControl.Passive()));
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        stock.put(1L,new FluidDeviceSpec(1,298.15,101325,n).initialize(devices.get(0),model,()->{}));stock.put(5L,new FluidDeviceSpec(1,298.15,800000,n).initialize(devices.get(4),model,()->{}));
        var graph=PhysicalFluidTopology.compile(devices,stock).islands().stream().filter(i->i.physicalIds().size()==5).findFirst().orElseThrow().graph();
        String dir=System.getProperty("user.dir")+"/build/f1-trace/";java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
        for(boolean cap:new boolean[]{false,true})for(boolean projection:new boolean[]{false,true}){PassiveStepSolver.PROBE_CAP_BY_DRIVING=cap;PassiveStepSolver.PROBE_TRACE_PROJECTION=projection;
        try(var out=new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(dir+"deadhead-pump-cap"+cap+"-proj"+projection+".txt")))) {
            com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.TRACE=out::println;
            try{var r=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},.05);out.println("OK "+r.endpointModes()+" "+Arrays.toString(r.averageMassFlows()));}
            catch(RuntimeException e){out.println("HELD "+e.getMessage());}
        }finally{com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.TRACE=null;PassiveStepSolver.PROBE_CAP_BY_DRIVING=true;PassiveStepSolver.PROBE_TRACE_PROJECTION=true;}
        }
    }
}
