package com.wormzjl.createcheme.network;

import net.minecraft.server.MinecraftServer;

/** Harness stand-in for the real coordinator (block entities, packets); compile-time only. */
public final class ProcessSolveCoordinator {
    private ProcessSolveCoordinator() {}
    public static void drainCompletedCalculations(MinecraftServer server) { throw new UnsupportedOperationException("harness stub: ProcessSolveCoordinator.drainCompletedCalculations"); }
}
