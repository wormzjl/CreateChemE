package com.wormzjl.createcheme.world.inventory;

import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.registry.ModMenus;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

/** Position-bound V3 menu with no inventory slots or client-authored scientific state. */
public final class ColumnCalculatorV3Menu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    private final BlockPos blockPos;
    private final DataSlot status;

    /** Client-side constructor used by the menu type's extra-data factory. */
    public ColumnCalculatorV3Menu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, ContainerLevelAccess.NULL, extraData.readBlockPos());
    }

    /** Server-side constructor created only by the V3 block entity. */
    public ColumnCalculatorV3Menu(
            int containerId, Inventory inventory, ContainerLevelAccess access, BlockPos blockPos) {
        super(ModMenus.COLUMN_CALCULATOR_V3.get(), containerId);
        this.access = access;
        this.blockPos = blockPos.immutable();
        status = addDataSlot(new DataSlot() {
            private int clientValue;

            @Override
            public int get() {
                return access.evaluate((level, pos) -> level.getBlockEntity(pos) instanceof ColumnCalculatorV3BlockEntity calculator
                        ? calculator.statusCode() : 0, clientValue);
            }

            @Override
            public void set(int value) {
                clientValue = value;
            }
        });
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    public int statusCode() {
        return status.get();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != 0) return false;
        return access.evaluate((level, pos) -> level.getBlockEntity(pos) instanceof ColumnCalculatorV3BlockEntity calculator
                && calculator.startPilot(), false);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.COLUMN_CALCULATOR_V3.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
