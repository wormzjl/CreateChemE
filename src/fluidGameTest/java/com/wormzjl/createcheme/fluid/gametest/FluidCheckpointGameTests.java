package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/**
 * Checkpoint format 3 through the world (plan section 3.5, section 5 item 8): a world with a resting tank is saved by
 * its own saved-data path, and the checkpoint is loaded into a fresh coordinator standing in for the restarted server.
 * The tank comes back certified, its status line continuing from the saved since tick, and it is advanced by
 * materialisation alone: no slice is ever handed to a worker.
 */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidCheckpointGameTests {
    private FluidCheckpointGameTests() {}
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-persistence-certified")
    public static void aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var world=FluidWorldAuthority.find(server).orElseThrow();
        var options=com.wormzjl.createcheme.CreateChemE.fluidOptions();
        // The world's own model construction, so the restored certificate's signature is the one the world gives it.
        java.util.function.Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models=key->FluidThermodynamics.forNetwork(MaterialRuntime.active(),key.packageId(),key.compressibility(),options.maximumVelocity(),options.traceCutoffMoleFraction(),options.solids());
        var pos=helper.absolutePos(new BlockPos(0,1,0));level.setBlock(pos,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long device=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
        java.util.function.Supplier<IslandCoordinator.Snapshot> stored=()->world.diagnosticSnapshots().stream().filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==device)).findFirst().orElseThrow();
        IslandCoordinator[] restarted={null};int[] submitted={0};IslandCoordinator.Snapshot[] saved={null};String[] status={null};
        helper.onEachTick(()->{if(restarted[0]!=null)restarted[0].tick();});
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(stored.get().certificate().isPresent(),"Waiting for the tank to certify"))
        .thenExecute(()->{
            // The world's saved data, saved exactly as an autosave would save it.
            var data=FluidSavedData.open(server,models);var tag=data.save(new CompoundTag(),level.registryAccess());
            helper.assertTrue(tag.getInt("FluidFormat")==FluidCheckpointCodec.VERSION,"Not format 3");
            var loaded=FluidSavedData.load(tag,models);
            helper.assertTrue(loaded.world().orElseThrow().onlineTick()==world.onlineTick(),"The topology and the epoch did not round-trip");
            var entry=loaded.checkpoint().islands().stream().filter(e->e.snapshot().graph().reservoirs().stream().anyMatch(n->n.id()==device)).findFirst().orElseThrow();
            saved[0]=entry.snapshot();var live=stored.get();status[0]=live.status();
            var certificate=saved[0].certificate().orElseThrow();
            helper.assertTrue(certificate.kind()==IslandCertificate.Kind.REST&&certificate.saved().isPresent(),"The saved tank is not a resting certificate: "+certificate);
            helper.assertTrue(certificate.sinceTick()==live.certificate().orElseThrow().sinceTick(),"The since tick did not round-trip");
            helper.assertTrue(saved[0].status().equals(status[0])&&status[0].startsWith("RESTING: no flow since "+String.format(Locale.ROOT,"%.1f",certificate.sinceTick()/20.0)+" s"),"Saved status: "+saved[0].status());
            // The restarted server's coordinator: its dispatcher fails the test if the tank is ever handed to a worker.
            var dispatcher=new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 64;}
                public long nextRequestId(){return ProcessSolveServices.nextRequestId();}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){submitted[0]++;return false;}
                public void cancel(long requestId){}
            };
            restarted[0]=new IslandCoordinator(dispatcher,changed->{},System::nanoTime,new IslandCoordinator.Settings(options.wallBudgetNanos(),options.wallBudgetNanos()*3/4,64,false,options.initialCadenceTicks(),world.certificates()));
            restarted[0].register(saved[0],models.apply(new FluidCheckpointCodec.PackageKey(entry.packageId(),entry.compressibility())));
            var restored=restarted[0].observe(saved[0].id());
            helper.assertTrue(restored.certificate().isPresent(),"The restarted tank was not restored certified: "+restored.status()+" / "+restarted[0].certificationRefusal(saved[0].id()));
            helper.assertTrue(restored.status().equals(status[0]),"The status line did not continue: "+restored.status());
        }).thenIdle(200).thenExecute(()->{
            var id=saved[0].id();var read=restarted[0].snapshot(id);
            helper.assertTrue(submitted[0]==0&&restarted[0].pendingCount()==0,"The restarted tank was solved: "+submitted[0]+" submissions");
            helper.assertTrue(read.clock().committedTick()==read.clock().onlineTick()&&read.clock().committedTick()>=saved[0].clock().committedTick()+150,"Materialisation did not advance the restarted tank to now: "+read.clock()+" from "+saved[0].clock());
            helper.assertTrue(read.certificate().orElseThrow().sinceTick()==saved[0].certificate().orElseThrow().sinceTick()&&read.status().equals(status[0]),"The status line moved: "+read.status());
            helper.assertTrue(restarted[0].metrics(id).orElseThrow().advance()==IslandCoordinator.Advance.RESTED,"Not advanced by the identity");
            for(int n=0;n<read.graph().reservoirs().size();n++)helper.assertTrue(read.graph().reservoirs().get(n).inventory().equals(saved[0].graph().reservoirs().get(n).inventory()),"Rest changed the inventory");
            restarted[0].stop();restarted[0]=null;
            helper.assertTrue(stored.get().status().equals(status[0]),"The live tank's status line changed: "+stored.get().status());
            level.removeBlock(pos,false);
        }).thenWaitUntil(()->helper.assertTrue(!world.capture().world().active().containsKey(device),"Waiting for topology cleanup")).thenSucceed();
    }
}
