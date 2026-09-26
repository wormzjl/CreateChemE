package com.wormzjl.createcheme.world.item;

import com.wormzjl.createcheme.runtime.fluid.RecoveredSolid;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** Harness stand-in for the real item class; compile-time only. */
public final class RecoveredSolidsItem {
    private RecoveredSolidsItem() {}
    public static ItemStack create(UUID transfer, RecoveredSolid recovered) { throw new UnsupportedOperationException("harness stub: RecoveredSolidsItem.create"); }
    public static boolean carriesTransfer(ItemStack stack, UUID transfer) { throw new UnsupportedOperationException("harness stub: RecoveredSolidsItem.carriesTransfer"); }
}
