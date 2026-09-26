package net.minecraft.world.inventory;

import net.minecraft.world.entity.player.Player;

/** Harness stand-in: compile-time only. */
public abstract class AbstractContainerMenu {
    public abstract boolean stillValid(Player player);
}
