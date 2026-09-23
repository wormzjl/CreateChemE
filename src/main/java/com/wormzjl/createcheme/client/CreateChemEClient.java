package com.wormzjl.createcheme.client;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.client.gui.screens.inventory.ColumnCalculatorV3Screen;
import com.wormzjl.createcheme.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = CreateChemE.MOD_ID, value = Dist.CLIENT)
public final class CreateChemEClient {
    private CreateChemEClient() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.COLUMN_CALCULATOR_V3.get(), ColumnCalculatorV3Screen::new);
        event.register(ModMenus.FLUID_DEVICE.get(),com.wormzjl.createcheme.client.gui.screens.inventory.FluidDeviceScreen::new);
    }

    /** Fluid device identities belong to a world: the remembered last-delivered views go with it. */
    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        com.wormzjl.createcheme.network.FluidNetwork.forgetDelivered();
    }

}
