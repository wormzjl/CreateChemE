package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/** Like Minecraft's: getAsByteArray returns the backing array, so callers may write through it. */
public final class ByteArrayTag extends CollectionTag<ByteTag> {
    private byte[] data;
    public ByteArrayTag(byte[] data) { this.data = data; }
    public ByteArrayTag(java.util.List<? extends Number> values) { data = new byte[values.size()]; for (int i = 0; i < data.length; i++) data[i] = values.get(i).byteValue(); }
    @Override public void write(DataOutput output) throws IOException { output.writeInt(data.length); for (byte value : data) output.writeByte(value); }
    @Override public byte getId() { return TAG_BYTE_ARRAY; }
    @Override public ByteArrayTag copy() { return new ByteArrayTag(Arrays.copyOf(data, data.length)); }
    public byte[] getAsByteArray() { return data; }
    @Override public int size() { return data.length; }
    @Override public ByteTag get(int index) { return ByteTag.valueOf(data[index]); }
    @Override public ByteTag set(int index, ByteTag tag) { byte old = data[index]; data[index] = tag.getAsByte(); return ByteTag.valueOf(old); }
    @Override public void add(int index, ByteTag tag) { data = insert(data, index, tag.getAsByte()); }
    @Override public boolean setTag(int index, Tag tag) { if (tag instanceof NumericTag n) { data[index] = n.getAsByte(); return true; } return false; }
    @Override public boolean addTag(int index, Tag tag) { if (tag instanceof NumericTag n) { data = insert(data, index, n.getAsByte()); return true; } return false; }
    @Override public ByteTag remove(int index) { byte old = data[index]; byte[] next = new byte[data.length - 1]; System.arraycopy(data, 0, next, 0, index); System.arraycopy(data, index + 1, next, index, data.length - index - 1); data = next; return ByteTag.valueOf(old); }
    @Override public byte getElementType() { return TAG_BYTE; }
    @Override public void clear() { data = new byte[0]; }
    private static byte[] insert(byte[] array, int index, byte value) { byte[] next = new byte[array.length + 1]; System.arraycopy(array, 0, next, 0, index); next[index] = value; System.arraycopy(array, index, next, index + 1, array.length - index); return next; }
    @Override public boolean equals(Object other) { return this == other || other instanceof ByteArrayTag tag && Arrays.equals(data, tag.data); }
    @Override public int hashCode() { return Arrays.hashCode(data); }
    @Override public String getAsString() { return Arrays.toString(data); }
    @Override public String toString() { return getAsString(); }
}
