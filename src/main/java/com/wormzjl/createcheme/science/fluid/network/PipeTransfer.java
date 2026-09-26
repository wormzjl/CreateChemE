package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;

/** Gross transport history: reversals retain two nonnegative compositions. This is never owned stock. */
public record PipeTransfer(long pipeId, Stream forward, Stream reverse) {
    public PipeTransfer {Objects.requireNonNull(forward);Objects.requireNonNull(reverse);}

    /** Phase order is hydrocarbon liquid, free water, mixed vapor. Water is the final component. */
    public record Stream(double massKg, double[][] phaseMoles, double[] phaseVolumes,SolidInventory solids) {
        public Stream(double massKg,double[][] phaseMoles,double[] phaseVolumes){this(massKg,phaseMoles,phaseVolumes,SolidInventory.EMPTY);}
        public Stream {
            Objects.requireNonNull(solids);
            phaseMoles=copy(phaseMoles);phaseVolumes=phaseVolumes.clone();
            if(!Double.isFinite(massKg)||massKg<0||phaseMoles.length!=3||phaseVolumes.length!=3)throw new IllegalArgumentException("Invalid pipe history");
            int count=phaseMoles[0].length;
            for(int p=0;p<3;p++) {
                if(phaseMoles[p].length!=count||!Double.isFinite(phaseVolumes[p])||phaseVolumes[p]<0)throw new IllegalArgumentException("Invalid phase history");
                for(double n:phaseMoles[p])if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid transported amount");
            }
        }
        @Override public double[][] phaseMoles(){return copy(phaseMoles);}
        @Override public double[] phaseVolumes(){return phaseVolumes.clone();}
        public double[] componentMoles(){double[] n=new double[phaseMoles[0].length];for(var phase:phaseMoles)for(int c=0;c<n.length;c++)n[c]+=phase[c];return n;}
        private static double[][] copy(double[][] source){return Arrays.stream(source).map(double[]::clone).toArray(double[][]::new);}
    }

