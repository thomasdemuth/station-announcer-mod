package com.stationannouncer.mtraddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which MTR platforms have gap fillers, and how their fillers are timed.
 *
 * <p>Timing is a PLATFORM property, not a block property: every filler on a
 * platform moves together and the interlock holds the train on the slowest of
 * them, so one set of numbers per platform is the only thing that can be
 * honoured. The brush screen on any filler edits its platform's numbers.</p>
 *
 * <p>A platform is <em>active</em> — the engine sequences its stops — while at
 * least one filler block is registered to it. Blocks register themselves from
 * their server ticker (idempotent) and unregister when broken, so /fill,
 * pastes and chunk reloads all converge without a placement hook. Chunk
 * unloads do not unregister: trains keep waiting for fillers nobody is
 * looking at, exactly as they would for real ones.</p>
 *
 * <p><b>Threads:</b> mutated on the server thread only; {@link #active()} is an
 * immutable snapshot published through a volatile field for the simulator
 * threads. Persisted at {@code <save>/station-announcer-addon/gap_fillers.json},
 * its own file so it never collides with the addon's data.json.</p>
 */
public final class GapFillerStore {
    /** Per-platform timing, all in milliseconds. */
    public record Settings(int extendMs, int retractMs, int minDwellMs) {
        public static final int MIN_MOVE_MS = 1_000;
        public static final int MAX_MOVE_MS = 10_000;
        public static final int MAX_DWELL_MS = 120_000;

        /** Union Square: a hydraulic ram, quick and hard ("a loud bang"). */
        public static final Settings UNION = new Settings(2_500, 2_500, 20_000);
        /** South Ferry loop: sections rolling down sloping rails, slower out and back. */
        public static final Settings LOOP = new Settings(3_500, 3_000, 25_000);

        public Settings clamped() {
            return new Settings(clamp(extendMs, MIN_MOVE_MS, MAX_MOVE_MS),
                    clamp(retractMs, MIN_MOVE_MS, MAX_MOVE_MS),
                    clamp(minDwellMs, 0, MAX_DWELL_MS));
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static final Map<Long, Settings> SETTINGS = new HashMap<>();
    /** platform id → "dimension|x|y|z" of every filler block linked to it. */
    private static final Map<Long, Set<String>> BLOCKS = new HashMap<>();

    private static volatile Map<Long, Settings> active = Map.of();
    private static Path path;
    private static boolean dirty;

    private GapFillerStore() {
    }

    /** Simulator threads: the platforms whose stops are sequenced. Never null. */
    public static Map<Long, Settings> active() {
        return active;
    }

    /** Server thread. The platform's settings, or {@code fallback} when it has none yet. */
    public static Settings settings(long platformId, Settings fallback) {
        synchronized (LOCK) {
            return SETTINGS.getOrDefault(platformId, fallback);
        }
    }

    /**
     * Server thread: a filler block at {@code key} belongs to {@code platformId}.
     * The first filler of a platform seeds its timing from the block's style.
     */
    public static void register(long platformId, String key, Settings seed) {
        if (platformId == 0) {
            return;
        }
        synchronized (LOCK) {
            boolean changed = BLOCKS.computeIfAbsent(platformId, id -> new HashSet<>()).add(key);
            if (!SETTINGS.containsKey(platformId)) {
                SETTINGS.put(platformId, seed.clamped());
                changed = true;
            }
            if (!changed) {
                return;
            }
            dirty = true;
        }
        publish();
    }

    /** Server thread: the block at {@code key} was broken or relinked away. */
    public static void unregister(long platformId, String key) {
        if (platformId == 0) {
            return;
        }
        synchronized (LOCK) {
            Set<String> blocks = BLOCKS.get(platformId);
            if (blocks == null || !blocks.remove(key)) {
                return;
            }
            if (blocks.isEmpty()) {
                BLOCKS.remove(platformId);
            }
            dirty = true;
        }
        publish();
    }

    /** Server thread: the brush screen saved new timing for a platform. */
    public static void setSettings(long platformId, Settings settings) {
        if (platformId == 0) {
            return;
        }
        synchronized (LOCK) {
            SETTINGS.put(platformId, settings.clamped());
            dirty = true;
        }
        publish();
    }

    public static int fillerCount(long platformId) {
        synchronized (LOCK) {
            Set<String> blocks = BLOCKS.get(platformId);
            return blocks == null ? 0 : blocks.size();
        }
    }

    private static void publish() {
        Map<Long, Settings> next = new HashMap<>();
        synchronized (LOCK) {
            BLOCKS.forEach((platformId, blocks) -> {
                if (!blocks.isEmpty()) {
                    next.put(platformId, SETTINGS.getOrDefault(platformId, Settings.UNION));
                }
            });
        }
        active = Map.copyOf(next);
    }

    // ------------------------------------------------------------- lifecycle

    public static void load(MinecraftServer server) {
        Path file = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("gap_fillers.json").normalize();
        synchronized (LOCK) {
            path = file;
            SETTINGS.clear();
            BLOCKS.clear();
            dirty = false;
            try {
                if (Files.exists(file)) {
                    JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    JsonObject platforms = root.getAsJsonObject("platforms");
                    if (platforms != null) {
                        for (Map.Entry<String, JsonElement> entry : platforms.entrySet()) {
                            long platformId = Long.parseLong(entry.getKey());
                            JsonObject p = entry.getValue().getAsJsonObject();
                            SETTINGS.put(platformId, new Settings(p.get("extendMs").getAsInt(),
                                    p.get("retractMs").getAsInt(), p.get("minDwellMs").getAsInt()).clamped());
                            Set<String> blocks = new HashSet<>();
                            JsonArray list = p.getAsJsonArray("blocks");
                            if (list != null) {
                                list.forEach(element -> blocks.add(element.getAsString()));
                            }
                            if (!blocks.isEmpty()) {
                                BLOCKS.put(platformId, blocks);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not read {}, starting without gap fillers", file, e);
            }
        }
        publish();
        StationAnnouncer.LOGGER.info("Gap filler store loaded ({} platforms with gap fillers)", active.size());
    }

    /** Server thread, from the ticker: write if something changed. */
    public static void saveIfDirty() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
        }
        saveNow();
    }

    public static void saveNow() {
        String json;
        Path file;
        synchronized (LOCK) {
            if (path == null) {
                return;
            }
            file = path;
            JsonObject platforms = new JsonObject();
            SETTINGS.forEach((platformId, settings) -> {
                Set<String> blocks = BLOCKS.get(platformId);
                if (blocks == null || blocks.isEmpty()) {
                    return; // settings of a platform with no fillers left are not worth keeping
                }
                JsonObject p = new JsonObject();
                p.addProperty("extendMs", settings.extendMs());
                p.addProperty("retractMs", settings.retractMs());
                p.addProperty("minDwellMs", settings.minDwellMs());
                JsonArray list = new JsonArray();
                blocks.stream().sorted().forEach(list::add);
                p.add("blocks", list);
                platforms.add(Long.toString(platformId), p);
            });
            JsonObject root = new JsonObject();
            root.add("platforms", platforms);
            json = GSON.toJson(root);
            dirty = false;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", file, e);
        }
    }

    public static void unload() {
        saveNow();
        synchronized (LOCK) {
            SETTINGS.clear();
            BLOCKS.clear();
            path = null;
        }
        active = Map.of();
    }
}
