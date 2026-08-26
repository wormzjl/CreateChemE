package com.wormzjl.createcheme.world.inventory;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** Separate menu identity keeps malformed legacy/next payloads from crossing the comparison boundary. */
public final class ColumnCalculatorNextMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private final BlockPos blockPos;

    public ColumnCalculatorNextMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, ContainerLevelAccess.NULL, extraData.readBlockPos());
    }

    public ColumnCalculatorNextMenu(
            int containerId, Inventory inventory, ContainerLevelAccess access, BlockPos blockPos) {
        super(ModMenus.COLUMN_CALCULATOR_NEXT.get(), containerId);
        this.access = access;
        this.blockPos = blockPos.immutable();
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.COLUMN_CALCULATOR_NEXT.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
