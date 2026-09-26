package net.minecraft.server;

/** Harness stand-in: a tick-stamped runnable, as in Minecraft. */
public class TickTask implements Runnable {
    private final int tick;
    private final Runnable runnable;
    public TickTask(int tick, Runnable runnable) { this.tick = tick; this.runnable = runnable; }
    public int getTick() { return tick; }
    @Override public void run() { runnable.run(); }
}
