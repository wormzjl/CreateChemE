package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.HashMap;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual-server regression for the per-tick terminal-routing budget. */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidCompletionBudgetGameTests {
    private FluidCompletionBudgetGameTests() {}

    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-completion-budget")
    public static void sixtyFifthTerminalWaitsForTheNextTickWithoutAZeroBudgetDrain(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,
                model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        var command=new ProcessSolveServices.FluidIslandCommand(model,graph,5,
                PassiveIntervalSolver.Settings.defaults(),2_000_000_000L);
        int[] issued={0},completed={0},saturatedTick={-1};long[] started={0};
        var byTick=new HashMap<Integer,Integer>();var previousPump=new Runnable[1];
        long owner=ProcessSolveServices.nextRequestId();
        var handler=new ProcessSolveServices.FluidCompletionHandler() {
            @Override public void completed(ProcessSolveServices.FluidIslandCompletion completion) {
                helper.assertTrue(server.isSameThread(),"Completion left the owner thread");
                helper.assertTrue(completion.completion().result().orElseThrow().candidate().isPresent(),"Idle job failed");
                completed[0]++;byTick.merge(server.getTickCount(),1,Integer::sum);
            }
            @Override public void abandoned(ProcessSolveServices.FluidIslandRequest request) {
                helper.fail("Completion-budget job was abandoned");
            }
        };
        Runnable pump=()->{
            if(issued[0]>=70||issued[0]!=completed[0])return;
            long requestId=ProcessSolveServices.nextRequestId();
            var request=new ProcessSolveServices.FluidIslandRequest(requestId,
                    new ProcessSolveServices.FluidIslandTarget(helper.getLevel().dimension(),owner),issued[0],handler);
            if(ProcessSolveServices.submitFluidIsland(server,request,command).accepted())issued[0]++;
        };
        Runnable restore=()->{
            if(previousPump[0]!=null){ProcessSolveServices.setReadinessPump(server,previousPump[0]);previousPump[0]=null;}
        };

        helper.startSequence().thenWaitUntil(()->{
            var diagnostics=ProcessSolveServices.diagnostics(server);
            helper.assertTrue(diagnostics.outstandingJobs()==0&&diagnostics.pendingCompletions()==0,
                    "Waiting for a quiescent shared service");
        }).thenIdle(1).thenExecute(()->{
            try {
                helper.assertTrue(ProcessSolveServices.drainCompletions(server,64).isEmpty(),
                        "Quiescent setup unexpectedly consumed a completion");
                saturatedTick[0]=server.getTickCount();started[0]=System.nanoTime();
                previousPump[0]=ProcessSolveServices.setReadinessPump(server,pump);pump.run();
                server.managedBlock(()->completed[0]>=64||System.nanoTime()-started[0]>=5_000_000_000L);
                helper.assertTrue(completed[0]==64,"Did not exhaust the exact same-tick completion budget");
                helper.assertTrue(server.getTickCount()==saturatedTick[0],"Managed block crossed a server tick");
                server.managedBlock(()->ProcessSolveServices.diagnostics(server).pendingCompletions()>=1
                        ||System.nanoTime()-started[0]>=5_000_000_000L);
                var deferred=ProcessSolveServices.diagnostics(server);
                helper.assertTrue(server.getTickCount()==saturatedTick[0],"Deferred terminal crossed a server tick");
                helper.assertTrue(completed[0]==64&&issued[0]==65,"Unexpected same-tick callback count");
                helper.assertTrue(deferred.pendingCompletions()==1&&deferred.outstandingJobs()==1,
                        "The sixty-fifth terminal was not retained by the bounded service");
                for(int repeat=0;repeat<3;repeat++)helper.assertTrue(ProcessSolveServices.drainCompletions(server,64).isEmpty(),
                        "Same-tick drain bypassed the exhausted completion budget");
                var unchanged=ProcessSolveServices.diagnostics(server);
                helper.assertTrue(unchanged.pendingCompletions()==1&&unchanged.outstandingJobs()==1&&completed[0]==64,
                        "A zero remaining budget drained or routed the deferred terminal");
            } catch(RuntimeException failure) {
                restore.run();helper.fail("Completion-budget fixture failed: "+failure);
            } catch(AssertionError failure) {restore.run();throw failure;}
        }).thenWaitUntil(()->{
            helper.assertTrue(server.getTickCount()>saturatedTick[0],"Waiting for the next server tick");
            helper.assertTrue(completed[0]==70,"Waiting for all deferred short jobs");
        }).thenExecute(()->{
            try {
                helper.assertTrue(issued[0]==70,"Not every short job was admitted");
                helper.assertTrue(ProcessSolveServices.diagnostics(server).outstandingJobs()==0,"Terminal ownership leaked");
                helper.assertTrue(byTick.getOrDefault(saturatedTick[0],0)==64,"First tick did not stop at the exact cap");
                helper.assertTrue(byTick.values().stream().allMatch(count->count<=64),"Completion budget exceeded");
                helper.assertTrue(System.nanoTime()-started[0]<5_000_000_000L,"Deferred completion sequence exceeded its wall bound");
            } finally {restore.run();}
        }).thenSucceed();
    }

    /**
     * A completion left behind by an exhausted per-tick budget, with no later worker activity to wake anything:
     * the drain owes exactly one continuation, that continuation never drains inside the exhausted tick, and
     * the stranded completion is routed on a later tick.
     */
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-completion-continuation")
    public static void anExhaustedBudgetOwesOneContinuationThatNeverReentersItsTick(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,
                model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        var command=new ProcessSolveServices.FluidIslandCommand(model,graph,5,
                PassiveIntervalSolver.Settings.defaults(),2_000_000_000L);
        // 65 jobs, one after another, then nothing: the 65th completion is the one the budget leaves behind.
        int[] issued={0},completed={0},saturatedTick={-1};long[] started={0},counted=new long[2];
        var byTick=new HashMap<Integer,Integer>();var previousPump=new Runnable[1];
        long owner=ProcessSolveServices.nextRequestId();
        var handler=new ProcessSolveServices.FluidCompletionHandler() {
            @Override public void completed(ProcessSolveServices.FluidIslandCompletion completion) {
                helper.assertTrue(server.isSameThread(),"Completion left the owner thread");
                completed[0]++;byTick.merge(server.getTickCount(),1,Integer::sum);
            }
            @Override public void abandoned(ProcessSolveServices.FluidIslandRequest request) {helper.fail("Continuation job was abandoned");}
        };
        Runnable pump=()->{
            if(issued[0]>=65||issued[0]!=completed[0])return;
            var request=new ProcessSolveServices.FluidIslandRequest(ProcessSolveServices.nextRequestId(),
                    new ProcessSolveServices.FluidIslandTarget(helper.getLevel().dimension(),owner),issued[0],handler);
            if(ProcessSolveServices.submitFluidIsland(server,request,command).accepted())issued[0]++;
        };
        Runnable restore=()->{
            com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.ENABLED=false;
            if(previousPump[0]!=null){ProcessSolveServices.setReadinessPump(server,previousPump[0]);previousPump[0]=null;}
        };
        java.util.function.IntFunction<Long> counter=index->com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.sample().get(index==0?"drainContinuations":"drainContinuationsDeferred");
        helper.startSequence().thenWaitUntil(()->{
            var diagnostics=ProcessSolveServices.diagnostics(server);
            helper.assertTrue(diagnostics.outstandingJobs()==0&&diagnostics.pendingCompletions()==0,"Waiting for a quiescent shared service");
        }).thenIdle(1).thenExecute(()->{
            try {
                com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.ENABLED=true;counted[0]=counter.apply(0);counted[1]=counter.apply(1);
                saturatedTick[0]=server.getTickCount();started[0]=System.nanoTime();
                previousPump[0]=ProcessSolveServices.setReadinessPump(server,pump);pump.run();
                server.managedBlock(()->completed[0]>=64&&ProcessSolveServices.diagnostics(server).pendingCompletions()>=1||System.nanoTime()-started[0]>=5_000_000_000L);
                helper.assertTrue(server.getTickCount()==saturatedTick[0],"Managed block crossed a server tick");
                helper.assertTrue(completed[0]==64&&issued[0]==65,"The 65th terminal must be left behind by the exhausted budget");
                // Keep servicing this tick's mailbox: the owed continuation runs here and must not drain.
                long settle=System.nanoTime()+200_000_000L;server.managedBlock(()->System.nanoTime()>=settle);
                helper.assertTrue(server.getTickCount()==saturatedTick[0],"Settling crossed a server tick");
                helper.assertTrue(completed[0]==64,"The continuation re-entered the exhausted tick");
                helper.assertTrue(counter.apply(0)-counted[0]==1,"Exactly one continuation is owed for the exhausted tick, got "+(counter.apply(0)-counted[0]));
                helper.assertTrue(counter.apply(1)-counted[1]==1,"The continuation must run and defer inside the exhausted tick");
                helper.assertTrue(ProcessSolveServices.diagnostics(server).pendingCompletions()==1,"The deferred terminal is still retained");
            } catch(RuntimeException failure) {
                restore.run();helper.fail("Continuation fixture failed: "+failure);
            } catch(AssertionError failure) {restore.run();throw failure;}
        }).thenWaitUntil(()->{
            helper.assertTrue(server.getTickCount()>saturatedTick[0],"Waiting for the next server tick");
            helper.assertTrue(completed[0]==65,"Waiting for the stranded completion");
        }).thenExecute(()->{
            try {
                helper.assertTrue(byTick.getOrDefault(saturatedTick[0],0)==64,"The exhausted tick routed more than its budget");
                helper.assertTrue(byTick.keySet().stream().filter(tick->tick>saturatedTick[0]).count()==1,"The stranded completion was routed on one later tick");
                helper.assertTrue(ProcessSolveServices.diagnostics(server).outstandingJobs()==0,"Terminal ownership leaked");
                helper.assertTrue(counter.apply(0)-counted[0]==1,"No further continuation without further exhaustion");
            } finally {restore.run();}
        }).thenSucceed();
    }
}
