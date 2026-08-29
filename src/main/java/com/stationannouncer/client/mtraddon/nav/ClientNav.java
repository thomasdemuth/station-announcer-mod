package com.stationannouncer.client.mtraddon.nav;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.client.mtraddon.AddonClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.ArrivalsCacheClient;
import java.util.ArrayList;
import java.util.List;

/**
 * Client half of in-game journey directions: the received itinerary, the
 * progress tracker, and the derived instruction the HUD / waypoint / alerts
 * all read.
 *
 * <p><b>Performance contract</b> — the same one {@code DrivingHud} keeps: the
 * instruction is a {@link Snapshot} recomputed in a {@link ClientTickEvents}
 * handler at {@value #UPDATE_INTERVAL_MILLIS} ms (4 Hz), never per frame.
 * {@link NavHud} and {@link NavWaypointRenderer} only draw the cached
 * snapshot; the only per-frame work they do is text measuring, fills and a
 * blink-phase colour choice.</p>
 *
 * <p><b>MTR client lookups.</b> Every id is resolved through the cheap hash
 * maps MTR syncs to clients — {@code platformIdMap} for platform positions and
 * (through {@code Platform.area}) station names, {@code simplifiedRouteIdMap}
 * for route names/colours. The one genuinely expensive call, {@code
 * ArrivalsCacheClient.requestArrivals}, is wrapped in the same
 * fetched-at/TTL guard {@code DrivingHud} uses ({@value #ARRIVALS_TTL_MILLIS}
 * ms for one platform), which is exactly the discipline {@code MtrDataCache}
 * enforces for the block renderers — that class is package-private in
 * {@code client.mtr} and not ours to touch, so the guard is reproduced here
 * rather than shared. {@code InitClient.findStation} (a full station scan) is
 * never called at all: a platform already knows its station through
 * {@code area}, which is what {@code DrivingHud.stopName} relies on too.</p>
 *
 * <p>All state is static, touched only on the client thread (the packet
 * receiver hops with {@code client.execute}), and cleared on disconnect.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientNav {
    /** Server → client itinerary. {@code legCount == 0} clears navigation. */
    public static final Identifier NAVIGATE_S2C = StationAnnouncer.id("addon_navigate");

    // ------------------------------------------------------------ protocol caps
    private static final int MAX_DESTINATION_LENGTH = 64;
    private static final int MAX_LEGS = 24;
    private static final int MAX_VIA = 4;
    private static final int MAX_METRES = 1_000_000;
    private static final int MAX_STOPS = 4_096;

    private static final int LEG_WALK = 0;
    private static final int LEG_RIDE = 1;
    private static final int LEG_TRANSFER = 2;

    // ------------------------------------------------------------- tuning
    /** Snapshot rate — 4 Hz, matching the driving HUD. */
    private static final long UPDATE_INTERVAL_MILLIS = 250;

    /** Minimum age before the board-platform arrivals fetch is repeated. */
    private static final long ARRIVALS_TTL_MILLIS = 1000;

    /** Within this many blocks of a leg's end target, the leg is done. */
    private static final double ARRIVE_RADIUS = 24;

    /** Within this many blocks of a ride's platform, that stop counts as passed. */
    private static final double PASSED_RADIUS = 40;

    /** Above this ground speed (blocks per second) the player is treated as aboard. */
    private static final double ABOARD_SPEED = 8.0;

    /** Hard cap on a ride's resolved platform sequence. */
    private static final int MAX_SEQUENCE = 256;

    /** How long "You have arrived" lingers before navigation clears itself. */
    private static final long ARRIVED_LINGER_MILLIS = 30_000;

    /** A departure this close counts as the "departs in 1 min" moment. */
    private static final long DEPARTURE_ALERT_MILLIS = 60_000;

    /** Chat itinerary budget (header + legs + footer). */
    private static final int MAX_CHAT_LINES = 12;

    // ------------------------------------------------------------- state
    @Nullable
    private static Journey journey;
    private static int legIndex;
    private static int passedIndex;
    private static boolean arrived;
    private static long arrivedAt;

    @Nullable
    private static Snapshot snapshot;
    private static long nextUpdateMillis;

    /** Ride sequence cache, keyed by journey stamp + leg index. */
    @Nullable
    private static Ride cachedRide;
    private static long cachedRideKey = Long.MIN_VALUE;

    // Cached arrivals fetch for the boarding countdown (client thread only).
    private static long arrivalsFetchedAt;
    private static long arrivalsPlatformId;
    private static ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
    private static final LongArrayList ARRIVALS_REQUEST_IDS = new LongArrayList();

    // Speed sampling (aboard detection) between snapshots.
    @Nullable
    private static Vec3d lastSamplePos;
    private static long lastSampleMillis;
    private static double lastSpeed;

    // Once-per-occurrence alert guards.
    private static int alightAlertLeg = -1;
    private static int departureAlertLeg = -1;

    private static long journeyStamp;

    private ClientNav() {
    }

    // ----------------------------------------------------------- lifecycle

    /** Registers the packet receiver, the 4 Hz tracker, the HUD and the waypoint marker. */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NAVIGATE_S2C,
                (client, handler, buf, responseSender) -> {
                    Journey parsed;
                    try {
                        parsed = read(buf);
                    } catch (Exception e) {
                        // A malformed packet is dropped, never fatal.
                        StationAnnouncer.LOGGER.warn("Ignoring a malformed navigation packet", e);
                        return;
                    }
                    client.execute(() -> apply(parsed));
                });
        ClientTickEvents.END_CLIENT_TICK.register(ClientNav::tick);
        NavHud.register();
        NavWaypointRenderer.register();
    }

    /** Drops every piece of navigation state; called on disconnect and by Stop. */
    public static void stop() {
        journey = null;
        legIndex = 0;
        passedIndex = 0;
        arrived = false;
        arrivedAt = 0;
        snapshot = null;
        nextUpdateMillis = 0;
        cachedRide = null;
        cachedRideKey = Long.MIN_VALUE;
        arrivalsFetchedAt = 0;
        arrivalsPlatformId = 0;
        arrivals = new ObjectArrayList<>();
        ARRIVALS_REQUEST_IDS.clear();
        lastSamplePos = null;
        lastSampleMillis = 0;
        lastSpeed = 0;
        alightAlertLeg = -1;
        departureAlertLeg = -1;
    }

    /** The cached instruction, or null when no navigation is active. */
    @Nullable
    public static Snapshot snapshot() {
        return snapshot;
    }

    /** True while an itinerary is loaded (even if the instruction is momentarily unresolvable). */
    public static boolean isActive() {
        return journey != null;
    }

    // ------------------------------------------------------------- packet

    /**
     * Reads the itinerary off the wire in exactly the documented order. Every
     * count and length is bounded; anything out of range throws, and the caller
     * drops the packet.
     */
    private static Journey read(PacketByteBuf buf) {
        String destination = buf.readString(MAX_DESTINATION_LENGTH);
        int legCount = buf.readVarInt();
        if (legCount < 0 || legCount > MAX_LEGS) {
            throw new IllegalArgumentException("leg count " + legCount);
        }
        List<Leg> legs = new ArrayList<>(legCount);
        for (int i = 0; i < legCount; i++) {
            int type = buf.readByte();
            switch (type) {
                case LEG_WALK -> {
                    Endpoint from = readEndpoint(buf);
                    Endpoint to = readEndpoint(buf);
                    int metres = clampCount(buf.readVarInt(), MAX_METRES);
                    legs.add(Leg.walk(from, to, metres));
                }
                case LEG_RIDE -> {
                    long routeId = buf.readLong();
                    long boardPlatformId = buf.readLong();
                    long alightPlatformId = buf.readLong();
                    int stops = clampCount(buf.readVarInt(), MAX_STOPS);
                    int viaCount = buf.readVarInt();
                    if (viaCount < 0 || viaCount > MAX_VIA) {
                        throw new IllegalArgumentException("via count " + viaCount);
                    }
                    List<Via> vias = new ArrayList<>(viaCount);
                    for (int j = 0; j < viaCount; j++) {
                        long viaRouteId = buf.readLong();
                        long atPlatformId = buf.readLong();
                        vias.add(new Via(viaRouteId, atPlatformId));
                    }
                    legs.add(Leg.ride(routeId, boardPlatformId, alightPlatformId, stops, List.copyOf(vias)));
                }
                case LEG_TRANSFER -> {
                    long fromPlatformId = buf.readLong();
                    long toPlatformId = buf.readLong();
                    int metres = clampCount(buf.readVarInt(), MAX_METRES);
                    legs.add(Leg.transfer(fromPlatformId, toPlatformId, metres));
                }
                default -> throw new IllegalArgumentException("leg type " + type);
            }
        }
        long plannedArriveMs = buf.readLong();
        return new Journey(destination, List.copyOf(legs), plannedArriveMs);
    }

    private static Endpoint readEndpoint(PacketByteBuf buf) {
        int kind = buf.readByte();
        if (kind == 0) {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("non-finite coordinate");
            }
            return Endpoint.coords(x, y, z);
        }
        if (kind == 1) {
            return Endpoint.platform(buf.readLong());
        }
        throw new IllegalArgumentException("endpoint kind " + kind);
    }

    private static int clampCount(int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException("count " + value);
        }
        return value;
    }

    /** Installs (or clears) a freshly received itinerary. Client thread. */
    private static void apply(Journey incoming) {
        boolean hadJourney = journey != null;
        stop();
        if (incoming.legs().isEmpty()) {
            if (hadJourney && AddonClientConfig.get().navChatEnabled) {
                chat(Text.translatable("msg.station_announcer.nav.cleared").formatted(Formatting.GRAY));
            }
            return;
        }
        journey = incoming;
        journeyStamp++;
        if (AddonClientConfig.get().navChatEnabled) {
            printItinerary(incoming);
        }
        // Draw something immediately rather than waiting up to 250 ms.
        nextUpdateMillis = 0;
    }

    // --------------------------------------------------------------- tick

    private static void tick(MinecraftClient client) {
        if (journey == null || client.player == null) {
            if (snapshot != null) {
                snapshot = null;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextUpdateMillis) {
            return;
        }
        nextUpdateMillis = now + UPDATE_INTERVAL_MILLIS;
        try {
            sampleSpeed(client.player.getPos(), now);
            snapshot = compute(client, now);
        } catch (Exception e) {
            // MTR's client data can be swapped underneath us mid-sync; one
            // blank update beats a crash loop.
            snapshot = null;
        }
    }

    /** Ground speed in blocks per second, sampled between snapshots. */
    private static void sampleSpeed(Vec3d pos, long now) {
        if (lastSamplePos != null && now > lastSampleMillis) {
            double dx = pos.x - lastSamplePos.x;
            double dz = pos.z - lastSamplePos.z;
            lastSpeed = Math.sqrt(dx * dx + dz * dz) * 1000.0 / (now - lastSampleMillis);
        }
        lastSamplePos = pos;
        lastSampleMillis = now;
    }

    // ------------------------------------------------------------ compute

    @Nullable
    private static Snapshot compute(MinecraftClient client, long now) {
        Journey current = journey;
        if (current == null) {
            return null;
        }
        Vec3d player = client.player == null ? Vec3d.ZERO : client.player.getPos();

        if (arrived) {
            if (now - arrivedAt > ARRIVED_LINGER_MILLIS) {
                stop();
                return null;
            }
            return Snapshot.arrivedAt(firstLang(current.destination()));
        }

        if (legIndex >= current.legs().size()) {
            markArrived(client, now, firstLang(current.destination()));
            return Snapshot.arrivedAt(firstLang(current.destination()));
        }
        Leg leg = current.legs().get(legIndex);
        Ride ride = leg.type() == LEG_RIDE ? ride(current, leg) : null;

        if (ride != null) {
            advanceRideProgress(ride, player);
        }

        Vec3d endTarget = resolve(leg.endpointTo());
        boolean reached = endTarget != null && player.squaredDistanceTo(endTarget) <= ARRIVE_RADIUS * ARRIVE_RADIUS;
        if (ride != null) {
            // A ride ends only once the alight platform is both reached and the
            // last in the passed sequence — otherwise a route that loops back
            // past its own boarding platform would end the leg early.
            reached = reached && passedIndex >= ride.platformIds().size() - 1;
        }
        if (reached) {
            legIndex++;
            passedIndex = 0;
            cachedRide = null;
            cachedRideKey = Long.MIN_VALUE;
            if (legIndex >= current.legs().size()) {
                markArrived(client, now, firstLang(current.destination()));
                return Snapshot.arrivedAt(firstLang(current.destination()));
            }
            leg = current.legs().get(legIndex);
            ride = leg.type() == LEG_RIDE ? ride(current, leg) : null;
        }

        return switch (leg.type()) {
            case LEG_RIDE -> rideSnapshot(client, current, leg, ride, player, now);
            case LEG_TRANSFER -> transferSnapshot(leg, player);
            default -> walkSnapshot(leg, player);
        };
    }

    private static void markArrived(MinecraftClient client, long now, String destination) {
        if (arrived) {
            return;
        }
        arrived = true;
        arrivedAt = now;
        if (AddonClientConfig.get().navChatEnabled) {
            chat(Text.translatable("msg.station_announcer.nav.arrived", destination)
                    .formatted(Formatting.GREEN));
        }
    }

    // --------------------------------------------------------- walk / transfer

    private static Snapshot walkSnapshot(Leg leg, Vec3d player) {
        Vec3d target = resolve(leg.endpointTo());
        String where = endpointName(leg.endpointTo());
        String title = where.isEmpty()
                ? Text.translatable("gui.station_announcer.nav.walk").getString()
                : Text.translatable("gui.station_announcer.nav.walk_to", where).getString();
        String detail = detailDistance(target, player, leg.metres());
        return new Snapshot(true, 0, "", title, detail, 0, 0, false, NavHud.COLOR_TEXT,
                target, where, distance(target, player));
    }

    private static Snapshot transferSnapshot(Leg leg, Vec3d player) {
        Vec3d target = resolve(leg.endpointTo());
        String where = endpointName(leg.endpointTo());
        String title = Text.translatable("gui.station_announcer.nav.transfer", where).getString();
        String detail = detailDistance(target, player, leg.metres());
        return new Snapshot(true, 0, "", title, detail, 0, 0, false, NavHud.COLOR_TEXT,
                target, where, distance(target, player));
    }

    private static String detailDistance(@Nullable Vec3d target, Vec3d player, int metres) {
        double live = distance(target, player);
        long shown = live >= 0 ? Math.round(live) : metres;
        return Text.translatable("gui.station_announcer.nav.distance", shown).getString();
    }

    // ------------------------------------------------------------- ride

    private static Snapshot rideSnapshot(MinecraftClient client, Journey current, Leg leg,
                                         @Nullable Ride ride, Vec3d player, long now) {
        Segment segment = ride == null ? null : ride.segmentAt(passedIndex);
        long routeId = segment != null ? segment.routeId() : leg.routeId();
        SimplifiedRoute route = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
        int color = route == null ? NavHud.COLOR_FALLBACK_BULLET : 0xFF000000 | route.getColor();
        String routeName = route == null ? "" : firstLang(route.getName());
        String label = routeLabel(routeName);

        boolean aboard = lastSpeed > ABOARD_SPEED || (client.player != null && client.player.hasVehicle());
        String alightName = platformStationName(leg.alightPlatformId());
        int total = ride == null ? leg.stops() : Math.max(0, ride.platformIds().size() - 1);
        int done = ride == null ? 0 : Math.min(passedIndex, total);
        int remaining = Math.max(0, total - done);

        boolean alert = false;
        int alertColor = NavHud.COLOR_TEXT;
        String title;
        String detail;
        Vec3d target;
        String targetLabel;

        if (!aboard && passedIndex == 0) {
            // Still boarding: the target is the boarding platform and the
            // detail line is the live countdown for this route there.
            String destination = segment != null && !segment.destination().isEmpty()
                    ? segment.destination()
                    : firstLang(current.destination());
            title = Text.translatable("gui.station_announcer.nav.board",
                    displayRoute(label, routeName), destination).getString();
            target = platformPosition(leg.boardPlatformId());
            targetLabel = platformStationName(leg.boardPlatformId());
            long departure = departureMillis(leg.boardPlatformId(), routeId, now);
            if (departure > Long.MIN_VALUE) {
                long untilMillis = departure - now;
                if (untilMillis <= 0) {
                    detail = Text.translatable("gui.station_announcer.nav.departing_now").getString();
                    alert = true;
                    alertColor = NavHud.COLOR_RED;
                } else {
                    detail = Text.translatable("gui.station_announcer.nav.departs_in",
                            formatSeconds(untilMillis / 1000)).getString();
                    if (untilMillis <= DEPARTURE_ALERT_MILLIS) {
                        alert = true;
                        alertColor = NavHud.COLOR_AMBER;
                        fireDepartureAlert(client, targetLabel);
                    }
                }
            } else {
                String platformName = platformName(leg.boardPlatformId());
                detail = platformName.isEmpty()
                        ? Text.translatable("gui.station_announcer.nav.distance",
                                Math.round(Math.max(0, distance(target, player)))).getString()
                        : Text.translatable("gui.station_announcer.nav.platform", platformName).getString();
            }
        } else {
            target = platformPosition(leg.alightPlatformId());
            targetLabel = alightName;
            if (remaining <= 1) {
                title = Text.translatable("gui.station_announcer.nav.alight", alightName).getString();
                detail = Text.translatable("gui.station_announcer.nav.next_stop_alight", alightName).getString();
                alert = true;
                alertColor = NavHud.COLOR_RED;
                fireAlightAlert(client, alightName);
            } else if (segment != null && segment.index() > 0) {
                // Through-run: the vehicle carries on as a different route.
                title = Text.translatable("gui.station_announcer.nav.continues_as",
                        displayRoute(label, routeName), segment.destination()).getString();
                detail = stopsDetail(remaining, alightName);
            } else {
                title = Text.translatable("gui.station_announcer.nav.stay_on",
                        displayRoute(label, routeName)).getString();
                detail = stopsDetail(remaining, alightName);
            }
        }

        return new Snapshot(true, color, label, title, detail, total, done, alert, alertColor,
                target, targetLabel, distance(target, player));
    }

    private static String stopsDetail(int remaining, String alightName) {
        return remaining == 1
                ? Text.translatable("gui.station_announcer.nav.one_stop_to", alightName).getString()
                : Text.translatable("gui.station_announcer.nav.stops_to", remaining, alightName).getString();
    }

    private static String displayRoute(String label, String routeName) {
        return routeName.isEmpty() ? label : routeName;
    }

    /**
     * Walks the ride's resolved platform sequence and moves {@link #passedIndex}
     * forward to the nearest platform the player is actually beside. Monotonic:
     * a player who wanders (or a route that doubles back past an earlier stop)
     * never rewinds the instruction.
     */
    private static void advanceRideProgress(Ride ride, Vec3d player) {
        List<Long> ids = ride.platformIds();
        int best = -1;
        double bestSquared = PASSED_RADIUS * PASSED_RADIUS;
        for (int i = passedIndex; i < ids.size(); i++) {
            Vec3d pos = platformPosition(ids.get(i));
            if (pos == null) {
                continue;
            }
            double squared = player.squaredDistanceTo(pos);
            if (squared <= bestSquared) {
                bestSquared = squared;
                best = i;
            }
        }
        if (best > passedIndex) {
            passedIndex = best;
        }
    }

    // ------------------------------------------------------- ride sequence

    /** The resolved ride for this leg, cached until the journey or the leg changes. */
    @Nullable
    private static Ride ride(Journey current, Leg leg) {
        long key = journeyStamp * 1_000L + legIndex;
        if (cachedRide != null && cachedRideKey == key) {
            return cachedRide;
        }
        Ride built = buildRide(leg);
        cachedRide = built;
        cachedRideKey = key;
        return built;
    }

    /**
     * Expands the ride into one platform sequence plus the segment boundaries
     * of any through-run. A segment whose route is not synced yet degrades to
     * its two endpoints, so the HUD keeps working with partial data.
     */
    private static Ride buildRide(Leg leg) {
        List<Via> vias = leg.vias();
        List<Segment> segments = new ArrayList<>(vias.size() + 1);
        List<Long> sequence = new ArrayList<>();

        long startId = leg.boardPlatformId();
        for (int i = 0; i <= vias.size(); i++) {
            long routeId = i == 0 ? leg.routeId() : vias.get(i - 1).routeId();
            long from = i == 0 ? startId : vias.get(i - 1).atPlatformId();
            long to = i < vias.size() ? vias.get(i).atPlatformId() : leg.alightPlatformId();

            List<Long> part = platformsBetween(routeId, from, to);
            int begin = sequence.size();
            for (Long id : part) {
                if (sequence.isEmpty() || !sequence.get(sequence.size() - 1).equals(id)) {
                    if (sequence.size() >= MAX_SEQUENCE) {
                        break;
                    }
                    sequence.add(id);
                }
            }
            int end = Math.max(begin, sequence.size() - 1);
            segments.add(new Segment(i, routeId, destinationOf(routeId, to), begin, end));
        }
        return new Ride(List.copyOf(sequence), List.copyOf(segments));
    }

    /** The route's platforms from {@code from} to {@code to} inclusive, or the two endpoints. */
    private static List<Long> platformsBetween(long routeId, long from, long to) {
        SimplifiedRoute route = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
        if (route != null) {
            int start = route.getPlatformIndex(from);
            int end = route.getPlatformIndex(to);
            ObjectArrayList<SimplifiedRoutePlatform> platforms = route.getPlatforms();
            if (start >= 0 && end > start && end < platforms.size()) {
                List<Long> ids = new ArrayList<>(end - start + 1);
                for (int i = start; i <= end && ids.size() < MAX_SEQUENCE; i++) {
                    ids.add(platforms.get(i).getPlatformId());
                }
                return ids;
            }
        }
        return from == to ? List.of(from) : List.of(from, to);
    }

    /** The destination string a route shows when heading toward this platform. */
    private static String destinationOf(long routeId, long towardPlatformId) {
        SimplifiedRoute route = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
        if (route == null) {
            return platformStationName(towardPlatformId);
        }
        ObjectArrayList<SimplifiedRoutePlatform> platforms = route.getPlatforms();
        if (platforms.isEmpty()) {
            return platformStationName(towardPlatformId);
        }
        String destination = firstLang(platforms.get(platforms.size() - 1).getStationName());
        return destination.isEmpty() ? platformStationName(towardPlatformId) : destination;
    }

    // ----------------------------------------------------------- arrivals

    /**
     * When the next train of this route leaves that platform, on the client
     * clock, or {@link Long#MIN_VALUE} when MTR has nothing published. Guarded
     * by the same fetched-at/TTL pair the driving HUD uses — this is the one
     * expensive MTR call in the whole feature.
     */
    private static long departureMillis(long platformId, long routeId, long now) {
        if (platformId == 0) {
            return Long.MIN_VALUE;
        }
        if (platformId != arrivalsPlatformId || now - arrivalsFetchedAt >= ARRIVALS_TTL_MILLIS) {
            arrivalsPlatformId = platformId;
            arrivalsFetchedAt = now;
            ARRIVALS_REQUEST_IDS.clear();
            ARRIVALS_REQUEST_IDS.add(platformId);
            arrivals = ArrivalsCacheClient.INSTANCE.requestArrivals(ARRIVALS_REQUEST_IDS);
        }
        long offset = ArrivalsCacheClient.INSTANCE.getMillisOffset();
        long best = Long.MIN_VALUE;
        for (ArrivalResponse arrival : arrivals) {
            if (arrival.getRouteId() != routeId || arrival.getPlatformId() != platformId) {
                continue;
            }
            long departure = arrival.getDeparture() - offset;
            if (departure < now - 2000) {
                continue;
            }
            if (best == Long.MIN_VALUE || departure < best) {
                best = departure;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- alerts

    private static void fireAlightAlert(MinecraftClient client, String stationName) {
        if (alightAlertLeg == legIndex) {
            return;
        }
        alightAlertLeg = legIndex;
        alert(client, Text.translatable("msg.station_announcer.nav.alert_alight"),
                Text.literal(stationName));
    }

    private static void fireDepartureAlert(MinecraftClient client, String stationName) {
        if (departureAlertLeg == legIndex) {
            return;
        }
        departureAlertLeg = legIndex;
        alert(client, Text.translatable("msg.station_announcer.nav.alert_departure"),
                Text.literal(stationName));
    }

    /** Title + subtitle + one chime, each firing once per occurrence. */
    private static void alert(MinecraftClient client, Text title, Text subtitle) {
        AddonClientConfig config = AddonClientConfig.get();
        if (config.navAlertsEnabled && client.inGameHud != null) {
            client.inGameHud.setTitleTicks(5, 45, 10);
            client.inGameHud.setSubtitle(subtitle);
            client.inGameHud.setTitle(title);
        }
        if (config.navSoundEnabled) {
            // Reuses the base mod's PA chime rather than shipping a new asset.
            client.getSoundManager().play(PositionedSoundInstance.master(ModContent.CHIME, 1.2f));
        }
    }

    // --------------------------------------------------------------- chat

    /** Prints the whole itinerary once, capped at {@value #MAX_CHAT_LINES} lines. */
    private static void printItinerary(Journey current) {
        List<Leg> legs = current.legs();
        chat(Text.translatable("msg.station_announcer.nav.header", firstLang(current.destination()))
                .formatted(Formatting.GOLD, Formatting.BOLD));

        // Header + footer(s) take their share of the budget; the rest is legs.
        int footerLines = current.plannedArriveMs() > 0 ? 1 : 0;
        int budget = MAX_CHAT_LINES - 1 - footerLines;
        boolean truncated = legs.size() > budget;
        int shown = truncated ? Math.max(1, budget - 1) : legs.size();

        for (int i = 0; i < shown; i++) {
            chat(legLine(legs.get(i)));
        }
        if (truncated) {
            chat(Text.translatable("msg.station_announcer.nav.more", legs.size() - shown)
                    .formatted(Formatting.DARK_GRAY));
        }
        if (current.plannedArriveMs() > 0) {
            chat(Text.translatable("msg.station_announcer.nav.arrive_at",
                    formatClock(current.plannedArriveMs())).formatted(Formatting.GRAY));
        }
    }

    private static Text legLine(Leg leg) {
        if (leg.type() == LEG_WALK) {
            String where = endpointName(leg.endpointTo());
            MutableText line = where.isEmpty()
                    ? Text.translatable("msg.station_announcer.nav.leg_walk_plain", leg.metres())
                    : Text.translatable("msg.station_announcer.nav.leg_walk", leg.metres(), where);
            return bullet(0, "").append(line.formatted(Formatting.WHITE));
        }
        if (leg.type() == LEG_TRANSFER) {
            return bullet(0, "").append(Text.translatable("msg.station_announcer.nav.leg_transfer",
                    platformStationName(leg.alightPlatformId()), leg.metres()).formatted(Formatting.WHITE));
        }
        SimplifiedRoute route = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(leg.routeId());
        int color = route == null ? NavHud.COLOR_FALLBACK_BULLET : 0xFF000000 | route.getColor();
        String routeName = route == null ? "" : firstLang(route.getName());
        String label = displayRoute(routeLabel(routeName), routeName);
        MutableText line = Text.translatable(
                leg.stops() == 1
                        ? "msg.station_announcer.nav.leg_ride_one"
                        : "msg.station_announcer.nav.leg_ride",
                label,
                platformStationName(leg.boardPlatformId()),
                platformStationName(leg.alightPlatformId()),
                leg.stops()).formatted(Formatting.WHITE);
        MutableText out = bullet(color, routeLabel(routeName)).append(line);
        for (Via via : leg.vias()) {
            SimplifiedRoute viaRoute = MinecraftClientData.getInstance()
                    .simplifiedRouteIdMap.get(via.routeId());
            String viaName = viaRoute == null ? "" : firstLang(viaRoute.getName());
            out.append(Text.literal("\n")).append(
                    Text.translatable("msg.station_announcer.nav.leg_via",
                                    displayRoute(routeLabel(viaName), viaName),
                                    platformStationName(via.atPlatformId()))
                            .formatted(Formatting.GRAY));
        }
        return out;
    }

    /** A coloured square plus the route letter, or a plain dash for a walking step. */
    private static MutableText bullet(int color, String label) {
        if (color == 0) {
            return Text.literal("• ").formatted(Formatting.DARK_GRAY);
        }
        MutableText text = Text.literal("■" + (label.isEmpty() ? "" : " " + label) + " ");
        int rgb = color & 0xFFFFFF;
        return text.styled(style -> style.withColor(TextColor.fromRgb(rgb)));
    }

    private static void chat(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }

    // ------------------------------------------------------------ resolving

    @Nullable
    private static Vec3d resolve(Endpoint endpoint) {
        if (endpoint == null) {
            return null;
        }
        return endpoint.isPlatform()
                ? platformPosition(endpoint.platformId())
                : new Vec3d(endpoint.x(), endpoint.y(), endpoint.z());
    }

    /** The middle of a platform, in world coordinates, or null when it is not synced. */
    @Nullable
    private static Vec3d platformPosition(long platformId) {
        if (platformId == 0) {
            return null;
        }
        Platform platform = MinecraftClientData.getInstance().platformIdMap.get(platformId);
        if (platform == null) {
            return null;
        }
        Position position = platform.getMidPosition();
        return new Vec3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
    }

    /** The station a platform belongs to, falling back to the platform's own name. */
    private static String platformStationName(long platformId) {
        Platform platform = MinecraftClientData.getInstance().platformIdMap.get(platformId);
        if (platform == null) {
            return Text.translatable("gui.station_announcer.nav.unknown").getString();
        }
        Station station = platform.area;
        if (station != null && !station.getName().isEmpty()) {
            return firstLang(station.getName());
        }
        String name = firstLang(platform.getName());
        return name.isEmpty() ? Text.translatable("gui.station_announcer.nav.unknown").getString() : name;
    }

    private static String platformName(long platformId) {
        Platform platform = MinecraftClientData.getInstance().platformIdMap.get(platformId);
        return platform == null ? "" : firstLang(platform.getName());
    }

    private static String endpointName(Endpoint endpoint) {
        return endpoint != null && endpoint.isPlatform() ? platformStationName(endpoint.platformId()) : "";
    }

    private static double distance(@Nullable Vec3d target, Vec3d player) {
        return target == null ? -1 : target.distanceTo(player);
    }

    // ---------------------------------------------------------- formatting

    /** MTR names are "English|Other Language" — display the first part. */
    static String firstLang(String raw) {
        if (raw == null) {
            return "";
        }
        int split = raw.indexOf('|');
        return (split >= 0 ? raw.substring(0, split) : raw).trim();
    }

    /**
     * The short bullet label for a route: its first digit run, else its first
     * letter. Same heuristic the NYC PIDS uses — the synced client route data
     * carries no route number field.
     */
    static String routeLabel(String routeName) {
        String name = firstLang(routeName);
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                int end = i;
                while (end < name.length() && Character.isDigit(name.charAt(end))) {
                    end++;
                }
                return name.substring(i, Math.min(end, i + 2));
            }
        }
        return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    static String formatSeconds(long seconds) {
        if (seconds < 60) {
            return Text.translatable("gui.station_announcer.nav.seconds", seconds).getString();
        }
        return Text.translatable("gui.station_announcer.nav.minutes", seconds / 60).getString();
    }

    private static String formatClock(long epochMillis) {
        java.time.LocalTime time = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalTime();
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    // ----------------------------------------------------------------- types

    /**
     * What the HUD, the waypoint and the chat pieces all read: one fully
     * resolved instruction, rebuilt at 4 Hz and then only drawn.
     *
     * @param bulletColor 0 = no route bullet
     * @param stopsTotal  0 = no progress pips
     * @param target      null when the platform is not synced yet
     * @param distance    blocks to {@code target}, negative when unknown
     */
    public record Snapshot(boolean active, int bulletColor, String bulletLabel, String title,
                           String detail, int stopsTotal, int stopsDone, boolean alert, int alertColor,
                           @Nullable Vec3d target, String targetLabel, double distance) {
        static Snapshot arrivedAt(String destination) {
            return new Snapshot(true, 0, "",
                    Text.translatable("gui.station_announcer.nav.arrived").getString(),
                    destination, 0, 0, false, NavHud.COLOR_GREEN, null, destination, -1);
        }
    }

    /** One endpoint of a walking leg: either world coordinates or a platform. */
    private record Endpoint(boolean isPlatform, long platformId, double x, double y, double z) {
        static Endpoint coords(double x, double y, double z) {
            return new Endpoint(false, 0, x, y, z);
        }

        static Endpoint platform(long platformId) {
            return new Endpoint(true, platformId, 0, 0, 0);
        }
    }

    /** A through-run handover: the vehicle continues as {@code routeId} at {@code atPlatformId}. */
    private record Via(long routeId, long atPlatformId) {
    }

    /** One itinerary step. Unused fields are zero for the type in question. */
    private record Leg(int type, @Nullable Endpoint endpointFrom, @Nullable Endpoint endpointTo,
                       int metres, long routeId, long boardPlatformId, long alightPlatformId,
                       int stops, List<Via> vias) {
        static Leg walk(Endpoint from, Endpoint to, int metres) {
            return new Leg(LEG_WALK, from, to, metres, 0, 0, 0, 0, List.of());
        }

        static Leg ride(long routeId, long board, long alight, int stops, List<Via> vias) {
            return new Leg(LEG_RIDE, Endpoint.platform(board), Endpoint.platform(alight),
                    0, routeId, board, alight, stops, vias);
        }

        static Leg transfer(long from, long to, int metres) {
            return new Leg(LEG_TRANSFER, Endpoint.platform(from), Endpoint.platform(to),
                    metres, 0, from, to, 0, List.of());
        }
    }

    private record Journey(String destination, List<Leg> legs, long plannedArriveMs) {
    }

    /** One stretch of a ride under a single route; {@code index} 0 is the boarded route. */
    private record Segment(int index, long routeId, String destination, int firstStop, int lastStop) {
    }

    /** A ride leg expanded into its platform sequence plus the through-run segments. */
    private record Ride(List<Long> platformIds, List<Segment> segments) {
        @Nullable
        Segment segmentAt(int stopIndex) {
            Segment found = null;
            for (Segment segment : segments) {
                if (stopIndex >= segment.firstStop()) {
                    found = segment;
                }
            }
            return found == null && !segments.isEmpty() ? segments.get(0) : found;
        }
    }
}
