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
 * Per-LINE bullet style: the shape (the NYC circle, the express diamond, a
 * square or a hexagon), the bullet TEXT and its ink (auto / white / black).
 * Set from the "Line style…" editor on MTR's Edit Route screen, drawn by every
 * bullet in the mod — NYC PIDS, entrance and MTA signs, posters, pickers and
 * the Map+ web map. The line's COLOUR is not stored here: it is MTR's own
 * route colour (the editor writes it to every route of the line), so a bullet
 * and a Map+ ribbon can never disagree.
 *
 * <p>Keyed by LINE name — the part of an MTR route name before its {@code ||}
 * direction, first language (the same key posters and signs already use) — so
 * both directions of a line share one style, and a line drawn only by name
 * (a poster's {@code {b:LINE}}) finds it. Only non-default styles are stored.</p>
 *
 * <p>Server: {@code <save>/station-announcer-addon/line_styles.json}, edits
 * gated like the other addon settings ({@code editPermissionLevel}), the whole
 * map synced to every client on join and after each change. {@link #styleFor}
 * reads a volatile immutable snapshot, so the dispatch map's simulator thread
 * can call it too.</p>
 */
public final class LineStyles {
    /** Appended only — the packet carries the ordinal. */
    public enum Shape {
        CIRCLE("circle"),
        DIAMOND("diamond"),
        SQUARE("square"),
        HEXAGON("hexagon");

        public final String id;

        Shape(String id) {
            this.id = id;
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

    /** The bullet's lettering: AUTO picks whichever reads on the line colour (black on the N/Q/R/W yellow). */
    public enum TextColor {
        AUTO("auto"),
        WHITE("white"),
        BLACK("black");

        public final String id;

        TextColor(String id) {
            this.id = id;
        }

        public static TextColor byOrdinal(int ordinal) {
            TextColor[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : AUTO;
        }

        public static TextColor byId(String id) {
            for (TextColor color : values()) {
                if (color.id.equals(id)) {
                    return color;
                }
            }
            return AUTO;
        }
    }

    /**
     * One line's style. {@code label} empty = automatic (MTR's route number,
     * else the first number or letter of the name).
     */
    public record Style(Shape shape, String label, TextColor text) {
        public static final Style DEFAULT = new Style(Shape.CIRCLE, "", TextColor.AUTO);

        public Style {
            shape = shape == null ? Shape.CIRCLE : shape;
            label = cleanLabel(label);
            text = text == null ? TextColor.AUTO : text;
        }

        public boolean isDefault() {
            return shape == Shape.CIRCLE && label.isEmpty() && text == TextColor.AUTO;
        }

        /** True when the lettering on a bullet of this colour is black. */
        public boolean darkText(int argb) {
            return switch (text) {
                case BLACK -> true;
                case WHITE -> false;
                default -> needsDarkText(argb);
            };
        }

        /** This style's text, or {@code fallback} when it is automatic. */
        public String labelOr(String fallback) {
            return label.isEmpty() ? fallback : label;
        }
    }

    /**
     * One ROUTE's departures from its line's style (null = same as the line): an express
     * variant's diamond or "4X", a shuttle's black lettering. Keyed by {@link #routeKey}
     * (the whole route name), so every bullet drawn from a route name — signs, PIDS,
     * Map+ — picks it up; a bare line name never matches one.
     */
    public record RouteStyle(Shape shape, String label, TextColor text) {
        public static final RouteStyle NONE = new RouteStyle(null, null, null);

        public RouteStyle {
            label = label == null ? null : cleanLabel(label);
            if (label != null && label.isEmpty()) {
                label = null;
            }
        }

        public boolean isEmpty() {
            return shape == null && label == null && text == null;
        }

        public Style over(Style line) {
            return isEmpty() ? line : new Style(shape != null ? shape : line.shape(),
                    label != null ? label : line.label(), text != null ? text : line.text());
        }
    }

    public static final Identifier UPDATE_LINE_STYLE_C2S = StationAnnouncer.id("update_line_style");
    public static final Identifier UPDATE_ROUTE_STYLE_C2S = StationAnnouncer.id("update_route_style");
    public static final Identifier LINE_STYLES_S2C = StationAnnouncer.id("line_styles");
    public static final int MAX_LINE_LENGTH = 128;
    /** Bullet text: NYC uses one or two characters; three still fit ("FS", "42", "SIR"). */
    public static final int MAX_LABEL_LENGTH = 3;
    private static final int MAX_ENTRIES = 4096;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Map<String, Style> styles = Map.of();
    private static volatile Map<String, RouteStyle> routeStyles = Map.of();
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

    /**
     * The per-route key: the WHOLE route name, trimmed. Not the part before {@code ||}:
     * on Baker City that part is the line ("Line 4||North Limited"), so it would file one
     * route's override under every route of the line.
     */
    public static String routeKey(String routeName) {
        return routeName == null ? "" : routeName.trim();
    }

    /** Bullet text as stored: trimmed, no line breaks, at most {@value #MAX_LABEL_LENGTH} characters. */
    public static String cleanLabel(String label) {
        if (label == null) {
            return "";
        }
        String clean = label.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').trim();
        return clean.length() > MAX_LABEL_LENGTH ? clean.substring(0, MAX_LABEL_LENGTH) : clean;
    }

    /** Black or white lettering, whichever reads on a bullet of this colour (perceived brightness). */
    public static boolean needsDarkText(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 140;
    }

    /** Server side (any thread): the style of a route's or line's bullet. */
    public static Style styleFor(String routeOrLineName) {
        Map<String, Style> current = styles;
        Style line = current.isEmpty() ? Style.DEFAULT : current.getOrDefault(lineKey(routeOrLineName), Style.DEFAULT);
        Map<String, RouteStyle> routes = routeStyles;
        return routes.isEmpty() ? line : routes.getOrDefault(routeKey(routeOrLineName), RouteStyle.NONE).over(line);
    }

    /** Server side (any thread): the shape of a route's or line's bullet. */
    public static Shape shapeFor(String routeOrLineName) {
        return styleFor(routeOrLineName).shape();
    }

    // ---------------------------------------------------------------- packets

    /** The C2S edit, also used by the client to build it. */
    public static void writeStyle(PacketByteBuf buf, Style style) {
        buf.writeByte(style.shape().ordinal());
        buf.writeString(style.label(), MAX_LABEL_LENGTH * 4);
        buf.writeByte(style.text().ordinal());
    }

    /** A route override: presence mask (1 shape, 2 label, 4 text), then the present fields. */
    public static void writeRouteStyle(PacketByteBuf buf, RouteStyle style) {
        int mask = (style.shape() != null ? 1 : 0) | (style.label() != null ? 2 : 0) | (style.text() != null ? 4 : 0);
        buf.writeByte(mask);
        if (style.shape() != null) {
            buf.writeByte(style.shape().ordinal());
        }
        if (style.label() != null) {
            buf.writeString(style.label(), MAX_LABEL_LENGTH * 4);
        }
        if (style.text() != null) {
            buf.writeByte(style.text().ordinal());
        }
    }

    public static RouteStyle readRouteStyle(PacketByteBuf buf) {
        int mask = buf.readByte();
        Shape shape = (mask & 1) != 0 ? Shape.byOrdinal(buf.readByte()) : null;
        String label = (mask & 2) != 0 ? buf.readString(MAX_LABEL_LENGTH * 4) : null;
        TextColor text = (mask & 4) != 0 ? TextColor.byOrdinal(buf.readByte()) : null;
        return new RouteStyle(shape, label, text);
    }

    public static Style readStyle(PacketByteBuf buf) {
        Shape shape = Shape.byOrdinal(buf.readByte());
        String label = buf.readString(MAX_LABEL_LENGTH * 4);
        TextColor text = TextColor.byOrdinal(buf.readByte());
        return new Style(shape, label, text);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(LineStyles::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            styles = Map.of();
            routeStyles = Map.of();
            path = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(sender));
        registerRouteStyles();
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_LINE_STYLE_C2S, (server, player, handler, buf, responseSender) -> {
            String line = lineKey(buf.readString(MAX_LINE_LENGTH));
            Style style = readStyle(buf);
            server.execute(() -> {
                if (line.isEmpty() || !player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                Map<String, Style> next = new HashMap<>(styles);
                if (style.isDefault()) {
                    next.remove(line);
                } else if (next.size() < MAX_ENTRIES || next.containsKey(line)) {
                    next.put(line, style);
                }
                styles = Map.copyOf(next);
                save();
                for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(online, LINE_STYLES_S2C, buildSync());
                }
            });
        });
    }

    private static void registerRouteStyles() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_ROUTE_STYLE_C2S, (server, player, handler, buf, responseSender) -> {
            String key = routeKey(buf.readString(MAX_LINE_LENGTH));
            RouteStyle style = readRouteStyle(buf);
            server.execute(() -> {
                if (key.isEmpty() || !player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                Map<String, RouteStyle> next = new HashMap<>(routeStyles);
                if (style.isEmpty()) {
                    next.remove(key);
                } else if (next.size() < MAX_ENTRIES || next.containsKey(key)) {
                    next.put(key, style);
                }
                routeStyles = Map.copyOf(next);
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
        Map<String, Style> current = styles;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(current.size());
        current.forEach((line, style) -> {
            buf.writeString(line, MAX_LINE_LENGTH);
            writeStyle(buf, style);
        });
        Map<String, RouteStyle> routes = routeStyles;
        buf.writeVarInt(routes.size());
        routes.forEach((key, style) -> {
            buf.writeString(key, MAX_LINE_LENGTH);
            writeRouteStyle(buf, style);
        });
        return buf;
    }

    // ------------------------------------------------------------- persistence

    private static void load(MinecraftServer server) {
        path = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("line_styles.json").normalize();
        Map<String, Style> loaded = new HashMap<>();
        Map<String, RouteStyle> loadedRoutes = new HashMap<>();
        try {
            if (Files.exists(path)) {
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                // Pre-3.5 files hold only "bulletShapes" (line → shape id).
                JsonObject shapes = root.getAsJsonObject("bulletShapes");
                if (shapes != null) {
                    for (Map.Entry<String, JsonElement> entry : shapes.entrySet()) {
                        Shape shape = Shape.byId(entry.getValue().getAsString().toLowerCase(Locale.ROOT));
                        String line = lineKey(entry.getKey());
                        if (shape != Shape.CIRCLE && !line.isEmpty()) {
                            loaded.put(line, new Style(shape, "", TextColor.AUTO));
                        }
                    }
                }
                JsonObject lines = root.getAsJsonObject("lines");
                if (lines != null) {
                    for (Map.Entry<String, JsonElement> entry : lines.entrySet()) {
                        String line = lineKey(entry.getKey());
                        if (line.isEmpty() || !entry.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject json = entry.getValue().getAsJsonObject();
                        Style style = new Style(
                                Shape.byId(string(json, "shape").toLowerCase(Locale.ROOT)),
                                string(json, "label"),
                                TextColor.byId(string(json, "text").toLowerCase(Locale.ROOT)));
                        if (style.isDefault()) {
                            loaded.remove(line);
                        } else {
                            loaded.put(line, style);
                        }
                    }
                }
                JsonObject routes = root.getAsJsonObject("routes");
                if (routes != null) {
                    for (Map.Entry<String, JsonElement> entry : routes.entrySet()) {
                        String key = routeKey(entry.getKey());
                        if (key.isEmpty() || !entry.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject json = entry.getValue().getAsJsonObject();
                        RouteStyle style = new RouteStyle(
                                json.has("shape") ? Shape.byId(string(json, "shape").toLowerCase(Locale.ROOT)) : null,
                                json.has("label") ? string(json, "label") : null,
                                json.has("text") ? TextColor.byId(string(json, "text").toLowerCase(Locale.ROOT)) : null);
                        if (!style.isEmpty()) {
                            loadedRoutes.put(key, style);
                        }
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, all bullets are plain circles", path, e);
        }
        styles = Map.copyOf(loaded);
        routeStyles = Map.copyOf(loadedRoutes);
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static void save() {
        Path file = path;
        if (file == null) {
            return;
        }
        // "bulletShapes" stays alongside so an older build still finds its shapes.
        JsonObject shapes = new JsonObject();
        JsonObject lines = new JsonObject();
        styles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Style style = entry.getValue();
            if (style.shape() != Shape.CIRCLE && style.shape() != Shape.HEXAGON) {
                shapes.addProperty(entry.getKey(), style.shape().id);
            }
            JsonObject json = new JsonObject();
            json.addProperty("shape", style.shape().id);
            json.addProperty("label", style.label());
            json.addProperty("text", style.text().id);
            lines.add(entry.getKey(), json);
        });
        JsonObject root = new JsonObject();
        root.add("bulletShapes", shapes);
        root.add("lines", lines);
        JsonObject routes = new JsonObject();
        routeStyles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RouteStyle style = entry.getValue();
            JsonObject json = new JsonObject();
            if (style.shape() != null) {
                json.addProperty("shape", style.shape().id);
            }
            if (style.label() != null) {
                json.addProperty("label", style.label());
            }
            if (style.text() != null) {
                json.addProperty("text", style.text().id);
            }
            routes.add(entry.getKey(), json);
        });
        root.add("routes", routes);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
