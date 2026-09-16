package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.createcheme.science.fluid.linalg.SparseMatrix;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidHarnessGameTests {
    private FluidHarnessGameTests() {}
    @GameTest(template="empty",timeoutTicks=200000,batch="fluid-world-unloaded")
    public static void registeredPipesAndReservoirsKeepSimulatingAcrossUnloadedChunks(GameTestHelper helper) {
        var level=helper.getLevel();var world=com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var source=new net.minecraft.core.BlockPos(((origin.getX()>>4)+64)*16+8,80,(origin.getZ()>>4)*16+8);var reservoir=source.east(128);var middle=source.east(64);
        var a=new net.minecraft.world.level.ChunkPos(source);var b=new net.minecraft.world.level.ChunkPos(reservoir);
        level.setChunkForced(a.x,a.z,true);level.setChunkForced(b.x,b.z,true);
        level.setBlock(source,com.wormzjl.createcheme.registry.ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
        for(int x=1;x<128;x++)level.setBlock(source.east(x),com.wormzjl.createcheme.registry.ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        level.setBlock(reservoir,com.wormzjl.createcheme.registry.ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long sourceId=((com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity)level.getBlockEntity(source)).fluidIdentity();
        long tankId=((com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity)level.getBlockEntity(reservoir)).fluidIdentity();
        var registration=world.registrations().get(sourceId);var spec=registration.spec();
        world.edit(sourceId,registration.revision(),registration.device(),new com.wormzjl.createcheme.runtime.fluid.FluidDeviceSpec(spec.volume(),spec.temperature(),200000,spec.composition()));
        double initialNitrogen=world.view(tankId).state().phaseMoles()[2][20];int[] phase={0};double[] before={0};long[] changedAt={0};
        helper.succeedWhen(()->{
            if(phase[0]==0) {
                helper.assertTrue(!level.hasChunkAt(middle),"Waiting for intermediate pipe chunks to unload");
                var view=world.view(tankId);helper.assertTrue(view.state().phaseMoles()[1][21]>0,"No transfer across unloaded middle chunks");
                helper.assertTrue(level.hasChunkAt(source)&&level.hasChunkAt(reservoir),"Test endpoint tickets were not retained");
                level.setChunkForced(a.x,a.z,false);level.setChunkForced(b.x,b.z,false);phase[0]=1;
                helper.assertTrue(false,"Waiting for endpoint chunks to unload");
            }
            if(phase[0]==1) {
                helper.assertTrue(!level.hasChunkAt(source)&&!level.hasChunkAt(reservoir),"Waiting for all endpoint chunks to unload");
                before[0]=world.view(tankId).state().phaseMoles()[1][21];changedAt[0]=world.onlineTick();var current=world.registrations().get(sourceId);var s=current.spec();
                world.edit(sourceId,current.revision(),current.device(),new com.wormzjl.createcheme.runtime.fluid.FluidDeviceSpec(s.volume(),s.temperature(),250000,s.composition()));phase[0]=2;
                helper.assertTrue(false,"Waiting for the unloaded configuration event and new flow");
            }
            if(phase[0]==2) {
                var view=world.view(tankId);helper.assertTrue(view.committedTick()>changedAt[0]&&view.state().phaseMoles()[1][21]>before[0]+1e-6,"Waiting for changed flow with every endpoint unloaded");
                helper.assertTrue(!level.hasChunkAt(source)&&!level.hasChunkAt(reservoir)&&!level.hasChunkAt(middle),"Simulation loaded a chunk");
                level.getChunk(reservoir);world.refreshLoaded(tankId);
                var entity=(com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity)level.getBlockEntity(reservoir);
                helper.assertTrue(entity.fluidIdentity()==tankId,"Reload changed the tank identity");
                helper.assertTrue(Math.abs(world.view(tankId).state().phaseMoles()[2][20]-initialNitrogen)<1e-8,"Reload recreated nitrogen");
                for(int x=0;x<=128;x++)level.removeBlock(source.east(x),false);phase[0]=3;
                helper.assertTrue(false,"Waiting for test topology cleanup");
            }
            var saved=world.capture();helper.assertTrue(saved.world().active().isEmpty()&&saved.world().events().isEmpty()&&saved.checkpoint().islands().isEmpty(),"Waiting for all cleanup transactions");
            helper.assertTrue(!level.getForcedChunks().contains(a.toLong())&&!level.getForcedChunks().contains(b.toLong()),"Test endpoint tickets leaked");
        });
    }
    @GameTest(template="empty",timeoutTicks=100000,batch="fluid-world")
    public static void gameplayPlacementTransfersFluidAndRemovalClosesThePersistentOwnershipLedger(GameTestHelper helper) {
        var level=helper.getLevel();var world=com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var reservoir=helper.absolutePos(new net.minecraft.core.BlockPos(0,1,0));var pipe=reservoir.east();var source=pipe.east();
        double oldConstructed=world.capture().world().constructed().moles()[20],oldDestroyed=world.capture().world().destroyed().moles()[20];
        level.setBlock(reservoir,com.wormzjl.createcheme.registry.ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        level.setBlock(pipe,com.wormzjl.createcheme.registry.ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        level.setBlock(source,com.wormzjl.createcheme.registry.ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
        var r=(com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity)level.getBlockEntity(reservoir);
        var p=(com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity)level.getBlockEntity(pipe);
        var g=(com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity)level.getBlockEntity(source);
        helper.assertTrue(r.fluidIdentity()>0&&p.fluidIdentity()>0&&g.fluidIdentity()>0,"Placement did not bind stable identities");
        long tankId=r.fluidIdentity(),pipeId=p.fluidIdentity();var initial=world.view(tankId).state();double nitrogen=initial.phaseMoles()[2][20];
        var registration=world.registrations().get(g.fluidIdentity());var spec=registration.spec();
        world.edit(g.fluidIdentity(),registration.revision(),registration.device(),new com.wormzjl.createcheme.runtime.fluid.FluidDeviceSpec(spec.volume(),spec.temperature(),200000,spec.composition()));
        int[] phase={0};
        helper.succeedWhen(()->{
            if(phase[0]==0) {
                var view=world.view(tankId);helper.assertTrue(view.state()!=null&&view.state().phaseMoles()[1][21]>0,"Waiting for pressure-driven water filling");
                helper.assertTrue(Math.abs(view.state().phaseMoles()[2][20]-nitrogen)<1e-8,"Initial nitrogen was lost or recreated");
                helper.assertTrue(!world.view(pipeId).pipeHistory().isEmpty(),"Pipe has no committed phase/flow history");
                var capture=world.capture();var data=new com.wormzjl.createcheme.runtime.fluid.FluidSavedData(capture.checkpoint(),capture.world(),key->com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.forNetwork(com.wormzjl.createcheme.science.material.MaterialRuntime.active(),key.packageId(),key.compressibility()));
                var tag=data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
                var restored=com.wormzjl.createcheme.runtime.fluid.FluidSavedData.load(tag,key->com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.forNetwork(com.wormzjl.createcheme.science.material.MaterialRuntime.active(),key.packageId(),key.compressibility()));
                helper.assertTrue(restored.world().orElseThrow().active().size()==3,"Physical registry was not saved with inventory");
                level.removeBlock(source,false);level.removeBlock(pipe,false);level.removeBlock(reservoir,false);phase[0]=1;
                helper.assertTrue(false,"Waiting for removal event alignment");
            }
            var finalState=world.capture();helper.assertTrue(finalState.world().active().isEmpty()&&finalState.world().events().isEmpty()&&finalState.checkpoint().islands().isEmpty(),"Waiting for authoritative removal transactions");
            helper.assertTrue(Math.abs(finalState.world().constructed().moles()[20]-oldConstructed-nitrogen)<1e-8,"Construction charged nitrogen more than once");
            helper.assertTrue(Math.abs(finalState.world().destroyed().moles()[20]-oldDestroyed-nitrogen)<1e-8,"Removal did not account for the retained nitrogen");
        });
    }
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-persistence")
    public static void savedDataKeepsADepletedNitrogenChargeAndAtomicWritesKeepThePreviousCheckpoint(GameTestHelper helper) throws java.io.IOException {
        var model=com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.forNetwork(com.wormzjl.createcheme.science.material.MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var graph=new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork(java.util.List.of(new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,65000,()->{}))),java.util.List.of());
        var island=new com.wormzjl.createcheme.runtime.fluid.IslandCoordinator.Snapshot(1,0,graph,new com.wormzjl.createcheme.runtime.fluid.IslandClock.Snapshot(250,100,0,100),com.wormzjl.createcheme.runtime.fluid.FallbackAllowance.NONE,java.util.Optional.empty(),java.util.Optional.empty(),"HELD");
        var checkpoint=new com.wormzjl.createcheme.runtime.fluid.FluidCheckpointCodec.Checkpoint(java.util.List.of(new com.wormzjl.createcheme.runtime.fluid.FluidCheckpointCodec.IslandEntry("minecraft:overworld","createcheme:tjl20_methane",1e-9,island)),new com.wormzjl.createcheme.runtime.fluid.BufferedTransfers.Snapshot(0,java.util.Map.of(),java.util.Map.of()));
        var data=new com.wormzjl.createcheme.runtime.fluid.FluidSavedData(checkpoint,key->model);data.setDirty();
        var folder=helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/fluid-checkpoint-tests");java.nio.file.Files.createDirectories(folder);
        var path=folder.resolve(java.util.UUID.randomUUID()+".dat");data.save(path.toFile(),helper.getLevel().registryAccess());
        helper.succeedWhen(()->{
            helper.assertTrue(java.nio.file.Files.exists(path),"Waiting for the SavedData IO worker");
            try {
                var root=net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                var loaded=com.wormzjl.createcheme.runtime.fluid.FluidSavedData.load(root.getCompound("data"),key->model).checkpoint().islands().getFirst().snapshot();
                helper.assertTrue(loaded.graph().reservoirs().getFirst().inventory().equals(graph.reservoirs().getFirst().inventory()),"Save/load recreated or changed nitrogen inventory");
                helper.assertTrue(loaded.clock().equals(island.clock()),"Save/load lost online debt or added offline time");
                var before=java.nio.file.Files.readAllBytes(path);
                boolean failed=false;
                try{net.neoforged.neoforge.common.IOUtilities.atomicWrite(path,out->{out.write(new byte[]{1,2,3});throw new java.io.IOException("Injected checkpoint write failure");});}
                catch(java.io.IOException expected){failed=true;}
                helper.assertTrue(failed&&java.util.Arrays.equals(before,java.nio.file.Files.readAllBytes(path)),"Failed write replaced the complete checkpoint");
                var corrupt=root.getCompound("data").copy();var bytes=corrupt.getByteArray("Checkpoint").clone();bytes[0]^=1;corrupt.putByteArray("Checkpoint",bytes);
                boolean rejected=false;try{com.wormzjl.createcheme.runtime.fluid.FluidSavedData.load(corrupt,key->model);}catch(IllegalArgumentException expected){rejected=true;}
                helper.assertTrue(rejected,"Corrupt fluid authority was accepted");java.nio.file.Files.delete(path);
            }catch(java.io.IOException failure){helper.fail("Checkpoint integration failed: "+failure.getMessage());}
        });
    }
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-coordinator")
    public static void worldRuntimeCatchesUpNineIntervalsThroughSharedWorkers(GameTestHelper helper) {
        var model=com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.forNetwork(com.wormzjl.createcheme.science.material.MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var graph=new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork(java.util.List.of(new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),java.util.List.of());
        int[] publications={0};
        var runtime=new com.wormzjl.createcheme.runtime.fluid.MinecraftFluidRuntime(helper.getLevel().getServer(),changed->{
            helper.assertTrue(helper.getLevel().getServer().isSameThread(),"Publication left server thread");publications[0]++;
        },com.wormzjl.createcheme.runtime.fluid.IslandCoordinator.Settings.defaults());
        long id=com.wormzjl.createcheme.runtime.ProcessSolveServices.nextRequestId();
        runtime.register(helper.getLevel().dimension(),new com.wormzjl.createcheme.runtime.fluid.IslandCoordinator.Snapshot(id,0,graph,
                new com.wormzjl.createcheme.runtime.fluid.IslandClock.Snapshot(900,0,0,100),com.wormzjl.createcheme.runtime.fluid.FallbackAllowance.NONE,
                java.util.Optional.empty(),java.util.Optional.empty(),"READY"),model);
        runtime.coordinator().pump();
        helper.succeedWhen(()->{
            var current=runtime.coordinator().snapshot(id);
            helper.assertTrue(current.clock().committedTick()==900,"Waiting for nine catch-up intervals");
            helper.assertTrue(publications[0]==9,"Unexpected publication count");
            helper.assertTrue(current.graph().reservoirs().getFirst().inventory().equals(graph.reservoirs().getFirst().inventory()),"Idle nitrogen charge changed");
            runtime.close();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void sparseSolverLoadsInServer(GameTestHelper helper) {
        if (Boolean.getBoolean("createcheme.fluid.testFailure")) {
            helper.fail("Intentional M0 GameTest failure: nonzero exit must propagate to Gradle");
            return;
        }
        var matrix = new SparseMatrix(2, new int[] {0, 1, 3}, new int[] {1, 0, 1}, new double[] {1, 2, 3});
        double[] result = SparseLuSolver.solve(matrix, new double[] {4, 7});
        if (Math.abs(result[0] - 1) > 1e-12 || Math.abs(result[1] - 2) > 1e-12) {
            helper.fail("Sparse solver returned the wrong pivoted solution");
            return;
        }
        helper.succeed();
    }

    // GameTestServer advances unpaced ticks. The command keeps its independent 2 s wall deadline.
    @GameTest(template="empty",timeoutTicks=10000)
    public static void fluidSnapshotRunsThroughTheSharedServiceAndReturnsOnServerThread(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var model=com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.forNetwork(
                com.wormzjl.createcheme.science.material.MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var state=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var graph=new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork(java.util.List.of(
                new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,200000,()->{})),
                new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Reservoir(2,0,state)),java.util.List.of(
                new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Pipe(1,0,1,new com.wormzjl.createcheme.science.fluid.network.PipeResistance.Geometry(100,.02,.000045,0))));
        var received=new java.util.ArrayList<com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandCompletion>();
        var handler=new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidCompletionHandler(){
            public void completed(com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandCompletion completion){helper.assertTrue(server.isSameThread(),"Fluid callback escaped server thread");received.add(completion);}
            public void abandoned(com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandRequest request){helper.fail("Fluid test request was abandoned");}
        };
        long requestId=com.wormzjl.createcheme.runtime.ProcessSolveServices.nextRequestId();
        var request=new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandRequest(requestId,
                new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandTarget(helper.getLevel().dimension(),requestId),1,handler);
        var command=new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandCommand(model,graph,5,
                com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults(),2_000_000_000L);
        var admission=com.wormzjl.createcheme.runtime.ProcessSolveServices.submitFluidIsland(server,request,command);
        helper.assertTrue(admission.accepted(),"Fluid request was not admitted to shared workers");
        helper.succeedWhen(()->{
            helper.assertTrue(received.size()==1,"Waiting for exactly one fluid completion");
            var completion=received.getFirst();helper.assertTrue(completion.completion().result().orElseThrow().candidate().isPresent(),"Fluid job did not produce a candidate");
            helper.assertTrue(completion.completion().result().orElseThrow().candidate().orElseThrow().averageMassFlows()[0]>0,"No pressure-driven transfer was calculated");
            helper.assertTrue(completion.request().requestId()==requestId,"Fluid request identity changed");
        });
    }
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-wakeup")
    public static void shortJobsResumeBetweenTicksWithoutExceedingTheDrainBudget(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var model=com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics.forNetwork(com.wormzjl.createcheme.science.material.MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var graph=new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork(java.util.List.of(new com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),java.util.List.of());
        var command=new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandCommand(model,graph,5,com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults(),2_000_000_000L);
        long started=System.nanoTime();
        int[] issued={0},completed={0};var byTick=new java.util.HashMap<Integer,Integer>();long island=com.wormzjl.createcheme.runtime.ProcessSolveServices.nextRequestId();
        var handler=new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidCompletionHandler(){
            public void completed(com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandCompletion completion){
                helper.assertTrue(server.isSameThread(),"Completion left the owner thread");helper.assertTrue(completion.completion().result().orElseThrow().candidate().isPresent(),"Idle job failed");completed[0]++;byTick.merge(server.getTickCount(),1,Integer::sum);
            }
            public void abandoned(com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandRequest request){helper.fail("Wakeup job abandoned");}
        };
        Runnable pump=()->{
            if(issued[0]>=70||issued[0]!=completed[0])return;
            var request=new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandRequest(com.wormzjl.createcheme.runtime.ProcessSolveServices.nextRequestId(),
                    new com.wormzjl.createcheme.runtime.ProcessSolveServices.FluidIslandTarget(helper.getLevel().dimension(),island),issued[0],handler);
            if(com.wormzjl.createcheme.runtime.ProcessSolveServices.submitFluidIsland(server,request,command).accepted())issued[0]++;
        };
        var previous=com.wormzjl.createcheme.runtime.ProcessSolveServices.setReadinessPump(server,pump);pump.run();
        // GameTestServer has no 50 ms inter-tick wait. Deliberately keep this test tick open,
        // servicing the real server mailbox, to prove completion wakeups need no next tick.
        server.managedBlock(()->completed[0]>=32||System.nanoTime()-started>=5_000_000_000L);
        helper.assertTrue(completed[0]>=32,"Completion wakeups require another game tick");
        helper.succeedWhen(()->{
            helper.assertTrue(completed[0]==70,"Waiting for all short jobs");
            helper.assertTrue(System.nanoTime()-started<5_000_000_000L,"Short-job sequence exceeded its real wall-time bound");
            helper.assertTrue(byTick.values().stream().anyMatch(count->count>1),"Workers waited for a server tick after every completion");
            helper.assertTrue(byTick.values().stream().allMatch(count->count<=64),"Completion budget exceeded");
            com.wormzjl.createcheme.runtime.ProcessSolveServices.setReadinessPump(server,previous);
        });
    }
}
