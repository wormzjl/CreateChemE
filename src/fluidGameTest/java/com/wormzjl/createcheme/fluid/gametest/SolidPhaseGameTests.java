package com.wormzjl.createcheme.fluid.gametest;

import com.mojang.authlib.GameProfile;
import com.wormzjl.createcheme.registry.*;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.InlineFilter;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.world.item.RecoveredSolidsItem;
import com.wormzjl.createcheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class SolidPhaseGameTests {
    private SolidPhaseGameTests(){}
    @GameTest(template="empty",timeoutTicks=200000,batch="fluid-solids")
    public static void filterCaptureRecoveryPersistenceAndBreakDropConserveOwnedSolids(GameTestHelper helper){
        // Give asynchronous workers wall time; unpaced test ticks otherwise create hours of catch-up debt.
        helper.onEachTick(()->java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var source=helper.absolutePos(new BlockPos(0,2,0));var pump=source.east();var left=source.east(2);var filter=source.east(3);var right=source.east(4);var tank=source.east(5);
        level.setBlock(source,ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
        level.setBlock(pump,ModBlocks.FLUID_PUMP.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST),3);
        level.setBlock(left,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        level.setBlock(filter,ModBlocks.INLINE_FILTER.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST),3);
        level.setBlock(right,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        level.setBlock(tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long sourceId=((FluidDeviceBlockEntity)level.getBlockEntity(source)).fluidIdentity();
        long filterId=((FluidDeviceBlockEntity)level.getBlockEntity(filter)).fluidIdentity();
        long tankId=((FluidDeviceBlockEntity)level.getBlockEntity(tank)).fluidIdentity();
        var registration=world.registrations().get(sourceId);
        world.edit(sourceId,registration.revision(),registration.device(),new FluidDeviceSpec(1,298.15,101325,registration.spec().composition(),SlurryFeed.demo()));
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.fromString("9f5c1da7-915c-4d47-8c9f-1a4b29fe91c0"),"SolidFilterTest"));
        InlineFilter[] first={null},second={null};
        helper.startSequence()
            .thenWaitUntil(()->helper.assertTrue(world.view(filterId).filter()!=null&&world.view(filterId).filter().captured().massKg()>1e-6,"Waiting for filter capture: "+world.view(filterId).status()))
            .thenExecute(()->{level.removeBlock(left,false);level.removeBlock(right,false);})
            .thenWaitUntil(()->helper.assertTrue(world.capture().world().events().isEmpty(),"Waiting for disconnected filter to retain its cake"))
            .thenExecute(()->{
                first[0]=world.view(filterId).filter();helper.assertTrue(first[0]!=null&&!first[0].captured().empty(),"Disconnected filter lost solids");
                var capture=world.capture();
                var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
                var data=new FluidSavedData(capture.checkpoint(),capture.world(),key->model);var saved=data.save(new CompoundTag(),level.registryAccess());
                var loaded=FluidSavedData.load(saved,key->model,data.store().reopen());
                var restored=loaded.checkpoint().islands().stream().flatMap(i->i.snapshot().graph().pipes().stream()).filter(p->p.id()==PhysicalFluidTopology.filterIdentity(filterId)).findFirst().orElseThrow().filter();
                helper.assertTrue(first[0].equals(restored),"Captured solids changed during save/load");
                var receiver=capture.checkpoint().islands().stream().flatMap(i->i.snapshot().graph().reservoirs().stream()).filter(n->n.id()==tankId).findFirst().orElseThrow();
                helper.assertTrue(receiver.inventory().solids().empty(),"Filter passed particles into the receiving tank");
                for(int i=0;i<player.getInventory().items.size();i++)player.getInventory().items.set(i,new ItemStack(Items.STONE,64));
                boolean refused=false;try{world.recoverFilter(filterId,world.registrations().get(filterId).revision(),player);}catch(IllegalStateException full){refused=true;}
                helper.assertTrue(refused,"Full inventory did not refuse recovery");helper.assertTrue(first[0].equals(world.view(filterId).filter()),"Rejected recovery changed the filter");
                player.getInventory().clearContent();registerLookup(level.getServer().getPlayerList(),player);
                world.recoverFilter(filterId,world.registrations().get(filterId).revision(),player);
            })
            .thenWaitUntil(()->helper.assertTrue(player.getInventory().items.stream().anyMatch(s->s.is(ModItems.RECOVERED_SOLIDS.get())),"Waiting for owned recovery delivery: events="+world.capture().world().events().size()+" recoveries="+world.capture().world().recoveries().size()+" filter="+world.view(filterId).status()))
            .thenExecute(()->{
                var items=player.getInventory().items.stream().filter(s->s.is(ModItems.RECOVERED_SOLIDS.get())).toList();
                helper.assertTrue(items.size()==1&&items.getFirst().getCount()==1,"Recovery duplicated an item");
                var contents=RecoveredSolidsItem.contents(items.getFirst()).orElseThrow();
                helper.assertTrue(contents.solids().equals(first[0].captured())&&contents.energyJoule()==first[0].energyJoule(),"Recovered item changed mass, size, or energy");
                helper.assertTrue(world.view(filterId).filter().captured().empty(),"Recovered stock remains in filter");
                helper.assertTrue(RecoveredSolidsItem.carriesTransfer(items.getFirst(),contents.transfer()),"Recovery identity was lost");
                boolean repeated=false;try{world.recoverFilter(filterId,world.registrations().get(filterId).revision(),player);}catch(IllegalStateException empty){repeated=true;}
                helper.assertTrue(repeated&&player.getInventory().items.stream().filter(s->s.is(ModItems.RECOVERED_SOLIDS.get())).count()==1,"Repeated recovery duplicated solids");
                unregisterLookup(level.getServer().getPlayerList(),player);
                level.setBlock(left,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);level.setBlock(right,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
            })
            .thenWaitUntil(()->helper.assertTrue(world.view(filterId).filter()!=null&&world.view(filterId).filter().captured().massKg()>1e-6,"Waiting for refill after recovery"))
            .thenExecute(()->{level.removeBlock(left,false);level.removeBlock(right,false);})
            .thenWaitUntil(()->helper.assertTrue(world.capture().world().events().isEmpty(),"Waiting for refill isolation"))
            .thenExecute(()->{second[0]=world.view(filterId).filter();level.removeBlock(filter,false);})
            .thenWaitUntil(()->helper.assertTrue(!level.getEntitiesOfClass(ItemEntity.class,new AABB(filter).inflate(2),e->e.getItem().is(ModItems.RECOVERED_SOLIDS.get())).isEmpty(),"Waiting for break-drop delivery"))
            .thenExecute(()->{
                var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(filter).inflate(2),e->e.getItem().is(ModItems.RECOVERED_SOLIDS.get()));
                helper.assertTrue(drops.size()==1,"Breaking a filter duplicated its contents");
                var contents=RecoveredSolidsItem.contents(drops.getFirst().getItem()).orElseThrow();
                helper.assertTrue(contents.solids().equals(second[0].captured())&&contents.energyJoule()==second[0].energyJoule(),"Break drop lost captured material or energy");
                helper.assertTrue(!world.registrations().containsKey(filterId),"Broken filter remains registered");
                drops.forEach(ItemEntity::discard);player.getInventory().clearContent();
                level.removeBlock(source,false);level.removeBlock(pump,false);level.removeBlock(tank,false);
            })
            .thenWaitUntil(()->helper.assertTrue(world.capture().world().events().isEmpty(),"Waiting for cleanup"))
            .thenSucceed();
    }
    /** Populate only the lookup used by recovery; no fake network connection or player tick is needed. */
    @SuppressWarnings("unchecked")
    private static Map<UUID,ServerPlayer> lookups(net.minecraft.server.players.PlayerList list){
        try{var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);return (Map<UUID,ServerPlayer>)field.get(list);}
        catch(ReflectiveOperationException failure){throw new AssertionError("Player lookup test adapter changed",failure);}
    }
    private static void registerLookup(net.minecraft.server.players.PlayerList list,ServerPlayer player){if(lookups(list).putIfAbsent(player.getUUID(),player)!=null)throw new AssertionError("Duplicate test player");}
    private static void unregisterLookup(net.minecraft.server.players.PlayerList list,ServerPlayer player){lookups(list).remove(player.getUUID(),player);}
}