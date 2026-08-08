package com.stationannouncer.mtraddon.disruption;

import com.stationannouncer.AnnouncerRegistry;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.AddonStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature 6b — turns active {@link Disruption}s into PA announcements and PIDS
 * banners at the stations the affected lines actually call at.
 *
 * <p><b>Everything is event-driven and throttled</b>, per ARCHITECTURE §6:</p>
 * <ul>
 *   <li>{@link #tick(MinecraftServer)} runs on the addon's EXISTING once-a-second
 *       server ticker (no new loop). With the feature off, or with no stored
 *       disruptions, it returns after one or two field reads.</li>
 *   <li>The <em>affected-station scan</em> — which stations does an affected line
 *       serve, and what are their bounds — runs on the SIMULATOR threads via
 *       {@code Simulator.run(...)}, and only when the disruption set changed or
 *       {@code stationRescanSeconds} has elapsed. The result is a list of plain
 *       records (no MTR references) handed back to the server thread.</li>
 *   <li>The PA sweep only walks {@link AnnouncerRegistry} on ticks where at least
 *       one station is due to announce or the display banner needs refreshing.</li>
 * </ul>
 *
 * <p><b>Cadence and multiple disruptions at one station</b> (the documented
 * rules): each affected station announces one disruption every
 * {@code announceIntervalMinutes}, <em>cycling</em> through the disruptions that
 * apply to it in severity order (SEVERE → INFO, ties broken by creation id) — one
 * per cadence, so a station with three alerts speaks all three over three cycles.
 * Linked displays always show the <em>highest-severity</em> one, re-pushed every
 * {@code displayRefreshSeconds} because the PIDS live banner is time-limited on
 * the renderer side. The stations' normal PA message pools keep running
 * untouched in between: announcements go through
 * {@link AbstractPaBlockEntity#announceExternal(String)}, which never reads or
 * writes the block's own pool, index or settings.</p>
 *
 * <p><b>Revert:</b> when a disruption expires, is toggled off, is deleted, or the
 * feature is disabled, every PA source we pushed to gets
 * {@code showExternalDisplayMessage("")}, which clears the banner so the screens
 * fall straight back to their usual content. The same happens for an individual
 * block that is no longer inside an affected station.</p>
 *
 * <p><b>Thread:</b> all of this class runs on the SERVER thread except the scan
 * body, which runs on the target dimension's simulator thread and only reads that
 * simulator's own data before hopping back with {@code server.execute}.</p>
 */
public final class DisruptionBroadcaster {
    /** One station whose platforms are served by at least one affected line. */
    public record StationScope(String dimension, long stationId, String stationName,
                               long minX, long maxX, long minZ, long maxZ, long[] routeIds) {
        boolean contains(String worldId, BlockPos pos) {
            return dimension.equals(worldId)
                    && pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        boolean serves(long routeId) {
            for (long id : routeIds) {
                if (id == routeId) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A PA block we have pushed a disruption message to (so we can revert it). */
    private record TargetKey(String dimension, long packedPos) {
    }

    /** Per-dimension scan results, merged into {@link #scopes} on the server thread. */
    private static final Map<String, List<StationScope>> scopesByDimension = new LinkedHashMap<>();
    private static List<StationScope> scopes = List.of();

    private static final Map<Long, Long> nextAnnounceAt = new HashMap<>();
    private static final Map<Long, Integer> cycleIndex = new HashMap<>();
    private static final Set<TargetKey> announcedTo = new HashSet<>();
    /** MTR's dimension string per world, computed once per world instance. */
    private static final Map<ServerWorld, String> WORLD_IDS = new IdentityHashMap<>();

    private static int lastVersion = Integer.MIN_VALUE;
    private static long lastScanMillis;
    private static long nextDisplayRefreshAt;

    private DisruptionBroadcaster() {
    }

    /** SERVER_STOPPED. */
    public static void clearRuntimeState() {
        scopesByDimension.clear();
        scopes = List.of();
        nextAnnounceAt.clear();
        cycleIndex.clear();
        announcedTo.clear();
        WORLD_IDS.clear();
        lastVersion = Integer.MIN_VALUE;
        lastScanMillis = 0;
        nextDisplayRefreshAt = 0;
    }

    // ------------------------------------------------------------------ tick

    /** Server thread, once a second, from {@code AddonInit}'s existing ticker. */
    public static void tick(MinecraftServer server) {
        AddonServerConfig.Disruptions config = AddonServerConfig.get().disruptions;
        if (!config.enabled) {
            revertAll(server);
            return;
        }
        if (AddonStore.disruptionCount() == 0) {
            revertAll(server);
            if (!scopes.isEmpty()) {
                scopesByDimension.clear();
                scopes = List.of();
            }
            lastVersion = AddonStore.disruptionVersion();
            return;
        }

        long now = System.currentTimeMillis();
        List<Disruption> active = AddonStore.activeDisruptions(now); // already severity-sorted
        if (active.isEmpty()) {
            revertAll(server);
            return;
        }

        int version = AddonStore.disruptionVersion();
        if (version != lastVersion || now - lastScanMillis >= config.stationRescanSeconds * 1000L) {
            lastVersion = version;
            lastScanMillis = now;
            requestScan(server, active);
        }

        List<StationScope> current = scopes;
        if (current.isEmpty()) {
            return; // scan still running, or no affected station in the world
        }

        boolean displayDue = now >= nextDisplayRefreshAt;
        Map<Long, Disruption> toSpeak = new HashMap<>();
        Map<Long, Disruption> toShow = new HashMap<>();
        for (StationScope scope : current) {
            List<Disruption> forStation = disruptionsFor(active, scope);
            if (forStation.isEmpty()) {
                continue;
            }
            toShow.put(scope.stationId(), forStation.get(0)); // highest severity first
            long due = nextAnnounceAt.getOrDefault(scope.stationId(), 0L);
            if (now >= due) {
                int index = cycleIndex.merge(scope.stationId(), 1, Integer::sum) - 1;
                toSpeak.put(scope.stationId(), forStation.get(Math.floorMod(index, forStation.size())));
                nextAnnounceAt.put(scope.stationId(), now + config.announceIntervalMinutes * 60_000L);
            }
        }

        if (toSpeak.isEmpty() && !displayDue) {
            return; // nothing due — no registry walk at all
        }
        sweep(server, config, current, toSpeak, toShow, displayDue);
        if (displayDue) {
            nextDisplayRefreshAt = now + config.displayRefreshSeconds * 1000L;
        }
    }

    /** One pass over the loaded PA sources, pushing speech and/or banners. */
    private static void sweep(MinecraftServer server, AddonServerConfig.Disruptions config,
                              List<StationScope> current, Map<Long, Disruption> toSpeak,
                              Map<Long, Disruption> toShow, boolean displayDue) {
        AnnouncerRegistry.forEachLoaded(server, blockEntity -> {
            if (!(blockEntity instanceof ControlBoxBlockEntity) && !config.includeStandaloneAnnouncers) {
                return;
            }
            if (!(blockEntity.getWorld() instanceof ServerWorld world)) {
                return;
            }
            String worldId = worldId(world);
            if (worldId == null) {
                return;
            }
            BlockPos pos = blockEntity.getPos();
            StationScope scope = null;
            for (StationScope candidate : current) {
                if (candidate.contains(worldId, pos)) {
                    scope = candidate;
                    break;
                }
            }
            TargetKey key = new TargetKey(worldId, pos.asLong());
            if (scope == null) {
                // Out of every affected station now — put its displays back.
                if (announcedTo.remove(key)) {
                    blockEntity.showExternalDisplayMessage("");
                }
                return;
            }
            Disruption speak = toSpeak.get(scope.stationId());
            if (speak != null) {
                // Chime + TTS + chat exactly as this block already sounds, and the
                // same call pushes the text to its linked displays.
                blockEntity.announceExternal(speak.message());
                announcedTo.add(key);
                return;
            }
            if (displayDue) {
                Disruption show = toShow.get(scope.stationId());
                if (show != null) {
                    blockEntity.showExternalDisplayMessage(show.message());
                    announcedTo.add(key);
                }
            }
        });
    }

    /** Clears every banner we pushed, and forgets the cadence state. */
    private static void revertAll(MinecraftServer server) {
        if (announcedTo.isEmpty()) {
            nextAnnounceAt.clear();
            cycleIndex.clear();
            return;
        }
        AnnouncerRegistry.forEachLoaded(server, blockEntity -> {
            if (!(blockEntity.getWorld() instanceof ServerWorld world)) {
                return;
            }
            String worldId = worldId(world);
            if (worldId != null && announcedTo.contains(new TargetKey(worldId, blockEntity.getPos().asLong()))) {
                blockEntity.showExternalDisplayMessage("");
            }
        });
        announcedTo.clear();
        nextAnnounceAt.clear();
        cycleIndex.clear();
        nextDisplayRefreshAt = 0;
    }

    private static List<Disruption> disruptionsFor(List<Disruption> active, StationScope scope) {
        List<Disruption> result = new ArrayList<>();
        for (Disruption disruption : active) {
            for (long routeId : disruption.routeIds()) {
                if (scope.serves(routeId)) {
                    result.add(disruption);
                    break;
                }
            }
        }
        return result;
    }

    private static String worldId(ServerWorld world) {
        String cached = WORLD_IDS.get(world);
        if (cached != null) {
            return cached;
        }
        try {
            String id = org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
            WORLD_IDS.put(world, id);
            return id;
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Could not resolve the MTR dimension id of a world; disruption broadcasting skipped there", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ scan

    /**
     * Asks every simulator, on its own thread, which of its stations are served by
     * an affected line. Cheap enough to run at most once per
     * {@code stationRescanSeconds} (or immediately after an edit): stations ×
     * platforms × routes, all in-memory sets.
     */
    private static void requestScan(MinecraftServer server, List<Disruption> active) {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            return;
        }
        LongOpenHashSet routeIds = new LongOpenHashSet();
        for (Disruption disruption : active) {
            for (long routeId : disruption.routeIds()) {
                routeIds.add(routeId);
            }
        }
        if (routeIds.isEmpty()) {
            return;
        }
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                List<StationScope> found = new ArrayList<>();
                try {
                    for (Station station : simulator.stations) {
                        LongOpenHashSet served = new LongOpenHashSet();
                        for (Platform platform : station.savedRails) {
                            for (Route route : platform.routes) {
                                if (route != null && routeIds.contains(route.getId())) {
                                    served.add(route.getId());
                                }
                            }
                        }
                        if (!served.isEmpty()) {
                            found.add(new StationScope(simulator.dimension, station.getId(), station.getName(),
                                    station.getMinX(), station.getMaxX(), station.getMinZ(), station.getMaxZ(),
                                    served.toLongArray()));
                        }
                    }
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Disruption station scan failed for dimension {}", simulator.dimension, t);
                    return;
                }
                // Plain records only — nothing here references MTR objects.
                server.execute(() -> mergeScopes(simulator.dimension, found));
            });
        }
    }

    /** Server thread (via {@code server.execute}): fold one dimension's result in. */
    private static void mergeScopes(String dimension, List<StationScope> found) {
        scopesByDimension.put(dimension, found);
        List<StationScope> merged = new ArrayList<>();
        scopesByDimension.values().forEach(merged::addAll);
        scopes = List.copyOf(merged);
        // Stations that dropped out of the affected set lose their cadence timers.
        Set<Long> live = new HashSet<>();
        for (StationScope scope : merged) {
            live.add(scope.stationId());
        }
        nextAnnounceAt.keySet().retainAll(live);
        cycleIndex.keySet().retainAll(live);
    }

    /** Read by the dispatch/debug side: how many stations are currently covered. */
    public static int affectedStationCount() {
        return scopes.size();
    }
}
