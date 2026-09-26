package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic probe (temporary): one island driven through the worker command and the coordinator's slice and
 * hold policy (IslandCoordinator.closeRound), with a deterministic clock that charges a fixed cost per
 * checkpoint, so the wall budget cuts attempts at reproducible points.
 */
class FluidPumpedFillCoordinatedProbeTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PumpedFillProbeTest builder=new PumpedFillProbeTest();
    private static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}
        public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}
        public void throwIfCancellationRequested(){}
    };
    /** Counts checkpoint calls of one warm interval to calibrate a per-call cost. */
    @Test void calibrate() {
        var graph=builder.line("GUPRPRPR");var solver=new RetainedSolver();long[] calls={0};
        solver.solve(model,builder.line("GUPR"),5,PassiveIntervalSolver.Settings.defaults(),()->{});
        long t0=System.nanoTime();
        solver=new RetainedSolver();
        solver.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),()->calls[0]++);
        long ns=System.nanoTime()-t0;
        System.out.println("calibration: fill3 interval 1: "+calls[0]+" checkpoints in "+ns/1e6+" ms = "+(ns/(double)calls[0])+" ns per checkpoint");
    }
    static boolean FRESH_AFTER_HOLD=false;
    record Outcome(String line,boolean committed) {}
    String coordinate(String layout,long nanosPerCall,int attempts,long budgetNanos){return coordinate(layout,nanosPerCall,attempts,budgetNanos,-1,null);}
    String coordinate(String layout,long nanosPerCall,int attempts,long budgetNanos,int traceAttempt,String traceFile) {
        var graph=builder.line(layout);var out=new StringBuilder("=== "+layout+" nanosPerCall="+nanosPerCall+" budget="+budgetNanos/1e6+" ms");
        var retained=new RetainedSolver();long committed=0;int maximumSliceTicks=Integer.MAX_VALUE,cadence=100;
        Optional<ApproximationAnchor> anchor=Optional.empty();FallbackAllowance allowance=FallbackAllowance.NONE;
        long soft=budgetNanos*3/4;int holdsInARow=0;long totalCalls=0;
        for(int attempt=0;attempt<attempts;attempt++) {
            int ticks=Math.min(cadence,maximumSliceTicks);
            var policy=anchor.isPresent()?FluidFallbackPolicy.active(anchor.orElseThrow(),allowance,cadence,soft):FluidFallbackPolicy.disabled();
            var command=new ProcessSolveServices.FluidIslandCommand(model,graph,ticks/20.0,PassiveIntervalSolver.Settings.defaults(),budgetNanos,policy,retained);
            long[] clock={0};
            java.io.PrintWriter tw=null;
            if(attempt==traceAttempt){try{tw=new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(traceFile)));}catch(java.io.IOException e){throw new RuntimeException(e);}com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.TRACE=tw::println;}
            ProcessSolveServices.FluidIslandSolveResult result;
            try{result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER,()->clock[0]+=nanosPerCall);}
            finally{com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics.TRACE=null;if(tw!=null)tw.close();}
            totalCalls+=clock[0]/nanosPerCall;
            boolean accepted=result.candidate().isPresent();
            String note;
            if(accepted) {
                var candidate=result.candidate().orElseThrow();graph=candidate.graph();committed+=ticks;allowance=result.proposedAllowance();anchor=result.proposedAnchor();
                if(candidate.acceptance()==PassiveStepSolver.Acceptance.FULL&&maximumSliceTicks!=Integer.MAX_VALUE)maximumSliceTicks=maximumSliceTicks>=cadence/2?Integer.MAX_VALUE:maximumSliceTicks*2;
                holdsInARow=0;note=result.detail()+" accepted="+candidate.acceptedSubsteps()+" rejected="+candidate.rejectedSubsteps();
            } else {
                if(result.detail().startsWith("HELD: wall deadline"))maximumSliceTicks=Math.max(1,ticks/2);
                holdsInARow++;note=result.detail();if(FRESH_AFTER_HOLD)retained=new RetainedSolver();
            }
            var tanks=new StringBuilder();for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)tanks.append(String.format(" %.0f/%.3f",node.state().pressure(),(node.state().liquidVolume()+node.state().waterVolume())/node.state().volume()));
            out.append(String.format("%n #%d slice [%d,+%d] %s work=%.2f s committed=%d tanks%s",attempt,committed-(accepted?ticks:0),ticks,note,clock[0]/1e9,committed,tanks));
            if(holdsInARow>=12){out.append("\n  twelve holds in a row: stop");break;}
            if(committed>=20*60*4)break;
        }
        out.append(String.format("%n  total work %.1f s (at %d ns per checkpoint)",totalCalls*nanosPerCall/1e9,nanosPerCall));
        return out.toString();
    }
    @Test void tracedAttempts() {
        String dir=System.getProperty("user.dir")+"/build/f1-trace/";
        try{java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));}catch(java.io.IOException e){throw new RuntimeException(e);}
        System.out.println(coordinate("GUPRPRPRPRPRPR",1000,14,2_000_000_000L,13,dir+"fill6-attempt13.txt"));
        System.out.println(coordinate("GUPRPRPRPV",1000,4,2_000_000_000L,3,dir+"vent3-attempt3.txt"));
        System.out.println(coordinate("GUPRPRPR",1000,11,2_000_000_000L,10,dir+"fill3-attempt10.txt"));
    }
    @Test void coordinatedFresh() {
        FRESH_AFTER_HOLD=true;
        try{coordinated();}finally{FRESH_AFTER_HOLD=false;}
    }
    @Test void coordinated() {
        for(long perCall:new long[]{1000})
        for(String layout:new String[]{"GUPRPRPR","GUPRPRPRPRPRPR","GUPRPRPRPV","GUPRPRPRPRPRPRPV","RUPR","RUPRPR","RPRUPRPR"})
            System.out.println(coordinate(layout,perCall,60,2_000_000_000L));
    }
}
