package net.minecraft.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class Registries {
    private Registries() {}
    public static final ResourceKey<Registry<Level>> DIMENSION = ResourceKey.createRegistryKey(ResourceLocation.withDefaultNamespace("dimension"));
}
