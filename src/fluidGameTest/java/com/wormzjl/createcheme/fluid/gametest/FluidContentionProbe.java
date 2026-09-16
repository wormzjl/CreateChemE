package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only competing equipment kernels in the actual shared pool. The normal router
 * still owns terminal draining. No second executor or production test switch is added. */
final class FluidContentionProbe {
    private static final long DURATION=45_000_000_000L;
    private static final class Occupation {
        final AtomicLong started=new AtomicLong(),finished=new AtomicLong(),cpuNanos=new AtomicLong(),checksum=new AtomicLong();
        boolean terminal,success;String detail="";
    }
    private final List<Occupation> jobs=new ArrayList<>();
    private FluidContentionProbe() {}

    /** Reflection stays inside the test source set and supplies the normal request context
     * alongside a controlled kernel. All admission/context mutation is owner-thread confined. */
    @SuppressWarnings("unchecked")
    static FluidContentionProbe start(MinecraftServer server,FluidThermodynamics model,int count) {
        if(!server.isSameThread())throw new IllegalStateException("Contention admission requires server thread");
        var diagnostics=ProcessSolveServices.diagnostics(server);
        if(count<1||count>diagnostics.workerCount()-diagnostics.activeWorkers()||diagnostics.readyJobs()!=0)throw new IllegalStateException("Contention fixture requires idle capacity");
        try {
            var statesField=ProcessSolveServices.class.getDeclaredField("STATES");statesField.setAccessible(true);
            var state=((Map<MinecraftServer,?>)statesField.get(null)).get(server);
            var serviceField=state.getClass().getDeclaredField("service");serviceField.setAccessible(true);
            var service=(BoundedCpuSolveService<ProcessSolveServices.SolveTarget,ProcessSolveServices.ProcessSolveCommand,ProcessSolveServices.ProcessSolveResult>)serviceField.get(state);
            var contextsField=state.getClass().getDeclaredField("requestsBySequence");contextsField.setAccessible(true);var contexts=(Map<Long,Object>)contextsField.get(state);
            var contextType=Class.forName(ProcessSolveServices.class.getName()+"$RequestContext");
            var constructor=contextType.getDeclaredConstructor(ProcessSolveServices.ProcessSolveRequest.class,ProcessSolveServices.Diagnostics.class,BoundedCpuSolveService.JobStamp.class);constructor.setAccessible(true);
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
            var command=new ProcessSolveServices.FluidIslandCommand(model,graph,5,PassiveIntervalSolver.Settings.defaults(),2_000_000_000L);
            var probe=new FluidContentionProbe();
            for(int index=0;index<count;index++) {
                var job=new Occupation();long id=ProcessSolveServices.nextRequestId();
                var handler=new ProcessSolveServices.FluidCompletionHandler() {
                    public void completed(ProcessSolveServices.FluidIslandCompletion completion){job.terminal=true;job.success=completion.completion().result().flatMap(ProcessSolveServices.FluidIslandSolveResult::candidate).isPresent();job.detail=completion.completion().status().name();}
                    public void abandoned(ProcessSolveServices.FluidIslandRequest request){job.terminal=true;job.detail="ABANDONED";}
                };
                var target=new ProcessSolveServices.FluidIslandTarget(server.overworld().dimension(),Long.MAX_VALUE-id);
                var request=new ProcessSolveServices.FluidIslandRequest(id,target,0,handler);
                var stamp=new BoundedCpuSolveService.JobStamp<ProcessSolveServices.SolveTarget>(service.serverEpoch(),id,target,0,"test-only-45-second-equipment",System.nanoTime()+60_000_000_000L);
                var context=constructor.newInstance(request,diagnostics,stamp);
                var admitted=service.trySubmit(stamp,command,(snapshot,token)->{
                    var bean=java.lang.management.ManagementFactory.getThreadMXBean();long cpu=bean.isCurrentThreadCpuTimeSupported()?bean.getCurrentThreadCpuTime():-1;
                    long start=System.nanoTime();job.started.set(start);double value=.123;
                    try {
                        while(System.nanoTime()-start<DURATION){token.throwIfCancellationRequested();for(int i=0;i<1024;i++)value=Math.sin(value)+.123;}
                        return snapshot.solve(token);
                    } finally {job.checksum.set(Double.doubleToLongBits(value));job.finished.set(System.nanoTime());job.cpuNanos.set(cpu<0?-1:bean.getCurrentThreadCpuTime()-cpu);}
                });
                if(admitted!=BoundedCpuSolveService.Admission.ACCEPTED)throw new IllegalStateException("Competing job admission failed: "+admitted);
                if(contexts.putIfAbsent(id,context)!=null)throw new IllegalStateException("Competing request collision");probe.jobs.add(job);
            }
            return probe;
        } catch(ReflectiveOperationException failure){throw new IllegalStateException("Shared-pool test adapter no longer matches runtime",failure);}
    }
    boolean complete(){return jobs.stream().allMatch(j->j.terminal);}
    boolean passed(){return complete()&&jobs.stream().allMatch(j->j.success&&j.started.get()>0&&j.finished.get()-j.started.get()>=DURATION);}
    List<Map<String,Object>> report() {
        return jobs.stream().map(j->{Map<String,Object> row=new LinkedHashMap<>();row.put("terminal",j.terminal);row.put("success",j.success);row.put("status",j.detail);row.put("wallSeconds",(j.finished.get()-j.started.get())/1e9);row.put("cpuSeconds",j.cpuNanos.get()/1e9);return row;}).toList();
    }
}
