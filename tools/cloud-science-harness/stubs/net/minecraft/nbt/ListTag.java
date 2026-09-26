package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Harness stand-in for Minecraft 1.21.1 {@code ListTag}: one element type, fixed by the first element added. */
public final class ListTag extends CollectionTag<Tag> {
    private final List<Tag> list;
    private byte type;
    public ListTag() { this(new ArrayList<>(), (byte) 0); }
    public ListTag(List<Tag> list, byte type) { this.list = list; this.type = type; }
    @Override public void write(DataOutput output) throws IOException {
        type = list.isEmpty() ? 0 : list.get(0).getId();
        output.writeByte(type);
        output.writeInt(list.size());
        for (Tag tag : list) tag.write(output);
    }
    @Override public byte getId() { return TAG_LIST; }
    @Override public ListTag copy() {
        var copied = new ArrayList<Tag>(list.size());
        for (Tag tag : list) copied.add(tag.copy());
        return new ListTag(copied, type);
    }
    private boolean updateType(Tag tag) {
        if (tag.getId() == TAG_END) return false;
        if (type == TAG_END) { type = tag.getId(); return true; }
        return type == tag.getId();
    }
    @Override public Tag remove(int index) { Tag old = list.remove(index); if (list.isEmpty()) type = 0; return old; }
    @Override public boolean isEmpty() { return list.isEmpty(); }
    public CompoundTag getCompound(int index) {
        if (index >= 0 && index < list.size() && list.get(index) instanceof CompoundTag tag) return tag;
        return new CompoundTag();
    }
    public ListTag getList(int index) {
        if (index >= 0 && index < list.size() && list.get(index) instanceof ListTag tag) return tag;
        return new ListTag();
    }
    public short getShort(int index) { return index >= 0 && index < list.size() && list.get(index) instanceof ShortTag t ? t.getAsShort() : 0; }
    public int getInt(int index) { return index >= 0 && index < list.size() && list.get(index) instanceof IntTag t ? t.getAsInt() : 0; }
    public int[] getIntArray(int index) { return index >= 0 && index < list.size() && list.get(index) instanceof IntArrayTag t ? t.getAsIntArray() : new int[0]; }
    public long[] getLongArray(int index) { return index >= 0 && index < list.size() && list.get(index) instanceof LongArrayTag t ? t.getAsLongArray() : new long[0]; }
    public double getDouble(int index) { return index >= 0 && index < list.size() && list.get(index) instanceof DoubleTag t ? t.getAsDouble() : 0.0; }
    public float getFloat(int index) { return index >= 0 && index < list.size() && list.get(index) instanceof FloatTag t ? t.getAsFloat() : 0.0F; }
    public String getString(int index) {
        if (index >= 0 && index < list.size()) { Tag tag = list.get(index); return tag.getId() == TAG_STRING ? tag.getAsString() : tag.toString(); }
        return "";
    }
    @Override public int size() { return list.size(); }
    @Override public Tag get(int index) { return list.get(index); }
    @Override public Tag set(int index, Tag tag) {
        Tag old = get(index);
        if (!setTag(index, tag)) throw new UnsupportedOperationException(String.format(java.util.Locale.ROOT, "Trying to add tag of type %d to list of %d", tag.getId(), type));
        return old;
    }
    @Override public void add(int index, Tag tag) {
        if (!addTag(index, tag)) throw new UnsupportedOperationException(String.format(java.util.Locale.ROOT, "Trying to add tag of type %d to list of %d", tag.getId(), type));
    }
    @Override public boolean setTag(int index, Tag tag) { if (updateType(tag)) { list.set(index, tag); return true; } return false; }
    @Override public boolean addTag(int index, Tag tag) { if (updateType(tag)) { list.add(index, tag); return true; } return false; }
    @Override public byte getElementType() { return type; }
    @Override public void clear() { list.clear(); type = 0; }
    @Override public boolean equals(Object other) { return this == other || other instanceof ListTag tag && Objects.equals(list, tag.list); }
    @Override public int hashCode() { return list.hashCode(); }
    @Override public String getAsString() { return list.toString(); }
    @Override public String toString() { return getAsString(); }
}
