package net.minecraft.world.level.storage;

public final class LevelResource {
    public static final LevelResource ROOT = new LevelResource(".");
    private final String id;
    private LevelResource(String id) { this.id = id; }
    public String getId() { return id; }
}
