package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateChemE.MOD_ID);

    public static final DeferredItem<BlockItem> COLUMN_CALCULATOR = ITEMS.registerSimpleBlockItem(
            "column_calculator",
            ModBlocks.COLUMN_CALCULATOR
    );

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
