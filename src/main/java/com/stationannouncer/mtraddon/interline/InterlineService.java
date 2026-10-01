package com.stationannouncer.mtraddon.interline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mixin.PacketRequestResponseInvoker;
import com.stationannouncer.mtraddon.AddonNetworking;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.mtraddon.AddonStore;
import com.stationannouncer.mtraddon.DepotGroup;
import com.stationannouncer.mtraddon.DepotGroupEngine;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.mtr.core.data.Depot;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server side of the interline tooling: the analysis cache, the solver worker, the
 * in-game packets and the Apply action. The web view reads through
 * {@link #analyzeAndCache} / {@link #cached} and is read-only.
 *
 * <p><b>Threads.</b> Analyses are built on the dimension's SIMULATOR thread (they read its
 * sidings); the solver runs on one daemon worker because a search can take a few hundred
 * milliseconds that must never stall a simulation tick; store edits and packets happen on
 * the SERVER thread.</p>
 *
 * <p><b>Apply.</b> Delays go into the addon store; dwell pads into the per-route dwell
 * overrides (Feature 2); frequencies through MTR's own data-update path
 * ({@code PacketUpdateData} → simulator → response broadcast to every player in the world,
 * i.e. exactly what saving MTR's depot screen does), so MTR's dashboard shows the new
 * sliders. Two seconds later the affected depots' departures are rewritten — or, when a
 * dwell changed, the depots are regenerated, because dwell is baked into the path.</p>
 *
 * <ul>
 *   <li>{@code interline_request} (C2S) — {@code varint id, string json}: {@code {}} for the
 *       analysis, or a solver request ({@link InterlineSolver.Request}). Replies with
 *       {@code interline_reply} carrying {@code {"analysis":…, "suggestion":…}}.</li>
 *   <li>{@code interline_apply} (C2S) — {@code {"delays":{id:ms}, "frequencies":{id:f},
 *       "dwell":[{platform, route, dwellMs}]}}; a delay ≤ 0 clears it.</li>
 *   <li>{@code interline_group} (C2S) — create/rename/re-member/delete one depot group.</li>
 *   <li>{@code interline_reply} (S2C) — {@code varint id, string json}; id −1 is an
 *       unsolicited refresh after an edit.</li>
 * </ul>
 */
public final class InterlineService {
    public static final Identifier REQUEST_C2S = StationAnnouncer.id("interline_request");
    public static final Identifier APPLY_C2S = StationAnnouncer.id("interline_apply");
    public static final Identifier GROUP_C2S = StationAnnouncer.id("interline_group");
    public static final Identifier REPLY_S2C = StationAnnouncer.id("interline_reply");

    /** Wire cap on a reply's JSON (bytes). Custom payloads top out at 1 MiB. */
    public static final int MAX_REPLY_BYTES = 1_000_000;
    /** Wire cap on a request/apply body. */
    public static final int MAX_REQUEST_CHARS = 32_000;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_GROUP_DEPOTS = 32;
    /** How long after Apply the departures are rewritten (MTR's own update lands first). */
    private static final int REWRITE_DELAY_TICKS = 40;
    /** An analysis older than this is rebuilt for the web suggestion endpoint. */
    public static final long WEB_CACHE_MILLIS = 120_000;

    private static final ConcurrentHashMap<String, InterlineModel.Analysis> CACHE = new ConcurrentHashMap<>();
    private static volatile ExecutorService solver;
    /** Server-thread-only delayed actions. */
    private static final List<long[]> PENDING_TICKS = new ArrayList<>();
    private static final List<Runnable> PENDING_ACTIONS = new ArrayList<>();

    private InterlineService() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            solver = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Station Announcer interline solver");
                thread.setDaemon(true);
                return thread;
            });
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ExecutorService current = solver;
            solver = null;
            if (current != null) {
                current.shutdownNow();
            }
            CACHE.clear();
            PENDING_TICKS.clear();
            PENDING_ACTIONS.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING_TICKS.isEmpty()) {
                return;
            }
            List<Runnable> due = new ArrayList<>();
            for (int i = PENDING_TICKS.size() - 1; i >= 0; i--) {
                if (--PENDING_TICKS.get(i)[0] <= 0) {
                    due.add(PENDING_ACTIONS.remove(i));
                    PENDING_TICKS.remove(i);
                }
            }
            due.forEach(Runnable::run);
        });

        ServerPlayNetworking.registerGlobalReceiver(REQUEST_C2S, (server, player, handler, buf, sender) -> {
            int requestId = buf.readVarInt();
            String body = buf.readString(MAX_REQUEST_CHARS);
            server.execute(() -> {
                if (!allowed(player)) {
                    return;
                }
                JsonObject params = parse(body);
                request(server, player, requestId, params.has("section") ? params : null);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(APPLY_C2S, (server, player, handler, buf, sender) -> {
            String body = buf.readString(MAX_REQUEST_CHARS);
            server.execute(() -> {
                if (allowed(player)) {
                    apply(server, player, parse(body));
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(GROUP_C2S, (server, player, handler, buf, sender) -> {
            boolean delete = buf.readBoolean();
            long id = buf.readLong();
            String name = buf.readString(MAX_NAME_LENGTH);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_GROUP_DEPOTS) {
                return;
            }
            long[] members = new long[count];
            for (int i = 0; i < count; i++) {
                members[i] = buf.readLong();
            }
            server.execute(() -> {
                if (allowed(player)) {
                    editGroup(server, player, delete, id, name, members);
                }
            });
        });
    }

    private static boolean allowed(ServerPlayerEntity player) {
        AddonServerConfig config = AddonServerConfig.get();
        return config.depotGroups.enabled && player.hasPermissionLevel(config.editPermissionLevel);
    }

    // ---------------------------------------------------------------- analysis

    /** Simulator thread: build, cache and return the analysis for that simulator's dimension. */
    public static InterlineModel.Analysis analyzeAndCache(Simulator simulator) {
        InterlineModel.Analysis analysis = InterlineAnalyzer.analyze(simulator);
        CACHE.put(simulator.dimension, analysis);
        return analysis;
    }

    /** Any thread: the last analysis of a dimension if it is younger than {@code maxAgeMillis}. */
    public static InterlineModel.Analysis cached(String dimension, long maxAgeMillis) {
        InterlineModel.Analysis analysis = CACHE.get(dimension);
        return analysis == null || System.currentTimeMillis() - analysis.builtAt() > maxAgeMillis ? null : analysis;
    }

    /** Server thread: analyse the player's dimension (and solve, when asked), then reply. */
    private static void request(MinecraftServer server, ServerPlayerEntity player, int requestId, JsonObject solveParams) {
        Simulator simulator = simulatorFor(player.getServerWorld());
        if (simulator == null) {
            reply(player, requestId, wrap(InterlineJson.error("MTR has no railway simulation for this dimension."), null));
            return;
        }
        simulator.run(() -> {
            InterlineModel.Analysis analysis;
            try {
                analysis = analyzeAndCache(simulator);
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Interline analysis failed", t);
                server.execute(() -> reply(player, requestId, wrap(InterlineJson.error("Analysis failed: " + t), null)));
                return;
            }
            if (solveParams == null) {
                JsonObject json = InterlineJson.analysis(analysis);
                server.execute(() -> reply(player, requestId, wrap(json, null)));
                return;
            }
            ExecutorService worker = solver;
            if (worker == null) {
                return;
            }
            worker.execute(() -> {
                JsonObject suggestion;
                try {
                    suggestion = InterlineSolver.solve(analysis, InterlineSolver.Request.fromJson(solveParams));
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Interline suggestion failed", t);
                    suggestion = InterlineJson.error("Suggestion failed: " + t);
                }
                JsonObject json = wrap(InterlineJson.analysis(analysis), suggestion);
                server.execute(() -> reply(player, requestId, json));
            });
        });
    }

    private static JsonObject wrap(JsonObject analysis, JsonObject suggestion) {
        JsonObject root = new JsonObject();
        root.add("analysis", analysis);
        if (suggestion != null) {
            root.add("suggestion", suggestion);
        }
        return root;
    }

    private static void reply(ServerPlayerEntity player, int requestId, JsonObject json) {
        if (player.isDisconnected()) {
            return;
        }
        String text = json.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_REPLY_BYTES) {
            text = wrap(InterlineJson.error("Too many sections to send — lower depotGroups.maxSections."), null).toString();
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(requestId);
        buf.writeString(text, MAX_REPLY_BYTES);
        ServerPlayNetworking.send(player, REPLY_S2C, buf);
    }

    /** Pushes a fresh analysis to one player (after an edit). */
    private static void push(MinecraftServer server, ServerPlayerEntity player) {
        request(server, player, -1, null);
    }

    // ---------------------------------------------------------------- groups

    private static void editGroup(MinecraftServer server, ServerPlayerEntity player, boolean delete, long id,
                                  String name, long[] members) {
        AddonServerConfig config = AddonServerConfig.get();
        long[] unique = java.util.Arrays.stream(members).filter(depotId -> depotId != 0).distinct()
                .limit(config.depotGroups.maxDepotsPerGroup).toArray();
        if (delete || unique.length == 0) {
            if (id != 0) {
                AddonStore.removeDepotGroup(id);
            }
        } else {
            String label = name.trim();
            if (label.length() > config.depotGroups.maxNameLength) {
                label = label.substring(0, config.depotGroups.maxNameLength);
            }
            if (label.isBlank()) {
                label = "Depot group"; // literal: a dedicated server has no lang file
            }
            if (AddonStore.putDepotGroup(new DepotGroup(id, label, unique), config.depotGroups.maxGroups) == null) {
                player.sendMessage(Text.translatable("msg.station_announcer.depot_group.too_many",
                        config.depotGroups.maxGroups), true);
            }
        }
        push(server, player);
    }

    // ---------------------------------------------------------------- apply

    private static void apply(MinecraftServer server, ServerPlayerEntity player, JsonObject body) {
        boolean changed = applyChanges(server, simulatorFor(player.getServerWorld()), player.getServerWorld(), body,
                () -> push(server, player));
        if (changed) {
            player.sendMessage(Text.translatable("msg.station_announcer.interline.applied"), true);
        } else {
            push(server, player);
        }
    }

    /**
     * Server thread, from the dispatch web page (already authorised by the servlet: a
     * paired browser whose player has the edit permission level). Returns a short status.
     */
    public static String applyFromWeb(MinecraftServer server, String dimension, JsonObject body) {
        if (!AddonServerConfig.get().depotGroups.enabled || !AddonServerConfig.get().depotGroups.webApply) {
            return "Applying from the web is turned off (depotGroups.webApply).";
        }
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        Simulator simulator = null;
        if (simulators != null) {
            for (Simulator candidate : simulators) {
                if (candidate.dimension.equals(dimension)) {
                    simulator = candidate;
                }
            }
        }
        ServerWorld world = null;
        for (ServerWorld candidate : server.getWorlds()) {
            try {
                if (org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(candidate)).equals(dimension)) {
                    world = candidate;
                }
            } catch (Throwable ignored) {
                // a world MTR does not know
            }
        }
        if (simulator == null || world == null) {
            return "That dimension has no railway.";
        }
        return applyChanges(server, simulator, world, body, null)
                ? "Applied — departures are rewritten in a few seconds." : "Nothing to change.";
    }

    /** Shared by the game packet and the web page. Returns false when the body changed nothing. */
    private static boolean applyChanges(MinecraftServer server, Simulator simulator, ServerWorld world, JsonObject body,
                                        Runnable afterRewrite) {
        AddonServerConfig config = AddonServerConfig.get();
        // Delays.
        Map<Long, Long> delays = new LinkedHashMap<>();
        if (body.has("delays") && body.get("delays").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("delays").entrySet()) {
                long depotId = parseLong(entry.getKey());
                if (depotId != 0) {
                    delays.put(depotId, Math.max(0, Math.min(86_400_000L, entry.getValue().getAsLong())));
                }
            }
        }
        // Frequencies.
        Map<Long, Integer> frequencies = new LinkedHashMap<>();
        if (body.has("frequencies") && body.get("frequencies").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("frequencies").entrySet()) {
                long depotId = parseLong(entry.getKey());
                if (depotId != 0) {
                    frequencies.put(depotId, Math.max(1, Math.min(config.depotGroups.maxFrequency, entry.getValue().getAsInt())));
                }
            }
        }
        // Dwell pads (absolute per-route dwell at a platform).
        Map<Long, Map<Long, Long>> dwell = new LinkedHashMap<>();
        if (body.has("dwell") && body.get("dwell").isJsonArray()) {
            JsonArray array = body.getAsJsonArray("dwell");
            for (int i = 0; i < Math.min(64, array.size()); i++) {
                JsonObject entry = array.get(i).getAsJsonObject();
                long platformId = parseLong(entry.get("platform").getAsString());
                long routeId = parseLong(entry.get("route").getAsString());
                long millis = Math.max(1_000, Math.min(600_000, entry.get("dwellMs").getAsLong()));
                if (platformId != 0 && routeId != 0) {
                    dwell.computeIfAbsent(platformId, key -> new LinkedHashMap<>()).put(routeId, millis);
                }
            }
        }
        if (delays.isEmpty() && frequencies.isEmpty() && dwell.isEmpty()) {
            return false;
        }

        AddonStore.setDepotDelays(delays);
        if (!dwell.isEmpty()) {
            Map<Long, java.util.LinkedHashMap<Long, Long>> existing = AddonStore.dwellOverridesView();
            dwell.forEach((platformId, byRoute) -> {
                Map<Long, Long> merged = new LinkedHashMap<>(existing.getOrDefault(platformId, new java.util.LinkedHashMap<>()));
                merged.putAll(byRoute);
                AddonStore.setDwellOverrides(platformId, merged);
            });
            AddonNetworking.broadcastDwellOverrides(server);
        }

        Set<Long> touched = new HashSet<>(delays.keySet());
        touched.addAll(frequencies.keySet());
        Set<Long> padRoutes = new HashSet<>();
        dwell.values().forEach(byRoute -> padRoutes.addAll(byRoute.keySet()));
        if (simulator != null && !frequencies.isEmpty()) {
            pushFrequencies(server, simulator, world, frequencies);
        }
        Runnable done = afterRewrite == null ? () -> { } : afterRewrite;

        later(() -> {
            if (simulator != null && !padRoutes.isEmpty()) {
                // Dwell is baked into the path: regenerate every depot running a padded route.
                simulator.run(() -> {
                    ObjectArrayList<Depot> regenerate = new ObjectArrayList<>();
                    for (Depot depot : simulator.depots) {
                        if (depot != null && depot.routes.stream().anyMatch(route -> route != null && padRoutes.contains(route.getId()))) {
                            regenerate.add(depot);
                            touched.remove(depot.getId());
                        }
                    }
                    try {
                        Depot.generateDepots(simulator, regenerate);
                    } catch (Throwable t) {
                        StationAnnouncer.LOGGER.warn("Could not regenerate depots after a dwell change", t);
                    }
                    server.execute(() -> DepotGroupEngine.refreshOffsets(server, touched, done));
                });
            } else {
                DepotGroupEngine.refreshOffsets(server, touched, done);
            }
        });
        return true;
    }

    /**
     * Frequencies through MTR's own update path. Each depot is serialised on its simulator
     * thread (MTR's own full data, unchanged), re-read into a detached copy on the server
     * thread, given the new all-day slider value and sent as {@code PacketUpdateData} —
     * byte-for-byte what MTR's depot screen sends, just originating on the server.
     */
    private static void pushFrequencies(MinecraftServer server, Simulator simulator, ServerWorld world,
                                        Map<Long, Integer> frequencies) {
        simulator.run(() -> {
            Map<Long, org.mtr.libraries.com.google.gson.JsonObject> serialized = new HashMap<>();
            for (Map.Entry<Long, Integer> entry : frequencies.entrySet()) {
                Depot depot = simulator.depotIdMap.get(entry.getKey().longValue());
                if (depot != null) {
                    serialized.put(entry.getKey(), Utilities.getJsonObjectFromData(depot));
                }
            }
            server.execute(() -> {
                try {
                    MinecraftClientData scratch = new MinecraftClientData();
                    UpdateDataRequest update = new UpdateDataRequest(scratch);
                    serialized.forEach((depotId, json) -> {
                        Depot copy = new Depot(new JsonReader(json), scratch);
                        int f = frequencies.get(depotId);
                        for (int hour = 0; hour < Timetable.HOURS; hour++) {
                            copy.setFrequency(hour, f);
                        }
                        update.addDepot(copy);
                    });
                    PacketUpdateData packet = new PacketUpdateData(update);
                    ((PacketRequestResponseInvoker) (Object) packet).stationAnnouncer$runServerOutbound(
                            new org.mtr.mapping.holder.ServerWorld(world), null);
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.warn("Could not write depot frequencies", t);
                }
            });
        });
    }

    /** Server thread: run {@code action} {@link #REWRITE_DELAY_TICKS} ticks from now. */
    private static void later(Runnable action) {
        schedule(REWRITE_DELAY_TICKS, action);
    }

    /** Server thread: run {@code action} on the server thread {@code ticks} ticks from now. */
    public static void schedule(int ticks, Runnable action) {
        PENDING_TICKS.add(new long[]{Math.max(1, ticks)});
        PENDING_ACTIONS.add(action);
    }

    // ---------------------------------------------------------------- helpers

    /** The simulator of a world, matched on MTR's world id (as BridgeService does). */
    public static Simulator simulatorFor(ServerWorld world) {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null || world == null) {
            return null;
        }
        String id;
        try {
            id = org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            return null;
        }
        for (Simulator simulator : simulators) {
            if (simulator.dimension.equals(id)) {
                return simulator;
            }
        }
        return null;
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
