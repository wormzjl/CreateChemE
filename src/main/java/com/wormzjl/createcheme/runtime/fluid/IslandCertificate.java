package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A certificate (plan section 3.3; one kind since the backward-Euler basis, BE_INTEGRATOR_PLAN.md section 4): the
 * evidence that an island's interval map is stationary, carrying its per-interval change {@link #drift() d} (zero
 * for the identity map of an island at rest), and the arithmetic that advances the island without solving.
 * A checkpoint keeps it as a {@link Saved} record with its validity {@link Signature} (plan section 3.5); a
 * restart restores it while the signature still holds, and otherwise discards it and keeps the inventory.
 *
 * <p>Replay is arithmetic on the last solved interval, never a model evaluation: from the base tick b to a tick
 * t the island holds {@code base + f * delta} with {@code f = (t - b) / D} in every finite node (vessel or junction
 * holdup), component and energy field, and the boundary transfers, pump work and pipe history of the replayed span are the recorded
 * ones scaled by the replayed fraction. States are the base states. Conservation is exact by construction
 * because the recorded interval conserved and the scaling is linear. Solids never move under replay: the entry
 * evidence requires unchanged node solids, and an island on which anything moves carries no solids in transport and
 * has no filter.
 */
public final class IslandCertificate {

    /** One accepted solved interval, with the graph it started from. */
    public record Interval(long startTick,long endTick,PassiveNetwork before,PassiveIntervalSolver.Result result) {
        public Interval {
            Objects.requireNonNull(before);Objects.requireNonNull(result);
            if(startTick<0||endTick<=startTick)throw new IllegalArgumentException("Invalid certified interval");
        }
    }

