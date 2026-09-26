package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateChemE.MOD_ID);
    public static final DeferredItem<BlockItem> FLUID_RESERVOIR=ITEMS.registerSimpleBlockItem("fluid_reservoir",ModBlocks.FLUID_RESERVOIR);
    public static final DeferredItem<BlockItem> FLUID_PIPE=ITEMS.registerSimpleBlockItem("fluid_pipe",ModBlocks.FLUID_PIPE);
    public static final DeferredItem<BlockItem> FLUID_PUMP=ITEMS.registerSimpleBlockItem("fluid_pump",ModBlocks.FLUID_PUMP);
    public static final DeferredItem<BlockItem> FLUID_COMPRESSOR=ITEMS.registerSimpleBlockItem("fluid_compressor",ModBlocks.FLUID_COMPRESSOR);
    public static final DeferredItem<BlockItem> PRESSURE_CONTROL_VALVE=ITEMS.registerSimpleBlockItem("pressure_control_valve",ModBlocks.PRESSURE_CONTROL_VALVE);
    public static final DeferredItem<BlockItem> FLUID_GENERATOR=ITEMS.registerSimpleBlockItem("fluid_generator",ModBlocks.FLUID_GENERATOR);
    public static final DeferredItem<BlockItem> FLUID_VOID=ITEMS.registerSimpleBlockItem("fluid_void",ModBlocks.FLUID_VOID);
    public static final DeferredItem<BlockItem> INLINE_FILTER=ITEMS.registerSimpleBlockItem("inline_filter",ModBlocks.INLINE_FILTER);
    public static final DeferredItem<com.wormzjl.createcheme.world.item.RecoveredSolidsItem> RECOVERED_SOLIDS=ITEMS.registerItem("recovered_solids",com.wormzjl.createcheme.world.item.RecoveredSolidsItem::new);
    public static final DeferredItem<net.minecraft.world.item.Item> FLUID_DEBUGGER=ITEMS.registerSimpleItem("fluid_debugger",new net.minecraft.world.item.Item.Properties().stacksTo(1));

    public static final DeferredItem<BlockItem> COLUMN_CALCULATOR_V3 = ITEMS.registerSimpleBlockItem(
            "column_calculator_v3",
            ModBlocks.COLUMN_CALCULATOR_V3
    );

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
