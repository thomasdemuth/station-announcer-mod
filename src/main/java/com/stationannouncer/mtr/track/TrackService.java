package com.stationannouncer.mtr.track;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.TrackSidingPathsAccessor;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import com.stationannouncer.track.TrackPath;
import com.stationannouncer.track.TrackProfile;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of custom track geometry: the save/reset packets, their validation, sync to clients,
 * re-pathing after a length change, and orphan pruning. Call {@link #register()} once from the
 * common init (MTR present).
 *
 * <h2>Packets</h2>
 * <ul>
 *   <li>{@code station_announcer:track_save} C2S — one profile as JSON (≤ {@link #MAX_SAVE_JSON}
 *       chars: vanilla caps a C2S custom payload at 32767 bytes; a maximal profile is ~10 KB).</li>
 *   <li>{@code station_announcer:track_reset} C2S — a rail key: back to MTR's own shape.</li>
 *   <li>{@code station_announcer:track_sync} S2C — {@code byte op} (0 SET = replace the client's
 *       map, 1 PUT, 2 REMOVE), {@code varint n}, then n × (key, JSON) — REMOVE carries keys only.
 *       Full sync of the player's dimension on join / respawn / dimension change (one SET then PUT
 *       batches, each packet under ~900 KB); deltas to the players of that dimension on change.</li>
 * </ul>
 *
 * <h2>Validation (save and reset)</h2>
 * Same gate as MTR's own rail edits — MTR's client only lets creative/survival players send
 * {@code PacketUpdateData} ({@code MinecraftClientData.hasPermission}; the server does not
 * re-check) — plus what a block edit needs: not spectator/adventure, within {@link #EDIT_REACH}
 * blocks of node A or B, {@code canPlayerModifyAt} that node (spawn protection, claims). The
 * profile is sanitized, the rail must EXIST in the dimension's simulator with the profile's exact
 * nodes and MTR angles (not CABLE), and the path rebuilt server-side from the rail's own shape /
 * vertical radius must {@code closes()}. A 250 ms per-player-per-rail cooldown keeps a stuck key from
 * re-pathing depots in a loop.
 *
 * <h2>Applying a change</h2>
 * Nothing is rebuilt: every {@code RailMath} re-resolves lazily when the registry version moves
 * (positions, length, rendering, bounds — see {@link TrackRailMathState}). What MTR CACHES are path
 * distances: {@code PathData.start/endDistance} are cumulative rail lengths baked at depot
 * generation ({@code SidingPathFinder.generatePathDataDistances}), and a vehicle evaluates
 * {@code rail.railMath.getPosition(distance − startDistance)}. So when the rail's LENGTH changed
 * (tilt-only or same-length edits do not), every depot whose main route ({@code Depot.getPath()})
 * or whose sidings' legs cross the rail is regenerated with MTR's own
 * {@code Depot.generateDepots(simulator, depots)} — exactly what the dashboard's generate button
 * runs ({@code DepotOperationByIds.generate}). That clears those sidings' vehicles and redeploys
 * them, as any MTR regeneration does. Debounced {@link #REGEN_DELAY_TICKS} after the last change.
 * (MTR itself never re-paths after a rail edit — {@code UpdateDataRequest.update} only re-links
 * path caches in {@code Data.sync} — which is why its own shape changes leave trains jumping
 * until the next generation.)
 *
 * <h2>Threads</h2>
 * Packet bodies are read on the netty thread and handled on the server thread; anything that reads
 * simulator data runs inside {@code simulator.run(...)} on its thread and hands plain values back
 * through {@code server.execute}. {@link TrackStore} and the registry's server side change on the
 * server thread only. {@code MtrSimulators} is null until MTR builds its simulators (after our
 * SERVER_STARTED): every simulator-dependent step simply waits / retries via the ticker.
 */
public final class TrackService {
    public static final Identifier SAVE_C2S = StationAnnouncer.id("track_save");
    public static final Identifier RESET_C2S = StationAnnouncer.id("track_reset");
    public static final Identifier SYNC_S2C = StationAnnouncer.id("track_sync");

    public static final byte OP_SET = 0;
    public static final byte OP_PUT = 1;
    public static final byte OP_REMOVE = 2;

    public static final int MAX_SAVE_JSON = 30_000;
    public static final int MAX_SYNC_JSON = 65_536;
    public static final int MAX_KEY = 160;
    public static final double EDIT_REACH = 64;
    private static final int SYNC_BATCH_CHARS = 900_000;
    private static final long EDIT_COOLDOWN_MILLIS = 250;
    private static final int PRUNE_INTERVAL_TICKS = 200;
    private static final int REGEN_DELAY_TICKS = 40;
    /** A length change smaller than this keeps every cached path distance valid. */
    private static final double LENGTH_EPSILON = 1e-4;

    /** player → rail key → last accepted edit (per RAIL: one Save click sends one packet per edited track at the node). */
    private static final Map<UUID, Map<String, Long>> LAST_EDIT = new HashMap<>();
    /** dimension → depot ids waiting to be regenerated. Server thread. */
    private static final Map<String, Set<Long>> PENDING_REGEN = new HashMap<>();
    private static int regenCountdown;
    private static int pruneCountdown = PRUNE_INTERVAL_TICKS;
    private static boolean pruneInFlight;

    private TrackService() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(SAVE_C2S, (server, player, handler, buf, responseSender) -> {
            final String json = buf.readString(MAX_SAVE_JSON);
            server.execute(() -> handleSave(server, player, json));
        });
        ServerPlayNetworking.registerGlobalReceiver(RESET_C2S, (server, player, handler, buf, responseSender) -> {
            final String key = buf.readString(MAX_KEY);
            server.execute(() -> handleReset(server, player, key));
        });

        // Before MTR's own SERVER_STARTED builds the simulators: rails resolve while loading.
        ServerLifecycleEvents.SERVER_STARTING.register(TrackStore::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TrackStore.unload();
            LAST_EDIT.clear();
            PENDING_REGEN.clear();
            regenCountdown = 0;
            pruneCountdown = PRUNE_INTERVAL_TICKS;
            pruneInFlight = false;
        });
        ServerTickEvents.END_SERVER_TICK.register(TrackService::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_EDIT.remove(handler.player.getUuid()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> syncTo(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> syncTo(player));
    }

    // ------------------------------------------------------------------ save / reset

    private static void handleSave(MinecraftServer server, ServerPlayerEntity player, String json) {
        if (!mayEdit(player)) {
            return;
        }
        final TrackProfile profile = TrackJson.parse(json);
        if (profile == null) {
            reject(player, "invalid", "the shape data is invalid");
            return;
        }
        if (coolingDown(player, profile.key())) {
            return;
        }
        final ServerWorld world = player.getServerWorld();
        if (!nearNode(player, world, profile.ax, profile.ay, profile.az, profile.bx, profile.by, profile.bz)) {
            return;
        }
        final Simulator simulator = simulatorFor(world);
        if (simulator == null) {
            reject(player, "no_simulator", "the railway simulation is not running");
            return;
        }
        final String dimension = worldId(world);
        final UUID playerId = player.getUuid();
        simulator.run(() -> {
            String error = null;
            double oldLength = 0;
            try {
                final Rail rail = TrackRails.find(simulator, profile);
                if (rail == null) {
                    error = "no_rail";
                } else {
                    final TrackRailMathAccess access = TrackRails.access(rail);
                    final long[] n = access.stationannouncer$nodes();
                    final double[] angles = access.stationannouncer$angles();
                    if ("CABLE".equals(access.stationannouncer$shapeName())) {
                        error = "cable";
                    } else if (!profile.matches(n[0], n[1], n[2], angles[0], n[3], n[4], n[5], angles[1])) {
                        error = "changed";
                    } else if (!TrackPath.build(profile, access.stationannouncer$shapeName(), access.stationannouncer$verticalRadiusArg()).closes()) {
                        error = "open";
                    } else {
                        oldLength = rail.railMath.getLength();
                    }
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Track save: validation failed", t);
                error = "invalid";
            }
            final String finalError = error;
            final double finalOldLength = oldLength;
            server.execute(() -> {
                final ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerId);
                if (finalError != null) {
                    if (online != null) {
                        reject(online, finalError, fallbackReason(finalError));
                    }
                    return;
                }
                TrackStore.put(dimension, profile);
                broadcast(server, dimension, OP_PUT, Map.of(profile.key(), profile), List.of());
                afterChange(server, simulator, dimension, profile.key(), finalOldLength);
                if (online != null) {
                    online.sendMessage(Text.translatableWithFallback("msg.station_announcer.track.saved", "Track shape saved"), true);
                }
            });
        });
    }

    private static void handleReset(MinecraftServer server, ServerPlayerEntity player, String key) {
        if (!mayEdit(player)) {
            return;
        }
        final long[] n = TrackJson.parseKey(key);
        if (n == null) {
            return;
        }
        final ServerWorld world = player.getServerWorld();
        final String dimension = worldId(world);
        final String canonical = com.stationannouncer.track.TrackKey.ofUnordered(n[0], n[1], n[2], n[3], n[4], n[5]);
        if (coolingDown(player, canonical)) {
            return;
        }
        if (TrackStore.get(dimension, canonical) == null) {
            // nothing saved: make sure the client does not keep a stale copy
            sendOps(player, OP_REMOVE, Map.of(), List.of(canonical));
            return;
        }
        if (!nearNode(player, world, n[0], n[1], n[2], n[3], n[4], n[5])) {
            return;
        }
        final Simulator simulator = simulatorFor(world);
        if (simulator == null) {
            removeAndSync(server, dimension, canonical);
            return;
        }
        final UUID playerId = player.getUuid();
        simulator.run(() -> {
            double oldLength = Double.NaN;
            try {
                final Rail rail = TrackRails.find(simulator, n[0], n[1], n[2], n[3], n[4], n[5]);
                if (rail != null) {
                    oldLength = rail.railMath.getLength();
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Track reset: rail lookup failed", t);
            }
            final double finalOldLength = oldLength;
            server.execute(() -> {
                if (!removeAndSync(server, dimension, canonical)) {
                    return;
                }
                if (finalOldLength == finalOldLength) {
                    afterChange(server, simulator, dimension, canonical, finalOldLength);
                }
                final ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerId);
                if (online != null) {
                    online.sendMessage(Text.translatableWithFallback("msg.station_announcer.track.reset", "Track shape reset to MTR's"), true);
                }
            });
        });
    }

    private static boolean removeAndSync(MinecraftServer server, String dimension, String key) {
        if (!TrackStore.remove(dimension, key)) {
            return false;
        }
        broadcast(server, dimension, OP_REMOVE, Map.of(), List.of(key));
        return true;
    }

    /**
     * Server thread, after the registry changed for {@code key}: on the simulator thread, resolve the
     * rail (updates its bounds now rather than at the next query) and, if its length changed,
     * queue the depots whose paths cross it for regeneration.
     */
    private static void afterChange(MinecraftServer server, Simulator simulator, String dimension, String key, double oldLength) {
        final long[] n = TrackJson.parseKey(key);
        if (n == null) {
            return;
        }
        simulator.run(() -> {
            try {
                final Rail rail = TrackRails.find(simulator, n[0], n[1], n[2], n[3], n[4], n[5]);
                if (rail == null) {
                    return;
                }
                TrackRails.path(rail);
                final double newLength = rail.railMath.getLength();
                if (Math.abs(newLength - oldLength) <= LENGTH_EPSILON) {
                    return;
                }
                final Set<Long> depots = depotsCrossing(simulator, new Position(n[0], n[1], n[2]), new Position(n[3], n[4], n[5]));
                if (!depots.isEmpty()) {
                    server.execute(() -> {
                        PENDING_REGEN.computeIfAbsent(dimension, d -> new HashSet<>()).addAll(depots);
                        regenCountdown = REGEN_DELAY_TICKS;
                    });
                }
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Track change: re-path check failed", t);
            }
        });
    }

    /** Simulator thread: ids of depots whose main route or any siding leg uses the rail a–b. */
    private static Set<Long> depotsCrossing(Simulator simulator, Position a, Position b) {
        final Set<Long> ids = new HashSet<>();
        for (final Depot depot : simulator.depots) {
            boolean uses = uses(depot.getPath(), a, b);
            if (!uses) {
                for (final Siding siding : depot.savedRails) {
                    final TrackSidingPathsAccessor paths = (TrackSidingPathsAccessor) (Object) siding;
                    if (uses(paths.stationannouncer$getPathSidingToMainRoute(), a, b) || uses(paths.stationannouncer$getPathMainRouteToSiding(), a, b)) {
                        uses = true;
                        break;
                    }
                }
            }
            if (uses) {
                ids.add(depot.getId());
            }
        }
        return ids;
    }

    private static boolean uses(List<PathData> path, Position a, Position b) {
        for (final PathData data : path) {
            final Position p1 = data.getOrderedPosition1();
            final Position p2 = data.getOrderedPosition2();
            if (p1.equals(a) && p2.equals(b) || p1.equals(b) && p2.equals(a)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ticker: re-path + prune

    private static void tick(MinecraftServer server) {
        if (regenCountdown > 0 && --regenCountdown == 0 && !PENDING_REGEN.isEmpty()) {
            if (MtrSimulators.isAvailable()) {
                final Map<String, Set<Long>> due = new HashMap<>(PENDING_REGEN);
                PENDING_REGEN.clear();
                due.forEach((dimension, ids) -> {
                    final Simulator simulator = simulatorFor(dimension);
                    if (simulator != null) {
                        simulator.run(() -> regenerate(simulator, ids));
                    }
                });
            } else {
                regenCountdown = REGEN_DELAY_TICKS; // MTR not up yet: retry
            }
        }
        if (--pruneCountdown <= 0) {
            pruneCountdown = PRUNE_INTERVAL_TICKS;
            if (!pruneInFlight && MtrSimulators.isAvailable()) {
                prune(server);
            }
        }
    }

    private static void regenerate(Simulator simulator, Set<Long> ids) {
        try {
            final ObjectArrayList<Depot> depots = new ObjectArrayList<>();
            for (final long id : ids) {
                final Depot depot = simulator.depotIdMap.get(id);
                if (depot != null) {
                    depots.add(depot);
                }
            }
            if (!depots.isEmpty()) {
                StationAnnouncer.LOGGER.info("Track geometry: regenerating {} depot(s) in {} after a rail length change", depots.size(), simulator.dimension);
                Depot.generateDepots(simulator, depots);
            }
        } catch (Throwable t) {
            StationAnnouncer.LOGGER.warn("Track geometry: depot regeneration failed", t);
        }
    }

    /**
     * Drops profiles whose rail is gone, became a cable, or whose nodes were re-angled (the profile
     * no longer {@code matches}, so it is inert anyway). Reads each dimension's rails on its simulator
     * thread; removes on the server thread only if the stored profile is still the one checked.
     */
    private static void prune(MinecraftServer server) {
        final List<String> dimensions = new ArrayList<>();
        TrackStore.dimensions().forEach(dimensions::add);
        int[] outstanding = {0};
        for (final String dimension : dimensions) {
            final Simulator simulator = simulatorFor(dimension);
            if (simulator == null) {
                continue; // dimension not simulated (yet): keep its profiles
            }
            final Map<String, TrackProfile> snapshot = TrackStore.dimension(dimension);
            if (snapshot.isEmpty()) {
                continue;
            }
            outstanding[0]++;
            pruneInFlight = true;
            simulator.run(() -> {
                final List<String> orphans = new ArrayList<>();
                try {
                    snapshot.forEach((key, profile) -> {
                        if (isOrphan(simulator, profile)) {
                            orphans.add(key);
                        }
                    });
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Track geometry: prune check failed", t);
                    orphans.clear();
                }
                server.execute(() -> {
                    final List<String> removed = new ArrayList<>();
                    for (final String key : orphans) {
                        if (TrackStore.get(dimension, key) == snapshot.get(key) && TrackStore.remove(dimension, key)) {
                            removed.add(key);
                        }
                    }
                    if (!removed.isEmpty()) {
                        StationAnnouncer.LOGGER.info("Track geometry: dropped {} orphaned profile(s) in {}", removed.size(), dimension);
                        broadcast(server, dimension, OP_REMOVE, Map.of(), removed);
                    }
                    if (--outstanding[0] <= 0) {
                        pruneInFlight = false;
                    }
                });
            });
        }
    }

    /** Simulator thread. */
    static boolean isOrphan(Simulator simulator, TrackProfile profile) {
        final Rail rail = TrackRails.find(simulator, profile);
        if (rail == null) {
            return true;
        }
        final TrackRailMathAccess access = TrackRails.access(rail);
        if ("CABLE".equals(access.stationannouncer$shapeName())) {
            return true;
        }
        final long[] n = access.stationannouncer$nodes();
        final double[] angles = access.stationannouncer$angles();
        return !profile.matches(n[0], n[1], n[2], angles[0], n[3], n[4], n[5], angles[1]);
    }

    // ------------------------------------------------------------------ sync

    /** Full sync of the player's current dimension: SET (first batch) then PUT batches. */
    public static void syncTo(ServerPlayerEntity player) {
        final Map<String, TrackProfile> profiles = TrackStore.dimension(worldId(player.getServerWorld()));
        final List<Map<String, String>> batches = batches(profiles);
        if (batches.isEmpty()) {
            send(player, OP_SET, Map.of(), List.of());
            return;
        }
        for (int i = 0; i < batches.size(); i++) {
            send(player, i == 0 ? OP_SET : OP_PUT, batches.get(i), List.of());
        }
    }

    private static void broadcast(MinecraftServer server, String dimension, byte op, Map<String, TrackProfile> profiles, List<String> keys) {
        for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (dimension.equals(worldId(player.getServerWorld()))) {
                sendOps(player, op, profiles, keys);
            }
        }
    }

    private static void sendOps(ServerPlayerEntity player, byte op, Map<String, TrackProfile> profiles, List<String> keys) {
        if (op == OP_REMOVE) {
            send(player, op, Map.of(), keys);
        } else {
            for (final Map<String, String> batch : batches(profiles)) {
                send(player, op, batch, List.of());
            }
        }
    }

    private static List<Map<String, String>> batches(Map<String, TrackProfile> profiles) {
        final List<Map<String, String>> batches = new ArrayList<>();
        Map<String, String> batch = new HashMap<>();
        int chars = 0;
        for (final Map.Entry<String, TrackProfile> entry : profiles.entrySet()) {
            final String json = TrackJson.toJson(entry.getValue());
            if (json.length() > MAX_SYNC_JSON) {
                continue;
            }
            if (chars + json.length() > SYNC_BATCH_CHARS && !batch.isEmpty()) {
                batches.add(batch);
                batch = new HashMap<>();
                chars = 0;
            }
            batch.put(entry.getKey(), json);
            chars += json.length() + entry.getKey().length();
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    private static void send(ServerPlayerEntity player, byte op, Map<String, String> jsonByKey, List<String> keys) {
        final PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(op);
        if (op == OP_REMOVE) {
            buf.writeVarInt(keys.size());
            keys.forEach(key -> buf.writeString(key, MAX_KEY));
        } else {
            buf.writeVarInt(jsonByKey.size());
            jsonByKey.forEach((key, json) -> {
                buf.writeString(key, MAX_KEY);
                buf.writeString(json, MAX_SYNC_JSON);
            });
        }
        ServerPlayNetworking.send(player, SYNC_S2C, buf);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean mayEdit(ServerPlayerEntity player) {
        if (player.isSpectator()) {
            return false;
        }
        final GameMode mode = player.interactionManager.getGameMode();
        if (mode != GameMode.CREATIVE && mode != GameMode.SURVIVAL) {
            reject(player, "permission", "you cannot edit rails in this game mode");
            return false;
        }
        return true;
    }

    /** True (= drop the packet) when this player edited this same rail less than {@link #EDIT_COOLDOWN_MILLIS} ago. */
    private static boolean coolingDown(ServerPlayerEntity player, String key) {
        final long now = System.currentTimeMillis();
        final Map<String, Long> perRail = LAST_EDIT.computeIfAbsent(player.getUuid(), id -> new HashMap<>());
        final Long last = perRail.get(key);
        if (last != null && now - last < EDIT_COOLDOWN_MILLIS) {
            return true;
        }
        perRail.values().removeIf(time -> now - time >= EDIT_COOLDOWN_MILLIS);
        perRail.put(key, now);
        return false;
    }

    /** Within reach of node A or B, and allowed to modify the world at that node. */
    private static boolean nearNode(ServerPlayerEntity player, ServerWorld world, long ax, long ay, long az, long bx, long by, long bz) {
        final double reach2 = EDIT_REACH * EDIT_REACH;
        final double da = player.squaredDistanceTo(ax + 0.5, ay + 0.5, az + 0.5);
        final double db = player.squaredDistanceTo(bx + 0.5, by + 0.5, bz + 0.5);
        final boolean useA = da <= db;
        if (Math.min(da, db) > reach2) {
            reject(player, "far", "too far from the rail");
            return false;
        }
        final BlockPos node = useA ? new BlockPos((int) ax, (int) ay, (int) az) : new BlockPos((int) bx, (int) by, (int) bz);
        if (!world.canPlayerModifyAt(player, node)) {
            reject(player, "permission", "you cannot modify blocks here");
            return false;
        }
        return true;
    }

    private static void reject(ServerPlayerEntity player, String reason, String fallback) {
        player.sendMessage(Text.translatableWithFallback("msg.station_announcer.track.rejected",
            "Track shape not saved: %s", Text.translatableWithFallback("msg.station_announcer.track.rejected." + reason, fallback)), true);
    }

    private static String fallbackReason(String reason) {
        return switch (reason) {
            case "no_rail" -> "the rail no longer exists";
            case "cable" -> "cable rails cannot be reshaped";
            case "changed" -> "the rail's nodes were changed";
            case "open" -> "the shape does not reach the far node";
            default -> "the shape data is invalid";
        };
    }

    /** MTR's world id for a dimension ({@code Init.getWorldId}: "namespace/path") = {@code Simulator.dimension}. */
    public static String worldId(ServerWorld world) {
        final Identifier id = world.getRegistryKey().getValue();
        return id.getNamespace() + "/" + id.getPath();
    }

    public static Simulator simulatorFor(ServerWorld world) {
        return simulatorFor(worldId(world));
    }

    public static Simulator simulatorFor(String dimension) {
        final ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null) {
            return null;
        }
        for (final Simulator simulator : simulators) {
            if (simulator.dimension.equals(dimension)) {
                return simulator;
            }
        }
        return null;
    }
}
