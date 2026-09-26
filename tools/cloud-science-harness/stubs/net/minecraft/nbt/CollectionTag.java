package net.minecraft.nbt;

import java.util.AbstractList;

public abstract class CollectionTag<T extends Tag> extends AbstractList<T> implements Tag {
    protected CollectionTag() {}
    @Override public abstract T set(int index, T tag);
    @Override public abstract void add(int index, T tag);
    @Override public abstract T remove(int index);
    public abstract boolean setTag(int index, Tag tag);
    public abstract boolean addTag(int index, Tag tag);
    public abstract byte getElementType();
}
