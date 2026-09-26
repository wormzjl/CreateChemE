package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class DoubleTag extends NumericTag {
    private final double data;
    private DoubleTag(double data) { this.data = data; }
    public static DoubleTag valueOf(double data) { return new DoubleTag(data); }
    @Override public void write(DataOutput output) throws IOException { output.writeDouble(data); }
    @Override public byte getId() { return TAG_DOUBLE; }
    @Override public DoubleTag copy() { return this; }
    @Override public boolean equals(Object other) { return this == other || other instanceof DoubleTag tag && data == tag.data; }
    @Override public int hashCode() { return Long.hashCode(Double.doubleToLongBits(data)); }
    @Override public String getAsString() { return String.valueOf(data) + "d"; }
    @Override public long getAsLong() { return (long) Math.floor(data); }
    @Override public int getAsInt() { return (int) Math.floor(data); }
    @Override public short getAsShort() { return (short) ((int) Math.floor(data) & 0xFFFF); }
    @Override public byte getAsByte() { return (byte) ((int) Math.floor(data) & 0xFF); }
    @Override public double getAsDouble() { return data; }
    @Override public float getAsFloat() { return (float) data; }
    @Override public Number getAsNumber() { return data; }
}
