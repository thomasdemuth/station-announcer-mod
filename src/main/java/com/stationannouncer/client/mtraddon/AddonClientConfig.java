package com.stationannouncer.client.mtraddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-player addon settings, stored at
 * {@code config/station-announcer-addon-client.json}. Later feature agents add
 * their HUD toggles here.
 */
@Environment(EnvType.CLIENT)
public class AddonClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AddonClientConfig instance;

    /** Show the "Hold rules…" button on MTR's platform screen (it only appears with dashboard permission anyway). */
    public boolean showHoldRulesButton = true;

    /** Show the "Per-route dwell…" button on MTR's platform screen (same permission gating). */
    public boolean showRouteDwellButton = true;

    /** Show the "Door sides…" button on MTR's lift customization screen (same permission gating). */
    public boolean showLiftDoorSidesButton = true;

    /** Show the "Platform group…" button on MTR's platform screen (same permission gating). */
    public boolean showPlatformGroupButton = true;

    // ---- Feature 4: advanced manual driving HUD (all read on the client thread) ----

    /** Master toggle for the driving HUD overlay. */
    public boolean hudEnabled = true;

    /** Show upcoming speed-limit changes on the HUD. */
    public boolean hudShowSpeedLimits = true;

    /** Show upcoming signal blocks (and the obstruction cue) on the HUD. */
    public boolean hudShowSignals = true;

    /** Show the next stop (name, distance, ETA) on the HUD. */
    public boolean hudShowNextStop = true;

    /** Show the EARLY / ON TIME / LATE schedule indicator on the HUD. */
    public boolean hudShowOnTime = true;

    /** Show the door state row (OPEN / OPENING / CLOSING / CLOSED / OBSTRUCTED) on the HUD. */
    public boolean hudShowDoors = true;

    /** Lookahead recomputes per second (clamped 1–20; rendering itself only draws the cache). */
    public int hudUpdateHz = 4;

    /** How far ahead the path is scanned, in meters (clamped 100–20000). */
    public int hudLookaheadMeters = 2000;

    /** Minimum age before the on-time arrivals fetch is repeated, in milliseconds (min 250). */
    public int hudArrivalsCacheMillis = 1000;

    /** |deviation| at or below this many seconds reads ON TIME. */
    public int hudOnTimeThresholdSeconds = 15;

    /** Panel corner: top_left, top_right, bottom_left or bottom_right (MTR's speedometer owns bottom-right). */
    public String hudCorner = "top_left";

    /** Panel distance from the screen edges, in GUI pixels (clamped 0–64). */
    public int hudMargin = 6;

    /** Writes the current settings to disk (used by the HUD settings screen's Done button). */
    public static synchronized void persist() {
        get().save(configPath());
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("station-announcer-addon-client.json");
    }

    public static synchronized AddonClientConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static AddonClientConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("station-announcer-addon-client.json");
        try {
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) {
                    AddonClientConfig loaded = GSON.fromJson(reader, AddonClientConfig.class);
                    if (loaded != null) {
                        return loaded;
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, using defaults", path, e);
        }
        AddonClientConfig config = new AddonClientConfig();
        config.save(path);
        return config;
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
