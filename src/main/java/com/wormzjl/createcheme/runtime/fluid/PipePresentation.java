package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import java.util.*;

/** Display-only reduction of accepted graph/history. No thermo calls, solver work or state mutation. */
public final class PipePresentation {
    private PipePresentation(){}
    public static FluidView.PipeInfo inspect(PhysicalFluidTopology.Device device,PassiveNetwork graph,
            List<PhysicalFluidTopology.View> mappings,List<PipeTransfer> history,double seconds,int components){
        if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid history interval");
        var pipes=new HashMap<Long,PassiveNetwork.Pipe>();for(var p:graph.pipes())pipes.put(p.id(),p);
        var transfers=new HashMap<Long,PipeTransfer>();for(var t:history)transfers.put(t.pipeId(),t);
        int node=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==device.id())node=i;
        boolean junction=device.kind()==TopologyCompiler.Kind.PIPE&&node>=0&&graph.reservoirs().get(node).junction();
        var ids=new LinkedHashSet<Long>();for(var mapping:mappings)if(pipes.containsKey(mapping.pipeId()))ids.add(mapping.pipeId());
        double divisor=junction?1:Math.max(1,ids.size());
        var total=new Sum(components);var metrics=new ArrayList<FluidView.PipeConnection>();boolean reversal=false;
        for(long id:ids){
            var pipe=pipes.get(id);var t=transfers.get(id);
            double mass=0,volume=0;boolean reverse=false;
            if(t!=null){
                mass=t.forward().massKg()+t.reverse().massKg();
                volume=volume(t.forward())+volume(t.reverse());reverse=t.reverse().massKg()>t.forward().massKg();
                reversal|=t.forward().massKg()>0&&t.reverse().massKg()>0;
                if(junction){
                    if(pipe.first()==node)total.add(t.forward(),1);
                    else if(pipe.second()==node)total.add(t.reverse(),1);
                }else{total.add(t.forward(),1/divisor);total.add(t.reverse(),1/divisor);}
            }
            double length=pipe.sections().stream().mapToDouble(PipeResistance.Geometry::length).sum();
            double difference=Math.abs(graph.reservoirs().get(pipe.first()).state().pressure()-graph.reservoirs().get(pipe.second()).state().pressure());
            metrics.add(new FluidView.PipeConnection(id,seconds>0?mass/seconds:0,
                seconds>0?volume/seconds/device.geometry().area():0,difference/length,reverse));
        }
        return new FluidView.PipeInfo(total.finish(),metrics,junction,reversal);
    }
    private static double volume(PipeTransfer.Stream stream){return Arrays.stream(stream.phaseVolumes()).sum()+stream.solids().volume();}
    private static final class Sum {
        private double mass;
        private final double[][] moles;
        private final double[] volumes=new double[3];
        private final SolidInventory.Accumulator solids=new SolidInventory.Accumulator();
        Sum(int count){moles=new double[3][count];}
        void add(PipeTransfer.Stream stream,double weight){
            mass+=stream.massKg()*weight;var n=stream.phaseMoles();var v=stream.phaseVolumes();
            for(int p=0;p<3;p++){volumes[p]+=v[p]*weight;for(int c=0;c<moles[p].length;c++)moles[p][c]+=n[p][c]*weight;}
            solids.add(stream.solids(),weight);
        }
        PipeTransfer.Stream finish(){return new PipeTransfer.Stream(mass,moles,volumes,solids.finish());}
    }
}
