package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import java.util.*;

/**
 * A rest or steady-flow certificate (plan section 3.3): the evidence that an island's interval map is the
 * identity (REST) or stationary (STEADY), and the arithmetic that advances the island without solving.
 * In memory only; a restart starts every island awake.
 *
 * <p>Replay is arithmetic on the last solved interval, never a model evaluation: from the base tick b to a tick
 * t the island holds {@code base + f * delta} with {@code f = (t - b) / D} in every finite node, component and
 * internal energy, and the boundary transfers, pump work and pipe history of the replayed span are the recorded
 * ones scaled by the replayed fraction. States are the base states. Conservation is exact by construction
 * because the recorded interval conserved and the scaling is linear. Solids never move under replay: the entry
 * evidence requires unchanged node solids, and a STEADY island carries no solids and has no filter.
 */
public final class IslandCertificate {
    public enum Kind {REST,STEADY}

    /** One accepted solved interval, with the graph it started from. */
    public record Interval(long startTick,long endTick,PassiveNetwork before,PassiveIntervalSolver.Result result) {
        public Interval {
            Objects.requireNonNull(before);Objects.requireNonNull(result);
            if(startTick<0||endTick<=startTick)throw new IllegalArgumentException("Invalid certified interval");
        }
    }

    /** What the evidence compares, derived once per solved interval. Row n is null for a node that owns no stock. */
    static final class Summary {
        final long startTick,endTick;final int durationTicks;
        final PassiveNetwork before,after;final PassiveIntervalSolver.Result result;
        final double[][] moles;final double[] energy;final int[] phases;
        /** The largest relative change of any finite node's temperature or pressure over the interval. */
        final double stateChange;
        final boolean fullAcceptance,transitionFree,closuresUnchanged,solidsUnchanged,phasesUnchanged,exactZero,grossWithoutNet,solidsInTransport,hasFilter;
        Summary(Interval interval) {
            startTick=interval.startTick;endTick=interval.endTick;durationTicks=Math.toIntExact(endTick-startTick);
            before=interval.before;result=interval.result;after=result.graph();
            if(before.reservoirs().size()!=after.reservoirs().size()||before.pipes().size()!=after.pipes().size())throw new IllegalArgumentException("Interval changed the island's structure");
            int nodes=after.reservoirs().size();moles=new double[nodes][];energy=new double[nodes];phases=new int[nodes];
            boolean zero=true,solids=true,phase=true;double state=0;
            for(int n=0;n<nodes;n++) {
                var a=before.reservoirs().get(n);var b=after.reservoirs().get(n);
                if(a.id()!=b.id()||a.kind()!=b.kind())throw new IllegalArgumentException("Interval changed the island's structure");
                if(b.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                phases[n]=phases(b.state());if(phases(a.state())!=phases[n])phase=false;
                // An empty vessel's state is a numerical guess, not a physical temperature or pressure.
                if(!a.empty()&&!b.empty())state=Math.max(state,Math.max(Math.abs(b.state().temperature()-a.state().temperature())/b.state().temperature(),
                        Math.abs(b.state().pressure()-a.state().pressure())/b.state().pressure()));
                var m0=a.inventory().moles();var m1=b.inventory().moles();moles[n]=new double[m1.length];
                for(int c=0;c<m1.length;c++){moles[n][c]=m1[c]-m0[c];if(moles[n][c]!=0)zero=false;}
                energy[n]=b.inventory().internalEnergy()-a.inventory().internalEnergy();if(energy[n]!=0)zero=false;
                if(!a.inventory().solids().equals(b.inventory().solids()))solids=false;
            }
            for(double q:result.averageMassFlows())if(q!=0)zero=false;
            if(result.pumpWorkJoule()!=0)zero=false;
            for(var boundary:result.boundaries()){for(double n:boundary.moles())if(n!=0)zero=false;if(boundary.totalEnergyJoule()!=0||!boundary.solids().empty())zero=false;}
            boolean closures=true,filter=false;
            for(int p=0;p<after.pipes().size();p++) {
                var x=before.pipes().get(p);var y=after.pipes().get(p);
                if(x.id()!=y.id()||x.blockedDirections()!=y.blockedDirections()||!Objects.equals(x.filter(),y.filter()))closures=false;
                if(y.filter()!=null)filter=true;
            }
            boolean transport=false,gross=false;
            for(var pipe:result.pipeTransfers()) {
                if(!pipe.forward().solids().empty()||!pipe.reverse().solids().empty())transport=true;
                if(pipe.forward().massKg()!=0||pipe.reverse().massKg()!=0)gross=true;
            }
            fullAcceptance=result.acceptance()==PassiveStepSolver.Acceptance.FULL;transitionFree=transitionFree(result.rejectionReasons());
            closuresUnchanged=closures;solidsUnchanged=solids;phasesUnchanged=phase;solidsInTransport=transport;hasFilter=filter;stateChange=state;
            // Rest is the identity of the map: nothing may move, not even back and forth inside the interval.
            exactZero=zero&&!gross;grossWithoutNet=zero&&gross;
        }
        /** Which of vapour, hydrocarbon liquid and free water a node holds. */
        static int phases(com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.State state) {
            return (state.vaporVolume()>0?1:0)|(state.liquidVolume()>0?2:0)|(state.waterVolume()>0?4:0);
        }
        double largestFlow(){double q=0;for(double v:result.averageMassFlows())q=Math.max(q,Math.abs(v));return q;}
    }
    /**
     * The energy a node's internal energy is compared with: its magnitude, but at least the thermal scale
     * {@code n R T}, since internal energy is measured from a reference state and may pass near zero where a
     * relative test on it alone would demand an absurdly small change.
     */
    static double energyScale(PassiveNetwork.Reservoir node) {
        double n=0;for(double v:node.inventory().moles())n+=v;
        return Math.max(Math.abs(node.inventory().internalEnergy()),n*8.314462618*node.state().temperature());
    }

