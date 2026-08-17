package com.stationannouncer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side settings, stored at {@code config/station_announcer/server.json}.
 * Loaded lazily on first use; delete the file to regenerate defaults.
 */
public class ServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ServerConfig instance;

    /** Permission level required to run /announce (vanilla default for command blocks/ops is 2). */
    public int announcePermissionLevel = 2;

    /** Hard cap on announcement text length, applied when the GUI saves. */
    public int maxTextLength = 512;

    /** Farthest a Speaker may be from the PA Control Box it links to (blocks). */
    public int maxLinkDistance = 500;

    public static synchronized ServerConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ServerConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("server.json");
        try {
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) {
                    ServerConfig loaded = GSON.fromJson(reader, ServerConfig.class);
                    if (loaded != null) {
                        loaded.sanitize();
                        return loaded;
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, using defaults", path, e);
        }
        ServerConfig config = new ServerConfig();
        config.save(path);
        return config;
    }

    private void sanitize() {
        announcePermissionLevel = Math.max(0, Math.min(4, announcePermissionLevel));
        maxTextLength = Math.max(1, Math.min(512, maxTextLength));
        maxLinkDistance = Math.max(8, Math.min(1024, maxLinkDistance));
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
