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
    /** {@link #sample(FluidThermodynamics,PassiveNetwork,List,double[],double)} of a graph whose every end is BULK, which
     * needs no model. */
    static List<PipeTransfer> sample(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] rates,double duration) {
        return sample(null,graph,states,rates,duration);
    }
    /**
     * What each connection moved over {@code duration} at the given rates, in the phase split of the stream it drew: the
     * donor's whole state at a BULK end (every phase in proportion, solids included), only the vapour and water vapour
     * through a VAPOR port, only the hydrocarbon liquid, free water and solids through a LIQUID port, each scaled by the
     * moved mass over that stream's mass. A phase port whose phase is absent draws as a BULK end (decision A8). A graph
     * with a phase port needs the model for the stream masses.
     */
    static List<PipeTransfer> sample(FluidThermodynamics model,PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] rates,double duration) {
        var result=new ArrayList<PipeTransfer>();
        for(int i=0;i<rates.length;i++) {
            var pipe=graph.pipes().get(i);var state=states.get(rates[i]>=0?pipe.first():pipe.second());
            var port=pipe.drawPort(rates[i]);
            boolean vapor=port==PassiveNetwork.PhasePort.VAPOR&&FluidThermodynamics.holdsVapor(state);
            boolean liquidOnly=port==PassiveNetwork.PhasePort.LIQUID&&FluidThermodynamics.holdsLiquid(state);
            if((vapor||liquidOnly)&&model==null)throw new IllegalArgumentException("A phase port's transfer needs the model");
            double mass=Math.abs(rates[i])*duration,fraction=mass/(vapor?model.vaporMass(state):liquidOnly?model.liquidMass(state):state.mass());
            var liquid=state.liquid();var vapour=state.vapor();int count=liquid.length+1;
            double[][] n=new double[3][count];
            Stream moved;
            if(vapor) {
                for(int c=0;c<liquid.length;c++)n[2][c]=fraction*vapour[c];
                n[2][count-1]=fraction*state.waterVapor();
                moved=new Stream(mass,n,new double[]{0,0,fraction*state.vaporVolume()},SolidInventory.EMPTY);
            } else if(liquidOnly) {
                for(int c=0;c<liquid.length;c++)n[0][c]=fraction*liquid[c];
                n[1][count-1]=fraction*state.waterLiquid();
                moved=new Stream(mass,n,new double[]{fraction*state.liquidVolume(),fraction*state.waterVolume(),0},state.solids().scale(fraction));
            } else {
                for(int c=0;c<liquid.length;c++){n[0][c]=fraction*liquid[c];n[2][c]=fraction*vapour[c];}
                n[1][count-1]=fraction*state.waterLiquid();n[2][count-1]=fraction*state.waterVapor();
                moved=new Stream(mass,n,new double[]{fraction*state.liquidVolume(),fraction*state.waterVolume(),fraction*state.vaporVolume()},state.solids().scale(fraction));
            }
            var zero=zero(count);
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
