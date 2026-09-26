package net.minecraft.nbt;

import java.io.DataOutput;
import java.io.IOException;

/** Harness stand-in for Minecraft 1.21.1 {@code Tag}: same type ids, same payload encoding (see NbtIo). */
public interface Tag {
    int OBJECT_HEADER = 8, ARRAY_HEADER = 12, OBJECT_REFERENCE = 4, STRING_SIZE = 28;
    byte TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4, TAG_FLOAT = 5, TAG_DOUBLE = 6,
            TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9, TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;
    int TAG_ANY_NUMERIC = 99;
    int MAX_DEPTH = 512;

    void write(DataOutput output) throws IOException;
    byte getId();
    Tag copy();
    default String getAsString() { return toString(); }
    default int sizeInBytes() { return 0; }
}
