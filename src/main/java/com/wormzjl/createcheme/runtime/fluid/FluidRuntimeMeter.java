package com.wormzjl.createcheme.runtime.fluid;

import net.minecraft.server.MinecraftServer;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in measurement of logical-server work, including completion wakeups between ticks.
 * Nested scopes count once. Counters are not simulation state and are never persisted. */
public final class FluidRuntimeMeter {
    private static final ConcurrentHashMap<MinecraftServer,Counter> COUNTERS=new ConcurrentHashMap<>();
    private static final class Counter {int depth;long started,total;}
    private FluidRuntimeMeter() {}
    private static void owned(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Runtime timing belongs to the logical server thread");}
    public static void enable(MinecraftServer server){owned(server);if(COUNTERS.putIfAbsent(server,new Counter())!=null)throw new IllegalStateException("Runtime timing already enabled");}
    public static void enter(MinecraftServer server){var c=COUNTERS.get(server);if(c==null)return;owned(server);if(c.depth++==0)c.started=System.nanoTime();}
    public static void exit(MinecraftServer server){var c=COUNTERS.get(server);if(c==null)return;owned(server);if(c.depth<=0)throw new IllegalStateException("Unbalanced timing scope");if(--c.depth==0)c.total+=System.nanoTime()-c.started;}
    public static long totalNanos(MinecraftServer server){owned(server);var c=COUNTERS.get(server);if(c==null)throw new IllegalStateException("Runtime timing not enabled");return c.total+(c.depth==0?0:System.nanoTime()-c.started);}
    public static void forget(MinecraftServer server){owned(server);var c=COUNTERS.get(server);if(c!=null&&c.depth!=0)throw new IllegalStateException("Timing scope still active");COUNTERS.remove(server);}
}
