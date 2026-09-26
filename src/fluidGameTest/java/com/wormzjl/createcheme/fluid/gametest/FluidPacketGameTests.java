package com.wormzjl.createcheme.fluid.gametest;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.createcheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.Proxy;
import java.util.UUID;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPacketGameTests {
    private FluidPacketGameTests() {}
    @GameTest(template="empty",timeoutTicks=100,batch="fluid-ambient-generator")
    public static void generatorMenuAcceptsAmbientCrudeWithoutChangingTemperature(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var pos=helper.absolutePos(new BlockPos(0,1,0));
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"AmbientCrude"));
        player.setGameMode(GameType.CREATIVE);player.setPos(pos.getX()+1,pos.getY(),pos.getZ());
        try {
            for(var preset:com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog.resolve(
                    com.wormzjl.createcheme.science.material.MaterialRuntime.active()).subList(2,5)) {
                level.setBlock(pos,ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
                long identity=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
                player.containerMenu=new FluidDeviceMenu(43,player.getInventory(),pos,identity,false);
                var controls=new FluidNetwork.Controls(298,101325,.05,.000045,.01,500000,preset.moleFractions());
                var before=world.registrations().get(identity);
                send(player,new FluidNetwork.EditPayload(43,pos,identity,before.revision(),new Gson().toJson(controls)));
                var after=world.registrations().get(identity);
                helper.assertTrue(after.revision()==before.revision()+1,"Ambient generator edit rejected: "+preset.name());
                helper.assertTrue(after.spec().temperature()==298,"Generator temperature was clamped or reset");
                level.removeBlock(pos,false);
            }
            helper.succeed();
        } finally {level.removeBlock(pos,false);player.containerMenu=player.inventoryMenu;}
    }
    @GameTest(template="empty",timeoutTicks=100,batch="fluid-movement")
    public static void createAndVanillaMovementRejectEveryFluidBlock(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(0,1,0));
        for(var block:java.util.List.of(ModBlocks.FLUID_RESERVOIR.get(),ModBlocks.FLUID_PIPE.get(),ModBlocks.FLUID_PUMP.get(),ModBlocks.FLUID_COMPRESSOR.get(),ModBlocks.PRESSURE_CONTROL_VALVE.get(),ModBlocks.FLUID_GENERATOR.get(),ModBlocks.FLUID_VOID.get())) {
            var state=block.defaultBlockState();
            helper.assertTrue(!com.simibubi.create.api.contraption.BlockMovementChecks.isMovementAllowed(state,level,pos),"Create allowed moving "+block);
            helper.assertTrue(state.getPistonPushReaction()==net.minecraft.world.level.material.PushReaction.BLOCK,"Vanilla piston allowed moving "+block);
        }
        helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=3000,batch="fluid-menu-unload")
    public static void unloadedMenuDoesNotReloadItsChunkAndReplacementInvalidatesIdentity(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(BlockPos.ZERO);var pos=new BlockPos(((origin.getX()>>4)+96)*16+8,80,(origin.getZ()>>4)*16+8);
        var chunk=new net.minecraft.world.level.ChunkPos(pos);level.setChunkForced(chunk.x,chunk.z,true);level.setBlock(pos,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        long identity=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"FluidMenuUnload"));player.setPos(pos.getX()+2,pos.getY(),pos.getZ());
        var menu=new FluidDeviceMenu(42,player.getInventory(),pos,identity,false);player.containerMenu=menu;
        helper.assertTrue(menu.stillValid(player),"Initial menu was not valid");level.setChunkForced(chunk.x,chunk.z,false);
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(!level.hasChunkAt(pos),"Waiting for the menu chunk to unload")).thenExecute(()->{
            try {
                helper.assertTrue(!menu.stillValid(player),"Unloaded menu remained valid");menu.broadcastChanges();
                helper.assertTrue(!level.hasChunkAt(pos),"Menu validation/subscription loaded its chunk");
                level.getChunk(pos);helper.assertTrue(((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity()==identity,"Reload changed the identity");
                helper.assertTrue(menu.stillValid(player),"Reload lost the original menu identity");
                level.removeBlock(pos,false);level.setBlock(pos,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
                helper.assertTrue(((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity()!=identity,"Replacement reused a retired identity");
                helper.assertTrue(!menu.stillValid(player),"Old menu attached to a replacement block");
                helper.assertTrue(!level.getForcedChunks().contains(chunk.toLong()),"Menu installed a chunk ticket");
            } finally {player.containerMenu=player.inventoryMenu;level.removeBlock(pos,false);level.setChunkForced(chunk.x,chunk.z,false);}
        }).thenWaitUntil(()->helper.assertTrue(world.registrations().values().stream().noneMatch(r->r.device().position().x()==pos.getX()&&r.device().position().z()==pos.getZ()),"Waiting for cleanup")).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=2000,batch="fluid-packets")
    public static void realServerRejectsInvalidStaleDistantReadOnlyAndFloodedEdits(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var pos=helper.absolutePos(new BlockPos(0,1,0));level.setBlock(pos,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        long identity=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"FluidPacketTest"));
        player.setGameMode(GameType.CREATIVE);player.setPos(pos.getX()+2,pos.getY(),pos.getZ());
        try {
        var old=world.registrations().get(identity);var c=FluidNetwork.Controls.from(old);
        String valid=new Gson().toJson(new FluidNetwork.Controls(c.temperature(),c.pressure(),.04,c.roughness(),c.volumeFlow(),c.maximumAddedPressure(),c.composition()));
        player.containerMenu=new FluidDeviceMenu(41,player.getInventory(),pos,identity,false);
        send(player,new FluidNetwork.EditPayload(42,pos,identity,0,valid));
        send(player,new FluidNetwork.EditPayload(41,pos.east(),identity,0,valid));
        send(player,new FluidNetwork.EditPayload(41,pos,identity+100,0,valid));
        helper.assertTrue(world.registrations().get(identity).equals(old),"Wrong menu/position/identity mutated controls");
        player.containerMenu=new FluidDeviceMenu(41,player.getInventory(),pos,identity,true);
        send(player,new FluidNetwork.EditPayload(41,pos,identity,0,valid));
        helper.assertTrue(world.registrations().get(identity).equals(old),"Probe permitted an edit");
        player.containerMenu=new FluidDeviceMenu(41,player.getInventory(),pos,identity,false);
        player.setPos(pos.getX()+20,pos.getY(),pos.getZ());send(player,new FluidNetwork.EditPayload(41,pos,identity,0,valid));
        helper.assertTrue(world.registrations().get(identity).equals(old),"Distant player mutated controls");
        player.setPos(pos.getX()+2,pos.getY(),pos.getZ());player.getAbilities().mayBuild=false;
        send(player,new FluidNetwork.EditPayload(41,pos,identity,0,valid));
        helper.assertTrue(world.registrations().get(identity).equals(old),"Read-only player mutated controls");player.getAbilities().mayBuild=true;
        for(String invalid:new String[]{"{",valid.replace("0.04","-0.04"),valid.replace("298.15","\"NaN\""),"{}"}) {
            player.containerMenu=new FluidDeviceMenu(41,player.getInventory(),pos,identity,false);
            send(player,new FluidNetwork.EditPayload(41,pos,identity,0,invalid));
            helper.assertTrue(world.registrations().get(identity).equals(old),"Malformed or nonfinite payload mutated controls");
        }
        player.containerMenu=new FluidDeviceMenu(41,player.getInventory(),pos,identity,false);
        send(player,new FluidNetwork.EditPayload(41,pos,identity,0,valid));
        helper.assertTrue(world.registrations().get(identity).revision()==1&&world.registrations().get(identity).device().geometry().diameter()==.04,"Valid edit failed");
        send(player,new FluidNetwork.EditPayload(41,pos,identity,1,valid.replace("0.04","0.03")));
        helper.assertTrue(world.registrations().get(identity).revision()==1,"Same-tick flood mutated controls");
        player.containerMenu=new FluidDeviceMenu(41,player.getInventory(),pos,identity,false);
        send(player,new FluidNetwork.EditPayload(41,pos,identity,0,valid.replace("0.04","0.03")));
        helper.assertTrue(world.registrations().get(identity).revision()==1,"Stale revision mutated controls");
        level.removeBlock(pos,false);
        helper.assertTrue(!player.containerMenu.stillValid(player),"Removed block retained a menu subscription");
        player.containerMenu=player.inventoryMenu;
        helper.succeed();
        } finally {
            level.removeBlock(pos,false);
            player.containerMenu=player.inventoryMenu;
        }
    }
    /** Invoke the registered handler with a real server player; only the transport context is a test double. */
    private static void send(ServerPlayer player,FluidNetwork.EditPayload payload) {
        var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(proxy,method,args)->{
            if(method.getName().equals("player"))return player;
            throw new AssertionError("Unexpected transport operation: "+method.getName());
        });
        try {var handler=FluidNetwork.class.getDeclaredMethod("edit",FluidNetwork.EditPayload.class,IPayloadContext.class);handler.setAccessible(true);handler.invoke(null,payload,context);}
        catch(ReflectiveOperationException failure){throw new AssertionError("Packet handler failed",failure);}
    }
}
