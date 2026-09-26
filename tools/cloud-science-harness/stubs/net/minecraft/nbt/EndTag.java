package net.minecraft.nbt;

import java.io.DataOutput;

public final class EndTag implements Tag {
    public static final EndTag INSTANCE = new EndTag();
    private EndTag() {}
    @Override public void write(DataOutput output) {}
    @Override public byte getId() { return TAG_END; }
    @Override public EndTag copy() { return this; }
    @Override public String toString() { return "END"; }
}
