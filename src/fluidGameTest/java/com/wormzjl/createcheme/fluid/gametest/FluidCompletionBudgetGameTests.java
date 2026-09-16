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
}