    /** One immutable empty stream per basis size instead of one per pipe and step; a Stream's arrays are copied in and
     * out, so sharing it is invisible. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer,Stream> ZERO=new java.util.concurrent.ConcurrentHashMap<>();
    private static Stream zero(int count){return ZERO.computeIfAbsent(count,c->new Stream(0,new double[3][c],new double[3]));}
    static List<PipeTransfer> sample(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] rates,double duration) {
        var result=new ArrayList<PipeTransfer>();
        for(int i=0;i<rates.length;i++) {
            var pipe=graph.pipes().get(i);var state=states.get(rates[i]>=0?pipe.first():pipe.second());
            double mass=Math.abs(rates[i])*duration,fraction=mass/state.mass();
            var liquid=state.liquid();var vapor=state.vapor();int count=liquid.length+1;
            double[][] n=new double[3][count];
            for(int c=0;c<liquid.length;c++){n[0][c]=fraction*liquid[c];n[2][c]=fraction*vapor[c];}
            n[1][count-1]=fraction*state.waterLiquid();n[2][count-1]=fraction*state.waterVapor();
            var moved=new Stream(mass,n,new double[]{fraction*state.liquidVolume(),fraction*state.waterVolume(),fraction*state.vaporVolume()},state.solids().scale(fraction));
            var zero=zero(count);
            result.add(new PipeTransfer(pipe.id(),rates[i]>=0?moved:zero,rates[i]>=0?zero:moved));
        }
        return List.copyOf(result);
    }

    /**
     * TR-BDF2's stage one (review 7.9): the gross history of a frozen rate
     * evaluation booked with a relaxed junction, matching {@code ConservativeTransport.reconstruct(..., relax)}.
     * Every junction that is not on a junction cycle in flow direction gets, on each outflow, a*sampled + b*share*In:
     * its own frozen sample weighted a = m/(m + relax Q_out) plus its share |q_e|/Q_out of the streams its inflows
     * delivered (a filter edge delivers its stream without the solids) weighted b = relax Q_out/(m + relax Q_out),
     * in junction flow order. The rate graph of TR-BDF2 carries no scheduled transfers, so none is excluded here.
     */
    static List<PipeTransfer> relaxed(PassiveNetwork graph,List<PipeTransfer> sampled,double[] rates,double relax,double[] molecularWeight) {
        int nodes=graph.reservoirs().size();boolean[] through=new boolean[nodes];
        for(int node=0;node<nodes;node++)through[node]=graph.reservoirs().get(node).junction();
        for(var transfer:graph.scheduledTransfers())through[transfer.node()]=false;
        int[] indegree=new int[nodes];var order=new ArrayList<Integer>();
        for(int edge=0;edge<rates.length;edge++){if(rates[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=rates[edge]>0?pipe.first():pipe.second(),receiver=rates[edge]>0?pipe.second():pipe.first();if(through[donor]&&through[receiver])indegree[receiver]++;}
        var queue=new ArrayDeque<Integer>();for(int node=0;node<nodes;node++)if(through[node]&&indegree[node]==0)queue.add(node);
        while(!queue.isEmpty()) {
            int node=queue.poll();order.add(node);
            for(int edge=0;edge<rates.length;edge++){if(rates[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=rates[edge]>0?pipe.first():pipe.second(),receiver=rates[edge]>0?pipe.second():pipe.first();if(donor==node&&through[receiver]&&--indegree[receiver]==0)queue.add(receiver);}
        }
        var streams=new ArrayList<Stream>();
        for(int edge=0;edge<rates.length;edge++){var t=sampled.get(edge);if(t.pipeId()!=graph.pipes().get(edge).id())throw new IllegalStateException("Pipe history order");streams.add(rates[edge]>=0?t.forward():t.reverse());}
        int count=streams.isEmpty()?0:streams.getFirst().phaseMoles[0].length;
        for(int node:order) {
            double outgoing=0;for(int edge=0;edge<rates.length;edge++){var pipe=graph.pipes().get(edge);if((rates[edge]>=0?pipe.first():pipe.second())==node)outgoing+=Math.abs(rates[edge]);}
            double mass=0;double[][] n=new double[3][count];double[] volumes=new double[3];var solids=new SolidInventory.Accumulator();
            for(int edge=0;edge<rates.length;edge++) {
                var pipe=graph.pipes().get(edge);if((rates[edge]>=0?pipe.second():pipe.first())!=node||rates[edge]==0)continue;
                var in=streams.get(edge);boolean filtered=pipe.filter()!=null;
                mass+=in.massKg-(filtered?in.solids.massKg():0);if(!filtered)solids.add(in.solids,1);
                for(int p=0;p<3;p++){volumes[p]+=in.phaseVolumes[p];for(int c=0;c<count;c++)n[p][c]+=in.phaseMoles[p][c];}
            }
            var inflow=solids.finish();
            var inventory=graph.reservoirs().get(node).inventory();var held=inventory.moles();double stock=inventory.solids().massKg();
            for(int c=0;c<held.length;c++)stock+=held[c]*molecularWeight[c];
            double a=stock/(stock+relax*outgoing),b=relax*outgoing/(stock+relax*outgoing);
            for(int edge=0;edge<rates.length;edge++) {
                var pipe=graph.pipes().get(edge);if((rates[edge]>=0?pipe.first():pipe.second())!=node||rates[edge]==0)continue;
                double share=Math.abs(rates[edge])/outgoing;double[][] m=new double[3][count];double[] v=new double[3];
                var own=rates[edge]>=0?sampled.get(edge).forward():sampled.get(edge).reverse();
                for(int p=0;p<3;p++){v[p]=a*own.phaseVolumes[p]+b*share*volumes[p];for(int c=0;c<count;c++)m[p][c]=a*own.phaseMoles[p][c]+b*share*n[p][c];}
                var solidPart=new SolidInventory.Accumulator();solidPart.add(own.solids,a);solidPart.add(inflow,b*share);
                streams.set(edge,new Stream(a*own.massKg+b*share*mass,m,v,solidPart.finish()));
            }
        }
        var result=new ArrayList<PipeTransfer>();
        for(int edge=0;edge<rates.length;edge++) {
            var t=sampled.get(edge);
            result.add(rates[edge]>=0?new PipeTransfer(t.pipeId(),streams.get(edge),t.reverse()):new PipeTransfer(t.pipeId(),t.forward(),streams.get(edge)));
        }
        return List.copyOf(result);
    }

    /** Bounded accumulator: one record per compiled pipe, regardless of accepted substep count. */
    static final class Accumulator {
        private final Map<Long,Mutable> pipes=new LinkedHashMap<>();
        void add(List<PipeTransfer> values,double weight) {
            if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("History quadrature weights must be nonnegative");
            for(var value:values) {
                var entry=pipes.computeIfAbsent(value.pipeId(),id->new Mutable(value.forward.phaseMoles[0].length));
                entry.add(value.forward,0,weight);entry.add(value.reverse,1,weight);
            }
        }
        List<PipeTransfer> snapshot(){return pipes.entrySet().stream().map(e->new PipeTransfer(e.getKey(),e.getValue().stream(0),e.getValue().stream(1))).toList();}
    }
    private static final class Mutable {
        private final double[] mass=new double[2];
        private final SolidInventory.Accumulator[] solids={new SolidInventory.Accumulator(),new SolidInventory.Accumulator()};
        private final double[][][] n;
        private final double[][] volumes=new double[2][3];
        private Mutable(int components){n=new double[2][3][components];}
        private void add(Stream value,int direction,double weight) {
            mass[direction]+=weight*value.massKg;solids[direction].add(value.solids,weight);
            for(int p=0;p<3;p++) {
                volumes[direction][p]+=weight*value.phaseVolumes[p];
                for(int c=0;c<n[direction][p].length;c++)n[direction][p][c]+=weight*value.phaseMoles[p][c];
            }
        }
        private Stream stream(int direction){return new Stream(mass[direction],n[direction],volumes[direction],solids[direction].finish());}
    }
}
