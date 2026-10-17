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

    /**
     * Replace MTR's platform screen with ours (number, dwell per route, hold rule, platform
     * groups, schedule check). Off = MTR's screen with a "Platform settings…" button.
     */
    public boolean replacePlatformScreen = true;

    /** Replace MTR's Edit Route screen with ours. Off = MTR's screen with a "Route settings…" button. */
    public boolean replaceRouteScreen = true;

    /** Group the dashboard's Routes list into line folders (off = MTR's flat list). */
    public boolean dashboardFolders = true;

    /** The dashboard rail's recently used tools, newest first (ids). */
    public java.util.List<String> recentDashboardTools = new java.util.ArrayList<>();

    /** Show the "Door sides…" button on MTR's lift customization screen (same permission gating). */
    public boolean showLiftDoorSidesButton = true;

    /** Show the "Disruptions" button on MTR's dashboard (same permission gating). */
    public boolean showDisruptionsButton = true;

    /** Show the "Tools…" button on MTR's dashboard — duplicate a line, depot groups (same permission gating). */
    public boolean showToolsButton = true;

    // ---- Feature 4: advanced manual driving HUD (all read on the client thread) ----

    /** Master toggle for the driving HUD overlay. */
    public boolean hudEnabled = true;

    /**
     * Driving HUD style: {@code "cab"} = the cab HUD (signal + speed bottom left, the run bottom
     * right, destination top left; MANUAL_DRIVING_PLAN.md §9), {@code "classic"} = the original
     * text panel. The {@code hudShow*} toggles below apply to the classic panel.
     */
    public String hudStyle = "cab";

    /** Skip the startup ritual (key in, battery, brake test) — the train starts up at once. */
    public boolean crewSkipStartup = false;

    /** Driver snaps to the front of the leading car while driving (Thomas: "snap to the front cab"). */
    public boolean cabSeatEnabled = true;
    /** Blocks behind the frontmost floor edge (min 0.35). */
    public double cabSeatInset = 0.6;
    /** Blocks right of the car's centre line in the direction of travel. */
    public double cabSeatLateral = 0.0;
    /** Camera up/down while seated, clamped ±1.5. */
    public double cabSeatEyeOffset = 0.0;
    /** Per train type (leading car's vehicle id) overrides of the seat placement. */
    public java.util.Map<String, com.stationannouncer.client.mtraddon.cab.CabSeat.Placement> cabSeatTrains = new java.util.HashMap<>();
    /** Hold the lean key to look out of the cab window along the train. */
    public boolean cabLeanEnabled = true;
    public double cabLeanMax = 1.8;

    /** Cab HUD: speed in mph instead of km/h. */
    public boolean cabMph = false;

    /** Cab HUD panels expanded (true) or collapsed — the "HUD detail" key cycles them. */
    public boolean cabDestOpen = false;
    public boolean cabDriveOpen = true;
    public boolean cabRunOpen = false;

    /** Cab HUD size multiplier in GUI pixels (1 = native GUI scale). */
    public int cabScale = 1;

    /** Cosmetic passengers on the platforms of the train you drive or guard (client-only). */
    public boolean cabPassengers = true;
    /** Hard cap on cosmetic passengers (0 = off). */
    public int cabPassengersMax = 40;

    public boolean isClassicHud() {
        return "classic".equals(hudStyle);
    }

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

    // ---- Journey directions (client half of in-game navigation) ----

    /** Master toggle for the journey card. */
    public boolean navHudEnabled = true;

    /**
     * Journey card corner: top_left, top_right, bottom_left or bottom_right.
     * Defaults away from {@link #hudCorner} so the two panels do not stack by
     * default; when they do share a corner the card offsets itself.
     */
    public String navHudCorner = "top_right";

    /**
     * Keep an offline copy of Map+ for each world in {@code station_announcer/offline_maps}
     * (refreshed every 10 minutes while connected) — it opens without the game running.
     */
    public boolean offlineMapCopies = true;

    /** Draw the in-world marker at the current navigation target. */
    public boolean navWaypointEnabled = true;

    /** Print the itinerary to chat when a journey arrives. */
    public boolean navChatEnabled = true;

    /** Title/subtitle alerts for "get off next stop" and "departs in 1 min". */
    public boolean navAlertsEnabled = true;

    /** Play a chime alongside those alerts. */
    public boolean navSoundEnabled = true;

    // ---- Train crew (driver / guard, buzzer, horn, vigilance) ----

    /** Show the small crew panel (role, ready lamps, buzz codes, vigilance) while holding a crew role. */
    public boolean crewPanelEnabled = true;

    /** Ask the server for periodic vigilance checks while driving (the server may clamp or disable it). */
    public boolean crewVigilanceEnabled = true;

    /** Seconds between vigilance checks (clamped to CrewProtocol.MIN/MAX_VIGIL_SECONDS when sent). */
    public int crewVigilanceSeconds = 45;

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