    /**
     * What the evidence compares, derived once per solved interval. Row n is null for a node that owns no stock (a
     * generator, void or port). A vessel and a junction own stock: a junction's holdup m_J (BE_INTEGRATOR_PLAN.md
     * section 2) keeps its mass but moves its composition and energy, so its change enters the evidence, the drift,
     * the zero-crossing limit and the replay exactly like a vessel's.
     */
    static final class Summary {
        final Interval interval;
        final long startTick,endTick;final int durationTicks;
        final PassiveNetwork before,after;final PassiveIntervalSolver.Result result;
        final double[][] moles;final double[] energy;final int[] phases;
        /** The largest relative change of any finite node's (vessel's or junction's) temperature or pressure over the interval. */
        final double stateChange;
        /**
         * Per pipe, the fluid mass (kg) of the smaller finite inventory its flow draws on at the end of the
         * interval: the smaller of its endpoints' masses, a junction standing for the island's smallest vessel
         * inventory (its small holdup passes the flow on), a generator, void or port for none (infinity).
         */
        final double[] referenceMass;
        final boolean fullAcceptance,transitionFree,closuresUnchanged,solidsUnchanged,phasesUnchanged,exactZero,grossWithoutNet,solidsInTransport,hasFilter;
        /** {@code molecularWeights} (kg/mol, conserved-component order) weigh the finite inventories for {@link #referenceMass}. */
        Summary(Interval interval,double[] molecularWeights) {
            this.interval=interval;startTick=interval.startTick;endTick=interval.endTick;durationTicks=Math.toIntExact(endTick-startTick);
            before=interval.before;result=interval.result;after=result.graph();
            if(before.reservoirs().size()!=after.reservoirs().size()||before.pipes().size()!=after.pipes().size())throw new IllegalArgumentException("Interval changed the island's structure");
            int nodes=after.reservoirs().size();moles=new double[nodes][];energy=new double[nodes];phases=new int[nodes];
            var nodeMass=new double[nodes];double smallest=Double.POSITIVE_INFINITY;
            boolean zero=true,solids=true,phase=true;double state=0;
            for(int n=0;n<nodes;n++) {
                var a=before.reservoirs().get(n);var b=after.reservoirs().get(n);
                if(a.id()!=b.id()||a.kind()!=b.kind())throw new IllegalArgumentException("Interval changed the island's structure");
                nodeMass[n]=Double.POSITIVE_INFINITY;
                if(b.kind()!=PassiveNetwork.NodeKind.RESERVOIR&&!b.junction())continue;
                phases[n]=phases(b.state());if(phases(a.state())!=phases[n])phase=false;
                // An empty vessel's state is a numerical guess, not a physical temperature or pressure.
                if(!a.empty()&&!b.empty())state=Math.max(state,Math.max(Math.abs(b.state().temperature()-a.state().temperature())/b.state().temperature(),
                        Math.abs(b.state().pressure()-a.state().pressure())/b.state().pressure()));
                var m0=a.inventory().moles();var m1=b.inventory().moles();moles[n]=new double[m1.length];
                if(m1.length!=molecularWeights.length)throw new IllegalArgumentException("Molecular weights do not match the component basis");
                double mass=0;
                for(int c=0;c<m1.length;c++){moles[n][c]=m1[c]-m0[c];if(moles[n][c]!=0)zero=false;mass+=m1[c]*molecularWeights[c];}
                if(!b.junction()){nodeMass[n]=mass;smallest=Math.min(smallest,mass);}
                energy[n]=b.inventory().internalEnergy()-a.inventory().internalEnergy();if(energy[n]!=0)zero=false;
                if(!a.inventory().solids().equals(b.inventory().solids()))solids=false;
            }
            referenceMass=new double[after.pipes().size()];
            for(int p=0;p<referenceMass.length;p++) {
                var pipe=after.pipes().get(p);
                referenceMass[p]=Math.min(endpointMass(after,nodeMass,smallest,pipe.first()),endpointMass(after,nodeMass,smallest,pipe.second()));
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
        private static double endpointMass(PassiveNetwork graph,double[] nodeMass,double smallest,int node) {
            return graph.reservoirs().get(node).junction()?smallest:nodeMass[node];
        }
        double largestFlow(){double q=0;for(double v:result.averageMassFlows())q=Math.max(q,Math.abs(v));return q;}
        /**
         * The largest relative change of the interval: of any finite node's component (against that inventory),
         * internal energy (against {@link #energyScale}), temperature or pressure. Infinite when a component or
         * energy appears in a node that held none. This is the d of the plan's horizon {@code budget / d}.
         */
        double drift() {
            double relative=stateChange;
            for(int n=0;n<moles.length;n++) {
                if(moles[n]==null)continue;var node=after.reservoirs().get(n);var inventory=node.inventory().moles();
                if(energy[n]!=0){double scale=energyScale(node);if(scale<=0)return Double.POSITIVE_INFINITY;relative=Math.max(relative,Math.abs(energy[n])/scale);}
                for(int c=0;c<inventory.length;c++) {
                    double delta=moles[n][c];if(delta==0)continue;
                    if(inventory[c]<=0)return Double.POSITIVE_INFINITY;
                    relative=Math.max(relative,Math.abs(delta)/inventory[c]);
                }
            }
            return relative;
        }
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
            // An interval whose step control met the property domain's boundary is not replayed: the replay would carry
            // the island along a trajectory the solve itself could only just keep inside the data.
            if(key.startsWith(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation.REASON_PREFIX))return false;
            int at=key.lastIndexOf("; t=");if(at<0)continue;
            try{if(Double.parseDouble(key.substring(at+4))>0)return false;}catch(NumberFormatException unreadable){return false;}
        }
        return true;
    }

