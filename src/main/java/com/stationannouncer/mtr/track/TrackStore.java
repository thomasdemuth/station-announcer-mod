package com.stationannouncer.mtr.track;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.track.TrackProfile;
import com.stationannouncer.track.TrackRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saved custom track profiles: {@code <save>/station-announcer-addon/track_profiles.json} =
 * {@code {dimension id: {rail key: TrackProfile}}}. Dimension ids are MTR's world ids
 * ({@code "namespace/path"}, = {@code Simulator.dimension}).
 *
 * <p>The store is the server's source of truth; every change is mirrored into
 * {@link TrackRegistry}'s SERVER side (what the simulator's RailMaths read) and written at once
 * (atomic temp-file + move — edits are rare and the file is small).</p>
 *
 * <p><b>Threads:</b> mutated on the server thread only (packet handlers, prune results). Loaded at
 * SERVER_STARTING — before MTR's SERVER_STARTED builds the simulators — so rails resolve their custom
 * shape the moment they are loaded.</p>
 */
public final class TrackStore {
    private static final Map<String, Map<String, TrackProfile>> PROFILES = new HashMap<>();
    private static Path path;

    private TrackStore() {
    }

    public static void load(MinecraftServer server) {
        final Path file = server.getSavePath(WorldSavePath.ROOT)
            .resolve("station-announcer-addon").resolve("track_profiles.json").normalize();
        PROFILES.clear();
        TrackRegistry.clearServer();
        TrackRailMathState.clearCache(false);
        path = file;
        int count = 0;
        try {
            if (Files.exists(file)) {
                final JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (final Map.Entry<String, JsonElement> dimension : root.entrySet()) {
                    if (!dimension.getValue().isJsonObject()) {
                        continue;
                    }
                    final Map<String, TrackProfile> map = new HashMap<>();
                    for (final Map.Entry<String, JsonElement> entry : dimension.getValue().getAsJsonObject().entrySet()) {
                        final TrackProfile profile = TrackJson.parse(entry.getValue());
                        if (profile != null) {
                            map.put(profile.key(), profile);
                        }
                    }
                    if (!map.isEmpty()) {
                        PROFILES.put(dimension.getKey(), map);
                        TrackRegistry.setServerDimension(dimension.getKey(), map);
                        count += map.size();
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, starting without custom track profiles", file, e);
        }
        StationAnnouncer.LOGGER.info("Track profile store loaded ({} custom rails)", count);
    }

    /** Server stop: write and forget everything. */
    public static void unload() {
        PROFILES.clear();
        path = null;
        TrackRegistry.clearServer();
        TrackRailMathState.clearCache(false);
    }

    public static Map<String, TrackProfile> dimension(String dimension) {
        final Map<String, TrackProfile> map = PROFILES.get(dimension);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    public static Iterable<String> dimensions() {
        return Map.copyOf(PROFILES).keySet();
    }

    public static TrackProfile get(String dimension, String key) {
        final Map<String, TrackProfile> map = PROFILES.get(dimension);
        return map == null ? null : map.get(key);
    }

    public static void put(String dimension, TrackProfile profile) {
        PROFILES.computeIfAbsent(dimension, d -> new HashMap<>()).put(profile.key(), profile);
        TrackRegistry.putServer(dimension, profile);
        save();
    }

    /** @return true if something was removed */
    public static boolean remove(String dimension, String key) {
        final Map<String, TrackProfile> map = PROFILES.get(dimension);
        if (map == null || map.remove(key) == null) {
            return false;
        }
        if (map.isEmpty()) {
            PROFILES.remove(dimension);
        }
        TrackRegistry.removeServer(dimension, key);
        save();
        return true;
    }

    private static void save() {
        if (path == null) {
            return;
        }
        final Map<String, Map<String, TrackProfile>> sorted = new TreeMap<>();
        PROFILES.forEach((dimension, map) -> sorted.put(dimension, new TreeMap<>(map)));
        final Map<String, Object> root = new LinkedHashMap<>(sorted);
        final String json = TrackJson.PRETTY.toJson(root);
        final Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temp, json);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
