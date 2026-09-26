package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic probe (temporary): one placed line driven by the real IslandCoordinator (slice, hold and retry policy,
 * certificates at the world defaults) with a synchronous one-worker dispatcher whose jobs run on a clock that
 * charges a fixed cost per solver checkpoint, so the 2 s wall and 1.5 s soft budgets cut at reproducible points.
 */
class FluidPumpedFillRealCoordinatorProbeTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PumpedFillProbeTest builder=new PumpedFillProbeTest();
    static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}
        public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}
        public void throwIfCancellationRequested(){}
    };
    static final class SyncDispatch implements IslandCoordinator.Dispatcher {
        final long nanosPerCall;long sequence,work,jobs;
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final List<String> log=new ArrayList<>();
        SyncDispatch(long nanosPerCall){this.nanosPerCall=nanosPerCall;}
        public int availableWorkers(){return 1-attempts.size();}
        public long nextRequestId(){return ++sequence;}
        public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
        public void cancel(long request){}
        void runAll(IslandCoordinator coordinator) {
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);long[] clock={0};
                var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER,()->clock[0]+=nanosPerCall);
                work+=clock[0];jobs++;
                log.add(String.format("  slice [%d,%d) %s work=%.2f s",attempt.slice().startTick(),attempt.slice().endTick(),result.detail().length()>150?result.detail().substring(0,150):result.detail(),clock[0]/1e9));
                coordinator.completed(attempt,Optional.of(result));
            }
        }
    }
    String run(String layout,long nanosPerCall,long ticks) {
        var dispatch=new SyncDispatch(nanosPerCall);
        var coordinator=new IslandCoordinator(dispatch,changed->{},()->0L,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false,100,CertificatePolicy.defaults()));
        coordinator.register(new IslandCoordinator.Snapshot(1,0,builder.line(layout),new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        var out=new StringBuilder("=== "+layout+" at "+nanosPerCall+" ns per checkpoint, "+ticks+" online ticks");
        String lastStatus="";long firstCommitted=-1;
        for(long tick=0;tick<ticks;tick++) {
            coordinator.tick();dispatch.runAll(coordinator);coordinator.pump();
            var s=coordinator.observe(1);
            if(!s.status().equals(lastStatus)&&!s.status().startsWith("SOLVING")) {
                if(dispatch.log.size()>0){for(var l:dispatch.log)out.append('\n').append(l);dispatch.log.clear();}
                out.append(String.format("%n tick %d committed=%d status=%s",tick,s.clock().committedTick(),s.status().length()>160?s.status().substring(0,160):s.status()));
                lastStatus=s.status();
            }
            dispatch.log.clear();
        }
        var s=coordinator.snapshot(1);var tanks=new StringBuilder();
        for(var node:s.graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)tanks.append(String.format(" [P=%.1f T=%.2f liq=%.4f]",node.state().pressure(),node.state().temperature(),(node.state().liquidVolume()+node.state().waterVolume())/node.state().volume()));
        out.append(String.format("%n END online=%d committed=%d status=%s certificate=%s jobs=%d work=%.1f s%n tanks%s",s.clock().onlineTick(),s.clock().committedTick(),s.status(),s.certificate(),dispatch.jobs,dispatch.work/1e9,tanks));
        s.lastResult().ifPresent(r->out.append("\n last modes=").append(r.endpointModes()).append(" flows=").append(Arrays.toString(r.averageMassFlows())));
        return out.toString();
    }
    @Test void sixTank() {
        for(String layout:new String[]{"GUPRPRPRPRPRPR","GUPRPRPRPRPRPRPV"})System.out.println(run(layout,1000,20*900));
    }
    @Test void realCoordinator() {
        String only=System.getProperty("probe.layout","");
        for(String layout:only.isEmpty()?new String[]{"GUPRPRPR","GUPRPRPRPRPRPR","GUPRPRPRPV","GUPRPRPRPRPRPRPV","RUPR","RUPRPR","RPRUPRPR"}:only.split(","))
            System.out.println(run(layout,1000,20*600));
    }
}
