package com.stationannouncer.mtraddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.disruption.Disruption;
import com.stationannouncer.mtraddon.disruption.StopOverlayEngine;
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
    /** Accessibility: station id → step-free platform ids (empty = every platform). */
    private static final Map<Long, long[]> accessibility = new LinkedHashMap<>();

    /** Announcement templates: route id → template string (client presentation). */
    private static final Map<Long, String> announcementTemplates = new LinkedHashMap<>();

    /** Feature 2: platform id → (route id → dwell millis). */
    private static final Map<Long, LinkedHashMap<Long, Long>> dwellOverrides = new LinkedHashMap<>();
    /** Feature 3: lift id → configured door sides. */
    private static final Map<Long, LiftDoorSides> liftDoors = new LinkedHashMap<>();
    /** Feature 5: {@code "<routeId>:<stopIndex>"} → member platform ids. */
    private static final Map<String, long[]> platformGroups = new LinkedHashMap<>();
    /** Feature 6a: {@code "<routeId>:<stopIndex>"} → expiry epoch millis (0 = until turned off). */
    private static final Map<String, Long> disabledStops = new LinkedHashMap<>();
    /** Feature 6a: {@code "<routeId>:<stopIndex>"} → {platform id inserted after that stop, expiry millis}. */
    private static final Map<String, long[]> addedStops = new LinkedHashMap<>();
    /** Feature 6b: disruption id → record. */
    private static final Map<Long, Disruption> disruptions = new LinkedHashMap<>();
    /** Depot groups: group id → the group (name + member depot ids in offset order). */
    private static final Map<Long, DepotGroup> depotGroups = new LinkedHashMap<>();

    /**
     * Lock-free "is there anything to do" state for the once-a-second server
     * ticker: the soonest expiry of any temporary change or disruption
     * ({@link Long#MAX_VALUE} when nothing expires) and how many disruptions
     * exist at all. With neither, the ticker costs two field reads.
     */
    private static volatile long nextExpiryMillis = Long.MAX_VALUE;
    private static volatile int disruptionCount;
    /** Bumped on every disruption/stop-change edit so the broadcaster knows to rescan. */
    private static volatile int disruptionVersion;

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
            announcementTemplates.clear();
            accessibility.clear();
            liftDoors.clear();
            platformGroups.clear();
            disabledStops.clear();
            addedStops.clear();
            disruptions.clear();
            depotGroups.clear();
            DepotGroupEngine.clearRuntimeState();
            // A world without a data file must not inherit a previous world's
            // rotation/choice state (SERVER_STOPPED normally clears it, but not
            // after a crash).
            PlatformGroupEngine.clearRuntimeState();
            com.stationannouncer.mtraddon.disruption.StopOverlayEngine.clearRuntimeState();
            try {
                if (Files.exists(path)) {
                    JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    readHoldRules(root.getAsJsonObject("holdRules"));
                    readDwellOverrides(root.getAsJsonObject("dwellOverrides"));
                    readAnnouncementTemplates(root.getAsJsonObject("announcementTemplates"));
                    readAccessibility(root.getAsJsonObject("accessibility"));
                    readLiftDoors(root.getAsJsonObject("liftDoors"));
                    readPlatformGroups(root.getAsJsonObject("platformGroups"));
                    // Seed the engine's persisted runtime BEFORE publishGroups prunes
                    // against the freshly loaded group set.
                    readPlatformGroupRuntime(root.getAsJsonObject("platformGroupRuntime"));
                    readDisabledStops(root.getAsJsonObject("disabledStops"));
                    readAddedStops(root.getAsJsonObject("addedStops"));
                    readDisruptions(root.getAsJsonObject("disruptions"));
                    readDepotGroups(root.getAsJsonObject("depotGroups"));
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not read {}, starting with empty addon data", path, e);
            }
        }
        publish();
        publishDwell();
        publishGroups();
        publishStopChanges();
        publishDepotGroups();
        StationAnnouncer.LOGGER.info("Addon store loaded ({} hold rules, {} platforms with dwell overrides, {} lift door configs, {} platform groups, {} temporary stop changes, {} disruptions, {} depot groups)",
                holdRuleCount(), dwellOverridePlatformCount(), liftDoorCount(), platformGroupCount(),
                stopChangeCount(), disruptionCount, depotGroupCount());
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

    // ------------------------------------------------- accessibility

    /** Server thread: an immutable snapshot safe to iterate while building packets. */
    public static Map<Long, long[]> accessibilityView() {
        synchronized (LOCK) {
            Map<Long, long[]> copy = new LinkedHashMap<>(accessibility.size());
            accessibility.forEach((stationId, platforms) -> copy.put(stationId, platforms.clone()));
            return copy;
        }
    }

    /**
     * Server thread: mark a station step-free (with an optional platform subset —
     * empty means every platform) or clear it entirely. Presentation data: the
     * dispatch map and future system-map work read it; nothing simulates on it.
     */
    public static void setAccessibility(long stationId, boolean accessible, long[] platformIds) {
        synchronized (LOCK) {
            if (!accessible) {
                if (accessibility.remove(stationId) == null) {
                    return;
                }
            } else {
                accessibility.put(stationId, platformIds == null ? new long[0] : platformIds.clone());
            }
        }
        markDirty();
    }

    // ------------------------------------------ announcement templates

    /** Server thread: an immutable snapshot safe to iterate while building sync packets. */
    public static Map<Long, String> announcementTemplatesView() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(announcementTemplates);
        }
    }

    /**
     * Server thread: set or clear one route's announcement template, then save.
     * Null/blank removes the entry (the route falls back to MTR's stock
     * next-station announcement). Pure client presentation — nothing server-side
     * reads it; callers rebroadcast the S2C sync.
     */
    public static void setAnnouncementTemplate(long routeId, String template) {
        synchronized (LOCK) {
            if (template == null || template.isBlank()) {
                if (announcementTemplates.remove(routeId) == null) {
                    return;
                }
            } else {
                announcementTemplates.put(routeId, template);
            }
        }
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

    // ------------------------------------------------------- depot groups

    public static int depotGroupCount() {
        synchronized (LOCK) {
            return depotGroups.size();
        }
    }

    /** Server thread: a deep copy safe to iterate while building sync packets. */
    public static Map<Long, DepotGroup> depotGroupsView() {
        synchronized (LOCK) {
            Map<Long, DepotGroup> copy = new LinkedHashMap<>(depotGroups.size());
            depotGroups.forEach((id, group) ->
                    copy.put(id, new DepotGroup(group.id(), group.name(), group.depotIds().clone())));
            return copy;
        }
    }

    /**
     * Server thread: create or replace one depot group, then republish + save. A zero id
     * means "new" and gets the current time (nudged forward on collision so ids stay
     * unique), mirroring {@link #putDisruption}. Returns the stored record, or null when
     * the cap was hit.
     *
     * <p>Member ids are stored in the order given — that order IS the offset order, so the
     * GUI must send the list exactly as it displays it.</p>
     */
    public static DepotGroup putDepotGroup(DepotGroup group, int maxGroups) {
        DepotGroup stored;
        synchronized (LOCK) {
            long id = group.id();
            if (id == 0) {
                if (depotGroups.size() >= maxGroups) {
                    return null;
                }
                id = System.currentTimeMillis();
                while (depotGroups.containsKey(id)) {
                    id++;
                }
            } else if (!depotGroups.containsKey(id) && depotGroups.size() >= maxGroups) {
                return null;
            }
            stored = new DepotGroup(id, group.name(), group.depotIds().clone());
            depotGroups.put(id, stored);
        }
        publishDepotGroups();
        markDirty();
        return stored;
    }

    /** Server thread: delete a depot group. */
    public static void removeDepotGroup(long id) {
        synchronized (LOCK) {
            if (depotGroups.remove(id) == null) {
                return;
            }
        }
        publishDepotGroups();
        markDirty();
    }

    // -------------------------------- Feature 6a: temporary stop changes

    public static int stopChangeCount() {
        synchronized (LOCK) {
            return disabledStops.size() + addedStops.size();
        }
    }

    /** Server thread: {@code "<routeId>:<stopIndex>"} → expiry millis (0 = until turned off). */
    public static Map<String, Long> disabledStopsView() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(disabledStops);
        }
    }

    /** Server thread: {@code "<routeId>:<stopIndex>"} → {platformId, expiryMillis}. */
    public static Map<String, long[]> addedStopsView() {
        synchronized (LOCK) {
            Map<String, long[]> copy = new LinkedHashMap<>(addedStops.size());
            addedStops.forEach((key, value) -> copy.put(key, value.clone()));
            return copy;
        }
    }

    /**
     * Server thread: mark a route stop as temporarily skipped, or clear it.
     * {@code expiresAtMillis <= 0} means "until turned off".
     */
    public static void setDisabledStop(long routeId, int stopIndex, boolean disabled, long expiresAtMillis) {
        String key = StopOverlayEngine.stopKey(routeId, stopIndex);
        synchronized (LOCK) {
            if (disabled) {
                disabledStops.put(key, Math.max(0, expiresAtMillis));
            } else if (disabledStops.remove(key) == null) {
                return;
            }
            recomputeNextExpiry();
        }
        publishStopChanges();
        bumpVersion();
        markDirty();
    }

    /**
     * Server thread: insert an extra stop after route stop {@code stopIndex}, or
     * clear the insertion ({@code platformId == 0}).
     */
    public static void setAddedStop(long routeId, int stopIndex, long platformId, long expiresAtMillis) {
        String key = StopOverlayEngine.stopKey(routeId, stopIndex);
        synchronized (LOCK) {
            if (platformId != 0) {
                addedStops.put(key, new long[]{platformId, Math.max(0, expiresAtMillis)});
            } else if (addedStops.remove(key) == null) {
                return;
            }
            recomputeNextExpiry();
        }
        publishStopChanges();
        bumpVersion();
        markDirty();
    }

    // ------------------------------------------- Feature 6b: disruptions

    public static int disruptionCount() {
        return disruptionCount;
    }

    /** Bumped whenever a disruption or temporary stop change is edited or expires. */
    public static int disruptionVersion() {
        return disruptionVersion;
    }

    /** The soonest expiry of anything time-limited, or {@link Long#MAX_VALUE}. */
    public static long nextExpiryMillis() {
        return nextExpiryMillis;
    }

    /** Server thread: a copy safe to iterate while building sync packets. */
    public static Map<Long, Disruption> disruptionsView() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(disruptions);
        }
    }

    /**
     * Server thread: the disruptions currently in force, most severe first (ties
     * broken by id so the announcement cycle is stable). Empty list when the
     * feature is idle — {@link #disruptionCount()} short-circuits the caller
     * before this ever allocates.
     */
    public static java.util.List<Disruption> activeDisruptions(long now) {
        java.util.List<Disruption> active = new java.util.ArrayList<>();
        synchronized (LOCK) {
            for (Disruption disruption : disruptions.values()) {
                if (disruption.isActiveAt(now)) {
                    active.add(disruption);
                }
            }
        }
        active.sort((a, b) -> {
            int bySeverity = Integer.compare(b.severity().ordinal(), a.severity().ordinal());
            return bySeverity != 0 ? bySeverity : Long.compare(a.id(), b.id());
        });
        return active;
    }

    /**
     * Server thread: create or replace one disruption. A zero id means "new" and
     * gets the current time (nudged forward on collision so ids stay unique).
     * Returns the stored record, or null when the cap was hit.
     */
    public static Disruption putDisruption(Disruption disruption, int maxActive) {
        Disruption stored;
        synchronized (LOCK) {
            long id = disruption.id();
            if (id == 0) {
                if (disruptions.size() >= maxActive) {
                    return null;
                }
                id = System.currentTimeMillis();
                while (disruptions.containsKey(id)) {
                    id++;
                }
            } else if (!disruptions.containsKey(id) && disruptions.size() >= maxActive) {
                return null;
            }
            stored = new Disruption(id, disruption.routeIds(), disruption.message(), disruption.severity(),
                    disruption.startMillis(), disruption.endMillis(), disruption.active());
            disruptions.put(id, stored);
            disruptionCount = disruptions.size();
            recomputeNextExpiry();
        }
        bumpVersion();
        markDirty();
        return stored;
    }

    /** Server thread: delete a disruption. */
    public static void removeDisruption(long id) {
        synchronized (LOCK) {
            if (disruptions.remove(id) == null) {
                return;
            }
            disruptionCount = disruptions.size();
            recomputeNextExpiry();
        }
        bumpVersion();
        markDirty();
    }

    /**
     * Server thread, once a second: drop everything whose expiry has passed.
     * Costs one volatile read while nothing is time-limited. Returns true when
     * something was removed, so the caller can rebroadcast the syncs.
     */
    public static boolean expireDue(long now) {
        if (now < nextExpiryMillis) {
            return false;
        }
        boolean stopsChanged = false;
        boolean disruptionsChanged = false;
        synchronized (LOCK) {
            for (var iterator = disabledStops.entrySet().iterator(); iterator.hasNext(); ) {
                Long expiry = iterator.next().getValue();
                if (expiry != null && expiry > 0 && now >= expiry) {
                    iterator.remove();
                    stopsChanged = true;
                }
            }
            for (var iterator = addedStops.entrySet().iterator(); iterator.hasNext(); ) {
                long[] value = iterator.next().getValue();
                if (value != null && value.length > 1 && value[1] > 0 && now >= value[1]) {
                    iterator.remove();
                    stopsChanged = true;
                }
            }
            for (var iterator = disruptions.entrySet().iterator(); iterator.hasNext(); ) {
                if (iterator.next().getValue().hasExpired(now)) {
                    iterator.remove();
                    disruptionsChanged = true;
                }
            }
            if (disruptionsChanged) {
                disruptionCount = disruptions.size();
            }
            recomputeNextExpiry();
        }
        if (stopsChanged) {
            publishStopChanges();
        }
        if (stopsChanged || disruptionsChanged) {
            bumpVersion();
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * Call inside {@code LOCK}. Only deadlines still in the FUTURE count — a
     * start time that has already passed must not pin the ticker awake forever.
     */
    private static void recomputeNextExpiry() {
        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        for (Long expiry : disabledStops.values()) {
            if (expiry != null && expiry > now) {
                soonest = Math.min(soonest, expiry);
            }
        }
        for (long[] value : addedStops.values()) {
            if (value != null && value.length > 1 && value[1] > now) {
                soonest = Math.min(soonest, value[1]);
            }
        }
        for (Disruption disruption : disruptions.values()) {
            if (disruption.endMillis() > now) {
                soonest = Math.min(soonest, disruption.endMillis());
            }
            // A not-yet-started disruption also needs a wake-up so it starts announcing.
            if (disruption.startMillis() > now) {
                soonest = Math.min(soonest, disruption.startMillis());
            }
        }
        nextExpiryMillis = soonest;
    }

    private static void bumpVersion() {
        disruptionVersion++;
    }

    private static void publishStopChanges() {
        AddonSnapshots.publishStopOverlays(disabledStopsView(), addedStopsView());
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

    private static void publishDepotGroups() {
        AddonSnapshots.publishDepotGroups(depotGroupsView());
    }

    private static void readDepotGroups(JsonObject json) {
        if (json == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            try {
                long id = Long.parseLong(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                String name = value.has("name") ? value.get("name").getAsString() : "";
                JsonArray membersJson = value.getAsJsonArray("depots");
                long[] members = new long[membersJson == null ? 0 : membersJson.size()];
                int size = 0;
                for (int i = 0; i < members.length; i++) {
                    long depotId = membersJson.get(i).getAsLong();
                    if (depotId != 0 && !contains(members, size, depotId)) {
                        members[size++] = depotId;
                    }
                }
                if (size > 0) {
                    depotGroups.put(id, new DepotGroup(id, name, java.util.Arrays.copyOf(members, size)));
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed depot group '{}'", entry.getKey(), e);
            }
        }
    }

    private static void readAccessibility(JsonObject accessibilityJson) {
        if (accessibilityJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : accessibilityJson.entrySet()) {
            try {
                JsonArray platformsJson = entry.getValue().getAsJsonArray();
                long[] platforms = new long[platformsJson.size()];
                for (int i = 0; i < platforms.length; i++) {
                    platforms[i] = platformsJson.get(i).getAsLong();
                }
                accessibility.put(Long.parseLong(entry.getKey()), platforms);
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed accessibility entry '{}'", entry.getKey(), e);
            }
        }
    }

    private static void readAnnouncementTemplates(JsonObject templatesJson) {
        if (templatesJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : templatesJson.entrySet()) {
            try {
                String template = entry.getValue().getAsString();
                if (!template.isBlank()) {
                    announcementTemplates.put(Long.parseLong(entry.getKey()), template);
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed announcement template '{}'", entry.getKey(), e);
            }
        }
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
        Map<String, Long> choiceByDepot = new LinkedHashMap<>();
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
                // "<depotId>|<routeId>:<stopIndex>" → chosen platform id. Absent in files
                // written before per-depot rotation; the engine then falls back to the
                // shared "choice" entry, which is exactly the old behaviour.
                JsonObject byDepotJson = runtimeJson.getAsJsonObject("choiceByDepot");
                if (byDepotJson != null) {
                    for (Map.Entry<String, JsonElement> entry : byDepotJson.entrySet()) {
                        if (PlatformGroupEngine.parseDepotChoiceKey(entry.getKey()) != null) {
                            choiceByDepot.put(entry.getKey(), entry.getValue().getAsLong());
                        }
                    }
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed platform group runtime state", e);
            }
        }
        PlatformGroupEngine.seedRuntime(rotation, choice, choiceByDepot);
    }

    private static void readDisabledStops(JsonObject json) {
        if (json == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            try {
                if (StopOverlayEngine.parseStopKey(entry.getKey()) == null) {
                    StationAnnouncer.LOGGER.warn("Skipping malformed disabled stop key '{}'", entry.getKey());
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                long expiry = value.has("expiresAtMillis") ? value.get("expiresAtMillis").getAsLong() : 0;
                disabledStops.put(entry.getKey(), Math.max(0, expiry));
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed disabled stop '{}'", entry.getKey(), e);
            }
        }
    }

    private static void readAddedStops(JsonObject json) {
        if (json == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            try {
                if (StopOverlayEngine.parseStopKey(entry.getKey()) == null) {
                    StationAnnouncer.LOGGER.warn("Skipping malformed added stop key '{}'", entry.getKey());
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                long platformId = value.has("platformId") ? value.get("platformId").getAsLong() : 0;
                long expiry = value.has("expiresAtMillis") ? value.get("expiresAtMillis").getAsLong() : 0;
                if (platformId != 0) {
                    addedStops.put(entry.getKey(), new long[]{platformId, Math.max(0, expiry)});
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed added stop '{}'", entry.getKey(), e);
            }
        }
    }

    private static void readDisruptions(JsonObject json) {
        if (json == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            try {
                long id = Long.parseLong(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                JsonArray routesJson = value.getAsJsonArray("routeIds");
                long[] routeIds = new long[routesJson == null ? 0 : routesJson.size()];
                for (int i = 0; i < routeIds.length; i++) {
                    routeIds[i] = routesJson.get(i).getAsLong();
                }
                String message = value.has("message") ? value.get("message").getAsString() : "";
                Disruption.Severity severity = Disruption.Severity.fromName(
                        value.has("severity") ? value.get("severity").getAsString() : null);
                long start = value.has("startMillis") ? value.get("startMillis").getAsLong() : 0;
                long end = value.has("endMillis") ? value.get("endMillis").getAsLong() : 0;
                boolean active = !value.has("active") || value.get("active").getAsBoolean();
                if (!message.isBlank()) {
                    disruptions.put(id, new Disruption(id, routeIds, message, severity, start, end, active));
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Skipping malformed disruption '{}'", entry.getKey(), e);
            }
        }
        disruptionCount = disruptions.size();
        recomputeNextExpiry();
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
            JsonObject templatesJson = new JsonObject();
            announcementTemplates.forEach((routeId, template) ->
                    templatesJson.addProperty(Long.toString(routeId), template));
            root.add("announcementTemplates", templatesJson);
            JsonObject accessibilityJson = new JsonObject();
            accessibility.forEach((stationId, platforms) -> {
                JsonArray platformsJson = new JsonArray(platforms.length);
                for (long platform : platforms) {
                    platformsJson.add(platform);
                }
                accessibilityJson.add(Long.toString(stationId), platformsJson);
            });
            root.add("accessibility", accessibilityJson);
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
            // Per-DEPOT applied choices (added with per-depot rotation, 2026-08-08):
            // keys are "<depotId>|<routeId>:<stopIndex>". Without these, two depots
            // sharing a route — which now deliberately pick DIFFERENT members — would
            // re-apply each other's choice to their route cache after a data sync.
            JsonObject choiceByDepotJson = new JsonObject();
            PlatformGroupEngine.appliedChoiceByDepotSnapshot().forEach(choiceByDepotJson::addProperty);
            runtimeJson.add("choiceByDepot", choiceByDepotJson);
            root.add("platformGroupRuntime", runtimeJson);
            // Feature 6a — temporary stop changes (runtime overlay; the saved
            // routes themselves are never modified).
            JsonObject disabledJson = new JsonObject();
            disabledStops.forEach((key, expiry) -> {
                JsonObject value = new JsonObject();
                value.addProperty("expiresAtMillis", expiry == null ? 0 : expiry);
                disabledJson.add(key, value);
            });
            root.add("disabledStops", disabledJson);
            JsonObject addedJson = new JsonObject();
            addedStops.forEach((key, value) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("platformId", value[0]);
                entry.addProperty("expiresAtMillis", value.length > 1 ? value[1] : 0);
                addedJson.add(key, entry);
            });
            root.add("addedStops", addedJson);
            // Feature 6b — service disruptions.
            JsonObject disruptionsJson = new JsonObject();
            disruptions.forEach((id, disruption) -> {
                JsonObject entry = new JsonObject();
                JsonArray routesJson = new JsonArray(disruption.routeIds().length);
                for (long routeId : disruption.routeIds()) {
                    routesJson.add(routeId);
                }
                entry.add("routeIds", routesJson);
                entry.addProperty("message", disruption.message());
                entry.addProperty("severity", disruption.severity().name());
                entry.addProperty("startMillis", disruption.startMillis());
                entry.addProperty("endMillis", disruption.endMillis());
                entry.addProperty("active", disruption.active());
                disruptionsJson.add(Long.toString(id), entry);
            });
            root.add("disruptions", disruptionsJson);
            // Depot groups — the "these depots must not dispatch together" sets.
            JsonObject depotGroupsJson = new JsonObject();
            depotGroups.forEach((id, group) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", group.name());
                JsonArray membersJson = new JsonArray(group.depotIds().length);
                for (long depotId : group.depotIds()) {
                    membersJson.add(depotId);
                }
                entry.add("depots", membersJson);
                depotGroupsJson.add(Long.toString(id), entry);
            });
            root.add("depotGroups", depotGroupsJson);
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
