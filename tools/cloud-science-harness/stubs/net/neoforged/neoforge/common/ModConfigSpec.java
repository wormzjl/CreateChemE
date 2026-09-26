package net.neoforged.neoforge.common;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Harness stand-in for NeoForge's ModConfigSpec: the builder records each value's default, and every value reads as
 * that default (no config file is ever loaded). Ranges are checked as the real builder checks the default.
 */
public final class ModConfigSpec {
    private ModConfigSpec() {}

    public static class ConfigValue<T> implements Supplier<T> {
        private final String path;
        private final T defaultValue;
        ConfigValue(String path, T defaultValue) { this.path = path; this.defaultValue = defaultValue; }
        @Override public T get() { return defaultValue; }
        public T getDefault() { return defaultValue; }
        public String getPath() { return path; }
    }
    public static final class IntValue extends ConfigValue<Integer> implements IntSupplier {
        IntValue(String path, int value) { super(path, value); }
        @Override public int getAsInt() { return get(); }
    }
    public static final class LongValue extends ConfigValue<Long> {
        LongValue(String path, long value) { super(path, value); }
        public long getAsLong() { return get(); }
    }
    public static final class DoubleValue extends ConfigValue<Double> implements DoubleSupplier {
        DoubleValue(String path, double value) { super(path, value); }
        @Override public double getAsDouble() { return get(); }
    }
    public static final class BooleanValue extends ConfigValue<Boolean> implements BooleanSupplier {
        BooleanValue(String path, boolean value) { super(path, value); }
        @Override public boolean getAsBoolean() { return get(); }
    }
    public static final class EnumValue<T extends Enum<T>> extends ConfigValue<T> {
        EnumValue(String path, T value) { super(path, value); }
    }

    public static final class Builder {
        private final java.util.ArrayDeque<String> path = new java.util.ArrayDeque<>();
        private String qualified(String name) { var parts = new java.util.ArrayList<>(path); java.util.Collections.reverse(parts); parts.add(name); return String.join(".", parts); }
        public Builder comment(String... lines) { return this; }
        public Builder translation(String key) { return this; }
        public Builder worldRestart() { return this; }
        public Builder gameRestart() { return this; }
        public Builder push(String name) { path.push(name); return this; }
        public Builder pop() { path.pop(); return this; }
        public Builder pop(int count) { for (int i = 0; i < count; i++) path.pop(); return this; }
        public BooleanValue define(String name, boolean defaultValue) { return new BooleanValue(qualified(name), defaultValue); }
        public IntValue defineInRange(String name, int defaultValue, int min, int max) { check(name, defaultValue, min, max); return new IntValue(qualified(name), defaultValue); }
        public LongValue defineInRange(String name, long defaultValue, long min, long max) { check(name, defaultValue, min, max); return new LongValue(qualified(name), defaultValue); }
        public DoubleValue defineInRange(String name, double defaultValue, double min, double max) { check(name, defaultValue, min, max); return new DoubleValue(qualified(name), defaultValue); }
        public <V extends Enum<V>> EnumValue<V> defineEnum(String name, V defaultValue) { return new EnumValue<>(qualified(name), defaultValue); }
        public ModConfigSpec build() { return new ModConfigSpec(); }
        private static void check(String name, double value, double min, double max) {
            if (!(value >= min && value <= max)) throw new IllegalArgumentException("Default of " + name + " (" + value + ") outside [" + min + ", " + max + "]");
        }
    }
}
