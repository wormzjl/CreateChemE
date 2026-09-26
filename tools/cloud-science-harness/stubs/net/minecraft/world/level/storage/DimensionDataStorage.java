package net.minecraft.world.level.storage;

import net.minecraft.world.level.saveddata.SavedData;

/** Harness stand-in: compile-time only. */
public class DimensionDataStorage {
    public <T extends SavedData> T get(SavedData.Factory<T> factory, String name) { throw new UnsupportedOperationException("harness stub"); }
    public void set(String name, SavedData data) { throw new UnsupportedOperationException("harness stub"); }
}
