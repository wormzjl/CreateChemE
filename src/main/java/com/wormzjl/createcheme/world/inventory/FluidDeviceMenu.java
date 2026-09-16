package com.wormzjl.createcheme.world.inventory;

import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.registry.ModMenus;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** One bounded subscription, owned by the player's existing open-container lifetime. */
public final class FluidDeviceMenu extends AbstractContainerMenu {
    private final BlockPos position;
    private final long identity;
    private final boolean debug;
    private final ServerPlayer serverPlayer;
    private int lastSentTick=-1;
    private long lastEditTick=Long.MIN_VALUE;
    private FluidNetwork.MenuData clientData;
    private String message="";
    private long messageRevision;
    public FluidDeviceMenu(int id,Inventory inventory,RegistryFriendlyByteBuf buffer){this(id,inventory,buffer.readBlockPos(),buffer.readLong(),buffer.readBoolean());}
    public FluidDeviceMenu(int id,Inventory inventory,BlockPos position,long identity,boolean debug) {
        super(ModMenus.FLUID_DEVICE.get(),id);this.position=position.immutable();this.identity=identity;this.debug=debug;serverPlayer=inventory.player instanceof ServerPlayer p?p:null;
    }
    public BlockPos position(){return position;}
    public long identity(){return identity;}
    public boolean debug(){return debug;}
    public FluidNetwork.MenuData clientData(){return clientData;}
    public String message(){return message;}
    public long messageRevision(){return messageRevision;}
    public void acceptData(FluidNetwork.MenuData data){clientData=data;if(!data.message().isEmpty()){message=data.message();messageRevision++;}}
    public boolean admitEdit(long tick){if(lastEditTick!=Long.MIN_VALUE&&tick-lastEditTick<5)return false;lastEditTick=tick;return true;}
    @Override public boolean stillValid(Player player) {
        var level=player.level();return level.hasChunkAt(position)&&player.distanceToSqr(position.getX()+.5,position.getY()+.5,position.getZ()+.5)<=64
                &&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity&&entity.fluidIdentity()==identity;
    }
    @Override public ItemStack quickMoveStack(Player player,int index){return ItemStack.EMPTY;}
    @Override public void broadcastChanges() {
        super.broadcastChanges();if(serverPlayer==null||!stillValid(serverPlayer))return;int tick=serverPlayer.server.getTickCount();
        if(lastSentTick==-1||tick-lastSentTick>=10){lastSentTick=tick;FluidNetwork.sendState(serverPlayer,this,"");}
    }
}
