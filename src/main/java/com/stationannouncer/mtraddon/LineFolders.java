package com.stationannouncer.mtraddon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line FOLDERS: a tree that groups routes (Thomas, 2026-10-14). A route lands in a folder
 * two ways: MANUALLY ({@link State#routes}: route id → folder + order; manual wins), or
 * AUTOMATICALLY by its line name ({@link LineStyles#lineKey}: "Line 4||North Limited" →
 * the "Line 4" folder) — an automatic folder exists implicitly once two routes share a line
 * name and is only stored when edited (renamed, ordered, given subfolders); a stored folder
 * keeps its {@link Folder#line} so it still collects that line's new routes. Folders nest
 * ({@link Folder#parent}, 0 = top level).
 *
 * <p>Server: {@code <save>/station-announcer-addon/line_folders.json}; the whole state is
 * small, so an edit is a full replacement (C2S JSON, gated by {@code editPermissionLevel},
 * validated: sizes, names, parents that exist, no cycles, depth ≤ {@link #MAX_DEPTH}) and
 * every client gets the whole state on join and after each edit.</p>
 */
public final class LineFolders {
    public static final Identifier SYNC_S2C = StationAnnouncer.id("line_folders");
    public static final Identifier UPDATE_C2S = StationAnnouncer.id("update_line_folders");
    public static final int MAX_JSON = 120_000;
    public static final int MAX_FOLDERS = 512;
    public static final int MAX_ROUTES = 8_192;
    public static final int MAX_NAME = 64;
    public static final int MAX_DEPTH = 6;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile State state = State.EMPTY;
    private static Path path;

    private LineFolders() {
    }

    /**
     * One folder. {@code line} = the line name it collects automatically ("" = a manual
     * folder: only routes moved into it). {@code order} sorts siblings.
     */
    public record Folder(long id, String name, long parent, int order, String line) {
        public Folder {
            name = clean(name);
            line = line == null ? "" : line.trim();
        }

        public Folder withName(String newName) {
            return new Folder(id, newName, parent, order, line);
        }

        public Folder withParent(long newParent) {
            return new Folder(id, name, newParent, order, line);
        }

        public Folder withOrder(int newOrder) {
            return new Folder(id, name, parent, newOrder, line);
        }
    }

    /** A manual placement: the folder (0 = top level, out of any folder) and the order in it. */
    public record Placement(long folder, int order) {
    }

    public record State(Map<Long, Folder> folders, Map<Long, Placement> routes) {
        public static final State EMPTY = new State(Map.of(), Map.of());

        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            JsonArray list = new JsonArray();
            folders.values().forEach(folder -> {
                JsonObject json = new JsonObject();
                json.addProperty("id", Long.toString(folder.id()));
                json.addProperty("name", folder.name());
                json.addProperty("parent", Long.toString(folder.parent()));
                json.addProperty("order", folder.order());
                json.addProperty("line", folder.line());
                list.add(json);
            });
            root.add("folders", list);
            JsonObject routesJson = new JsonObject();
            routes.forEach((routeId, placement) -> {
                JsonObject json = new JsonObject();
                json.addProperty("folder", Long.toString(placement.folder()));
                json.addProperty("order", placement.order());
                routesJson.add(Long.toString(routeId), json);
            });
            root.add("routes", routesJson);
            return root;
        }

        /** Parses and validates; anything malformed is dropped rather than failing the whole state. */
        public static State fromJson(JsonObject root) {
            Map<Long, Folder> folders = new LinkedHashMap<>();
            JsonArray list = root.has("folders") && root.get("folders").isJsonArray() ? root.getAsJsonArray("folders") : new JsonArray();
            for (JsonElement element : list) {
                if (folders.size() >= MAX_FOLDERS || !element.isJsonObject()) {
                    continue;
                }
                try {
                    JsonObject json = element.getAsJsonObject();
                    long id = Long.parseLong(json.get("id").getAsString());
                    Folder folder = new Folder(id, json.get("name").getAsString(),
                            json.has("parent") ? Long.parseLong(json.get("parent").getAsString()) : 0,
                            json.has("order") ? json.get("order").getAsInt() : 0,
                            json.has("line") ? json.get("line").getAsString() : "");
                    if (id != 0 && !folder.name().isEmpty()) {
                        folders.put(id, folder);
                    }
                } catch (Exception ignored) {
                    // one bad folder does not lose the rest
                }
            }
            // Parents must exist; break cycles and over-deep chains by moving the folder to the top.
            for (Folder folder : new ArrayList<>(folders.values())) {
                if (folder.parent() != 0 && (!folders.containsKey(folder.parent()) || depth(folders, folder.id()) < 0)) {
                    folders.put(folder.id(), folder.withParent(0));
                }
            }
            Map<Long, Placement> routes = new LinkedHashMap<>();
            if (root.has("routes") && root.get("routes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("routes").entrySet()) {
                    if (routes.size() >= MAX_ROUTES) {
                        break;
                    }
                    try {
                        JsonObject json = entry.getValue().getAsJsonObject();
                        long folder = Long.parseLong(json.get("folder").getAsString());
                        if (folder != 0 && !folders.containsKey(folder)) {
                            continue;
                        }
                        routes.put(Long.parseLong(entry.getKey()), new Placement(folder, json.has("order") ? json.get("order").getAsInt() : 0));
                    } catch (Exception ignored) {
                        // skip
                    }
                }
            }
            return new State(Map.copyOf(folders), Map.copyOf(routes));
        }
    }

    /** Depth of a folder (1 = top level), or -1 for a cycle or a chain deeper than {@link #MAX_DEPTH}. */
    public static int depth(Map<Long, Folder> folders, long id) {
        Set<Long> seen = new HashSet<>();
        int depth = 0;
        long current = id;
        while (current != 0) {
            Folder folder = folders.get(current);
            if (folder == null) {
                return depth;
            }
            if (!seen.add(current) || ++depth > MAX_DEPTH) {
                return -1;
            }
            current = folder.parent();
        }
        return depth;
    }

    public static String clean(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }

    public static State state() {
        return state;
    }

    // ---------------------------------------------------------------- server

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(LineFolders::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            state = State.EMPTY;
            path = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sender.sendPacket(SYNC_S2C, buildSync()));
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_C2S, (server, player, handler, buf, responseSender) -> {
            String json = buf.readString(MAX_JSON);
            server.execute(() -> {
                if (!player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                try {
                    state = State.fromJson(JsonParser.parseString(json).getAsJsonObject());
                } catch (Exception e) {
                    StationAnnouncer.LOGGER.warn("Ignoring a malformed line folder update from {}", player.getName().getString(), e);
                    return;
                }
                save();
                for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
                    ServerPlayNetworking.send(online, SYNC_S2C, buildSync());
                }
            });
        });
    }

    private static PacketByteBuf buildSync() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(state.toJson().toString(), MAX_JSON);
        return buf;
    }

    private static void load(MinecraftServer server) {
        path = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("line_folders.json").normalize();
        State loaded = State.EMPTY;
        try {
            if (Files.exists(path)) {
                loaded = State.fromJson(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, no line folders", path, e);
        }
        state = loaded;
    }

    private static void save() {
        Path file = path;
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(state.toJson()));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", file, e);
        }
    }

    /** The folders of a state sorted for display: by order, then name. */
    public static List<Folder> sorted(Iterable<Folder> folders) {
        List<Folder> list = new ArrayList<>();
        folders.forEach(list::add);
        list.sort((a, b) -> a.order() != b.order() ? Integer.compare(a.order(), b.order()) : a.name().compareToIgnoreCase(b.name()));
        return list;
    }
}
