package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.transport.SolidMobility;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import java.util.*;

/** Worker-local strict transport events. Trial integrations never commit ownership. */
final class SolidEventIntegrator {
    /**
     * Every transport closure names both directions. A filter that has met its capacity carries
     * nothing either way; a bed that has settled out of a carrier too slow to suspend it is in the
     * connection, not in one end of it.
     *
     * <p>It is also what the step solver can actually work with. {@link PassiveStepSolver} presets
     * {@code boundaryClosed} only for a fully closed connection, so a one-directional closure left
     * the first Newton pass free to drive flow through the forbidden direction and to manufacture,
     * hop by hop, the solid dust that used to stall the line search; the active set then needed two
     * or three passes per solve to find its way back. Closing both puts it right on pass 0.
     * Measured over eleven warm repeats: a ten-reservoir chain 70 -> 51 ms and a thirty-reservoir
     * chain 215 -> 103 ms, against 226 -> 270 ms on the five-node pump-filter island, whose
     * closures isolate a junction and are re-examined more often.
     *
     * <p>What made this impossible before was that isolating a junction produced a singular block;
     * {@link com.wormzjl.createcheme.science.fluid.solver.PhaseLayout#junctionRows} now has a rule
     * for one. The next interval's rate pass reconsiders every closure from scratch either way, so
     * none of them is a permanent physical deposit.
     */
    private static final int BOTH_DIRECTIONS=3;
    private final FluidThermodynamics model;
    private final PassiveIntervalSolver integrator;
    private final PassiveStepSolver rates;
    SolidEventIntegrator(FluidThermodynamics model,PassiveIntervalSolver integrator) {
        this.model=model;this.integrator=integrator;rates=new PassiveStepSolver(model);
    }
    /**
     * A stage guard's report that the connection it names may no longer carry what the trajectory
     * asks it to. It is a control-flow signal out of a step solve, thrown once per rejected
     * substep while the interval solver refines onto the event, so it carries no stack trace.
     */
    static final class Transition extends RuntimeException {
        /** {@code direction} is the closure mask to apply, which is {@link #BOTH_DIRECTIONS} for
         * every transport closure; see there. */
        final int edge,direction;
        /** The connection's own identity, which is what a player sees on a device and what the
         * only durable record of a closure - the reason text - is looked up by. The edge index is
         * a position in one graph and means nothing outside it. */
        final long pipeId;
        final SolidMobility.Check check;
        /** Seen on the state at the start of a step: the event is at the accepted state
         * itself, so it needs no refinement and is declared where the integration stands. */
        final boolean atStart;
        Transition(int edge,long pipeId,int direction,SolidMobility.Check check,boolean atStart) {
            super("blocked with solid: "+check.reason()+"; pipe="+pipeId+"; velocity="+check.velocity()+"; deposition="+check.minimumVelocity());
            this.edge=edge;this.pipeId=pipeId;this.direction=direction;this.check=check;this.atStart=atStart;
        }
        SolidMobility.Reason reason(){return check.reason();}
        Transition atStart(){return atStart?this:new Transition(edge,pipeId,direction,check,true);}
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    private PassiveNetwork masks(PassiveNetwork graph,int[] masks) {
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<masks.length;i++)pipes.add(graph.pipes().get(i).withBlockedDirections(masks[i]));
        return new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());
    }
    private PassiveNetwork stopFilter(PassiveNetwork graph,int edge){var pipes=new ArrayList<>(graph.pipes());var p=pipes.get(edge);pipes.set(edge,p.withFilter(p.filter().stopped()));return new PassiveNetwork(graph.reservoirs(),pipes,graph.scheduledTransfers());}
    private PassiveStepSolver.Result rate(PassiveNetwork graph,Runnable checkpoint) {
        var nodes=graph.reservoirs().stream().map(n->new PassiveNetwork.Reservoir(n.id(),n.elevation(),n.state(),
                n.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:n.kind(),n.inventory())).toList();
        return rates.solveRate(new PassiveNetwork(nodes,graph.pipes()),checkpoint,PassiveStepSolver.Acceptance.FULL);
    }
    private Transition failed(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows) {
        Transition result=null;double ratio=Double.POSITIVE_INFINITY;long identity=Long.MAX_VALUE;
        var reach=populationReach(graph,states,flows);
        // One mobility preparation per donor node, not per connection leaving it: the carrier
        // viscosities, which every wet island pays whether or not it carries a particle, depend on
        // the donor alone. A node that is never a donor this pass is never prepared at all.
        var prepared=new SolidMobility.Donor[states.size()];
        Map<Integer,SolidMobility.Check> limited=null;
        for(int i=0;i<flows.length;i++) {
            var pipe=graph.pipes().get(i);double flow=flows[i];int direction=flow>=0?1:2;
            if(pipe.filter()!=null&&atCapacity(pipe.filter())&&pipe.blockedDirections()!=BOTH_DIRECTIONS)return clogged(i,pipe.id());
            if((pipe.blockedDirections()&direction)!=0)continue;
            int upstream=flow>=0?pipe.first():pipe.second();var donor=states.get(upstream);
            if(prepared[upstream]==null)prepared[upstream]=SolidMobility.donor(model,donor);
            var check=SolidMobility.check(prepared[upstream],donor,pipe,flow,SolidMobility.Outlet.MIXED);
            if(check.allowed()&&reach!=null&&flow!=0&&overPopulated(graph,pipe,flow,reach)) {
                check=new SolidMobility.Check(SolidMobility.Reason.POPULATION_LIMIT,check.velocity(),check.minimumVelocity());
                // A node receiver can be fed by several connections at once, and every one of them
                // is flagged although at most one of them has to stop; which one is decided over
                // the whole set, once it is known. A filter's cake has the one donor that fills it.
                if(pipe.filter()==null){(limited==null?limited=new LinkedHashMap<>():limited).put(i,check);continue;}
            }
            if(check.allowed())continue;
            double candidate=closureRatio(check);
            if(candidate<ratio||candidate==ratio&&pipe.id()<identity){result=new Transition(i,pipe.id(),BOTH_DIRECTIONS,check,false);ratio=candidate;identity=pipe.id();}
        }
        if(limited!=null)for(var entry:narrowLimits(graph,states,flows,reach,limited).entrySet()) {
            var pipe=graph.pipes().get(entry.getKey());double candidate=closureRatio(entry.getValue());
            if(candidate<ratio||candidate==ratio&&pipe.id()<identity){result=new Transition(entry.getKey(),pipe.id(),BOTH_DIRECTIONS,entry.getValue(),false);ratio=candidate;identity=pipe.id();}
        }
        return result;
    }
    /** How far a connection is from carrying what it is asked to, as the shared closure rule orders
     * it: the lowest ratio closes first, and a failure with no velocity of its own sorts ahead of
     * every one that has one. */
    private static double closureRatio(SolidMobility.Check check) {
        return check.minimumVelocity()>0?check.velocity()/check.minimumVelocity():-1;
    }
    /**
     * Which of an over-populated receiver's flagged connections actually has to stop: one per
     * receiver, and never one whose delivery the limit did not need to refuse.
     *
     * <p>Every open inbound connection of a receiver whose union does not fit is flagged, so the
     * shared rule - lowest velocity ratio, then lowest identity - could name a feed whose removal
     * leaves the union over the limit anyway. A receiver holding sixty grades, fed four new ones by
     * one connection and ten by another, closed the four-grade feed first and the ten-grade feed on
     * the next pass: two closures, one of them for a delivery that always fit. So the feed that
     * closes is the one contributing the most keys no other open inbound donor - and not the
     * receiver's own stock or injection - supplies; the rest stay open and are reconsidered from
     * scratch next pass like every other closure. Ties fall back to the shared rule, so the choice
     * is still decided by the graph rather than by iteration order.
     */
    private static Map<Integer,SolidMobility.Check> narrowLimits(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,
                                                                 List<SortedSet<SolidInventory.Key>> reach,Map<Integer,SolidMobility.Check> flagged) {
        var byReceiver=new LinkedHashMap<Integer,List<Integer>>();
        for(var edge:flagged.keySet()){var pipe=graph.pipes().get(edge);byReceiver.computeIfAbsent(flows[edge]>=0?pipe.second():pipe.first(),k->new ArrayList<Integer>()).add(edge);}
        var kept=new LinkedHashMap<Integer,SolidMobility.Check>();
        for(var entry:byReceiver.entrySet()) {
            var group=entry.getValue();
            if(group.size()==1){kept.put(group.getFirst(),flagged.get(group.getFirst()));continue;}
            int best=-1,exclusive=-1;double ratio=Double.POSITIVE_INFINITY;long identity=Long.MAX_VALUE;
            for(int edge:group) {
                var pipe=graph.pipes().get(edge);var elsewhere=suppliedWithout(graph,states,flows,reach,entry.getKey(),edge);
                int own=0;for(var key:reach.get(flows[edge]>=0?pipe.first():pipe.second()))if(!elsewhere.contains(key))own++;
                double candidate=closureRatio(flagged.get(edge));
                if(own>exclusive||own==exclusive&&(candidate<ratio||candidate==ratio&&pipe.id()<identity)){best=edge;exclusive=own;ratio=candidate;identity=pipe.id();}
            }
            kept.put(best,flagged.get(best));
        }
        return kept;
    }
    /** The keys this receiver would still be given without the named connection: its own stock and
     * injections, and the reach of every other open inbound donor, flagged or not. */
    private static SortedSet<SolidInventory.Key> suppliedWithout(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows,
                                                                 List<SortedSet<SolidInventory.Key>> reach,int receiver,int except) {
        var keys=new TreeSet<SolidInventory.Key>();
        for(var p:states.get(receiver).solids().populations())keys.add(p.key());
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in&&in.node()==receiver)
            for(var p:in.solidsPerSecond().populations())keys.add(p.key());
        for(int edge=0;edge<flows.length;edge++) {
            if(edge==except||flows[edge]==0)continue;
            var pipe=graph.pipes().get(edge);
            if(pipe.filter()!=null||pipe.blocked(flows[edge])||(flows[edge]>=0?pipe.second():pipe.first())!=receiver)continue;
            keys.addAll(reach.get(flows[edge]>=0?pipe.first():pipe.second()));
        }
        return keys;
    }
    /**
     * The conserved population keys that can reach each node over one step, or {@code null} when
     * this island cannot put more than {@link SolidInventory#MAXIMUM_POPULATIONS} of them anywhere.
     *
     * <p>The reconstruction's population system has only positive coefficients, so a stock reaches
     * every node connected to it by open connections a filter does not empty - not only its
     * immediate receivers - and the union that decides whether an inventory can be built is the
     * transitive one. A node whose stock the reconstruction does not rebuild, which is every fixed
     * node, neither receives nor forwards; it answers with its own inventory throughout.
     *
     * <p>The bound that skips all of this is the island's population count <em>with</em>
     * duplicates: if the whole island holds 64 or fewer, no union of them can exceed 64. That is
     * one field read per node, so an ordinary island never pays for the closure.
     */
    private static List<SortedSet<SolidInventory.Key>> populationReach(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows) {
        int held=0;
        for(var state:states)held+=state.solids().populations().size();
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in)held+=in.solidsPerSecond().populations().size();
        if(held<=SolidInventory.MAXIMUM_POPULATIONS)return null;
        var reach=new ArrayList<SortedSet<SolidInventory.Key>>(states.size());
        for(var state:states){var keys=new TreeSet<SolidInventory.Key>();for(var p:state.solids().populations())keys.add(p.key());reach.add(keys);}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in)
            for(var p:in.solidsPerSecond().populations())reach.get(in.node()).add(p.key());
        for(int pass=0;pass<states.size();pass++) {
            boolean changed=false;
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);
                if(pipe.filter()!=null||flows[edge]==0||pipe.blocked(flows[edge]))continue;
                int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
                if(graph.reservoirs().get(receiver).fixed())continue;
                changed|=reach.get(receiver).addAll(reach.get(donor));
            }
            if(!changed)break;
        }
        return reach;
    }
    /** Whether delivering this connection's flow puts more distinct populations than a conserved
     * stock can hold into what receives it: the far reservoir, or an inline filter's own cake. */
    private static boolean overPopulated(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow,List<SortedSet<SolidInventory.Key>> reach) {
        int donor=flow>=0?pipe.first():pipe.second(),receiver=flow>=0?pipe.second():pipe.first();
        if(pipe.filter()!=null) {
            var union=new TreeSet<>(reach.get(donor));
            for(var p:pipe.filter().captured().populations())union.add(p.key());
            return union.size()>SolidInventory.MAXIMUM_POPULATIONS;
        }
        return !graph.reservoirs().get(receiver).fixed()&&reach.get(receiver).size()>SolidInventory.MAXIMUM_POPULATIONS;
    }
    private static Transition clogged(int edge,long pipeId) {
        return new Transition(edge,pipeId,BOTH_DIRECTIONS,new SolidMobility.Check(SolidMobility.Reason.FILTER_CLOGGED,0,0),false);
    }
    /**
     * Whether this cake has met its capacity and the connection must stop. The saturated inlet law
     * aims a hair below capacity so that an owned cake never exceeds it, so "met" is a relative
     * test rather than {@link InlineFilter#clogged()}'s exact one; a filter restored from a save
     * at or past capacity, and one already stopped there, answer the same way either way.
     */
    private static boolean atCapacity(InlineFilter state) {
        return state.clogged()||state.captured().volume()>=state.capacity()*(1-1e-9);
    }
    private StageGuard guard(PassiveNetwork graph) {
        return new StageGuard() {
            public void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes) {}
            @Override public void checkRate(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                // The graph this guard closes over is the one the segment started from, so its own
                // cakes are stale by the steps already accepted; the live ones arrive here.
                var full=cloggedFilter(filters);if(full!=null)throw full.atStart();
                var failure=failed(graph,states,flows);if(failure!=null)throw failure.atStart();
            }
            @Override public void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                // A step may not reach capacity: the exact test, so the saturated inlet law's own
                // landing a relative hair below it is an accepted step whose closure the next
                // step's t0 guard declares, while a step that overshot the remaining room - by
                // more than rounding absorbs, or on an island where the law is
                // not applied at all - is refused and the controller halves onto the fill.
                var over=overfilledFilter(filters);if(over!=null)throw over;
                checkFlow(states,modes,flows);
            }
            @Override public void checkFlow(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                var failure=failed(graph,states,flows);
                if(failure!=null)throw failure;
            }
            private Transition cloggedFilter(Map<Long,InlineFilter> filters) {
                return firstFilter(filters,SolidEventIntegrator::atCapacity);
            }
            private Transition overfilledFilter(Map<Long,InlineFilter> filters) {
                return firstFilter(filters,InlineFilter::clogged);
            }
            private Transition firstFilter(Map<Long,InlineFilter> filters,java.util.function.Predicate<InlineFilter> refused) {
                for(int i=0;i<graph.pipes().size();i++) {
                    var pipe=graph.pipes().get(i);
                    if(pipe.filter()==null||pipe.blockedDirections()==BOTH_DIRECTIONS)continue;
                    var state=filters.get(pipe.id());
                    if(state!=null&&refused.test(state))return clogged(i,pipe.id());
                }
                return null;
            }
        };
    }
    PassiveIntervalSolver.Result solve(PassiveNetwork initial,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint,double startingStep) {
        if(!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Positive finite interval required");
        var graph=InventoryEquilibrium.refresh(initial,model,checkpoint);
        int[] blocked=new int[graph.pipes().size()];
        for(int i=0;i<blocked.length;i++){var p=graph.pipes().get(i);var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());if(p.filter()!=null&&a.id()<0&&b.id()<0&&a.kind()==PassiveNetwork.NodeKind.GENERATOR&&b.kind()==PassiveNetwork.NodeKind.VOID)blocked[i]=BOTH_DIRECTIONS;}
        double elapsed=0,work=0,step=startingStep;int accepted=0,rejected=0;
        var reasons=new LinkedHashMap<String,Integer>();
        // Reconsider every closure on a new requested interval; none is a permanent physical deposit.
        // A closure the interval starts with is reported like any other: it is the same event, at
        // the interval's own t0 rather than inside it, and it is the only account of why a
        // connection that carried material last interval carries none in this one.
        for(int attempt=0;SolidMobility.requiresFull(model,graph)&&attempt<=2*blocked.length;attempt++) {
            checkpoint.run();graph=masks(graph,blocked);
            var candidate=rate(graph,checkpoint);var failure=failed(graph,candidate.states(),candidate.massFlows());
            if(failure==null)break;
            blocked[failure.edge]|=failure.direction;
            // A cake the last interval's final step left at capacity is declared here, at the next interval's t0, and
            // not by an in-interval guard: under backward Euler a 5 s interval is often a single step, so the saturated
            // inlet's landing is the interval's last step. Stop the filter exactly as a declared FILTER_CLOGGED
            // transition does below, so the stopped state (status, checkpoint) is the same either way (review 8.8 (a)).
            if(failure.reason()==SolidMobility.Reason.FILTER_CLOGGED&&graph.pipes().get(failure.edge).filter()!=null)graph=stopFilter(graph,failure.edge);
            reasons.merge(failure.getMessage()+"; t="+elapsed,1,Integer::sum);
            if(attempt==2*blocked.length)throw new SparseNewton.Nonconvergence("Solid closure active set did not settle");
        }
        double[] transported=new double[graph.pipes().size()];
        var histories=new PipeTransfer.Accumulator();var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        PassiveIntervalSolver.Result last=null;
        // One segment per closure. The interval solver stops its own step grid on the transition
        // and hands back the prefix it accepted, so the event is located by the same adaptive
        // controller that integrates the rest of the interval, at the same tolerance, and the
        // prefix is never re-integrated.
        for(int event=0;elapsed<duration&&event<=2*blocked.length+1;event++) {
            checkpoint.run();
            var start=masks(graph,blocked);double remaining=duration-elapsed;
            var prefix=integrator.integrateToTransition(start,remaining,settings,checkpoint,PassiveStepSolver.Acceptance.FULL,guard(start),step);
            var transition=prefix.transition();int stoppedFilter=-1;
            if(transition!=null) {
                blocked[transition.edge]|=transition.direction;
                if(transition.reason()==SolidMobility.Reason.FILTER_CLOGGED)stoppedFilter=transition.edge;
            }
            var result=prefix.result();
            if(result!=null) {
                double advanced=transition==null?remaining:result.advancedSeconds();
                elapsed+=advanced;last=result;graph=result.graph();accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();
                work+=result.pumpWorkJoule();boundaries.addAll(result.boundaries());histories.add(result.pipeTransfers(),1);
                result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
                var flows=result.averageMassFlows();for(int i=0;i<flows.length;i++)transported[i]+=advanced*flows[i];
                step=integrator.nextStepEstimate();
            }
            if(transition!=null)reasons.merge(transition.getMessage()+"; t="+elapsed,1,Integer::sum);
            if(stoppedFilter>=0)graph=stopFilter(graph,stoppedFilter);
            graph=masks(graph,blocked);
        }
        if(elapsed<duration||last==null)throw new SparseNewton.Nonconvergence("Solid event limit exhausted; no partial interval may commit");
        graph=masks(graph,blocked);
        for(int i=0;i<transported.length;i++)transported[i]/=duration;
        return new PassiveIntervalSolver.Result(graph,duration,transported,accepted,rejected,work,boundaries,reasons,
                last.endpointModes(),last.endpointHeads(),PassiveStepSolver.Acceptance.FULL,histories.snapshot());
    }
}