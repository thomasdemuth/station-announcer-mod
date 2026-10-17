package com.stationannouncer.mtraddon.dispatch;

import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.AddonServerConfig;
import net.minecraft.server.MinecraftServer;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Map+ as ONE self-contained HTML file: the page, its stylesheet and script inlined, and a
 * snapshot of the network, the map data and the basemap tiles embedded as
 * {@code window.MAPPLUS_OFFLINE}. Opened from disk it shows the map as it was saved, with
 * no server at all; whenever the server it came from answers (same world — {@link #worldId}),
 * it goes live exactly like the served page, and keeps the newest data it has seen.
 *
 * <p>Served as {@code GET /dispatch/api/offline[?satellite=1][&download=1]} — the Map+
 * "Save offline copy" button and the client mod's automatic copy both fetch it.</p>
 */
public final class DispatchOfflineMap {
    /** Colour tiles stop being embedded past this many raw bytes (the class masks always go in). */
    private static final long MAX_COLOR_BYTES = 150L * 1024 * 1024;
    private static volatile MinecraftServer idServer;
    private static volatile String idValue = "";

    private DispatchOfflineMap() {
    }

    /** One dimension's payloads, built on its simulator thread by the caller. */
    public record Dimension(String id, JsonObject network, JsonObject mapdata) {
    }

    /**
     * A stable, non-reversible id of the running world (level name + seed, hashed), so an
     * offline copy never mistakes another world on the same port — singleplayer worlds all
     * start their webserver on the first free port from 8888 — for its own.
     */
    public static String worldId() {
        MinecraftServer server = com.stationannouncer.mtraddon.nav.NavStore.server();
        if (server == null) {
            return "";
        }
        if (server == idServer) {
            return idValue;
        }
        String value = "";
        try {
            String name = server.getSaveProperties().getLevelName();
            long seed = server.getOverworld() == null ? 0 : server.getOverworld().getSeed();
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((name + "\u0000" + seed).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            value = hex.toString();
        } catch (Throwable t) {
            // no id: copies then trust whatever answers at their address
        }
        idValue = value;
        idServer = server;
        return value;
    }

    /** The world's name as the save calls it ("" when unknown). */
    public static String worldName() {
        MinecraftServer server = com.stationannouncer.mtraddon.nav.NavStore.server();
        try {
            return server == null ? "" : server.getSaveProperties().getLevelName();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Builds and sends the page. Jetty worker thread. */
    public static void send(HttpServletRequest request, HttpServletResponse response, List<Dimension> dimensions,
                            boolean satellite, boolean download) throws IOException {
        String html = DispatchStaticServlet.resourceText("map.html");
        String css = DispatchStaticServlet.resourceText("map.css");
        String js = DispatchStaticServlet.resourceText("map.js");
        if (html == null || css == null || js == null) {
            DispatchStaticServlet.sendText(response, 500, "text/plain;charset=utf-8", "Map+ files are missing from the jar");
            return;
        }

        JsonObject offline = new JsonObject();
        offline.addProperty("savedAt", System.currentTimeMillis());
        offline.addProperty("server", serverBase(request));
        offline.addProperty("worldId", worldId());
        offline.addProperty("worldName", worldName());
        offline.addProperty("schemaVersion", DispatchPingServlet.SCHEMA_VERSION);
        offline.addProperty("updateMillis", DispatchStreamer.clampedUpdateMillis(AddonServerConfig.get().dispatch));
        offline.addProperty("satellite", satellite);
        JsonArray dimensionIds = new JsonArray();
        JsonArray data = new JsonArray();
        long colorBytes = 0;
        for (Dimension dimension : dimensions) {
            dimensionIds.add(dimension.id());
            JsonObject entry = new JsonObject();
            entry.add("network", dimension.network());
            entry.add("mapdata", dimension.mapdata());
            JsonObject satmeta = BasemapScanner.satelliteJson(dimension.id());
            entry.add("satmeta", satmeta);
            JsonObject tiles = new JsonObject();
            JsonArray list = satmeta.getAsJsonArray("tiles");
            if (list != null) {
                for (JsonElement element : list) {
                    JsonArray tile = element.getAsJsonArray();
                    int tx = tile.get(0).getAsInt();
                    int tz = tile.get(1).getAsInt();
                    JsonObject images = new JsonObject();
                    byte[] mask = BasemapScanner.tile(dimension.id(), tx, tz, true);
                    if (mask != null) {
                        images.addProperty("m", dataUri("image/png", mask));
                    }
                    if (satellite && colorBytes < MAX_COLOR_BYTES) {
                        byte[] color = BasemapScanner.tile(dimension.id(), tx, tz, false);
                        if (color != null) {
                            colorBytes += color.length;
                            images.addProperty("c", dataUri("image/png", color));
                        }
                    }
                    if (images.size() > 0) {
                        tiles.add(tx + "," + tz, images);
                    }
                }
            }
            entry.add("tiles", tiles);
            data.add(entry);
        }
        offline.add("dimensions", dimensionIds);
        offline.add("data", data);
        JsonObject assets = new JsonObject();
        for (String name : new String[]{"access_badge.svg", "access_badge_outline.svg"}) {
            String svg = DispatchStaticServlet.resourceText(name);
            if (svg != null) {
                assets.addProperty(name, dataUri("image/svg+xml", svg.getBytes(StandardCharsets.UTF_8)));
            }
        }
        offline.add("assets", assets);

        // "</" inside a <script> would end it early; "<\/" means the same thing to JavaScript
        String snapshot = offline.toString().replace("</", "<\\/");
        String page = html
                .replace("<link rel=\"stylesheet\" href=\"map.css\">", "<style>\n" + css + "\n</style>")
                .replace("<script src=\"map.js\"></script>",
                        "<script>window.MAPPLUS_OFFLINE = " + snapshot + ";</script>\n<script>\n"
                                + js.replace("</script", "<\\/script") + "\n</script>");
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        response.setStatus(200);
        response.setHeader("Content-Type", "text/html;charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Access-Control-Allow-Origin", "*");
        if (download) {
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName(worldName()) + "\"");
        }
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        StationAnnouncer.LOGGER.info("Map+ offline copy served ({} KB{})", bytes.length / 1024,
                satellite ? ", with satellite imagery" : "");
    }

    /** "Map+ <world>.html", with nothing a file system would choke on. */
    public static String fileName(String world) {
        String clean = world == null ? "" : world.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
        return "Map+ " + (clean.isEmpty() ? "offline" : clean) + ".html";
    }

    /** Where this page came from, as the browser asked for it: {@code http://host:port/dispatch/}. */
    private static String serverBase(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            DispatchRegistry.State state = DispatchRegistry.get();
            host = "localhost" + (state == null ? "" : ":" + state.port());
        }
        String scheme = request.getScheme() == null ? "http" : request.getScheme();
        return scheme + "://" + host.trim() + "/dispatch/";
    }

    private static String dataUri(String type, byte[] bytes) {
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
