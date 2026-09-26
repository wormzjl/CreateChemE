package com.wormzjl.createcheme.network;

import net.minecraft.server.MinecraftServer;

/** Harness stand-in for the real column packet class; compile-time only. */
public final class ColumnV3Network {
    private ColumnV3Network() {}
    public static void presentationTick(MinecraftServer server, long onlineTick) { throw new UnsupportedOperationException("harness stub: ColumnV3Network.presentationTick"); }
    public static void forgetPresentation(MinecraftServer server) { throw new UnsupportedOperationException("harness stub: ColumnV3Network.forgetPresentation"); }
}
