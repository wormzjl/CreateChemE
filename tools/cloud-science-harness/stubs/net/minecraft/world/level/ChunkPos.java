package net.minecraft.world.level;

/** Harness stand-in: chunk coordinates packed as in Minecraft. */
public class ChunkPos {
    public final int x, z;
    public ChunkPos(int x, int z) { this.x = x; this.z = z; }
    public ChunkPos(long packed) { this.x = (int) packed; this.z = (int) (packed >> 32); }
    public static long asLong(int x, int z) { return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32; }
    public long toLong() { return asLong(x, z); }
    @Override public boolean equals(Object o) { return this == o || o instanceof ChunkPos c && x == c.x && z == c.z; }
    @Override public int hashCode() { return Long.hashCode(toLong()); }
    @Override public String toString() { return "[" + x + ", " + z + "]"; }
}