    /** A solver rejection that records a solid transport transition inside the interval, as opposed to ordinary
     * step control or a closure the interval already started with (reported again at its own t = 0). */
    static boolean transitionFree(Map<String,Integer> reasons) {
        for(var key:reasons.keySet()) {
            if(key.startsWith("Solid transport transition"))return false;
            int at=key.lastIndexOf("; t=");if(at<0)continue;
            try{if(Double.parseDouble(key.substring(at+4))>0)return false;}catch(NumberFormatException unreadable){return false;}
        }
        return true;
    }

    /**
     * Why the later interval is not a stationary repeat of the earlier one, or null when it is: equal duration,
     * both FULL with no transition and no closure, cake, phase or node-solid change inside either interval, equal
     * modes, blocked masks, filter cakes and fixed-node states, no finite node's temperature or pressure drifting
     * by more than {@code tolerance} per interval (two identity maps excepted), every finite node's component
     * change within {@code tolerance} of its inventory and energy change within {@code tolerance} of its
     * {@link #energyScale}, and every pipe's flow within {@code tolerance} of the island's largest flow.
     */
    static String stationaryRefusal(Summary a,Summary b,double tolerance) {
        if(a.durationTicks!=b.durationTicks)return "interval lengths differ";
        if(!a.fullAcceptance||!b.fullAcceptance)return "an interval was not FULL";
        if(!a.transitionFree||!b.transitionFree)return "a solid transport transition";
        if(!a.closuresUnchanged||!b.closuresUnchanged)return "a closure or filter cake changed";
        if(!a.solidsUnchanged||!b.solidsUnchanged)return "node solids changed";
        if(!a.phasesUnchanged||!b.phasesUnchanged||!Arrays.equals(a.phases,b.phases))return "a phase appeared or disappeared";
        if(!a.result.endpointModes().equals(b.result.endpointModes()))return "endpoint modes changed";
        // A node whose temperature or pressure drifts is not stationary even at a constant rate of change (pump
        // heating, a slow depletion). Two identity maps are exempt: their states differ at most by decode roundoff.
        if(!(a.exactZero&&b.exactZero)&&Math.max(a.stateChange,b.stateChange)>tolerance)return "a node's temperature or pressure changed by "+Math.max(a.stateChange,b.stateChange)+" per interval";
        var x=a.after;var y=b.after;
        if(x.reservoirs().size()!=y.reservoirs().size()||x.pipes().size()!=y.pipes().size())return "structure changed";
        for(int p=0;p<y.pipes().size();p++) {
            var s=x.pipes().get(p);var t=y.pipes().get(p);
            if(s.id()!=t.id()||s.blockedDirections()!=t.blockedDirections()||!Objects.equals(s.filter(),t.filter()))return "a closure or filter cake changed";
        }
        for(int n=0;n<y.reservoirs().size();n++) {
            var s=x.reservoirs().get(n);var t=y.reservoirs().get(n);
            if(s.id()!=t.id()||s.kind()!=t.kind())return "structure changed";
            if(t.fixed()&&(!s.inventory().equals(t.inventory())||Double.doubleToLongBits(s.state().temperature())!=Double.doubleToLongBits(t.state().temperature())
                    ||Double.doubleToLongBits(s.state().pressure())!=Double.doubleToLongBits(t.state().pressure())))return "a fixed node changed";
            if(b.moles[n]==null)continue;
            var inventory=t.inventory().moles();
            for(int c=0;c<inventory.length;c++)if(Math.abs(b.moles[n][c]-a.moles[n][c])>tolerance*inventory[c])return "node "+t.id()+" component "+c+" changed by "+Math.abs(b.moles[n][c]-a.moles[n][c])/Math.max(Double.MIN_VALUE,inventory[c])+" of its inventory";
            if(Math.abs(b.energy[n]-a.energy[n])>tolerance*energyScale(t))return "node "+t.id()+" energy changed by "+Math.abs(b.energy[n]-a.energy[n])/energyScale(t)+" of its scale";
        }
        var q1=a.result.averageMassFlows();var q2=b.result.averageMassFlows();double reference=b.largestFlow();
        for(int i=0;i<q2.length;i++)if(Math.abs(q2[i]-q1[i])>tolerance*Math.max(Math.abs(q2[i]),reference))return "pipe flow changed by "+Math.abs(q2[i]-q1[i])/Math.max(Double.MIN_VALUE,reference)+" of the largest flow";
        return null;
    }

