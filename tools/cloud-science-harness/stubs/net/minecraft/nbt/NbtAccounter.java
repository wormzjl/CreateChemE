package net.minecraft.nbt;

/** Harness stand-in: the quota and depth are recorded, never enforced. */
public class NbtAccounter {
    private final long quota;
    private final int maxDepth;
    public NbtAccounter(long quota, int maxDepth) { this.quota = quota; this.maxDepth = maxDepth; }
    public static NbtAccounter create(long quota) { return new NbtAccounter(quota, Tag.MAX_DEPTH); }
    public static NbtAccounter unlimitedHeap() { return new NbtAccounter(Long.MAX_VALUE, Tag.MAX_DEPTH); }
    public void accountBytes(long bytes) {}
    public void pushDepth() {}
    public void popDepth() {}
}
