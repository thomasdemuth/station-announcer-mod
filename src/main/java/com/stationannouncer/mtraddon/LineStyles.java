package com.stationannouncer.mtraddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-LINE bullet shape: the NYC circle, the express diamond (6◆, 7◆) or a
 * square. Set from MTR's Edit Route screen, drawn by every bullet in the mod —
 * NYC PIDS, entrance and MTA signs, posters, pickers and the Map+ web map.
 *
 * <p>Keyed by LINE name — the part of an MTR route name before its {@code ||}
 * direction, first language (the same key posters and signs already use) — so
 * both directions of a line share one shape, and a line drawn only by name
 * (a poster's {@code {b:LINE}}) finds it. Only non-circle shapes are stored.</p>
 *
 * <p>Server: {@code <save>/station-announcer-addon/line_styles.json}, edits
 * gated like the other addon settings ({@code editPermissionLevel}), the whole
 * map synced to every client on join and after each change. {@link #shapeFor}
 * reads a volatile immutable snapshot, so the dispatch map's simulator thread
 * can call it too.</p>
 */
public final class LineStyles {
    public enum Shape {
        CIRCLE("circle"),
        DIAMOND("diamond"),
        SQUARE("square");

        public final String id;

        Shape(String id) {
            this.id = id;
        }

        public Shape next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static Shape byOrdinal(int ordinal) {
            Shape[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CIRCLE;
        }

        public static Shape byId(String id) {
            for (Shape shape : values()) {
                if (shape.id.equals(id)) {
                    return shape;
                }
            }
            return CIRCLE;
        }
    }

    public static final Identifier UPDATE_LINE_STYLE_C2S = StationAnnouncer.id("update_line_style");
    public static final Identifier LINE_STYLES_S2C = StationAnnouncer.id("line_styles");
    public static final int MAX_LINE_LENGTH = 128;
    private static final int MAX_ENTRIES = 4096;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Map<String, Shape> shapes = Map.of();
    private static Path path;

    private LineStyles() {
    }

    /**
     * The line key for any route or line name: before {@code ||}, first
     * language, trimmed. Shared with the client so both ends agree.
     */
    public static String lineKey(String routeOrLineName) {
        if (routeOrLineName == null) {
            return "";
        }
        // Exactly AddonUi.splitLineAndDirection(name)[0] (which posters store).
        String line = routeOrLineName.split("\\|\\|", -1)[0];
        int bar = line.indexOf('|');
        String first = (bar < 0 ? line : line.substring(0, bar)).trim();
        return first.isEmpty() ? line.trim() : first;
    }

    /** Server side (any thread): the shape of a route's or line's bullet. */
    public static Shape shapeFor(String routeOrLineName) {
        Map<String, Shape> current = shapes;
        return current.isEmpty() ? Shape.CIRCLE : current.getOrDefault(lineKey(routeOrLineName), Shape.CIRCLE);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(LineStyles::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            shapes = Map.of();
            path = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(sender));
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_LINE_STYLE_C2S, (server, player, handler, buf, responseSender) -> {
            String line = lineKey(buf.readString(MAX_LINE_LENGTH));
            Shape shape = Shape.byOrdinal(buf.readByte());
            server.execute(() -> {
                if (line.isEmpty() || !player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                Map<String, Shape> next = new HashMap<>(shapes);
                if (shape == Shape.CIRCLE) {
                    next.remove(line);
                } else if (next.size() < MAX_ENTRIES || next.containsKey(line)) {
                    next.put(line, shape);
                }
                shapes = Map.copyOf(next);
                save();
                for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(online, LINE_STYLES_S2C, buildSync());
                }
            });
        });
    }

    private static void syncTo(PacketSender sender) {
        sender.sendPacket(LINE_STYLES_S2C, buildSync());
    }

    private static PacketByteBuf buildSync() {
        Map<String, Shape> current = shapes;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(current.size());
        current.forEach((line, shape) -> {
            buf.writeString(line, MAX_LINE_LENGTH);
            buf.writeByte(shape.ordinal());
        });
        return buf;
    }

    // ------------------------------------------------------------- persistence

    private static void load(MinecraftServer server) {
        path = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("line_styles.json").normalize();
        Map<String, Shape> loaded = new HashMap<>();
        try {
            if (Files.exists(path)) {
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                JsonObject lines = root.getAsJsonObject("bulletShapes");
                if (lines != null) {
                    for (Map.Entry<String, JsonElement> entry : lines.entrySet()) {
                        Shape shape = Shape.byId(entry.getValue().getAsString().toLowerCase(Locale.ROOT));
                        String line = lineKey(entry.getKey());
                        if (shape != Shape.CIRCLE && !line.isEmpty()) {
                            loaded.put(line, shape);
                        }
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, all bullets are circles", path, e);
        }
        shapes = Map.copyOf(loaded);
    }

    private static void save() {
        Path file = path;
        if (file == null) {
            return;
        }
        JsonObject lines = new JsonObject();
        shapes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.addProperty(entry.getKey(), entry.getValue().id));
        JsonObject root = new JsonObject();
        root.add("bulletShapes", lines);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