    private final Kind kind;
    private final long baseTick,horizonTick;
    private final Summary summary;
    private IslandCertificate(Kind kind,Summary summary,long horizonTick){this.kind=kind;this.summary=summary;baseTick=summary.endTick;this.horizonTick=horizonTick;}

    /**
     * The certificate the last interval supports, or empty with the reason: REST when every change, flow, gross
     * pipe transfer, boundary transfer and pump work of the interval is exactly zero; none when the averages are
     * zero but something still moved back and forth (the average hides it); otherwise STEADY, which also needs no solids in
     * transport and no filter on the island, and a horizon of at least one interval: {@code min(K_max,
     * floor(budget / d))} intervals, d the largest relative per-interval change of any finite node component,
     * internal energy (against {@link #energyScale}), temperature or pressure, cut short before any
     * extrapolated component could reach zero. A constant boundary transfer cannot change
     * sign under linear replay, so no further limit is needed for fixed nodes.
     */
    static Result issue(Summary last,CertificatePolicy policy) {
        if(!last.fullAcceptance||!last.transitionFree||!last.closuresUnchanged||!last.solidsUnchanged||!last.phasesUnchanged)return Result.refused("the interval changed a closure, a cake, a phase or node solids");
        if(last.grossWithoutNet)return Result.refused("gross flow with a zero average is not rest");
        if(last.exactZero) {
            long horizon=policy.recheckSeconds()==0?Long.MAX_VALUE:Math.addExact(last.endTick,20L*policy.recheckSeconds());
            return Result.of(new IslandCertificate(Kind.REST,last,horizon));
        }
        if(last.solidsInTransport)return Result.refused("solids in transport");
        if(last.hasFilter)return Result.refused("a filter on the island");
        double relative=last.stateChange;long zeroLimit=Long.MAX_VALUE;
        for(int n=0;n<last.moles.length;n++) {
            if(last.moles[n]==null)continue;var node=last.after.reservoirs().get(n);var inventory=node.inventory().moles();
            relative=Math.max(relative,Math.abs(last.energy[n])/energyScale(node));
            for(int c=0;c<inventory.length;c++) {
                double delta=last.moles[n][c];if(delta==0)continue;
                if(inventory[c]<=0)return Result.refused("a component appears in an empty node");
                relative=Math.max(relative,Math.abs(delta)/inventory[c]);
                if(delta<0)zeroLimit=Math.min(zeroLimit,(long)Math.ceil(inventory[c]/-delta)-1);
            }
        }
        long intervals=policy.maximumIntervals();
        if(relative>0)intervals=Math.min(intervals,(long)Math.floor(policy.inventoryBudget()/relative));
        intervals=Math.min(intervals,zeroLimit);
        if(intervals<1)return Result.refused("drift of "+relative+" per interval exceeds the inventory budget");
        return Result.of(new IslandCertificate(Kind.STEADY,last,Math.addExact(last.endTick,Math.multiplyExact(intervals,last.durationTicks))));
    }
    /** A certificate, or the reason there is none. */
    record Result(IslandCertificate certificate,String refusal) {
        static Result of(IslandCertificate certificate){return new Result(certificate,null);}
        static Result refused(String reason){return new Result(null,reason);}
    }

