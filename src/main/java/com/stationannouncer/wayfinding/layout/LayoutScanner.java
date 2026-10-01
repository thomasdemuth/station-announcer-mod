package com.stationannouncer.wayfinding.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.EmergencyExitDoorBlock;
import com.stationannouncer.mtr.FareLane;
import com.stationannouncer.mtr.MarkerBlock;
import com.stationannouncer.mtr.Wayfinding;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import com.stationannouncer.wayfinding.ExitPin;
import com.stationannouncer.wayfinding.WayfindingStore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Lift;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Station layout scans, the Minecraft side: reads one station's blocks and MTR
 * data into a {@link LayoutInput}, runs {@link LayoutSolver}, stores the result
 * and tells whoever asked.
 *
 * <p><b>Only on user input</b> (Thomas, 2026-09-29): a scan runs when a player
 * presses Scan in the Exit Marker's Layout tab or uses
 * {@code /stationlayout scan|scanall} — never on its own. "Scan all" exists for
 * worlds that installed the mod late.</p>
 *
 * <p><b>Threads.</b> One daemon worker runs one station at a time. MTR data is
 * read inside {@code simulator.run} (the TSC thread) and only plain values come
 * back. Blocks: chunks that are LOADED are copied on the server thread (their
 * palettes, cheap); every other chunk is read from its saved NBT through the
 * chunk storage, the way the basemap scanner does — never {@code getChunk},
 * which would finish proto-chunks and grow the world.</p>
 *
 * <p>Results live at {@code <save>/station-announcer-addon/layouts/<stationId>.json}
 * and feed Map+ (walks through exits, transfers, computed step-free) and the
 * editor's Layout tab. A stale layout stays until the next scan.</p>
 */
public final class LayoutScanner {
    public static final Identifier REQUEST_C2S = StationAnnouncer.id("layout_request");
    public static final Identifier DATA_S2C = StationAnnouncer.id("layout_data");

    public static final int ACTION_STATUS = 0;
    public static final int ACTION_SCAN = 1;
    public static final int ACTION_SCAN_ALL = 2;

    public static final byte STATE_NONE = 0;
    public static final byte STATE_QUEUED = 1;
    public static final byte STATE_SCANNING = 2;
    public static final byte STATE_DONE = 3;
    public static final byte STATE_FAILED = 4;
    public static final byte STATE_HIDE = 5;

    /** Who may start scans (they cost a few seconds of one CPU core each). */
    public static final int SCAN_PERMISSION = 2;

    private static final int[] MARGINS = {24, 12, 6};
    private static final long MAX_CELLS = 8_000_000L;
    private static final int MAX_SPAN = 768;
    private static final int Y_PAD_BELOW = 6;
    private static final int Y_PAD_ABOVE = 8;
    private static final int Y_REACH = 48;
    private static final long READ_TIMEOUT_SECONDS = 30;

    private static final Object LOCK = new Object();
    private static final LinkedHashSet<Long> QUEUE = new LinkedHashSet<>();
    private static final Map<Long, Set<UUID>> WATCHERS = new HashMap<>();
    private static final Map<Long, String> RESULTS = new ConcurrentHashMap<>();
    private static final Map<Long, String> FAILURES = new ConcurrentHashMap<>();
    private static volatile Map<Long, Map<Long, Boolean>> stepFree = Map.of();
    private static volatile List<KnownStation> known = List.of();

    private static ExecutorService worker;
    private static MinecraftServer server;
    private static Path dir;
    private static volatile long current;
    private static volatile boolean cancelled;
    private static boolean running;
    private static int bulkTotal;
    private static int bulkDone;
    @Nullable
    private static UUID bulkRequester;

    /** A station MTR knows, for command resolution / suggestions / "scan all". */
    public record KnownStation(long id, String name, String dimension) {
    }

    private LayoutScanner() {
    }

