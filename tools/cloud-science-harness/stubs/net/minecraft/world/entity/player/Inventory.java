package net.minecraft.world.entity.player;

import net.minecraft.world.item.ItemStack;

/** Harness stand-in: compile-time only. */
public class Inventory {
    public int getContainerSize() { throw new UnsupportedOperationException("harness stub"); }
    public ItemStack getItem(int slot) { throw new UnsupportedOperationException("harness stub"); }
    public int getFreeSlot() { throw new UnsupportedOperationException("harness stub"); }
    public boolean add(ItemStack stack) { throw new UnsupportedOperationException("harness stub"); }
}
