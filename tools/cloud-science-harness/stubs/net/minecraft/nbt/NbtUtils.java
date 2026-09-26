package net.minecraft.nbt;

public final class NbtUtils {
    /** Minecraft 1.21.1's world data version (SharedConstants.getCurrentVersion().getDataVersion()). */
    public static final int DATA_VERSION_1_21_1 = 3955;
    private NbtUtils() {}
    public static CompoundTag addCurrentDataVersion(CompoundTag tag) { return addDataVersion(tag, DATA_VERSION_1_21_1); }
    public static CompoundTag addDataVersion(CompoundTag tag, int version) { tag.putInt("DataVersion", version); return tag; }
    public static int getDataVersion(CompoundTag tag, int fallback) { return tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC) ? tag.getInt("DataVersion") : fallback; }
}
