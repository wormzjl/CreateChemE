package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class LongTag extends NumericTag {
    private final long data;
    private LongTag(long data) { this.data = data; }
    public static LongTag valueOf(long data) { return new LongTag(data); }
    @Override public void write(DataOutput output) throws IOException { output.writeLong(data); }
    @Override public byte getId() { return TAG_LONG; }
    @Override public LongTag copy() { return this; }
    @Override public boolean equals(Object other) { return this == other || other instanceof LongTag tag && data == tag.data; }
    @Override public int hashCode() { return (int) (data ^ data >>> 32); }
    @Override public String getAsString() { return String.valueOf(data) + "L"; }
    @Override public long getAsLong() { return (long) data; }
    @Override public int getAsInt() { return (int) data; }
    @Override public short getAsShort() { return (short) ((int) data & 0xFFFF); }
    @Override public byte getAsByte() { return (byte) ((int) data & 0xFF); }
    @Override public double getAsDouble() { return data; }
    @Override public float getAsFloat() { return (float) data; }
    @Override public Number getAsNumber() { return data; }
}
