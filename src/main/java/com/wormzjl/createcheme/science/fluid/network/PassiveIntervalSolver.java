package com.wormzjl.createcheme.science.fluid.network;

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
    public PassiveIntervalSolver(FluidThermodynamics model,ErrorControl errorControl){this.model=Objects.requireNonNull(model);stepSolver=new TrBdf2StepSolver(model);this.errorControl=Objects.requireNonNull(errorControl);}
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
        return integrate(initial,duration,settings,checkpoint,PassiveStepSolver.Acceptance.FULL,TrBdf2StepSolver.StageGuard.NONE);
    }
    public Result solveApproximate(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,TrBdf2StepSolver.StageGuard guard) {
        return integrate(initial,duration,settings,checkpoint,PassiveStepSolver.Acceptance.APPROXIMATE,guard);
    }
    private Result integrate(PassiveNetwork initial,double duration,Settings settings,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,TrBdf2StepSolver.StageGuard guard) {
        if(!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Positive finite interval required");
        PassiveNetwork accepted=acceptance==PassiveStepSolver.Acceptance.FULL?InventoryEquilibrium.refresh(initial,model,checkpoint):initial;
        double elapsed=0,h=Math.min(settings.initialStep,duration);int acceptedCount=0,rejectedCount=0,consecutiveRejects=0;PassiveStepSolver.Result last=null;
        double[] transferred=new double[initial.pipes().size()],grossTransferred=new double[initial.pipes().size()];
        double work=0;var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var pipeTransfers=new PipeTransfer.Accumulator();
        var rejectionReasons=new LinkedHashMap<String,Integer>();
        String lastRejection="";
        for(int attempt=0;attempt<settings.maximumAttempts&&elapsed<duration;attempt++) {
            checkpoint.run();h=Math.min(h,duration-elapsed);
            try {
                var regimes=new RegimeTrace(guard,accepted);
                PassiveStepSolver.Result coarse=null;
                if(errorControl==ErrorControl.EMBEDDED&&initial.pipes().stream().noneMatch(pipe->pipe.control() instanceof FlowControl.PressureValve)) {
                    var trial=stepSolver.trial(accepted,h,checkpoint,acceptance,regimes);var full=trial.solution();coarse=full;
                    if(regimes.smooth()) {
                    double stateError=error(full.states(),trial.estimatedStates());
                    double pipeError=flowError(accepted,full.massFlows(),trial.estimatedMassFlows(),trial.estimatedMassFlows(),h,grossTransferred,duration);
                    double sourceError=initial.scheduledTransfers().isEmpty()?0:boundaryError(full.boundaries(),trial.estimatedBoundaries());
                    double error=Math.max(Math.max(stateError,pipeError),sourceError);
                    if(error>settings.relativeTolerance)throw new AccuracyRejection("Embedded "+(sourceError>=Math.max(stateError,pipeError)?"boundary":pipeError>=stateError?"pipe":"state")+" error "+error,error);
                    accepted=replace(accepted,full);last=full;elapsed+=h;acceptedCount++;consecutiveRejects=0;
                    work+=full.pumpWorkJoule();boundaries.addAll(full.boundaries());var flows=full.massFlows();
                    pipeTransfers.add(full.pipeTransfers(),1);
                    for(int i=0;i<transferred.length;i++){transferred[i]+=h*flows[i];grossTransferred[i]+=h*Math.abs(flows[i]);}
                    h=Math.min(settings.maximumStep,h*stepFactor(error,settings.relativeTolerance,false));
                    continue;
                    }
                }
                var full=coarse!=null?coarse:stepSolver.solve(accepted,h,checkpoint,acceptance,regimes);
                var first=stepSolver.solve(accepted,h/2,checkpoint,acceptance,regimes);var middle=replace(accepted,first);
                var second=stepSolver.solve(middle,h/2,checkpoint,acceptance,regimes);
                // Order-two Richardson scaling requires a smooth phase/device regime throughout.
                // Across a transition, retain the entire coarse-versus-refined discrepancy.
                double error=Math.max(error(full.states(),second.states()),flowError(accepted,full.massFlows(),first.massFlows(),second.massFlows(),h,grossTransferred,duration))/(regimes.smooth()?3:1);
                if(!initial.scheduledTransfers().isEmpty())error=Math.max(error,boundaryError(full,first,second)/(regimes.smooth()?3:1));
                if(error>settings.relativeTolerance)throw new AccuracyRejection("Step refinement error "+error,error);
                accepted=replace(accepted,second);last=second;elapsed+=h;acceptedCount+=2;consecutiveRejects=0;
                work+=first.pumpWorkJoule()+second.pumpWorkJoule();boundaries.addAll(first.boundaries());boundaries.addAll(second.boundaries());
                pipeTransfers.add(first.pipeTransfers(),1);pipeTransfers.add(second.pipeTransfers(),1);
                var q1=first.massFlows();var q2=second.massFlows();for(int i=0;i<transferred.length;i++){transferred[i]+=h*.5*(q1[i]+q2[i]);grossTransferred[i]+=h*.5*(Math.abs(q1[i])+Math.abs(q2[i]));}
                h=Math.min(settings.maximumStep,h*stepFactor(error,settings.relativeTolerance,false));
            }catch(SparseNewton.Nonconvergence|IllegalArgumentException|AccuracyRejection rejected) {
                lastRejection=String.valueOf(rejected.getMessage());
                String reason=String.valueOf(rejected.getMessage()).replaceAll("[-+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][-+]?[0-9]+)?","#");
                if(rejectionReasons.size()<8||rejectionReasons.containsKey(reason))rejectionReasons.merge(reason,1,Integer::sum);else rejectionReasons.merge("Other",1,Integer::sum);
                rejectedCount++;consecutiveRejects++;h*=rejected instanceof AccuracyRejection accuracy?stepFactor(accuracy.error,settings.relativeTolerance,true):.5;
                // A short pipe can introduce the first liquid phase in less than a millisecond.
                // Permit bounded refinement through that transition; every accepted step still
                // meets the same error tolerance, attempt cap, and caller's wall deadline.
                if(consecutiveRejects>=20||elapsed+h==elapsed)throw new SparseNewton.Nonconvergence("Substep refinement exhausted: "+rejected.getMessage());
            }
        }
        if(elapsed<duration)throw new SparseNewton.Nonconvergence("Interval substep limit; no partial interval may commit: advanced="+elapsed+" of "+duration+" s, accepted="+acceptedCount+", rejected="+rejectedCount+", reasons="+rejectionReasons+", last="+lastRejection);
        for(int i=0;i<transferred.length;i++)transferred[i]/=duration;
        return new Result(accepted,elapsed,transferred,acceptedCount,rejectedCount,work,boundaries,rejectionReasons,Objects.requireNonNull(last).modes(),last.devicePressureChanges(),acceptance,pipeTransfers.snapshot());
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
        return new PassiveNetwork(reservoirs,graph.pipes(),graph.scheduledTransfers());
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
