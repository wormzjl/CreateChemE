package net.minecraft.server.players;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Harness stand-in: compile-time only. */
public abstract class PlayerList {
    public ServerPlayer getPlayer(UUID id) { throw new UnsupportedOperationException("harness stub"); }
    public List<ServerPlayer> getPlayers() { throw new UnsupportedOperationException("harness stub"); }
}
