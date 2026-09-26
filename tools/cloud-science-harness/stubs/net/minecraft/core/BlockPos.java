package net.minecraft.core;

/** Harness stand-in for Minecraft's BlockPos: coordinates and value equality only. */
public class BlockPos extends Vec3i {
    public static final BlockPos ZERO = new BlockPos(0, 0, 0);
    public BlockPos(int x, int y, int z) { super(x, y, z); }
    public BlockPos offset(int dx, int dy, int dz) { return new BlockPos(getX() + dx, getY() + dy, getZ() + dz); }
    public BlockPos immutable() { return this; }
    public BlockPos above() { return offset(0, 1, 0); }
    public BlockPos below() { return offset(0, -1, 0); }
    @Override public String toString() { return "BlockPos{x=" + getX() + ", y=" + getY() + ", z=" + getZ() + "}"; }
}
