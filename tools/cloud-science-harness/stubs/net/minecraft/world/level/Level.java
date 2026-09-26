package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Harness stand-in: the dimension keys are real values; world access is compile-time only. */
public abstract class Level {
    public static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    public static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));
    public static final ResourceKey<Level> END = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_end"));
    protected Level() {}
    public boolean hasChunkAt(BlockPos pos) { throw new UnsupportedOperationException("harness stub: Level.hasChunkAt"); }
    public boolean addFreshEntity(Entity entity) { throw new UnsupportedOperationException("harness stub: Level.addFreshEntity"); }
    public BlockState getBlockState(BlockPos pos) { throw new UnsupportedOperationException("harness stub: Level.getBlockState"); }
    public BlockEntity getBlockEntity(BlockPos pos) { throw new UnsupportedOperationException("harness stub: Level.getBlockEntity"); }
    public boolean hasChunk(int x, int z) { throw new UnsupportedOperationException("harness stub: Level.hasChunk"); }
    public ResourceKey<Level> dimension() { throw new UnsupportedOperationException("harness stub: Level.dimension"); }
}
