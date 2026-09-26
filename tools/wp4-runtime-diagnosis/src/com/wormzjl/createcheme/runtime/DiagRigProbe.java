package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * WP4 runtime diagnosis (scratch, not tracked): FluidPumpedFillLineTest's Rig, verbatim, with the wall budget as a
 * parameter, printing every job. Args: layout ticks stop(committed|certificate|none) [wallNanos].
 */
public class DiagRigProbe {
    static final long NANOS_PER_CHECKPOINT=1_000;
    static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    static final FluidThermodynamics model=DiagPumpedFillProbe.model;
    public static void main(String[] args) {
        String layout=args[0];long ticks=Long.parseLong(args[1]);String stop=args[2];long wall=args.length>3?Long.parseLong(args[3]):2_000_000_000L;
        long soft=wall*3/4;
        var attempts=new LinkedHashMap<Long,IslandCoordinator.Attempt>();var commands=new HashMap<Long,ProcessSolveServices.FluidIslandCommand>();
        long[] requests={0};
        var coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers(){return 1-attempts.size();}
            public long nextRequestId(){return ++requests[0];}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
            public void cancel(long request){}
        },changed->{},()->0L,new IslandCoordinator.Settings(wall,soft,64,false,100,CertificatePolicy.defaults()));
        coordinator.register(new IslandCoordinator.Snapshot(1,0,DiagPumpedFillProbe.line(layout),new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        long jobs=0,work=0,online=0,maxJob=0;
        for(long tick=0;tick<ticks;tick++) {
            coordinator.tick();online++;
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);long[] clock={0};
                var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER,()->clock[0]+=NANOS_PER_CHECKPOINT);
                jobs++;work+=clock[0];maxJob=Math.max(maxJob,clock[0]);
                System.out.printf("  job %d at online %d: slice %.2f s, %d checkpoints, %s%n",jobs,online,command.durationSeconds(),clock[0]/NANOS_PER_CHECKPOINT,result.detail().length()>150?result.detail().substring(0,150):result.detail());
                coordinator.completed(attempt,Optional.of(result));
            }
            coordinator.pump();
            var s=coordinator.observe(1);
            if(stop.equals("committed")&&s.clock().committedTick()>=40)break;
            if(stop.equals("certificate")&&s.certificate().isPresent())break;
        }
        var s=coordinator.observe(1);
        var text=new StringBuilder(String.format("END online %d, committed %d, %d jobs, %.1f s of work (largest job %.3f s), %s, %s",online,s.clock().committedTick(),jobs,work/1e9,maxJob/1e9,s.status(),s.certificate()));
        for(var n:s.graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)text.append(String.format(" [%.1f Pa, %.2f K, liquid %.4f]",n.state().pressure(),n.state().temperature(),(n.state().liquidVolume()+n.state().waterVolume())/n.state().volume()));
        System.out.println(text);
        coordinator.snapshot(1).lastResult().ifPresent(r->System.out.println("END modes="+r.endpointModes()));
    }
}
