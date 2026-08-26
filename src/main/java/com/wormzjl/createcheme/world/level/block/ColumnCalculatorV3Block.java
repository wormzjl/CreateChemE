package com.wormzjl.createcheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Additive V3 product block; solve admission remains unavailable until the V3 server protocol is installed. */
public final class ColumnCalculatorV3Block extends BaseEntityBlock {
    public static final MapCodec<ColumnCalculatorV3Block> CODEC = simpleCodec(ColumnCalculatorV3Block::new);

    public ColumnCalculatorV3Block(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ColumnCalculatorV3Block> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ColumnCalculatorV3BlockEntity(pos, state);
    }

    @Override
    public @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof ColumnCalculatorV3BlockEntity calculator ? calculator : null;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            MenuProvider menuProvider = state.getMenuProvider(level, pos);
            if (menuProvider != null) serverPlayer.openMenu(menuProvider, buffer -> buffer.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
