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
 * <p>The {@code platformGroups} section belongs to a later feature agent; it is
 * preserved verbatim across load/save so that feature can formalize it without a
 * migration. {@code dwellOverrides} (Feature 2) and {@code liftDoors} (Feature 3)
 * are fully typed — {@code {"<platformId>": {"<routeId>": dwellMillis}}} and
 * {@code {"<liftId>": {"front": true, "back": false, "left": true, "right": false}}}
 * respectively — the same shapes the raw passthrough preserved, so no migration
 * was needed.</p>
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
    /** Feature 3: lift id → configured door sides. */
    private static final Map<Long, LiftDoorSides> liftDoors = new LinkedHashMap<>();
    /** Section owned by a later feature agent — carried through untouched. */
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
            liftDoors.clear();
            platformGroups = new JsonObject();
            try {
                if (Files.exists(path)) {
                    JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    readHoldRules(root.getAsJsonObject("holdRules"));
                    readDwellOverrides(root.getAsJsonObject("dwellOverrides"));
                    readLiftDoors(root.getAsJsonObject("liftDoors"));
                    platformGroups = objectOrEmpty(root, "platformGroups");
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not read {}, starting with empty addon data", path, e);
            }
        }
        publish();
        publishDwell();
        StationAnnouncer.LOGGER.info("Addon store loaded ({} hold rules, {} platforms with dwell overrides, {} lift door configs)",
                holdRuleCount(), dwellOverridePlatformCount(), liftDoorCount());
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

    // ------------------------------------------------ Feature 3: lift doors

    public static int liftDoorCount() {
        synchronized (LOCK) {
            return liftDoors.size();
        }
    }

    /** Server thread: a copy safe to iterate while building sync packets. */
    public static Map<Long, LiftDoorSides> liftDoorsView() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(liftDoors);
        }
    }

    /**
     * Server thread: set or clear one lift's door sides, then save. {@code null}
     * or an all-off value removes the entry (the lift falls back to stock MTR
     * behavior). No simulator snapshot is republished — lift door sides are pure
     * client presentation, nothing server-side ever reads them; the caller
     * rebroadcasts the S2C sync instead.
     */
    public static void setLiftDoors(long liftId, LiftDoorSides sides) {
        synchronized (LOCK) {
            if (sides == null || !sides.any()) {
                if (liftDoors.remove(liftId) == null) {
                    return;
                }
            } else {
                liftDoors.put(liftId, sides);
            }
        }
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

    private static void readLiftDoors(JsonObject liftDoorsJson) {
        if (liftDoorsJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : liftDoorsJson.entrySet()) {
            try {
                long liftId = Long.parseLong(entry.getKey());
                JsonObject sidesJson = entry.getValue().getAsJsonObject();
                LiftDoorSides sides = new LiftDoorSides(
                        booleanOrFalse(sidesJson, "front"),
                        booleanOrFalse(sidesJson, "back"),
                        booleanOrFalse(sidesJson, "left"),
                        booleanOrFalse(sidesJson, "right"));
                if (sides.any()) {
                    liftDoors.put(liftId, sides);
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed lift door config '{}'", entry.getKey(), e);
            }
        }
    }

    private static boolean booleanOrFalse(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.getAsBoolean();
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
            JsonObject liftDoorsJson = new JsonObject();
            liftDoors.forEach((liftId, sides) -> {
                JsonObject sidesJson = new JsonObject();
                sidesJson.addProperty("front", sides.front());
                sidesJson.addProperty("back", sides.back());
                sidesJson.addProperty("left", sides.left());
                sidesJson.addProperty("right", sides.right());
                liftDoorsJson.add(Long.toString(liftId), sidesJson);
            });
            root.add("liftDoors", liftDoorsJson);
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
