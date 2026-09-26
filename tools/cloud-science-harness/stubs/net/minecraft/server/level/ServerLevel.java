package net.minecraft.server.level;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** Harness stand-in: compile-time only. */
public abstract class ServerLevel extends Level {
    public DimensionDataStorage getDataStorage() { throw new UnsupportedOperationException("harness stub"); }
}
