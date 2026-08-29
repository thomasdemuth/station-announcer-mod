package com.stationannouncer.mtraddon.nav;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The one S2C packet behind in-game journey directions:
 * {@code station_announcer:addon_navigate}. Follows
 * {@link com.stationannouncer.mtraddon.AddonNetworking}'s style — build the buffer,
 * send it from the SERVER THREAD, cap every length.
 *
 * <h2>THE AUTHORITATIVE WIRE FORMAT</h2>
 * This is a fixed contract; the client HUD decodes it verbatim. Fields are written in
 * exactly this order:
 * <pre>
 * writeString(destination, 64)      // "" allowed
 * writeVarInt(legCount)             // 0 = CLEAR / stop navigating, max 24
 * per leg:
 *   writeByte(type)                 // 0 = walk, 1 = ride, 2 = transfer
 *   type 0 WALK:
 *     writeByte(fromKind)           // 0 = coords, 1 = platform
 *     fromKind 0 -&gt; writeDouble x, writeDouble y, writeDouble z
 *     fromKind 1 -&gt; writeLong platformId
 *     writeByte(toKind); same encoding
 *     writeVarInt(metres)
 *   type 1 RIDE:
 *     writeLong(routeId); writeLong(boardPlatformId); writeLong(alightPlatformId)
 *     writeVarInt(stops)
 *     writeVarInt(viaCount)         // through-run continuations, max 4
 *     per via: writeLong(routeId); writeLong(atPlatformId)
 *   type 2 TRANSFER:
 *     writeLong(fromPlatformId); writeLong(toPlatformId); writeVarInt(metres)
 * after legs:
 * writeLong(plannedArriveMs)        // 0 = unknown
 * </pre>
 *
 * <p>A CLEAR is simply {@code ("", 0, 0L)} — {@link #clear(ServerPlayerEntity)} writes
 * exactly that, so the client needs no second channel to stop navigating.</p>
 *
 * <p><b>Thread:</b> both entry points must be called on the SERVER thread. Everything
 * they touch is the plain {@link NavPlanner.Journey} record, so callers coming off a
 * simulator thread or a Jetty worker hop with {@code server.execute(...)} first.</p>
 */
public final class NavNetworking {
    public static final Identifier NAVIGATE_S2C = StationAnnouncer.id("addon_navigate");

    /** Contract cap, mirrored in {@link NavPlanner#MAX_LEGS}. */
    public static final int MAX_LEGS = 24;
    /** Contract cap, mirrored in {@link NavPlanner#MAX_VIA}. */
    public static final int MAX_VIA = 4;
    /** Contract cap on the destination string. */
    public static final int MAX_DESTINATION_LENGTH = 64;

    private NavNetworking() {
    }

    /** Server thread: push a planned journey to one player's HUD. */
    public static void send(ServerPlayerEntity player, NavPlanner.Journey journey) {
        if (player == null) {
            return;
        }
        if (journey == null) {
            clear(player);
            return;
        }
        try {
            ServerPlayNetworking.send(player, NAVIGATE_S2C, build(journey));
        } catch (Throwable throwable) {
            // A malformed journey must never take the connection (or the tick) down.
            StationAnnouncer.LOGGER.warn("Could not send navigation to {} ({})",
                    player.getName().getString(), throwable.toString());
        }
    }

    /** Server thread: tell one player's HUD to stop navigating. */
    public static void clear(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString("", MAX_DESTINATION_LENGTH);
        buf.writeVarInt(0);
        buf.writeLong(0L);
        try {
            ServerPlayNetworking.send(player, NAVIGATE_S2C, buf);
        } catch (Throwable throwable) {
            StationAnnouncer.LOGGER.warn("Could not clear navigation for {} ({})",
                    player.getName().getString(), throwable.toString());
        }
    }

    private static PacketByteBuf build(NavPlanner.Journey journey) {
        PacketByteBuf buf = PacketByteBufs.create();
        String destination = journey.destination() == null ? "" : journey.destination();
        if (destination.length() > MAX_DESTINATION_LENGTH) {
            destination = destination.substring(0, MAX_DESTINATION_LENGTH);
        }
        buf.writeString(destination, MAX_DESTINATION_LENGTH);

        List<NavPlanner.Leg> legs = journey.legs() == null ? List.of() : journey.legs();
        int legCount = Math.min(legs.size(), MAX_LEGS);
        buf.writeVarInt(legCount);
        for (int i = 0; i < legCount; i++) {
            NavPlanner.Leg leg = legs.get(i);
            if (leg instanceof NavPlanner.WalkLeg walk) {
                buf.writeByte(0);
                writePoint(buf, walk.from());
                writePoint(buf, walk.to());
                buf.writeVarInt(Math.max(0, walk.metres()));
            } else if (leg instanceof NavPlanner.RideLeg ride) {
                buf.writeByte(1);
                buf.writeLong(ride.routeId());
                buf.writeLong(ride.boardPlatformId());
                buf.writeLong(ride.alightPlatformId());
                buf.writeVarInt(Math.max(0, ride.stops()));
                List<NavPlanner.Via> via = ride.via() == null ? List.of() : ride.via();
                int viaCount = Math.min(via.size(), MAX_VIA);
                buf.writeVarInt(viaCount);
                for (int v = 0; v < viaCount; v++) {
                    buf.writeLong(via.get(v).routeId());
                    buf.writeLong(via.get(v).atPlatformId());
                }
            } else if (leg instanceof NavPlanner.TransferLeg transfer) {
                buf.writeByte(2);
                buf.writeLong(transfer.fromPlatformId());
                buf.writeLong(transfer.toPlatformId());
                buf.writeVarInt(Math.max(0, transfer.metres()));
            } else {
                // Unreachable while Leg has exactly three implementations; a future
                // fourth would otherwise desync the reader, so fail loudly here.
                throw new IllegalStateException("unknown leg type " + leg);
            }
        }
        buf.writeLong(Math.max(0, journey.plannedArriveMs()));
        return buf;
    }

    /** {@code byte kind} then either three doubles (coords) or one long (platform). */
    private static void writePoint(PacketByteBuf buf, NavPlanner.Point point) {
        if (point != null && point.platform()) {
            buf.writeByte(1);
            buf.writeLong(point.platformId());
            return;
        }
        buf.writeByte(0);
        buf.writeDouble(point == null ? 0 : point.x());
        buf.writeDouble(point == null ? 0 : point.y());
        buf.writeDouble(point == null ? 0 : point.z());
    }
}
