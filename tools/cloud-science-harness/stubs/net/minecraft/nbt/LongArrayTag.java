package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/** Like Minecraft's: getAsLongArray returns the backing array, so callers may write through it. */
public final class LongArrayTag extends CollectionTag<LongTag> {
    private long[] data;
    public LongArrayTag(long[] data) { this.data = data; }
    public LongArrayTag(java.util.List<? extends Number> values) { data = new long[values.size()]; for (int i = 0; i < data.length; i++) data[i] = values.get(i).longValue(); }
    @Override public void write(DataOutput output) throws IOException { output.writeInt(data.length); for (long value : data) output.writeLong(value); }
    @Override public byte getId() { return TAG_LONG_ARRAY; }
    @Override public LongArrayTag copy() { return new LongArrayTag(Arrays.copyOf(data, data.length)); }
    public long[] getAsLongArray() { return data; }
    @Override public int size() { return data.length; }
    @Override public LongTag get(int index) { return LongTag.valueOf(data[index]); }
    @Override public LongTag set(int index, LongTag tag) { long old = data[index]; data[index] = tag.getAsLong(); return LongTag.valueOf(old); }
    @Override public void add(int index, LongTag tag) { data = insert(data, index, tag.getAsLong()); }
    @Override public boolean setTag(int index, Tag tag) { if (tag instanceof NumericTag n) { data[index] = n.getAsLong(); return true; } return false; }
    @Override public boolean addTag(int index, Tag tag) { if (tag instanceof NumericTag n) { data = insert(data, index, n.getAsLong()); return true; } return false; }
    @Override public LongTag remove(int index) { long old = data[index]; long[] next = new long[data.length - 1]; System.arraycopy(data, 0, next, 0, index); System.arraycopy(data, index + 1, next, index, data.length - index - 1); data = next; return LongTag.valueOf(old); }
    @Override public byte getElementType() { return TAG_LONG; }
    @Override public void clear() { data = new long[0]; }
    private static long[] insert(long[] array, int index, long value) { long[] next = new long[array.length + 1]; System.arraycopy(array, 0, next, 0, index); next[index] = value; System.arraycopy(array, index, next, index + 1, array.length - index); return next; }
    @Override public boolean equals(Object other) { return this == other || other instanceof LongArrayTag tag && Arrays.equals(data, tag.data); }
    @Override public int hashCode() { return Arrays.hashCode(data); }
    @Override public String getAsString() { return Arrays.toString(data); }
    @Override public String toString() { return getAsString(); }
}
