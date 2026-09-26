package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PipeTransfer;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.network.InlineFilter;

/** Read-only presentation of a committed interval; a null state denotes a zero-holdup pipe segment. */
public record FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,
                        double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,
                        boolean hydraulicOwner,double intervalSeconds,String intervalQuality,List<PipeRoute> pipeRoutes,InlineFilter filter,PipeInfo pipeInfo,
                        List<Outlet> outlets) {
    /** A tank has six faces and each face one neighbour, so it has at most six connections. */
    public static final int MAXIMUM_OUTLETS=6;
    /** Source-side convenience for devices without pipe inspection, not a wire-format fallback. */
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,boolean hydraulicOwner,double intervalSeconds,String intervalQuality,List<PipeRoute> pipeRoutes,InlineFilter filter){
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,hydraulicOwner,intervalSeconds,intervalQuality,pipeRoutes,filter,null,List.of());
    }
    public FluidView withPipeInfo(PipeInfo info){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,hydraulicOwner,intervalSeconds,intervalQuality,pipeRoutes,filter,info,outlets);
    }
    public FluidView withOutlets(List<Outlet> list){
        return new FluidView(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,hydraulicOwner,intervalSeconds,intervalQuality,pipeRoutes,filter,pipeInfo,list);
    }
    /**
     * One connection of a tank, as its page lists it (plan 4.3 under decision D11): the port the connection's end at the
     * tank compiled to ({@code VAPOR} top, {@code BULK} side, {@code LIQUID} bottom), the net flow out of the tank through
     * it over the last completed interval (kg/s and mol/s; negative is inflow), the phase that led what it drew ({@link
     * #NONE} when it drew nothing, else the phase index of {@link PipeTransfer.Stream}: 0 hydrocarbon liquid, 1 free
     * water, 2 vapour), and, at a bottom port, the level head of the committed state (Pa; 0 elsewhere).
     */
    public record Outlet(long pipeId,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort port,double massFlow,double moleFlow,int drawn,double head) {
        public static final int NONE=-1;
        public Outlet {
            Objects.requireNonNull(port);
            if(!Double.isFinite(massFlow)||!Double.isFinite(moleFlow)||drawn<NONE||drawn>2||!Double.isFinite(head)||head<0)throw new IllegalArgumentException("Invalid tank outlet");
        }
    }
    /** Nonnegative interval transport; a junction counts outgoing material once, not every incident edge. */
    public record PipeInfo(PipeTransfer.Stream contents,List<PipeConnection> connections,boolean junction,boolean changedDirection){
        public PipeInfo {
            Objects.requireNonNull(contents);connections=List.copyOf(connections);
            if(connections.size()>12||connections.stream().map(PipeConnection::pipeId).distinct().count()!=connections.size())
                throw new IllegalArgumentException("Invalid pipe inspection connections");
        }
        public static PipeInfo empty(int components){return new PipeInfo(new PipeTransfer.Stream(0,new double[3][components],new double[3]),List.of(),false,false);}
    }
    /** Speed is the previous-five-online-seconds bulk speed at this block's bore; gradient is endpoint pressure difference / path length. */
    public record PipeConnection(long pipeId,double massRateKgPerSecond,double velocityMetresPerSecond,double pressureDropPascalPerMetre,boolean reverse){
        public PipeConnection {
            for(double value:new double[]{massRateKgPerSecond,velocityMetresPerSecond,pressureDropPascalPerMetre})
                if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid pipe inspection metric");
        }
    }
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange,boolean hydraulicOwner,double intervalSeconds,String intervalQuality,List<PipeRoute> pipeRoutes){this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,hydraulicOwner,intervalSeconds,intervalQuality,pipeRoutes,null);}
    public record PipeRoute(long pipeId,String first,String second) {}
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory,Double devicePressureChange) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,devicePressureChange,true,0,"",List.of());
    }
    public FluidView(long identity,long inputRevision,long committedTick,long onlineTick,String status,State state,double massFlow,List<PipeTransfer> pipeHistory) {
        this(identity,inputRevision,committedTick,onlineTick,status,state,massFlow,pipeHistory,null);
    }
    public FluidView {
        Objects.requireNonNull(status);Objects.requireNonNull(intervalQuality);pipeHistory=List.copyOf(pipeHistory);pipeRoutes=List.copyOf(pipeRoutes);outlets=List.copyOf(outlets);
        if(outlets.size()>MAXIMUM_OUTLETS)throw new IllegalArgumentException("Too many tank outlets");
    }
    public record State(double pressure,double temperature,double volume,double mass,double[] phaseVolumes,double[][] phaseMoles,SolidInventory solids) {
        public State(double pressure,double temperature,double volume,double mass,double[] phaseVolumes,double[][] phaseMoles){this(pressure,temperature,volume,mass,phaseVolumes,phaseMoles,SolidInventory.EMPTY);}
        public State {Objects.requireNonNull(solids);phaseVolumes=phaseVolumes.clone();phaseMoles=Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);}
        @Override public double[] phaseVolumes(){return phaseVolumes.clone();}
        @Override public double[][] phaseMoles(){return Arrays.stream(phaseMoles).map(double[]::clone).toArray(double[][]::new);}
        static State from(FluidThermodynamics.State s) {
            double[][] n=new double[3][s.liquid().length+1];var l=s.liquid();var v=s.vapor();
            for(int c=0;c<l.length;c++){n[0][c]=l[c];n[2][c]=v[c];}n[1][l.length]=s.waterLiquid();n[2][l.length]=s.waterVapor();
            return new State(s.pressure(),s.temperature(),s.volume(),s.mass(),new double[]{s.liquidVolume(),s.waterVolume(),s.vaporVolume()},n,s.solids());
        }
    }
    public interface Receiver {
        long fluidIdentity();
        void acceptFluidView(FluidView view);
    }
}
