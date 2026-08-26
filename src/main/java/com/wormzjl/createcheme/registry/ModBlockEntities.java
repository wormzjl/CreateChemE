package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorBlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorNextBlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateChemE.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ColumnCalculatorBlockEntity>>
            COLUMN_CALCULATOR = BLOCK_ENTITY_TYPES.register(
                    "column_calculator",
                    () -> BlockEntityType.Builder.of(
                            ColumnCalculatorBlockEntity::new,
                            ModBlocks.COLUMN_CALCULATOR.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ColumnCalculatorNextBlockEntity>>
            COLUMN_CALCULATOR_NEXT = BLOCK_ENTITY_TYPES.register(
                    "column_calculator_next",
                    () -> BlockEntityType.Builder.of(
                            ColumnCalculatorNextBlockEntity::new,
                            ModBlocks.COLUMN_CALCULATOR_NEXT.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ColumnCalculatorV3BlockEntity>>
            COLUMN_CALCULATOR_V3 = BLOCK_ENTITY_TYPES.register(
                    "column_calculator_v3",
                    () -> BlockEntityType.Builder.of(
                            ColumnCalculatorV3BlockEntity::new,
                            ModBlocks.COLUMN_CALCULATOR_V3.get()
                    ).build(null)
            );

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
