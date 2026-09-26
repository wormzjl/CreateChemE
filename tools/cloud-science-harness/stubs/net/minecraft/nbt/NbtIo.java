package net.minecraft.nbt;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Harness stand-in for Minecraft 1.21.1 {@code NbtIo}: the same binary NBT layout (named root compound, gzip for the compressed forms). */
public final class NbtIo {
    private NbtIo() {}
    public static CompoundTag readCompressed(Path path, NbtAccounter accounter) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return readCompressed(in, accounter); }
    }
    public static CompoundTag readCompressed(InputStream stream, NbtAccounter accounter) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(stream)))) { return read(input, accounter); }
    }
    public static void writeCompressed(CompoundTag tag, Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) { writeCompressed(tag, out); }
    }
    public static void writeCompressed(CompoundTag tag, OutputStream stream) throws IOException {
        var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(stream)));
        write(tag, output);
        output.flush();
        output.close();
    }
    public static void write(CompoundTag tag, Path path) throws IOException {
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) { write(tag, output); }
    }
    public static CompoundTag read(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) { return read(input, NbtAccounter.unlimitedHeap()); }
    }
    public static CompoundTag read(DataInput input) throws IOException { return read(input, NbtAccounter.unlimitedHeap()); }
    public static CompoundTag read(DataInput input, NbtAccounter accounter) throws IOException {
        Tag tag = readUnnamedTag(input, accounter);
        if (tag instanceof CompoundTag compound) return compound;
        throw new IOException("Root tag must be a named compound tag");
    }
    public static void write(CompoundTag tag, DataOutput output) throws IOException { writeUnnamedTag(tag, output); }
    public static void writeUnnamedTag(Tag tag, DataOutput output) throws IOException {
        output.writeByte(tag.getId());
        if (tag.getId() != Tag.TAG_END) { output.writeUTF(""); tag.write(output); }
    }
    public static Tag readUnnamedTag(DataInput input, NbtAccounter accounter) throws IOException {
        byte type = input.readByte();
        if (type == Tag.TAG_END) return EndTag.INSTANCE;
        input.readUTF();
        return readPayload(type, input, 0);
    }
    private static Tag readPayload(byte type, DataInput in, int depth) throws IOException {
        if (depth > Tag.MAX_DEPTH) throw new IOException("Tried to read NBT tag with too high complexity, depth > " + Tag.MAX_DEPTH);
        switch (type) {
            case Tag.TAG_END: return EndTag.INSTANCE;
            case Tag.TAG_BYTE: return ByteTag.valueOf(in.readByte());
            case Tag.TAG_SHORT: return ShortTag.valueOf(in.readShort());
            case Tag.TAG_INT: return IntTag.valueOf(in.readInt());
            case Tag.TAG_LONG: return LongTag.valueOf(in.readLong());
            case Tag.TAG_FLOAT: return FloatTag.valueOf(in.readFloat());
            case Tag.TAG_DOUBLE: return DoubleTag.valueOf(in.readDouble());
            case Tag.TAG_BYTE_ARRAY: { byte[] a = new byte[in.readInt()]; in.readFully(a); return new ByteArrayTag(a); }
            case Tag.TAG_STRING: return StringTag.valueOf(in.readUTF());
            case Tag.TAG_LIST: {
                byte element = in.readByte(); int size = in.readInt();
                if (element == Tag.TAG_END && size > 0) throw new IOException("Missing type on ListTag");
                var list = new ArrayList<Tag>(size);
                for (int i = 0; i < size; i++) list.add(readPayload(element, in, depth + 1));
                return new ListTag(list, element);
            }
            case Tag.TAG_COMPOUND: {
                var tag = new CompoundTag();
                byte id;
                while ((id = in.readByte()) != Tag.TAG_END) { String key = in.readUTF(); tag.put(key, readPayload(id, in, depth + 1)); }
                return tag;
            }
            case Tag.TAG_INT_ARRAY: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return new IntArrayTag(a); }
            case Tag.TAG_LONG_ARRAY: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return new LongArrayTag(a); }
            default: throw new IOException("Invalid tag id: " + type);
        }
    }
}
