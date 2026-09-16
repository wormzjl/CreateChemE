package com.wormzjl.createcheme.fluid.gametest;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidTopologyGameTests {
    private FluidTopologyGameTests() {}
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-independent-events")
    public static void anUnresolvedIslandHorizonDoesNotBlockUnrelatedPhysicalEdits(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var tank=helper.absolutePos(new BlockPos(0,1,0));var pipe=tank.east();var source=pipe.east();var independent=tank.north(8);
        level.setBlock(tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);level.setBlock(pipe,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);level.setBlock(source,ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
        long tankId=((FluidDeviceBlockEntity)level.getBlockEntity(tank)).fluidIdentity(),sourceId=((FluidDeviceBlockEntity)level.getBlockEntity(source)).fluidIdentity();
        var coordinator=coordinator(world);var island=world.capture().checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==tankId)).findFirst().orElseThrow();
        var held=UUID.randomUUID();coordinator.fence(held,island.clock().committedTick(),Set.of(island.id()));long[] editTick={-1};
        helper.startSequence().thenIdle(2).thenExecute(()->{
            try {
                var before=world.registrations().get(sourceId);editTick[0]=world.onlineTick();
                world.edit(sourceId,before.revision(),before.device(),new FluidDeviceSpec(before.spec().volume(),before.spec().temperature(),200000,before.spec().composition()));
                helper.assertTrue(!world.capture().world().events().isEmpty(),"The dependent edit should wait at the unresolved horizon");
                level.setBlock(independent,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
                long id=((FluidDeviceBlockEntity)level.getBlockEntity(independent)).fluidIdentity();
                var capture=world.capture();helper.assertTrue(capture.world().active().containsKey(id),"Unrelated placement was blocked by an earlier island event");
                helper.assertTrue(world.view(id).state()!=null,"Unrelated reservoir has no initialized authority");
                helper.assertTrue(capture.world().events().stream().anyMatch(e->e.tick()==editTick[0]&&e.edits().stream().anyMatch(change->change.id()==sourceId)),"Blocked edit was dropped or retimestamped");
                helper.assertTrue(coordinator.snapshot(island.id()).clock().committedTick()==island.clock().committedTick(),"Blocked island advanced past its causal horizon");
                level.removeBlock(independent,false);helper.assertTrue(!world.capture().world().active().containsKey(id),"Unrelated removal was blocked");
            } finally {
                coordinator.releaseFence(held,Set.of(island.id()));
            }
        }).thenWaitUntil(()->helper.assertTrue(world.capture().world().events().isEmpty(),"Waiting for the original timestamped edit after horizon release"))
        .thenExecute(()->{level.removeBlock(source,false);level.removeBlock(pipe,false);level.removeBlock(tank,false);})
        .thenWaitUntil(()->helper.assertTrue(world.capture().world().events().isEmpty(),"Waiting for topology cleanup")).thenSucceed();
    }
    /** Controlled unresolved dependency; no scientific state or worker result is fabricated. */
    private static IslandCoordinator coordinator(FluidWorldAuthority world) {
        try {var field=FluidWorldAuthority.class.getDeclaredField("runtime");field.setAccessible(true);return ((MinecraftFluidRuntime)field.get(world)).coordinator();}
        catch(ReflectiveOperationException failure){throw new AssertionError("World coordinator test adapter changed",failure);}
    }
}
