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

    /** Feature 2 — per-route dwell time overrides at platforms. */
    public DwellOverrides dwellOverrides = new DwellOverrides();

    /** Feature 3 — multi-sided, multi-door elevators. */
    public MultiDoorLifts multiDoorLifts = new MultiDoorLifts();

    /** Feature 5 — dynamic platform selection (platform groups + generation-time rotation). */
    public DynamicPlatforms dynamicPlatforms = new DynamicPlatforms();

    public static class HoldRules {
        /** Master switch; when false the startUp mixin no-ops with a single field read. */
        public boolean enabled = true;

        /** How long a watched platform's computed "next arrival" is reused before re-querying (ms). */
        public int holdArrivalCacheMillis = 500;

        /** Deadlock guard: a train is never held longer than this per stop, rule or not. */
        public int maxHoldSeconds = 120;
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
        if (dwellOverrides == null) {
            dwellOverrides = new DwellOverrides();
        }
        if (multiDoorLifts == null) {
            multiDoorLifts = new MultiDoorLifts();
        }
        if (dynamicPlatforms == null) {
            dynamicPlatforms = new DynamicPlatforms();
        }
        holdRules.holdArrivalCacheMillis = Math.max(50, Math.min(10_000, holdRules.holdArrivalCacheMillis));
        holdRules.maxHoldSeconds = Math.max(5, Math.min(3_600, holdRules.maxHoldSeconds));
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
