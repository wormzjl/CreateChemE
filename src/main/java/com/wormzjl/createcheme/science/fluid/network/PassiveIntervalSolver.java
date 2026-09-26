package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * Adaptive internal substeps. The requested simulated interval is independent of the caller's wall deadline.
 *
 * <p>Every step is one backward-Euler step, {@link PassiveStepSolver#solve}: one Newton with its active-set passes, one
 * exact conservative reconstruction, the equation gate and the conservation audit. A converged step is accepted when no
 * vessel's pressure or mass changed by more than {@link #STATE_CAP} of itself. The basis and its measurements are in
 * documentation/2026-09-24-mixed-gas-junction/HANDOFF_REVIEW.md 8.4-8.9 and the batch's BE_INTEGRATOR_PLAN.md: against
 * the second-order TR-BDF2 it replaced, one Newton solve per step instead of two plus an embedded companion, no rate
 * solve per step, and zero nonconvergence on the mixed-gas junction matrices where TR-BDF2 failed on its rate solve;
 * trajectories are first order (a decay lags by up to one 5 s cycle), which the owner accepted (decision D4).
 */
public final class PassiveIntervalSolver {
    /**
     * The largest relative change of a vessel's (a {@code RESERVOIR} node's) pressure, over max(100 Pa, P0), or mass an
     * accepted step may make. Junctions are not in the measure: their pressure is algebraic (the Newton's net-mass row)
     * and their mass is pinned. 0.02 and 0.05 gave the same zero nonconvergence; 0.05 takes fewer steps (review 8.6 (e)).
     */
    static final double STATE_CAP=.05;
    private final PassiveStepSolver implicit;
    private final FluidThermodynamics model;
    /** A converged step the state-change controller refused. */
    private static final class StateChangeRejection extends RuntimeException {
        private StateChangeRejection(String message){super(message);}
    }
    public PassiveIntervalSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    /** Retained across island jobs: every nested workspace answers to the supplied latch. */
    public PassiveIntervalSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);implicit=new PassiveStepSolver(model,Objects.requireNonNull(ownership));
    }
    /** {@code relativeTolerance} is not read by the backward-Euler controller, whose step bound is {@link #STATE_CAP}. */
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
     * repeatedly may pass {@link #nextStepEstimate()} of the previous interval. The island runtime
     * does not: it starts every ordinary interval at {@code min(initialStep, duration)}, a function
     * of the interval alone, so that a certified replay is bitwise (see {@link #replayStart}).
     */
    public Result solve(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,double startingStep) {
        initial=PassiveNetwork.sizeJunctionHoldups(initial,model);
        if(com.wormzjl.createcheme.science.fluid.transport.SolidMobility.monitored(initial)||model.solidSettings.immobileViscosity()<.002&&initial.reservoirs().stream().anyMatch(n->n.state().waterVolume()>0))return new SolidEventIntegrator(model,this).solve(initial,duration,settings,checkpoint,startingStep);
        return integrate(initial,duration,settings,checkpoint,PassiveStepSolver.Acceptance.FULL,StageGuard.NONE,startingStep);
    }
    public Result solveApproximate(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,StageGuard guard) {
        initial=PassiveNetwork.sizeJunctionHoldups(initial,model);
        if(com.wormzjl.createcheme.science.fluid.transport.SolidMobility.requiresFull(model,initial))throw new ApproximationRejected("Solid/filter intervals require a full solve");
        StageGuard checked=(states,modes)->{
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
    private static final String REOPEN_REJECTION="Backward-Euler boundary reopened";

    Result integrate(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,
                     StageGuard guard,double startingStep) {
        var prefix=run(initial,duration,settings,checkpoint,acceptance,guard,startingStep);
        // No caller of the public contract can handle a prefix, so a guard that declares a
        // transition on this path escapes exactly as it did before there was a prefix at all.
        if(prefix.transition()!=null)throw prefix.transition();
        return prefix.result();
    }
    /** {@link #integrate} with the prefix returned rather than thrown; see {@link Prefix}. */
    Prefix integrateToTransition(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,
                                 StageGuard guard,double startingStep) {
        return run(initial,duration,settings,checkpoint,acceptance,guard,startingStep);
    }
    /**
     * The interval, stepped by backward Euler under the state-change controller.
     *
     * <ul>
     * <li><b>Acceptance.</b> A converged step is accepted when the largest relative change of a vessel's pressure (over
     * max(100 Pa, P0)) or mass (over m0) is at most {@link #STATE_CAP}; otherwise it is refused and the step halved. A
     * change that halving did not reduce - at least 0.9 of the refused change at twice the step - is an algebraic jump of
     * the node's state, not a trajectory error, and the step that shows it is accepted (water entering a dry gas tank
     * drops its pressure about 5 % at any step size, review 8.6 (b)). A step at or below the transition floor is accepted
     * whatever its change. There is no rule on accepted-mode transitions: a symmetric pair of void edges opens and closes
     * together, so a one-transition rule halved every event to the floor and drove a resting line into the rounding
     * floor of its rows (review 8.6 (c) and (f)).</li>
     * <li><b>Growth.</b> After an accepted step the estimate grows by {@code clamp(0.9 sqrt(cap/change), 0.5, 2)} (2 at
     * zero change), bounded by the maximum step. A Newton failure, an equation-gate or conservation failure halves.</li>
     * <li><b>Boundary reopen.</b> A converged step that holds a passive run closed although its own end states drive the
     * run in an allowed direction ({@link PassiveStepSolver#reopenable}) is refused and solved again with that run
     * exempt from the start-of-solve closures; each run at most once per attempted step (review 8.8 (c)).</li>
     * <li><b>Cold start.</b> While no step of this structure has been accepted (after compile, a topology change or a
     * job start with no committed interval on it), one rate solve of the port graph supplies the junctions' starting
     * states; the balanced pressure seed treats vessel neighbours as held (review 8.6 (b) additions 1 and 2).</li>
     * <li><b>Events.</b> The stage guard's {@code checkRate} runs at every step start, on the previous accepted endpoint,
     * else on the endpoint carried from the last interval while no connection's identity or closure changed, else (a
     * guard that can declare something, first step) on one rate solve of the port graph; {@code checkFilters} runs on
     * every step's end. A declared transition is searched onto by halving, exactly as before.</li>
     * <li><b>Limits.</b> The attempt cap and the twenty-consecutive-rejection limit; transition refusals have their own
     * bound. An exhausted interval states its rejection map (a domain approach ends on twenty rejections, review 8.8 (a)).</li>
     * </ul>
     */
    private Prefix run(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,
                       StageGuard guard,double startingStep) {
        if(!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Positive finite interval required");
        initial=PassiveNetwork.sizeJunctionHoldups(initial,model);
        if(!Double.isFinite(startingStep)||startingStep<=0)throw new IllegalArgumentException("Positive finite starting step required");
        PassiveNetwork accepted=acceptance==PassiveStepSolver.Acceptance.FULL?InventoryEquilibrium.refresh(initial,model,checkpoint):initial;
        // h is the controller's estimate; each attempt uses it truncated to the rest of the interval.
        double elapsed=0,h=Math.min(startingStep,settings.maximumStep);int acceptedCount=0,rejectedCount=0,consecutiveRejects=0;PassiveStepSolver.Result last=null;
        double[] transferred=new double[initial.pipes().size()];
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
        // The last refused state change and its step, for the algebraic-jump test.
        double previousStateChange=0,previousStateStep=0;
        // Runs already reopened for the attempted step (same t0 and step).
        var reopened=new HashSet<PassiveNetwork.Pipe.Identity>();double reopenElapsed=-1,reopenStep=-1;
        // Rejections the property domain decided: the last one, how many, and whether the latest rejection was one.
        com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation lastDomain=null;int domainRejections=0;boolean lastWasDomain=false;
        for(int attempt=0;attempt<settings.maximumAttempts&&elapsed<duration&&declared==null;attempt++) {
            checkpoint.run();double step=Math.min(h,duration-elapsed);
            try {
                if(accepted.reservoirs().stream().anyMatch(n->n.kind()==PassiveNetwork.NodeKind.PORT))throw new IllegalArgumentException("Internal ports cannot be integrated");
                // The start-of-step guard reads the previous accepted endpoint, which is this step's t0.
                if(last!=null)guard.checkRate(liveFilters(accepted),last.states(),last.modes(),last.massFlows());
                // The first step of an interval or segment reads the endpoint the last accepted step left, carried
                // across the call while no connection's identity or closure changed: without it a cake left a hair
                // below capacity at an interval boundary was never declared clogged (review 8.6 (f), 8.8 (a)).
                else if(carriedFlows(accepted)!=null)guard.checkRate(liveFilters(accepted),accepted.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),carriedModes,carriedFlows.clone());
                // With nothing carried (a new segment whose closures changed, or a fresh solver) and a guard that can
                // declare something (the solid event integrator's), one rate solve of the port graph supplies the t0
                // flows; a failed rate solve skips the t0 check.
                else if(guard!=StageGuard.NONE&&attempt==0) {
                    var rate=portRate(accepted,checkpoint,acceptance);
                    if(rate!=null)guard.checkRate(liveFilters(accepted),rate.states(),rate.modes(),rate.massFlows());
                }
                PassiveNetwork start=accepted;
                if(implicit.backwardEulerCold(accepted)) {
                    if(coldSeedFor!=accepted){coldSeed=coldRateSeed(accepted,checkpoint,acceptance);coldSeedFor=accepted;}
                    start=coldSeed;
                }
                var result=implicit.solve(start,step,checkpoint,acceptance);
                if(elapsed!=reopenElapsed||step!=reopenStep){reopened.clear();reopenElapsed=elapsed;reopenStep=step;}
                var candidates=new LinkedHashSet<>(PassiveStepSolver.reopenable(start,result));candidates.removeAll(reopened);
                if(!candidates.isEmpty()) {
                    reopened.addAll(candidates);implicit.reopenNext(candidates);rejectedCount++;
                    SolverDiagnostics.attempt(attempt,step,false,"be-reopen",candidates.size());
                    count(rejectionReasons,REOPEN_REJECTION);
                    continue;
                }
                guard.checkFilters(result.filters(),result.states(),result.modes(),result.massFlows());
                double change=stateChange(accepted,result.states());
                boolean jump=change>STATE_CAP&&previousStateChange>0&&step<=.51*previousStateStep&&change>=.9*previousStateChange;
                boolean ok=step<=transitionFloor||change<=STATE_CAP||jump;
                SolverDiagnostics.attempt(attempt,step,ok,ok&&jump&&step>transitionFloor?"be-jump":ok&&step<=transitionFloor&&change>STATE_CAP?"be-floor":"be-state",change);
                if(!ok){previousStateChange=change;previousStateStep=step;throw new StateChangeRejection("Backward-Euler state change "+change);}
                accepted=replace(accepted,result);last=result;elapsed+=step;acceptedCount++;consecutiveRejects=0;previousStateChange=0;
                implicit.markBackwardEulerAccepted(accepted);
                carriedModes=result.modes();carriedFlows=result.massFlows();carriedKey=flowKey(accepted);
                // The cold-start graphs are read only while the structure is cold, and the step solver's last solve only
                // by a test of the Jacobian; a retained island keeps neither (review 8.7 (c) E2).
                coldSeed=null;coldSeedFor=null;implicit.releaseLastSolve();
                work+=result.pumpWorkJoule();boundaries.addAll(result.boundaries());pipeTransfers.add(result.pipeTransfers(),1);
                var flows=result.massFlows();for(int i=0;i<transferred.length;i++)transferred[i]+=step*flows[i];
                double factor=change>0?Math.clamp(.9*Math.sqrt(STATE_CAP/change),.5,2):2;
                h=Math.min(settings.maximumStep,Math.max(h,step)*factor);
            }catch(SolidEventIntegrator.Transition transition) {
                // A rejection kind of its own. The step is not accepted, so no accepted step ever
                // contains a state past the transition; the search is a plain halving onto the
                // event, and it is separate from the other rejections both in the substep counts and
                // in the consecutive-rejection limit, which answers for a step size the controller
                // could not make work rather than for a physical regime boundary.
                SolverDiagnostics.count(SolverDiagnostics.solidTransitionRejections);
                SolverDiagnostics.attempt(attempt,step,false,"transition",Double.NaN);
                if(transition.atStart||step<=transitionFloor||transitionRejects>=MAXIMUM_TRANSITION_REJECTIONS){declared=transition;break;}
                if(transitionRejects==0)beforeTransition=h;
                transitionRejects++;
                if(rejectionReasons.size()<8||rejectionReasons.containsKey(TRANSITION_REJECTION))rejectionReasons.merge(TRANSITION_REJECTION,1,Integer::sum);
                h=step*.5;
            }catch(SparseNewton.Nonconvergence|IllegalArgumentException|StateChangeRejection rejected) {
                lastRejection=String.valueOf(rejected.getMessage());
                if(!(rejected instanceof StateChangeRejection))SolverDiagnostics.attempt(attempt,step,false,diagnosticReason(rejected),Double.NaN);
                // A trial the property domain refused - directly, or a Newton pass whose failing line search it
                // stopped - is counted under its own key (one per component, property and side, never folded into
                // "Other"), so a checkpoint, a view and the certificate's transition check all see it.
                var domain=SparseNewton.domainViolation(rejected);
                if(domain!=null){rejectionReasons.merge(domain.reasonKey(),1,Integer::sum);lastDomain=domain;domainRejections++;}
                else count(rejectionReasons,String.valueOf(rejected.getMessage()).replaceAll("[-+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][-+]?[0-9]+)?","#"));
                lastWasDomain=domain!=null;
                rejectedCount++;consecutiveRejects++;h=step*.5;
                if(consecutiveRejects>=20||elapsed+h==elapsed)throw new SparseNewton.Nonconvergence("Substep refinement exhausted: "+rejected.getMessage()+"; reasons="+rejectionReasons,null,domainCause(lastWasDomain,lastDomain,domainRejections,rejectedCount));
            }
        }
        if(declared==null&&elapsed<duration)throw new SparseNewton.Nonconvergence("Interval substep limit; no partial interval may commit: advanced="+elapsed+" of "+duration+" s, accepted="+acceptedCount+", rejected="+rejectedCount+", reasons="+rejectionReasons+", last="+lastRejection,
                null,domainCause(lastWasDomain,lastDomain,domainRejections,rejectedCount));
        // A segment that ended on a declared transition hands the next one the estimate it held
        // before the search, not the micro-step the search finished on.
        nextStepEstimate=declared!=null&&transitionRejects>0?beforeTransition:h;
        if(last==null)return new Prefix(null,Objects.requireNonNull(declared,"A completed interval always has a last substep"));
        // An unbroken interval advanced exactly what was requested and normalizes by it; a prefix
        // normalizes by what it did advance, so the caller's advanced*averageMassFlows is still the
        // transported mass.
        double normalizer=declared==null?duration:elapsed;
        for(int i=0;i<transferred.length;i++)transferred[i]/=normalizer;
        return new Prefix(new Result(accepted,elapsed,transferred,acceptedCount,rejectedCount,work,boundaries,rejectionReasons,last.modes(),last.devicePressureChanges(),acceptance,pipeTransfers.snapshot()),declared);
    }
    private static void count(Map<String,Integer> reasons,String reason) {
        if(reasons.size()<8||reasons.containsKey(reason))reasons.merge(reason,1,Integer::sum);else reasons.merge("Other",1,Integer::sum);
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
    /** The diagnostic reason of an attempt that threw ("newton|message" with numbers masked). */
    private static String diagnosticReason(RuntimeException rejected) {
        String message=String.valueOf(rejected.getMessage()).replaceAll("[-+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][-+]?[0-9]+)?","#");
        if(message.length()>90)message=message.substring(0,90);
        return (rejected instanceof SparseNewton.Nonconvergence?"newton":"other")+"|"+message;
    }
    /** The cold-start graph with its junction states replaced by one rate solve's, and the graph it was built for. */
    private PassiveNetwork coldSeed,coldSeedFor;
    /** One rate solve of the graph with its vessels held as ports (dt = 1), or null if it fails. */
    private PassiveStepSolver.Result portRate(PassiveNetwork graph,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance) {
        if(graph.pipes().isEmpty())return null;
        var ports=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:graph.reservoirs())ports.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),
                node.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:node.kind(),node.inventory()));
        try{return implicit.solveRate(new PassiveNetwork(ports,graph.pipes()),checkpoint,acceptance);}
        catch(SparseNewton.Nonconvergence|IllegalArgumentException|ApproximationRejected failed){return null;}
    }
    /**
     * The cold-start graph: the junctions' states replaced by one rate solve's of the port graph, whose flows the step
     * solver's warm start then carries into the first steps; the graph unchanged if it has no junction or the rate solve
     * fails. From the compiled property guess alone a pumped line into a dry tank, with three liquid junctions, failed
     * at every step size (review 8.6 (b), run 88d).
     */
    private PassiveNetwork coldRateSeed(PassiveNetwork graph,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance) {
        if(graph.pipes().isEmpty()||graph.reservoirs().stream().noneMatch(PassiveNetwork.Reservoir::junction))return graph;
        var rate=portRate(graph,checkpoint,acceptance);
        if(rate==null)return graph;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);
            nodes.add(node.junction()?new PassiveNetwork.Reservoir(node.id(),node.elevation(),rate.states().get(i),node.kind(),node.inventory()):node);}
        return new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }
    /**
     * The start of an island job ({@code graph}, the island's committed state) given the island's committed interval
     * ({@code committedGraph} and its endpoint modes; null for an island with none). Afterwards this solver holds
     * nothing an earlier job left that a fresh solver would not also derive from the committed interval: the carried
     * endpoint (modes and flows) and the cold-start seed graphs are dropped, and the step solver is reset by
     * {@link PassiveStepSolver#replayStart}.
     *
     * <p>Why: a certified island is woken with a fresh solver, and its replay must reproduce the island that solved
     * every interval bit for bit. A Newton workspace carried across a job boundary opened the next job's first solve on
     * an older factorization, the converged pressures differed in the last digits, and through the controller's step
     * growth (a continuous function of the change) the difference reached the committed inventories (review 8.9 (c),
     * decision D8).
     */
    public void replayStart(PassiveNetwork graph,PassiveNetwork committedGraph,List<FlowControl.Mode> committedModes) {
        graph=PassiveNetwork.sizeJunctionHoldups(graph,model);
        carriedModes=null;carriedFlows=null;carriedKey=List.of();coldSeed=null;coldSeedFor=null;
        boolean committed=committedGraph!=null&&PassiveStepSolver.sameStructure(graph,committedGraph);
        implicit.replayStart(graph,committed,committed?committedModes:null);
    }
    /** The accepted modes and flows of the last accepted step, carried across intervals for the first step's guard;
     * used only while no connection's identity or closure changed ({@link #flowKey}). */
    private List<FlowControl.Mode> carriedModes;
    private double[] carriedFlows;
    private List<Long> carriedKey=List.of();
    private static List<Long> flowKey(PassiveNetwork graph) {
        var key=new ArrayList<Long>();for(var pipe:graph.pipes()){key.add(pipe.id());key.add((long)pipe.blockedDirections());}
        return key;
    }
    private double[] carriedFlows(PassiveNetwork graph) {
        return carriedFlows!=null&&carriedModes!=null&&carriedKey.equals(flowKey(graph))?carriedFlows:null;
    }
    /** The largest relative pressure or mass change of a vessel (a RESERVOIR node). */
    private static double stateChange(PassiveNetwork before,List<FluidThermodynamics.State> after) {
        double change=0;
        for(int i=0;i<after.size();i++) {
            var node=before.reservoirs().get(i);if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
            var a=node.state();var b=after.get(i);
            change=Math.max(change,Math.abs(b.pressure()-a.pressure())/Math.max(100,a.pressure()));
            change=Math.max(change,Math.abs(b.mass()-a.mass())/Math.max(1e-12,a.mass()));
        }
        return change;
    }
    /** The cake each filter edge of this graph carries right now, for the start-of-step guard. */
    private static Map<Long,InlineFilter> liveFilters(PassiveNetwork graph) {
        Map<Long,InlineFilter> filters=null;
        for(var pipe:graph.pipes())if(pipe.filter()!=null){if(filters==null)filters=new HashMap<>();filters.put(pipe.id(),pipe.filter());}
        return filters==null?Map.of():filters;
    }
    public static PassiveNetwork replace(PassiveNetwork graph,PassiveStepSolver.Result result) {
        var states=result.states();
        if(states.size()!=graph.reservoirs().size())throw new IllegalArgumentException("State count mismatch");
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<states.size();i++){var old=graph.reservoirs().get(i);reservoirs.add(new PassiveNetwork.Reservoir(old.id(),old.elevation(),states.get(i),old.kind(),result.inventories().get(i)));}
        return new PassiveNetwork(reservoirs,graph.pipes().stream().map(p->result.filters().containsKey(p.id())?p.withFilter(result.filters().get(p.id())):p).toList(),graph.scheduledTransfers());
    }
}
