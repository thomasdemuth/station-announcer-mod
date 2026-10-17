package com.stationannouncer.client.mtraddon;

import com.stationannouncer.StationAnnouncer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps an up-to-date OFFLINE COPY of Map+ for every world you play, so the map opens
 * even when the game or the server is not running: a minute after joining, then every
 * {@link #EVERY_TICKS} ticks while connected, the server's {@code /dispatch/api/offline}
 * page (one self-contained HTML file) is downloaded on a background thread into
 * {@code <game dir>/station_announcer/offline_maps/}. Opened from there it shows the saved
 * map and goes live by itself whenever that server answers.
 *
 * <p>Off with {@code offlineMapCopies} in the client config.</p>
 */
@Environment(EnvType.CLIENT)
public final class OfflineMapSaver {
    private static final int FIRST_TICKS = 60 * 20;
    private static final int EVERY_TICKS = 10 * 60 * 20;
    private static int countdown = -1;
    private static volatile boolean busy;

    private OfflineMapSaver() {
    }

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("station_announcer").resolve("offline_maps");
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> countdown = FIRST_TICKS);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> countdown = -1);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (countdown < 0 || --countdown > 0) {
                return;
            }
            countdown = EVERY_TICKS;
            if (!AddonClientConfig.get().offlineMapCopies || !ClientDispatchInfo.isAvailable() || busy) {
                return;
            }
            String name = worldLabel(client);
            String url = ClientDispatchInfo.url() + "api/offline";
            busy = true;
            Thread thread = new Thread(() -> {
                try {
                    save(url, folder().resolve(fileName(name)));
                } finally {
                    busy = false;
                }
            }, "Station Announcer offline map");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        });
    }

    /** "Singleplayer - My World" or the server list's name for the server. */
    private static String worldLabel(MinecraftClient client) {
        try {
            if (client.isIntegratedServerRunning() && client.getServer() != null) {
                return "Singleplayer - " + client.getServer().getSaveProperties().getLevelName();
            }
            ServerInfo info = client.getCurrentServerEntry();
            if (info != null) {
                return info.name == null || info.name.isBlank() ? info.address : info.name;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "World";
    }

    private static String fileName(String label) {
        String clean = label.replaceAll("[^A-Za-z0-9 _.()-]", "_").trim();
        if (clean.length() > 80) {
            clean = clean.substring(0, 80);
        }
        return "Map+ " + (clean.isEmpty() ? "World" : clean) + ".html";
    }

    /** Background thread: download to a temp file, keep it only if it is really the page. */
    private static void save(String url, Path target) {
        HttpURLConnection connection = null;
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (connection.getResponseCode() != 200) {
                StationAnnouncer.LOGGER.debug("Offline Map+ copy skipped: HTTP {}", connection.getResponseCode());
                return;
            }
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            byte[] head = new byte[64];
            try (InputStream check = Files.newInputStream(temp)) {
                int read = check.read(head);
                String start = new String(head, 0, Math.max(0, read), StandardCharsets.UTF_8).trim().toLowerCase();
                if (!start.startsWith("<!doctype html")) {
                    Files.deleteIfExists(temp);
                    return;
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            StationAnnouncer.LOGGER.debug("Offline Map+ copy saved to {}", target);
        } catch (Exception e) {
            StationAnnouncer.LOGGER.debug("Offline Map+ copy not saved ({})", e.toString());
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // nothing left to clean
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