    /**
     * How far the later of two consecutive intervals is from a stationary repeat of the earlier one: the first
     * discrete difference, or null when there is none, and for each continuous test the smallest tolerance it
     * passes at. The pair is stationary at a tolerance {@code eps} exactly when {@code discrete} is null and every
     * measure is at most {@code eps} ({@link #refusal}); {@link #measure} is the smallest such {@code eps}.
     *
     * <ul>
     * <li>{@code discrete}: unequal durations; an interval not FULL, or with a solid transport transition, a
     *     closure, cake, phase or node-solid change inside it; unequal modes, phases, blocked masks, filter cakes
     *     or fixed-node states.</li>
     * <li>{@code state}: the larger interval's largest relative temperature or pressure drift of a finite node.
     *     A node whose state drifts at a constant rate (pump heating, a slow depletion) is not stationary. Zero
     *     between two identity maps, whose states differ at most by decode roundoff.</li>
     * <li>{@code component}: the largest change between the intervals of a finite node's component change,
     *     relative to that inventory.</li>
     * <li>{@code energy}: the same for internal energy, relative to {@link #energyScale}.</li>
     * <li>{@code flow}: per pipe the smaller of two ratios, the largest over the pipes. The first is the change
     *     of its flow relative to the larger of that flow and the island's largest flow. The second is the mass
     *     the pipe moves in one interval (the larger of the two intervals) relative to the smaller finite
     *     inventory it draws on ({@link Summary#referenceMass}): a pipe that moves less than {@code eps} of that
     *     inventory in both intervals is quiet, because whatever its flow does is already bounded by the
     *     component and energy tests of the inventories it moves between. Without it the relative test is
     *     ill-conditioned wherever the island's largest flow is itself solver noise (a settled closed island at
     *     1e-10 kg/s), since a noise flow's change is of the order of the flow itself. A pipe between two
     *     boundaries (a generator straight into a void) draws on no finite inventory and is never quiet.</li>
     * </ul>
     * {@code flowChange} and {@code flowMoves} are the two ratios of the pipe that sets {@code flow};
     * {@code largestRelativeFlowChange} is the largest first ratio over all pipes, the whole flow test before
     * quiet pipes were exempt, kept for diagnostics. The locations are for the refusal text only. Nothing here
     * decides accounting: replay scales the recorded interval, so conservation is exact whatever the tolerance.
     */
    public record Stationarity(String discrete,double state,double component,String componentAt,double energy,String energyAt,
                               double flow,String flowAt,double flowChange,double flowMoves,double largestRelativeFlowChange) {
        /** The smallest tolerance at which the pair counts as stationary; infinity after a discrete difference. */
        public double measure(){return discrete!=null?Double.POSITIVE_INFINITY:Math.max(Math.max(state,component),Math.max(energy,flow));}
        /** Why the pair is not stationary at {@code tolerance}, or null when it is. */
        public String refusal(double tolerance) {
            if(discrete!=null)return discrete;
            if(state>tolerance)return "a node's temperature or pressure changed by "+state+" per interval";
            if(component>tolerance)return componentAt+" changed by "+component+" of its inventory";
            if(energy>tolerance)return energyAt+" changed by "+energy+" of its scale";
            if(flow>tolerance)return "pipe flow changed by "+flowChange+" of the larger of its flow and the island's largest flow and moves "+flowMoves+" of the inventory it draws on per interval ("+flowAt+")";
            return null;
        }
    }
    static Stationarity stationarity(Summary a,Summary b) {
        String discrete=discreteDifference(a,b);
        if(discrete!=null)return new Stationarity(discrete,0,0,null,0,null,0,null,0,0,0);
        double state=a.exactZero&&b.exactZero?0:Math.max(a.stateChange,b.stateChange);
        var y=b.after;double component=0,energy=0;String componentAt=null,energyAt=null;
        for(int n=0;n<y.reservoirs().size();n++) {
            if(b.moles[n]==null)continue;var t=y.reservoirs().get(n);
            var inventory=t.inventory().moles();
            for(int c=0;c<inventory.length;c++) {
                double change=Math.abs(b.moles[n][c]-a.moles[n][c]);if(change==0)continue;
                double ratio=inventory[c]>0?change/inventory[c]:Double.POSITIVE_INFINITY;
                if(ratio>component){component=ratio;componentAt="node "+t.id()+" component "+c;}
            }
            double change=Math.abs(b.energy[n]-a.energy[n]);
            if(change!=0){double scale=energyScale(t);double ratio=scale>0?change/scale:Double.POSITIVE_INFINITY;if(ratio>energy){energy=ratio;energyAt="node "+t.id()+" energy";}}
        }
        var q1=a.result.averageMassFlows();var q2=b.result.averageMassFlows();double reference=b.largestFlow(),seconds=b.durationTicks/20.0;
        double flow=0,worstChange=0,moves=0,largest=0;String flowAt=null;
        for(int i=0;i<q2.length;i++) {
            double change=Math.abs(q2[i]-q1[i]);if(change==0)continue;
            double scale=Math.max(Math.abs(q2[i]),reference);
            double relative=scale>0?change/scale:Double.POSITIVE_INFINITY;
            double moved=Math.max(Math.abs(q1[i]),Math.abs(q2[i]))*seconds,mass=Math.min(a.referenceMass[i],b.referenceMass[i]);
            // No finite inventory behind the pipe (both ends boundaries) or an empty one: never quiet.
            double fraction=mass>0&&mass<Double.POSITIVE_INFINITY?moved/mass:Double.POSITIVE_INFINITY;
            double pipe=Math.min(relative,fraction);largest=Math.max(largest,relative);
            if(pipe>flow){flow=pipe;worstChange=relative;moves=fraction;flowAt="pipe "+y.pipes().get(i).id();}
        }
        return new Stationarity(null,state,component,componentAt,energy,energyAt,flow,flowAt,worstChange,moves,largest);
    }
    /** Everything that must repeat exactly between the two intervals, or null when it does. */
    private static String discreteDifference(Summary a,Summary b) {
        if(a.durationTicks!=b.durationTicks)return "interval lengths differ";
        if(!a.fullAcceptance||!b.fullAcceptance)return "an interval was not FULL";
        if(!a.transitionFree||!b.transitionFree)return "a solid transport transition or a thermo-domain rejection";
        if(!a.closuresUnchanged||!b.closuresUnchanged)return "a closure or filter cake changed";
        if(!a.solidsUnchanged||!b.solidsUnchanged)return "node solids changed";
        if(!a.phasesUnchanged||!b.phasesUnchanged||!Arrays.equals(a.phases,b.phases))return "a phase appeared or disappeared";
        if(!a.result.endpointModes().equals(b.result.endpointModes()))return "endpoint modes changed";
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
        }
        return null;
    }
    /** Why the later interval is not a stationary repeat of the earlier one at {@code tolerance}, or null when it is; see {@link Stationarity}. */
    static String stationaryRefusal(Summary a,Summary b,double tolerance){return stationarity(a,b).refusal(tolerance);}

