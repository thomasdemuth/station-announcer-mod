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
 * <p>{@code dwellOverrides} (Feature 2), {@code liftDoors} (Feature 3) and
 * {@code platformGroups} (Feature 5) are fully typed —
 * {@code {"<platformId>": {"<routeId>": dwellMillis}}},
 * {@code {"<liftId>": {"front": true, "back": false, "left": true, "right": false}}}
 * and {@code {"<routeId>:<stopIndex>": [platformIds]}} respectively — the same
 * shapes the original raw passthrough preserved, so no migration was needed.</p>
 *
 * <p>Feature 5 also persists a {@code platformGroupRuntime} section (per-group
 * rotation counters and the platform each group last resolved to). Unlike every
 * other section it is WRITTEN by the simulator thread's
 * {@link PlatformGroupEngine} (into its own concurrent maps, serialized here at
 * save time) — the engine only pokes the thread-safe debounced dirty flag via
 * {@link #requestSaveFromEngine()}, never the store maps.</p>
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
    /** Feature 5: {@code "<routeId>:<stopIndex>"} → member platform ids. */
    private static final Map<String, long[]> platformGroups = new LinkedHashMap<>();

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
            platformGroups.clear();
            // A world without a data file must not inherit a previous world's
            // rotation/choice state (SERVER_STOPPED normally clears it, but not
            // after a crash).
            PlatformGroupEngine.clearRuntimeState();
            try {
                if (Files.exists(path)) {
                    JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    readHoldRules(root.getAsJsonObject("holdRules"));
                    readDwellOverrides(root.getAsJsonObject("dwellOverrides"));
                    readLiftDoors(root.getAsJsonObject("liftDoors"));
                    readPlatformGroups(root.getAsJsonObject("platformGroups"));
                    // Seed the engine's persisted runtime BEFORE publishGroups prunes
                    // against the freshly loaded group set.
                    readPlatformGroupRuntime(root.getAsJsonObject("platformGroupRuntime"));
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not read {}, starting with empty addon data", path, e);
            }
        }
        publish();
        publishDwell();
        publishGroups();
        StationAnnouncer.LOGGER.info("Addon store loaded ({} hold rules, {} platforms with dwell overrides, {} lift door configs, {} platform groups)",
                holdRuleCount(), dwellOverridePlatformCount(), liftDoorCount(), platformGroupCount());
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
    public static void setHoldRule(long platformId, long[] watched, int seconds, int transferSeconds) {
        synchronized (LOCK) {
            holdRules.put(platformId, new AddonSnapshots.HoldRule(watched, seconds, transferSeconds));
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

    // -------------------------------------------- Feature 5: platform groups

    public static int platformGroupCount() {
        synchronized (LOCK) {
            return platformGroups.size();
        }
    }

    /** Server thread: a deep copy safe to iterate while building sync packets. */
    public static Map<String, long[]> platformGroupsView() {
        synchronized (LOCK) {
            Map<String, long[]> copy = new LinkedHashMap<>(platformGroups.size());
            platformGroups.forEach((key, members) -> copy.put(key, members.clone()));
            return copy;
        }
    }

    /**
     * Server thread: set or clear one {@code (route, stopIndex)} group, then
     * republish + save. {@code null} or an empty member list removes the entry.
     */
    public static void setPlatformGroup(long routeId, int stopIndex, long[] members) {
        String key = PlatformGroupEngine.groupKey(routeId, stopIndex);
        synchronized (LOCK) {
            if (members == null || members.length == 0) {
                if (platformGroups.remove(key) == null) {
                    return;
                }
            } else {
                platformGroups.put(key, members.clone());
            }
        }
        publishGroups();
        markDirty();
    }

    /**
     * Thread-safe entry point for {@link PlatformGroupEngine} (SIMULATOR thread)
     * to persist its rotation/choice runtime after a depot generation. Only the
     * debounced dirty flag is touched (AtomicBoolean + executor); the engine's
     * concurrent maps are read later, on the save executor, inside
     * {@link #saveNow()}.
     */
    public static void requestSaveFromEngine() {
        markDirty();
    }

    // ------------------------------------------------------------- internals

    private static void publish() {
        AddonSnapshots.publishHoldRules(holdRulesView());
    }

    private static void publishDwell() {
        AddonSnapshots.publishDwellOverrides(dwellOverridesView());
    }

    private static void publishGroups() {
        AddonSnapshots.publishPlatformGroups(platformGroupsView());
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
                // Pre-transfer-window saves have no transferSeconds field: default 0 = old behavior.
                int transferSeconds = rule.has("transferSeconds")
                        ? Math.max(0, rule.get("transferSeconds").getAsInt())
                        : 0;
                if (watched.length > 0 && seconds > 0) {
                    holdRules.put(platformId, new AddonSnapshots.HoldRule(watched, seconds, transferSeconds));
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

    private static void readPlatformGroups(JsonObject groupsJson) {
        if (groupsJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : groupsJson.entrySet()) {
            try {
                if (PlatformGroupEngine.parseGroupKey(entry.getKey()) == null) {
                    StationAnnouncer.LOGGER.warn("Skipping malformed platform group key '{}'", entry.getKey());
                    continue;
                }
                JsonArray membersJson = entry.getValue().getAsJsonArray();
                long[] members = new long[Math.min(membersJson.size(), AddonNetworking.MAX_GROUP_SIZE)];
                int size = 0;
                for (int i = 0; i < membersJson.size() && size < members.length; i++) {
                    long id = membersJson.get(i).getAsLong();
                    if (id != 0 && !contains(members, size, id)) {
                        members[size++] = id;
                    }
                }
                if (size > 0) {
                    platformGroups.put(entry.getKey(), java.util.Arrays.copyOf(members, size));
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed platform group '{}'", entry.getKey(), e);
            }
        }
    }

    /** Seeds {@link PlatformGroupEngine}'s persisted rotation counters + applied choices. */
    private static void readPlatformGroupRuntime(JsonObject runtimeJson) {
        Map<String, Integer> rotation = new LinkedHashMap<>();
        Map<String, Long> choice = new LinkedHashMap<>();
        if (runtimeJson != null) {
            try {
                JsonObject rotationJson = runtimeJson.getAsJsonObject("rotation");
                if (rotationJson != null) {
                    for (Map.Entry<String, JsonElement> entry : rotationJson.entrySet()) {
                        if (PlatformGroupEngine.parseGroupKey(entry.getKey()) != null) {
                            rotation.put(entry.getKey(), entry.getValue().getAsInt());
                        }
                    }
                }
                JsonObject choiceJson = runtimeJson.getAsJsonObject("choice");
                if (choiceJson != null) {
                    for (Map.Entry<String, JsonElement> entry : choiceJson.entrySet()) {
                        if (PlatformGroupEngine.parseGroupKey(entry.getKey()) != null) {
                            choice.put(entry.getKey(), entry.getValue().getAsLong());
                        }
                    }
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed platform group runtime state", e);
            }
        }
        PlatformGroupEngine.seedRuntime(rotation, choice);
    }

    private static boolean contains(long[] array, int size, long value) {
        for (int i = 0; i < size; i++) {
            if (array[i] == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean booleanOrFalse(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.getAsBoolean();
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
                ruleJson.addProperty("transferSeconds", rule.transferSeconds());
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
            JsonObject groupsJson = new JsonObject();
            platformGroups.forEach((key, members) -> {
                JsonArray membersJson = new JsonArray(members.length);
                for (long member : members) {
                    membersJson.add(member);
                }
                groupsJson.add(key, membersJson);
            });
            root.add("platformGroups", groupsJson);
            // Feature 5 runtime state — read from the engine's concurrent maps
            // (weakly consistent iteration is fine; the next save catches stragglers).
            JsonObject runtimeJson = new JsonObject();
            JsonObject rotationJson = new JsonObject();
            PlatformGroupEngine.rotationSnapshot().forEach(rotationJson::addProperty);
            runtimeJson.add("rotation", rotationJson);
            JsonObject choiceJson = new JsonObject();
            PlatformGroupEngine.appliedChoiceSnapshot().forEach(choiceJson::addProperty);
            runtimeJson.add("choice", choiceJson);
            root.add("platformGroupRuntime", runtimeJson);
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
