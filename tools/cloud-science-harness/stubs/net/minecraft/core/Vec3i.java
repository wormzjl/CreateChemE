package net.minecraft.core;

/** Harness stand-in: an immutable integer triple with value equality, as Minecraft's. */
public class Vec3i implements Comparable<Vec3i> {
    private final int x, y, z;
    public Vec3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    @Override public boolean equals(Object other) { return this == other || other instanceof Vec3i v && x == v.x && y == v.y && z == v.z; }
    @Override public int hashCode() { return (y + z * 31) * 31 + x; }
    @Override public int compareTo(Vec3i other) { return y == other.y ? (z == other.z ? x - other.x : z - other.z) : y - other.y; }
    @Override public String toString() { return "[" + x + ", " + y + ", " + z + "]"; }
}
