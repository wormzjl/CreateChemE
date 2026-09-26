package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class ByteTag extends NumericTag {
    private final byte data;
    private ByteTag(byte data) { this.data = data; }
    public static final ByteTag ZERO = new ByteTag((byte) 0), ONE = new ByteTag((byte) 1);
    public static ByteTag valueOf(byte data) { return new ByteTag(data); }
    public static ByteTag valueOf(boolean data) { return data ? ONE : ZERO; }
    @Override public void write(DataOutput output) throws IOException { output.writeByte(data); }
    @Override public byte getId() { return TAG_BYTE; }
    @Override public ByteTag copy() { return this; }
    @Override public boolean equals(Object other) { return this == other || other instanceof ByteTag tag && data == tag.data; }
    @Override public int hashCode() { return data; }
    @Override public String getAsString() { return String.valueOf(data) + "b"; }
    @Override public long getAsLong() { return (long) data; }
    @Override public int getAsInt() { return data; }
    @Override public short getAsShort() { return (short) (data & 0xFFFF); }
    @Override public byte getAsByte() { return (byte) (data & 0xFF); }
    @Override public double getAsDouble() { return data; }
    @Override public float getAsFloat() { return (float) data; }
    @Override public Number getAsNumber() { return data; }
}
