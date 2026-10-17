package com.stationannouncer.mtr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.block.AbstractPaBlockEntity;
import com.stationannouncer.mtraddon.AddonServerConfig;
import com.stationannouncer.pa.TrainAnnouncements;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Announcements that RIDE WITH A ROUTE'S TRAINS: per MTR route, the same timed rows a
 * PA source has ({@link TrainAnnouncements}: before arrival, doors open, doors closing,
 * after departure, with repeats), spoken at every stop to the players aboard the train
 * — {@code {station}} is the stop being served, {@code {access}} that platform's
 * step-free access in the train's own direction. {@link PaTrains} does the timing.
 *
 * <p>Server: {@code <save>/station-announcer-addon/route_train_pa.json} (route id →
 * chime settings + the rows as SNBT), edits gated by {@code editPermissionLevel}. The
 * editor asks for one route when it opens ({@code route_train_pa_query} →
 * {@code route_train_pa}) and saves with {@code route_train_pa_update}. Readers get a
 * volatile immutable snapshot, so the simulator threads can test {@link #routes()}.</p>
 */
public final class RouteTrainPa {
    public static final Identifier QUERY_C2S = StationAnnouncer.id("route_train_pa_query");
    public static final Identifier UPDATE_C2S = StationAnnouncer.id("route_train_pa_update");
    public static final Identifier DATA_S2C = StationAnnouncer.id("route_train_pa");
    private static final int MAX_ROUTES = 2048;

    /** One route's on-board announcements. */
    public record Entry(boolean chime, String chimeSound, TrainAnnouncements trains) {
        public static final Entry EMPTY = new Entry(true, "", TrainAnnouncements.EMPTY);

        public void write(PacketByteBuf buf) {
            buf.writeBoolean(chime);
            buf.writeString(chimeSound, AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
            trains.write(buf);
        }

        public static Entry read(PacketByteBuf buf) {
            boolean chime = buf.readBoolean();
            String sound = buf.readString(AbstractPaBlockEntity.MAX_CHIME_SOUND_LENGTH);
            return new Entry(chime, sound, TrainAnnouncements.read(buf));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Map<Long, Entry> entries = Map.of();
    /** Routes with at least one switched-on row — the simulator hooks test this. */
    private static volatile Set<Long> active = Set.of();
    private static Path path;

    private RouteTrainPa() {
    }

    /** Any thread: routes whose trains have something to say. */
    public static Set<Long> routes() {
        return active;
    }

    /** Any thread: a route's entry, or null. */
    @Nullable
    public static Entry get(long routeId) {
        return entries.get(routeId);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(RouteTrainPa::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            entries = Map.of();
            active = Set.of();
            path = null;
        });
        ServerPlayNetworking.registerGlobalReceiver(QUERY_C2S, (server, player, handler, buf, responseSender) -> {
            long routeId = buf.readLong();
            server.execute(() -> send(player, routeId));
        });
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_C2S, (server, player, handler, buf, responseSender) -> {
            long routeId = buf.readLong();
            Entry entry;
            try {
                entry = Entry.read(buf);
            } catch (RuntimeException e) {
                return; // malformed: ignore rather than kick
            }
            Entry parsed = entry;
            server.execute(() -> {
                if (routeId == 0 || !player.hasPermissionLevel(AddonServerConfig.get().editPermissionLevel)) {
                    return;
                }
                Map<Long, Entry> next = new HashMap<>(entries);
                if (parsed.trains().rows.isEmpty()) {
                    next.remove(routeId);
                } else if (next.size() < MAX_ROUTES || next.containsKey(routeId)) {
                    next.put(routeId, parsed);
                }
                publish(next);
                save();
                send(player, routeId);
            });
        });
    }

    private static void send(ServerPlayerEntity player, long routeId) {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeLong(routeId);
        Entry entry = entries.get(routeId);
        (entry == null ? Entry.EMPTY : entry).write(out);
        ServerPlayNetworking.send(player, DATA_S2C, out);
    }

    private static void publish(Map<Long, Entry> next) {
        entries = Map.copyOf(next);
        Set<Long> on = new java.util.HashSet<>();
        next.forEach((id, entry) -> {
            if (entry.trains().active()) {
                on.add(id);
            }
        });
        active = Set.copyOf(on);
    }

    // ------------------------------------------------------------- persistence

    private static void load(MinecraftServer server) {
        path = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("route_train_pa.json").normalize();
        Map<Long, Entry> loaded = new HashMap<>();
        try {
            if (Files.exists(path)) {
                JsonObject routes = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("routes");
                if (routes != null) {
                    for (Map.Entry<String, JsonElement> e : routes.entrySet()) {
                        JsonObject o = e.getValue().getAsJsonObject();
                        NbtCompound nbt = StringNbtReader.parse(o.get("rows").getAsString());
                        loaded.put(Long.parseLong(e.getKey()), new Entry(!o.has("chime") || o.get("chime").getAsBoolean(),
                                o.has("chimeSound") ? o.get("chimeSound").getAsString() : "", TrainAnnouncements.fromNbt(nbt)));
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read {}, no on-board train announcements", path, e);
        }
        publish(loaded);
    }

    private static void save() {
        Path file = path;
        if (file == null) {
            return;
        }
        JsonObject routes = new JsonObject();
        entries.forEach((id, entry) -> {
            JsonObject o = new JsonObject();
            o.addProperty("chime", entry.chime());
            o.addProperty("chimeSound", entry.chimeSound());
            o.addProperty("rows", entry.trains().toNbt().toString());
            routes.add(Long.toString(id), o);
        });
        JsonObject root = new JsonObject();
        root.add("routes", routes);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