    private final long baseTick,horizonTick;
    private final Summary summary;
    private IslandCertificate(Summary summary,long horizonTick){this.summary=summary;baseTick=summary.endTick;this.horizonTick=horizonTick;}

    /**
     * The certificate the last interval supports, or empty with the reason. When every change, flow, gross pipe
     * transfer, boundary transfer and pump work of the interval is exactly zero (the identity map, d = 0) its horizon
     * is the policy's recheck period, or {@link Long#MAX_VALUE} without one. None when the averages are zero but
     * something still moved back and forth (the average hides it). Otherwise it also needs no solids in transport and
     * no filter on the island, and a horizon of at least one interval: {@code min(K_max, floor(budget / d))}
     * intervals, d the largest relative per-interval change of any finite node component, internal energy (against
     * {@link #energyScale}), temperature or pressure, cut short before any extrapolated component could reach zero.
     * A constant boundary transfer cannot change sign under linear replay, so no further limit is needed for fixed
     * nodes.
     */
    static Result issue(Summary last,CertificatePolicy policy) {
        if(!last.fullAcceptance||!last.transitionFree||!last.closuresUnchanged||!last.solidsUnchanged||!last.phasesUnchanged)return Result.refused("the interval changed a closure, a cake, a phase or node solids");
        if(last.grossWithoutNet)return Result.refused("gross flow with a zero average is not rest");
        if(last.exactZero) {
            long horizon=policy.recheckSeconds()==0?Long.MAX_VALUE:Math.addExact(last.endTick,20L*policy.recheckSeconds());
            return Result.of(new IslandCertificate(last,horizon));
        }
        if(last.solidsInTransport)return Result.refused("solids in transport");
        if(last.hasFilter)return Result.refused("a filter on the island");
        double relative=last.drift();long zeroLimit=Long.MAX_VALUE;
        if(relative==Double.POSITIVE_INFINITY)return Result.refused("material or energy appears in an empty node");
        for(int n=0;n<last.moles.length;n++) {
            if(last.moles[n]==null)continue;var inventory=last.after.reservoirs().get(n).inventory().moles();
            for(int c=0;c<inventory.length;c++){double delta=last.moles[n][c];if(delta<0)zeroLimit=Math.min(zeroLimit,(long)Math.ceil(inventory[c]/-delta)-1);}
        }
        long intervals=policy.maximumIntervals();
        if(relative>0)intervals=Math.min(intervals,(long)Math.floor(policy.inventoryBudget()/relative));
        intervals=Math.min(intervals,zeroLimit);
        if(intervals<1)return Result.refused("drift of "+relative+" per interval exceeds the inventory budget");
        return Result.of(new IslandCertificate(last,Math.addExact(last.endTick,Math.multiplyExact(intervals,last.durationTicks))));
    }
    /** A certificate, or the reason there is none. */
    record Result(IslandCertificate certificate,String refusal) {
        static Result of(IslandCertificate certificate){return new Result(certificate,null);}
        static Result refused(String reason){return new Result(null,reason);}
    }

