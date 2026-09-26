package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/** Like Minecraft's: getAsIntArray returns the backing array, so callers may write through it. */
public final class IntArrayTag extends CollectionTag<IntTag> {
    private int[] data;
    public IntArrayTag(int[] data) { this.data = data; }
    public IntArrayTag(java.util.List<? extends Number> values) { data = new int[values.size()]; for (int i = 0; i < data.length; i++) data[i] = values.get(i).intValue(); }
    @Override public void write(DataOutput output) throws IOException { output.writeInt(data.length); for (int value : data) output.writeInt(value); }
    @Override public byte getId() { return TAG_INT_ARRAY; }
    @Override public IntArrayTag copy() { return new IntArrayTag(Arrays.copyOf(data, data.length)); }
    public int[] getAsIntArray() { return data; }
    @Override public int size() { return data.length; }
    @Override public IntTag get(int index) { return IntTag.valueOf(data[index]); }
    @Override public IntTag set(int index, IntTag tag) { int old = data[index]; data[index] = tag.getAsInt(); return IntTag.valueOf(old); }
    @Override public void add(int index, IntTag tag) { data = insert(data, index, tag.getAsInt()); }
    @Override public boolean setTag(int index, Tag tag) { if (tag instanceof NumericTag n) { data[index] = n.getAsInt(); return true; } return false; }
    @Override public boolean addTag(int index, Tag tag) { if (tag instanceof NumericTag n) { data = insert(data, index, n.getAsInt()); return true; } return false; }
    @Override public IntTag remove(int index) { int old = data[index]; int[] next = new int[data.length - 1]; System.arraycopy(data, 0, next, 0, index); System.arraycopy(data, index + 1, next, index, data.length - index - 1); data = next; return IntTag.valueOf(old); }
    @Override public byte getElementType() { return TAG_INT; }
    @Override public void clear() { data = new int[0]; }
    private static int[] insert(int[] array, int index, int value) { int[] next = new int[array.length + 1]; System.arraycopy(array, 0, next, 0, index); next[index] = value; System.arraycopy(array, index, next, index + 1, array.length - index); return next; }
    @Override public boolean equals(Object other) { return this == other || other instanceof IntArrayTag tag && Arrays.equals(data, tag.data); }
    @Override public int hashCode() { return Arrays.hashCode(data); }
    @Override public String getAsString() { return Arrays.toString(data); }
    @Override public String toString() { return getAsString(); }
}
