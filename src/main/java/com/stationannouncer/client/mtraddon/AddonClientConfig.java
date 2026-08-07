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
