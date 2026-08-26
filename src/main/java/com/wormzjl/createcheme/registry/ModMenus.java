package com.wormzjl.createcheme.registry;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorMenu;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, CreateChemE.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<ColumnCalculatorMenu>> COLUMN_CALCULATOR =
            MENU_TYPES.register(
                    "column_calculator",
                    () -> IMenuTypeExtension.create(ColumnCalculatorMenu::new)
            );

    public static final DeferredHolder<MenuType<?>, MenuType<ColumnCalculatorV3Menu>> COLUMN_CALCULATOR_V3 =
            MENU_TYPES.register(
                    "column_calculator_v3",
                    () -> IMenuTypeExtension.create(ColumnCalculatorV3Menu::new)
            );

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }
}
