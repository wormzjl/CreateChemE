package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.runtime.fluid.FluidView;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Harness stand-in for the real block entity class; compile-time only. */
public final class FluidDeviceBlockEntity extends BlockEntity implements FluidView.Receiver {
    private FluidDeviceBlockEntity() {}
    @Override public long fluidIdentity() { throw new UnsupportedOperationException("harness stub"); }
    public void bindIdentity(long id) { throw new UnsupportedOperationException("harness stub"); }
    @Override public void acceptFluidView(FluidView view) { throw new UnsupportedOperationException("harness stub"); }
}
