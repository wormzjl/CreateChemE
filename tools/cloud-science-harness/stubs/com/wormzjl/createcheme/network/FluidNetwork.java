package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.runtime.fluid.FluidView;
import com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.createcheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.createcheme.world.inventory.FluidDeviceMenu;

/** Harness stand-in for the real packet class (Minecraft networking): only the members FluidWorldAuthority names; compile-time only. */
public final class FluidNetwork {
    private FluidNetwork() {}
    public record MenuData(FluidView view) {}
    public static MenuData snapshot(FluidWorldAuthority world, WorldTopologyLedger.Registration record, FluidView view) { throw new UnsupportedOperationException("harness stub: FluidNetwork.snapshot"); }
    public static void deliver(FluidDeviceMenu menu, boolean withStatic, MenuData data, String reply) { throw new UnsupportedOperationException("harness stub: FluidNetwork.deliver"); }
}
