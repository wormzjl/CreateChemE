package net.minecraft.server.level;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Harness stand-in: compile-time only. */
public abstract class ServerPlayer extends Player {
    public boolean hasDisconnected() { throw new UnsupportedOperationException("harness stub"); }
    public void sendSystemMessage(Component component) { throw new UnsupportedOperationException("harness stub"); }
}
