package com.stationannouncer.client.mtr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.BridgeSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The player's own Bridge Creator presets, kept client-side in
 * {@code config/station_announcer/bridge_presets.json} (name → spec) so
 * they follow the player across worlds and servers.
 */
@Environment(EnvType.CLIENT)
public final class BridgeUserPresets {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, BridgeSpec> presets;

    private BridgeUserPresets() {
    }

    public static synchronized Map<String, BridgeSpec> all() {
        if (presets == null) {
            presets = load();
        }
        return presets;
    }

    public static synchronized void put(String name, BridgeSpec spec) {
        all().put(name, spec.copy());
        save();
    }

    public static synchronized void remove(String name) {
        all().remove(name);
        save();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("bridge_presets.json");
    }

    private static Map<String, BridgeSpec> load() {
        Path path = path();
        try {
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) {
                    Map<String, BridgeSpec> loaded = GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, BridgeSpec>>() {
                    }.getType());
                    if (loaded != null) {
                        loaded.values().forEach(BridgeSpec::clamp);
                        return new LinkedHashMap<>(loaded);
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read bridge presets", e);
        }
        return new LinkedHashMap<>();
    }

    private static void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(path)) {
                GSON.toJson(presets, writer);
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not save bridge presets", e);
        }
    }
}
