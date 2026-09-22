package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.transport.SolidMobility;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import java.util.*;

/** Worker-local strict transport events. Trial integrations never commit ownership. */
final class SolidEventIntegrator {
    /**
     * A filter that has met its capacity carries nothing either way, so its closure names both
     * directions. A failed mobility check names only the direction that failed: closing both would
     * put the Newton active set right on pass 0 - {@link PassiveStepSolver} presets
     * {@code boundaryClosed} only for a fully closed pipe - but it also isolates a junction whose
     * remaining connections are closed, and that is a singular block the step solver cannot
     * factor. Either way the next interval's rate pass reconsiders every closure from scratch, so
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
     * asks it to. It is a control-flow signal out of a stage solve, thrown once per rejected
     * substep while the interval solver refines onto the event, so it carries no stack trace.
     */
    static final class Transition extends RuntimeException {
        final int edge,direction;
        final SolidMobility.Check check;
        /** Seen on the endpoint rate at the start of a step: the event is at the accepted state
         * itself, so it needs no refinement and is declared where the integration stands. */
        final boolean atStart;
        Transition(int edge,int direction,SolidMobility.Check check,boolean atStart) {
            super("blocked with solid: "+check.reason()+"; velocity="+check.velocity()+"; deposition="+check.minimumVelocity());
            this.edge=edge;this.direction=direction;this.check=check;this.atStart=atStart;
        }
        SolidMobility.Reason reason(){return check.reason();}
        Transition atStart(){return atStart?this:new Transition(edge,direction,check,true);}
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
        for(int i=0;i<flows.length;i++) {
            var pipe=graph.pipes().get(i);double flow=flows[i];int direction=flow>=0?1:2;
            if(pipe.filter()!=null&&atCapacity(pipe.filter())&&pipe.blockedDirections()!=BOTH_DIRECTIONS)return clogged(i);
            if((pipe.blockedDirections()&direction)!=0)continue;
            var donor=states.get(flow>=0?pipe.first():pipe.second());
            var check=SolidMobility.check(model,donor,pipe,flow,SolidMobility.Outlet.MIXED);
            if(check.allowed())continue;
            double candidate=check.minimumVelocity()>0?check.velocity()/check.minimumVelocity():-1;
            if(candidate<ratio||candidate==ratio&&pipe.id()<identity){result=new Transition(i,direction,check,false);ratio=candidate;identity=pipe.id();}
        }
        return result;
    }
    private static Transition clogged(int edge) {
        return new Transition(edge,BOTH_DIRECTIONS,new SolidMobility.Check(SolidMobility.Reason.FILTER_CLOGGED,0,0),false);
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
    private TrBdf2StepSolver.StageGuard guard(PassiveNetwork graph) {
        return new TrBdf2StepSolver.StageGuard() {
            public void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes) {}
            @Override public boolean requiresStepDoubling(){return PassiveStepSolver.hasSolids(graph)||graph.pipes().stream().anyMatch(p->p.filter()!=null);}
            @Override public void checkRate(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                // The graph this guard closes over is the one the segment started from, so its own
                // cakes are stale by the steps already accepted; the live ones arrive here.
                var full=cloggedFilter(filters);if(full!=null)throw full.atStart();
                var failure=failed(graph,states,flows);if(failure!=null)throw failure.atStart();
            }
            @Override public void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                // A stage may not reach capacity: the exact test, so the saturated inlet law's own
                // landing a relative hair below it is an accepted step whose closure the next
                // step's t0 guard declares, while a step that overshot the remaining room - by
                // more than the stage-two extrapolation absorbs, or on an island where the law is
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
                    if(state!=null&&refused.test(state))return clogged(i);
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
        // Reconsider every closure on a new requested interval; none is a permanent physical deposit.
        for(int attempt=0;SolidMobility.requiresFull(model,graph)&&attempt<=2*blocked.length;attempt++) {
            checkpoint.run();graph=masks(graph,blocked);
            var candidate=rate(graph,checkpoint);var failure=failed(graph,candidate.states(),candidate.massFlows());
            if(failure==null)break;
            blocked[failure.edge]|=failure.direction;
            if(attempt==2*blocked.length)throw new SparseNewton.Nonconvergence("Solid closure active set did not settle");
        }
        double elapsed=0,work=0,step=startingStep;int accepted=0,rejected=0;
        double[] transported=new double[graph.pipes().size()];
        var histories=new PipeTransfer.Accumulator();var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var reasons=new LinkedHashMap<String,Integer>();PassiveIntervalSolver.Result last=null;
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