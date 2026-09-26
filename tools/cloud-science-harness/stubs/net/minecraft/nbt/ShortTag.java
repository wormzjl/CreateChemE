package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class ShortTag extends NumericTag {
    private final short data;
    private ShortTag(short data) { this.data = data; }
    public static ShortTag valueOf(short data) { return new ShortTag(data); }
    @Override public void write(DataOutput output) throws IOException { output.writeShort(data); }
    @Override public byte getId() { return TAG_SHORT; }
    @Override public ShortTag copy() { return this; }
    @Override public boolean equals(Object other) { return this == other || other instanceof ShortTag tag && data == tag.data; }
    @Override public int hashCode() { return data; }
    @Override public String getAsString() { return String.valueOf(data) + "s"; }
    @Override public long getAsLong() { return (long) data; }
    @Override public int getAsInt() { return data; }
    @Override public short getAsShort() { return (short) (data & 0xFFFF); }
    @Override public byte getAsByte() { return (byte) (data & 0xFF); }
    @Override public double getAsDouble() { return data; }
    @Override public float getAsFloat() { return (float) data; }
    @Override public Number getAsNumber() { return data; }
}
