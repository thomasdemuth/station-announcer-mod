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
 * <h2>THE AUTHORITATIVE WIRE FORMAT (v2)</h2>
 * This is a fixed contract; the client HUD decodes it verbatim. Fields are written in
 * exactly this order:
 * <pre>
 * writeString(destination, 64)      // "" allowed
 * writeVarInt(legCount)             // 0 = CLEAR / stop navigating, max 24
 * per leg:
 *   writeByte(type)                 // 0 = walk, 1 = ride, 2 = transfer
 *   type 0 WALK:
 *     writeByte(fromKind)           // 0 = coords, 1 = platform
 *     fromKind 0 -&gt; writeDouble x, y, z
 *     fromKind 1 -&gt; writeLong platformId, writeDouble x, y, z
 *     writeString(fromName, 64)     // "" = unknown
 *     writeByte(toKind); same encoding
 *     writeString(toName, 64)
 *     writeVarInt(metres)
 *   type 1 RIDE:
 *     writeLong(routeId)
 *     writeString(routeName, 48)    // already displayName()'d, "" = unknown
 *     writeString(routeLabel, 8)    // the bullet text, e.g. "6"
 *     writeInt(routeColor)          // 0xRRGGBB; −1 = unknown
 *     writeString(headsign, 64)     // where the train is going, "" = unknown
 *     writeLong(boardPlatformId);  writeDouble bx, by, bz;  writeString(boardName, 64)
 *     writeLong(alightPlatformId); writeDouble ax, ay, az;  writeString(alightName, 64)
 *     writeVarInt(stops)
 *     writeVarInt(viaCount)         // through-run continuations, max 4
 *     per via: writeLong(routeId), writeLong(atPlatformId), writeString(routeName, 48),
 *              writeString(routeLabel, 8), writeInt(routeColor), writeString(headsign, 64)
 *     writeVarInt(stopListCount)    // max 48; 0 = not supplied
 *     per stop: writeString(name, 64), writeDouble x, y, z
 *   type 2 TRANSFER:
 *     writeLong(fromPlatformId); writeDouble fx, fy, fz; writeString(fromName, 64)
 *     writeLong(toPlatformId);   writeDouble tx, ty, tz; writeString(toName, 64)
 *     writeVarInt(metres)
 * after legs:
 * writeLong(plannedArriveMs)        // 0 = unknown
 * </pre>
 *
 * <p><b>Why the names and positions are on the wire at all.</b> v1 carried only IDS and
 * left the client to resolve them out of {@code platformIdMap} / {@code
 * simplifiedRouteIdMap}. MTR syncs those AROUND THE PLAYER, so every stop and route
 * outside the synced area resolved to nothing: the itinerary printed "Unknown stop" and
 * "?", and — silently — the waypoint had no target and the stop countdown could not
 * advance, because both need platform POSITIONS. Every id therefore now travels beside
 * the display name and world position the server resolved for it, and the client prefers
 * its own live lookup only when that lookup SUCCEEDS.</p>
 *
 * <p>A position of exactly {@code (0, 0, 0)} means "unknown" and the client treats it as
 * absent; an empty string likewise. Writer and reader ship in the same jar, so there is
 * no v1 compatibility shim — the two change together.</p>
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
    /** Contract cap, mirrored in {@link NavPlanner#MAX_STOP_LIST}. */
    public static final int MAX_STOP_LIST = 48;
    /** Contract cap on the destination string. */
    public static final int MAX_DESTINATION_LENGTH = 64;
    /** Contract cap on every place name (walk/transfer endpoints, board/alight, stops). */
    public static final int MAX_NAME_LENGTH = 64;
    /** Contract cap on a route's display name. */
    public static final int MAX_ROUTE_NAME_LENGTH = 48;
    /** Contract cap on a route's bullet label. */
    public static final int MAX_ROUTE_LABEL_LENGTH = 8;
    /** Contract cap on a ride's headsign. */
    public static final int MAX_HEADSIGN_LENGTH = 64;

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
        buf.writeString(text(journey.destination(), MAX_DESTINATION_LENGTH), MAX_DESTINATION_LENGTH);

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
                buf.writeString(text(ride.routeName(), MAX_ROUTE_NAME_LENGTH), MAX_ROUTE_NAME_LENGTH);
                buf.writeString(text(ride.routeLabel(), MAX_ROUTE_LABEL_LENGTH), MAX_ROUTE_LABEL_LENGTH);
                buf.writeInt(color(ride.routeColor()));
                buf.writeString(text(ride.headsign(), MAX_HEADSIGN_LENGTH), MAX_HEADSIGN_LENGTH);
                writePlatform(buf, ride.board());
                writePlatform(buf, ride.alight());
                buf.writeVarInt(Math.max(0, ride.stops()));
                List<NavPlanner.Via> via = ride.via() == null ? List.of() : ride.via();
                int viaCount = Math.min(via.size(), MAX_VIA);
                buf.writeVarInt(viaCount);
                for (int v = 0; v < viaCount; v++) {
                    NavPlanner.Via entry = via.get(v);
                    buf.writeLong(entry.routeId());
                    buf.writeLong(entry.atPlatformId());
                    buf.writeString(text(entry.routeName(), MAX_ROUTE_NAME_LENGTH), MAX_ROUTE_NAME_LENGTH);
                    buf.writeString(text(entry.routeLabel(), MAX_ROUTE_LABEL_LENGTH), MAX_ROUTE_LABEL_LENGTH);
                    buf.writeInt(color(entry.routeColor()));
                    buf.writeString(text(entry.headsign(), MAX_HEADSIGN_LENGTH), MAX_HEADSIGN_LENGTH);
                }
                List<NavPlanner.Stop> stopList = ride.stopList() == null ? List.of() : ride.stopList();
                int stopCount = Math.min(stopList.size(), MAX_STOP_LIST);
                buf.writeVarInt(stopCount);
                for (int s = 0; s < stopCount; s++) {
                    NavPlanner.Stop stop = stopList.get(s);
                    buf.writeString(text(stop == null ? "" : stop.name(), MAX_NAME_LENGTH), MAX_NAME_LENGTH);
                    buf.writeDouble(finite(stop == null ? 0 : stop.x()));
                    buf.writeDouble(finite(stop == null ? 0 : stop.y()));
                    buf.writeDouble(finite(stop == null ? 0 : stop.z()));
                }
            } else if (leg instanceof NavPlanner.TransferLeg transfer) {
                buf.writeByte(2);
                writePlatform(buf, transfer.from());
                writePlatform(buf, transfer.to());
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

    /**
     * A walk endpoint: {@code byte kind}, then three doubles (coords) or a long plus
     * three doubles (platform), then the display name. The POSITION is written for both
     * kinds — that is what lets the client aim its waypoint at a platform it has never
     * been near.
     */
    private static void writePoint(PacketByteBuf buf, NavPlanner.Point point) {
        boolean platform = point != null && point.platform();
        buf.writeByte(platform ? 1 : 0);
        if (platform) {
            buf.writeLong(point.platformId());
        }
        buf.writeDouble(finite(point == null ? 0 : point.x()));
        buf.writeDouble(finite(point == null ? 0 : point.y()));
        buf.writeDouble(finite(point == null ? 0 : point.z()));
        buf.writeString(text(point == null ? "" : point.name(), MAX_NAME_LENGTH), MAX_NAME_LENGTH);
    }

    /** A ride/transfer endpoint, which is always a platform: id, position, name. */
    private static void writePlatform(PacketByteBuf buf, NavPlanner.Point point) {
        buf.writeLong(point == null ? 0 : point.platformId());
        buf.writeDouble(finite(point == null ? 0 : point.x()));
        buf.writeDouble(finite(point == null ? 0 : point.y()));
        buf.writeDouble(finite(point == null ? 0 : point.z()));
        buf.writeString(text(point == null ? "" : point.name(), MAX_NAME_LENGTH), MAX_NAME_LENGTH);
    }

    /**
     * Never-null, control-character-free, capped at {@code maxLength} CHARACTERS — which
     * is what {@code PacketByteBuf.writeString} counts, so a name that came in from the
     * web endpoint can never make the packet throw at write time.
     */
    private static String text(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
            char c = value.charAt(i);
            if (c >= ' ' && c != 0x7F) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /** 0xRRGGBB, or −1 for "unknown" (which is the only negative value that survives). */
    private static int color(int value) {
        return value < 0 ? -1 : value & 0xFFFFFF;
    }

    /** NaN/∞ would decode as a non-finite coordinate and get the packet dropped. */
    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }
}
