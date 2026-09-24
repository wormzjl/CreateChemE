package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** Adaptive internal substeps. The requested simulated interval is independent of the caller's wall deadline. */
public final class PassiveIntervalSolver {
    private final TrBdf2StepSolver stepSolver;
    private final FluidThermodynamics model;
    public enum ErrorControl { EMBEDDED, STEP_DOUBLING }
    private static final class AccuracyRejection extends RuntimeException {
        private final double error;
        private AccuracyRejection(String message,double error){super(message);this.error=error;}
    }
    private final ErrorControl errorControl;
    public PassiveIntervalSolver(FluidThermodynamics model){this(model,ErrorControl.EMBEDDED);}
    public PassiveIntervalSolver(FluidThermodynamics model,ErrorControl errorControl){this(model,errorControl,SolverOwnership.confinedToCurrentThread());}
    /** Retained across island jobs: every nested workspace answers to the supplied latch. */
    public PassiveIntervalSolver(FluidThermodynamics model,ErrorControl errorControl,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);stepSolver=new TrBdf2StepSolver(model,ownership);this.errorControl=Objects.requireNonNull(errorControl);
    }
    public record Settings(double initialStep,double maximumStep,double relativeTolerance,int maximumAttempts) {
        public Settings {
            if(!Double.isFinite(initialStep)||initialStep<=0||!Double.isFinite(maximumStep)||maximumStep<initialStep
                    ||!Double.isFinite(relativeTolerance)||relativeTolerance<=0||maximumAttempts<1)throw new IllegalArgumentException("Invalid interval settings");
        }
        public static Settings defaults(){return new Settings(1,20,.001,1024);}
    }
    public record Result(PassiveNetwork graph,double advancedSeconds,double[] averageMassFlows,int acceptedSubsteps,int rejectedSubsteps,
                         double pumpWorkJoule,List<ConservativeTransport.BoundaryTransfer> boundaries,Map<String,Integer> rejectionReasons,
                         List<FlowControl.Mode> endpointModes,double[] endpointHeads,PassiveStepSolver.Acceptance acceptance,List<PipeTransfer> pipeTransfers) {
        public Result {averageMassFlows=averageMassFlows.clone();boundaries=List.copyOf(boundaries);rejectionReasons=Map.copyOf(rejectionReasons);endpointModes=List.copyOf(endpointModes);endpointHeads=endpointHeads.clone();Objects.requireNonNull(acceptance);pipeTransfers=List.copyOf(pipeTransfers);}
        @Override public double[] averageMassFlows(){return averageMassFlows.clone();}
        @Override public double[] endpointHeads(){return endpointHeads.clone();}
    }
    public Result solve(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint) {
        return solve(initial,duration,settings,checkpoint,settings.initialStep());
    }
    /**
     * Starts the adaptive controller from {@code startingStep} instead of {@code initialStep},
     * bounded by the interval and by {@code maximumStep}. A caller that solves the same island
     * repeatedly passes {@link #nextStepEstimate()} of the previous interval, so a quiet island
     * stops rediscovering its step size from 1 s at every interval boundary. Tolerances, error
     * criteria and the pipe term are unchanged.
     */
    public Result solve(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,double startingStep) {
        if(com.wormzjl.createcheme.science.fluid.transport.SolidMobility.monitored(initial)||model.solidSettings.immobileViscosity()<.002&&initial.reservoirs().stream().anyMatch(n->n.state().waterVolume()>0))return new SolidEventIntegrator(model,this).solve(initial,duration,settings,checkpoint,startingStep);
        return integrate(initial,duration,settings,checkpoint,PassiveStepSolver.Acceptance.FULL,TrBdf2StepSolver.StageGuard.NONE,startingStep);
    }
    public Result solveApproximate(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,TrBdf2StepSolver.StageGuard guard) {
        if(com.wormzjl.createcheme.science.fluid.transport.SolidMobility.requiresFull(model,initial))throw new ApproximationRejected("Solid/filter intervals require a full solve");
        TrBdf2StepSolver.StageGuard checked=(states,modes)->{
            for(var state:states)if(state.solidMoments().mass()>0||com.wormzjl.createcheme.science.fluid.transport.SolidMobility.immobileLiquid(model,state))throw new ApproximationRejected("Solid transition requires a full solve");
            guard.check(states,modes);
        };
        return integrate(initial,duration,settings,checkpoint,PassiveStepSolver.Acceptance.APPROXIMATE,checked,settings.initialStep());
    }
    /** The step the controller would try next after the last successful interval, before the
     * interval boundary truncated it; 0 until an interval has been integrated. */
    public double nextStepEstimate(){return nextStepEstimate;}
    private double nextStepEstimate;

    /**
     * An interval that a stage guard cut short, as the accepted prefix plus the transition that
     * stopped it. {@code result} is {@code null} when the transition was already there at the
     * interval's own t0 and nothing advanced, and its {@code advancedSeconds} is below the
     * requested duration otherwise, with {@code averageMassFlows} averaged over what it did
     * advance. No public path returns one: a partial interval may not commit, and the only caller
     * is {@link SolidEventIntegrator}, which closes the connection and integrates the remainder.
     */
    record Prefix(Result result,SolidEventIntegrator.Transition transition) {}
    /** Transition rejections spent refining one segment towards a single event. Each one halves the
     * step, so this is a bound on how far below the controller's own estimate the search may go
     * before the event is simply declared where the integration currently stands. */
    private static final int MAXIMUM_TRANSITION_REJECTIONS=40;
    private static final String TRANSITION_REJECTION="Solid transport transition inside the step";

    Result integrate(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,
                     TrBdf2StepSolver.StageGuard guard,double startingStep) {
        var prefix=run(initial,duration,settings,checkpoint,acceptance,guard,startingStep);
        // No caller of the public contract can handle a prefix, so a guard that declares a
        // transition on this path escapes exactly as it did before there was a prefix at all.
        if(prefix.transition()!=null)throw prefix.transition();
        return prefix.result();
    }
    /** {@link #integrate} with the prefix returned rather than thrown; see {@link Prefix}. */
    Prefix integrateToTransition(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,
                                 TrBdf2StepSolver.StageGuard guard,double startingStep) {
        return run(initial,duration,settings,checkpoint,acceptance,guard,startingStep);
    }
    private Prefix run(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,
                             TrBdf2StepSolver.StageGuard guard,double startingStep) {
        if(!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Positive finite interval required");
        if(!Double.isFinite(startingStep)||startingStep<=0)throw new IllegalArgumentException("Positive finite starting step required");
        PassiveNetwork accepted=acceptance==PassiveStepSolver.Acceptance.FULL?InventoryEquilibrium.refresh(initial,model,checkpoint):initial;
        // h is the controller's estimate; each attempt uses it truncated to the rest of the interval,
        // and that truncation must not be mistaken for a step the error controller chose.
        double elapsed=0,h=Math.min(startingStep,settings.maximumStep);int acceptedCount=0,rejectedCount=0,consecutiveRejects=0;PassiveStepSolver.Result last=null;
        double[] transferred=new double[initial.pipes().size()],grossTransferred=new double[initial.pipes().size()];
        double work=0;var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var pipeTransfers=new PipeTransfer.Accumulator();
        var rejectionReasons=new LinkedHashMap<String,Integer>();
        String lastRejection="";
        // A transition is declared rather than refined once the step that would contain it is this
        // short, so it is located to within one such step of where it physically is.
        double transitionFloor=Math.max(1e-6,1e-9*duration);
        // The controller's own estimate at the moment the search onto an event began. Refining onto
        // a transition halves the step down to the floor, and that final micro-step is a statement
        // about where the event is, not about what the trajectory can take: handing it to the next
        // segment made every closure cost another twenty accepted substeps climbing back out.
        int transitionRejects=0;double beforeTransition=0;SolidEventIntegrator.Transition declared=null;
        // Rejections the property domain decided: the last one, how many, and whether the latest rejection was one.
        com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation lastDomain=null;int domainRejections=0;boolean lastWasDomain=false;
        for(int attempt=0;attempt<settings.maximumAttempts&&elapsed<duration&&declared==null;attempt++) {
            checkpoint.run();double step=Math.min(h,duration-elapsed);
            try {
                var regimes=new RegimeTrace(guard,accepted);
                PassiveStepSolver.Result coarse=null;
                if(errorControl==ErrorControl.EMBEDDED&&initial.pipes().stream().noneMatch(pipe->pipe.control() instanceof FlowControl.PressureValve)) {
                    var trial=stepSolver.trial(accepted,step,checkpoint,acceptance,regimes);var full=trial.solution();coarse=full;
                    if(regimes.smooth()) {
                    double stateError=error(full.states(),trial.estimatedStates());
                    double pipeError=flowError(accepted,full.massFlows(),trial.estimatedMassFlows(),trial.estimatedMassFlows(),step,grossTransferred,duration);
                    double sourceError=initial.scheduledTransfers().isEmpty()?0:boundaryError(full.boundaries(),trial.estimatedBoundaries());
                    double error=Math.max(Math.max(stateError,pipeError),sourceError);
                    SolverDiagnostics.attempt(attempt,step,error<=settings.relativeTolerance,
                            sourceError>=Math.max(stateError,pipeError)?"boundary":pipeError>=stateError?"pipe":"state",error);
                    if(error>settings.relativeTolerance)throw new AccuracyRejection("Embedded "+(sourceError>=Math.max(stateError,pipeError)?"boundary":pipeError>=stateError?"pipe":"state")+" error "+error,error);
                    accepted=replace(accepted,full);last=full;elapsed+=step;acceptedCount++;consecutiveRejects=0;
                    work+=full.pumpWorkJoule();boundaries.addAll(full.boundaries());var flows=full.massFlows();
                    pipeTransfers.add(full.pipeTransfers(),1);
                    for(int i=0;i<transferred.length;i++){transferred[i]+=step*flows[i];grossTransferred[i]+=step*Math.abs(flows[i]);}
                    h=grow(h,step,error,settings);
                    continue;
                    }
                }
                var full=coarse!=null?coarse:stepSolver.solve(accepted,step,checkpoint,acceptance,regimes);
                var first=stepSolver.solve(accepted,step/2,checkpoint,acceptance,regimes);var middle=replace(accepted,first);
                var second=stepSolver.solve(middle,step/2,checkpoint,acceptance,regimes);
                // Order-two Richardson scaling requires a smooth phase/device regime throughout.
                // Across a transition, retain the entire coarse-versus-refined discrepancy.
                double error=Math.max(error(full.states(),second.states()),flowError(accepted,full.massFlows(),first.massFlows(),second.massFlows(),step,grossTransferred,duration))/(regimes.smooth()?3:1);
                if(!initial.scheduledTransfers().isEmpty())error=Math.max(error,boundaryError(full,first,second)/(regimes.smooth()?3:1));
                SolverDiagnostics.attempt(attempt,step,error<=settings.relativeTolerance,"refinement",error);
                if(error>settings.relativeTolerance)throw new AccuracyRejection("Step refinement error "+error,error);
                accepted=replace(accepted,second);last=second;elapsed+=step;acceptedCount+=2;consecutiveRejects=0;
                work+=first.pumpWorkJoule()+second.pumpWorkJoule();boundaries.addAll(first.boundaries());boundaries.addAll(second.boundaries());
                pipeTransfers.add(first.pipeTransfers(),1);pipeTransfers.add(second.pipeTransfers(),1);
                var q1=first.massFlows();var q2=second.massFlows();for(int i=0;i<transferred.length;i++){transferred[i]+=step*.5*(q1[i]+q2[i]);grossTransferred[i]+=step*.5*(Math.abs(q1[i])+Math.abs(q2[i]));}
                h=grow(h,step,error,settings);
            }catch(SolidEventIntegrator.Transition transition) {
                // A rejection kind of its own. The step is not accepted, so no accepted step ever
                // contains a stage past the transition; the search is a plain halving onto the
                // event, and it is separate from the accuracy rejections both in the substep
                // counts and in the consecutive-rejection limit, which answers for a step size the
                // error controller could not make work rather than for a physical regime boundary.
                SolverDiagnostics.count(SolverDiagnostics.solidTransitionRejections);
                if(transition.atStart||step<=transitionFloor||transitionRejects>=MAXIMUM_TRANSITION_REJECTIONS){declared=transition;break;}
                if(transitionRejects==0)beforeTransition=h;
                transitionRejects++;
                if(rejectionReasons.size()<8||rejectionReasons.containsKey(TRANSITION_REJECTION))rejectionReasons.merge(TRANSITION_REJECTION,1,Integer::sum);
                h=step*.5;
            }catch(SparseNewton.Nonconvergence|IllegalArgumentException|AccuracyRejection rejected) {
                lastRejection=String.valueOf(rejected.getMessage());
                // A trial the property domain refused - directly, or a Newton pass whose failing line search it
                // stopped - is counted under its own key (one per component, property and side, never folded into
                // "Other"), so a checkpoint, a view and the certificate's transition check all see it.
                var domain=SparseNewton.domainViolation(rejected);
                if(domain!=null){rejectionReasons.merge(domain.reasonKey(),1,Integer::sum);lastDomain=domain;domainRejections++;}
                else {
                    String reason=String.valueOf(rejected.getMessage()).replaceAll("[-+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][-+]?[0-9]+)?","#");
                    if(rejectionReasons.size()<8||rejectionReasons.containsKey(reason))rejectionReasons.merge(reason,1,Integer::sum);else rejectionReasons.merge("Other",1,Integer::sum);
                }
                lastWasDomain=domain!=null;
                rejectedCount++;consecutiveRejects++;h=step*(rejected instanceof AccuracyRejection accuracy?stepFactor(accuracy.error,settings.relativeTolerance,true):.5);
                // A short pipe can introduce the first liquid phase in less than a millisecond.
                // Permit bounded refinement through that transition; every accepted step still
                // meets the same error tolerance, attempt cap, and caller's wall deadline.
                if(consecutiveRejects>=20||elapsed+h==elapsed)throw new SparseNewton.Nonconvergence("Substep refinement exhausted: "+rejected.getMessage(),null,domainCause(lastWasDomain,lastDomain,domainRejections,rejectedCount));
            }
        }
        if(declared==null&&elapsed<duration)throw new SparseNewton.Nonconvergence("Interval substep limit; no partial interval may commit: advanced="+elapsed+" of "+duration+" s, accepted="+acceptedCount+", rejected="+rejectedCount+", reasons="+rejectionReasons+", last="+lastRejection,
                null,domainCause(lastWasDomain,lastDomain,domainRejections,rejectedCount));
        // A segment that ended on a declared transition hands the next one the estimate it held
        // before the search, not the micro-step the search finished on. The error controller still
        // rejects and halves if the post-closure transient needs it, and it does so from a step the
        // island had already qualified rather than from 1e-6 s.
        nextStepEstimate=declared!=null&&transitionRejects>0?beforeTransition:h;
        if(last==null)return new Prefix(null,Objects.requireNonNull(declared,"A completed interval always has a last substep"));
        // An unbroken interval advanced exactly what was requested and normalizes by it, bit for
        // bit as before; a prefix normalizes by what it did advance, so the caller's
        // advanced*averageMassFlows is still the transported mass.
        double normalizer=declared==null?duration:elapsed;
        for(int i=0;i<transferred.length;i++)transferred[i]/=normalizer;
        return new Prefix(new Result(accepted,elapsed,transferred,acceptedCount,rejectedCount,work,boundaries,rejectionReasons,last.modes(),last.devicePressureChanges(),acceptance,pipeTransfers.snapshot()),declared);
    }
    /**
     * The domain violation an interval that could not commit failed on, or null: its last rejection was a domain
     * refusal, or domain refusals were the dominant reason (more than half of the interval's rejections). An interval
     * that only met the domain on the way, and failed for another reason, is a numerical failure.
     */
    static com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation domainCause(boolean lastWasDomain,
            com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation lastDomain,int domainRejections,int rejected) {
        return lastWasDomain||2*domainRejections>rejected?lastDomain:null;
    }
    /** An interval boundary truncating an attempt is not evidence about the step size, so growth
     * applies to the controller's own estimate whenever the attempt was the truncated one. */
    private static double grow(double estimate,double step,double error,Settings settings) {
        return Math.min(settings.maximumStep,Math.max(estimate,step)*stepFactor(error,settings.relativeTolerance,false));
    }
    /** Average-flow defects are order two; use the measured error to approach the same
     * tolerance with a safety margin instead of repeatedly doubling and rejecting. */
    private static double stepFactor(double error,double tolerance,boolean rejected) {
        if(error==0)return rejected?.5:2;
        return Math.clamp(.8*Math.sqrt(tolerance/error),rejected?.1:.5,rejected?.8:2);
    }
    public static PassiveNetwork replace(PassiveNetwork graph,PassiveStepSolver.Result result) {
        var states=result.states();
        if(states.size()!=graph.reservoirs().size())throw new IllegalArgumentException("State count mismatch");
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<states.size();i++){var old=graph.reservoirs().get(i);reservoirs.add(new PassiveNetwork.Reservoir(old.id(),old.elevation(),states.get(i),old.kind(),result.inventories().get(i)));}
        return new PassiveNetwork(reservoirs,graph.pipes().stream().map(p->result.filters().containsKey(p.id())?p.withFilter(result.filters().get(p.id())):p).toList(),graph.scheduledTransfers());
    }
    private static double error(List<FluidThermodynamics.State> first,List<FluidThermodynamics.State> refined) {
        double error=0;
        for(int i=0;i<first.size();i++) {
            var a=first.get(i);var b=refined.get(i);
            error=Math.max(error,Math.abs(a.pressure()-b.pressure())/Math.max(100,b.pressure()));
            error=Math.max(error,Math.abs(a.temperature()-b.temperature())/Math.max(1,b.temperature()));
            error=Math.max(error,Math.abs(a.mass()-b.mass())/Math.max(1e-12,b.mass()));
            error=Math.max(error,Math.abs(a.vaporVolume()/a.volume()-b.vaporVolume()/b.volume()));
            error=Math.max(error,Math.abs(a.waterVolume()/a.volume()-b.waterVolume()/b.volume()));
            error=Math.max(error,Math.abs(a.liquidVolume()/a.volume()-b.liquidVolume()/b.volume()));
            error=Math.max(error,Math.abs(a.solidMoments().volume()/a.volume()-b.solidMoments().volume()/b.volume()));
            var populations=new java.util.HashSet<com.wormzjl.createcheme.science.fluid.state.SolidInventory.Key>();for(var p:a.solids().populations())populations.add(p.key());for(var p:b.solids().populations())populations.add(p.key());
            for(var key:populations){double firstMass=a.solids().mass(key),secondMass=b.solids().mass(key);error=Math.max(error,Math.max(0,Math.abs(firstMass-secondMass)-1e-10)/Math.max(1e-12,Math.max(firstMass,secondMass)));}
        }
        return error;
    }
    private static double flowError(PassiveNetwork graph,double[] full,double[] first,double[] second,double duration,double[] previousGross,double intervalDuration) {
        double maximum=0;
        for(int i=0;i<full.length;i++) {
            var edge=graph.pipes().get(i);double mean=.5*(first[i]+second[i]),gross=.5*(Math.abs(first[i])+Math.abs(second[i]));
            // A declared absolute allowance covers equation/roundoff noise near zero transfer.
            // It is below the BAL inventory-relative tolerance and does not relax conservation.
            double numericalFloor=1e-9+2e-9*(graph.reservoirs().get(edge.first()).state().mass()+graph.reservoirs().get(edge.second()).state().mass())/duration;
            // Scale by significant throughput over the requested interval as flow approaches zero.
            // Integral(max(q, priorGross/interval)) <= 2*totalGross, so this contributes at most
            // 2*tolerance of gross throughput, subject to reference qualification of the estimator.
            double scale=Math.max(gross,previousGross[i]/intervalDuration);
            maximum=Math.max(maximum,Math.max(0,Math.abs(full[i]-mean)-numericalFloor)/Math.max(1e-9,scale));
        }
        return maximum;
    }
    private static final class RegimeTrace implements TrBdf2StepSolver.StageGuard {
        private final TrBdf2StepSolver.StageGuard delegate;
        private final List<String> phases;
        private List<FlowControl.Mode> modes;
        private boolean smooth=true;
        private RegimeTrace(TrBdf2StepSolver.StageGuard delegate,PassiveNetwork initial) {
            this.delegate=delegate;
            phases=initial.reservoirs().stream().map(n->InventoryEquilibrium.regime(n.state())).toList();
        }
        @Override public void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> actualModes) {
            delegate.check(states,actualModes);
            if(modes==null)modes=List.copyOf(actualModes);else if(!modes.equals(actualModes))smooth=false;
            for(int i=0;i<states.size();i++)if(!phases.get(i).equals(InventoryEquilibrium.regime(states.get(i))))smooth=false;
        }
        @Override public void checkFlow(List<FluidThermodynamics.State> states,List<FlowControl.Mode> actualModes,double[] flows){check(states,actualModes);delegate.checkFlow(states,actualModes,flows);}
        @Override public void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> actualModes,double[] flows){check(states,actualModes);delegate.checkFilters(filters,states,actualModes,flows);}
        @Override public void checkRate(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> actualModes,double[] flows){check(states,actualModes);delegate.checkRate(filters,states,actualModes,flows);}
        private boolean smooth(){return smooth;}
    }
    private static double boundaryError(PassiveStepSolver.Result full,PassiveStepSolver.Result first,PassiveStepSolver.Result second) {
        var all=new ArrayList<>(first.boundaries());all.addAll(second.boundaries());return boundaryError(full.boundaries(),all);
    }
    private static double boundaryError(List<ConservativeTransport.BoundaryTransfer> full,List<ConservativeTransport.BoundaryTransfer> refined) {
        var coarse=boundaryTotals(full);var fine=boundaryTotals(refined);double maximum=0;var ids=new HashSet<>(coarse.keySet());ids.addAll(fine.keySet());
        for(long id:ids) {
            int length=coarse.containsKey(id)?coarse.get(id).length:fine.get(id).length;var b=fine.getOrDefault(id,new double[length]);var a=coarse.getOrDefault(id,new double[length]);
            for(int c=0;c<b.length;c++) {
                double floor=c==b.length-1?1e-4:1e-10;
                maximum=Math.max(maximum,Math.max(0,Math.abs(a[c]-b[c])-floor)/Math.max(floor,Math.abs(b[c])));
            }
        }
        return maximum;
    }
    private static Map<Long,double[]> boundaryTotals(List<ConservativeTransport.BoundaryTransfer> values) {
        var result=new HashMap<Long,double[]>();
        for(var value:values){var n=value.moles();var sum=result.computeIfAbsent(value.nodeId(),ignored->new double[n.length+1]);for(int c=0;c<n.length;c++)sum[c]+=n[c];sum[n.length]+=value.totalEnergyJoule();}
        return result;
    }
}
