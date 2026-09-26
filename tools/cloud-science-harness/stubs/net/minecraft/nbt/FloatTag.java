package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class FloatTag extends NumericTag {
    private final float data;
    private FloatTag(float data) { this.data = data; }
    public static FloatTag valueOf(float data) { return new FloatTag(data); }
    @Override public void write(DataOutput output) throws IOException { output.writeFloat(data); }
    @Override public byte getId() { return TAG_FLOAT; }
    @Override public FloatTag copy() { return this; }
    @Override public boolean equals(Object other) { return this == other || other instanceof FloatTag tag && data == tag.data; }
    @Override public int hashCode() { return Float.floatToIntBits(data); }
    @Override public String getAsString() { return String.valueOf(data) + "f"; }
    @Override public long getAsLong() { return (long) Math.floor(data); }
    @Override public int getAsInt() { return (int) Math.floor(data); }
    @Override public short getAsShort() { return (short) ((int) Math.floor(data) & 0xFFFF); }
    @Override public byte getAsByte() { return (byte) ((int) Math.floor(data) & 0xFF); }
    @Override public double getAsDouble() { return data; }
    @Override public float getAsFloat() { return (float) data; }
    @Override public Number getAsNumber() { return data; }
}
