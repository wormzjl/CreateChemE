package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.runtime.PresentationMailbox;
import com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.IdentityHashMap;
import java.util.Map;

/** Session input mailbox flushed only by the simulation engine, never by packet or menu callbacks. */
final class ColumnPresentation {
    private static final Map<MinecraftServer, ColumnPresentation> SERVERS = new IdentityHashMap<>();
    private final PresentationMailbox<Session> mailbox = new PresentationMailbox<>();
    private final Map<ServerPlayer, Session> sessions = new IdentityHashMap<>();
    private record Rejection(BlockPos pos, long nonce, String reason) {}
    private static final class Session {
        final ServerPlayer player;
        final ColumnCalculatorV3Menu menu;
        final net.minecraft.server.level.ServerLevel level;
        final net.minecraft.world.level.block.entity.BlockEntity target;
        long revision = -1, nonce;
        boolean refresh = true;
        Rejection rejection;
        Session(ServerPlayer player, ColumnCalculatorV3Menu menu) {
            this.player=player;this.menu=menu;this.level=player.serverLevel();this.target=level.getBlockEntity(menu.blockPos());
        }
        boolean valid() {
            return !player.hasDisconnected() && player.containerMenu==menu && player.serverLevel()==level
                    && menu.stillValid(player) && level.getBlockEntity(menu.blockPos())==target;
        }
    }

    static void queue(IPayloadContext context, BlockPos pos, long nonce, Runnable action) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ColumnCalculatorV3Menu menu) || !menu.blockPos().equals(pos)) return;
        var engine = FluidWorldAuthority.find(player.getServer());
        if (engine.isEmpty()) return; // No active simulation may accept a command.
        var host = SERVERS.computeIfAbsent(player.getServer(), ignored -> new ColumnPresentation());
        Session session = host.sessions.get(player);
        if (session == null || session.menu != menu) {
            if (session != null) host.mailbox.remove(session);
            session = new Session(player, menu); host.sessions.put(player, session);
        }
        host.mailbox.subscribe(session, engine.orElseThrow().onlineTick());
        if (action == null) { session.refresh = true; return; }
        if (!host.mailbox.submit(session, engine.orElseThrow().onlineTick(), action))
            session.rejection = new Rejection(pos, nonce, "A request is already awaiting the engine");
    }

    static void acknowledged(IPayloadContext context, V3State state) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var host = SERVERS.get(player.getServer());
        var session = host == null ? null : host.sessions.get(player);
        if (session != null) { session.nonce = state.clientNonce(); session.refresh = true; }
    }

    static void rejected(IPayloadContext context, BlockPos pos, long nonce, String reason) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var host = SERVERS.get(player.getServer());
        var session = host == null ? null : host.sessions.get(player);
        if (session != null) session.rejection = new Rejection(pos, nonce, reason);
    }

    static void tick(MinecraftServer server, long now) {
        var host = SERVERS.get(server);
        if (host == null) return;
        host.mailbox.tick(now, session -> {
            if (session.valid()) return true;
            host.sessions.remove(session.player, session);
            return false;
        }, session -> {
            var level = session.player.serverLevel();
            if (level.getBlockEntity(session.menu.blockPos()) instanceof ColumnCalculatorV3BlockEntity calculator) {
                var state = calculator.state(session.nonce);
                if (session.refresh || session.revision != state.stateRevision()) {
                    ColumnV3Network.deliverState(session.player, session.menu.blockPos(), state);
                    session.revision = state.stateRevision(); session.refresh = false; session.nonce = 0;
                }
            }
            if (session.rejection != null) {
                var rejection = session.rejection; session.rejection = null;
                ColumnV3Network.deliverRejection(session.player, rejection.pos(), rejection.nonce(), rejection.reason());
            }
        });
    }

    static void refresh(MinecraftServer server) {
        var host = SERVERS.get(server);
        if (host != null) host.sessions.values().forEach(session -> session.refresh = true);
    }

    static void forget(MinecraftServer server) { SERVERS.remove(server); }
}
