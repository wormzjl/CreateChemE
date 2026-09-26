package com.wormzjl.createcheme.world.level.block;

import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import net.minecraft.world.level.block.Block;

/** Harness stand-in for the real block class; compile-time only. */
public final class FluidDeviceBlock extends Block {
    private FluidDeviceBlock() {}
    public TopologyCompiler.Kind kind() { throw new UnsupportedOperationException("harness stub: FluidDeviceBlock.kind"); }
}
