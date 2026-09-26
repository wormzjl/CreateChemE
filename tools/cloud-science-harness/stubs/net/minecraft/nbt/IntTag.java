package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class IntTag extends NumericTag {
    private final int data;
    private IntTag(int data) { this.data = data; }
    public static IntTag valueOf(int data) { return new IntTag(data); }
    @Override public void write(DataOutput output) throws IOException { output.writeInt(data); }
    @Override public byte getId() { return TAG_INT; }
    @Override public IntTag copy() { return this; }
    @Override public boolean equals(Object other) { return this == other || other instanceof IntTag tag && data == tag.data; }
    @Override public int hashCode() { return data; }
    @Override public String getAsString() { return String.valueOf(data) + ""; }
    @Override public long getAsLong() { return (long) data; }
    @Override public int getAsInt() { return data; }
    @Override public short getAsShort() { return (short) (data & 0xFFFF); }
    @Override public byte getAsByte() { return (byte) (data & 0xFF); }
    @Override public double getAsDouble() { return data; }
    @Override public float getAsFloat() { return (float) data; }
    @Override public Number getAsNumber() { return data; }
}
