package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

public final class StringTag implements Tag {
    private static final StringTag EMPTY = new StringTag("");
    private final String data;
    private StringTag(String data) { this.data = Objects.requireNonNull(data, "Null string not allowed"); }
    public static StringTag valueOf(String data) { return data.isEmpty() ? EMPTY : new StringTag(data); }
    @Override public void write(DataOutput output) throws IOException { output.writeUTF(data); }
    @Override public byte getId() { return TAG_STRING; }
    @Override public StringTag copy() { return this; }
    @Override public String getAsString() { return data; }
    @Override public String toString() { return '"' + data + '"'; }
    @Override public boolean equals(Object other) { return this == other || other instanceof StringTag tag && data.equals(tag.data); }
    @Override public int hashCode() { return data.hashCode(); }
}
