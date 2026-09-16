package com.wormzjl.createcheme.fluid.gametest;

import net.neoforged.fml.common.Mod;

/** Loaded only in the explicit fluid GameTest run, never in the production artifact. */
@Mod("createcheme_fluid_test")
public final class FluidTestMod {
    public FluidTestMod() {
        if(Boolean.getBoolean("createcheme.fluid.benchmark")) {
            var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,FluidServerBenchmark::installFixture);
            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,FluidServerBenchmark::beforeTick);
            bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,FluidServerBenchmark::afterTick);
        }
    }
}
