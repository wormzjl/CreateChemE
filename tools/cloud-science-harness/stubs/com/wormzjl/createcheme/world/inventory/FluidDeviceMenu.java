package com.wormzjl.createcheme.world.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Harness stand-in for the real menu class; compile-time only. */
public final class FluidDeviceMenu extends AbstractContainerMenu {
    private FluidDeviceMenu() {}
    public long identity() { throw new UnsupportedOperationException("harness stub: FluidDeviceMenu.identity"); }
    public ServerPlayer serverPlayer() { throw new UnsupportedOperationException("harness stub: FluidDeviceMenu.serverPlayer"); }
    @Override public boolean stillValid(Player player) { throw new UnsupportedOperationException("harness stub: FluidDeviceMenu.stillValid"); }
}