    // ================================================================ setup

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(LayoutScanner::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> onStopping());
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_C2S, (srv, player, handler, buf, sender) -> {
            int action = buf.readByte();
            long stationId = buf.readLong();
            srv.execute(() -> handleRequest(player, action, stationId));
        });
    }

    private static void onStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        dir = minecraftServer.getSavePath(WorldSavePath.ROOT).resolve("station-announcer-addon").resolve("layouts").normalize();
        RESULTS.clear();
        FAILURES.clear();
        Map<Long, Map<Long, Boolean>> flags = new HashMap<>();
        try {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                        try {
                            String json = Files.readString(file);
                            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                            long id = Long.parseLong(root.get("station").getAsString());
                            RESULTS.put(id, json);
                            flags.put(id, readStepFree(root));
                        } catch (Exception e) {
                            StationAnnouncer.LOGGER.warn("Skipping unreadable station layout {}", file, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not list {}", dir, e);
        }
        stepFree = Map.copyOf(flags);
        cancelled = false;
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Station Announcer station layout");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        StationAnnouncer.LOGGER.info("Station layouts loaded ({} scanned stations)", RESULTS.size());
        refreshKnown();
    }

    private static void onStopping() {
        cancelled = true;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        synchronized (LOCK) {
            QUEUE.clear();
            WATCHERS.clear();
            running = false;
            bulkTotal = 0;
            bulkDone = 0;
            bulkRequester = null;
        }
        current = 0;
        server = null;
    }

    // ============================================================== queries

    /** Any thread: the stored layout JSON (with paths) for a station, or null. */
    @Nullable
    public static String layoutJson(long stationId) {
        return RESULTS.get(stationId);
    }

    /** Any thread: platform id → step-free from the street, for scanned stations. */
    public static Map<Long, Boolean> stepFree(long stationId) {
        return stepFree.getOrDefault(stationId, Map.of());
    }

    public static List<KnownStation> knownStations() {
        return known;
    }

    public static String statusLine() {
        synchronized (LOCK) {
            if (!running && QUEUE.isEmpty()) {
                return "idle · " + RESULTS.size() + " stations scanned";
            }
            String now = current == 0 ? "starting" : "scanning " + nameOf(current);
            return now + " · " + QUEUE.size() + " queued"
                    + (bulkTotal > 0 ? " · bulk " + bulkDone + "/" + bulkTotal : "");
        }
    }

    static String nameOf(long stationId) {
        for (KnownStation station : known) {
            if (station.id() == stationId) {
                return station.name();
            }
        }
        return Long.toString(stationId);
    }

    /** Refresh the MTR station list (async, on each simulator's thread). */
    public static void refreshKnown() {
        refreshKnown(null);
    }

    /**
     * Server thread: refresh and WAIT (at most {@code millis}) — for commands right
     * after boot, before the first async refresh could see MTR's stations.
     */
    public static void refreshKnownNow(long millis) {
        var simulators = MtrSimulators.get();
        if (simulators == null || simulators.isEmpty()) {
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(simulators.size());
        refreshKnown(latch);
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void refreshKnown(@Nullable java.util.concurrent.CountDownLatch latch) {
        var simulators = MtrSimulators.get();
        if (simulators == null) {
            return;
        }
        Map<Long, KnownStation> merged = new ConcurrentHashMap<>();
        for (Simulator simulator : simulators) {
            simulator.run(() -> {
                try {
                    for (Station station : simulator.stations) {
                        if (station != null) {
                            merged.put(station.getId(), new KnownStation(station.getId(), firstLang(station.getName()),
                                    simulator.dimension));
                        }
                    }
                    List<KnownStation> list = new ArrayList<>(merged.values());
                    list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                    known = List.copyOf(list);
                } catch (Throwable t) {
                    StationAnnouncer.LOGGER.debug("Station layout: station list refresh failed", t);
                } finally {
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            });
        }
    }

    // ============================================================ requests

    private static void handleRequest(ServerPlayerEntity player, int action, long stationId) {
        switch (action) {
            case ACTION_STATUS -> sendData(player, stationId);
            case ACTION_SCAN -> {
                if (!player.hasPermissionLevel(SCAN_PERMISSION)) {
                    player.sendMessage(Text.translatable("msg.station_announcer.layout.no_permission").formatted(Formatting.RED), false);
                    return;
                }
                request(List.of(stationId), player.getUuid(), false);
            }
            case ACTION_SCAN_ALL -> {
                if (!player.hasPermissionLevel(SCAN_PERMISSION)) {
                    player.sendMessage(Text.translatable("msg.station_announcer.layout.no_permission").formatted(Formatting.RED), false);
                    return;
                }
                requestAll(player.getUuid());
            }
            default -> {
            }
        }
    }

    /** Server thread: queue stations; {@code requester} (may be null) hears about each result. */
    public static int request(List<Long> stationIds, @Nullable UUID requester, boolean bulk) {
        int added = 0;
        synchronized (LOCK) {
            for (long id : stationIds) {
                if (requester != null) {
                    WATCHERS.computeIfAbsent(id, k -> new HashSet<>()).add(requester);
                }
                if (id != current && QUEUE.add(id)) {
                    added++;
                }
            }
            if (bulk) {
                bulkTotal += added;
                bulkRequester = requester;
            }
        }
        for (long id : stationIds) {
            notifyState(id, id == current ? STATE_SCANNING : STATE_QUEUED, "");
        }
        pump();
        return added;
    }

    /** Server thread: every station MTR knows (the "installed the mod late" button). */
    public static int requestAll(@Nullable UUID requester) {
        refreshKnownNow(2000);
        List<Long> ids = new ArrayList<>();
        for (KnownStation station : known) {
            ids.add(station.id());
        }
        if (ids.isEmpty()) {
            // right after a restart MTR is still reading its data: say so, don't "scan 0"
            if (requester != null && server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(requester);
                if (player != null) {
                    player.sendMessage(Text.translatable("msg.station_announcer.layout.mtr_loading").formatted(Formatting.YELLOW), false);
                }
            }
            return 0;
        }
        synchronized (LOCK) {
            if (QUEUE.isEmpty() && !running) {
                bulkTotal = 0;
                bulkDone = 0;
            }
        }
        int added = request(ids, requester, true);
        if (requester != null && server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(requester);
            if (player != null) {
                player.sendMessage(Text.translatable("msg.station_announcer.layout.bulk_started", added), false);
            }
        }
        return added;
    }

    public static void cancelAll() {
        synchronized (LOCK) {
            QUEUE.clear();
            bulkTotal = 0;
            bulkDone = 0;
        }
    }

    private static void pump() {
        synchronized (LOCK) {
            if (running || QUEUE.isEmpty() || worker == null) {
                return;
            }
            long next = QUEUE.iterator().next();
            QUEUE.remove(next);
            running = true;
            current = next;
            worker.execute(() -> runOne(next));
        }
    }

    private static void runOne(long stationId) {
        MinecraftServer srv = server;
        notifyState(stationId, STATE_SCANNING, "");
        String error = null;
        LayoutResult result = null;
        try {
            result = scan(srv, stationId);
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.toString() : t.getMessage();
            if (!(t instanceof ScanException)) {
                StationAnnouncer.LOGGER.warn("Station layout scan of {} failed", stationId, t);
            }
        }
        if (result != null) {
            try {
                store(result);
            } catch (Exception e) {
                error = "could not save: " + e.getMessage();
            }
        }
        LayoutResult done = result;
        String failure = error;
        synchronized (LOCK) {
            running = false;
            current = 0;
            if (bulkTotal > 0) {
                bulkDone++;
            }
        }
        if (srv != null) {
            srv.execute(() -> finished(stationId, done, failure));
        }
        if (!cancelled) {
            pump();
        }
    }

    /** Server thread: tell the watchers, report bulk progress. */
    private static void finished(long stationId, @Nullable LayoutResult result, @Nullable String error) {
        if (error != null) {
            FAILURES.put(stationId, error);
        } else {
            FAILURES.remove(stationId);
        }
        Set<UUID> watchers;
        UUID bulk;
        int done;
        int total;
        boolean bulkOver;
        synchronized (LOCK) {
            watchers = WATCHERS.remove(stationId);
            bulk = bulkRequester;
            done = bulkDone;
            total = bulkTotal;
            bulkOver = total > 0 && QUEUE.isEmpty() && !running;
            if (bulkOver) {
                bulkTotal = 0;
                bulkDone = 0;
                bulkRequester = null;
            }
        }
        if (watchers != null && server != null) {
            for (UUID uuid : watchers) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player == null) {
                    continue;
                }
                sendData(player, stationId);
                if (total == 0) {
                    player.sendMessage(summary(stationId, result, error), false);
                }
            }
        }
        if (total > 0 && bulk != null && server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(bulk);
            if (player != null && (bulkOver || done % 5 == 0 || error != null)) {
                player.sendMessage(Text.translatable("msg.station_announcer.layout.bulk_progress", done, total)
                        .append(" · ").append(summary(stationId, result, error)), false);
            }
            if (bulkOver && player != null) {
                player.sendMessage(Text.translatable("msg.station_announcer.layout.bulk_done", total).formatted(Formatting.GREEN), false);
            }
        }
        StationAnnouncer.LOGGER.info("Station layout {} ({}): {}", nameOf(stationId), stationId,
                error != null ? "FAILED " + error : result.links.size() + " walks, " + result.nodes + " standing spots, "
                        + result.millis + " ms, " + result.warnings.size() + " warnings");
    }

    private static Text summary(long stationId, @Nullable LayoutResult result, @Nullable String error) {
        String name = result != null ? result.stationName : nameOf(stationId);
        if (error != null) {
            return Text.translatable("msg.station_announcer.layout.failed", name, error).formatted(Formatting.RED);
        }
        long openings = result.anchors.stream().filter(a -> a.kind().equals("opening")).count();
        long stepFreeCount = result.platformStepFree.values().stream().filter(Boolean::booleanValue).count();
        return Text.translatable("msg.station_announcer.layout.done", name, result.links.size(),
                stepFreeCount, result.platformStepFree.size(), openings, result.warnings.size());
    }

    // ============================================================ client I/O

    private static void notifyState(long stationId, byte state, String message) {
        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        srv.execute(() -> {
            Set<UUID> watchers;
            synchronized (LOCK) {
                Set<UUID> set = WATCHERS.get(stationId);
                watchers = set == null ? Set.of() : Set.copyOf(set);
            }
            for (UUID uuid : watchers) {
                ServerPlayerEntity player = srv.getPlayerManager().getPlayer(uuid);
                if (player != null) {
                    send(player, stationId, state, message, null);
                }
            }
        });
    }

    /** Server thread: the station's layout (or its queue state) to one player. */
    public static void sendData(ServerPlayerEntity player, long stationId) {
        byte state;
        String message = "";
        synchronized (LOCK) {
            state = stationId == current ? STATE_SCANNING : QUEUE.contains(stationId) ? STATE_QUEUED : STATE_NONE;
        }
        String json = RESULTS.get(stationId);
        String failure = FAILURES.get(stationId);
        if (state == STATE_NONE) {
            state = failure != null ? STATE_FAILED : json != null ? STATE_DONE : STATE_NONE;
            message = failure != null ? failure : "";
        }
        send(player, stationId, state, message, json);
    }

    public static void sendHide(ServerPlayerEntity player) {
        send(player, 0, STATE_HIDE, "", null);
    }

    private static void send(ServerPlayerEntity player, long stationId, byte state, String message, @Nullable String json) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(stationId);
        buf.writeByte(state);
        buf.writeString(message.length() > 500 ? message.substring(0, 500) : message, 512);
        byte[] data = json == null ? new byte[0] : gzip(json);
        if (data.length > 900_000) {
            data = new byte[0]; // absurdly large layout: the editor shows the summary only
        }
        buf.writeByteArray(data);
        ServerPlayNetworking.send(player, DATA_S2C, buf);
    }

    private static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ================================================================ store

    private static void store(LayoutResult result) throws Exception {
        String json = result.toJson(true).toString();
        if (dir != null) {
            Files.createDirectories(dir);
            Path file = dir.resolve(result.stationId + ".json");
            Path temp = dir.resolve(result.stationId + ".json.tmp");
            Files.writeString(temp, json);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        RESULTS.put(result.stationId, json);
        Map<Long, Map<Long, Boolean>> next = new HashMap<>(stepFree);
        next.put(result.stationId, Map.copyOf(result.platformStepFree));
        stepFree = Map.copyOf(next);
    }

    private static Map<Long, Boolean> readStepFree(JsonObject root) {
        Map<Long, Boolean> out = new HashMap<>();
        JsonObject flags = root.getAsJsonObject("platformStepFree");
        if (flags != null) {
            for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
                out.put(Long.parseLong(entry.getKey()), entry.getValue().getAsBoolean());
            }
        }
        return out;
    }

    // ================================================================= scan

    /** A scan that cannot run, with a message for the player (no stack trace in the log). */
    static final class ScanException extends Exception {
        ScanException(String message) {
            super(message);
        }
    }

    /** What MTR knows about one station, read on its simulator thread. */
    private record Snapshot(String name, String dimension, long areaMinX, long areaMaxX, long areaMinZ, long areaMaxZ,
                            List<LayoutInput.Platform> platforms, List<LayoutInput.Lift> lifts,
                            List<double[]> track, int[] region) {
    }

    private static LayoutResult scan(MinecraftServer srv, long stationId) throws Exception {
        if (srv == null) {
            throw new ScanException("server is stopping");
        }
        var simulators = MtrSimulators.get();
        if (simulators == null) {
            throw new ScanException("MTR is not running yet");
        }
        List<LayoutInput.Exit> exits = new ArrayList<>();
        List<int[]> exitPositions = new ArrayList<>();
        for (ExitPin pin : WayfindingStore.pins()) {
            if (pin.stationId() == stationId && pin.pinned()) {
                exits.add(new LayoutInput.Exit(pin.exitName(), pin.x(), pin.y(), pin.z()));
                exitPositions.add(new int[]{pin.x(), pin.y(), pin.z()});
            }
        }
        LayoutInput input = null;
        Snapshot snapshot = null;
        String size = "";
        for (int margin : MARGINS) {
            snapshot = null;
            for (Simulator simulator : simulators) {
                CompletableFuture<Snapshot> future = new CompletableFuture<>();
                simulator.run(() -> {
                    try {
                        future.complete(snapshot(simulator, stationId, exitPositions, margin));
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                Snapshot got = future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (got != null) {
                    snapshot = got;
                    break;
                }
            }
            if (snapshot == null) {
                throw new ScanException("MTR has no station with id " + stationId);
            }
            if (snapshot.platforms().isEmpty()) {
                throw new ScanException(snapshot.name() + " has no platforms in its MTR area");
            }
            int[] r = snapshot.region();
            long columns = (long) (r[3] - r[0] + 1) * (r[5] - r[2] + 1);
            size = (r[3] - r[0] + 1) + "×" + (r[5] - r[2] + 1);
            if (r[3] - r[0] + 1 > MAX_SPAN || r[5] - r[2] + 1 > MAX_SPAN) {
                continue;
            }
            input = readBlocks(srv, snapshot, columns);
            if (input != null) {
                break;
            }
        }
        if (input == null) {
            throw new ScanException(snapshot == null ? "station not found"
                    : snapshot.name() + ": the station area is too big to scan (" + size
                    + " blocks with its platforms; the limit is " + MAX_SPAN + " a side or 8 million blocks in all — shrink its MTR area)");
        }
        input.stationId = stationId;
        input.stationName = snapshot.name();
        input.exits.addAll(exits);
        if (cancelled) {
            throw new ScanException("cancelled");
        }
        return new LayoutSolver(input).solve();
    }

    /** TSC thread. Null when this simulator does not have the station. */
    @Nullable
    private static Snapshot snapshot(Simulator simulator, long stationId, List<int[]> exits, int margin) {
        Station station = null;
        for (Station candidate : simulator.stations) {
            if (candidate != null && candidate.getId() == stationId) {
                station = candidate;
                break;
            }
        }
        if (station == null) {
            return null;
        }
        List<LayoutInput.Platform> platforms = new ArrayList<>();
        long minX = station.getMinX() - margin;
        long maxX = station.getMaxX() + margin;
        long minZ = station.getMinZ() - margin;
        long maxZ = station.getMaxZ() + margin;
        for (Platform platform : station.savedRails) {
            List<double[]> samples = railSamples(simulator, platform);
            if (samples.isEmpty()) {
                continue;
            }
            platforms.add(new LayoutInput.Platform(platform.getId(), firstLang(platform.getName()), samples));
            for (double[] s : samples) {
                minX = Math.min(minX, (long) Math.floor(s[0]) - 6);
                maxX = Math.max(maxX, (long) Math.floor(s[0]) + 6);
                minZ = Math.min(minZ, (long) Math.floor(s[2]) - 6);
                maxZ = Math.max(maxZ, (long) Math.floor(s[2]) + 6);
            }
        }
        for (int[] exit : exits) {
            minX = Math.min(minX, exit[0] - 4);
            maxX = Math.max(maxX, exit[0] + 4);
            minZ = Math.min(minZ, exit[2] - 4);
            maxZ = Math.max(maxZ, exit[2] + 4);
        }
        int[] region = {(int) minX, 0, (int) minZ, (int) maxX, 0, (int) maxZ};

        List<double[]> track = new ArrayList<>();
        for (Rail rail : simulator.rails) {
            var math = rail.railMath;
            if (math == null || math.maxX < minX || math.minX > maxX + 1 || math.maxZ < minZ || math.minZ > maxZ + 1) {
                continue;
            }
            double length = math.getLength();
            for (double t = 0; t <= length; t += 0.5) {
                Vector v = math.getPosition(t, false);
                if (v.x >= minX - 1 && v.x <= maxX + 2 && v.z >= minZ - 1 && v.z <= maxZ + 2) {
                    track.add(new double[]{v.x, v.y, v.z});
                }
            }
        }
        List<LayoutInput.Lift> lifts = new ArrayList<>();
        for (Lift lift : simulator.lifts) {
            List<int[]> floors = new ArrayList<>();
            final long fx0 = minX;
            final long fx1 = maxX;
            final long fz0 = minZ;
            final long fz1 = maxZ;
            lift.iterateFloors(floor -> {
                Position p = floor.getPosition();
                if (p.getX() >= fx0 && p.getX() <= fx1 && p.getZ() >= fz0 && p.getZ() <= fz1) {
                    floors.add(new int[]{(int) p.getX(), (int) p.getY(), (int) p.getZ()});
                }
            });
            if (floors.size() >= 2) {
                double radius = Math.max(lift.getWidth(), lift.getDepth()) / 2.0 + 2.5;
                lifts.add(new LayoutInput.Lift(lift.getId(), floors, radius));
            }
        }
        return new Snapshot(firstLang(station.getName()), simulator.dimension, station.getMinX(), station.getMaxX(),
                station.getMinZ(), station.getMaxZ(), platforms, lifts, track, region);
    }

    private static List<double[]> railSamples(Simulator simulator, Platform platform) {
        List<double[]> samples = new ArrayList<>();
        try {
            Position one = platform.getRandomPosition();
            Position two = platform.getOtherPosition(one);
            var fromOne = simulator.positionsToRail.get(one);
            Rail rail = fromOne == null ? null : fromOne.get(two);
            if (rail == null || rail.railMath == null) {
                return samples;
            }
            double length = rail.railMath.getLength();
            for (double t = 0; t <= length; t += 0.5) {
                Vector v = rail.railMath.getPosition(t, false);
                samples.add(new double[]{v.x, v.y, v.z});
            }
        } catch (Throwable ignored) {
            // a half-built platform: no samples, the solver warns
        }
        return samples;
    }

    // ================================================================ blocks

    /** One chunk's blocks, however they were read. */
    private interface ChunkView {
        BlockState state(int lx, int y, int lz);

        /** Absolute Y of the first free block above the MOTION_BLOCKING surface; MIN_VALUE if unknown. */
        int surface(int lx, int lz);
    }

    private static final ChunkView EMPTY_CHUNK = new ChunkView() {
        @Override
        public BlockState state(int lx, int y, int lz) {
            return Blocks.AIR.getDefaultState();
        }

        @Override
        public int surface(int lx, int lz) {
            return Integer.MIN_VALUE;
        }
    };

    /**
     * Worker thread: read the region's chunks, pick the Y window, build the cells.
     * Null when the region is over the cell budget at this margin.
     */
    @Nullable
    private static LayoutInput readBlocks(MinecraftServer srv, Snapshot snapshot, long columns) throws Exception {
        ServerWorld world = worldFor(srv, snapshot.dimension());
        if (world == null) {
            throw new ScanException("the station's world is not loaded");
        }
        int[] r = snapshot.region();
        int minX = r[0];
        int minZ = r[2];
        int sizeX = r[3] - r[0] + 1;
        int sizeZ = r[5] - r[2] + 1;

        // ---- Y window from what we know is in the station
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (LayoutInput.Platform platform : snapshot.platforms()) {
            for (double[] s : platform.samples()) {
                lo = Math.min(lo, s[1]);
                hi = Math.max(hi, s[1]);
            }
        }
        double platformLo = lo;
        double platformHi = hi;
        for (ExitPin pin : WayfindingStore.pins()) {
            if (pin.pinned() && pin.x() >= minX && pin.x() < minX + sizeX && pin.z() >= minZ && pin.z() < minZ + sizeZ
                    && pin.mtrDim().equals(snapshot.dimension())) {
                lo = Math.min(lo, pin.y());
                hi = Math.max(hi, pin.y());
            }
        }
        for (LayoutInput.Lift lift : snapshot.lifts()) {
            for (int[] floor : lift.floors()) {
                lo = Math.min(lo, floor[1]);
                hi = Math.max(hi, floor[1]);
            }
        }

        // ---- chunks: loaded ones copied on the server thread, the rest from disk
        int cx0 = minX >> 4;
        int cz0 = minZ >> 4;
        int cx1 = (minX + sizeX - 1) >> 4;
        int cz1 = (minZ + sizeZ - 1) >> 4;
        int chunksX = cx1 - cx0 + 1;
        int chunksZ = cz1 - cz0 + 1;
        ChunkView[] chunks = new ChunkView[chunksX * chunksZ];
        int bottomY = world.getBottomY();
        int bottomSection = world.getBottomSectionCoord();
        srv.submit(() -> {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, false);
                    if (chunk != null) {
                        chunks[(cz - cz0) * chunksX + (cx - cx0)] = copyLoaded(chunk, bottomSection);
                    }
                }
            }
        }).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        DiskReader disk = new DiskReader(MathHelper.ceilLog2(world.getHeight() + 1), bottomY, bottomSection);
        List<String> warnings = new ArrayList<>();
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int slot = (cz - cz0) * chunksX + (cx - cx0);
                if (chunks[slot] != null) {
                    continue;
                }
                Optional<NbtCompound> nbt = world.getChunkManager().threadedAnvilChunkStorage
                        .getNbt(new ChunkPos(cx, cz)).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ChunkView view = nbt.isPresent() ? disk.decode(nbt.get()) : null;
                chunks[slot] = view == null ? EMPTY_CHUNK : view;
                if (view == null && warnings.size() < 3) {
                    warnings.add("chunk " + cx + "," + cz + " has never been generated or could not be read");
                }
                if (cancelled) {
                    throw new ScanException("cancelled");
                }
            }
        }

        // ---- surface; widen the Y window to street level when it is nearby
        int[] surface = new int[sizeX * sizeZ];
        List<Integer> border = new ArrayList<>();
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int wx = minX + x;
                int wz = minZ + z;
                ChunkView view = chunks[((wz >> 4) - cz0) * chunksX + ((wx >> 4) - cx0)];
                int s = view.surface(wx & 15, wz & 15);
                surface[z * sizeX + x] = s;
                if ((x == 0 || z == 0 || x == sizeX - 1 || z == sizeZ - 1) && s != Integer.MIN_VALUE) {
                    border.add(s);
                }
            }
        }
        if (!border.isEmpty()) {
            border.sort(Integer::compare);
            int street = border.get(border.size() / 2);
            if (street > hi && street <= platformHi + Y_REACH) {
                hi = street + 3;
            } else if (street < lo && street >= platformLo - Y_REACH) {
                lo = street - 3;
            }
        }
        int minY = Math.max(world.getBottomY(), (int) Math.floor(lo) - Y_PAD_BELOW);
        int maxY = Math.min(world.getTopY() - 1, (int) Math.ceil(hi) + Y_PAD_ABOVE);
        int sizeY = maxY - minY + 1;
        if (columns * sizeY > MAX_CELLS) {
            return null;
        }

        // ---- cells
        LayoutInput input = new LayoutInput();
        input.minX = minX;
        input.minY = minY;
        input.minZ = minZ;
        input.sizeX = sizeX;
        input.sizeY = sizeY;
        input.sizeZ = sizeZ;
        input.surface = surface;
        input.cells = new CellInfo[sizeX * sizeY * sizeZ];
        input.areaMinX = snapshot.areaMinX();
        input.areaMaxX = snapshot.areaMaxX();
        input.areaMinZ = snapshot.areaMinZ();
        input.areaMaxZ = snapshot.areaMaxZ();
        input.platforms.addAll(snapshot.platforms());
        input.lifts.addAll(snapshot.lifts());
        input.track.addAll(snapshot.track());
        input.warnings.addAll(warnings);
        Map<BlockState, CellInfo> cache = new IdentityHashMap<>();
        for (int y = 0; y < sizeY; y++) {
            int wy = minY + y;
            for (int z = 0; z < sizeZ; z++) {
                int wz = minZ + z;
                for (int x = 0; x < sizeX; x++) {
                    int wx = minX + x;
                    ChunkView view = chunks[((wz >> 4) - cz0) * chunksX + ((wx >> 4) - cx0)];
                    BlockState state = view.state(wx & 15, wy, wz & 15);
                    input.cells[((y * sizeZ) + z) * sizeX + x] = cache.computeIfAbsent(state, LayoutScanner::classify);
                }
            }
            if (cancelled) {
                throw new ScanException("cancelled");
            }
        }
        return input;
    }

    /** Server thread: a loaded chunk's sections and surface, copied so the worker can read them. */
    @SuppressWarnings("unchecked")
    private static ChunkView copyLoaded(WorldChunk chunk, int bottomSection) {
        ChunkSection[] sections = chunk.getSectionArray();
        PalettedContainer<BlockState>[] copies = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] != null && !sections[i].isEmpty()) {
                copies[i] = sections[i].getBlockStateContainer().copy();
            }
        }
        int[] surface = new int[256];
        Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                surface[lz * 16 + lx] = heightmap.get(lx, lz);
            }
        }
        return new ChunkView() {
            @Override
            public BlockState state(int lx, int y, int lz) {
                int slot = (y >> 4) - bottomSection;
                if (slot < 0 || slot >= copies.length || copies[slot] == null) {
                    return Blocks.AIR.getDefaultState();
                }
                return copies[slot].get(lx, y & 15, lz);
            }

            @Override
            public int surface(int lx, int lz) {
                return surface[lz * 16 + lx];
            }
        };
    }

    /** Worker thread: decodes saved chunk NBT (block palettes + surface) — the basemap scanner's recipe. */
    private static final class DiskReader {
        private final int heightBits;
        private final int bottomY;
        private final int bottomSection;
        private final RegistryEntryLookup<Block> blocks = Registries.BLOCK.getReadOnlyWrapper();
        private final Map<NbtCompound, BlockState> stateCache = new HashMap<>();

        DiskReader(int heightBits, int bottomY, int bottomSection) {
            this.heightBits = heightBits;
            this.bottomY = bottomY;
            this.bottomSection = bottomSection;
        }

        @Nullable
        ChunkView decode(NbtCompound nbt) {
            String status = nbt.getString("Status");
            if (!"minecraft:full".equals(status) && !"full".equals(status)) {
                return null;
            }
            NbtList list = nbt.getList("sections", NbtElement.COMPOUND_TYPE);
            Map<Integer, Object[]> sections = new HashMap<>(); // section Y -> {palette BlockState[], PackedIntegerArray|null}
            for (int i = 0; i < list.size(); i++) {
                NbtCompound section = list.getCompound(i);
                NbtCompound states = section.getCompound("block_states");
                NbtList paletteList = states.getList("palette", NbtElement.COMPOUND_TYPE);
                if (paletteList.isEmpty()) {
                    continue;
                }
                BlockState[] palette = new BlockState[paletteList.size()];
                for (int p = 0; p < palette.length; p++) {
                    NbtCompound entry = paletteList.getCompound(p);
                    BlockState state = stateCache.get(entry);
                    if (state == null) {
                        try {
                            state = NbtHelper.toBlockState(blocks, entry);
                        } catch (Throwable t) {
                            state = Blocks.AIR.getDefaultState();
                        }
                        stateCache.put(entry.copy(), state);
                    }
                    palette[p] = state;
                }
                PackedIntegerArray data = null;
                if (palette.length > 1 && states.contains("data", NbtElement.LONG_ARRAY_TYPE)) {
                    try {
                        data = new PackedIntegerArray(Math.max(4, MathHelper.ceilLog2(palette.length)), 4096,
                                states.getLongArray("data"));
                    } catch (Throwable ignored) {
                        // malformed: the palette's first entry everywhere
                    }
                }
                sections.put((int) section.getByte("Y"), new Object[]{palette, data});
            }
            int[] surface = new int[256];
            java.util.Arrays.fill(surface, Integer.MIN_VALUE);
            long[] heights = nbt.getCompound("Heightmaps").getLongArray("MOTION_BLOCKING");
            if (heights.length > 0) {
                try {
                    PackedIntegerArray packed = new PackedIntegerArray(heightBits, 256, heights);
                    for (int i = 0; i < 256; i++) {
                        int stored = packed.get(i);
                        surface[i] = stored <= 0 ? Integer.MIN_VALUE : stored + bottomY;
                    }
                } catch (Throwable ignored) {
                    // unknown surface
                }
            }
            return new ChunkView() {
                @Override
                public BlockState state(int lx, int y, int lz) {
                    Object[] section = sections.get(y >> 4);
                    if (section == null) {
                        return Blocks.AIR.getDefaultState();
                    }
                    BlockState[] palette = (BlockState[]) section[0];
                    PackedIntegerArray data = (PackedIntegerArray) section[1];
                    if (data == null) {
                        return palette[0];
                    }
                    int i = data.get(((y & 15) * 16 + lz) * 16 + lx);
                    return i >= 0 && i < palette.length ? palette[i] : palette[0];
                }

                @Override
                public int surface(int lx, int lz) {
                    return surface[lz * 16 + lx];
                }
            };
        }
    }

    /**
     * One block state → what it means to a rider. Walk-through by rule: turnstile /
     * HEET / MTR ticket-barrier lanes (fare control), emergency exit doors (last
     * resort), openable doors and fence gates, MTR lift and platform doors, our
     * markers. Everything else by its collision shape, asked with an EMPTY view
     * (our blocks' shapes are state-only; anything that throws counts as solid).
     */
    static CellInfo classify(BlockState state) {
        Block block = state.getBlock();
        boolean liquid = !state.getFluidState().isEmpty();
        String className = block.getClass().getName();
        if (block instanceof FareLane.Host || className.endsWith("BlockTicketBarrier")) {
            return CellInfo.passable(CellInfo.TAG_FARE, false);
        }
        if (block instanceof EmergencyExitDoorBlock) {
            return CellInfo.passable(CellInfo.TAG_EMERGENCY, false);
        }
        if ((block instanceof DoorBlock && block != Blocks.IRON_DOOR) || block instanceof FenceGateBlock
                || block instanceof MarkerBlock || className.contains("LiftDoor") || className.endsWith("PSDDoor")
                || className.endsWith("APGDoor")) {
            return CellInfo.passable(CellInfo.TAG_NONE, false);
        }
        byte tag = className.endsWith("BlockEscalatorStep") ? CellInfo.TAG_ESCALATOR : CellInfo.TAG_NONE;
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        } catch (Throwable t) {
            return state.isOpaque() ? CellInfo.full(tag) : CellInfo.passable(tag, liquid);
        }
        if (shape.isEmpty()) {
            return CellInfo.passable(tag, liquid);
        }
        List<Box> boxes = shape.getBoundingBoxes();
        double[][] raw = new double[boxes.size()][];
        for (int i = 0; i < raw.length; i++) {
            Box b = boxes.get(i);
            raw[i] = new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        }
        return CellInfo.fromBoxes(raw, tag, false);
    }

    // ============================================================== helpers

    @Nullable
    private static ServerWorld worldFor(MinecraftServer srv, String dimension) {
        for (ServerWorld world : srv.getWorlds()) {
            if (Wayfinding.mtrDim(world).equals(dimension)) {
                return world;
            }
        }
        return null;
    }

    static String firstLang(String name) {
        if (name == null) {
            return "";
        }
        int bar = name.indexOf('|');
        return (bar < 0 ? name : name.substring(0, bar)).trim();
    }

    /** A station by id, exact name, or unique name prefix (case-insensitive). */
    @Nullable
    public static KnownStation resolve(String query) {
        KnownStation found = resolveCached(query);
        if (found == null) {
            refreshKnownNow(2000); // just booted, or a station MTR added since the last look
            found = resolveCached(query);
        }
        return found;
    }

    @Nullable
    private static KnownStation resolveCached(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return null;
        }
        List<KnownStation> list = known;
        for (KnownStation station : list) {
            if (Long.toString(station.id()).equals(q) || station.name().equalsIgnoreCase(q)) {
                return station;
            }
        }
        KnownStation prefix = null;
        int count = 0;
        String lower = q.toLowerCase(Locale.ROOT);
        for (KnownStation station : list) {
            if (station.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                prefix = station;
                count++;
            }
        }
        return count == 1 ? prefix : null;
    }

    /** For {@code /stationlayout status}: one line per scanned station. */
    public static List<String> scannedSummaries() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, String> entry : RESULTS.entrySet()) {
            try {
                JsonObject root = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                JsonArray links = root.getAsJsonArray("links");
                JsonArray warnings = root.getAsJsonArray("warnings");
                long age = (System.currentTimeMillis() - root.get("scannedAt").getAsLong()) / 60000L;
                out.add(root.get("name").getAsString() + " · " + links.size() + " walks · "
                        + warnings.size() + " warnings · " + age + " min ago");
            } catch (Exception e) {
                out.add(entry.getKey() + " · unreadable");
            }
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }
}
