package com.stationannouncer.mtraddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side settings for the MTR dispatch addon, stored at
 * {@code config/station-announcer-addon.json}. One section per feature, each with
 * its own {@code enabled} flag plus tunables; delete the file to regenerate defaults.
 *
 * <p>{@link #get()} is a volatile read after the first load because it is called
 * from MTR's <em>simulator threads</em> (the hold-rule mixin runs there) — a
 * synchronized getter would serialize every vehicle departure behind one lock.</p>
 */
public class AddonServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile AddonServerConfig instance;

    /** Permission level required to edit addon settings through the GUIs (op level 2, like /announce). */
    public int editPermissionLevel = 2;

    /** Feature 1 — hold trains at a platform while a connecting train is approaching a watched platform. */
    public HoldRules holdRules = new HoldRules();

    /** Random door obstructions — a low chance that something gets stuck in the doors on departure. */
    public DoorObstruction doorObstruction = new DoorObstruction();

    /** Feature 2 — per-route dwell time overrides at platforms. */
    public DwellOverrides dwellOverrides = new DwellOverrides();

    /** Feature 3 — multi-sided, multi-door elevators. */
    public MultiDoorLifts multiDoorLifts = new MultiDoorLifts();

    /** Feature 5 — dynamic platform selection (platform groups + generation-time rotation). */
    public DynamicPlatforms dynamicPlatforms = new DynamicPlatforms();

    /** Dispatch web UI — real-time network/train map served from MTR's own webserver. */
    public Dispatch dispatch = new Dispatch();

    /** Timetable &amp; headway analytics — per-train arrival/departure logging and derived metrics. */
    public Analytics analytics = new Analytics();

    public static class HoldRules {
        /** Master switch; when false the startUp mixin no-ops with a single field read. */
        public boolean enabled = true;

        /** How long a watched platform's computed "next arrival" is reused before re-querying (ms). */
        public int holdArrivalCacheMillis = 500;

        /** Deadlock guard: a train is never held longer than this per stop, rule or not. */
        public int maxHoldSeconds = 120;
    }

    public static class DoorObstruction {
        /** Master switch; when false the startUp/riding mixin paths no-op with a single field read. */
        public boolean enabled = true;

        /** Chance (percent, 0–100) that a departure's door close gets something stuck in it. */
        public int chancePercent = 3;

        /** Shortest stuck time in seconds (the actual duration is uniform in [min, max]). */
        public int minSeconds = 2;

        /** Longest stuck time in seconds. */
        public int maxSeconds = 6;
    }

    public static class DwellOverrides {
        /**
         * Master switch; when false the Siding path-generation mixins no-op with a
         * single field read. Turning it off leaves already-baked overrides in MTR's
         * saved siding paths until the next depot regeneration (the path is baked —
         * same rule as editing an override).
         */
        public boolean enabled = true;
    }

    public static class MultiDoorLifts {
        /**
         * Master switch; when false the C2S save packet is ignored and the S2C
         * sync sends an empty map, so clients (whose render mixin only takes
         * over while at least one lift is configured) fall back to MTR's stock
         * lift rendering entirely. Read at startup like the other flags —
         * changing it needs a server restart plus client rejoin.
         */
        public boolean enabled = true;
    }

    public static class DynamicPlatforms {
        /**
         * Master switch for platform groups AND the generation-time platform
         * rotation; when false the Depot mixin hooks no-op with a single field
         * read, group saves are refused and the S2C sync sends an empty map.
         * Turning it off leaves the last generated paths (with their swapped
         * platforms) untouched until the next depot regeneration — the path is
         * baked, same rule as the dwell overrides. There is deliberately NO
         * {@code experimentalRuntimeSwitch} key: runtime platform switching was
         * not implemented (see PROGRESS.md, Feature 5), and a dead config key
         * would be dishonest.
         */
        public boolean enabled = true;
    }

    public static class Dispatch {
        /**
         * Master switch; when false the {@code Main}-constructor mixin bails out with a
         * single field read and the dispatch servlets are never registered on MTR's
         * webserver. Read once per server start (the webserver only exists between
         * server start and stop), so toggling needs a server restart.
         */
        public boolean enabled = true;

        /**
         * How often the SSE stream samples each subscribed dimension and pushes an
         * update, in milliseconds. Clamped to 100–5000. With zero connected clients
         * nothing is sampled at all.
         */
        public int updateMillis = 333;

        /** Maximum simultaneously connected SSE stream clients; excess connections get HTTP 503. */
        public int maxClients = 8;
    }

    public static class Analytics {
        /**
         * Master switch. When false NOTHING runs: the two {@code Vehicle} hooks bail on a
         * single field read, the writer thread is never started, no files are opened or
         * pruned, and {@code /dispatch/api/analytics} answers an empty (disabled) payload.
         * Read on every recorded event, so it takes effect on the next config reload.
         */
        public boolean enabled = true;

        /** Days of {@code analytics/YYYY-MM-DD.jsonl} kept; older files are deleted on start and on rotation. */
        public int retentionDays = 7;

        /** Longest interval between recomputations of the cached aggregate. Clamped 5–600. */
        public int aggregateSeconds = 30;

        /** A departure counts as on time when {@code |deviation| <=} this. Clamped 1–3600. */
        public int onTimeToleranceSeconds = 60;

        /**
         * A gap between consecutive trains shorter than this fraction of the reference
         * headway raises a bunching alert. Clamped 0.05–1.0.
         */
        public double bunchingFraction = 0.5;

        /** How much history the derived metrics cover, in minutes. Clamped 5–1440. */
        public int windowMinutes = 60;

        /** How often the writer thread drains the event queue to disk. Clamped 1–60. */
        public int flushSeconds = 5;

        /**
         * Bounded hand-off queue between the simulator threads and the writer thread.
         * When it is full events are DROPPED (counted, warned about at most once a
         * minute) rather than blocking a simulator tick. Clamped 256–262144.
         */
        public int queueCapacity = 8192;

        /** Hard cap on in-memory departures per dimension, so the window cannot grow without bound. Clamped 1000–500000. */
        public int maxWindowEvents = 20_000;

        /**
         * Write arrival events to the log as well as departures. Departures alone carry
         * every metric (dwell is measured from the matching arrival in memory), so
         * turning this off roughly halves the log size at no cost to the analytics.
         */
        public boolean logArrivals = true;
    }

    public static AddonServerConfig get() {
        AddonServerConfig config = instance;
        if (config == null) {
            synchronized (AddonServerConfig.class) {
                config = instance;
                if (config == null) {
                    config = load();
                    instance = config;
                }
            }
        }
        return config;
    }

    private static AddonServerConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("station-announcer-addon.json");
        try {
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) {
                    AddonServerConfig loaded = GSON.fromJson(reader, AddonServerConfig.class);
                    if (loaded != null) {
                        loaded.sanitize();
                        return loaded;
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, using defaults", path, e);
        }
        AddonServerConfig config = new AddonServerConfig();
        config.save(path);
        return config;
    }

    private void sanitize() {
        editPermissionLevel = Math.max(0, Math.min(4, editPermissionLevel));
        if (holdRules == null) {
            holdRules = new HoldRules();
        }
        if (doorObstruction == null) {
            doorObstruction = new DoorObstruction();
        }
        if (dwellOverrides == null) {
            dwellOverrides = new DwellOverrides();
        }
        if (multiDoorLifts == null) {
            multiDoorLifts = new MultiDoorLifts();
        }
        if (dynamicPlatforms == null) {
            dynamicPlatforms = new DynamicPlatforms();
        }
        if (dispatch == null) {
            dispatch = new Dispatch();
        }
        if (analytics == null) {
            analytics = new Analytics();
        }
        holdRules.holdArrivalCacheMillis = Math.max(50, Math.min(10_000, holdRules.holdArrivalCacheMillis));
        holdRules.maxHoldSeconds = Math.max(5, Math.min(3_600, holdRules.maxHoldSeconds));
        doorObstruction.chancePercent = Math.max(0, Math.min(100, doorObstruction.chancePercent));
        doorObstruction.minSeconds = Math.max(1, Math.min(120, doorObstruction.minSeconds));
        doorObstruction.maxSeconds = Math.max(doorObstruction.minSeconds, Math.min(120, doorObstruction.maxSeconds));
        dispatch.updateMillis = Math.max(100, Math.min(5_000, dispatch.updateMillis));
        dispatch.maxClients = Math.max(1, Math.min(64, dispatch.maxClients));
        analytics.retentionDays = Math.max(1, Math.min(365, analytics.retentionDays));
        analytics.aggregateSeconds = Math.max(5, Math.min(600, analytics.aggregateSeconds));
        analytics.onTimeToleranceSeconds = Math.max(1, Math.min(3_600, analytics.onTimeToleranceSeconds));
        analytics.bunchingFraction = Math.max(0.05, Math.min(1.0, analytics.bunchingFraction));
        analytics.windowMinutes = Math.max(5, Math.min(1_440, analytics.windowMinutes));
        analytics.flushSeconds = Math.max(1, Math.min(60, analytics.flushSeconds));
        analytics.queueCapacity = Math.max(256, Math.min(262_144, analytics.queueCapacity));
        analytics.maxWindowEvents = Math.max(1_000, Math.min(500_000, analytics.maxWindowEvents));
    }

    private void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
