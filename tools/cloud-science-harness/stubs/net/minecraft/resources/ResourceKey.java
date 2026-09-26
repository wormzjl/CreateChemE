package net.minecraft.resources;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.core.Registry;

/** Harness stand-in for Minecraft's ResourceKey: interned, so equal keys are the same instance, as in Minecraft. */
public final class ResourceKey<T> {
    private static final ConcurrentMap<String, ResourceKey<?>> VALUES = new ConcurrentHashMap<>();
    private final ResourceLocation registryName, location;
    private ResourceKey(ResourceLocation registryName, ResourceLocation location) { this.registryName = registryName; this.location = location; }
    public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, ResourceLocation location) { return create(registry.location, location); }
    public static <T> ResourceKey<Registry<T>> createRegistryKey(ResourceLocation location) { return create(ResourceLocation.withDefaultNamespace("root"), location); }
    @SuppressWarnings("unchecked")
    private static <T> ResourceKey<T> create(ResourceLocation registry, ResourceLocation location) {
        return (ResourceKey<T>) VALUES.computeIfAbsent(registry + " " + location, k -> new ResourceKey<>(registry, location));
    }
    public ResourceLocation location() { return location; }
    public ResourceLocation registry() { return registryName; }
    public boolean isFor(ResourceKey<? extends Registry<?>> registry) { return registryName.equals(registry.location()); }
    @Override public String toString() { return "ResourceKey[" + registryName + " / " + location + "]"; }
}
