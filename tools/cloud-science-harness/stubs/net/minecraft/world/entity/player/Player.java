package net.minecraft.world.entity.player;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Harness stand-in: compile-time only. */
public abstract class Player extends Entity {
    public AbstractContainerMenu containerMenu;
    public java.util.UUID getUUID() { throw new UnsupportedOperationException("harness stub"); }
    public Inventory getInventory() { throw new UnsupportedOperationException("harness stub"); }
}
