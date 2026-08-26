package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.world.level.block.ColumnCalculatorBlock;
import com.wormzjl.createcheme.world.level.block.ColumnCalculatorV3Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateChemE.MOD_ID);

    public static final DeferredBlock<ColumnCalculatorBlock> COLUMN_CALCULATOR = BLOCKS.registerBlock(
            "column_calculator",
            ColumnCalculatorBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
    );

    public static final DeferredBlock<ColumnCalculatorV3Block> COLUMN_CALCULATOR_V3 = BLOCKS.registerBlock(
            "column_calculator_v3",
            ColumnCalculatorV3Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
    );

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
