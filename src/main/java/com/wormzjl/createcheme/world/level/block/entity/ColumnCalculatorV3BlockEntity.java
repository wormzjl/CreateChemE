package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal persistent V3 product anchor.
 *
 * <p>No input, warm state, result, operation, or mutable numerical workspace exists in this shell. The V3 server
 * protocol will add validated immutable state atomically in a later checkpoint.</p>
 */
public final class ColumnCalculatorV3BlockEntity extends BlockEntity implements MenuProvider {
    public ColumnCalculatorV3BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR_V3.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createcheme.column_calculator_v3");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) return null;
        return new ColumnCalculatorV3Menu(
                containerId, inventory, ContainerLevelAccess.create(level, worldPosition), worldPosition);
    }
}
