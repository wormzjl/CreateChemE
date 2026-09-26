package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Harness stand-in for Minecraft 1.21.1 {@code CompoundTag}: a HashMap of named tags with Minecraft's lenient getters
 * (a missing or mistyped key reads as 0, "", an empty array, an empty compound or list) and its typed
 * {@code contains(key, type)} with {@link Tag#TAG_ANY_NUMERIC}.
 */
public class CompoundTag implements Tag {
    private final Map<String, Tag> tags;
    protected CompoundTag(Map<String, Tag> tags) { this.tags = tags; }
    public CompoundTag() { this(new HashMap<>()); }

    @Override public void write(DataOutput output) throws IOException {
        for (String key : tags.keySet()) {
            Tag tag = tags.get(key);
            output.writeByte(tag.getId());
            if (tag.getId() != TAG_END) { output.writeUTF(key); tag.write(output); }
        }
        output.writeByte(TAG_END);
    }
    @Override public byte getId() { return TAG_COMPOUND; }
    public Set<String> getAllKeys() { return tags.keySet(); }
    public int size() { return tags.size(); }
    public Tag put(String key, Tag value) { if (value == null) throw new IllegalArgumentException("Invalid null NBT value with key " + key); return tags.put(key, value); }
    public void putByte(String key, byte value) { tags.put(key, ByteTag.valueOf(value)); }
    public void putShort(String key, short value) { tags.put(key, ShortTag.valueOf(value)); }
    public void putInt(String key, int value) { tags.put(key, IntTag.valueOf(value)); }
    public void putLong(String key, long value) { tags.put(key, LongTag.valueOf(value)); }
    public void putUUID(String key, UUID value) {
        tags.put(key, new IntArrayTag(new int[]{(int) (value.getMostSignificantBits() >> 32), (int) value.getMostSignificantBits(),
                (int) (value.getLeastSignificantBits() >> 32), (int) value.getLeastSignificantBits()}));
    }
    public UUID getUUID(String key) {
        int[] a = ((IntArrayTag) get(key)).getAsIntArray();
        if (a.length != 4) throw new IllegalArgumentException("Expected UUID-Array to be of length 4, but found " + a.length + ".");
        return new UUID((long) a[0] << 32 | a[1] & 0xFFFFFFFFL, (long) a[2] << 32 | a[3] & 0xFFFFFFFFL);
    }
    public boolean hasUUID(String key) { Tag tag = get(key); return tag != null && tag.getId() == TAG_INT_ARRAY && ((IntArrayTag) tag).getAsIntArray().length == 4; }
    public void putFloat(String key, float value) { tags.put(key, FloatTag.valueOf(value)); }
    public void putDouble(String key, double value) { tags.put(key, DoubleTag.valueOf(value)); }
    public void putString(String key, String value) { tags.put(key, StringTag.valueOf(value)); }
    public void putByteArray(String key, byte[] value) { tags.put(key, new ByteArrayTag(value)); }
    public void putIntArray(String key, int[] value) { tags.put(key, new IntArrayTag(value)); }
    public void putLongArray(String key, long[] value) { tags.put(key, new LongArrayTag(value)); }
    public void putBoolean(String key, boolean value) { tags.put(key, ByteTag.valueOf(value)); }
    public Tag get(String key) { return tags.get(key); }
    public byte getTagType(String key) { Tag tag = tags.get(key); return tag == null ? TAG_END : tag.getId(); }
    public boolean contains(String key) { return tags.containsKey(key); }
    public boolean contains(String key, int tagType) {
        int type = getTagType(key);
        if (type == tagType) return true;
        if (tagType != TAG_ANY_NUMERIC) return false;
        return type == TAG_BYTE || type == TAG_SHORT || type == TAG_INT || type == TAG_LONG || type == TAG_FLOAT || type == TAG_DOUBLE;
    }
    private NumericTag numeric(String key) { return contains(key, TAG_ANY_NUMERIC) ? (NumericTag) tags.get(key) : null; }
    public byte getByte(String key) { var n = numeric(key); return n == null ? 0 : n.getAsByte(); }
    public short getShort(String key) { var n = numeric(key); return n == null ? 0 : n.getAsShort(); }
    public int getInt(String key) { var n = numeric(key); return n == null ? 0 : n.getAsInt(); }
    public long getLong(String key) { var n = numeric(key); return n == null ? 0L : n.getAsLong(); }
    public float getFloat(String key) { var n = numeric(key); return n == null ? 0.0F : n.getAsFloat(); }
    public double getDouble(String key) { var n = numeric(key); return n == null ? 0.0 : n.getAsDouble(); }
    public String getString(String key) { return contains(key, TAG_STRING) ? tags.get(key).getAsString() : ""; }
    public byte[] getByteArray(String key) { return contains(key, TAG_BYTE_ARRAY) ? ((ByteArrayTag) tags.get(key)).getAsByteArray() : new byte[0]; }
    public int[] getIntArray(String key) { return contains(key, TAG_INT_ARRAY) ? ((IntArrayTag) tags.get(key)).getAsIntArray() : new int[0]; }
    public long[] getLongArray(String key) { return contains(key, TAG_LONG_ARRAY) ? ((LongArrayTag) tags.get(key)).getAsLongArray() : new long[0]; }
    public CompoundTag getCompound(String key) { return contains(key, TAG_COMPOUND) ? (CompoundTag) tags.get(key) : new CompoundTag(); }
    public ListTag getList(String key, int type) {
        if (getTagType(key) == TAG_LIST) {
            ListTag list = (ListTag) tags.get(key);
            if (!list.isEmpty() && list.getElementType() != type) return new ListTag();
            return list;
        }
        return new ListTag();
    }
    public boolean getBoolean(String key) { return getByte(key) != 0; }
    public void remove(String key) { tags.remove(key); }
    public boolean isEmpty() { return tags.isEmpty(); }
    @Override public CompoundTag copy() {
        var copied = new HashMap<String, Tag>();
        tags.forEach((key, value) -> copied.put(key, value.copy()));
        return new CompoundTag(copied);
    }
    public CompoundTag merge(CompoundTag other) {
        for (String key : other.tags.keySet()) {
            Tag tag = other.tags.get(key);
            if (tag.getId() == TAG_COMPOUND) {
                if (contains(key, TAG_COMPOUND)) getCompound(key).merge((CompoundTag) tag);
                else put(key, tag.copy());
            } else put(key, tag.copy());
        }
        return this;
    }
    @Override public boolean equals(Object other) { return this == other || other instanceof CompoundTag tag && Objects.equals(tags, tag.tags); }
    @Override public int hashCode() { return tags.hashCode(); }
    @Override public String getAsString() { return tags.toString(); }
    @Override public String toString() { return getAsString(); }
}
