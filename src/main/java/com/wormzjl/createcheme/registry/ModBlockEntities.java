package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateChemE.MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity>> FLUID_DEVICE=BLOCK_ENTITY_TYPES.register("fluid_device",()->BlockEntityType.Builder.of(com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity::new,
            ModBlocks.FLUID_RESERVOIR.get(),ModBlocks.FLUID_PIPE.get(),ModBlocks.FLUID_PUMP.get(),ModBlocks.PRESSURE_CONTROL_VALVE.get(),ModBlocks.FLUID_GENERATOR.get(),ModBlocks.FLUID_VOID.get(),ModBlocks.INLINE_FILTER.get()).build(null));

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
