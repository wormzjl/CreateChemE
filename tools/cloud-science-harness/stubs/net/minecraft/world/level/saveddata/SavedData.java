package net.minecraft.world.level.saveddata;

import java.io.File;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Harness stand-in for Minecraft 1.21.1 SavedData (with NeoForge's save(File, Provider) override point): the dirty flag is real. */
public abstract class SavedData {
    private boolean dirty;
    public abstract CompoundTag save(CompoundTag tag, HolderLookup.Provider registries);
    public void setDirty() { setDirty(true); }
    public void setDirty(boolean dirty) { this.dirty = dirty; }
    public boolean isDirty() { return dirty; }
    public void save(File file, HolderLookup.Provider registries) { throw new UnsupportedOperationException("harness stub: SavedData.save(File)"); }
    public record Factory<T extends SavedData>(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer, Object type) {
        public Factory(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer) { this(constructor, deserializer, null); }
    }
}
