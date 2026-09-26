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
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/**
 * Checkpoint format 4 through the world: a world with a resting tank is saved by its own saved-data path, exactly as an
 * autosave saves it - prepared on the server thread, the new pack and the core record written on NeoForge's IO worker -
 * and the files are copied as a restarted server finds them and loaded into a fresh store and a fresh coordinator. The
 * tank comes back certified, its status line continuing from the saved since tick; the restarted store, seeded by the
 * load, writes no unit for it at its first save; and it is advanced by materialisation alone: no slice is ever handed
 * to a worker.
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
        var dataFolder=server.getWorldPath(LevelResource.ROOT).resolve("data");var restart=dataFolder.resolve("fluid-restart-"+UUID.randomUUID());
        IslandCoordinator[] restarted={null};int[] submitted={0};IslandCoordinator.Snapshot[] saved={null};String[] status={null};FluidSavedData[] data={null};
        helper.onEachTick(()->{if(restarted[0]!=null)restarted[0].tick();});
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(stored.get().certificate().isPresent(),"Waiting for the tank to certify"))
        .thenExecute(()->{
            // The world's saved data, saved exactly as an autosave saves it: Minecraft's SavedData.save(File) path.
            data[0]=FluidSavedData.open(server,models);data[0].setDirty();data[0].save(dataFolder.resolve(FluidCheckpointStore.CORE_NAME).toFile(),level.registryAccess());
            helper.assertTrue(!data[0].isDirty()&&data[0].lastSave().writeNanos()==0,"The save was not handed to the IO worker");
        }).thenWaitUntil(()->helper.assertTrue(data[0].store().committed(),"Waiting for the IO worker's commit"))
        .thenExecute(()->{
            try {
                // The files as a restarted server finds them: the core record and every pack it lists, copied.
                var core=dataFolder.resolve(FluidCheckpointStore.CORE_NAME);var packs=dataFolder.resolve(FluidCheckpointStore.UNIT_DIRECTORY);
                helper.assertTrue(Files.exists(core)&&Files.isDirectory(packs),"No core record or pack folder on disk");
                Files.createDirectories(restart.resolve(FluidCheckpointStore.UNIT_DIRECTORY));Files.copy(core,restart.resolve(FluidCheckpointStore.CORE_NAME));
                try(var files=Files.list(packs)){for(var file:(Iterable<Path>)files::iterator)Files.copy(file,restart.resolve(FluidCheckpointStore.UNIT_DIRECTORY).resolve(file.getFileName()));}
                var raw=net.minecraft.nbt.NbtIo.readCompressed(core,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                helper.assertTrue(raw.getCompound("data").getInt("FluidFormat")==FluidCheckpointCodec.VERSION&&raw.contains("DataVersion"),"Not format 4 as a saved data file");
                var store=FluidCheckpointStore.directory(restart,net.neoforged.neoforge.common.IOUtilities::withIOWorker,true);
                var loaded=FluidSavedData.read(store,models).orElseThrow();
                helper.assertTrue(loaded.world().orElseThrow().onlineTick()==raw.getCompound("data").getLong("Epoch"),"The topology and the epoch did not round-trip");
                var entry=loaded.checkpoint().islands().stream().filter(e->e.snapshot().graph().reservoirs().stream().anyMatch(n->n.id()==device)).findFirst().orElseThrow();
                saved[0]=entry.snapshot();var live=stored.get();status[0]=live.status();
                var certificate=saved[0].certificate().orElseThrow();
                helper.assertTrue(certificate.drift()==0&&certificate.largestFlow()==0&&certificate.saved().isPresent(),"The saved tank is not a resting certificate: "+certificate);
                helper.assertTrue(certificate.sinceTick()==live.certificate().orElseThrow().sinceTick(),"The since tick did not round-trip");
                helper.assertTrue(saved[0].status().equals(status[0])&&status[0].startsWith("STEADY: no flow since "+String.format(Locale.ROOT,"%.1f",certificate.sinceTick()/20.0)+" s"),"Saved status: "+saved[0].status());
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
                // The restarted store was seeded by the load: its first save writes no unit for the unchanged tank and no topology.
                loaded.bindCapture(()->new FluidSavedData.Capture(new FluidCheckpointCodec.Checkpoint(restarted[0].snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry(entry.dimension(),entry.packageId(),entry.compressibility(),s)).toList(),
                        loaded.checkpoint().transfers()),loaded.world().orElseThrow()));
                loaded.save(new CompoundTag(),level.registryAccess());var first=loaded.lastSave();
                helper.assertTrue(first.payloadsEncoded()==0&&first.payloadsReused()==1&&!first.topologyEncoded()&&first.stats().encodedBytes()==0,"The first save after the load encoded units: "+first.stats());
            }catch(java.io.IOException failure){helper.fail("Checkpoint files: "+failure.getMessage());}
        }).thenIdle(200).thenExecute(()->{
            var id=saved[0].id();var read=restarted[0].snapshot(id);
            helper.assertTrue(submitted[0]==0&&restarted[0].pendingCount()==0,"The restarted tank was solved: "+submitted[0]+" submissions");
            helper.assertTrue(read.clock().committedTick()==read.clock().onlineTick()&&read.clock().committedTick()>=saved[0].clock().committedTick()+150,"Materialisation did not advance the restarted tank to now: "+read.clock()+" from "+saved[0].clock());
            helper.assertTrue(read.certificate().orElseThrow().sinceTick()==saved[0].certificate().orElseThrow().sinceTick()&&read.status().equals(status[0]),"The status line moved: "+read.status());
            helper.assertTrue(restarted[0].metrics(id).orElseThrow().advance()==IslandCoordinator.Advance.REPLAYED,"Not advanced by the certificate");
            for(int n=0;n<read.graph().reservoirs().size();n++)helper.assertTrue(read.graph().reservoirs().get(n).inventory().equals(saved[0].graph().reservoirs().get(n).inventory()),"Rest changed the inventory");
            restarted[0].stop();restarted[0]=null;
            helper.assertTrue(stored.get().status().equals(status[0]),"The live tank's status line changed: "+stored.get().status());
            try(var files=Files.walk(restart)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}catch(java.io.IOException failure){helper.fail("Cleanup: "+failure.getMessage());}
            level.removeBlock(pos,false);
        }).thenWaitUntil(()->helper.assertTrue(!world.capture().world().active().containsKey(device),"Waiting for topology cleanup")).thenSucceed();
    }
}
