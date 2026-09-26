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
            var zero=new Stream(0,new double[3][count],new double[3]);
            result.add(new PipeTransfer(pipe.id(),rates[i]>=0?moved:zero,rates[i]>=0?zero:moved));
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
