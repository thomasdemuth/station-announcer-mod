package com.stationannouncer.client.mtr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtr.EarthworksSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.StringNbtReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The player's own earthworks presets, client-side in
 * {@code config/station_announcer/earthworks_presets.json}: tool kind →
 * preset name → the spec as SNBT (the item's own NBT form, so every field —
 * mixes included — round-trips without a second schema).
 */
@Environment(EnvType.CLIENT)
public final class EarthworksUserPresets {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Map<String, String>> presets;

    private EarthworksUserPresets() {
    }

    private static synchronized Map<String, Map<String, String>> all() {
        if (presets == null) {
            presets = load();
        }
        return presets;
    }

    public static synchronized Map<String, String> forKind(EarthworksSpec.Kind kind) {
        return all().computeIfAbsent(kind.name(), k -> new LinkedHashMap<>());
    }

    public static synchronized EarthworksSpec get(EarthworksSpec.Kind kind, String name) {
        String snbt = forKind(kind).get(name);
        if (snbt == null) {
            return null;
        }
        try {
            return EarthworksSpec.fromNbt(kind, StringNbtReader.parse(snbt));
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized void put(EarthworksSpec spec, String name) {
        forKind(spec.kind).put(name, spec.copy().toNbt().toString());
        save();
    }

    public static synchronized void remove(EarthworksSpec.Kind kind, String name) {
        forKind(kind).remove(name);
        save();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("station_announcer").resolve("earthworks_presets.json");
    }

    private static Map<String, Map<String, String>> load() {
        try {
            Path path = path();
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) {
                    Map<String, Map<String, String>> loaded = GSON.fromJson(reader,
                            new TypeToken<LinkedHashMap<String, LinkedHashMap<String, String>>>() {
                            }.getType());
                    if (loaded != null) {
                        return new LinkedHashMap<>(loaded);
                    }
                }
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not read earthworks presets", e);
        }
        return new LinkedHashMap<>();
    }

    private static void save() {
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(path)) {
                GSON.toJson(presets, writer);
            }
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not save earthworks presets", e);
        }
    }
}
