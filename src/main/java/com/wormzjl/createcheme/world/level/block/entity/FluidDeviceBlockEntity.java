package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.world.inventory.FluidDeviceMenu;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Loaded presentation binding only. No nitrogen, fluid amount, energy, or clock is reconstructed from this TE. */
public final class FluidDeviceBlockEntity extends BlockEntity implements FluidView.Receiver {
    private long identity;
    private FluidView lastView;
    public FluidDeviceBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.FLUID_DEVICE.get(),pos,state);}
    @Override public long fluidIdentity(){return identity;}
    public void bindIdentity(long id) {
        if(id<=0)throw new IllegalStateException("Invalid fluid identity");
        if(level instanceof ServerLevel serverLevel) {
            var world=FluidWorldAuthority.find(serverLevel.getServer()).orElseThrow();
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),worldPosition.getX(),worldPosition.getY(),worldPosition.getZ());
            if(world.at(p).orElseThrow().device().id()!=id)throw new IllegalStateException("Fluid identity belongs to a different position");
        }
        identity=id;setChanged();
        if(level instanceof ServerLevel serverLevel){FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(w->w.refreshLoaded(id));level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);}
    }
    @Override public void onLoad() {
        super.onLoad();if(!(level instanceof ServerLevel serverLevel))return;
        FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),worldPosition.getX(),worldPosition.getY(),worldPosition.getZ());
            world.at(p).ifPresent(record->{if(identity!=record.device().id())bindIdentity(record.device().id());else world.refreshLoaded(identity);});
        });
    }
    @Override public void acceptFluidView(FluidView view){if(view.identity()==identity)lastView=view;}
    public FluidView lastView(){return lastView;}
    public void open(ServerPlayer player,boolean debug) {
        if(identity==0){player.displayClientMessage(Component.literal("Fluid device is waiting for its world identity."),true);return;}
        player.openMenu(new MenuProvider() {
            public Component getDisplayName(){return Component.translatable(getBlockState().getBlock().getDescriptionId());}
            public AbstractContainerMenu createMenu(int containerId,Inventory inventory,Player player){return new FluidDeviceMenu(containerId,inventory,worldPosition,identity,debug);}
        },buffer->{buffer.writeBlockPos(worldPosition);buffer.writeLong(identity);buffer.writeBoolean(debug);});
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);tag.putLong("FluidIdentity",identity);}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){super.loadAdditional(tag,registries);identity=tag.getLong("FluidIdentity");lastView=null;}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){var tag=new CompoundTag();tag.putLong("FluidIdentity",identity);return tag;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
