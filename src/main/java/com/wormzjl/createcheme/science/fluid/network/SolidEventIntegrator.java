package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.transport.SolidMobility;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import java.util.*;

/** Worker-local strict transport events. Trial integrations never commit ownership. */
final class SolidEventIntegrator {
    private final FluidThermodynamics model;
    private final PassiveIntervalSolver integrator;
    private final PassiveStepSolver rates;
    SolidEventIntegrator(FluidThermodynamics model,PassiveIntervalSolver integrator) {
        this.model=model;this.integrator=integrator;rates=new PassiveStepSolver(model);
    }
    private static final class Transition extends RuntimeException {
        final int edge,direction;
        final SolidMobility.Reason reason;
        Transition(int edge,int direction,SolidMobility.Check check) {
            super("blocked with solid: "+check.reason()+"; velocity="+check.velocity()+"; deposition="+check.minimumVelocity());
            this.edge=edge;this.direction=direction;this.reason=check.reason();
        }
    }
    private record Probe(PassiveIntervalSolver.Result result,Transition transition) {}
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
            if(pipe.filter()!=null&&pipe.filter().clogged()&&pipe.blockedDirections()!=3)return new Transition(i,3,new SolidMobility.Check(SolidMobility.Reason.FILTER_CLOGGED,0,0));
            if((pipe.blockedDirections()&direction)!=0)continue;
            var donor=states.get(flow>=0?pipe.first():pipe.second());
            var check=SolidMobility.check(model,donor,pipe,flow,SolidMobility.Outlet.MIXED);
            if(check.allowed())continue;
            double candidate=check.minimumVelocity()>0?check.velocity()/check.minimumVelocity():-1;
            if(candidate<ratio||candidate==ratio&&pipe.id()<identity){result=new Transition(i,direction,check);ratio=candidate;identity=pipe.id();}
        }
        return result;
    }
    private TrBdf2StepSolver.StageGuard guard(PassiveNetwork graph) {
        return new TrBdf2StepSolver.StageGuard() {
            public void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes) {}
            @Override public boolean requiresStepDoubling(){return PassiveStepSolver.hasSolids(graph)||graph.pipes().stream().anyMatch(p->p.filter()!=null);}
            @Override public void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                for(int i=0;i<graph.pipes().size();i++){var p=graph.pipes().get(i);var state=filters.get(p.id());if(p.filter()!=null&&p.blockedDirections()!=3&&state!=null&&state.clogged())throw new Transition(i,3,new SolidMobility.Check(SolidMobility.Reason.FILTER_CLOGGED,0,0));}
                checkFlow(states,modes,flows);
            }
            @Override public void checkFlow(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows) {
                var failure=failed(graph,states,flows);
                if(failure!=null)throw failure;
            }
        };
    }
    PassiveIntervalSolver.Result solve(PassiveNetwork initial,double duration,PassiveIntervalSolver.Settings settings,Runnable checkpoint,double startingStep) {
        if(!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Positive finite interval required");
        var graph=InventoryEquilibrium.refresh(initial,model,checkpoint);
        int[] blocked=new int[graph.pipes().size()];
        for(int i=0;i<blocked.length;i++){var p=graph.pipes().get(i);var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());if(p.filter()!=null&&a.id()<0&&b.id()<0&&a.kind()==PassiveNetwork.NodeKind.GENERATOR&&b.kind()==PassiveNetwork.NodeKind.VOID)blocked[i]=3;}
        // Reconsider every closure on a new requested interval; none is a permanent physical deposit.
        for(int attempt=0;SolidMobility.requiresFull(model,graph)&&attempt<=2*blocked.length;attempt++) {
            checkpoint.run();graph=masks(graph,blocked);
            var candidate=rate(graph,checkpoint);var failure=failed(graph,candidate.states(),candidate.massFlows());
            if(failure==null)break;
            blocked[failure.edge]|=failure.direction;
            if(attempt==2*blocked.length)throw new SparseNewton.Nonconvergence("Solid closure active set did not settle");
        }
        // A tight event bracket must use an adequately resolved hydraulic trajectory.
        var tight=new PassiveIntervalSolver.Settings(Math.min(settings.initialStep(),0.001),
                settings.maximumStep(),Math.min(settings.relativeTolerance(),1e-6),settings.maximumAttempts());
        double elapsed=0,work=0;int accepted=0,rejected=0;
        double[] transported=new double[graph.pipes().size()];
        var histories=new PipeTransfer.Accumulator();var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var reasons=new LinkedHashMap<String,Integer>();PassiveIntervalSolver.Result last=null;
        for(int event=0;elapsed<duration&&event<=2*blocked.length+1;event++) {
            checkpoint.run();
            var start=masks(graph,blocked);double remaining=duration-elapsed;
            var stageGuard=guard(start);
            java.util.function.DoubleFunction<Probe> replay=seconds->{
                try{return new Probe(integrator.integrate(start,seconds,tight,checkpoint,PassiveStepSolver.Acceptance.FULL,stageGuard,tight.initialStep()),null);}
                catch(Transition transition){return new Probe(null,transition);}
            };
            Probe attempt;
            try{attempt=new Probe(integrator.integrate(start,remaining,settings,checkpoint,PassiveStepSolver.Acceptance.FULL,stageGuard,startingStep),null);}
            catch(Transition transition){attempt=replay.apply(remaining);}
            double advanced=remaining;int stoppedFilter=-1;
            if(attempt.transition()!=null) {
                var bracket=ThresholdEventLocator.locate(
                        new ThresholdEventLocator.Sample<>(0,true,new Probe(null,null)),
                        new ThresholdEventLocator.Sample<>(remaining,false,attempt),
                        seconds->{var trial=replay.apply(seconds);return new ThresholdEventLocator.Sample<>(seconds,trial.transition()==null,trial);},
                        ThresholdEventLocator.Settings.defaults(),checkpoint);
                var transition=bracket.blocked().value().transition();
                blocked[transition.edge]|=transition.direction;
                if(transition.reason==SolidMobility.Reason.FILTER_CLOGGED)stoppedFilter=transition.edge;
                reasons.merge(transition.getMessage(),1,Integer::sum);
                advanced=bracket.safe().seconds();attempt=bracket.safe().value();
                if(advanced==0){if(stoppedFilter>=0)graph=stopFilter(graph,stoppedFilter);graph=masks(graph,blocked);continue;}
            }
            var result=Objects.requireNonNull(attempt.result());
            elapsed+=advanced;last=result;graph=result.graph();if(stoppedFilter>=0)graph=stopFilter(graph,stoppedFilter);accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();
            work+=result.pumpWorkJoule();boundaries.addAll(result.boundaries());histories.add(result.pipeTransfers(),1);
            result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            var flows=result.averageMassFlows();for(int i=0;i<flows.length;i++)transported[i]+=advanced*flows[i];
        }
        if(elapsed<duration||last==null)throw new SparseNewton.Nonconvergence("Solid event limit exhausted; no partial interval may commit");
        graph=masks(graph,blocked);
        for(int i=0;i<transported.length;i++)transported[i]/=duration;
        return new PassiveIntervalSolver.Result(graph,duration,transported,accepted,rejected,work,boundaries,reasons,
                last.endpointModes(),last.endpointHeads(),PassiveStepSolver.Acceptance.FULL,histories.snapshot());
    }
}