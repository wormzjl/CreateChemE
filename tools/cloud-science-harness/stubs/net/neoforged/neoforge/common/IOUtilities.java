package net.neoforged.neoforge.common;

/** Harness stand-in: compile-time only (only FluidSavedData.open, which needs a live server, uses it). */
public final class IOUtilities {
    private IOUtilities() {}
    public static void withIOWorker(Runnable task) { throw new UnsupportedOperationException("harness stub: IOUtilities.withIOWorker"); }
}
