package com.stationannouncer.wayfinding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Places and exit pins for one world: THE source of truth for both.
 *
 * <p>Marker blocks carry copies of their record (so a structure paste brings
 * its data along), but on their first tick they ADOPT whatever the store holds
 * for their position; only a block the store has never seen registers its own
 * copy. That one rule is what makes {@code /place} edits to an unloaded marker,
 * exit renames and pastes all converge. A marker removed by command while its
 * chunk was unloaded is TOMBSTONED and deletes itself when it next loads.</p>
 *
 * <p><b>Threads:</b> mutated on the server thread; {@link #places()} and
 * {@link #pins()} are immutable snapshots behind volatile fields, read by the
 * dispatch web threads and the MTR simulator threads. Persisted at
 * {@code <save>/station-announcer-addon/wayfinding.json} — its own file, like
 * the gap filler store, so it never collides with the addon's data.json.</p>
 */
public final class WayfindingStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static final Map<String, Place> PLACES = new LinkedHashMap<>();
    /** blockKey → place id. */
    private static final Map<String, String> PLACE_BLOCKS = new HashMap<>();
    /** blockKey → pin. */
    private static final Map<String, ExitPin> PINS = new LinkedHashMap<>();
    private static final Set<String> TOMBSTONES = new HashSet<>();

    private static volatile List<Place> placeSnapshot = List.of();
    private static volatile List<ExitPin> pinSnapshot = List.of();
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private static Path path;
    private static boolean dirty;

    private WayfindingStore() {
    }

    // ------------------------------------------------------------- snapshots

    /** Any thread. Every place in the world, in creation order. */
    public static List<Place> places() {
        return placeSnapshot;
    }

    /** Any thread. Every exit marker, pinned or not. */
    public static List<ExitPin> pins() {
        return pinSnapshot;
    }

    /** Server thread: called after every change (the public API's change events). */
    public static void addListener(Runnable listener) {
        synchronized (LOCK) {
            LISTENERS.add(listener);
        }
    }

    // ---------------------------------------------------------------- places

    @Nullable
    public static Place place(String id) {
        synchronized (LOCK) {
            return id == null ? null : PLACES.get(id);
        }
    }

    @Nullable
    public static Place placeAtBlock(String blockKey) {
        synchronized (LOCK) {
            String id = PLACE_BLOCKS.get(blockKey);
            return id == null ? null : PLACES.get(id);
        }
    }

    /**
     * A place by id, else by exact name (case-insensitive), else by a unique
     * name prefix. Null when nothing or more than one place matches the prefix.
     */
    @Nullable
    public static Place find(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();
        synchronized (LOCK) {
            Place byId = PLACES.get(q);
            if (byId != null) {
                return byId;
            }
            String lower = q.toLowerCase(Locale.ROOT);
            Place prefix = null;
            int prefixCount = 0;
            for (Place place : PLACES.values()) {
                String name = place.name().toLowerCase(Locale.ROOT);
                if (name.equals(lower)) {
                    return place;
                }
                if (name.startsWith(lower)) {
                    prefix = place;
                    prefixCount++;
                }
            }
            return prefixCount == 1 ? prefix : null;
        }
    }

    /** A fresh id no place uses yet. */
    public static String newId() {
        synchronized (LOCK) {
            while (true) {
                String id = String.format(Locale.ROOT, "%08x", ThreadLocalRandom.current().nextInt());
                if (!PLACES.containsKey(id)) {
                    return id;
                }
            }
        }
    }

    /** Insert or replace by id; keeps the block index in step. */
    public static void putPlace(Place place) {
        synchronized (LOCK) {
            Place old = PLACES.put(place.id(), place);
            if (old != null && old.blockKey() != null && !old.blockKey().equals(place.blockKey())) {
                PLACE_BLOCKS.remove(old.blockKey());
            }
            if (place.blockKey() != null) {
                PLACE_BLOCKS.put(place.blockKey(), place.id());
            }
            dirty = true;
        }
        publish();
    }

    @Nullable
    public static Place removePlace(String id) {
        Place removed;
        synchronized (LOCK) {
            removed = PLACES.remove(id);
            if (removed == null) {
                return null;
            }
            if (removed.blockKey() != null) {
                PLACE_BLOCKS.remove(removed.blockKey());
            }
            dirty = true;
        }
        publish();
        return removed;
    }

    // ------------------------------------------------------------------ pins

    @Nullable
    public static ExitPin pinAt(String blockKey) {
        synchronized (LOCK) {
            return PINS.get(blockKey);
        }
    }

    public static void putPin(ExitPin pin) {
        synchronized (LOCK) {
            if (pin.equals(PINS.get(pin.blockKey()))) {
                return;
            }
            PINS.put(pin.blockKey(), pin);
            dirty = true;
        }
        publish();
    }

    public static void removePin(String blockKey) {
        synchronized (LOCK) {
            if (PINS.remove(blockKey) == null) {
                return;
            }
            dirty = true;
        }
        publish();
    }

    /**
     * Carry pins along with exit edits made in our exit editor: every pin of
     * {@code stationId} on a renamed exit follows the new name, every pin on a
     * deleted exit becomes unpinned. Applied in ONE pass so swaps (A↔B) work.
     *
     * @return the pins that changed (the caller refreshes their loaded blocks)
     */
    public static List<ExitPin> applyExitEdits(long stationId, Map<String, String> renames, Set<String> deleted) {
        List<ExitPin> changed = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, ExitPin> entry : PINS.entrySet()) {
                ExitPin pin = entry.getValue();
                if (pin.stationId() != stationId || pin.exitName().isEmpty()) {
                    continue;
                }
                String next = renames.containsKey(pin.exitName()) ? renames.get(pin.exitName())
                        : deleted.contains(pin.exitName()) ? "" : pin.exitName();
                if (!next.equals(pin.exitName())) {
                    ExitPin updated = pin.withExit(stationId, next);
                    entry.setValue(updated);
                    changed.add(updated);
                }
            }
            if (!changed.isEmpty()) {
                dirty = true;
            }
        }
        if (!changed.isEmpty()) {
            publish();
        }
        return changed;
    }

    // ------------------------------------------------------------ tombstones

    public static void tombstone(String blockKey) {
        synchronized (LOCK) {
            TOMBSTONES.add(blockKey);
            dirty = true;
        }
    }

    /** True (and forgets it) when the marker at this position was removed by command. */
    public static boolean consumeTombstone(String blockKey) {
        synchronized (LOCK) {
            if (!TOMBSTONES.remove(blockKey)) {
                return false;
            }
            dirty = true;
            return true;
        }
    }

    // ------------------------------------------------------------- lifecycle

    private static void publish() {
        List<Runnable> listeners;
        synchronized (LOCK) {
            placeSnapshot = List.copyOf(PLACES.values());
            pinSnapshot = List.copyOf(PINS.values());
            listeners = List.copyOf(LISTENERS);
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Wayfinding listener failed", t);
            }
        }
    }

    public static void load(MinecraftServer server) {
        Path file = server.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("wayfinding.json").normalize();
        synchronized (LOCK) {
            path = file;
            PLACES.clear();
            PLACE_BLOCKS.clear();
            PINS.clear();
            TOMBSTONES.clear();
            dirty = false;
            try {
                if (Files.exists(file)) {
                    JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    JsonArray places = root.getAsJsonArray("places");
                    if (places != null) {
                        for (JsonElement element : places) {
                            try {
                                Place place = Place.fromJson(element.getAsJsonObject());
                                PLACES.put(place.id(), place);
                                if (place.blockKey() != null) {
                                    PLACE_BLOCKS.put(place.blockKey(), place.id());
                                }
                            } catch (Exception e) {
                                StationAnnouncer.LOGGER.warn("Skipping unreadable place {}", element, e);
                            }
                        }
                    }
                    JsonArray pins = root.getAsJsonArray("exitPins");
                    if (pins != null) {
                        for (JsonElement element : pins) {
                            try {
                                ExitPin pin = ExitPin.fromJson(element.getAsJsonObject());
                                PINS.put(pin.blockKey(), pin);
                            } catch (Exception e) {
                                StationAnnouncer.LOGGER.warn("Skipping unreadable exit pin {}", element, e);
                            }
                        }
                    }
                    JsonArray tombstones = root.getAsJsonArray("tombstones");
                    if (tombstones != null) {
                        tombstones.forEach(element -> TOMBSTONES.add(element.getAsString()));
                    }
                }
            } catch (Exception e) {
                StationAnnouncer.LOGGER.warn("Could not read {}, starting without places or exit pins", file, e);
            }
        }
        publish();
        StationAnnouncer.LOGGER.info("Wayfinding store loaded ({} places, {} exit markers)",
                placeSnapshot.size(), pinSnapshot.size());
    }

    public static void saveIfDirty() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
        }
        saveNow();
    }

    public static void saveNow() {
        String json;
        Path file;
        synchronized (LOCK) {
            if (path == null) {
                return;
            }
            file = path;
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray places = new JsonArray();
            PLACES.values().forEach(place -> places.add(place.toJson()));
            root.add("places", places);
            JsonArray pins = new JsonArray();
            PINS.values().forEach(pin -> pins.add(pin.toJson()));
            root.add("exitPins", pins);
            JsonArray tombstones = new JsonArray();
            TOMBSTONES.stream().sorted().forEach(tombstones::add);
            root.add("tombstones", tombstones);
            json = GSON.toJson(root);
            dirty = false;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", file, e);
        }
    }

    public static void unload() {
        saveNow();
        synchronized (LOCK) {
            PLACES.clear();
            PLACE_BLOCKS.clear();
            PINS.clear();
            TOMBSTONES.clear();
            path = null;
        }
        placeSnapshot = List.of();
        pinSnapshot = List.of();
    }
}
