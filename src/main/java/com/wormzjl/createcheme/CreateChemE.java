package com.wormzjl.createcheme;

import com.mojang.logging.LogUtils;
import com.wormzjl.createcheme.network.ColumnNetwork;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.registry.ModItems;
import com.wormzjl.createcheme.registry.ModMenus;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(CreateChemE.MOD_ID)
public final class CreateChemE {
    public static final String MOD_ID = "createcheme";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final ModConfigSpec CONFIG_SPEC;
    private static final ModConfigSpec.BooleanValue CALCULATION_LOGGING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CALCULATION_LOGGING = builder
                .comment("Log process-equipment calculation inputs, results, diagnostics, timing, and outputs.")
                .define("enableCalculationLogging", true);
        CONFIG_SPEC = builder.build();
    }

    public CreateChemE(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.register(modEventBus);
        modEventBus.addListener(ColumnNetwork::register);
        modEventBus.addListener(CreateChemE::addCreativeTabItem);
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "createcheme-common.toml");
    }

    public static boolean calculationLoggingEnabled() {
        return CALCULATION_LOGGING.getAsBoolean();
    }

    private static void addCreativeTabItem(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.COLUMN_CALCULATOR.get());
        }
    }
}
