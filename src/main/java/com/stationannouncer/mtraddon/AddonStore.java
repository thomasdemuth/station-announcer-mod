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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-world persistent addon data at {@code <save>/station-announcer-addon/data.json}.
 * Loaded on SERVER_STARTED, saved debounced-async on change and synchronously on
 * SERVER_STOPPING. Human-editable JSON, keyed by MTR's own long ids (platform id,
 * lift id, route id). Those ids are random longs, globally unique in practice, so a
 * single global map spans all dimensions — the cross-dimension assumption every
 * addon feature shares.
 *
 * <p>Threading: all mutation happens on the server thread; simulator threads read
 * only the immutable snapshots republished through {@link AddonSnapshots}. The
 * save executor serializes under {@code LOCK} so it never sees a half-applied
 * edit, and file I/O never runs on a server or simulator tick.</p>
 *
 * <p>The {@code liftDoors} / {@code platformGroups} sections belong to later
 * feature agents; they are preserved verbatim across load/save so those features
 * can formalize them without a migration. {@code dwellOverrides} (Feature 2) is
 * fully typed: {@code {"<platformId>": {"<routeId>": dwellMillis}}} — the same
 * shape the raw passthrough preserved, so no migration was needed.</p>
 */
public final class AddonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final long SAVE_DELAY_MILLIS = 2_000;

    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Station Announcer Addon Store");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean SAVE_PENDING = new AtomicBoolean();

    private static Path dataPath;
    private static final Map<Long, AddonSnapshots.HoldRule> holdRules = new LinkedHashMap<>();
    /** Feature 2: platform id → (route id → dwell millis). */
    private static final Map<Long, LinkedHashMap<Long, Long>> dwellOverrides = new LinkedHashMap<>();
    /** Sections owned by later feature agents — carried through untouched. */
    private static JsonObject liftDoors = new JsonObject();
    private static JsonObject platformGroups = new JsonObject();

    private AddonStore() {
    }

    // ------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: read the world's data file and publish the first snapshots. */
    public static void load(MinecraftServer server) {
        Path path = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("data.json").normalize();
        synchronized (LOCK) {
            dataPath = path;
            holdRules.clear();
            dwellOverrides.clear();
            liftDoors = new JsonObject();
            platformGroups = new JsonObject();
            try {
                if (Files.exists(path)) {
                    JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    readHoldRules(root.getAsJsonObject("holdRules"));
                    readDwellOverrides(root.getAsJsonObject("dwellOverrides"));
                    liftDoors = objectOrEmpty(root, "liftDoors");
                    platformGroups = objectOrEmpty(root, "platformGroups");
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not read {}, starting with empty addon data", path, e);
            }
        }
        publish();
        publishDwell();
        StationAnnouncer.LOGGER.info("Addon store loaded ({} hold rules, {} platforms with dwell overrides)",
                holdRuleCount(), dwellOverridePlatformCount());
    }

    /** SERVER_STOPPING: write the current state right now, on the calling thread. */
    public static void flush() {
        // A debounced write may still fire afterwards; saveNow is idempotent, so
        // the worst case is one redundant write of identical content.
        SAVE_PENDING.set(false);
        saveNow();
    }

    // ------------------------------------------------------------ hold rules

    public static int holdRuleCount() {
        synchronized (LOCK) {
            return holdRules.size();
        }
    }

    /** Server thread: a copy safe to iterate while building sync packets. */
    public static Map<Long, AddonSnapshots.HoldRule> holdRulesView() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(holdRules);
        }
    }

    /** Server thread: create or replace the rule for a platform, then republish + save. */
    public static void setHoldRule(long platformId, long[] watched, int seconds) {
        synchronized (LOCK) {
            holdRules.put(platformId, new AddonSnapshots.HoldRule(watched, seconds));
        }
        publish();
        markDirty();
    }

    /** Server thread: remove a platform's rule, then republish + save. */
    public static void clearHoldRule(long platformId) {
        synchronized (LOCK) {
            if (holdRules.remove(platformId) == null) {
                return;
            }
        }
        publish();
        markDirty();
    }

    // -------------------------------------------- Feature 2: dwell overrides

    public static int dwellOverridePlatformCount() {
        synchronized (LOCK) {
            return dwellOverrides.size();
        }
    }

    /** Server thread: a deep copy safe to iterate while building sync packets. */
    public static Map<Long, LinkedHashMap<Long, Long>> dwellOverridesView() {
        synchronized (LOCK) {
            Map<Long, LinkedHashMap<Long, Long>> copy = new LinkedHashMap<>(dwellOverrides.size());
            dwellOverrides.forEach((platformId, byRoute) -> copy.put(platformId, new LinkedHashMap<>(byRoute)));
            return copy;
        }
    }

    /**
     * Server thread: replace ALL of one platform's route overrides (the GUI always
     * sends the full per-platform set), then republish + save. An empty map removes
     * the platform's entry entirely.
     */
    public static void setDwellOverrides(long platformId, Map<Long, Long> routeToMillis) {
        synchronized (LOCK) {
            if (routeToMillis.isEmpty()) {
                if (dwellOverrides.remove(platformId) == null) {
                    return;
                }
            } else {
                dwellOverrides.put(platformId, new LinkedHashMap<>(routeToMillis));
            }
        }
        publishDwell();
        markDirty();
    }

    // ------------------------------------------------------------- internals

    private static void publish() {
        AddonSnapshots.publishHoldRules(holdRulesView());
    }

    private static void publishDwell() {
        AddonSnapshots.publishDwellOverrides(dwellOverridesView());
    }

    private static void readDwellOverrides(JsonObject overridesJson) {
        if (overridesJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : overridesJson.entrySet()) {
            try {
                long platformId = Long.parseLong(entry.getKey());
                LinkedHashMap<Long, Long> byRoute = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> routeEntry : entry.getValue().getAsJsonObject().entrySet()) {
                    long millis = routeEntry.getValue().getAsLong();
                    if (millis > 0) {
                        byRoute.put(Long.parseLong(routeEntry.getKey()), millis);
                    }
                }
                if (!byRoute.isEmpty()) {
                    dwellOverrides.put(platformId, byRoute);
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed dwell override '{}'", entry.getKey(), e);
            }
        }
    }

    private static void readHoldRules(JsonObject rulesJson) {
        if (rulesJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : rulesJson.entrySet()) {
            try {
                long platformId = Long.parseLong(entry.getKey());
                JsonObject rule = entry.getValue().getAsJsonObject();
                JsonArray watchedJson = rule.getAsJsonArray("watched");
                long[] watched = new long[watchedJson == null ? 0 : watchedJson.size()];
                for (int i = 0; i < watched.length; i++) {
                    watched[i] = watchedJson.get(i).getAsLong();
                }
                int seconds = rule.get("seconds").getAsInt();
                if (watched.length > 0 && seconds > 0) {
                    holdRules.put(platformId, new AddonSnapshots.HoldRule(watched, seconds));
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed hold rule '{}'", entry.getKey(), e);
            }
        }
    }

    private static JsonObject objectOrEmpty(JsonObject root, String key) {
        JsonObject value = root.getAsJsonObject(key);
        return value == null ? new JsonObject() : value;
    }

    /** Debounce: many GUI edits in a burst produce one write, a couple of seconds later. */
    private static void markDirty() {
        if (SAVE_PENDING.compareAndSet(false, true)) {
            SAVE_EXECUTOR.schedule(() -> {
                SAVE_PENDING.set(false);
                saveNow();
            }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static void saveNow() {
        Path path;
        String json;
        synchronized (LOCK) {
            path = dataPath;
            if (path == null) {
                return;
            }
            JsonObject root = new JsonObject();
            JsonObject rulesJson = new JsonObject();
            holdRules.forEach((platformId, rule) -> {
                JsonObject ruleJson = new JsonObject();
                JsonArray watchedJson = new JsonArray(rule.watched().length);
                for (long watched : rule.watched()) {
                    watchedJson.add(watched);
                }
                ruleJson.add("watched", watchedJson);
                ruleJson.addProperty("seconds", rule.seconds());
                rulesJson.add(Long.toString(platformId), ruleJson);
            });
            root.add("holdRules", rulesJson);
            JsonObject overridesJson = new JsonObject();
            dwellOverrides.forEach((platformId, byRoute) -> {
                JsonObject byRouteJson = new JsonObject();
                byRoute.forEach((routeId, millis) -> byRouteJson.addProperty(Long.toString(routeId), millis));
                overridesJson.add(Long.toString(platformId), byRouteJson);
            });
            root.add("dwellOverrides", overridesJson);
            root.add("liftDoors", liftDoors);
            root.add("platformGroups", platformGroups);
            json = GSON.toJson(root);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json);
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
