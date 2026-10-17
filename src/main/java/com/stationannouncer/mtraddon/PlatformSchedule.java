package com.stationannouncer.mtraddon;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.SidingSlotAccessor;
import com.stationannouncer.mixin.SidingStopTimesAccessor;
import com.stationannouncer.mtraddon.analytics.AnalyticsAggregator;
import com.stationannouncer.mtraddon.interline.InterlineService;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Trip;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform screen's schedule data: every train that is timetabled to stop at one
 * platform, as each siding's real departures plus that siding's baked stop times at the
 * platform — exactly the numbers MTR's PIDS arrivals are made of, so the client can draw
 * the platform's occupancy and re-time it live while dwell sliders move (a dwell change
 * moves this stop's departure and every later stop of the same train's cycle, never its
 * arrival).
 *
 * <p>Also owns the "regenerate after a platform save" step: dwell (platform default and
 * per-route overrides) and platform groups are baked at depot generation, so a save that
 * changed them regenerates every depot whose routes call here, 40 ticks later so MTR's own
 * platform update (sent by the client beside ours) has landed first.</p>
 *
 * <p>Reply layout ({@code addon_platform_schedule}): platform id, current simulation
 * millis, game millis per day, a note, then per siding: depot id, depot name, repeat
 * interval, departure phases (sorted, in {@code [0, repeat)}, delta-coded varlongs) and the
 * stop times at this platform (route id, trip stop index, start, end — millis after the
 * siding's departure).</p>
 */
public final class PlatformSchedule {
    public static final Identifier REQUEST_C2S = StationAnnouncer.id("addon_platform_schedule_request");
    public static final Identifier REPLY_S2C = StationAnnouncer.id("addon_platform_schedule");

    public static final int MAX_SIDINGS = 256;
    public static final int MAX_DEPARTURES = 2_048;
    public static final int MAX_STOPS = 64;
    public static final int MAX_NOTE = 256;
    /** Platforms per request (a route screen asks for every stop at once). */
    public static final int MAX_REQUEST = 64;

    private static final long REQUEST_COOLDOWN_MILLIS = 400;
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private PlatformSchedule() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_C2S, (server, player, handler, buf, sender) -> {
            int count = buf.readVarInt();
            if (count < 1 || count > MAX_REQUEST) {
                return;
            }
            long[] platformIds = new long[count];
            for (int i = 0; i < count; i++) {
                platformIds[i] = buf.readLong();
            }
            server.execute(() -> request(server, player, platformIds));
        });
    }

    private static void request(MinecraftServer server, ServerPlayerEntity player, long[] platformIds) {
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(player.getUuid());
        if (last != null && now - last < REQUEST_COOLDOWN_MILLIS) {
            return;
        }
        LAST_REQUEST.put(player.getUuid(), now);
        Simulator simulator = InterlineService.simulatorFor(player.getServerWorld());
        if (simulator == null) {
            return;
        }
        simulator.run(() -> {
            List<PacketByteBuf> replies = new ArrayList<>();
            for (long platformId : platformIds) {
                try {
                    replies.add(build(simulator, platformId));
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Could not build the platform schedule", t);
                }
            }
            server.execute(() -> {
                if (!player.isDisconnected()) {
                    replies.forEach(buf -> ServerPlayNetworking.send(player, REPLY_S2C, buf));
                }
            });
        });
    }

    /** Simulator thread. */
    private static PacketByteBuf build(Simulator simulator, long platformId) {
        long day = Math.max(1, simulator.getGameMillisPerDay());
        List<String> skipped = new ArrayList<>();
        List<Object[]> sidings = new ArrayList<>(); // depot, siding, phases, stops
        for (Depot depot : simulator.depots) {
            if (depot == null) {
                continue;
            }
            boolean servesHere = false;
            for (Route route : depot.routes) {
                if (route != null && callsAt(route, platformId)) {
                    servesHere = true;
                    break;
                }
            }
            if (!servesHere) {
                continue;
            }
            if (depot.getUseRealTime() || depot.getTransportMode().continuousMovement) {
                skipped.add(AnalyticsAggregator.displayName(depot.getName()));
                continue;
            }
            for (Siding siding : depot.savedRails) {
                if (siding == null || sidings.size() >= MAX_SIDINGS) {
                    continue;
                }
                ObjectArraySet<Trip.StopTime> atPlatform = ((SidingStopTimesAccessor) (Object) siding)
                        .stationAnnouncer$getPlatformTripStopTimes().get(platformId);
                if (atPlatform == null || atPlatform.isEmpty()) {
                    continue;
                }
                SidingSlotAccessor access = (SidingSlotAccessor) (Object) siding;
                LongArrayList departures = access.stationAnnouncer$getDepartures();
                long repeat = access.stationAnnouncer$getRepeatInterval(day);
                if (departures.isEmpty() || repeat <= 0) {
                    continue;
                }
                long[] phases = new long[Math.min(MAX_DEPARTURES, departures.size())];
                for (int i = 0; i < phases.length; i++) {
                    phases[i] = Math.floorMod(departures.getLong(i), repeat);
                }
                Arrays.sort(phases);
                List<long[]> stops = new ArrayList<>();
                for (Trip.StopTime stopTime : atPlatform) {
                    if (stopTime.trip == null || stopTime.trip.route == null || stops.size() >= MAX_STOPS) {
                        continue;
                    }
                    stops.add(new long[]{stopTime.trip.route.getId(), stopTime.tripStopIndex,
                            stopTime.startTime, stopTime.endTime});
                }
                stops.sort((a, b) -> Long.compare(a[2], b[2]));
                sidings.add(new Object[]{depot, siding, repeat, phases, stops});
            }
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(platformId);
        buf.writeLong(simulator.getCurrentMillis());
        buf.writeVarLong(day);
        String note = skipped.isEmpty() ? "" : "Not timetabled (real-time or continuous): " + String.join(", ", skipped);
        buf.writeString(note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE - 1) + "…" : note, MAX_NOTE);
        buf.writeVarInt(sidings.size());
        for (Object[] entry : sidings) {
            Depot depot = (Depot) entry[0];
            Siding siding = (Siding) entry[1];
            long repeat = (long) entry[2];
            long[] phases = (long[]) entry[3];
            @SuppressWarnings("unchecked")
            List<long[]> stops = (List<long[]>) entry[4];
            buf.writeLong(depot.getId());
            buf.writeLong(siding.getId());
            String name = AnalyticsAggregator.displayName(depot.getName());
            buf.writeString(name.length() > 64 ? name.substring(0, 64) : name, 64);
            buf.writeVarLong(repeat);
            buf.writeVarInt(phases.length);
            long previous = 0;
            for (long phase : phases) {
                buf.writeVarLong(phase - previous);
                previous = phase;
            }
            buf.writeVarInt(stops.size());
            for (long[] stop : stops) {
                buf.writeLong(stop[0]);
                buf.writeVarInt((int) stop[1]);
                buf.writeVarLong(Math.max(0, stop[2]));
                buf.writeVarLong(Math.max(0, stop[3]));
            }
        }
        return buf;
    }

    private static boolean callsAt(Route route, long platformId) {
        for (RoutePlatformData data : route.getRoutePlatforms()) {
            if (data.platform != null && data.platform.getId() == platformId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Server thread: regenerate every depot running a route that calls at the platform,
     * {@code 40} ticks from now (dwell and platform groups are baked into the path).
     */
    public static void regenerateFor(MinecraftServer server, ServerWorld world, long platformId) {
        InterlineService.schedule(40, () -> {
            Simulator simulator = InterlineService.simulatorFor(world);
            if (simulator == null) {
                return;
            }
            simulator.run(() -> {
                ObjectArrayList<Depot> regenerate = new ObjectArrayList<>();
                for (Depot depot : simulator.depots) {
                    if (depot != null && depot.routes.stream().anyMatch(route -> route != null && callsAt(route, platformId))) {
                        regenerate.add(depot);
                    }
                }
                if (regenerate.isEmpty()) {
                    return;
                }
                try {
                    Depot.generateDepots(simulator, regenerate);
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Could not regenerate depots after a platform save", t);
                }
            });
        });
    }
}
