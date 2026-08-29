package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everybody is, for the dispatch map's live player layer.
 *
 * <p>A holder and nothing more: {@link com.stationannouncer.mtraddon.AddonInit}'s ticker
 * calls {@link #capture(MinecraftServer)} four times a second on the SERVER thread, which
 * publishes one immutable list; {@link DispatchStreamer} reads that volatile reference on
 * its own thread while building each frame. No simulator, no packet, no persistence —
 * player positions are live-only, and a client that misses a frame gets the next one.</p>
 *
 * <p>The MTR dimension id is resolved the same way {@code DisruptionBroadcaster} does it
 * ({@code Init.getWorldId} over a mapping-holder {@code World}), so the strings match the
 * {@code simulator.dimension} the streamer filters by. The whole capture is wrapped
 * defensively: a player layer is never worth a tick exception.</p>
 */
public final class PlayerPositions {
    /** One player's published position. Immutable; the uuid never reaches the wire. */
    public record Position(String name, String uuid, double x, double y, double z, String worldId) {
    }

    /** Immutable published snapshot. Written on the server thread, read anywhere. */
    private static volatile List<Position> positions = List.of();
    /** One warning per session when a world's MTR id cannot be resolved. */
    private static volatile boolean warned;

    private PlayerPositions() {
    }

    /**
     * Server thread. Rebuilds the snapshot from the live player list — a handful of field
     * reads and one {@code getWorldId} per player, so this stays well under a microsecond
     * per player and allocates one list per capture.
     */
    public static void capture(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) {
                if (!positions.isEmpty()) {
                    positions = List.of();
                }
                return;
            }
            List<Position> captured = new ArrayList<>(players.size());
            for (ServerPlayerEntity player : players) {
                String worldId = worldId(player);
                if (worldId == null) {
                    continue;
                }
                captured.add(new Position(player.getName().getString(), player.getUuidAsString(),
                        player.getX(), player.getY(), player.getZ(), worldId));
            }
            positions = List.copyOf(captured);
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not capture player positions for the dispatch map", t);
        }
    }

    /** The current snapshot. Safe from any thread: one volatile read of an immutable list. */
    public static List<Position> get() {
        return positions;
    }

    /** SERVER_STOPPING: nobody is anywhere any more. */
    public static void clear() {
        positions = List.of();
        warned = false;
    }

    private static String worldId(ServerPlayerEntity player) {
        try {
            return org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(player.getServerWorld()));
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a player's world;"
                        + " they are left off the dispatch map", t);
            }
            return null;
        }
    }
}
