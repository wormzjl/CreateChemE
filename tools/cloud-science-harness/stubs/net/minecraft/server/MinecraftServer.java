package net.minecraft.server;

import java.nio.file.Path;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Harness stand-in: no fluid test starts a server; every method is compile-time only. */
public abstract class MinecraftServer {
    public boolean isSameThread() { throw new UnsupportedOperationException("harness stub: MinecraftServer.isSameThread"); }
    public void tell(TickTask task) { throw new UnsupportedOperationException("harness stub: MinecraftServer.tell"); }
    public void execute(Runnable task) { throw new UnsupportedOperationException("harness stub: MinecraftServer.execute"); }
    public ServerLevel getLevel(ResourceKey<Level> dimension) { throw new UnsupportedOperationException("harness stub: MinecraftServer.getLevel"); }
    public ServerLevel overworld() { throw new UnsupportedOperationException("harness stub: MinecraftServer.overworld"); }
    public Path getWorldPath(LevelResource resource) { throw new UnsupportedOperationException("harness stub: MinecraftServer.getWorldPath"); }
    public PlayerList getPlayerList() { throw new UnsupportedOperationException("harness stub: MinecraftServer.getPlayerList"); }
    public int getTickCount() { throw new UnsupportedOperationException("harness stub: MinecraftServer.getTickCount"); }
}
