package com.wormzjl.createcheme.world.inventory;

import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.registry.ModMenus;
import com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * One bounded subscription, owned by the player's existing open-container lifetime. On the server the menu is a
 * consumer of its device's presentation bucket: opening it registers it and delivers nothing; the engine delivers
 * its static and live payloads with the bucket. On the client it shows the last state delivered for the device
 * (from an earlier menu, if any) until its first bucket arrives.
 */
public final class FluidDeviceMenu extends AbstractContainerMenu {
    private final BlockPos position;
    private final long identity;
    private final boolean debug;
    private final ServerPlayer serverPlayer;
    private long lastEditTick=Long.MIN_VALUE;
    private FluidNetwork.MenuData clientData;
    private FluidNetwork.StaticData staticData;
    private boolean lastDelivered;
    private String message="";
    private long messageRevision;
    /** The client's menu: shows the last state delivered for this device, if this session has one, until its first bucket. */
    public FluidDeviceMenu(int id,Inventory inventory,RegistryFriendlyByteBuf buffer) {
        this(id,inventory,buffer.readBlockPos(),buffer.readLong(),buffer.readBoolean());
        clientData=FluidNetwork.lastDelivered(identity);lastDelivered=clientData!=null;
    }
    public FluidDeviceMenu(int id,Inventory inventory,BlockPos position,long identity,boolean debug) {
        super(ModMenus.FLUID_DEVICE.get(),id);this.position=position.immutable();this.identity=identity;this.debug=debug;serverPlayer=inventory.player instanceof ServerPlayer p?p:null;
        // Opening subscribes to the device's bucket; nothing is sent until that bucket.
        if(serverPlayer!=null&&serverPlayer.server.isSameThread())FluidWorldAuthority.find(serverPlayer.server).ifPresent(world->world.subscribe(this));
    }
    public BlockPos position(){return position;}
    public long identity(){return identity;}
    public boolean debug(){return debug;}
    /** The server player this menu belongs to, or null on the client. */
    public ServerPlayer serverPlayer(){return serverPlayer;}
    public FluidNetwork.MenuData clientData(){return clientData;}
    /** Client: true while the menu shows the state delivered to an earlier menu on this device, before its own first bucket. */
    public boolean showingLastDelivered(){return lastDelivered;}
    public String message(){return message;}
    public long messageRevision(){return messageRevision;}
    /** Client: the static payload of a bucket; the live payload that follows it is joined with it. */
    public void acceptStatic(FluidNetwork.StaticData data){staticData=data;}
    /** Client: the live payload of a bucket, with the engine's reply to this player's inputs when it has one. */
    public void acceptLive(FluidNetwork.LiveData live) {
        if(staticData==null)return;
        var data=FluidNetwork.MenuData.of(staticData,live);clientData=data;lastDelivered=false;FluidNetwork.remember(identity,data);
        if(!live.message().isEmpty()){message=live.message();messageRevision++;}
    }
    public boolean admitEdit(long tick){if(lastEditTick!=Long.MIN_VALUE&&tick-lastEditTick<5)return false;lastEditTick=tick;return true;}
    @Override public boolean stillValid(Player player) {
        var level=player.level();return level.hasChunkAt(position)&&player.distanceToSqr(position.getX()+.5,position.getY()+.5,position.getZ()+.5)<=64
                &&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity&&entity.fluidIdentity()==identity;
    }
    @Override public ItemStack quickMoveStack(Player player,int index){return ItemStack.EMPTY;}
    /** Closing ends the subscription; a menu that is simply abandoned is dropped at its next bucket. */
    @Override public void removed(Player player) {
        super.removed(player);
        if(serverPlayer!=null&&serverPlayer.server.isSameThread())FluidWorldAuthority.find(serverPlayer.server).ifPresent(world->world.unsubscribe(this));
    }
}