    public Kind kind(){return kind;}
    /** The committed tick the certificate was issued at: the end of its qualifying interval. */
    public long baseTick(){return baseTick;}
    /** The last tick replay may reach; {@link Long#MAX_VALUE} for a REST certificate that is never rechecked. */
    public long horizonTick(){return horizonTick;}
    public int intervalTicks(){return summary.durationTicks;}
    Summary summary(){return summary;}
    /** The island's largest pipe flow in kg/s, which a STEADY certificate keeps replaying. */
    public double largestFlow(){return summary.largestFlow();}

    /** The island at tick t: base inventories plus the replayed fraction of the recorded change, base states. */
    PassiveNetwork graphAt(long tick) {
        if(tick<baseTick||tick>horizonTick)throw new IllegalArgumentException("Replay outside the certificate window");
        var base=summary.after;if(kind==Kind.REST||tick==baseTick)return base;
        double f=(tick-baseTick)/(double)summary.durationTicks;var nodes=new ArrayList<PassiveNetwork.Reservoir>(base.reservoirs().size());
        for(int n=0;n<base.reservoirs().size();n++) {
            var node=base.reservoirs().get(n);
            if(summary.moles[n]==null){nodes.add(node);continue;}
            var inventory=node.inventory();var moles=inventory.moles();
            for(int c=0;c<moles.length;c++)moles[c]+=f*summary.moles[n][c];
            nodes.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),
                    new PassiveNetwork.Inventory(inventory.volume(),moles,inventory.internalEnergy()+f*summary.energy[n],inventory.solids())));
        }
        return new PassiveNetwork(nodes,base.pipes(),base.scheduledTransfers());
    }
    /** The accounting of the replayed span [from, to]: the recorded interval scaled by its fraction, on the graph at {@code to}. */
    PassiveIntervalSolver.Result replay(long from,long to) {
        if(to<=from)throw new IllegalArgumentException("Empty replay");
        var recorded=summary.result;double g=(to-from)/(double)summary.durationTicks;
        var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>(recorded.boundaries().size());
        for(var b:recorded.boundaries()) {
            var moles=b.moles();for(int c=0;c<moles.length;c++)moles[c]*=g;
            boundaries.add(new ConservativeTransport.BoundaryTransfer(b.nodeId(),moles,g*b.totalEnergyJoule(),b.solids().scale(g),b.solidDirection()));
        }
        var pipes=new ArrayList<PipeTransfer>(recorded.pipeTransfers().size());
        for(var p:recorded.pipeTransfers())pipes.add(new PipeTransfer(p.pipeId(),scale(p.forward(),g),scale(p.reverse(),g)));
        return new PassiveIntervalSolver.Result(graphAt(to),(to-from)/20.0,recorded.averageMassFlows(),0,0,g*recorded.pumpWorkJoule(),boundaries,Map.of(),
                recorded.endpointModes(),recorded.endpointHeads(),recorded.acceptance(),pipes);
    }
    private static PipeTransfer.Stream scale(PipeTransfer.Stream stream,double g) {
        var phases=stream.phaseMoles();for(var phase:phases)for(int c=0;c<phase.length;c++)phase[c]*=g;
        var volumes=stream.phaseVolumes();for(int p=0;p<volumes.length;p++)volumes[p]*=g;
        return new PipeTransfer.Stream(g*stream.massKg(),phases,volumes,stream.solids().empty()?SolidInventory.EMPTY:stream.solids().scale(g));
    }
}
