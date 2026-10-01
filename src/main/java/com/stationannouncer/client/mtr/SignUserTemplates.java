package com.stationannouncer.client.mtr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.sign.SignSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's own sign templates, kept client-side in
 * {@code config/station_announcer/sign_templates.json} (name → one sign face
 * in the sign's own JSON) so they follow the player across worlds and servers
 * — the Bridge Creator's user presets, for signs. Built-in templates the player
 * deleted are remembered by name in {@code sign_templates_hidden.json} beside it,
 * so they stay gone until restored (a separate file: the template file maps names
 * to signs and older builds read every object in it as one).
 */
@Environment(EnvType.CLIENT)
public final class SignUserTemplates {
    public static final int MAX_NAME = 32;
    public static final int MAX_TEMPLATES = 64;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, SignSpec> templates;
    private static Set<String> hiddenPresets;

    private SignUserTemplates() {
    }

    public static synchronized Map<String, SignSpec> all() {
        if (templates == null) {
            templates = load();
        }
        return templates;
    }

    public static synchronized List<String> names() {
        return new ArrayList<>(all().keySet());
    }

    /** Saves (or replaces) a template; false when the list is full. */
    public static synchronized boolean put(String name, SignSpec spec) {
        if (!all().containsKey(name) && all().size() >= MAX_TEMPLATES) {
            return false;
        }
        all().put(name, spec);
        save();
        return true;
    }

    public static synchronized void remove(String name) {
        all().remove(name);
        save();
    }

    public static synchronized boolean isPresetHidden(String name) {
        return hidden().contains(name);
    }

    public static synchronized void hidePreset(String name) {
        if (hidden().add(name)) {
            saveHidden();
        }
    }

    /** Brings every deleted built-in template back. */
    public static synchronized void restorePresets() {
        hidden().clear();
        saveHidden();
    }

    private static Set<String> hidden() {
        if (hiddenPresets == null) {
            hiddenPresets = loadHidden();
        }
        return hiddenPresets;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("sign_templates.json");
    }

    private static Path hiddenPath() {
        return path().resolveSibling("sign_templates_hidden.json");
    }

    private static Set<String> loadHidden() {
        Set<String> loaded = new LinkedHashSet<>();
        Path path = hiddenPath();
        try {
            if (Files.exists(path)) {
                JsonElement root = JsonParser.parseString(Files.readString(path));
                if (root.isJsonArray()) {
                    root.getAsJsonArray().forEach(element -> {
                        if (element.isJsonPrimitive()) {
                            loaded.add(element.getAsString());
                        }
                    });
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read deleted sign templates from {}", path, e);
        }
        return loaded;
    }

    private static void saveHidden() {
        Path path = hiddenPath();
        try {
            if (hiddenPresets.isEmpty()) {
                Files.deleteIfExists(path);
                return;
            }
            JsonArray root = new JsonArray();
            hiddenPresets.forEach(root::add);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write deleted sign templates to {}", path, e);
        }
    }

    private static Map<String, SignSpec> load() {
        Map<String, SignSpec> loaded = new LinkedHashMap<>();
        Path path = path();
        try {
            if (Files.exists(path)) {
                JsonElement root = JsonParser.parseString(Files.readString(path));
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                        if (entry.getValue().isJsonObject() && loaded.size() < MAX_TEMPLATES) {
                            loaded.put(entry.getKey(), SignSpec.fromJson(entry.getValue().getAsJsonObject()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read sign templates from {}", path, e);
        }
        return loaded;
    }

    private static void save() {
        Path path = path();
        try {
            JsonObject root = new JsonObject();
            templates.forEach((name, spec) -> root.add(name, spec.toJson()));
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write sign templates to {}", path, e);
        }
    }
}
