package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;

/** D12: the 32 MixedGasJunctionStaticTest cases, every result double printed, to diff base against D12. */
public class D12StaticCompare {
    static final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    static final PipeResistance.Geometry BORE=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
    static PhysicalFluidTopology.Device device(long id,int x,int y,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z),kind,PhysicalFluidTopology.Direction.EAST,BORE,new FlowControl.Passive());
    }
    public static void main(String[] args){
        for(boolean swap:new boolean[]{false,true})for(boolean unequal:new boolean[]{false,true})for(double pressure:new double[]{102325,150000})for(int count=3;count<=6;count++){
            int[][] ports={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
            var devices=new ArrayList<PhysicalFluidTopology.Device>();devices.add(device(100,0,0,0,Kind.PIPE));
            var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
            for(int i=0;i<count;i++){int[] port=ports[i];var d=device(i+1,port[0],port[1],port[2],i<2?Kind.GENERATOR:Kind.VOID);devices.add(d);
                double p=i==0?pressure:i==1?pressure-(unequal?(pressure>110000?5000:100):0):101325;int component=i<2?(((i==0)^swap)?0:MaterialTestBasis.NITROGEN):MaterialTestBasis.NITROGEN;
                double temperature=unequal&&d.kind()==Kind.GENERATOR?(d.id()==1?300:400):350;
                double[] n=new double[model.components().size()];n[component]=1;var unit=model.flashTP(temperature,p,n,()->{});n[component]/=unit.volume();
                boundaries.put(d.id(),new PassiveNetwork.Reservoir(d.id(),d.position().y(),model.flashTP(temperature,p,n,()->{}),d.kind()==Kind.GENERATOR?PassiveNetwork.NodeKind.GENERATOR:PassiveNetwork.NodeKind.VOID));}
            var graph=PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
            boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
            long solves=SolverDiagnostics.sample().value("newtonSolves");SolverDiagnostics.ENABLED=enabled;
            var sb=new StringBuilder(String.format(Locale.ROOT,"swap=%s unequal=%s P=%.0f ports=%d solves=%d acc=%d rej=%d modes=%s flows=%s",swap,unequal,pressure,count,solves,result.acceptedSubsteps(),result.rejectedSubsteps(),result.endpointModes(),Arrays.toString(result.averageMassFlows())));
            for(var node:result.graph().reservoirs())if(node.junction())sb.append(" J P=").append(node.state().pressure()).append(" T=").append(node.state().temperature()).append(" n=").append(Arrays.toString(node.inventory().moles()));
            System.out.println(sb);
        }
    }
}