    // ---------------- persistence (plan section 3.5) ----------------

    /**
     * The validity signature of a certificate: the model's full property revision ({@link ApproximationAnchor#revision},
     * which also carries the velocity clamp, the trace cutoff and the solid settings), the six certificate policy values
     * it was issued under, and a SHA-256 digest of its island's graph identity - node ids, kinds, elevations and finite
     * volumes, fixed-node inventories and states, and pipe identities with their sections, controls, blocked masks, end
     * ports and filters. Replay repeats one solved interval of one graph under one model and one policy, so a certificate holds
     * only under the signature it was issued with; a saved one whose signature is not its island's current one is
     * discarded on load and the island keeps its inventory.
     */
    public record Signature(String propertyRevision,String policy,String graph) {
        public Signature {
            Objects.requireNonNull(propertyRevision);Objects.requireNonNull(policy);Objects.requireNonNull(graph);
            if(propertyRevision.isBlank()||propertyRevision.length()>4096||policy.isBlank()||policy.length()>512||!graph.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid certificate signature");
        }
        /** The signature a certificate of this graph gets under this model and policy. */
        public static Signature of(FluidThermodynamics model,CertificatePolicy policy,PassiveNetwork graph) {
            return new Signature(ApproximationAnchor.revision(model),policyValues(policy),graphIdentity(graph));
        }
        /** Which part of this signature differs from {@code current}, or null when none does. */
        public String difference(Signature current) {
            if(!propertyRevision.equals(current.propertyRevision))return "property revision";
            if(!policy.equals(current.policy))return "certificate policy ("+policy+" when saved, "+current.policy+" now)";
            if(!graph.equals(current.graph))return "graph identity";
            return null;
        }
    }
    /** The six policy values, exactly (doubles in hexadecimal), in a fixed order. */
    static String policyValues(CertificatePolicy policy) {
        return "restDetection="+policy.enabled()+";stationaryTolerance="+Double.toHexString(policy.stationaryTolerance())
                +";inventoryBudget="+Double.toHexString(policy.inventoryBudget())+";maximumIntervals="+policy.maximumIntervals()
                +";confirmIntervals="+policy.confirmIntervals()+";recheckSeconds="+policy.recheckSeconds();
    }
    /** The digest of what replay takes to be fixed about an island: everything but its finite inventories and states. */
    static String graphIdentity(PassiveNetwork graph) {
        var d=new Digest();d.text("createcheme-certificate-graph-identity-2");d.integer(graph.reservoirs().size());
        for(var node:graph.reservoirs()) {
            d.number(node.id());d.text(node.kind().name());d.real(node.elevation());
            // A junction's volume is a numerical placeholder that a solve may move; a vessel's is its identity.
            if(!node.junction())d.real(node.inventory().volume());
            if(node.fixed()) {
                var inventory=node.inventory();d.integer(inventory.moles().length);for(double n:inventory.moles())d.real(n);
                d.real(inventory.internalEnergy());d.solids(inventory.solids());d.real(node.state().temperature());d.real(node.state().pressure());
            }
        }
        d.integer(graph.pipes().size());
        for(var pipe:graph.pipes()) {
            d.number(pipe.id());d.integer(pipe.first());d.integer(pipe.second());d.integer(pipe.sections().size());
            for(var section:pipe.sections()){d.real(section.length());d.real(section.diameter());d.real(section.roughness());d.real(section.minorLoss());}
            switch(pipe.control()) {
                case FlowControl.Passive ignored->d.text("passive");
                case FlowControl.Pump pump->{d.text("pump");d.real(pump.targetVolumeFlow());d.real(pump.maximumAddedPressure());d.real(pump.efficiency());}
                case FlowControl.PressureValve valve->{d.text("valve");d.real(valve.targetPressure());}
            }
            d.integer(pipe.blockedDirections());
            // What each end draws (PassiveNetwork.PhasePort): it changes the equations, so a replay holds only under it.
            d.integer(pipe.firstPort().ordinal());d.integer(pipe.secondPort().ordinal());
            var filter=pipe.filter();
            if(filter==null)d.text("no filter");
            else{d.text("filter");d.real(filter.capacity());d.real(filter.cleanResistance());d.integer(filter.stoppedAtCapacity()?1:0);d.solids(filter.captured());d.real(filter.energyJoule());}
        }
        return d.hex();
    }
    /** A canonical SHA-256 stream: every value with its exact bits, every text with its length. */
    private static final class Digest {
        private final MessageDigest sha;private final ByteBuffer buffer=ByteBuffer.allocate(8);
        Digest(){try{sha=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
        void number(long value){buffer.clear();buffer.putLong(value);sha.update(buffer.array(),0,8);}
        void integer(int value){number(value);}
        void real(double value){number(Double.doubleToRawLongBits(value));}
        void text(String value){var bytes=value.getBytes(StandardCharsets.UTF_8);integer(bytes.length);sha.update(bytes);}
        void solids(SolidInventory solids){integer(solids.populations().size());for(var p:solids.populations()){text(p.material().id());text(p.size().metres());real(p.massKg());}}
        String hex(){return HexFormat.of().formatHex(sha.digest());}
    }

    /**
     * A certificate as a checkpoint keeps it: the tick its island first certified (a renewal keeps it), its horizon,
     * the solved interval it replays together with the graph that interval started from, and the signature it was
     * issued under. The interval's end is the certificate's base tick; its result's graph is the base graph.
     */
    public record Saved(long sinceTick,long horizonTick,Interval interval,Signature signature) {
        public Saved {
            Objects.requireNonNull(interval);Objects.requireNonNull(signature);
            if(sinceTick<0||sinceTick>interval.endTick()||horizonTick<=interval.endTick())
                throw new IllegalArgumentException("Saved certificate needs 0 <= since <= base < horizon: since "+sinceTick+", base "+interval.endTick()+", horizon "+horizonTick);
        }
        public long baseTick(){return interval.endTick();}
        @Override public String toString(){return "Saved[since "+sinceTick+", base "+baseTick()+", horizon "+horizonTick+", "+signature.graph().substring(0,12)+"]";}
    }
    /**
     * The replay arithmetic of a saved certificate, rebuilt from its interval without judging whether it still holds:
     * what a load materialises its island from, from the base tick to the saved committed tick. Refuses an interval
     * whose length disagrees with its result, a structure change inside it, or nonfinite per-interval deltas.
     */
    static IslandCertificate rebuild(Saved saved,double[] molecularWeights) {
        var interval=saved.interval();
        if(interval.result().advancedSeconds()!=(interval.endTick()-interval.startTick())/20.0)
            throw new IllegalArgumentException("Saved certificate interval ["+interval.startTick()+", "+interval.endTick()+"] disagrees with its result of "+interval.result().advancedSeconds()+" s");
        var summary=new Summary(interval,molecularWeights);
        for(int n=0;n<summary.moles.length;n++) {
            if(summary.moles[n]==null)continue;
            for(double delta:summary.moles[n])if(!Double.isFinite(delta))throw new IllegalArgumentException("Saved certificate has a nonfinite component delta at node "+summary.after.reservoirs().get(n).id());
            if(!Double.isFinite(summary.energy[n]))throw new IllegalArgumentException("Saved certificate has a nonfinite energy delta at node "+summary.after.reservoirs().get(n).id());
        }
        return new IslandCertificate(summary,saved.horizonTick());
    }
    /** A saved certificate judged for its island now: the certificate, or null with the reason it is discarded. */
    record Restored(IslandCertificate certificate,String discarded) {}
    /**
     * Whether a saved certificate still holds under the island's current model and policy: off when certificates are
     * off, discarded when any part of its signature differs. A matching signature with a horizon that the saved
     * interval does not give under that same policy is inconsistent data, and the load is refused.
     */
    static Restored restore(Saved saved,FluidThermodynamics model,CertificatePolicy policy) {
        if(!policy.enabled())return new Restored(null,"certificates are off (restDetection=false)");
        String difference=saved.signature().difference(Signature.of(model,policy,saved.interval().result().graph()));
        if(difference!=null)return new Restored(null,"its "+difference+" changed");
        var rebuilt=rebuild(saved,model.molecularWeights());
        var issued=issue(rebuilt.summary,policy);
        if(issued.certificate()==null||issued.certificate().horizonTick!=saved.horizonTick())
            throw new IllegalArgumentException("Saved certificate with horizon "+saved.horizonTick()+" is not the certificate its interval gives under its own signature: "
                    +(issued.certificate()==null?issued.refusal():"horizon "+issued.certificate().horizonTick));
        return new Restored(issued.certificate(),null);
    }
    /** The solved interval this certificate replays, with the graph it started from. */
    public Interval interval(){return summary.interval;}

    /**
     * The per-interval change d the horizon was set from ({@link Summary#drift}): zero for the identity map, where
     * every change, flow and transfer of the interval is exactly zero.
     */
    public double drift(){return summary.exactZero?0:summary.drift();}
    /** The committed tick the certificate was issued at: the end of its qualifying interval. */
    public long baseTick(){return baseTick;}
    /** The last tick replay may reach; {@link Long#MAX_VALUE} for an identity map that is never rechecked. */
    public long horizonTick(){return horizonTick;}
    public int intervalTicks(){return summary.durationTicks;}
    Summary summary(){return summary;}
    /** The island's largest pipe flow in kg/s, which the certificate keeps replaying (zero at rest). */
    public double largestFlow(){return summary.largestFlow();}

    /** The island at tick t: base inventories plus the replayed fraction of the recorded change, base states. */
    PassiveNetwork graphAt(long tick) {
        if(tick<baseTick||tick>horizonTick)throw new IllegalArgumentException("Replay outside the certificate window");
        var base=summary.after;if(summary.exactZero||tick==baseTick)return base;
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
