package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.world.level.block.ColumnCalculatorV3Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateChemE.MOD_ID);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> FLUID_RESERVOIR=fluid("fluid_reservoir",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.RESERVOIR);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> FLUID_PIPE=fluid("fluid_pipe",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.PIPE);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> FLUID_PUMP=fluid("fluid_pump",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.PUMP);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> PRESSURE_CONTROL_VALVE=fluid("pressure_control_valve",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.VALVE);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> FLUID_GENERATOR=fluid("fluid_generator",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> FLUID_VOID=fluid("fluid_void",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.VOID);
    public static final DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> INLINE_FILTER=fluid("inline_filter",com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.FILTER);
    private static DeferredBlock<com.wormzjl.createcheme.world.level.block.FluidDeviceBlock> fluid(String name,com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind kind) {
        var properties=BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK);
        // All device models except the full-cube inline filter leave part of their block space open.
        // They must not occlude the neighbouring block's faces in the chunk renderer.
        if(kind!=com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.FILTER)properties.noOcclusion();
        return BLOCKS.registerBlock(name,p->new com.wormzjl.createcheme.world.level.block.FluidDeviceBlock(kind,p),properties);
    }

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
